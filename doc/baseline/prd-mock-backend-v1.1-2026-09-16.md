# 动态演示后端 PRD 增量 v1.1

Owner: Product Lead；PM，指定dev实现
Last updated: 2026-09-16
Source: [用户授权](../../raw/SRC-20260916-gym-mock-decision-01.md)；[当前源码](../../raw/SRC-20260916-gym-backend-code-01.md)；[补充决定](decision-mock-backend-v1.1-2026-09-16.md)
Confidence: High（现有边界）；Medium（新增功能待实现）
Related decisions: GYM-MOCK-BACKEND-DECISION-001；GYM-MVP-PRD-001
Next review date: 2026-09-30

Status: FINAL_FOR_REVIEW；Decision: APPROVED方向，待指挥者验收本增量
Research Quality: 90/100（内部决策证据质量，见补充决定评分；非市场需求评分）
Validation Level: V0；合成演示不升级真实门店验证等级

## P1. 目标与最小架构

用户能在原生小程序浏览变化的忙闲和课表，以演示用户申请会员关联，以演示维护者核验、撤销和更新；关闭应用或重启后端后数据仍存在。此“动态”来自**服务端真实写事务**，不是前端随机数、固定成功响应或页面每次刷新重新生成。

数据链：`演示脚本/原生操作 → 既有HTTP API/领域服务 → SQLite → 同一公开及本人API → 小程序`。未来数据链：`馆方导出/API/数据库 → 专用适配器 → 校验/候选 → 维护者确认 → 既有领域写入 → 同一API`。不重建现有业务，不引入消息队列、通用ETL平台或第二套业务数据库。

演示仍为“场馆／我的＋受控维护页”，中文主界面，醒目轻量“演示数据”标签；状态大字、来源/更新时间和课程保持主次。会员页标“演示会员资格”，演示核验按钮解释“仅验证流程，不代表真实馆方核验”。未登录公共浏览继续可用。继续不做数字人数、定位、门禁、训练、支付或预约。

## P2. 模式、身份及权限

| 维度 | 演示模式 | 未来正式模式 |
| --- | --- | --- |
| 配置 | environment=test，simulation=true，identity.mode=test，identity.appId=test-app，新增显式demo.enabled=true | environment=store，simulation=false，identity.mode=wechat；demo关闭 |
| 状态目录 | `.runtime/test/<gymId>`，独立DB/密钥；gymId使用`demo-*` | `.runtime/store/<gymId>`独立DB/密钥，不把演示DB改配置迁入 |
| 登录来源 | 维护者CLI发行短期一次性演示凭证；不调用wx.login生成此凭证 | 客户端wx.login→服务端官方code2Session |
| 业务鉴权 | 复用真实session、role、限流、撤销、审计与删除 | 同一领域规则；官方身份独立验证 |
| 标识 | 页面及响应明确simulation和identityMode；原生演示入口需客户端显式demo配置及服务端相符 | 无演示入口；拒绝演示凭证，不因官方错误回退 |

沿用当前test/store和test/wechat分支，不新增“假官方”模式。服务端是最终边界，前端隐藏按钮不足以隔离。`demo.enabled`与test/simulation/identity不匹配时启动拒绝；缺配置不自动启用演示。既有test fixture自动化测试可保留，其注入能力不能成为普通HTTP入口。

**最小身份流程**：

1. 新增本地CLI `demo.mjs init <gymId>`，只创建全新demo目录并启用上述配置；目录已存在则返回明确结果，不删除/重置。重启只用已有config；不重复初始化角色、会员或课程。
2. `demo.mjs ticket <config> <actorAlias>`为允许的合成主体`member-a / member-b / operator-a`发行32字节安全随机base64url凭证，TTL=300秒；只输出一次供操作者手动输入演示登录页。摘要绑定环境/门店/合成主体，数据库只保存HMAC、issuedAt/expiresAt；不得硬编码到客户端、fixture文件、Git、截图或日志。到期凭证在下次小时清理删除，即使旧login_codes台账被清理也不能复活。
3. 原生“演示登录”说明用途并提交凭证至**既有**`POST /v1/sessions/exchange`，复用consent/notice及session签发。test adapter检查演示凭证而非接受任意wx.login code。只有notice/consent及格式校验通过、未被前置限流拒绝、成功写入既有login_codes尝试台账的交换才消费凭证；用户拒绝不发请求，服务端CONSENT_REQUIRED也不消费。消费后即使adapter或建session失败也不能重试旧凭证；并发最多一次成功。响应丢失由维护者发新凭证；现有5会话上限和持久登录限流不变。
4. 新鲜认证同样通过新凭证完成，不能把5分钟敏感操作规则关闭。客户端不自报userId、memberKey或role。首次演示operator登录后，仅本机维护者通过既有role CLI显式授予；ticket命令不授予角色，不根据alias自动恢复被撤销或删除的角色。
5. 演示账号删除后，同alias新登录生成新userId，会员和operator不恢复。deleting期间不能复建。保留删除回执、会话撤销及最小资格防复活规则。

允许继续复用现有`wechat_identities`表保存test命名空间内的synthetic subject，这是历史物理表名，不表示这些记录来自微信。无需为表名重构；域文档和界面必须标识真实来源。演示会话仅在本scope使用；缓存scope新增gymId/identityMode或等效命名空间识别，后端不匹配时清会话并关闭演示动作，不能只相信前端标签。

## P3. 动态场景及操作方式

默认无自动后台循环。提供本机CLI场景`start / step / status / resume`；拥挤场景可显式`play --interval-seconds 60`，执行有限三步后停止。真实写动作调用既有HTTP API；身份和初始角色引导例外仍走服务端受控流程，不用SQL替代领域校验。start/step/play/resume的每个管理写步骤均使用当前operator会话，由既有服务端逐请求重检权限；会员本人步骤使用对应会员自己的有效会话，不能用operator冒充本人。会话由操作者通过标准输入或0600临时文件提供，仅进程内使用，不写run记录、不写命令行或日志。401/403即标暂停，resume不自行发ticket/session或grant角色；需维护者重新认证、提供当前会话并显式恢复。CLI不公开到HTTP，不接任意SQL/脚本。未来每个场景的README给确切命令和预期状态。

| 场景 | 操作序列 | 必须可观察的持久结果 |
| --- | --- | --- |
| DYN-O 忙闲变化 | 从无记录开始；operator发布较空→适中→较忙；再由操作者暂停/撤回；另一次发布后停止更新 | 每步revision递增、observedAt/validUntil来自服务器；两个客户端刷新同值；停止后900秒自然过期，无读请求续期 |
| DYN-M 会员流程 | member-a申请→operator输入合成会员引用核验→撤销→新请求/显式恢复；member-b尝试绑定已占引用 | 配对消费、绑定/registry版本、撤销及恢复真实落库；抢占409；不因为切换alias自动成为会员 |
| DYN-S 课表变化 | 今日及本周草稿→发布→取消一课/改期另一课→再次发布→撤回；另备明确无课与覆盖外状态 | 未发布草稿不可见；同稳定courseId更新；公共只见当前快照；冲突不覆盖旧草稿 |
| DYN-F 故障和生命周期 | 同一写请求重放/不同body；停后端→刷新→重启；主动退出/拒绝登录；解绑/删除单独确认执行 | 重放不重复，409不丢数据，断网显示无法确认，重启读原状态；删除后无角色/资格复活 |

初始课程日期以运行者明确选定的`anchorDate + Asia/Taipei`生成未来/当周UTC时刻；不得每天重启悄悄挪动原课。纯合成课程名称、引用和主体，不填真实姓名/人脸/手机号。演示脚本不虚构馆方联系信息。

演示进度保存runId、scenarioVersion、anchorDate、stepId、operationId、请求创建时间/最小安全参数、状态与最后已确认revision；不得保存登录凭证/令牌/明文配对码。配对码由当前会话按既有API取用，过期即暂停并要求新请求。每个scope只允许一个执行中的场景runner，竞争返回409；进程终止后标示中断，维护者显式resume，不在服务启动时重放全部场景。

已执行业务与进度写入之间中断：恢复时用原operationId查询并对账；不自动新key。超过intent窗口且确定未提交的步骤要求重新确认，不能把旧观察时间改新。已提交但台账清理后无法确认的操作标needs_review，不能盲重放。角色撤销、限流、版本冲突立即暂停当前场景，避免后台脚本覆盖用户手改；不自动重新授予operator。场景默认不自动执行账户删除、角色撤销、会员抢绑或覆盖测试数据，这些作为明确命名的人工确认验收步骤。

工具层不新增客户端调时/缩短TTL接口。900秒自然过期至少一次实际演示；899→900等精确边界沿用服务端受控时钟工程测试。长等待不要求操作者停留页面，回前台按现有规则读取。

## P4. 数据存储与保留

| 内容 | 既有表／新增逻辑记录 | 写入归属及重启行为 |
| --- | --- | --- |
| 身份/会话/角色 | identity_namespace、accounts、wechat_identities、sessions、operator_roles | 后端；演示也是独立持久身份，重启不重授角色 |
| 忙闲 | observation_head、observation_events | 领域服务；读、场景恢复和初始化均不重置TTL |
| 会员/删除 | member_registry、bindings、pairings、deletion_jobs | 领域服务；合成memberRef也按既有HMAC保存，不把原始引用写日志 |
| 课表 | schedule_draft、schedule_snapshots、schedule_head | 草稿、发布、撤回经既有角色/版本/事务 |
| 操作/权限/清理 | operations、audit、rate_buckets及已有cleanup记录 | 延用原保留规则；演示不以清DB绕过限额 |
| 演示凭证 | 新增demo_login_tickets或等效新增字段/表 | 本机CLI发行，test adapter消费；300秒即失效，小时清理；与自动化fixture区分 |
| 演示进度 | 最小demo_runs及步骤记录 | 保存安全参数与结果引用；终态30天清理，进行中/待确认保留；新建显式run不重置业务数据 |
| 导入 | source_bindings、source_entity_map、import_batches（逻辑名，可合并实现） | 来源配置/所有权、externalId映射、应用游标和候选摘要；确认导入与草稿事务原子提交 |

**课表来源落点**：新增迁移为草稿和发布快照增加provenance摘要（JSON列或等效关联），历史记录默认manual。导入apply与草稿同事务写入kind=import、sourceId、安全sourceLabel、batchId、sourceRevision、mappingVersion、capturedAt、importedAt；publish同事务将当时摘要复制到不可变snapshot。后续人工编辑草稿标kind=import-edited，保留导入来源/原capturedAt并另记editedAt；纯人工草稿为manual。新导入替换为新来源摘要，不通过改旧snapshot追溯“更新”。`/v1/schedule`保留兼容的source=gym-schedule，新增公开provenance仅含kind/sourceLabel/capturedAt/importedAt/editedAt及simulation，不公开batchId、外部键或操作者。候选/批次明细清理不删当前草稿/快照自带来源摘要；历史快照随既有保留规则清理。没有导入时不得伪造capturedAt。

新增表通过**新迁移**，不编辑001–005历史迁移或丢弃现有DB。所有外键/唯一性按environment/gym/source作用域处理；现有每scope独立DB可避免重复存储环境列，但API和导入仍核对scope。不新增自动备份承诺。

导入批次含schemaVersion、sourceId、sourceRevision、内容SHA-256、mappingVersion、capturedAt、状态、准备时本地版本及安全错误码。候选24小时后不可apply；未应用候选随后小时清理，已应用明细30天后清理。保持每source最后已应用revision/hash和稳定键映射，用于拒绝乱序/重放；清理不重置游标、不复用已取消课程ID。实际清理实现纳入对应D2验收。

## P5. API及外部适配边界

公开/本人/维护读写继续使用当前`/v1/venue`、`/v1/observations/current`、`/v1/schedule`、`/v1/me/*`、`/v1/operator/*`、`/v1/operations/:id`和会话接口。现有响应`ok/serverNow/data/operation/current/error`保持兼容；为演示显示补充环境/来源元信息，只增字段，不换领域模型。禁止“网络失败返回内置mock数据”的客户端拦截器。

原生小程序只连接自有业务API，永不保存馆方数据库凭证或直接查询数据库。适配器只产生标准化候选，不能自行签发session、绑定会员、授予角色或直接改公开快照。

| 数据 | 权威来源与本地归属 | 同步/写回规则 |
| --- | --- | --- |
| 本应用身份、session、operator、Binding、删除及审计 | 自有后端唯一权威 | 外部不得写入；没有姓名/手机号匹配自动绑会员 |
| 会员资格/有效期（未来） | 馆方系统可成为资格证据；当前MemberRegistry是前台核验后的本地登记 | 本次不实现同步。未来需显式映射稳定会员键、资格/撤销/期限、核验时间和冲突政策；外部active不能自动复活本地revoked或已删绑定 |
| 忙闲等级 | 当前本地operator或隔离演示actor | 入场事件不能推出实时在场人数；无可靠离场/传感数据不改变此规则 |
| 课表 | 外部提供排课候选；自有草稿和发布仍由维护者控制 | 第一适配器只读输入，确认替换草稿后再显式发布；不回写外部、不双向同步 |

**首个可执行连接器：本机课表JSON v1**。这是未来适配器接口验证，不要求馆方给文件；本次用合成输入。只支持full快照，不实现轮询/增量/CDC/webhook。入口为受控CLI `import-schedule.mjs plan/apply/status`，无需新通用管理UI；apply要求当前有效operator会话（本地标准输入或0600临时凭证文件，不能命令行暴露token），并复用领域鉴权。实际馆方连接另开任务。

输入固定：schemaVersion=1、sourceId、gymId、sourceRevision（正安全整数、每source严格递增）、mode=full、capturedAt（UTC）、timeZone、coverage{startDate,endDate}、courses[]。每课含externalId、name、localStart、localEnd、status=scheduled|cancelled。时间对象固定为`{ "local": "2026-09-16T18:00", "offset": "+08:00" }`，localStart/localEnd分别映射现有localInput.start/end，经既有period规则计算startAt/endAt；不接受含糊日期或自动猜offset。不接受未知字段、个人资料或数据库连接串；文件≤64KiB、输入及补齐取消后的标准课程集合均≤200、coverage为1–14天，startDate为包含端点、endDate为开区间终点（如16日至17日代表16日一天），时间和重叠规则沿用既有校验。

1. 来源先由本机维护者显式注册至当前gym，固定schema/mappingVersion/timeZone；首个来源独占该店外部课表输入权。未知source、错gym、同一externalId重复或时区不符整批422。外部键映射为`sourceId+externalId → 本地UUID courseId`，持续保留，不按名称/时间生成ID；改名改期不换ID，外部回收键必须拒绝并人工决策。
2. `plan`完整解析/校验，生成可审阅差异（新增/变更/取消/从覆盖删除）、候选hash及当前draft/publication版本，不改变草稿或公共页。任何一行错误整批拒绝，报告行号/字段/安全错误码，不部分导入；原始文件不额外归档进DB。
3. `apply`必须明确确认**整份替换草稿**，再次鉴权并比较准备时draft及publication版本；仅确认时更新。人工编辑或其他导入先提交则409，保留对方修改，重新plan/审阅后才能apply；不自动merge、不取最后写入者获胜。已存在人工草稿同样需差异确认。
4. 同一事务写入标准草稿、课程映射、批次applied和source游标，复用现有课程校验及审计；可最小扩展领域服务以纳入事务，禁止适配器先直接改表再调发布。此时公开课表仍不变，须另走现有publish的双版本校验与确认。
5. 先鉴权和核对source，再按以下优先顺序处理版本：低于已应用revision→409；同一已知revision不同hash→409；当前已应用revision同hash→返回原applied结果，绝不重新修改草稿；未应用且同revision/hash的候选若**已过期**，plan必须废弃/替换旧候选并重新绑定当前draft/publication版本及差异，需要重新确认；只有未过期的同revision/hash候选才返回原候选。被替换候选的旧batchId不可apply。高版本允许跳号，不意味着能识别遗漏的增量。capturedAt只是来源信息，不用接收时间冒充最新，也不单凭时间戳排序。
6. full快照遗漏课程的含义为“该覆盖内不再排课”，不是直接删除记录。候选将**旧草稿中与新coverage相交但输入遗漏的每一课**补成同courseId、保留名称/时间/localInput、status=cancelled的记录（已有cancelled继续保留），并用当前草稿course revision调用既有校验，满足EXPLICIT_CANCELLATION_REQUIRED；来源映射保持不变。历史映射中不在当前草稿的课程不凭映射捏造取消记录；输入再次引用该externalId时复用UUID，对当前草稿不存在的课程按revision=absent提交。正常列表仍可显示“已取消”，当前上课集合排除它们。补齐后超200课整批拒绝并报告，不能通过丢弃取消记录绕过上限。差异明确列出遗漏转取消、显式取消及覆盖变化，确认后才apply。覆盖外不隐含删除，但现有“单当前发布、整覆盖替换”仍成立，新发布会替换旧公共覆盖，须提示旧覆盖丢失范围。未来增量事件必须另定合同，不能把增量缺行当取消。
7. 解析失败/读源失败/DB失败不推进游标、不修改草稿/公开页；DB提交响应丢失查batch状态，不盲重发。权限403、版本409、结构422、锁/存储503分开。最后已发布课表可继续显示其原覆盖与发布时间，维护者看到失败；不得以同步失败续期、清空、假造成功或把旧表标为刚同步。

正式连接器上线前还需取得：读权限和密钥存储方式、schema/样例、主键及删除语义、时区/过期规则、API或DB读取方式/分页游标/频率、个人数据边界、来源故障处理及对账样例。MVP不开放数据库任意表写权限，不复制整馆人脸/会员库；只抽取获准的最小字段。未知来源“随时接入”解释为边界已预留、具体适配可增加，不是零工作承诺。

## P6. 本地可执行联调路线

**推荐唯一默认路线：同机官方开发工具模拟器 → loopback HTTP → 自有test后端。** 下列新增CLI是待实现合同，不声称当前已存在。既有Node24.18、SQLite、官方工具和用户选择的测试号可复用，无需馆方数据库、AppSecret或购买云资源。

```text
node scripts/demo.mjs init demo-local             # 仅首次；拒绝覆盖
node scripts/db.mjs migrate .runtime/test/demo-local/config.json
node server/main.mjs .runtime/test/demo-local/config.json
curl --fail http://127.0.0.1:8787/health
node scripts/demo.mjs ticket .runtime/test/demo-local/config.json member-a
# 原生演示登录输入一次性凭证；operator-a同理，由维护者显式grant角色
node scripts/demo.mjs run .runtime/test/demo-local/config.json start crowd-v1
# step/play/status由README给出；复用同一DB和端口，不重初始化
```

执行前核对端口占用；冲突则明确选择空闲端口并同步客户端，不能杀不明进程。客户端显式配置baseUrl=`http://127.0.0.1:<port>`、environment=test、gymId及demo开关；GET health核实simulation=true/identityMode=test，再试wx.request。默认仓库配置保持未配置，不提交私人目录/凭证。

**必须单列的工具确认门**：目前tracked/private的urlCheck=true，控制台已证明loopback请求不通。仅在用户明确授权后，在本项目开发工具“详情／本地设置”临时选择其实际显示的“不校验合法域名、web-view（业务域名）、TLS版本以及HTTPS证书”。这是组合安全开关，不能称只影响一项。记录前后值、作用项目/版本/运行时间；完成后恢复原值并验证。不得自行修改project.config/private配置绕开确认；不要改全局，不升级为真机证据。[既有微信网络来源](../../raw/SRC-20260916-gym-wechat-06.md)仅用于规则依据，具体开关显示和生效仍需D3实际核对。

用户未确认时：可完成curl/HTTP多客户端/Node测试/实际编译，D3网络业务部分BLOCKED；用原生页面fixture或注入session不得替代此层通过。得到确认且打开开关也不自动PASS，须真实wx.request＋登录/读写证据。

若用户拒绝本地例外而仍要求原生联网：另提受控HTTPS方案，需要用户可用域名/有效证书/平台request合法域名权限及部署或反向代理授权；当前test客户端仅接受loopback HTTP，必须另行最小扩展受控test HTTPS配置，后端可由本机代理转发，不直接改为全网监听。匿名公网隧道、借用他人域名或store模式塞合成数据均非替代方案，本轮不部署。真实手机的127.0.0.1指手机自身，不能复制本机地址当作真机方案。

## P7. 完成定义

演示完成须D1→D2→D3被逐项验收：自有持久后端、隔离演示身份、动态场景、JSON候选/冲突、真实原生交互、失败/重启证据均齐全。工程PASS不填成官方登录PASS；模拟器PASS不填成真机PASS；演示成功不填成门店需求验证。未来官方/门店按独立CP6/CP7授权执行，不需推倒上述领域层。
