# CP2 单一路线静态与隔离执行审查

任务 V2-OFFLINE-VOICE-001，attempt a20f0582-3aaf-4952-8a15-876f2bd93297，合同1864fa5bbe273ed34e44b437f1d9bc158bc385d9df44c7c64646d51927a1ab86。未继承旧think许可或制品。

## 固定对象

- Vosk Android `0.3.75`，官方Maven `https://repo.maven.apache.org/maven2/com/alphacephei/vosk-android/0.3.75/`；AAR 13,472,638 bytes；SHA256 `ab2f8b91ac8051561aa325546b35fed9a68b36b8121bac5c6fb927525c4adfad`，匹配同源发布摘要。
- 必要依赖 JNA `5.18.1`，官方Maven `https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/5.18.1/`；AAR522,677 bytes；本地SHA256 `7f053e3ec99e14dd71259c82c1c8a02738d64a13c31226b2acc170f3060951e0`；未取得发布方SHA256，不能声称签名验证。
- 模型 `vosk-model-small-cn-0.22`，[官方模型页](https://alphacephei.com/vosk/models)与其HTTPS ZIP。43,898,754 bytes，本地SHA256 `3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba`；发布页未给摘要，首次取得后固定身份。不是校验签名。
- Vosk和模型Apache2；JNA双许可选择Apache2。许可/POM、对象摘要及扫描报告见`doc/evidence/voice/`。未引入远程Gradle插件或运行上游构建脚本；精确AAR作为本地输入，由既有Android工具转换，原生代码仅在授权隔离模拟器加载。
- `arm64-v8a`为本单唯一打包ABI；两个AAR实际ELF PT_LOAD p_align均16384，满足所选Android37.1 arm64/16KB模拟器的静态前提。小米15实际加载未验证。32位Vosk仍4096，不打包也不宣称支持。

## 非执行审查

使用scan-untrusted-code1.1.2扫描ZIP，不运行其中代码；AAR复制为`.aar.zip`使归档检查生效，字节未改。Vosk包装源、AAR、模型未出现配置规则的高风险信号。模型大二进制被扫描器大小上限跳过，另行检查路径、symlink、尺寸、配置和每个资产SHA；不能声称模型内容无漏洞。未将评分当作安全证明。

JNA源扫描`sandbox_only`、score60、无block信号：`CallbackReference.java:716`、`Function.java:204`、`NativeLibrary.java:150/618`的`new Function(...)`被动态执行规则匹配。人工读源码确认是JNA正常FFI桥接到native符号，不是shell下载执行链；该能力本身真实存在。执行仅限本合同授权的无私人数据、无账户、无共享宿主目录的隔离Android模拟器，应用无INTERNET权限。没有在宿主执行JNA/Vosk目标代码。二进制供应链、反编译和完整依赖递归审计不在此扫描器覆盖内。

## 数据路径

审阅Maven包装源及官方版本提交`625e44c62607a3b48fec6d72a30118448909e83f`的`recognizer.cc`、`model.cc`、`vosk_api.cc`。版本提交对应0.3.75更新，未进行可重现构建，不能证明AAR逐指令等价于此源码。

- `Recognizer.acceptWaveForm(short[],count)`直接通过JNA进native；native把样本变为内存向量并进入特征/decoder，结果以JSON字符串返回。该调用路径未发现音频文件输出或网络调用。
- 只使用本机路径Model与默认Recognizer；不使用StorageService、SpeechService、下载API、说话人、图更新/grammar、TTS或文本规范化功能。
- 模型配置只有固定解码参数，没有外部命令/管道路径。资产复制至noBackupFilesDir的是模型；每个文件按APK内清单验证长度与SHA。模型文件允许持久化。
- JNA `Native.java`有native解包临时文件能力。初始化前设置`jna.nounpack=true`及`jna.noclasspath=true`，显式加载APK打包的库。普通FFI反射/系统加载能力仍存在，未当作“没有动态执行”。
- Vosk日志级别-1关闭info/debug；不调用会把用户grammar写入错误日志的接口；本应用不记录原音频、PCM、识别JSON或用户转写。第三方异常只转换为固定错误码，不输出异常内容。
- App PCM仅短数组和有限队列；消费、异常和取消路径清零Java数组并释放AudioRecord/Recognizer。native特征内部内存、内核AudioRecord缓存、OS物理页擦除不在应用保证内；释放不等于物理擦除。

## 动态门

以上支持在专用合成模拟器开展限定动态验证，不证明完整安全或最终功能验收。尚需真实加载/识别、目录/日志/网络观察、取消/权限/后台与性能数据。发生音频持久化、上传、对象变化或权限拒绝，停止受影响动作并向指挥官报告。
