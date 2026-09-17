# 新电脑构建与验收恢复

## 在线恢复许可安全版本

```sh
git clone -b codex/migrate-thinkv2-20260917 https://github.com/3956ray/Developer.git Developer
git clone -b codex/thinkv2-public-history https://github.com/3956ray/Developer.git Developer/thinkV2
```

随后在[迁移Release](https://github.com/3956ray/Developer/releases/tag/thinkv2-migration-20260917)下载public-acceptance-evidence.tar.gz和PUBLIC-SHA256SUMS.txt，先用`shasum -a 256 -c PUBLIC-SHA256SUMS.txt`核对，再使用本指南的restore_evidence.py还原。最终发布与回下载校验结果见PUBLICATION_RECEIPT.json。

公开安全历史不包含原始SenseVoice词表，不能代替完整私有包；旧机器清理前仍需离线/私有复制原件。GitHub main保留其他任务的工作区内容，thinkV2在以上两个明确分支。

## 先确认交付层

用户已批准公开许可安全交接资料；以PUBLICATION_RECEIPT.json确认远端发布结果。完整原始历史仍需先将`PRIVATE_TRANSFER.md`列出的本地包通过用户选择的离线/私有渠道复制到新机器；校验包SHA后使用。不要以旧机器`/private/tmp`路径作为长期恢复来源。

完整原件：解开私有转移包后用显式分支克隆，保留原18个commit与13个开发分支：

```sh
git clone -b codex/v2-reminders-usability /你的目录/thinkV2-original-all.bundle thinkV2
cd thinkV2
git rev-parse HEAD
# 应为88d38616c5a5947d4ca798d4039c6368fa465624
```

许可安全副本：经批准交付公开证据包后，可验证并恢复：

```sh
python3 restore_evidence.py --archive /你的目录/public-acceptance-evidence.tar.gz --verify-only
python3 restore_evidence.py --archive /你的目录/public-acceptance-evidence.tar.gz --output /你的目录/restored
# 还原的archives/保存13个正式归档的许可安全文件；history/包含安全历史bundle。
git clone -b codex/v2-reminders-usability /你的目录/restored/history/thinkV2-public-history.bundle thinkV2
```

安全历史HEAD应为6cfd50f5…，原HEAD与公开HEAD对应关系见JSON。不要宣称原始完整Git历史已上传。此bundle所有默认产品文件均与88d3861相同，仅缺未准公开的评估词表，不影响正常构建。

## 本机工具与版本

原验证机使用macOS Apple Silicon、Android Studio JBR OpenJDK25.0.2、Python3（标准库sqlite3供host测试使用）。以下是**原机已安装/已验证值**，不代表新机已验证：

- Gradle Wrapper9.5.0（仓库内固定SHA）；AGP9.3.2、Kotlin2.2.10、Compose BOM2026.02.01，详见版本目录。
- SDK platform `android-37.0`（compileSdk release37），Build Tools36.0.0、platform-tools37.0.1。
- Emulator37.1.11；原测试系统映像`system-images;android-37.1;google_apis_playstore_ps16k;arm64-v8a` revision9，16KB页。
- 产品只包含arm64-v8a。其他架构/新系统映像需独立验证，不继承旧模拟器结论。

从官方Android Studio/SDK工具安装所需版本。许可证与账号在新机自行确认，不迁移SDK缓存/用户凭据。设置JAVA_HOME为新机JBR目录，在未跟踪的local.properties填入新SDK的sdk.dir；原绝对路径不能直接照用。不要复制旧debug.keystore。

```sh
# 首次需允许Gradle从项目配置的仓库取得固定依赖；旧机缓存没有迁移。
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --no-daemon --console=plain
# 缓存齐备后可沿用AGENTS中的--offline命令。
```

本次原机127host测试通过、lint0错误18警告。新机首次构建尚未执行；debug签名/工具缓存/时间戳变化意味着重建APK不保证与归档APK逐字节一致。验收恢复用归档中原APK的精确SHA，而非假装新签名产物相同。

## 模型与许可

日常Vosk0.3.75、JNA5.18.1及vosk-model-small-cn-0.22在安全Git历史中保留，沿用Apache2许可/NOTICE；不必重新找其他模型。需要重新取得时只使用`doc/voice-runtime-review.md`固定的官方Maven/模型URL及SHA，先静态核验，不自动换版本。必要的许可材料位于app/src/main/assets/licenses和doc/evidence/voice。

SenseVoice词表、权重、评估APK及分发义务未解决的Sherpa评估输入**不得从公开包恢复执行或重新分发**。私有完整包保留历史研究原件不等于新许可。`-PsenseVoiceEvaluation=true`仍需新的明确准入，不自动下载、不重跑质量评估、不改变私人嵌入PAUSE。可读的来源/版本/哈希和失败结论保留在doc/voice-002-review.md。

## 设备与证据

重建干净、无私人数据/账户的专用模拟器；音频输入/相机关闭，尽可能保持原配置。不要复制约20GB旧AVD磁盘，也不要使用会枚举真机的泛化connected测试。ADB必须显式指定专用服务端口和serial。

历史fixture脚本中的`/Users/orderly_ray/.../adb`、端口与serial需在**新attempt副本**中调整，不覆盖已验收证据/manifest。新运行输出到独立目录。CP7入口是doc/reminders-usability-verification.md；其他历史报告保持各自边界。恢复测试前核对源/目标包哈希，原APK和test APK需按同一报告中的版本对应关系安装。

无原始音频迁移。系统离线TTS生成器源码和证据在私有补充包；新OS/voice版本可能产生不同PCM，不能把重跑视作同输入或继承ASR质量。自然日/周周期、小米15、家庭、真实TalkBack以及provider_verified仍保留原缺口。

## Agent与任务重绑

保留Developer根AGENTS/.codex与项目自身AGENTS。所附karpathy-guidelines可由新任务显式读取，按实际路径配置；不迁移全局Codex配置、登录态或聊天数据库。scan-untrusted-code完整个人副本仅放私有技能包，静态审查新输入时可单独读取，不自动运行。

在新机分别建立Leader、PM和dev新任务，填写thread-rebinding.template.json，人工确认三者能互通。旧ID只作历史引用；不能通过克隆Git恢复旧聊天运行态、授权上下文或调度器。以新的Leader合同为开发入口，CP7待验收、Voice002 parked partial等状态不得自行提升。
