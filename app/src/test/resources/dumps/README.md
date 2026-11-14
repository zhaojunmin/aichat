# 解析器对照样本(golden dumps)

`<包名>/<用例>.xml` 是 `uiautomator dump` 导出的界面结构,`<用例>.expected.txt` 是期望的解析结果
(每行 `我: …` 或 `对方: …`,`#` 开头为注释)。`ChatParserGoldenTest` 会自动跑这里的每一对文件。

**样本必须脱敏后才能提交**:导出文件里有真实聊天原文、昵称和头像描述。用脱敏脚本在管道里处理,
原始导出文件不要落盘:

```bash
adb.exe shell uiautomator dump /sdcard/x.xml
adb.exe shell cat /sdcard/x.xml | python3 -I scripts/sanitize_dump.py <消息 view-id 名> \
  > app/src/test/resources/dumps/<包名>/<用例>.xml
adb.exe shell rm /sdcard/x.xml
```

脚本把所有 text / content-desc / hint 换成占位符:消息节点按从上到下编号为 `消息1..N`,其余为 `文本1..N`;
结构、id、class、bounds 原样保留。期望结果按导出时**屏幕上的真实情况**手写,不要用解析器的输出反推。

App 改版(例如 QQ 的混淆 id 变了)时:更新规则,再加一个新版本的样本,旧样本保留,用来防止回归。
