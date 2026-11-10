# 聊天搭子 · AI 回复助手 (Android MVP)

在微信、Soul 等第三方聊天 App 里,用 AI 辅助生成回复。通过 **无障碍服务读屏** 读取当前聊天界面的文字,点击 **悬浮球** 即可调用你配置的 AI 接口生成多条回复候选,一键复制或自动填入输入框。

> ⚠️ 仅供学习与个人使用。读屏/悬浮窗能力强大,请勿用于侵犯他人隐私、骚扰或违反相关 App 用户协议的场景。

## 工作原理

```
第三方聊天 App (微信/Soul)
        │  屏幕内容
        ▼
AccessibilityService ──► ChatParser(按水平位置判断"我/对方") ──► Conversation
        │                                                           │
        │ 前台包名变化切换悬浮球                                      ▼
        ▼                                             AiRepository ──► Claude / OpenAI 兼容后端
FloatingBubbleService(悬浮球 + 建议面板) ◄──── 回复候选 ◄───────────────┘
        │ 选中一条
        ├─► 复制到剪贴板
        └─► (可选)无障碍 ACTION_SET_TEXT 自动填入输入框
```

核心模块:

| 模块 | 文件 |
|---|---|
| 读屏服务 | `accessibility/ChatAccessibilityService.kt` |
| 聊天解析(启发式) | `accessibility/ChatParser.kt` |
| 悬浮球 + 面板 | `overlay/FloatingBubbleService.kt` |
| AI 接口抽象 | `ai/AiClient.kt`、`ai/HttpClients.kt`、`ai/AiRepository.kt` |
| 设置/人设/存储 | `data/*`、`MainActivity.kt` |
| 进程内总线 | `AppBus.kt` |

## 构建

需要 JDK 17、Android SDK(platform 36 / build-tools 36)。

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:assembleDebug
# 产物:app/build/outputs/apk/debug/app-debug.apk
```

安装到手机:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> 使用 AGP 9 的内置 Kotlin(不单独 apply `kotlin.android`),并在 `gradle.properties` 设置
> `android.disallowKotlinSourceSets=false`。

## 使用步骤

1. 打开 App,在 **① 权限** 中依次开启:**无障碍服务**、**悬浮窗**、(Android 13+)**通知权限**。
2. 在 **③ AI 后端** 选择 Claude 或 OpenAI 兼容,填入 Base URL、模型、API Key。
3. 在 **④ 聊天人设** 选一个场景(恋爱/破冰/商务/客服)或填自定义人设。
4. 点 **⑥ 测试生成回复** 验证接口通不通。
5. 回到 **② 悬浮球** 点"启动悬浮球"。
6. 打开微信/Soul 聊天界面 → 点悬浮球 → 选一条回复(自动复制;开了"自动填入"会直接写进输入框)。

## MVP 的已知局限 / 后续方向

- **解析是启发式的**:靠文字节点的水平位置区分"我/对方",复杂气泡(图片、引用、合并转发)可能识别不准。可按包名做专用解析器提升准确率。
- 悬浮球目前常驻显示;`AppBus.onForeground` 已预留"仅在目标 App 显示"的开关,可接 `setBubbleVisible` 完善。
- 自动填入依赖目标 App 的输入框是标准可编辑控件;部分 App 自绘输入框时会失败,此时回退到"已复制"。
- API Key 以明文存于 SharedPreferences,生产环境建议改用 EncryptedSharedPreferences / Keystore。
- 未做多轮记忆、草稿管理、回复风格微调等;接口已预留 `suggestionCount`、人设等扩展点。
