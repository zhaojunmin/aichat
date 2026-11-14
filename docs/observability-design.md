# 可观测性设计:日志、错误码与诊断信息

> 状态:**设计稿,待评审**。对应 `docs/quality-attributes.md` §2.7(可观测性)和 §3 第 7 条(异常被静默吞掉)。
>
> 最后更新:2026-11-15

---

## 0. TL;DR

1. **问题**:用户那边出了问题,我们拿不到任何信息。全仓库只有 3 处 `Log` 调用;27 处 `catch` / `runCatching` 里,大多数把异常直接吞掉。测试机(荣耀)还会屏蔽 release 包的 logcat,连上 adb 也看不到日志。
2. **方案**:新增包 `com.octos.aichat.diagnostics`,内容如下:
   - **错误码**:每个失败点一个稳定编号,如 `AI-108`、`BUB-501`。
   - **统一记录入口** `DiagLog`:只接受错误码和结构化字段,**接口上就不提供传文本的参数**,从源头保证不会记下聊天内容。
   - **本地环形日志**:放在 `files/diagnostics/`,有大小上限,写盘在后台线程。
   - **上次退出原因**:用系统的 `ApplicationExitInfo` 读出上次进程是崩溃、ANR 还是被系统杀掉的。这样不依赖 logcat 也能知道。
   - **导出**:「高级设置 → 导出诊断信息」,先给用户预览全文,再由用户自己分享或复制。
3. **隐私红线**:不记聊天文本、回复、记忆内容、API Key、Base URL,也不记**异常的 message**(服务端返回的错误里可能回显 prompt)。不接任何第三方崩溃上报 SDK,数据不会自动离开手机。
4. **性能约束**:读屏热路径上,记录一条只是一次内存写入(无 IO、无锁竞争)。写文件只在后台线程上做。

---

## 1. 现状

| 问题 | 位置 | 后果 |
|---|---|---|
| 异常被静默吞掉 | 27 处 `catch` / `runCatching`(清单见 §8) | 失败没有任何痕迹 |
| `startForeground` 失败被吞掉 | `bubble/FloatingBubbleService.kt` `startForegroundNotice` | Android 12+ 后台启动前台服务会被系统拒绝。「悬浮球不见了」最可能的原因之一,现在完全不可见 |
| 异常 message 直接显示在界面上 | `ai/embedding/Encoder.kt` 的 `status` 字符串,由 `AdvancedScreen` 显示 | 文案不可控,也没法统计 |
| 依赖 logcat | `ChatAccessibilityService`、`SettingsRepository` 的 3 处 `Log.w` | release 包在部分 ROM 上看不到 |
| 不知道进程为什么死 | — | 崩溃、ANR、被系统杀掉,事后都无从判断 |

已有的、**不在本设计范围内**的机制:
- `Tracing`:开发时用 atrace/Perfetto 测性能的打点(见 `docs/performance-testing.md`)。行为不变,只是挪进新包。
- `AiException.Kind`:AI 请求失败的分类,界面提示由 `ui/AiErrorMessages.kt` 负责。保留不动,错误码直接由它映射出来。

---

## 2. 目标与非目标

**目标**
- 用户报告问题时,一份诊断信息能回答三个问题:**发生了什么、多频繁、卡在哪一步**。
- 诊断信息里**不含任何聊天内容和密钥**,并且由测试来保证这一点。
- 读屏热路径**零新增开销**(见 §5.3)。
- 核心逻辑不依赖 Android 类型,能在 JVM 上做单元测试。

**非目标**
- 远程上报、崩溃 SDK、自动上传:与「聊天内容不出设备」的定位冲突。
- 用户行为分析、埋点。
- 记录完整的文本日志。

---

## 3. 隐私规则

**允许记录**(白名单):

| 字段 | 例子 |
|---|---|
| 错误码、级别、时间戳 | `AI-108 ERROR 2026-11-15 10:21:03` |
| 耗时(毫秒) | `ms=1602` |
| HTTP 状态码、平台错误码 | `http=402`、`screenshot=3` |
| 异常的**类名**(短名) | `cause=SocketTimeoutException` |
| 计数 | `AI-108 × 12` |
| App 与设备信息 | 版本、构建类型、机型、Android 版本、ABI、系统语言 |
| 设置里的**枚举和开关** | 推理模式、云端后端名、本地模型 id、OCR 开关、记忆开关 |
| 权限状态 | 无障碍已开启 / 已绑定、悬浮窗、通知 |
| 进程退出原因 | `ApplicationExitInfo` 的 reason 和时间 |

**禁止记录**:
- 聊天文本、生成的回复、记忆内容、人设 prompt
- API Key、Base URL(可能是自建地址)、模型名之外的配置文本
- **异常的 message**:服务端的错误返回里可能回显请求内容,本地异常里可能带文件路径或用户输入
- 前台 App 的包名序列(能看出用户什么时候在用哪个 App)。只在出错的那条记录里带上**当时的**目标包名,用于判断是哪个 App 的解析出了问题

**怎么保证**:
1. `DiagLog` 的接口**只接受**错误码、数字和 `Throwable`;`Throwable` 进去之后只保留类名(和可选的调用栈帧,见 §11 待定问题 1),**不读 message**。调用方无法传入任意字符串。
2. 单元测试用带「哨兵文本」的异常和设置去记录、导出,断言哨兵文本没有出现在导出结果里(§9)。

---

## 4. 错误码

### 4.1 格式与规则

- 格式:`<域>-<三位数字>`,例如 `AI-108`。百位数字对应域,方便肉眼区分。
- **编号一经发布永不复用、不改含义**。新错误只能追加;废弃的编号在表里标注「已废弃」,保留不删。
- 每个错误码固定一个级别:`ERROR`(功能失败)、`WARN`(已降级,功能还能用)、`INFO`(值得统计的事件)。

| 域 | 百位 | 范围 |
|---|---|---|
| `AI` | 1xx | 回复生成(云端请求、结果解析) |
| `LLM` | 2xx | 端侧推理引擎、模型下载 |
| `A11Y` | 3xx | 无障碍读屏、截图、填入 |
| `OCR` | 4xx | 截图文字识别 |
| `BUB` | 5xx | 悬浮窗、前台服务 |
| `MEM` | 6xx | 记忆记录、学习 |
| `EMB` | 7xx | 向量模型 |
| `SET` | 8xx | 设置、密钥存储 |
| `APP` | 9xx | 进程级事件:崩溃、退出原因 |

### 4.2 初版错误码表

| 码 | 级别 | 含义 | 来源 |
|---|---|---|---|
| `AI-101` | ERROR | Key 无效或无权限 | `AiException.Kind.UNAUTHORIZED` |
| `AI-102` | ERROR | 余额不足 / 欠费 | `INSUFFICIENT_BALANCE` |
| `AI-103` | ERROR | 限流 | `RATE_LIMITED` |
| `AI-104` | ERROR | 地址或模型不存在 | `NOT_FOUND` |
| `AI-105` | ERROR | 请求被拒 | `BAD_REQUEST` |
| `AI-106` | ERROR | 服务端错误 | `SERVER` |
| `AI-107` | ERROR | 云端超时 | `TIMEOUT` |
| `AI-108` | ERROR | 网络不可用 | `NETWORK` |
| `AI-109` | ERROR | 返回内容不可用 | `BAD_RESPONSE` |
| `AI-110` | ERROR | 其他失败 | `OTHER` |
| `LLM-201` | ERROR | 本地推理超时 | `LOCAL_TIMEOUT` |
| `LLM-202` | ERROR | 本地模型加载失败 | `LlmEngine.ensureLoaded` 返回 false |
| `LLM-203` | WARN | 原生推理库不可用(非 arm64) | `LlmEngine` 初始化 |
| `LLM-204` | ERROR | 模型下载失败 | `ModelStore.download` |
| `A11Y-301` | WARN | 处理无障碍事件时出异常(已兜底) | `ChatAccessibilityService.guarded` |
| `A11Y-302` | ERROR | 点击时解析聊天失败 | `readConversation` |
| `A11Y-303` | WARN | 点击时找不到聊天窗口 | `windowRoot` 返回 null |
| `A11Y-304` | ERROR | 截图失败(带平台错误码) | `captureScreenshot` |
| `A11Y-305` | WARN | 自动填入失败,已改为复制 | `pasteText` 返回 false |
| `OCR-401` | ERROR | 文字识别失败 | `OcrReader.recognize` |
| `OCR-402` | WARN | 识别成功但没读出消息 | 解析结果为空 |
| `BUB-501` | ERROR | 启动前台服务失败 | `startForegroundNotice` |
| `BUB-502` | WARN | 移除悬浮窗失败 | `removeView` |
| `MEM-601` | ERROR | 学习失败(已回滚,下次重试) | `MemoryRepository.runLearning` |
| `MEM-602` | WARN | 学习器返回不可用结果 | `learnMemory` 返回 null |
| `MEM-603` | ERROR | 回复日志写入失败 | `ReplyLog.append` |
| `EMB-701` | WARN | 向量原生库载入失败 | `Encoder.loadLibs` |
| `EMB-702` | WARN | 向量模型或词表资源拷贝失败 | `ModelAssets` |
| `EMB-703` | WARN | 向量模型加载失败 | `Encoder.ensureLoaded` |
| `EMB-704` | WARN | 单次向量计算失败 | `Encoder.embed` |
| `SET-801` | ERROR | API Key 加密失败,未保存 | `SettingsRepository.encodeApiKey` |
| `SET-802` | WARN | API Key 无法解密,已清空 | `SecretCodec.decode` |
| `APP-901` | ERROR | 未捕获异常(进程即将崩溃) | 全局异常处理器 |
| `APP-902` | INFO | 上次进程的退出原因 | `ApplicationExitInfo` |

---

## 5. 架构

### 5.1 组件

```
app/src/main/java/com/octos/aichat/
  ChatAssistantApp.kt        新增 Application:初始化 DiagLog、装全局异常处理器、读上次退出原因
  diagnostics/
    ErrorCode.kt             错误码枚举:编号、级别、所属域
    DiagLog.kt               记录入口(对外唯一 API):内存环形缓冲 + 计数 + 耗时直方图
    DiagnosticsStore.kt      落盘:files/diagnostics/,追加写 + 按大小轮转,后台单线程
    DiagnosticsReport.kt     组装导出文本(纯函数,输入是快照)
    ExitReasons.kt           读 ApplicationExitInfo(API 30+)
    Tracing.kt               从包根目录挪进来,行为不变
  ui/
    AdvancedScreen.kt        「导出诊断信息」「清空诊断信息」
    DiagnosticsScreen.kt     预览全文 + 分享 + 复制
```

依赖方向:`diagnostics` 不依赖任何业务包(`ai`、`data`、`ui`……),业务包单向依赖它。`AiException.Kind → ErrorCode` 的映射放在 `ai` 包里,不让 `diagnostics` 反过来认识 `ai`。

### 5.2 数据流

```
调用点(27 处 catch、关键路径)
   │ DiagLog.record(ErrorCode.AI_108, cause = e, ms = 1602, http = 0)
   ▼
内存:环形缓冲(最近 200 条)+ 每个码的计数(AtomicIntegerArray)+ 耗时直方图
   │ 后台单线程,每 5 秒或攒满 20 条刷一次
   ▼
files/diagnostics/events.log   (满 256 KB 轮转为 events.1.log,只保留这两个文件)
files/diagnostics/summary      (计数和直方图的快照,重启后接着累加)
   │ 用户点「导出诊断信息」
   ▼
DiagnosticsReport → 预览页 → 用户分享 / 复制
```

**崩溃时**:全局异常处理器先**同步**写入一条 `APP-901`(进程马上要退出,不能交给后台线程),再交还给系统原来的处理器。系统的崩溃行为不变。

**上次退出原因**:每次进程启动时读 `ActivityManager.getHistoricalProcessExitReasons`(Android 11+),把新出现的记录写成 `APP-902`(崩溃、ANR、被系统内存回收杀掉、用户强行停止……)。这条路不依赖 logcat,也能捕捉到原生崩溃和 ANR,这两种是进程内的异常处理器抓不到的。

### 5.3 热路径约束

遵守「热路径性能优先于可测性」的约定:
- `record()` 只做内存操作:写环形缓冲的一个槽位,再给计数加一。不做 IO、不格式化字符串、不调用系统服务。
- 正常路径上**完全不调用** `record()`。只有出错时才调用,而出错是少数情况。
- 耗时直方图(§5.4)只在**点击路径**上记录,读屏的事件回调里不记。
- 写文件只在后台单线程上做,有频率限制。

### 5.4 耗时直方图

性能测试只能在开发机上跑。用户手机上实际有多慢,目前完全不知道。所以对几个关键操作按固定分桶计数:

- 记录的操作:点击到出结果、云端生成、本地生成、截图识别、点击时解析、一次学习。
- 分桶(毫秒):`<250、<500、<1000、<2000、<3000、<5000、<10000、<30000、≥30000`。
- 导出时给出每个桶的次数,以及据此估算的 P50 / P95。

### 5.5 落盘格式

一行一条,用制表符分隔,便于 grep 和脚本处理:

```
1763173263123	AI-108	ERROR	cause=UnknownHostException ms=31 pkg=com.tencent.mm
1763173301877	APP-902	INFO	reason=ANR at=1763170012000
```

字段固定为 `key=value`,值只能是数字、枚举名、类名或包名,由 `DiagLog` 生成,调用方无法写入自由文本。

---

## 6. 导出

**入口**:高级设置 → 「导出诊断信息」→ 预览页。预览页上**显示全文**,用户看过才分享,做到完全透明。按钮有两个:「分享」(系统分享面板,纯文本)和「复制」。另设「清空诊断信息」。

**内容**(纯文本,一般在 30 KB 以内):

```
== 聊天助手 诊断信息 ==
生成时间  2026-11-15 10:30:12
App       0.1.0 (1) release
设备      HONOR ELP-AN00 · Android 16 (API 36) · arm64-v8a · zh-CN

== 设置 ==
推理模式 cloud · 后端 DEEPSEEK · 本地模型 qwen2.5-1.5b · OCR 开 · 记忆 开 · 语义召回 开

== 权限 ==
无障碍 已开启/已绑定 · 悬浮窗 允许 · 通知 允许

== 上次退出原因(最近 5 次)==
2026-11-15 00:51:10  CRASH
2026-11-14 21:36:57  ANR

== 错误计数(自安装 / 自上次清空)==
AI-108  网络不可用          12
A11Y-304 截图失败            3

== 耗时 ==
点击到出结果  n=86  P50≈2.4s  P95≈5.1s
...

== 最近事件(200 条)==
2026-11-15 10:21:03  AI-108 ERROR  cause=UnknownHostException ms=31
...
```

错误码旁边附上简短的中英文说明。说明来自错误码表本身,不来自异常。

---

## 7. 与现有代码的关系

| 现有 | 处理 |
|---|---|
| `Tracing.kt`(包根目录) | 挪到 `diagnostics/`,只改 import |
| `AiException.Kind` | 不变;在 `ai` 包里加 `Kind.errorCode` 映射 |
| `ui/AiErrorMessages.kt` | 不变,仍然负责面向用户的提示 |
| `Encoder.status` 自由文本 | 改成记录 `EMB-70x`;高级设置页显示错误码和简短说明,不再显示异常 message |
| 3 处 `Log.w` | 改成 `DiagLog.record`。debug 构建里 `DiagLog` 同时输出到 logcat |

---

## 8. 接入清单

把 27 处 `catch` / `runCatching` 逐个归类。「保持」表示这是正常的控制流,不是故障。

| 位置 | 现在 | 改成 |
|---|---|---|
| `MemoryRepository.runLearning` `catch (_: Exception)` | 吞掉 | 记 `MEM-601`(带类名)后吞掉 |
| `ReplyLog.append` `runCatching { appendText }` | 吞掉 | 记 `MEM-603` |
| `ReplyLog.clear` `runCatching { delete }` | 吞掉 | 保持(删除失败下次会覆盖) |
| `CloudClient` 4 处 | 转成 `AiException` | 保持;由调用方按 `Kind` 记 `AI-1xx` |
| `AiService.learnMemory` `runCatching { complete }` | 返回 null | 记对应的 `AI-1xx` / `LLM-2xx`,再返回 null |
| `AiService` 2 处 `JSONException` | 退回宽松解析 | 保持(解析失败最终会体现为 `AI-109`) |
| `ModelStore.download` | 返回 `Result` | 失败时记 `LLM-204` |
| `SecretCodec.decode` | 解不开就返回 "" | 记 `SET-802` |
| `SettingsRepository` 加密失败 `Log.w` | logcat | 记 `SET-801` |
| `SettingsRepository` 推理模式枚举解析 | 默认值 | 保持 |
| `FloatingBubbleService` `removeView` | 吞掉 | 记 `BUB-502` |
| `FloatingBubbleService` 生成结果 `runCatching` | 显示错误 | 额外记 `AI-1xx` / `LLM-2xx` |
| `FloatingBubbleService.startForegroundNotice` | 吞掉 | **记 `BUB-501`(带类名)** |
| `BubblePanel` `removeView` | 吞掉 | 记 `BUB-502` |
| `LlmEngine` 载入原生库 | `available=false` | 记 `LLM-203` |
| `ModelAssets.copyAssetIfMissing` | 返回 false | 记 `EMB-702` |
| `ChatAccessibilityService.guarded` / `readConversation` 的 `Log.w` | logcat | 记 `A11Y-301` / `A11Y-302` |
| `ChatAccessibilityService` 截图 2 处 | 返回 null | 记 `A11Y-304`(带平台错误码) |
| `ChatAccessibilityService.imeBounds` | 返回 null | 保持(拿不到键盘位置就不裁剪) |
| `Encoder` 5 处 | 写 `status` 字符串 | 记 `EMB-701` / `703` / `704`;`release` 保持 |
| `HomeViewModel` 测试连接 | 显示错误 | 保持(用户主动测试,结果已显示在界面上) |

另外新增两个记录点:`pasteText` 返回 false 时记 `A11Y-305`;点击时找不到窗口记 `A11Y-303`。

---

## 9. 测试

全部是白盒测试,按第三阶段定下的布局放在 `app/src/test/.../diagnostics/`。

| 测试 | 内容 |
|---|---|
| `ErrorCodeTest` | 编号格式正确、没有重复、百位和所属域一致;**和已提交的码表快照比对,防止有人改了已发布编号的含义** |
| `DiagLogTest` | 环形缓冲满了之后覆盖最旧的一条;计数准确;直方图分桶和 P50 / P95 估算 |
| `PrivacyTest` | 用 message 里带哨兵文本(如 `SECRET-chat-text`、`sk-SENTINEL`)的异常去记录,同时把设置里的 Key 和 Base URL 设成哨兵,导出后断言**一个哨兵都不出现** |
| `DiagnosticsStoreTest`(Robolectric) | 落盘、按大小轮转、重启后计数接着累加 |
| `DiagnosticsReportTest` | 导出格式;空数据时也能正常生成 |

黑盒测试(`quality/reliability`):在崩溃用例里加一条断言,进程重启后诊断信息里有 `APP-901` 和 `APP-902 reason=CRASH`。

---

## 10. 实施步骤

| PR | 内容 |
|---|---|
| 1 | `diagnostics` 包核心:错误码、`DiagLog`、落盘、Application、全局异常处理器、退出原因;`Tracing` 挪包;单元测试 |
| 2 | 按 §8 接入 27 处;`Encoder.status` 换成错误码 |
| 3 | 导出:预览页、分享、复制、清空;隐私测试 |
| 4 | 耗时直方图;可靠性黑盒测试里加崩溃记录的断言 |

---

## 11. 待定问题(需要评审时决定)

1. **要不要记调用栈帧**。崩溃(`APP-901`)只有类名的话,很难定位到具体是哪一行。调用栈帧只有类名、方法名和行号,不含用户数据。但 release 包经过 R8 混淆,要配合每个版本的 `mapping.txt` 才能还原,这和 P2「归档 mapping.txt」是绑在一起的。**建议**:只给 `APP-901` 记前 8 帧,同时把 mapping 归档提前做。
2. **出错记录里要不要带目标 App 的包名**。带上才能知道是哪个 App 的解析坏了,但包名本身能看出用户在用什么 App。**建议**:只在 `A11Y-*`、`OCR-*` 的记录里带,并且导出预览里用户能看到。
3. **保留多少**:两个 256 KB 文件,大约 4000 条记录。**建议**先这样,不按时间删除。
4. **要不要给诊断日志加开关**。因为不含聊天内容,**建议默认开启、不提供开关**,只提供「清空」。
