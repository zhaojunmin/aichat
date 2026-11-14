# Android 性能测试

三类测试,都在**真机**上跑(CI 没有设备,只做编译检查):

| 类型 | 位置 | 测什么 | 被测构建 |
|---|---|---|---|
| 微基准 | `app/src/androidTest/.../benchmark/HotPathBenchmarks.kt` | 回复路径上的纯计算:记忆召回、bge 查询向量、分词、向量解码、模型输出解析(查询按 `MemoryInjector` 的做法取最近 12 行聊天) | `benchmark` 构建类型(release 配置、不混淆、不可调试) |
| 宏基准 | `quality/macrobenchmark/` | 冷/热启动耗时、首页滚动帧时间 | release(R8 混淆后的正式包) |
| 读屏/悬浮球端到端 | 见下文「端到端」 | 无障碍事件处理、点悬浮球到出结果 | release |

## 运行

```bash
export JAVA_HOME=/path/to/jdk-17 ANDROID_HOME=$HOME/Android/Sdk
ADB=adb.exe quality/scripts/run-benchmarks.sh          # all;也可 micro / macro
# 原始输出:build/benchmark-results/{micro,macro}.txt
```

脚本做的事和要注意的点:

- **用正式版签名打 `benchmark` 包**(本机有 `local.properties` 签名配置时),覆盖安装不会清掉 App 数据(API Key、记忆、已下载的本地模型)。没有签名配置时退回 debug 签名,此时必须先卸载正式版,**数据会丢**。
- **微基准前先 AOT 编译**:`cmd package compile -m speed -f com.octos.aichat`。否则基准库报 `NOT-AOT-COMPILED` 拒绝运行(平时由基准 Gradle 插件代劳,这里用 `adb.exe` 手动跑,所以自己做)。
- **宏基准会关掉无障碍服务**:冷启动要 `force-stop` App,而这台手机的系统会把被强制停止的 App 从已启用的无障碍服务里移除。脚本跑完会用 `settings put secure enabled_accessibility_services` 恢复。手动跑宏基准后也要记得恢复。
- 跑完把手机恢复成正式版,卸载测试 APK,删除 `/sdcard/Android/media/` 下的 trace。
- Gradle 的 `connected*` 任务看不到 WSL 里经 Windows `adb.exe` 连接的设备,所以脚本用 `am instrument` 直接跑。

### 为什么微基准放在 app 的 androidTest 里

官方建议把微基准放在独立的库模块里。本项目只有一个 app 模块,被测代码都在里面,拆库改动太大,所以:

- `testBuildType = "benchmark"`:基准不能跑在可调试的构建上(JIT 和调试路径会扭曲耗时);
- `gradle.properties` 里设了 `android.onlyEnableUnitTestForTheTestedBuildType=false`:AGP 9 默认只给 `testBuildType` 生成单元测试任务,不设的话 `testDebugUnitTest` 会消失,CI 和本地检查命令都会失败;
- `benchmark` 构建类型额外依赖 `benchmark-common`:基准运行器会先在**被测 App 进程**里启动 `IsolationActivity`,这个 Activity 必须在 App 自己的 Manifest 里(只加在 benchmark 构建类型,正式版不带)。

## 基线(2026-11-14,骁龙 8s Gen3 / Android 16,充电中)

### 微基准(中位数)

| 基准 | 耗时 | 每次分配 | 对照预算 |
|---|---|---|---|
| `vectorDecode_512` | 1.5 µs | 4 | |
| `parseReplies` | 1.2 µs | 15 | |
| `parseLearnResult` | 42 µs | 116 | |
| `tokenize_query`(12 行聊天) | 0.05 ms | 1,318 | |
| `recall_lexical_500`(500 条记忆) | 2.1 ms | 35,501 | P5 召回 ≤ 50 ms ✅ |
| `recall_semanticScoring_500`(打分,不含模型) | 2.3 ms | 13,095 | |
| `embedQuery_bgeModel`(bge 前向,12 行查询) | **52 ms** | 1,515 | 语义召回合计约 55 ms,略超 P5 ⚠️ |

**结论**:召回打分本身很快(约 2 ms);语义召回的耗时几乎全在 bge 模型给查询算向量,合计约 55 ms,略超预算。可选的改进:进一步缩短查询,或在用户点悬浮球之前提前算好。

> 更正:第一版基准误用了 30 行查询,测出 93 ms;实际代码只取 12 行,以上为修正后的数据。

### 宏基准

| 场景 | 结果 |
|---|---|
| 冷启动 `timeToInitialDisplay` | 中位数 **330 ms**(310–406) |
| 热启动 `timeToInitialDisplay` | 中位数 **83 ms**(63–106) |
| 首页滚动(每次约 100 帧) | CPU 帧耗时 P50 5.9 / P99 8.8 ms;帧超时 P99 −3.8 ms(没有超时帧) |

**测宏基准时发现**:Compose 只间接带进 `profileinstaller` 1.3.1,它不支持 Android 14 及以上,侧载安装时启动优化配置(baseline profile)装不上。已显式依赖 1.4.1;上面的启动数据是修复后测的。

## 端到端(读屏 / 悬浮球)

关键路径上有 `Trace` 打点(`app/.../diagnostics/Tracing.kt`),在正式版上用 atrace 抓取:

```bash
ADB=adb.exe quality/scripts/trace-e2e.sh 60      # 录 60 秒,期间在手机上操作
python3 -I quality/scripts/trace_summary.py build/benchmark-results/e2e-trace.txt   # 重新汇总
```

| 区段 | 线程 | 含义 |
|---|---|---|
| `a11y.onEvent` | 主线程 | 每个无障碍事件的处理(预算 P4 ≤ 8 ms) |
| `a11y.parse` | IO | 点悬浮球时按需解析控件树 |
| `bubble.tapToResult` | 异步 | 点悬浮球 → 出结果(预算 P1),下分 `resolveConversation` / `ocr` / `memoryBlock` / `generate` |
| `llm.chat` | 推理线程 | 一次端侧推理 |

打点在没抓 trace 时只是一次已缓存的标签检查,没有日志、没有分配。**在云端模式下点悬浮球,会把当前聊天发给 AI 服务**(和正常使用一样),请在可以测试的聊天里操作。

### 无障碍事件(2026-11-14,Soul 聊天页,上下各滑 4 次)

| | 事件数 | P50 | P95 | 最大 |
|---|---|---|---|---|
| 优化前 | 30–42 | 7.1–8.1 ms | 17–20 ms | 25 ms |
| 优化后 | **0** | — | — | — |

- 细分打点显示,优化前约 95% 的时间花在 `rootInActiveWindow` 上:一次跨进程调用,要由聊天 App 的主线程来处理,对方在滑动时就要排队。关掉节点预取(API 33 的 `getRootInActiveWindow(0)`)没有改善,开销主要在往返和排队上。
- 优化:同一个前台 App 发来的滚动或内容变化事件不可能改变前台,直接跳过(`isRoutineUpdateOfForegroundApp`)。窗口切换、来自其他包的事件(输入法、系统界面)、调试采集开启时,仍然走完整检查。
- 回归验证:回到桌面时悬浮球隐藏、回到 Soul 时重新显示;弹出和收起输入法时一直可见;切换 App 时事件照常处理(P50 2.3 ms)。

### 点悬浮球 → 出结果

待测:需要人在一个可以测试的聊天里手动点几次悬浮球(上面的脚本会记录下来)。自动点击容易点到回复面板上,误触发「选中回复」。
