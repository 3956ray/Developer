# CP5 AI标题与分类建议

任务 `V2-AI-SUGGESTIONS-001`，attempt `b14de6bf-bb39-4bae-a6b9-14953ec4a1ae`，合同 SHA256 `b9af9a330483d17c1e0bc6961a96201a04048fb752635cca3ed50c613477b06f`。基线 `84f3691f97efaae6e9fdbb7c81405e6525770e38`。唯一范围为thinkV2的CP5工程；真实供应商 `provider_verified=false`，小米15/家庭未验证，语音工作仍暂停。

## I1–I6矩阵

| 条款 | 实现与验证边界 |
|---|---|
| I1 配置 | `AiConfig/AiVault/AiModel/AiDialog`；新装及新进程默认关闭，HTTPS端点/模型/凭据可保存、关闭、清除。AndroidKeyStore AES-GCM密钥保护配置，密文仅在noBackupFilesDir；无明文或硬编码凭据回退。真实服务不可用不影响手工功能。 |
| I2 同意 | 启用前显示供应商完整地址、模型、目的和字段；保存配置会关闭AI，更换端点/模型须重输凭据。每次请求另核对完整JSON和字段。发送框及候选分类选择默认空，不自动复制笔记、日历快照、账户或音频。 |
| I3 人工优先 | `NotesModel.aiAnchor/acceptAi`绑定编辑会话、recordId、revision、baseRevision、标题人工标志和分类身份/所有权。响应不写笔记。标题/分类可分别接受、修改或拒绝；接受一项后只把锚点更新为本次明确接受产生的新版本，保留另一项；其他人工修改使旧结果失效。新分类仅明确确认后在事务中创建。 |
| I4 请求边界 | `AiCall/AiProtocol/AiJson`异步真实HTTPS POST，无重定向/重试/备用供应商。取消、关闭、后台中断均丢弃晚到输出；已发送文字不能撤回。严格受限建议结构，无动作、脚本或正文替换执行通道。HTTP/网络/超时/解析错误不写笔记。 |
| I5 工程证据 | host存储/协议测试与专用模拟器实际UI、Keystore、Android TLS网络适配。合成HTTPS夹具明确标mock；不证明真实模型质量。逐项结果见下文及evidence/ai。 |
| I6 真实条件 | 用户尚未提供实际端点、模型、凭据及测试同意；没有真实服务请求。需用户配置并同意后用合成单条笔记验证服务兼容、额度和质量，小米15/家庭另行补证。可永久关闭AI。 |

## 支持协议与参数

支持用户明确配置的Chat Completions兼容端点，完整HTTPS URL须以 `/chat/completions` 结束；不接受userinfo、query、fragment、转义路径、`.`/`..`路径段或非HTTPS。TLS使用系统信任及默认主机名校验。Bearer凭据仅发送到该已确认端点，所有3xx均拒绝，不追随Location。

请求JSON字段：`model`、`stream=false`、`store=false`、`max_completion_tokens=256`、`response_format={type:json_object}`、`messages`。messages包含固定SYSTEM提示和用户数据JSON `{text,categories:[{id,name}]}`；没有recordId、revision、完整数据库、日历快照或来源账户。`store=false`是协议请求参数，不保证第三方供应商不留存；启用同意告知供应商可看到发送字段和网络地址。

响应只消费单个 `choices[0]`、`finish_reason=stop`、assistant消息的JSON内容，禁止tool_calls/function_call/refusal；内容必须恰好有 `title`、`category_id`、`new_category`。标题最多120字符、新分类最多80字符，现有分类ID必须来自发送候选，两个分类字段不能同时非空。拒绝空结果、重复键、无效UTF-8/代理对、尾随JSON、控制字符、HTML/代码围栏和额外动作字段。普通字符串即使含恶意指令也只是不执行的候选文字，仍需用户审阅；不宣称能判断所有语义提示注入。

连接超时5秒，读取间隔超时10秒，总传输时限20秒；文字最多8KiB、完整请求32KiB、响应64KiB、候选分类最多50个，JSON深度8、节点512、每对象/数组最多64项。超限拒绝，不截断后假装完整。模型须支持上述参数及JSON对象模式，不兼容时明确失败，没有规则或mock降级输出。

## 持久化、取消与备份

schema5→6仅新增 `ai_acceptances`，保存已接受的requestId、recordId、字段、供应商/模型、请求哈希、原提议、用户最终选择、时间和笔记版本。无凭据/发送正文/临时响应存储。正文不改；最终标题/分类采用人工所有权，AI来源另存以保留后来人工修改的区别。

接受操作是一个SQLite事务：核对笔记与候选分类版本，必要时明确创建分类，写草稿和来源记录。存储失败全部回滚，重复接受不重复写。正在保存时关闭按钮、系统Back和模型close/cancel均不取消已经确认的事务；结束后释放saving状态。请求阶段取消会使generation失效，后台结果不能套用到另一笔记或新版人工内容。

原备份格式不扩展。最终文字/人工标题/分类仍按既有字段导出；AI设置、凭据、接受来源记录和未接受建议不在备份中。恢复不能声称恢复AI来源；需后续独立CP8兼容任务。Android平台密钥保护不等于内存字符串物理擦除，不宣称供应商已删除发送数据。

## 环境与可复现测试

专用 `ThinkV2Ai` AVD，Android37.1 arm64，ADB server5050，唯一serial127.0.0.1:5589，飞行模式/关闭Wi-Fi。仅通过已知ADB reverse把模拟器localhost18443映射到本机loopback18443。没有枚举真机、私人日历、账户或真实AI凭据。

合成Python标准库HTTPS服务仅监听127.0.0.1；模式由synthetic-*模型名选择。每次凭据为运行时随机字符串。服务只记录 `authorization_present` 布尔值及明确合成请求体，不保存Authorization值。用于测试的localhost证书只位于androidTest assets；测试注入只信任该证书的socketFactory，保留默认主机名校验。产品无自签证书信任入口、无trust-all，无测试服务URL/证书/密钥。系统默认信任拒绝测试CA、错误主机名也失败。

标准命令：

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --no-daemon --console=plain
```

设备测试只定向此合成模拟器：

```sh
adb -P 5050 -s 127.0.0.1:5589 shell am instrument -w -e class com.example.thinkv2.ai.AiDeviceTest#METHOD com.example.thinkv2.test/androidx.test.runner.AndroidJUnitRunner
```

每个状态用例从清空后的合成App数据开始；通过后立即导出ai-*.json/PNG。原始日志单独归档。最终数值与APK/commit在交付时绑定，不将早期测试冒认为后续版本。

### 已知测试修正

- 旧schema2夹具需删除新ai_acceptances表再模拟迁移；修正夹具后通过。
- 端点host测试发现URI.normalize不能拒绝根部`/../`，产品新增显式路径段检查。
- 延迟保存测试首轮误把独立笔记模型与主界面模型混用，建议被正确判过期；修正测试UI绑定，不放宽产品版本检查。

## 最终结果

**工程范围完成；provider_verified=false。** 当前没有真实供应商配置、凭据或测试同意，未发送真实服务请求，不宣称真实AI质量或整个App完成。

- 标准build7：SUCCESSFUL，35秒；107 host / 0失败 / 0错误 / 0跳过，lint 0错误、19警告，保留原始报告。
- 最终8项AI Android用例全部通过：配置/最小字段同意与分别接受；TLS/离线/401/403/429/5xx/非法/注入/空/超大/重定向/取消；编辑/切换/关闭/接受顺序；Keystore与清除/新会话关闭；延迟事务系统Back；配置更改重新同意；持续慢响应总时限；拒绝及修改后明确新建分类。
- 总时限实测：每500ms回1字节，20,030ms后请求线程终止，无建议写入，验证的是默认20秒总时限。另慢首字节用例验证10秒读取超时。
- 最终13项既有Android回归通过：文字/分类/回收站/提醒/备份8项，日历3项，语音生命周期2项。语音只回归取消/后台/权限和草稿保护，不重启语音质量或候选模型任务。
- 真实force-stop/重新启动：已保存配置可见但AI关闭，2份草稿和3条接受来源记录的完整本地表快照前后相同。
- product APK SHA256 `11d679ba6c4bb16e3008e01cf311b1a2bc799a0896ac2eb8c8e126b8ddf8b543`；test APK `3afd75fddfc68539e0a352ff6be31b0520575b88f043551411537c8456c789de`。标准产品仅既有Vosk/JNA/Compose native库，无候选ASR、无测试证书/私钥；无新第三方依赖。

证据在 `doc/evidence/ai/`：device/下8项日志、状态摘要与UI控件截图，regression/下回归日志，ui/下真实重启PNG/XML/完整合成数据表对照；host-summary、android-summary、lint-results、APK权限/库检查、构建日志和合成请求字段。`synthetic-requests.json`为本单多轮累计合成服务捕获，具体最终行为由逐用例断言绑定；请求头仅布尔状态，没有凭据值，也没有PRIVATE_ACCOUNT_SENTINEL或redirect-trap请求。

外部归档 `/private/tmp/thinkv2-V2-AI-SUGGESTIONS-001-b14de6bf/` 保存最终APK、原始日志/host XML、合同、测试脚本和最终commit绑定的result.json。TLS测试私钥不入仓库或交付归档；公开证书仅test APK使用，复现时应重新生成localhost测试证书并重建测试包。仓库日志只整理行尾空白，原始输出另存。

收尾停止本单合成HTTPS服务、AVD和独立ADB5050，不触碰私人设备。下一步由指挥官审查CP5交付；真实供应商合成单条验证、最终小米/家庭及CP8来源兼容保持独立待办，不自动开始下一单。

API依据：[Chat Completions](https://developers.openai.com/api/reference/resources/chat)、[Android Keystore](https://developer.android.com/privacy-and-security/keystore)。本单未调用文档供应商的真实模型服务。
