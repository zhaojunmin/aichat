# 质量属性设计与工程质量方法

> 面向本 App(Android 读屏 + 悬浮窗 AI 聊天助手)。基本功能已完成,本文定义**要达到什么质量**(质量属性 + 可度量场景)
> 和**用什么工程手段保证**(测试、静态检查、CI、评审、发布)。结论都对照了当前代码,差距处标注文件位置。
>
> 最后更新:2026-11-14

---

## 0. TL;DR

1. **质量属性优先级**:隐私与安全 > 可靠性(读得准、不崩) > 性能(点球到出结果) > 可维护/可测试 > 兼容性(第三方 App 改版、设备 ABI) > 易用性 > 可观测性。
2. **最大的工程缺口**:仓库里**没有任何测试**(`app/src/test` 不存在)、**没有 CI**、**没有静态检查配置**。代码已经为可测性做了铺垫(`AiService` 注入 `clientFor`、`parseReplies`/`parseLearnResult` 是 `internal`、`SettingsStore`/`MemoryStore` 有接口),但没有用上。
3. **已发现的问题**(详见 §3):本地模式下记忆学习被云端 API Key 卡死;`allowBackup=true` 会把聊天记忆和 API Key 带进云备份;API Key 明文存储;错误被静默吞掉、无法定位;调试用的控件树抓取在生产环境的主线程上一直在跑。
4. **落地顺序**:P0 先建测试 + CI + Lint 的安全网,并修掉隐私类问题;P1 补性能预算和 LLM 输出回归评测;P2 做可观测性和发布流程。

---

## 1. 质量属性优先级(Utility Tree)

| 优先级 | 质量属性 | 为什么对本 App 重要 |
|---|---|---|
| H/H | **隐私与安全** | 读的是用户和第三方的私聊内容,还拿着无障碍 + 悬浮窗 + 截屏这类高敏权限。出一次事就失去用户信任。 |
| H/H | **可靠性 / 正确性** | 「我/对方」分错、读到键盘字母、读不到消息,回复就会答非所问。常驻的无障碍服务崩溃还会被系统禁用,需要用户手动重开。 |
| H/M | **性能** | 用户是在聊天中途点悬浮球,等超过 3 秒就会自己打字了。端侧模型还会占内存、耗电、发热。 |
| M/H | **可维护性 / 可测试性** | 项目定位是长期维护;解析规则、prompt、记忆策略都会频繁调整,没有回归测试就不敢改。 |
| M/H | **兼容性 / 可适配性** | 微信、QQ、Soul 改版会让 view-id 失效(QQ 的 `mjn` 是混淆 id);设备上有 arm64 与非 arm64、Android 8–16 的差别。 |
| M/M | **易用性** | 权限引导、失败时的提示、自动填入失败后回退到复制。 |
| M/L | **可观测性** | 现在出问题只能连 adb 复现,用户侧报上来的问题没法定位。 |

(H/M/L = 业务重要性 / 实现难度)

---

## 2. 质量属性场景与设计战术

每条场景都按「刺激 → 响应 → 度量」写成可以验证的目标。

### 2.1 隐私与安全

| # | 场景 | 目标(度量) | 战术 |
|---|---|---|---|
| S1 | 用户选了**本地模式** | 聊天内容 **0 字节**发往网络 | 已实现:`AiService.defaultClientFor` 本地未就绪时直接报错,不回退云端。**补一条单测锁住这个不变式。** |
| S2 | 手机做了云备份或换机迁移 | 记忆库、回复日志、API Key **不进备份** | `allowBackup=false`,或加 `dataExtractionRules` 排除 `databases/` 和 `shared_prefs/` |
| S3 | 设备被 root 或数据被导出 | API Key 不以明文存在 | `EncryptedSharedPreferences`,或用 Android Keystore 包一层 AES-GCM |
| S4 | 聊天 App 打开时无障碍事件持续触发 | 只在需要时把聊天文本留在内存里 | `captureTree` 改成仅在开启调试开关时运行;`DebugCapture.lastRawDump` 不在 release 版持有聊天原文 |
| S5 | 云端请求 | 只发 HTTPS;日志里不出现 Key 或聊天内容 | `network_security_config` 禁止明文流量;用统一日志门面做脱敏(见 §2.7) |
| S6 | 对方消息里夹带 prompt 注入(比如「忽略之前的指令」) | 回复只展示给用户,由用户手选;学习**只用「我」的消息** | 已基本满足(`recordMyMessages` 只取 `Speaker.ME`);如果把「我/对方」分错,对方的话会被写进记忆,所以靠 R1 的解析正确性兜底 |

### 2.2 可靠性 / 正确性

| # | 场景 | 目标 | 战术 |
|---|---|---|---|
| R1 | 在已支持 App 的标准聊天页点悬浮球 | 「我/对方」判定准确率 ≥ 95%(以固定样本集计) | 解析器输入抽象成纯数据(见 §4.2),用**脱敏后的 UI dump 做 golden 测试** |
| R2 | 第三方 App 改版导致 view-id 失效 | 不崩溃,自动降级到位置解析,并提示「解析规则可能过期」 | 已有降级逻辑;**补上降级时的提示和计数** |
| R3 | 模型返回非 JSON、带 markdown 围栏、空串或超长内容 | 依然能给出 1..n 条回复,或明确报错 | `parseReplies` 已经做了容错;**用表驱动单测覆盖各种畸形输入** |
| R4 | 网络超时或 4xx/5xx | 10 秒内给出可读的错误(区分 Key 无效、余额不足、超时),不留在一直转圈的状态 | `CloudClient` 按 HTTP 状态码映射成具体错误类型;总超时 ≤ 30 秒,现在 read 是 60 秒 |
| R5 | 学习过程中进程被杀 | 未学习的回复不丢,也不会重复学习 | 已实现(学习成功后才 `markLearned`);**补测试** |
| R6 | 本地模式且没有填云端 Key | 记忆照常学习 | **现在是坏的**,见 §3 问题 1 |
| R7 | 无障碍回调里抛出异常 | 服务不崩溃(服务一崩就会被系统禁用) | 在 `onAccessibilityEvent` 最外层加 try/catch 并记录;解析逻辑移出主线程 |

### 2.3 性能

| # | 场景 | 预算 |
|---|---|---|
| P1 | 控件树模式:点悬浮球 → 出现候选(云端) | P50 ≤ 2.5 s,P95 ≤ 5 s |
| P2 | OCR 模式下截屏加识别的额外开销 | ≤ 600 ms(骁龙 8s Gen3) |
| P3 | 本地 1.5B 模型生成 3 条回复 | ≤ 6 s;3B 模型 ≤ 10 s |
| P4 | 单个无障碍事件的主线程耗时 | ≤ 8 ms,不能造成聊天 App 掉帧 |
| P5 | 记忆召回(top-N,500 条记忆) | ≤ 50 ms(含 embedding) |
| P6 | 端侧模型常驻内存 | 切回云端后 30 s 内释放(`LlmEngine.unload`) |

战术:用 `Trace.beginSection` 给关键路径打点,在 `adb shell perfetto` 里看;P4 无障碍事件只做前台判断,`ChatParser.parse` 改为**点悬浮球时按需解析**(后台线程),聊天时不再逐事件遍历控件树;P5 和 Tokenizer 写 JVM 微基准。

### 2.4 可维护性 / 可测试性

| 目标 | 度量 |
|---|---|
| 纯逻辑可以在 JVM 上测 | `ai/`、`data/memory/MemoryRetrieval`、`ai/embedding/WordPieceTokenizer`、`VectorCodec`、`ChatParser` 核心算法**不依赖 Android 类型** |
| 覆盖率 | 纯逻辑包行覆盖率 ≥ 70%;UI 和 Service 不设硬指标 |
| 依赖方向 | `ui → ai/data`,`data` 不反向依赖 `ui`;`AccessibilityBridge` 这类进程级单例只在 Service 边界使用 |
| 静态检查 | Android Lint + detekt 零新增告警(先生成 baseline 接纳存量,只约束新代码) |

### 2.5 兼容性 / 可适配性

- **第三方 App 改版**:`APP_RULES`、`BUILTIN_OCR` 这些按包名的规则集中放在配置里。每次适配新版本时提交一份脱敏 dump 作为测试样本,规则一改,golden 测试就能发现回归。
- **ABI**:非 arm64 设备自动回退已经实现;在 CI 里加一个「`LlmEngine.isSupported=false` 路径」的单测。
- **系统版本**:minSdk 26。`takeScreenshot` 需要 Android 11+,已有判断;Android 12 及以下需要手动 `recycle()` `AccessibilityNodeInfo`,否则会泄漏(`findEditable` 和 `captureTree` 都没有回收)。

### 2.6 易用性

- 失败时都要给出可以操作的下一步,比如「Key 无效 → 去设置」「模型未下载 → 去下载」,不要只显示异常的 message。
- 自动填入失败时回退到复制,这一点已经实现。

### 2.7 可观测性(隐私优先)

> 详细设计见 [`observability-design.md`](observability-design.md)。

- 写一个统一的 `Logger` 门面:debug 版输出到 logcat;release 版只把**错误码和耗时**写进本地环形文件(不含聊天文本和 Key)。
- 设置页加「导出诊断信息」按钮,由用户主动分享,**不接第三方崩溃 SDK**(与隐私定位冲突)。
- 把现在的 `catch (_: Exception) {}` 改成「记录错误码再降级」。

---

## 3. 现状差距(按严重度)

> 进度(2026-11-14):1 已修(#15);2、4、5、6 已修(#16);3 已修(#18,改用 Keystore,未用已弃用的 EncryptedSharedPreferences);10 已建(#14);云端错误分类与超时(R4)见 §2.2;R3 畸形输出、R5 学习失败重试、本地超时映射已补单测(`MemoryLearningPassTest`、`LocalClientTest`、`AiServiceTest`)。 R5 学习进度与记忆写入已放进同一个 Room 事务(数据库 v5),并补上事务回滚、v1–v4 迁移、`ReplyLog` 的 Robolectric 测试。

| # | 问题 | 位置 | 影响 | 建议 |
|---|---|---|---|---|
| 1 | 本地模式下记忆学习永远不运行:`runLearning` 检查的是**云端** `apiKey`,首页「立即学习」按钮也一样 | `data/memory/MemoryRepository.kt` `runLearning`;`ui/HomeScreen.kt:307` | 纯本地用户的记忆功能实际失效 | 判断条件改成「`useLocal` 时看 `LlmEngine.isReady`,否则看 `apiKey`」,并把这个判断收敛成 `AppSettings` 上的一个方法 |
| 2 | `android:allowBackup="true"` | `AndroidManifest.xml` | 记忆库和 API Key 会进入云备份 | 关闭,或用备份规则排除 |
| 3 | API Key 明文存在 SharedPreferences | `data/SettingsRepository.kt` | root 或备份后会泄露 | EncryptedSharedPreferences |
| 4 | `captureTree` 在生产环境对每个目标 App 事件都会跑(1.5 s 节流),并且在静态变量里持有聊天原文 | `accessibility/ChatAccessibilityService.kt` | 主线程开销,原文常驻内存 | 只在调试开关打开时运行 |
| 5 | 解析在无障碍主线程上同步执行,且没有异常保护 | 同上 `onAccessibilityEvent` | 聊天 App 可能掉帧;一旦抛异常,服务会被系统禁用 | 防抖、移到后台线程、最外层 try/catch |
| 6 | 结果面板展示的「对方最后一句」读的是 `AppBus.conversation`,不是本次解析出来的 `conv` | `bubble/FloatingBubbleService.kt:239` | OCR 路径和异步时序下可能对不上 | 改用局部变量 `conv`(已修;`AppBus` 本身已在 #45/#46 拆除,见 §4.4) |
| 7 | 异常被静默吞掉 | `MemoryRepository`、`ChatAccessibilityService` 等多处 | 出问题时无从定位 | 统一日志门面 + 错误码 |
| 8 | Android 12 及以下没有回收 `AccessibilityNodeInfo` | `findEditable`、`captureTree` | 老设备上内存泄漏 | 在 `use {}` 风格的扩展函数里统一回收 |
| 9 | 端侧推理没有超时,也不能取消 | `ai/local/LlmEngine.kt` `chat` | 模型卡住时 mutex 一直不释放,后续请求全部阻塞 | `withTimeout`,并在 native 层加 abort 标志 |
| 10 | 没有测试、CI、静态检查 | — | 每次重构都只能靠人工上机验证 | 见 §4 |

---

## 4. 工程质量方法

### 4.1 测试金字塔

```
          ┌──────────────┐  真机冒烟清单(手工,每次发布前做一遍,约 10 分钟)
          │   E2E / 手工  │  微信 OCR、QQ 控件树、本地模式、自动填入
         ┌┴──────────────┴┐
         │  集成测试        │  Room DAO(Robolectric 或 instrumented)、MemoryRepository 加假 AiClient
        ┌┴────────────────┴┐
        │  JVM 单元测试      │  ← 主力:快、在 CI 里跑
        └──────────────────┘
```

**第一批单测(投入产出比最高)**:

| 对象 | 要测什么 |
|---|---|
| `AiService.parseReplies` / `parseLearnResult` | 表驱动:纯 JSON 数组、带围栏、带前后废话、编号列表、空串、非法 id、未知 category |
| `AiService.generateReplies` | 注入假 `AiClient`,检查 prompt 里包含记忆块,并且不包含「我」在末尾连发的消息 |
| `AiService` 本地模式 | **本地未就绪时抛异常、绝不调用云端 client**(锁住 S1) |
| `MemoryRetrieval` | 三因子打分的排序、词法回退、空记忆 |
| `WordPieceTokenizer` / `VectorCodec` | 与 HF tokenizer 的对齐样例;编码和解码互逆 |
| `Conversation` | `withoutTrailingMine`、`lastFromOther`、`toTranscript` |
| `ChatParser` | golden 测试(见 4.2,方案待定) |

依赖:`junit`、`kotlinx-coroutines-test`、`truth`(或 kotest 断言);`org.json` 在 JVM 上需要额外加 `testImplementation("org.json:json")`。

### 4.2 解析器 golden 测试(专门解决「读得准」)

`ChatParser` 现在直接吃 `AccessibilityNodeInfo`,在 JVM 上没法测。

> **已否决:整树快照(PR #17)**。先把 `AccessibilityNodeInfo` 整棵树复制成纯数据 `UiNode` 再解析,会让每次解析都对所有节点取全部字段、分配对象;而原实现只在命中规则的节点上取值。无障碍解析是常驻热路径,**为可测性做的改造不能增加运行时开销**。

可选做法(未实施):

1. 定义一个很薄的节点**接口**(id、文本、边界、子节点按需获取),真机上直接包装 `AccessibilityNodeInfo`、用到才取,测试里用 XML 实现同一接口。运行时只多一层接口调用,不复制数据。
2. 解析算法只依赖这个接口。
3. 把 `uiautomator dump` 出来的 XML **脱敏**(用占位文本替换真实聊天内容)后放到 `app/src/test/resources/dumps/<pkg>/<case>.xml`,同时放一份期望输出 `<case>.expected.json`。
4. 每适配一次 App 新版本就加一个样本。

> 注意:以前调试时随手留在仓库根目录的 dump(含真实聊天原文)不适合直接当样本,必须先脱敏。

### 4.3 LLM 输出质量回归(Prompt 评测)

prompt 和人设会频繁调整,需要一个离线评测集:

- `eval/replies/*.json`:30–50 段脱敏对话 + 人设,覆盖中文、英文、末尾是自己发言、空对话等情况。
- 一个 JVM 或脚本 runner,对指定后端跑一遍,检查**硬约束**:能解析成功、条数 = n、每条 ≤ 40 字、语言与对方一致、不复述对方原话。
- 软质量(语气是否自然)人工抽查或用 LLM 打分,结果存档做对比。
- **不在 CI 里跑**(要花钱、结果不稳定);改 prompt 的 PR 要求附上评测结果。

`docs/embedding-benchmark/` 已经是这种模式,可以照搬。

### 4.4 静态检查

- **Android Lint**:`lint { warningsAsErrors = false; abortOnError = true; baseline = file("lint-baseline.xml") }`,重点打开 `HardcodedText`、`UnusedResources`、`MissingPermission` 等检查。
- **detekt**(带 formatting 规则):**暂缓**。detekt 1.x 基于 Kotlin 2.0,与 AGP 9 内置的 Kotlin 2.2 冲突;等 detekt 2.0 正式版支持 K2 后再接,同样用 baseline 接纳存量。
- 第一步不必追求清零告警,先做到**不新增**。
- **架构与命名规则(Konsist)**:`quality/architecture` 模块把约定写成单测,读 `app` 源码检查,CI 的 style 任务里运行(`./gradlew :quality:architecture:test`)。
  - 包依赖白名单(`PackageDependencyRulesTest`):`diagnostics` 不依赖任何包;`debug`(开发者工具状态)只用 `data`;`accessibility` 只用 `data`/`diagnostics`/`debug`;`ocr` 只取 `MessageBounds`;`capture`(点击时读取会话)只经 `ScreenAccess` 访问读屏;`bubble` 经 `capture` 读屏、只从读屏取 `AccessibilityBridge`,只从 `ui` 取主题、`MainActivity`、错误文案;`ui` 不碰 `ocr`;根包只放 `ChatAssistantApp`。
  - 已知循环 `ai` ⇄ `data.memory`:`ai` 解析学习结果用到 `MemoryDraft` 等 4 个类型,以白名单形式冻结,不许再扩大;把这些结果类型移出 `data.memory` 即可消除。
  - 命名(`NamingRulesTest`):Bubble 只指悬浮球;`*ViewModel` 在 `ui` 且继承 androidx `ViewModel`,每个 `*Screen` 有对应 ViewModel;`*Repository` 只在 `data` 且实现同名 `*Store`;`AiClient` 实现叫 `*Client`;禁用 `Impl`/`Manager`/`Helper`/`Util(s)` 后缀;只有 `ChatAssistantApp` 可用 `android.util.Log`;`@Composable` 只在 `ui`。
- 「依赖有新版本」类检查(`GradleDependency`、`NewerVersionAvailable`、`AndroidGradlePluginVersion`)已关闭:它们的结果随联网和日期变化,与代码无关,会让 CI 结果漂移。依赖升级单独跟踪。
- baseline 里有一条 `NewApi` 错误(`captureScreenshot` 需要 API 30),实际调用处已有 `SDK_INT >= R` 判断,是 Lint 跟不进条件分支导致的误报。
- **Compose 检查(compose-lints)**:Slack 的 `compose-lint-checks` 以 `lintChecks` 挂在 `:app:lintDebug` 上,规则全开、无 baseline,和其他 Lint 问题一样会让构建失败。
  - 它基于较新的 Kotlin 分析 API,AGP 9.0 自带的 Lint 会以「references invalid APIs」把它整体跳过。`gradle.properties` 用 `android.experimental.lint.version=9.5.0` 只把 Lint 引擎换成 AGP 9.5 的,构建仍用 AGP 9.0;升级 AGP 到 9.5+ 后删掉这行。
  - 新引擎会报 `OldTargetApi`(结果随 Lint 版本变化,与代码无关),和上面的版本类检查一样关闭;targetSdk 升级单独跟踪。
  - 接入时修掉的存量:4 个界面在函数体里 `viewModel()`(`ComposeViewModelInjection`),改为参数默认值 `vm: XViewModel = viewModel(...)`,依赖显式可见,预览和测试可以直接传入。

### 4.5 CI(GitHub Actions)

`.github/workflows/ci.yml`,每个 PR 和推送到 main 时运行:

```
checkout(lfs: false) → setup JDK 17 → gradle cache
→ ./gradlew :app:testDebugUnitTest :app:lintDebug detekt :app:assembleDebug
→ 上传测试报告 / lint 报告
```

- `*.so` 和 `*.mnn` 在 LFS 里,单测和编译用不到;CI 不拉 LFS,可以省流量配额。
- main 分支打开分支保护,要求 CI 通过才能合并。按照之前的约定,**所有 PR 的 base 都设为 main**。

### 4.6 评审与完成定义(DoD)

每个 PR 合并前需要满足:

- [ ] CI 通过(测试、Lint、detekt、编译)
- [ ] 改了解析规则就附上新的 golden 样本;改了 prompt 就附上评测结果
- [ ] 改了 Manifest、权限、Service 或原生库就做真机冒烟测试
- [ ] 不在日志里打印聊天内容或 Key
- [ ] 命名、分层符合现有约定(SRP、接口在 `data` 层、每屏一个 ViewModel)

### 4.7 发布质量

- `versionCode` / `versionName` 从 git tag 推导,不再手动维护。
- 保存每次 release 的 R8 `mapping.txt`(作为 GitHub Release 的附件),否则崩溃堆栈没法还原。
- 发布前过一遍真机冒烟清单(放在 `docs/release-checklist.md`)。

---

## 5. 路线图

| 阶段 | 内容 | 产出 |
|---|---|---|
| **P0 安全网 + 隐私修复** | 测试基础设施、第一批 JVM 单测;CI;Lint baseline(detekt 暂缓,见 §4.4);修复 §3 的 1、2、4、5、6 | `app/src/test/**`、`.github/workflows/ci.yml`、几个 bugfix PR |
| **P1 正确性与性能** | 解析器 golden 测试(零开销方案,待定);Key 加密存储;CloudClient 错误分类与超时;LlmEngine 超时;关键路径打点 + 性能预算核对;prompt 评测集 | `eval/`、性能实测记录 |
| **P2 可观测性与发布** | Logger 门面 + 诊断导出;版本号自动化;mapping 归档;发布清单 | `docs/release-checklist.md` |
