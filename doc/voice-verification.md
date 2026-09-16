# V2-OFFLINE-VOICE-001 / CP2 验证记录（RESULT: BLOCKED；implementation_status: PARTIAL）

- 唯一目标：thinkV2，基线`cc957d56e8f5337d6548ed2b0296712fc0cbdcba`，分支`codex/v2-offline-voice`。
- attempt `a20f0582-3aaf-4952-8a15-876f2bd93297`；冻结合同SHA256 `1864fa5bbe273ed34e44b437f1d9bc158bc385d9df44c7c64646d51927a1ab86`，已按排序紧凑JSON复核。
- 完成条件保持V1–V7原门槛。当前结论：**准确率未达标，不能交付CP2 COMPLETE**。实现状态PARTIAL；BLOCKED表示本单质量门槛未通过，不表示整个App停止。

## 已知真实结果

- 离线Android Vosk加载与两轮静音测试1项通过，1.270s；app无INTERNET权限。不是中文准确率通过。
- 固定30段中文真实识别server通过76.226s，此处“测试通过”表示流程完成，不代表质量门槛通过。
- 原始专名47/66=71.21%，未达90%。全部样本、原始输出、逐段理由见`doc/evidence/voice/recognition-assessment.md`及JSON。
- 预先固定的75% meaningAnchors筛查proxy为30/30；人工逐段核对主体、时间、动作、否定/数量后严格工程可用性仅10/30，未达27。父亲接受=false，不以proxy替代需求。
- 错误集中于人名（陈晨→沉沉、刘洋→牛羊、林静→临近等），也有时间/数量/动作错误（晚上→外伤、两盒鸡蛋→俩和鸡蛋、亲戚寄东西→清洗剂东西）。没有重跑相同准确率碰运气或改题。
- 实时性能10×30秒：每段480000样本，100ms真实墙钟输入，完整解码尾耗时P95=235ms（94–235ms），达到≤5秒。稳定性20×90秒：每段1440000样本，全部完整接收、关闭来源且无失败。批次2115.216秒，server OK(1 test)。
- `measurement-summary.json`由独立校验脚本从原始记录重算；性能/稳定性通过，overall质量门槛失败。

## 实现边界与V1–V7

|条目|代码/行为|验证口径|
|---|---|---|
|V1|`VoiceControls`显式权限、按住说松手处理、取消；`AndroidMicrophone`只用AudioRecord；`VoiceBudget`1440000样本与90秒墙钟双上限|host故障/静音/边界；Android系统与UI验证单列|
|V2|`NotesModel.voiceAnchor/insertVoice/correctVoice`以editorToken+recordId+revision绑定；不覆盖选择范围原文；光标移到插入末尾且IME composing清除；不自动正式保存|host中间插入/连续两段/标题分类保留/新编辑拒绝/跨会话拒绝；真实中文UI另测|
|V3|短数组、最多20×1600 PCM队列；消费/清理归零；producer清理异常仍done，通知先于done；同一worker释放Recognizer；不创建音频文件|来源审查、文件/日志/权限/内存观测；OS级物理擦除Unknown|
|V4|本机文字纠错映射最多200项，每项80 UTF16单位；允许同音多候选；仅确认后作用于本次插入跨度，revision变更拒绝|不是模型热词、不是训练；词表备份留后续CP8，本页和备份UI明确范围|
|V5|固定Vosk0.3.75、JNA5.18.1、small-cn0.22，arm64-v8a/16KB；无其他路线取得/加载|详见`voice-runtime-review.md`，精确字节/摘要/许可/静态扫描及Unknown|
|V6|专用ThinkV2Voice模拟器，无私人资料、摄像头/主机麦克风关闭、飞行模式；测试侧内存PCM注入|不冒充真实手机麦克风；模型实际运行，不以mock替代识别|
|V7|冻结30段、专名按预标每次出现、真实100ms采集节奏测10×30秒P95与20×90秒|准确率/可用性FAIL；性能、稳定性通过；小米15/父亲待证|

## 音频夹具与身份

指挥官明确允许“系统自带离线TTS内存回调”仅作非私人测试素材；产品未加入TTS。源代码`fixtures/synthetic-voice.m`使用AVSpeechSynthesizer内存回调，转换16kHz单声道PCM16输出到pipe，不调用保存音频API。宿主调用加`sandbox-exec`限制网络和文件写入，成功执行。此限制覆盖调用进程，系统语音服务内部行为不能据此证明全覆盖，记Unknown。

显式声音`com.apple.voice.compact.zh-CN.Tingting`，rate0.45。首次查找name的旧校准返回super-compact，与恢复后声音不同，因此在任何评分前改成精确identifier并冻结plan；旧校准不计评分。AVSpeech返回float PCM，夹具以线性插值重采样到16kHz/PCM16；这是本次合成输入的固定组成部分，不代表真实麦克风采样质量。实际平台/声音、文本、音频时长、PCM SHA只作为身份记录，没有音频附件或持久化PCM。样本均自写非私人句子。

`run-memory-fixtures.py`只保留RAM数组（合成输入上限64MiB）；经专用ADB5050定向127.0.0.1:5589、host5051→localabstract测试socket进入instrumentation。App没有TCP监听或INTERNET权限。测试APK的socket仅显式指定测试方法时存在，不在产品入口。

准确率批次为快于实时内存注入，只报告engine吞吐。`lastReadToResultMs`是最后块读取后尾耗时，不能作V7 P95。性能批次先把合成PCM载入测试侧RAM，再由PcmSource按真实100ms墙钟节奏进入产品VoiceCapture，最后采集块产生时记录release，至完整结果（含队列积压和decoder释放）计时。30秒素材为CN01–CN10按序拼接前30秒；90秒素材按序拼接/重复到90秒，裁切方式已预先固定。不是自然人90秒连续口述，也不是物理麦克风验证。

## 测试与调试历史

- 标准离线构建、host91项与lint在build6通过；最终测试夹具改动后的全量标准命令亦通过，见final-build日志。没有为准确率失败调词表或改模型。
- 第一次Swift夹具编译因宿主CLT SDK/SwiftBridging不匹配失败，未产音频；改为同平台Objective-C单文件内存夹具，没有新建获取工具工程。
- 指挥官发现并已修复：release异常导致done不达；预取消仍短暂开麦；插入后光标不前移；processing通知晚到覆盖终态。均补host故障/时序/会话/光标测试。
- 一次自动审批因账号usage limit拒绝整条实时夹具修改/构建，命令未执行；未绕过。指挥官后续明确恢复同一attempt，先确认无存活qemu/测试（仅ADB5050存活），再恢复同一合成AVD。旧测试日志不当作仍在运行。
- 性能批次期间不运行Gradle/大型CPU任务，少量源码/报告编辑与目录/内存元数据观察；变更只涉及VM可注入捕获工厂、UI说明和许可，不改变VoiceCapture/OfflineEngine。最终包与性能包分别由apk-identities.json绑定，核心输入由performance-inputs.json绑定。

## 后续技术建议（未执行）

当前小模型的专名和自由口述能力已实际不足。[官方模型目录](https://alphacephei.com/vosk/models)中同路线更大的`vosk-model-cn-0.22`在SpeechIO/THCHS基准上CER低于small版本，但约1.3GB，移动端内存/加载成本更高；这不是本样本90%保证。建议由指挥官另行决定是否按同一冻结样本比较更大模型并评估小米15内存，取得/执行前重新固定来源、摘要和许可。本单没有取得或加载该对象。

现有Recognizer grammar接口是受限短语图重配置，未知词会被跳过，不等于任意自由口述热词偏置；把30段答案塞进grammar不构成真实泛化改进。确认式纠错是用户编辑便利，不能补算原始准确率。保留小模型并如实partial，比宣称达标更符合本单证据。

## 未验证

小米15 ABI/CPU/麦克风/权限/噪声/性能、父亲发音及接受程度、系统语音服务内部持久化细节、完整第三方二进制供应链与OS物理内存擦除均未证明。模型/运行库资产允许持久化，原始/中间用户音频仍禁止落盘和上传。

## Android 界面与回归补证

- 真实中文 UI：两段不同冻结样本CN01和CN28经实际Vosk进入正文指定光标处；连续插入不覆盖前段，手动标题保留，显式确认纠错仅作用最新插入跨度；取消、编辑变化、离开编辑器丢弃过期结果。61.120秒，OK(1)。测试输入为RAM夹具，非物理麦克风。
- 首次UI测试暴露语音按钮父子semantics分离，已合并为可访问的同一按钮后通过；保留ui1失败日志，不把失败记录删掉。
- 实际pointer down/up+AudioRecord静音路径、Activity后台取消、显式取消：8.420秒，OK(1)。Android权限拒绝不改变草稿：3.429秒，OK(1)。
- 实际新进程正常App词表对话框恢复李明→李明同学，见ui/wordlist-after-restart.png与保存的SharedPreferences；此前程序内确认已验证。
- 原有文字/分类回收站/持久化回归4项：15.829秒，OK(4)。提醒/备份最终回归及系统对话框证据见后续补证节。

## 性能包与最终包的关系

性能原包保存在外部本单目录`performance-verified-app-debug.apk`及`performance-verified-test.apk`，不覆盖。`performance-inputs.json`固定VoiceCapture、OfflineEngine、VoiceText与RealtimeFixture四个源码摘要；交付再次验证相同。后续变化包括VM的测试捕获工厂注入、按钮semantics、用户提示、许可资源及UI测试。最终包有独立摘要；不能声称整个最终APK重复跑过35分钟批次。

## 隐私与内存观测边界

- 本单运行的合成AVD飞行模式、Wi-Fi关闭、主机麦克风与摄像头关闭。产品无INTERNET权限。模型14个文件批次后摘要与manifest一致。
- 采集期间/之后应用文件列表未发现音频文件，仅模型、SQLite/日志、profile标志和测试文字结果。保存的日志环形缓冲区未发现抽查的合成正文标记；这不是所有时刻、所有第三方内部行为的完整证明。
- 30秒批次观测PSS 278451 KiB、RSS 388112 KiB；90秒批次观测PSS 316943 KiB、RSS 427280 KiB。测试完成后进程正常退出，因此末次meminfo为No process found；两个采样点不能证明绝无泄漏。
- 本实现只打包arm64-v8a。其他ABI、真实小米15和家庭噪声/发音未验收。

## 最终回归与系统权限补证

标准项目命令（README/AGENTS授权）使用Android Studio JBR、Gradle offline，assembleDebug、assembleDebugAndroidTest、testDebugUnitTest、lintDebug全部成功；最终11秒，91 tests / 0 failures / 0 errors。实际Android原有提醒UI 1项6.738秒、AlarmManager真实回调与固定时钟循环1项105.800秒、备份2项0.378秒均通过；原有4项另为15.829秒。本次没有重复完整的上一单SAF所有手工用例或真实设备启动矩阵，不扩大回归结论。

系统权限实际路径：新合成草稿→按住说话→Android权限弹窗→Don’t allow→App明确拒绝提示；再次请求→While using the app→提示重新按住。实际15秒pointer保持期间截得“录音中·松手处理”和系统麦克风指示，再由定向ADB撤销RECORD_AUDIO。撤权前PID10088，撤权后无进程；重新打开首页和编辑器均显示原草稿。before-revoke、after-permission-dialogs、after-revoke的全部数据库表快照完全一致（草稿1、正式笔记0）。这是Android撤权杀进程后的恢复验证，不声称杀进程前收到了应用错误回调。随后再次经系统弹窗授权成功。

截图/XML/数据库与进程记录见`doc/evidence/voice/ui/`：permission-request、permission-denied、permission-granted、recording-before-revoke、reopened-after-revoke、restored-draft-editor、reauthorized-after-revoke。均为专用模拟器自写合成资料。早期helper首次滚动未命中按钮，只是手工测试定位失败；重新定位并确认草稿已落盘后才进行权限操作。

词表正常UI补证：新增foo→bar并保存/重开；编辑为foo→baz并保存/重开；删除并保存/重开为空列表，见vocabulary-added/edited/deleted截图及XML。实际中文李明映射及确认替换另由真实解码UI测试覆盖。最终APK额外运行实际模型离线静音与释放测试1项，1.185秒，通过。

## 复现命令与归档

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --no-daemon --console=plain
python3 doc/evidence/voice/verify-evidence.py
```

Android只定向本单AVD：`adb -P 5050 -s 127.0.0.1:5589 shell am instrument -w -r -e class <完整测试类或类#方法> com.example.thinkv2.test/androidx.test.runner.AndroidJUnitRunner`。旧回归使用NotesUiTest、AndroidPersistenceTest、AndroidLifecycleTest、ExampleInstrumentedTest合批，以及ReminderUiTest、AndroidReminderTest、backup.AndroidBackupTest分批；真实语音UI、权限拒绝、按住与后台测试分别过滤。权限拒绝、提醒及备份各批先pm clear合成App；禁止对真实资料复用此清理命令。词表/权限手工证据用专用ADB helper，脚本归档在下述目录。

本单归档：`/private/tmp/thinkv2-V2-OFFLINE-VOICE-001-a20f0582/`。含完整logs、91项JUnit XML、lint报告、performance-verified-app-debug.apk、performance-verified-test.apk、final-app-debug.apk、final-test.apk、apk-identities.json、performance-inputs.json、manual-ui-helper.py和result.json。VoiceBudget定义在VoiceText.kt，已包含核心摘要。最终安装包的SHA256与归档最终包相同，见installed-apk-identities.json。证据树另含固定夹具plan及原始文字输出，未保存音频。

已关闭本单ThinkV2Voice模拟器（进程退出0）及专用ADB5050；未使用实体设备。交付Git提交、工作区状态与全证据摘要以归档result.json/evidence-manifest.json为准。此后停止等待指挥官下一单。
