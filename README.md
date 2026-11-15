# 聊天助手 · AI 回复助手 (Android)

在微信、QQ、Soul 等第三方聊天 App 里,用 AI 辅助生成回复。通过 **无障碍服务读屏**(或 **截图 OCR**)读取当前聊天界面,点击 **悬浮球** 即可生成多条回复候选,一键复制或自动填入输入框。支持 **云端 / 端侧本地** 两种推理,并带一个 **越用越懂你的专属记忆**。

> ⚠️ 仅供学习与个人使用。读屏/悬浮窗能力强大,请勿用于侵犯他人隐私、骚扰或违反相关 App 用户协议的场景。

## 功能特性

- **多后端**:云端 OpenAI 兼容接口(DeepSeek / 豆包),或 **端侧本地模型**(MNN + Qwen2.5,聊天内容不出设备)。
- **两种读屏**:默认走无障碍控件树;对气泡复杂的 App(如微信)可切 **截图 OCR**(ML Kit 中文识别),按包名分流。
- **专属记忆**:记录你发出的回复,LLM 定期蒸馏成稳定的「画像 / 事实 / 偏好 / 风格」,回复生成时按相关性注入,让建议更像你本人。
- **语义召回**:记忆检索用端侧 bge-small-zh 向量做语义匹配(离线、隐私),不就绪时自动回退本地关键词匹配。
- **人设/场景**:恋爱、破冰、商务、客服等预设,或自定义语气。
- **自动填入**:选中的回复自动复制;可选无障碍 `ACTION_SET_TEXT` 直接写进输入框。
- **主题外观**:纸 / 雾 / 月白(浅色)、蓝夜 / 墨 / 黛青(深色)或跟随系统;悬浮球与建议面板同步使用当前主题配色,切换主题或系统明暗时实时生效。
- **诊断信息**:关键失败按错误码记录到本地,可在 App 内预览、复制、分享或清空,便于排查问题。

## 工作原理

```
第三方聊天 App(微信 / QQ / Soul)
        │ 屏幕内容
        ▼
AccessibilityService ──► ChatParser(控件树)┐
        │                                   ├──► Conversation ──► AiService ─────┬─► 云端(DeepSeek/豆包,OpenAI 兼容)
        │                 截图 OCR(OcrReader)┘            ▲                      └─► 本地(MNN + Qwen2.5,隐私)
        │ 前台包名变化切换悬浮球                            │
        ▼                                      MemoryRepository(三因子召回:
FloatingBubbleService(悬浮球 + 建议面板)        recency + importance + relevance)
        │ 选中一条                                    relevance = 本地词法 / bge 语义向量
        ├─► 复制到剪贴板
        └─►(可选)无障碍 ACTION_SET_TEXT 自动填入输入框
```

## 核心模块

| 模块 | 文件 |
|---|---|
| 读屏服务 | `accessibility/ChatAccessibilityService.kt` |
| 聊天解析(控件树,按水平位置分「我/对方」) | `accessibility/ChatParser.kt`、`MessageBoundsLocator.kt` |
| 截图 OCR(ML Kit 中文) | `ocr/OcrReader.kt` |
| 悬浮球 + 面板 | `bubble/FloatingBubbleService.kt`、`BubblePanel.kt`、`BubbleStyle.kt`、`BubblePalette.kt`(主题色 → View 颜色) |
| AI 接口抽象 | `ai/AiClient.kt`、`ai/CloudClient.kt`、`ai/LocalClient.kt`、`ai/AiService.kt` |
| 本地推理(MNN Qwen2.5) | `ai/local/LlmEngine.kt`、`LocalModel.kt`、`ModelStore.kt` |
| 语义向量(bge-small-zh) | `ai/embedding/`(`Encoder`、`Embedder`、`WordPieceTokenizer` …) |
| 专属记忆 | `data/memory/`(`MemoryRepository`、`MemoryRetrieval`、`MemoryLearningPass`、`ReplyLog` …) |
| 设置 / 存储 | `data/`(`SettingsRepository`、`SecretCodec` Keystore 加密 API Key) |
| 界面 / 主题 | `ui/`(每屏一个 ViewModel)、`ui/theme/Theme.kt`(`ThemeVariant` 六套配色) |
| 诊断 | `diagnostics/`(错误码、本地记录、导出) |
| 原生 | `native/mnn-llm/`(本地 LLM JNI)、`native/mnn-embed/`(bge encoder JNI) |
| 设计文档 | `docs/`(记忆系统 / embedding 选型基准 / LLM 引擎 A/B / 集成设计) |

## 构建

需要 JDK 17、Android SDK(platform 36 / build-tools 36)。仓库用 **git-lfs** 存 `*.so` 与 `*.mnn`(本地模型),clone 后需 `git lfs pull`。

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:assembleDebug      # 或 assembleRelease
# 产物:app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 质量检查(与 CI 一致)
./gradlew spotlessCheck :app:testDebugUnitTest :app:lintDebug
# Kotlin 规范自动修复(换行、import 排序、空白等)
./gradlew spotlessApply
```

> - CI(`.github/workflows/ci.yml`)在每个 PR 和 main 上跑 Kotlin 规范检查 + 单测 + Lint + debug 构建;不拉 LFS。
> - Kotlin 规范 = [Android Kotlin 风格指南](https://developer.android.com/kotlin/style-guide?hl=zh-cn),由 ktlint(`android_studio` 风格)经 Spotless 执行,规则在 `.editorconfig`。`spotlessApply` 修不了的(命名、超 100 列的长行)需手改。纯格式化提交记入 `.git-blame-ignore-revs`(本地 `git config blame.ignoreRevsFile .git-blame-ignore-revs`)。
> - Lint 用 `app/lint-baseline.xml` 冻结存量问题,只拦新增;修掉存量后用 `./gradlew :app:updateLintBaseline` 重新生成。
> - 质量属性与工程方法见 `docs/quality-attributes.md`。
> - 性能测试(微基准 / 宏基准,真机)见 `docs/performance-testing.md`,一键运行:`ADB=adb.exe quality/scripts/run-benchmarks.sh`。

> - 用 AGP 9 内置 Kotlin(不单独 apply `kotlin.android`),`gradle.properties` 设 `android.disallowKotlinSourceSets=false`。
> - 本地 LLM / embedding 的原生库仅构建 **arm64-v8a**;非 arm64 设备自动回退云端 / 词法。
> - 重建原生库见 `native/mnn-llm/BUILD.md`、`native/mnn-embed/BUILD.md`。

## 使用步骤

1. 打开 App,在 **权限** 中依次开启:**无障碍服务**、**悬浮窗**、(Android 13+)**通知权限**。
2. **AI 服务**:选 **云端**(DeepSeek / 豆包,填 Base URL、模型、API Key)或 **本地(隐私)**(下载 Qwen2.5 0.5B/1.5B/3B 其一)。
3. 选一个 **回复风格**(恋爱/破冰/商务)或自定义。
4. 点 **测试连接** 验证接口。
5. (可选)在 **高级设置 → 外观** 选主题,悬浮球会跟着变色。
6. 回到首页点 **启动悬浮球**。
7. 打开聊天界面 → 点悬浮球 → 选一条回复(自动复制;开了「自动填入」会直接写进输入框)。

## 专属记忆

- 每条你发出的回复会被记录;累计到阈值后 LLM 蒸馏出稳定的个人信息(画像/事实/偏好/风格),写入时顺带产出关键词、重要度与语义向量。
- 生成回复时按 **三因子打分**(时效 + 重要度 + 与当前对话的相关性)挑选 top-N 注入 prompt。相关性优先用 **bge-small-zh 语义向量**,不可用时回退本地关键词匹配。
- 学习时做 Mem0 式 **ADD/UPDATE/DELETE**;删除为 **软删除**(进「回收站」,可恢复),规避 LLM 误删。「管理记忆」界面可逐条编辑/删除/恢复。
- 全部数据(回复记录、记忆、向量)只存在本机。

## 已知局限 / 后续方向

- **解析是启发式的**:控件树按水平位置分「我/对方」,复杂气泡(图片、引用、合并转发)可能不准;可按包名加专用解析器或用截图 OCR 兜底。
- **本地 LLM / embedding 仅 arm64-v8a**:其他 ABI 自动回退云端 / 词法。
- **自动填入** 依赖标准可编辑输入框;自绘输入框会失败,此时回退「已复制」。
- 语义召回为实验特性(可在高级设置一键关闭);召回质量、tokenizer 与 HF 的对齐建议用真实语料继续回归。
