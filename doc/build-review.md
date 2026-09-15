# 本项目构建与依赖核对

日期：2026-09-16。只检查本项目和本机已有工具/缓存，不继承旧think安全结论。

## 模板静态检查

已在执行前阅读 root/app Gradle脚本、settings、版本目录、wrapper脚本和配置。构建只包含Android application与Kotlin Compose插件，标准Google/MavenCentral/plugin portal仓库；settings中的Foojay 1.0.0是模板已有JDK解析插件。wrapper固定Gradle9.5.0与distribution SHA256；使用Android Studio已安装JBR25及已有SDK37。`org.gradle.java.installations.auto-download=false`明确禁止自动取得JDK。所有实际Gradle命令使用`--offline`，未下载依赖。

静态scanner原报告在 `evidence/template-static-scan.md`。唯一medium项是`.git/config`存在：该文件为本次获授权git init新生成，已逐行检查仅包含core基本配置，无hooksPath、filters、fsmonitor或shell alias。没有high/critical、IOC或敏感数据读取链。原始`manual_review`结果不篡改；本次人工复核解决这个上下文项。scanner不反编译或证明二进制安全，不将本结论视为穷尽供应链审计。

## 测试组件兼容调整

模板Espresso3.5.1在全新Android37.1模拟器启动Compose测试时，反射查找`InputManager.getInstance`失败，发生在实际UI动作前。仅测试配置调整为缓存中Espresso3.7.0、androidx.test.ext:junit1.3.0。核对直接对象和AndroidX测试配套缓存的坐标、POM声明Apache2.0许可、AAR成员路径/体积及SHA256，见 `evidence/test-dependency-cache-review.json`。全程离线；产品运行依赖未增加。

这是对已安装缓存的风险相称静态核对，不是外部签名认证或CVE全量检索。测试APK仅运行在新建临时AVD，没有真实父亲数据。初次wrapper因沙箱无法写缓存锁失败，随后使用已批准缓存访问完成离线构建。

## 工具与数据边界

host测试以独立新写的PythonSql测试桥将产品Kotlin SQL传给`/usr/bin/python3`标准库SQLite，数据库在测试临时目录，全部合成数据；该桥不随产品APK发布。平台测试使用AndroidSql实际数据库。

模拟器使用现存Android37.1 arm64-v8a Page Size16KB镜像rev9，新建`/private/tmp/thinkv2-emulator`数据目录；没有复用用户AVD磁盘。指定本机5581端口及独立ADB5039，仅操作这个模拟器；无真机操作、登录或新镜像下载。模拟器关闭Wi-Fi并设为飞行模式。测试完成后停止此临时实例。
