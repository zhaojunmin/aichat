# Android 可靠性测试

> 对应 `docs/quality-attributes.md` §2.2 的可靠性场景。目录布局与性能测试一致:**要调用 App 内部类的白盒测试**放在 `app/src/test`,随单元测试和 CI 一起跑;**从外部驱动正式版的真机黑盒测试**放在 `quality/reliability`。

## 运行

```bash
# 白盒(JVM + Robolectric,不需要手机,CI 每次都跑)
./gradlew :app:testDebugUnitTest

# 黑盒(需要一台已解锁的手机;WSL 下设 ADB=adb.exe)
quality/scripts/run-reliability.sh
# 原始输出:build/reliability-results/reliability.txt
```

黑盒测试的前提:手机上 App 的**无障碍服务已开启、悬浮窗已允许**,也就是用户平时使用的状态。不满足时用例会被跳过(skip),不算失败。脚本用本地的正式版签名安装 release 包,会保留 App 的数据。

## 白盒(`app/src/test`)

| 测试 | 场景 | 覆盖 |
|---|---|---|
| `LearningTransactionTest` | R5 | 用真实的 Room 数据库:写到一半失败、写完但保存进度失败,都整体回滚;成功后模拟重启仍然生效;失败后重试只学一次 |
| `MemoryDatabaseMigrationTest` | 升级不丢数据 | 按 v1–v4 各版本全新安装时的建表语句造库,再用当前版本和真实迁移链打开。Room 打开时会校验 schema,迁移写错就会失败 |
| `ReplyLogTest` | R5 | 去重、重启后保留进度、旧版本 prefs 里的进度迁进数据库、进度超出文件行数、清空 |
| `MemoryLearningPassTest` | R5 | 学习器返回空 / 抛异常 / 写库失败时进度不前进;编造的 id 不动任何行 |
| `SuggestionGeneratorTest`、`CloudClientTest`、`LocalClientTest` | R3、R4 | 畸形输出、HTTP 错误分类、超时、断连、取消、本地超时 |

数据库 schema 导出在 `app/schemas/`(每个版本一个 JSON,提交进仓库)。以后再升级数据库,可以对照上一版的 schema 检查迁移。

## 黑盒(`quality/reliability`)

`com.android.test` 模块,用自插桩(self-instrumenting)的方式从外部驱动正式版 App:UiAutomator 读界面,shell 命令扰动手机(以 shell 权限运行,不需要 root)。

| 用例 | 扰动 | 期望 |
|---|---|---|
| `processCrash_servicesComeBack` | `am crash`,让 App 进程崩溃 | 无障碍服务和悬浮窗服务都由系统自动拉起;无障碍开关不会被关掉;**诊断导出里多了一条 `APP-901`(崩溃时同步写下),并有新的 `APP-902 exit=CRASH`(重启时从系统读到)**。诊断开关关闭时跳过这一项 |
| `memoryPressure_noCrash` | 前台三级、后台三级 `send-trim-memory` | 不崩溃、不重启进程、服务不掉 |
| `overlayRevoked_noCrash_andRecovers` | 运行中收回悬浮窗权限(`appops deny`),再恢复 | 不崩溃;首页显示「去开启」;恢复后悬浮球重新起来 |
| `accessibilityDisabled_noCrash_andReconnects` | 运行中关闭无障碍服务,再打开 | 不崩溃、悬浮球保持;首页显示「去开启」;重新打开后系统重新绑定 |
| `offline_testConnectionAnswers` | 关闭 Wi-Fi 和移动数据,点「测试连接」 | 云端:10 秒内给出「网络连接失败」(R4),不会一直转圈。本地:照常生成 |
| `configChanges_noCrash` | 切换深色模式 + 横屏 | 不崩溃、不重启进程,首页仍可用 |

每个用例结束时都会检查:这段时间内系统有没有记录 App 的崩溃或 ANR(`dumpsys dropbox`)。崩溃用例要求恰好记录到 1 次,这同时验证了检测本身有效。

### 对手机的影响

用例会临时让 App 崩溃、收回权限、断网约半分钟、切换深色模式、旋转屏幕,每一项都在 `finally` 里恢复。脚本开始时会先记下原始设置,退出时再恢复一遍,即使测试中途挂掉也会恢复。最后卸载测试 APK。

### 刻意没做的

- **随机点击压力测试(monkey)**:会随机点中「清空记忆与回复记录」之类的按钮,毁掉真实数据。
- **`am force-stop`**:在这台测试机的 ROM 上,它会顺带把 App 从已开启的无障碍服务里移除。崩溃场景改用 `am crash`。

### 踩过的坑

- **UiAutomation 默认会压制其他无障碍服务**:UiAutomator 连上 UiAutomation 后,设备上的其他无障碍服务(包括被测 App 的)会被暂停,直到测试结束。必须在第一次使用 UiAutomation 前设置 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`(见 `Phone.kt`)。
- **判断无障碍服务是否绑定,要看 `dumpsys accessibility` 的 Bound services**。`dumpsys activity services` 里的服务记录在解绑之后还会留着,不能用来判断。
- **滑动之后要等页面静止再点**:滑动后列表还在惯性滚动,这时点按钮只会让滚动停下,不会触发点击。`Phone.scrollTo` 找到目标后先等页面空闲,再重新定位一次。
- **不能依赖 logcat**:这台荣耀手机会屏蔽 release 包的日志,所以崩溃和 ANR 改从 `dumpsys dropbox` 读。读取偶尔会拿到空结果,所以要求输出里带有固定表头,否则重试。

## 基线(2026-11-15,荣耀 ELP-AN00 / Android 16,云端模式)

| 用例 | 结果 |
|---|---|
| 进程崩溃 | 无障碍服务 65 ms、悬浮窗服务 80 ms 内恢复,无障碍开关保持开启 |
| 无障碍关闭后重新打开 | 52 ms 内重新绑定 |
| 悬浮窗权限收回后恢复 | 悬浮球 43 ms 内重新起来 |
| 断网时测试连接(云端) | 1.6 s 给出「网络连接失败」 |
| 内存压力、深色模式加旋转 | 不崩溃,同一进程 |
