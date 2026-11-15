# 聊天助手 · AI 回复助手 (Android)

在微信、QQ、Soul 等第三方聊天 App 里,用 AI 辅助生成回复。通过 **无障碍服务读屏**(或 **截图 OCR**)读取当前聊天界面,点击 **悬浮球** 即可生成多条回复候选,一键复制或自动填入输入框。支持 **云端 / 端侧本地** 两种推理,并带一个 **越用越懂你的专属记忆**。

> ⚠️ 仅供学习与个人使用。读屏/悬浮窗能力强大,请勿用于侵犯他人隐私、骚扰或违反相关 App 用户协议的场景。

## 功能特性

- **多后端**:云端 OpenAI 兼容接口(DeepSeek / 豆包),或 **端侧本地模型**(MNN + Qwen2.5,聊天内容不出设备)。
- **两种读屏**:默认走无障碍控件树;读不到文字的 App 走 **截图 OCR**(ML Kit 中文识别)。按 App 名单分流:微信、陌陌内置走 OCR,可在高级设置追加;每个 App 怎么读集中登记在 `data/SupportedApps.kt`。
- **专属记忆**:记录你发出的回复,LLM 定期蒸馏成稳定的「画像 / 事实 / 偏好 / 风格」,回复生成时按相关性注入,让建议更像你本人。
- **语义召回**:记忆检索用端侧 bge-small-zh 向量做语义匹配(离线、隐私),不就绪时自动回退本地关键词匹配。
- **人设/场景**:恋爱、破冰、商务、客服等预设,或自定义语气。
- **自动填入**:选中的回复自动复制;可选无障碍 `ACTION_SET_TEXT` 直接写进输入框。
- **主题外观**:纸 / 雾 / 月白(浅色)、蓝夜 / 墨 / 黛青(深色)或跟随系统;悬浮球与建议面板同步使用当前主题配色,切换主题或系统明暗时实时生效。
- **诊断信息**:关键失败按错误码记录到本地,可在 App 内预览、复制、分享或清空,便于排查问题。

## 工作原理

```
第三方聊天 App(微信 / QQ / Soul / Hertz / 陌陌,读取方式见 SupportedApps)
        │ 屏幕内容
        ▼
ChatAccessibilityService(只做回调分发)
        │ 每个事件:ForegroundTracker 判断前台 App ──► AccessibilityBridge.foreground ──► 悬浮球显示 / 隐藏
        │ 连接期间:经 AccessibilityBridge.screen 发布 ScreenAccess(读聊天 / 截图 / 键盘区域 / 回填)
        │
        │ 点悬浮球时:ChatCapture(只依赖 ScreenAccess 接口)
        ├─► ChatParser(控件树)──────┐
        └─► 截图 + OcrReader(OCR)──┴──► Conversation
                                              │
                                              ▼
                     SuggestionGenerator.suggestFor(记录我最后一句 → 取记忆块 → 生成)
                          │                      ▲
                          │        MemoryRepository / MemoryPromptBuilder(三因子召回:
                          │        recency + importance + relevance,relevance = 词法 / bge 向量)
                          ▼
                     AiClient(ai 包对外唯一入口)
                          ├─► CloudClient(DeepSeek / 豆包,OpenAI 兼容)
                          └─► LocalClient(MNN + Qwen2.5,隐私;自己准备模型,
                                          不支持 / 未下载 / 加载失败都以 AiException 返回)
        ▼
FloatingBubbleService(单实例,由 ChatAssistantApp 随无障碍连接创建/销毁;
                      FloatingBubble 悬浮球 + BubblePanel 建议面板)
        │ 选中一条
        ├─► 复制到剪贴板
        └─►(可选)ScreenAccess.fill(ReplyFiller,无障碍 ACTION_SET_TEXT)填入输入框
```

## 核心模块

| 模块 | 文件 |
|---|---|
| 读屏服务(只做回调分发与装配) | `accessibility/ChatAccessibilityService.kt` |
| 对外能力与连接发布 | `accessibility/ScreenAccess.kt`(连接期间可用的读屏 / 截图 / 回填接口)、`AccessibilityBridge.kt`(用 StateFlow 发布当前连接与前台 App) |
| 前台判断 / 读聊天 / 自动填入 / 调试抓树 | `accessibility/ForegroundTracker.kt`、`ChatWindowReader.kt`、`ReplyFiller.kt`、`RawTreeDumper.kt` |
| 点击时读取流程(控件树 / 截图 OCR 分流) | `capture/ChatCapture.kt`(只依赖 `ScreenAccess`,可在 JVM 上测) |
| 调试数据(上次读取的会话、原始控件树) | `capture/DebugCapture.kt` |
| 支持哪些 App、各自怎么读 | `data/SupportedApps.kt`(注册表:控件树规则 / 截图 OCR,加 App 只改这里) |
| 聊天解析(控件树,按水平位置分「我/对方」) | `accessibility/ChatParser.kt`、`MessageBoundsLocator.kt` |
| 截图 OCR(ML Kit 中文) | `ocr/OcrReader.kt`、`MessageLineMerger.kt`、`SenderNameFilter.kt` |
| 悬浮球 + 面板 | `bubble/FloatingBubbleService.kt`(悬浮助手编排;不是 Android Service,由 `ChatAssistantApp` 持有)、`FloatingBubble.kt`(悬浮球视图:拖拽、显隐、截图时隐藏)、`BubblePanel.kt`、`BubbleStyle.kt`、`BubblePalette.kt`(主题色 → View 颜色) |
| 生成建议 | `bubble/SuggestionGenerator.kt`(拼提示词、经 `AiClient` 调模型、云端超时、解析;`suggestFor` 串起记录消息与记忆) |
| AI 接口(对外只有 `AiClient`) | `ai/AiClient.kt`(`AiClient`、`AiException`、`aiClientFor`)、`ai/CloudClient.kt`、`ai/LocalClient.kt`(自己准备本地模型,失败以 `AiException` 返回) |
| 本地推理(MNN Qwen2.5) | `ai/local/LlmEngine.kt`、`LocalModel.kt`、`ModelStore.kt`(启动时 `ModelStore.init` 记下模型目录,AI 接口不需要 Context) |
| 语义向量(bge-small-zh) | `ai/embedding/`(`Encoder`、`Embedder`、`WordPieceTokenizer` …) |
| 专属记忆 | `data/memory/`(`MemoryRepository`、`MemoryLearner` 学习提示词与解析、`MemoryPromptBuilder` 拼记忆块、`MemoryRetrieval`、`MemoryLearningPass`、`ReplyLog` …) |
| 设置 / 存储 | `data/`(`SettingsRepository`、`SecretCodec` Keystore 加密 API Key) |
| 界面 / 主题 | `ui/`(每屏一个 ViewModel)、`ui/theme/Theme.kt`(`ThemeVariant` 六套配色) |
| 诊断 | `diagnostics/`(错误码、本地记录、导出) |
| 原生 | `native/mnn-llm/`(本地 LLM JNI)、`native/mnn-embed/`(bge encoder JNI) |
| 质量工程 | `quality/architecture/`(Konsist 架构与命名规则)、`quality/macrobenchmark/`(性能)、`quality/reliability/`(真机可靠性)、`quality/scripts/`(一键脚本) |
| 设计文档 | `docs/`(记忆系统 / embedding 选型基准 / LLM 引擎 A/B / 集成设计 / 质量属性 / 可观测性 / 性能与可靠性测试) |

## 构建

需要 JDK 17、Android SDK(platform 36 / build-tools 36)。仓库用 **git-lfs** 存 `*.so` 与 `*.mnn`(本地模型),clone 后需 `git lfs pull`。

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:assembleDebug      # 或 assembleRelease
# 产物:app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 质量检查(与 CI 一致)
./gradlew spotlessCheck :quality:architecture:test          # 规范 + 架构规则(CI style 任务)
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug   # 单测 + Lint + 构建(CI build 任务)
# Kotlin 规范自动修复(换行、import 排序、空白等)
./gradlew spotlessApply
```

> - CI(`.github/workflows/ci.yml`)在每个 PR 和 main 上跑两个任务,不拉 LFS:**style**(Kotlin 规范 + Konsist 架构规则,约 1 分钟)和 **build**(单测 + Lint + debug 构建 + 编译基准模块)。
> - Kotlin 规范 = [Android Kotlin 风格指南](https://developer.android.com/kotlin/style-guide?hl=zh-cn),由 ktlint(`android_studio` 风格)经 Spotless 执行,规则在 `.editorconfig`。`spotlessApply` 修不了的(命名、超 100 列的长行)需手改。纯格式化提交记入 `.git-blame-ignore-revs`(本地 `git config blame.ignoreRevsFile .git-blame-ignore-revs`)。
> - Lint 不设基线,存量已清零;`warningsAsErrors` 打开,任何新 warning 都会让构建失败。确属误报的,在出问题的位置用 `tools:ignore` / `@SuppressLint` 并写明理由。
> - Compose 检查用 Slack 的 [compose-lints](https://slackhq.github.io/compose-lints/),随 `:app:lintDebug` 一起跑。它需要较新的 Lint 引擎,`gradle.properties` 里的 `android.experimental.lint.version=9.5.0` 只换 Lint、不换 AGP;AGP 升到 9.5+ 后删掉。
> - 架构与命名规则用 [Konsist](https://docs.konsist.lemonappdev.com/) 写成单测(`quality/architecture/`):各包允许依赖的白名单(如 `accessibility` 不碰 `ai`/`ui`、`diagnostics` 不依赖任何包,`ai` 以外只能经 `AiClient` 调模型,根包只放 Application),以及命名约定(Bubble 只指悬浮球、每个界面一个 ViewModel、`*Repository` 实现同名 `*Store` 等)。违规时报出具体的文件和 import。规则清单见 `docs/quality-attributes.md` §4.4。
> - 质量属性与工程方法见 `docs/quality-attributes.md`。
> - 性能测试(微基准 / 宏基准,真机)见 `docs/performance-testing.md`,一键运行:`ADB=adb.exe quality/scripts/run-benchmarks.sh`。

> - 用 AGP 9 内置 Kotlin(不单独 apply `kotlin.android`),`gradle.properties` 设 `android.disallowKotlinSourceSets=false`。
> - 本地 LLM / embedding 的原生库仅构建 **arm64-v8a**;非 arm64 设备自动回退云端 / 词法。
> - 重建原生库见 `native/mnn-llm/BUILD.md`、`native/mnn-embed/BUILD.md`。

## 使用步骤

1. 打开 App,在 **权限** 中依次开启:**无障碍服务**、**悬浮窗**。悬浮球随无障碍服务连接自动出现,不再需要通知权限。
2. **AI 服务**:选 **云端**(DeepSeek / 豆包,填 Base URL、模型、API Key)或 **本地(隐私)**(下载 Qwen2.5 0.5B/1.5B/3B 其一)。
3. 选一个 **回复风格**(恋爱/破冰/商务)或自定义。
4. 点 **测试连接** 验证接口。
5. (可选)在 **高级设置 → 外观** 选主题,悬浮球会跟着变色;在 **高级设置 → 识别设置** 可追加走截图 OCR 的 App 包名。
6. 悬浮球随 App 启动自动开启(可在 **高级设置 → 悬浮球** 关闭),只在目标聊天 App 前台时显示。
7. 打开聊天界面 → 点悬浮球 → 选一条回复(自动复制;开了「自动填入」会直接写进输入框)。

## 专属记忆

- 每次点悬浮球,识别到的你在当前聊天里的**最后一句话**会被记录(去重;选中的 AI 建议不记录);累计到阈值后 LLM 蒸馏出稳定的个人信息(画像/事实/偏好/风格),写入时顺带产出关键词、重要度与语义向量。
- 生成回复时按 **三因子打分**(时效 + 重要度 + 与当前对话的相关性)挑选 top-N 注入 prompt。相关性优先用 **bge-small-zh 语义向量**,不可用时回退本地关键词匹配。
- 学习时做 Mem0 式 **ADD/UPDATE/DELETE**;删除为 **软删除**(进「回收站」,可恢复),规避 LLM 误删。「管理记忆」界面可逐条编辑/删除/恢复。
- 全部数据(回复记录、记忆、向量)只存在本机。

## 已知局限 / 后续方向

- **解析是启发式的**:控件树按水平位置分「我/对方」,复杂气泡(图片、引用、合并转发)可能不准;可在 `data/SupportedApps.kt` 给该 App 加控件树规则,或改为截图 OCR。
- **本地 LLM / embedding 仅 arm64-v8a**:其他 ABI 自动回退云端 / 词法。
- **自动填入** 依赖控件树里能找到可编辑的输入框;**微信屏蔽控件树,自动填入不可用**(只会「已复制」,手动粘贴),自绘输入框同理。
- 语义召回为实验特性(可在高级设置一键关闭);召回质量、tokenizer 与 HF 的对齐建议用真实语料继续回归。
