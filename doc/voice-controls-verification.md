# CP2 显式本地语音控制验证

任务 `V2-VOICE-CONTROLS-001`；attempt `ee161236-6248-44c9-b597-d8842df793a2`；冻结合同SHA256 `c4ea01635c9faf6e1881938f84ddb5414c236760f2e4f162bed6d38ae528cdd9`；基线 `7eb5b4dafe891d10fe65a15c5f67b01c0d2ab3ae`。仅PM保留的独立语音控制子单，不代表整个CP2、通用ASR质量或完整App验收。

## 行为与安全边界

编辑器默认普通口述，明确点击“进入命令模式”才识别三个本地口令。当前模式有文字标识，命令录音按钮与普通口述区分；两者仍使用既有按住/松手、最长90秒的有界内存采集。命令模式不会插入正文，也不发送AI、联网识别或任意执行。

`VoiceCommand`仅完整匹配“新建笔记／保存当前草稿／取消本次输入”。只忽略Vosk词边界空白，输入最多128字符；不做同义词、模糊、包含匹配或标点剥离。长句中出现命令短语仍属未知。普通口述从不进入该解析/执行分支。

| 状态/事件 | 结果 | 主要证据 |
|---|---|---|
| 普通口述包括命令词 | 原始识别文字插入当前草稿；不自动保存/新建 | 真实引擎UI与脚本状态测试 |
| 显式进入命令模式 | 绑定当前editorToken/recordId/revision，保留此前纠错建议 | 状态、纠错保留测试 |
| 空/未知/失败 | 明确提示，零命令执行；权限不可用同时退出模式 | parser、实际权限拒绝、未知语句 |
| 匹配三个完整口令 | 显示原始识别文字与意图，等待确认 | 三张真实引擎确认界面截图 |
| 确认新建 | 成功持久化原草稿后才切到新空白编辑器 | 真实SQL与故障触发器、真实识别UI |
| 确认保存 | 再核正文非空；沿用正式保存事务，成功后显示已保存 | 空正文/不执行/保存失败与重试/真实识别UI |
| 确认取消或退出模式 | 仅结束当前输入/命令会话，不调用discard、不擦除原文 | 逐字段比较及纠错建议仍可用 |
| 编辑/换记录/后台/撤权/重复识别 | 旧结果或确认票据失效；确认消费一次，迟到结果不执行 | 状态/真实麦克风生命周期/撤权重启 |

`NotesModel.finish`增加NEW动作复用原草稿持久化路径，并提供完成回调；失败保留当前编辑和明确错误。确认时先消费命令票据，再提交已确认动作；重复点旧票据不再调用存储。异步保存一旦用户已确认并提交，不把后台取消误当数据库回滚承诺。

命令模式、识别意图与同意只在RAM中。没有新增schema字段、SharedPreferences或备份字段，仍为SQLite schema8和备份v2。跨功能用例在有提醒、日历来源、AI接受来源、关系的旧笔记上确认新建，整个v2 payload前后字节相等，既有提醒请求仍开启；不称已重新验证真实提醒送达或供应商服务。

## 证据分层

### 真实当前引擎

复用已准入Vosk 0.3.75/JNA 5.18.1及现有中文模型；没有grammar、新运行库、新模型或SenseVoice。固定`fixtures/plan.json`为三个口令和一个包含命令短语的普通句子；不重开V7质量评估或调参。

合成素材沿用已审阅系统离线TTS内存回调：明确声音 `com.apple.voice.compact.zh-CN.Tingting`、rate0.45，线性重采样16kHz单声道PCM16。第一方夹具进程由sandbox-exec禁止网络和文件写入，PCM仅在stdout pipe、宿主有界RAM及专用ADB localabstract测试socket中。输出只有文字、样本身份/时长/PCM SHA256和测量，没有音频附件。系统语音服务内部行为与OS物理内存擦除仍非完整可观测，不做新增全覆盖承诺。

独立样本测量使用墙钟节奏的产品VoiceCapture/实际Vosk；控制器测试再把四个样本送入实际VoiceModel、Compose确认和AndroidSQLite。第二次合成的身份单独保存，并核对host与Android SHA相等，不假定两次生成字节必然相同。普通口述路径复用SAVE样本，证明相同声音在普通模式仅插入正文。

`recognition-results.json`保留原始输出，未做词表纠正；`real-ui-input-identities.json`与`device/voice-controls-real-engine-ui.json`绑定控制器那一轮输入、输出和执行结果。**仅本次固定3条合成命令匹配，不是一般准确率、真实发音误触率、P95或V7阈值通过。**

### 状态与错误注入

`VoiceCommandStateTest`使用脚本decoder和非语音RAM样本，只证明状态与持久化保护，不能当真实识别。真实SQLite失败触发器分别阻止草稿写入与正式笔记写入，确认编辑仍在、未切换/未假报成功，重新识别确认后才成功。脚本late callback、重复票据、编辑/换记录及纠错保留与真实引擎证据分列。

`VoiceCommandLifecycleTest`使用真实AndroidMicrophone验证拒绝权限和后台/退出取消，只称静音/生命周期证据，不冒称物理麦克风中文识别。

## 环境、命令与隐私核对

本attempt全新独立目录ThinkV2VoiceControls，Android37.1 arm64，2CPU/2GiB，1080×1920/420dpi，主机麦克风/摄像头关闭，飞行模式与Wi-Fi关闭；仅ADB5052 / serial127.0.0.1:5593。所有笔记、来源及语音素材均为合成，无私人设备/资料。

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --no-daemon --console=plain
adb -P 5052 -s 127.0.0.1:5593 shell am instrument -w -e class com.example.thinkv2.voice.CLASS#METHOD com.example.thinkv2.test/androidx.test.runner.AndroidJUnitRunner
```

生产manifest、依赖、模型/运行库不变。当前App的INTERNET权限来自之前已验收的可选AI，不把历史语音任务“无INTERNET”声明用于当前版本；命令代码没有网络/AI调用，测试断网运行。模型校验/解包写入仍仅为既有模型文件；采集队列和测试PCM为有界数组，释放时清零，命令文字不写日志/数据库。App可访问目录观测只见模型、SQLite、profile和明确合成测试的PNG/JSON，未见音频扩展名文件；这不是对系统或第三方内部所有行为的完整证明。

## 最终验证与产物

- 离线assembleDebug / assembleDebugAndroidTest / testDebugUnitTest / lintDebug通过；127项host测试，0失败/错误/跳过；lint为0错误、18警告。
- 本单9项Android测试通过：5项脚本状态与真实SQL、2项真实麦克风生命周期、1项实际Vosk命令确认UI、1项实际Vosk固定4样本测量。另14项既有回归通过，包括普通口述真实引擎的光标插入/纠错/取消/迟到编辑，以及笔记、提醒、备份、日历、关系、恢复状态。
- 固定三个口令原始识别分别为“新建 笔记”“保存 当前 草稿”“取消 本次 输入”，均匹配；包含命令短语的负例完整识别后不执行。四样本松手至结果分别127/31/13/36ms，仅此次样本测量，不是P95或质量评分。
- 实际录音期间撤销OS麦克风权限：Android终止PID19927，重开为PID20840；命令模式默认关闭。撤权前/后/重启后三份持久化JSON逐字段相等，1条合成草稿、0条正式笔记；操作性reminder_runtime时间戳不在比较范围。不声称进程死亡前收到了App回调。
- 产品APK SHA256：`8f308ec5111f9037c44bb8208d81f1254bdf65ebf8a80011d5c532fa76f53f2c`。实际安装APK与归档字节相等；所有Android证据均来自此产品。
- 最终测试APK：`b71626813069fa09663614d53dd117fdfa1289e6864f25f16352c0e5fe0d6456`。最后命令UI/普通口述UI使用此包；其余设备测试使用前版`1831d2cf099d2a6ce6bfcea71ec088d92f35c001b78972d6a59c0bc93486452d`。两版测试源码只差确认弹窗截图前等待绘制。
- build6未修改产品源码，但重打包产生不同产品DEX字节，SHA为`1cc6ca1cdc3b4404e23f8203daf618ce81aea457493769ac7d11d1a1b240ed90`；此包未安装、不作为交付，仅在外部归档保留。不承诺构建字节可复现。
- 生产manifest/模型/native共19个ZIP条目与CP8基线逐字节相等。未增加依赖、schema或备份字段。证据索引为`evidence/voice-controls/manifest.json`，统计为`validation-summary.json`，清理为`cleanup.json`。

交付归档：`/private/tmp/thinkv2-V2-VOICE-CONTROLS-001-ee161236/`，包括合同、产品/两版测试APK、原始日志、证据、patch和绑定最终commit的result.json。临时归档需要长期保存时应由接收方复制。仓库证据日志只移除行尾空白与文件末尾多余空行，原始字节另存；音频未保存。

### 修改文件用途

`VoiceCommand.kt`定义精确白名单与一次性预览票据；`VoiceModel.kt`管理模式、采集与确认失效；`VoiceControls.kt`显示显式入口、识别意图与确认；`NotesModel.kt`复用原持久化路径完成保留草稿/保存。新增parser/NotesModel host用例与三个Android测试类；README、本文和独立证据目录记录能力与边界。其余产品模块不改。

## 过程修正与剩余条件

首次编译发现新增测试decoder参数影响既有尾随lambda，调整参数顺序保持原采集夹具兼容。Compose lint在枚举构造器属性解析中崩溃，改为等价枚举getter后标准lint通过，未关闭检查。实际拒绝权限测试暴露命令错误被泛化成会话变化，改为明确权限错误并退出模式，保留初轮失败和复测日志。

确认弹窗初轮截图早于绘制，虽然确认按钮操作与SQL断言已通过，但截图未作为最终证据；仅测试加入语义节点/空闲等待后重跑实际引擎UI，三张PNG已肉眼确认有完整弹窗，原图保留诊断归档。

小米15实际麦克风、真实口音/发音误触率、TalkBack与家庭使用仍未验证；通用ASR质量保持未验收，Voice002仍parked partial，provider_verified=false。本单完成只建议指挥官复核此有界工程交付，然后停止，不自动开始UI细化、质量研究或新Checkpoint。
