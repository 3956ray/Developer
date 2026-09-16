# V2-OFFLINE-VOICE-002：RESULT BLOCKED / implementation_status PARTIAL

本单完成一次有界候选评估，未通过CP2。两个独立条件仍未过：严格可用23/30低于27；SenseVoice权重的私人App嵌入许可仍PAUSE。原始专名61/66=92.42%达到90%，不等于整体通过。停止10×30秒/20×90秒长测，未更换候选、修改答案/配置、加答案热词或重复识别择优。没有私人包交付。

## 身份与决定

- 唯一项目thinkV2；partial基线23ede0961ff83e5637493d7eb5f6c5ecf6b84f59，不是已通过CP2。分支codex/v2-offline-voice-002。
- attempt cb6a5460-1655-4778-9d85-6095d87906e4；合同规范化SHA256 bea42148f0e97dcd0194d6e15122a3129cd0b946675101f246b6e29e00f56fef已重算。
- 已完整读取PM正式[sensevoice-int8-product-admission-decision-2026-09-16.md](</Users/orderly_ray/Documents/Products Manager/product-knowledge-base/ideas/think-v2/sensevoice-int8-product-admission-decision-2026-09-16.md>)。APPROVED仅同候选合成隔离参考学习评估；私人嵌入、日常包及私人资料处理PAUSE。不是法律保证或对象安全批准。
- 精确最终提交、工作区状态见外部本单result.json，本文避免自引用提交摘要。

## 一次质量结果

原30条文字、66次专名、voice/rate及判分口径保持。plan SHA256 95d97b465918f40831658cbcbc29390cc2b417be6a13adf542826c76d9db0b6b。首次推理前冻结配置SHA256 dc81492e82016c969329b7db8bc883639a97186012d50f1c7e4a6b5780bfca40：CPU2线程、中文zh、ITN true、16k/80、greedy_search；debug false、无热词/外部规则/同音替换，不消费情绪/事件输出。ITN所产生的数字与标点仍作为模型原始输出，未事后纠错。

30段原始文字、设备记录及逐段理由见`doc/evidence/voice-002/recognition-results.json`、`recognition-device-results.json`和`recognition-assessment.md/json`。CN04陈晨→晨晨、CN06林静→临静、CN12/CN24何丽→合力、CN21周杰→周节：5段主体身份错误已使上限25/30。另CN26和→盒损及并列对象关系、CN27请→琴损及请求动作关系，保守不通过，得到23/30。即使两段都放宽，仍不满足27。她/他不单独判失败，沿用001 CN27“具名主体未改变”的判法；数字九/9等语义一致。不是父亲接受或临床/用户试验。

29/30 PCM SHA与001逐字节相同。CN14旧166172样本10.38575秒，SHA823f57262cc708ec301055ef88f0de5f0a8c08b7a080866ee3d9c6ee42ba4ba5；本轮83086样本5.192875秒，SHAd91a4c5ae2a54b62e5511be91b97765ffdbcc85f0998f0ab541082756cc81f9e。文字、声音、rate、OS26.6.2及生成器二进制SHA438588e42e3742cb2986556ff15736213d24b83dbc3e5cf33c04f9aa482ad04c完全一致。未保留原音频，差异原因不能确定，不推定callback原因，也未重跑。61/66仅是本轮生成输入结果；CN14不能作为同PCM提升证据。全部比较在fixture-comparison.json。即使忽略这一段，至少5段失败的结论仍成立。

## 精确对象与依赖

|对象|固定来源/版本|身份与许可|
|---|---|---|
|Sherpa Android标准AAR|官方v1.13.8；commit11afbd009a7f8c08f4bcf2fc1b265d0df4670fbf|50129134 bytes，SHA633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96，与release digest匹配；Sherpa代码Apache2，整个二进制另含以下依赖|
|转换SenseVoice int8|csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17；rev2365baeacb507f821a0c8120fcee3d484dba7a07|239233841 bytes，SHAc71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51，匹配LFS身份；转换LICENSE转引FunASR|
|tokens.txt|同一固定模型修订|315894 bytes，SHAf449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc，Git blob2cfc92fc2ff26aaa690b7c01fd96b41109413881匹配|
|原始模型卡及模型许可|FunAudioLLM/SenseVoiceSmall rev3847d57b6bdf2dd8875cb1508d2af43d80a16bf7；FunASR条款commit4d8e08748c9d7cc10f3e3a01f5f4b927a8948699|模型卡license other；FunASR Model License v1.1。§3参考学习用途、行为终止/自动修订等见PM决定。代码仓库MIT不覆盖权重|
|ONNX Runtime|1.28.2；固定Sherpa cmake及AAR内libonnxruntime.so|MIT+完整ThirdPartyNotices已保存；不是独立重现构建证明|
|静态识别依赖|fbank1.22.3、KissFFT febd4ca、kaldi-decoder0.3.0、simple-sentencepiece0.7、json3.12.0|分别Apache2、BSD3、Apache2、Apache2、MIT，来源/许可材料分别保存|
|AAR编入但未调用组件|默认TTS/diarization开关ON；espeak-ng ed530aa、piper f3ff95a、hclust2026-02-25、uni-algo|GPL3、MIT、BSD及MIT/Unicode。仅不分发内部评估，未启用这些功能；不能把整个AAR标为单一Apache，未声称分发义务已满足|

来源与字节身份见model-objects.json/runtime-release-asset.json及review-inputs.json。模型元数据author=iic、maintainer=k2-fsa，原模型名和转换者分开记入评估NOTICE；不是完整训练资料权利链证明。没有取得上游test_wavs、float32模型、其他模型或执行export/training/构建上游脚本。

## 静态准入范围与限制

AAR扫描0/100 low_indicators，但仅3文本，5二进制与12超限成员未被规则全面覆盖。模型大文件亦跳过文本规则。另以不加载模型的protobuf遍历验证1图4458tensor、36类operator、仅标准ONNX/com.microsoft域、无external_data；不是语义验证/安全保证。四份arm64 ELF均e_machine183、PT_LOAD p_align16384；DT_NEEDED只指向本AAR ONNX/Sherpa及系统库；manifest minSdk21，无组件/uses-permission。

相关源码扫描23/100 sandbox_only，3个high均为上游.github/workflows/android.yaml删除构建目录；因扁平保存被工具归source_code，人工按原路径确认是未执行CI，无宿主自动入口。9份源码被工具判binary，已只读人工审查可达路径。混合整个review目录扫描因2MB单行树元数据长期CPU占用被终止exit143；不算成功，改为相关源码限定扫描后完成。扫描报告/缺口均保留。

JNI浮点数组→OfflineStream/fbank→CMVN→ONNX CPU→CTC结果路径未见音频文件写入/上传。profiling/provider外部配置明确未启用。native异常可能写logcat，完整第三方二进制、可重复构建、OS内部和物理擦除未证明。来源SHA不是数字签名。此门只支持已授权专用合成模拟器的有限动态评估。

## 测试适配与隔离

`-PsenseVoiceEvaluation=true`才加入androidTest专用Kotlin源码、资产和AAR依赖；默认产品main源码相对23ede09完全无差异。SenseVoiceCandidate实现原VoiceDecoder接口：最大1440000个Short样本（2.88MB），finish创建最多5.76MB float副本，一次acceptWaveform，因为上游每次调用即InputFinished；成功/异常finally清零第一方缓冲、release stream/model，输入越界拒绝。native特征与ONNX工作内存另计，不从PCM容量推断。同步decode取消不可抢先并发释放，产品迟到结果保护未在本候选上做生命周期集成验证，因为质量已失败。

测试服务只在显式方法启动本地abstract socket；PCM经宿主pipe/RAM和ADB本地转发，宿主最多64MiB。测试readFully/accept异常外层finally清零bytes/samples。只持久化合成文字结果、摘要、计时；模型权重允许写no_backup。旧合成生成器使用系统离线TTS内存回调及sandbox禁止调用进程网络/文件写入，系统服务内部覆盖Unknown。

正常APK静态DEX枚举17345类，未发现SenseVoiceCandidate/Evaluation、com/k2fsa/sherpa或ai/onnxruntime；ZIP无候选模型/so。最终默认test APK亦无候选。normal debugRuntimeClasspath依赖报告无Sherpa；本地file依赖未完整展示，故同时用APK类/资产证据。与001 APK不同的DEX字节未归因，不宣称整个产品APK字节没变，不扩成可复现构建研究。

## 实测与未做项目

- 专用ThinkV2Voice：Android17/API37、arm64、page16384、2CPU/2GiB；飞行模式1、Wi-Fi0、主机mic/camera关闭、无账户私人资料。未使用实体设备。
- 首次模型校验/复制+初始化2356ms；内部加载后采样PSS401724KiB（约392MiB），native allocated290388752 bytes。不是峰值或90秒内存上限。
- 一次30段服务76.846秒，OK(1)，表示真实推理流程完成，包含等待生成/传输，不是质量PASS。各小段throughput/尾耗时不是30秒P95。
- 后置meminfo观察时测试进程已退出，文件明确No process found，不冒充内存稳定或无泄漏证明。
- 应用目录仅两份权重/词表、profile标志及两份合成结果JSON；未观察到音频文件。指定App UID日志留存8253 bytes，可见JNI加载成功，未见抽查李明/晨晨/合力/许宁原文标记。仅为有限目录/日志窗口，不证明所有OS内部行为。
- 评估APK198925889 bytes，模型解包239233841+315894 bytes；AAR arm64各库尺寸在elf-review.json。正常产品APK67365621 bytes。没有长期磁盘增长或真机资源结论。
- 质量失败后未做10×30秒P95、20×90秒、候选UI/权限/后台/草稿生命周期集成。001历史证据仍属001，不能借作新候选通过。
- 最新评估源码lint成功35秒；恢复默认完整assembleDebug/assembleDebugAndroidTest/testDebugUnitTest/lintDebug成功21秒，91项零失败。没有main代码改变，未重复原设备全回归。构建初期修正了AGP Kotlin sourceSet注册与float清零常量类型，保留失败日志，均发生首次推理前。

## 复现与归档

正常命令沿用AGENTS。评估必须有PM限定用途与同对象安全门禁；手工将固定AAR置app/libs/evaluation、固定模型置app/src/senseVoiceEvaluation/assets/sensevoice后，执行`JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --offline -PsenseVoiceEvaluation=true :app:assembleDebugAndroidTest :app:lintDebug --no-daemon --console=plain`。模型/AAR大文件明确gitignore，原始取得URL/摘要在证据；不能自动下载或加载未知对象。默认不带此参数，正常测试类/资产均不加入。

实际设备方法为`com.example.thinkv2.voice.SenseVoiceEvaluationTest#syntheticMemoryFixtureServer`；专用ADB5050、127.0.0.1:5589、host5051→localabstract:thinkv2-sensevoice-fixture。执行的宿主脚本在fixtures/run-memory-fixtures.py，读取001冻结plan；不能将本次失败评估重跑成挑选结果。只读复算用`python3 doc/evidence/voice-002/verify-evidence.py`。

归档`/private/tmp/thinkv2-V2-OFFLINE-VOICE-002-cb6a5460/`含本轮唯一评估APK、正常APK、logs、JUnit XML、lint、类清单、apk-identities/evidence-manifest/result JSON。评估APK为内部研究证据，不是日常交付。大对象静态原件在`/private/tmp/thinkv2-voice-002-review/`。模拟器退出0，ADB5050及5051转发已关闭。

下一建议：保留正常产品基线，不继续为本候选投入产品集成；若指挥官日后考虑再次采用，须另单同时处理剩余主体识别错误与取得有权许可方对固定转换模型私人日常嵌入的实质许可澄清。未解决前保持暂停，不重问相同许可、不联系外部权利方、不自行开第二路线。小米15/父亲验证仍未完成。至此停止等待指挥官。

第一方变更的定向git diff --check通过；原始上游源文、许可及工具输出保留原字节，完整基线whitespace告警另归档，不为了格式更改来源证据。忽略的本地评估AAR/模型仅为复核对象，非默认依赖；清洁工作区结论指Git可见变更均已提交。
