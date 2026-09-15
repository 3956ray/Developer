# thinkV2 Agent 规则

## 项目定位
- 从空白模板独立开发原生 Android 私人笔记应用；Kotlin、Compose Material 3、Android SQLite。
- Git 根是本目录；入口为 `README.md`、`doc/requirements.md`。
- `app/src/main` 是产品代码，`app/src/test` 使用合成数据，`app/build` 是生成目录。

## 工程规范
- 仅在本项目开发；不复制旧 think 源码或继承其验收。
- 私人文字只在本地保存，不进日志、云备份或遥测；原始音频不得落盘、上传。
- 保存成功只在事务提交后显示；迁移和异常不能清库或伪造数据。
- 新依赖先核对精确来源与风险；优先平台 API 和已核模板依赖。
- 简单连续任务由当前 agent 完成，不递归委派。

## 完成条件
- 对照本单验收，运行必要持久化/状态测试及可用构建、lint；如实记录阻塞。
- host 测试不能替代 Android 设备、无障碍或家庭试用证据。
- 一单完成后回报指挥官，不自动扩大产品范围。

## 已验证命令

在本项目使用已安装JBR，运行 `JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --no-daemon --console=plain`。设备测试只能定向获授权的合成模拟器，不使用泛化connected测试命令枚举真机。
