# CP0 技术契约 v1 · GYM-CP0-FOUNDATION-001

状态：CP0交付设计基线，待指挥者验收。产品依据为baseline/sources.json中三个哈希锁定文档。本文件冻结实现方式，不改变产品参数。**只有/health、配置、迁移和本地探针已实现；下文所有/v1业务API、业务表、清理及加密流程均为CP1–CP5待实现契约。** 不以设计映射宣称业务AC测试通过。

## 1. 实现边界与数据库选择

Node 24.18.0 ESM、内置node:http/node:crypto/node:sqlite/node:test，无npm依赖。SQLite本机文件，DELETE journal、synchronous FULL、foreign_keys ON、secure_delete ON、trusted_schema OFF、禁扩展、defensive模式。同步短事务使用BEGIN IMMEDIATE，所有写（含撤销、删除、清理、限流）串行取得数据库写锁，2秒等待后503 STORAGE_BUSY，不重试成其他操作。事务中不进行网络请求/await。单店低频人工维护适合此边界；同机多进程可协调，不能用于多机器共享数据库文件。不承诺吞吐容量，负载/托管与运行时升级留部署前验证。

迁移按文件名顺序执行，所有待应用DDL、ledger及deployment同事务；已有文件SHA不符或遗失拒绝启动。部署metadata固定environment/gymId/密钥指纹，SQL预编译绑定参数。每次业务事务重读账户/角色/资源状态及版本，不能信赖事务外预查；SQLite全局写串行解决删除、撤销与未提交写入的排序。读API用同一个短读事务构建一致快照，退出前不混用多版本。

当前迁移只创建schema_migrations、deployment、foundation_probe。以下业务表在各CP新增迁移，禁止在CP0提前开通路由。

## 2. 线协议、身份与通用错误

单部署单gymId；URL根`/v1`。配置定义environment/gymId，客户端不能通过body或header切换；提交未知字段（尤其role/openid/gymId/environment/simulation/人数）422。请求JSON上限64KiB（课程草稿允许256KiB），只接受application/json，时间为严格RFC3339 UTC（毫秒精度），数据库INTEGER为UTC epoch毫秒；本地日期严格YYYY-MM-DD，revision为非负安全整数。UUID为规范小写UUIDv4。Bearer业务token只放Authorization头，不入URL、日志或持久化正文。公共响应无个人actor，所有响应Cache-Control:no-store；私有缓存由客户端显式管理。

成功`{ok:true,serverNow,data}`；写成功另含`operation:{operationId,type,committedAt,appliedRevision,replayed},current:{revision,refreshRequired:true}`。data只包含该操作最小历史事实，绝非当前资格保证。首次创建201，其余200；幂等重放200。GET响应数据自带revision/state。错误`{ok:false,serverNow,error:{code,message,fields?,retryAfterSeconds?}}`，禁止敏感原输入。服务器未知结果/断网，客户端不显示失败已回滚。

| HTTP | code及处理 |
| --- | --- |
|400|INVALID_JSON、INVALID_REQUEST（语法/字段格式）|
|401|SESSION_INVALID（到期/撤销/未知一律清个人缓存，不区分token存在性）|
|403|FORBIDDEN、SCOPE_FORBIDDEN（角色/本人作用域，禁止重放）|
|404|NOT_FOUND（不可见对象和不存在保持一致）；无当前资源使用200明确state|
|409|REVISION_CONFLICT、BINDING_CONFLICT、IDEMPOTENCY_CONFLICT、ACCOUNT_DELETING、RESTORE_REQUIRED|
|410|PAIRING_EXPIRED、PAIRING_CANCELLED、PAIRING_CONSUMED（仅已获准查看请求状态者）；删除回执到期统一unknown|
|422|INTENT_EXPIRED、INTENT_FUTURE、INVALID_PERIOD、OVERLAPPING_COURSES、TIMEZONE_AMBIGUOUS、FRESH_AUTH_REQUIRED|
|429|RATE_LIMITED，retryAfterSeconds=ceil((windowEnd-now)/1000)，同时Retry-After|
|503|STORAGE_UNAVAILABLE、STORAGE_BUSY、PLATFORM_UNAVAILABLE、PAIRING_COLLISION_EXHAUSTED；无业务伪成功|

每个受保护读写都验证session摘要、所属部署、active账户和期限；管理员另读角色。禁止把客户端role作为权限。操作结果查询也重新鉴权；撤销operator后查询管理操作403，删除旧会话401。对象ID不构成授权。

### 通用写W与幂等台账

除微信code交换、删除回执读、已认证查询以及维护内部批次以外，写请求W含`operationId`（随机UUIDv4作为key）、`requestCreatedAt`、`expectedRevision`与该动作payload。创建用字符串`"absent"`，更新用当前revision。复杂多资源的expectedRevision为下表明确对象。类型固定为路由动作，不能任意指定。scope主键=(environment,gymId,actorUserId,operationType,operationId)。CLI角色维护actor为独立的受控部署维护者标识，不能冒充普通用户。

摘要=HMAC-SHA256(intentHmac, 域分隔scope + canonical JSON完整W正文)。JSON解析拒绝重复键（CP1实现专用解析验证或等价严格解码），对象键按Unicode码点排序，数组保持顺序；仅允许JSON原生值、有限安全数值，无undefined。去掉传输无意义空格，不对原会员引用以外字符串做业务性改写。body变化（包括expectedRevision/time）均409。不保存原正文或原会员引用/码的普通hash。

事务顺序：取得写锁→重检会话/账号/角色及对象访问权→查未满24小时的相同scope/key→摘要匹配则返回历史结果，禁止增加计数、改TTL或再次写审计→新请求校验intent时间和CAS→业务约束/原子计数→业务、审计、成功幂等结果同时提交。审计失败整体回滚。失败不写成功结果；明确的业务配对查询/核验错误需单独提交错误计数/最小安全审计，无部分业务修改。并发版本冲突/5xx不计核验错误。

普通intent年龄≤300000ms，观察发布≤60000ms，future≤30000ms；大于边界拒绝。校验以事务取得锁后的serverNow计算。台账逻辑有效至createdAt+86400000，边界视过期，即使尚未物理清理。24小时以后原请求因intent年龄失败；不能因台账删除重新执行。已提交的有效台账重试不按新intent重验年龄，但先鉴权。历史结果与当前状态分开，配对结果仅指向pairingId，绝不在台账存可显示码。

客户端10秒超时；写不自动换key、time、revision。先GET原operationId；unknown不等于未提交证明（可能过期/清理），随后取当前状态。观察60秒以后仍无提交证据须重新巡视，普通写5分钟后须重新确认。客户端页面前台每60秒读场馆/课表/本人状态，隐藏停止、恢复立刻重取；每资源最多一在途请求；轮询不续session idle。

## 3. API动作清单及事务边界

约定P=公共，U=当前本人，O=本店operator，D=本机已授权部署维护者。表内W隐含上节全部字段/规则和通用错误。没有独立多店参数。每个O写在同一业务事务内重验当前role和所有主体active；401/403/409/422/503适用于所有W。表列专属返回与额外错误。

| 方法/路径/operationType | 权限及请求 | 响应data与原子边界 |
| --- | --- | --- |
|GET /venue|P，无body|gym{name?,timeZone,openingHours?,contact?}、observation、scheduleSummary，serverNow；未核实配置为null，不捏造信息；同一快照读|
|GET /observations/current|P|state=never/quiet/moderate/busy/unknown/paused/withdrawn/expired/unavailable，revision、source、observedAt、publishedAt、validUntil；公共无actor|
|GET /schedule?from=日期&to=日期|P，1–14个本地日，含起不含止|publicationRevision、state=unpublished/published/withdrawn、coverage、每日covered标记、courses；覆盖内空与未覆盖区分，当前公开快照一次读|
|POST /sessions/exchange|P；code、privacyNoticeVersion、consent=true（应用用途同意，非平台授权）；此动作不使用W|一次新code先持久提交全局限流，再外调微信，验证成功后事务按AppID/openid唯一映射取得/建user，deleting返回409；同事务签session/5上限/同意记录/审计。返回token一次、sessionId、authAt、expiresAt、idleExpiresAt、userId，无会员或管理授权；无效/重放code400 LOGIN_CODE_INVALID，平台失败503。丢响应用新code，不缓存token幂等结果|
|GET /session|U；交互类型poll或interactive|本人userId、sessionId、authAt、expiresAt、idleExpiresAt、sessionRevision、accountRevision；sessionRevision用于登出，accountRevision来自当前active账户用于删除，二者不得互换；poll不续闲置。active查看interactive在事务中更新lastInteractiveAt，不改绝对期限|
|POST /session/logout / session.logout|U；W expectedRevision=session revision|sessionId、revokedAt；撤当前session+审计+台账，其他会话不变；旧token再试401，不以幂等绕过|
|GET /me/membership|U|bindingId及bindingRevision（无则absent）、registryRevision（无则absent）、derivedState、expiryMode、validUntil/localEndDate、verifiedAt；不返memberKey/原引用；按当前serverNow推导状态|
|GET /me/pairing|U|pairingId/revision/state/createdAt/expiresAt，只有本人且active未过期才解密返displayCode；终态无code；无请求state=none|
|POST /me/pairing / pairing.create|U；W expectedRevision=当前配对revision或absent|pairingId/revision/expiresAt；用户不可有有效当前关联，撤销关联可申请；原子使旧请求失效+创建+生成次数+审计+台账，安全随机碰撞5次全部回滚，旧请求仍有效；429/503|
|POST /me/pairing/cancel / pairing.cancel|U；W pairingId、expectedRevision=配对revision|pairingId/state=cancelled/revision；作废码查询与展示+审计+台账；终态410|
|POST /me/membership/unbind / membership.unbind|U、新鲜authAt≤5分钟；W confirmed=true，expectedRevision={binding,registry,pairing}（无请求absent）|bindingId/state=unbound/revision；关闭本人的current关联、失效配对、保留Registry资格+审计+台账；无取消原合同|
|POST /me/account/delete / account.delete|U、新鲜认证≤5分钟；W confirmed=true、receiptDigest=SHA256(本地32随机字节回执)，expectedRevision=account revision|jobId/state=pending/acceptedAt；事务active→deleting，全部session撤销、role移除、所有Binding关闭、pairing作废、持久化任务/审计；台账不能用旧session读取；绑定/角色的最新状态在锁内全部失效，不依赖客户端旧快照|
|POST /deletions/status|回执能力，不使用session；Authorization:DeletionReceipt 完整回执，不入URL；无业务W|仅state=unknown/pending/failed/completed、acceptedAt/completedAt、delayed、nextRetryAt；根据SHA256查询，完成7日边界unknown；无个人信息、无再次删除副作用|
|GET /operations/:operationId?type=枚举|U/O按原操作要求|state=committed或unknown；仅本人scope；历史committedAt/appliedRevision/最小result与current revision/refreshRequired；过期返回unknown，不保证回滚|
|GET /operator/role|U|isOperator、roleRevision；不泄露其他名单，无客户端授予入口|
|POST /operator/observations / observation.publish|O；W expectedRevision=observationRevision，level=quiet/moderate/busy、observedJustNow=true|revision/state、observedAt=publishedAt=提交时刻、validUntil=+900秒；事件+当前快照CAS+审计+台账；60秒窗口|
|POST /operator/observations/control / observation.control|O；W expectedRevision=observationRevision，state=unknown/paused/withdrawn、reasonCategory|revision/state；事件+控制快照+审计+台账，无回退旧level；普通300秒窗口，无现场等级发布|
|POST /operator/pairing/lookup|O；code=8位字符串，读取具有持久化错误计数副作用，不使用W|最小pairingId/revision/expiresAt/requesterLabel（随机业务短标识）、bindingRevision、registryRevision若有当前关联（此处无关联不表示Registry不存在，原系统引用须另调members/inspect）；不返openid/token/memberKey/码。不成功计operator及可定位requester的错误；未知/到期/消费统一400 PAIRING_UNAVAILABLE，已知并可见的请求后续核验可410|
|POST /operator/members/inspect|O；memberRef必需，pairingId/code可选且须成对；精确原系统引用核对，不使用W、不列表检索|返回registry={state,revision,expiryMode,validUntil,verifiedAt}或{state:absent,revision:absent}，currentBinding={state,id,revision,relationToRequester:self/other/unknown}或absent；不返memberKey/userId/openid/原引用，other只表示不可转移，不暴露身份。可选配对用于校验当前申请人、返回其配对revision及关联占位关系，另返requesterBinding={id,revision,state}或absent（申请人当前关联，不能与原引用对应的currentBinding混淆）；没有当前Binding不影响Registry读取，revoked仍返真实revision及restoreRequired=true。授权/查询及错误计数同短事务；无效/到期/消费的配对按§4错误计数（已知目标双维），并发/5xx不计；合法原引用对应Registry absent是正常核对结果不计错误，原引用格式错误422。受既定operator错误桶上限保护，不新增人群查询或遍历接口；核对结果是快照，bind/restore仍CAS重检|
|POST /operator/members/bind / membership.bind|O；W pairingId/code、memberRef、frontDeskConfirmed=true、expiry对象；expectedRevision={pairing,binding:absent,registry:revision或absent}|bindingId/revision、registryRevision、pairingState=consumed；锁内校验请求/本人active/Registry非revoked/双唯一，写Registry、Binding、消费、审计、台账；409不抢占；核验不符400并计错误，终态410|
|POST /operator/members/:bindingId/revoke / membership.revoke|O；W reasonCategory，expectedRevision={binding,registry}|bindingRevision/registryRevision、state=revoked；Registry撤销+当前Binding撤销（仍current占位）+审计+台账，下一读写实时失去资格|
|POST /operator/members/restore / membership.restore|O；W pairingId/code/memberRef/frontDeskConfirmed、expiry；expectedRevision={pairing,binding:本人撤销关联revision或absent,registry:revision}|bindingId/revision、registryRevision、consumed；必须显式恢复revoked；同一事务恢复本人同一Binding或absent创建+更新Registry+消费+审计/台账；其他人包括revoked槽位占有则409|
|POST /operator/members/:bindingId/reverify / membership.reverify|O；W memberRef/frontDeskConfirmed、expiry、reasonCategory=renewal/expiry-confirmation，expectedRevision={binding,registry}|bindingRevision/registryRevision、verifiedAt、expiry；只现有非撤销关联，校验原引用HMAC相等并更新期限/资格/关联版本+审计/台账；revoked必须走restore配对流程|
|GET /operator/schedule/draft|O|draftRevision、coverage、course集合及每项revision，publicationRevision；不存在草稿为absent，不把公开快照自动当新草稿|
|PUT /operator/schedule/draft / schedule.draft.save|O；W expectedRevision=draftRevision或absent；coverage、完整courses[{courseId,name,startAt,endAt,status,revision,localInput含offset}]|draftRevision、course revisions；同事务CAS草稿+稳定ID课程修订+审计/台账；改期保留ID，取消标cancelled；public不变，覆盖内已有课需显式取消；新的覆盖范围可省略范围外课程，历史ID保留在旧修订中|
|POST /operator/schedule/publish / schedule.publish|O；W expectedRevision={draft,publication}（初次absent）、replaceCoverageConfirmed=true|publicationRevision、coverage；锁内验证完整1–14天集合/相交/无重叠、两个CAS，写不可变快照+单当前指针+审计/台账；不合并、不回退|
|POST /operator/schedule/withdraw / schedule.withdraw|O；W expectedRevision=publicationRevision，reasonCategory|publicationRevision、state=withdrawn；写撤回当前控制状态+审计/台账；草稿不对外，历史不再选当前|
|GET /operator/audit?limit=1..100&before=cursor|O|近90天最多100条，业务动作、结果、revision、time、reasonCategory、必要业务actor/subject；已删除关联显示固定“已删除账户”；不含身份映射或凭据|
|CLI role grant/revoke|D；本机受控维护者ID、existing userId、gymId、reasonCategory、W role revision或absent|角色version+grant/revoke+审计+台账同事务；并发管理写在锁内重新看权限。作用域固定本配置，错误返回非零，无client管理接口；查询按维护者scope操作ID|
|CLI cleanup run/status/retry jobId|D；同一配置；内部幂等批次不使用用户W|批次progress、lastSuccessAt、failureCount、nextRunAt；只接受现有jobId重试，无新业务删除授权；同事务变更数据和游标，错误有脱敏分类|

所有管理核验请求不得接受客户端userId来替代配对解析的本人，引用仅trim两端、保持大小写/前导0。expires对象为`{mode:fixed_until,validUntil,localEndDate?}`或`{mode:no_fixed_expiry}`或`{mode:pending_confirmation}`；日期型localEndDate以门店下一日00:00排他换算，并校验UTC相符。memberRef未知时不造Registry revision。

### expectedRevision读取来源审计

所有创建读响应显式返回revision="absent"而非遗漏字段；已有实体返回整数。当前头即使withdrawn仍返回保存的递增revision；后台角色未创建也返回roleRevision=absent。响应中的版本不能推导另一个实体版本。版本读完可能改变，冲突后重新读取并由操作者确认，不自动覆盖。

| 写动作/所需版本 | 已授权读取来源 | 不存在/特殊情况 |
| --- | --- | --- |
|session.logout：session|GET /session sessionRevision（U）|只有有效session可登出，旧token401|
|account.delete：account|GET /session accountRevision（U）|不同于sessionRevision；deleting不能用旧session再取|
|pairing.create/cancel：pairing|GET /me/pairing revision（U）|初次absent，终态仍返该请求revision；物理清理后absent|
|membership.unbind：binding/registry/pairing|GET /me/membership bindingRevision、registryRevision；GET /me/pairing revision（U）|pairing不存在用absent；读取后并发变化CAS拒绝|
|observation.publish/control：head|GET /observations/current revision（P；写另检O）|从未创建absent；已撤回不absent|
|membership.bind/restore：pairing/binding/registry|POST /operator/pairing/lookup取得pairing；POST /operator/members/inspect带memberRef及pairingId/code取得Registry、requesterBinding及引用对应currentBinding（O）；bind/restore的binding版本来自requesterBinding|Registry可在无Binding时仍为revoked，必须用其revision走restore；同码解析的requester与currentBinding relation=other则禁止覆盖|
|membership.revoke/reverify：binding/registry|POST /operator/members/inspect精确memberRef（O），返回currentBinding.id/revision和registry.revision|无Binding不能构造当前关联动作；不能从Registry存在推断有Binding|
|schedule.draft.save：draft|GET /operator/schedule/draft draftRevision及course revisions（O）|初次absent；新course用revision=absent，新UUID|
|schedule.publish：draft/publication|GET /operator/schedule/draft draftRevision、publicationRevision（O）|未发布publicationRevision=absent，撤回保留整数|
|schedule.withdraw：publication|GET /operator/schedule/draft publicationRevision或GET /schedule publicationRevision|需现有发布版本，withdrawn不回退历史|
|CLI role grant/revoke：role|CLI role inspect --user-id --gym-id（D），只返targetUserId/activeAccount/roleState/roleRevision|未授予absent，撤销保留版本；该只读命令无client入口，与同配置/维护者鉴权边界一致|

新增inspect是既定“会员原系统核对/版本控制”和“operator受控授予撤销”的必要精确读取，不新增名单浏览或会员数据搜索。inspect无法替代frontDeskConfirmed和实际线下核验，检查结果不得持久化原始memberRef；成功读不消费配对、不恢复资格、不修改有效期。requesterLabel仅用于当面比对，原始微信映射不返回。

## 4. 持久化结构（后续迁移规范）

所有业务表所在数据库由deployment隔离；复合唯一键/关联均带gym_id，环境固定在库metadata。下列id均随机UUID，时间INTEGER毫秒，状态CHECK枚举；实体revision每次状态改变+1，创建revision=1，absent不是0。删除再创建的新实体换ID防ABA；当前观察/发布revision存独立永久单店头，不因历史清理归零。foreign key确保跨gym关联不可构造。禁止ON DELETE CASCADE无审计地抹掉当前资格。

| 表/关键字段 | 索引/约束及生命周期 |
| --- | --- |
|accounts(user_id,gym_id,state,revision,created_at)|PK(gym,user)，active/deleting；完成删除个人行移除，最小回执独立|
|wechat_identities(gym_id,appid,openid,user_id)|UNIQUE(gym,appid,openid)，FK account；deleting期间有限保留阻止重建，完成事务删除；不向operator暴露|
|sessions(session_id,gym_id,user_id,token_digest,auth_at,created_at,last_interactive_at,expires_at,revoked_at,revision)|UNIQUE(token_digest)；≥256位随机token，SHA256只存摘要；按user/created排序用于最多5个有效会话|
|privacy_consents(gym_id,user_id,notice_version,accepted_at)|UNIQUE(user,notice_version)，随账号删除；仅应用用途同意|
|operator_roles(gym_id,user_id,granted,revision,updated_at)|PK(gym,user)，role撤销保留version行直到账户删除；无权限缓存放行|
|observation_head(gym_id,revision,state,level?,observed_at?,published_at?,valid_until?)|PK(gym)，无actor；永不因历史清理回退/改TTL；head中的时间/level是自足当前快照|
|observation_events(id,gym_id,revision,state,level?,published_at,actor_id?)|UNIQUE(gym,revision)，published_at索引，30天；公开只读head|
|member_registry(gym_id,member_key,state,expiry_mode,valid_until?,local_end_date?,verified_at,revision)|PK(gym,member_key)，不带user/openid；fixed模式要求validUntil，其他模式无隐含永久；revoked最小快照不得时间清理|
|bindings(binding_id,gym_id,user_id,member_key,current,state,revision,closed_at?)|FK Registry/account；部分UNIQUE(gym,user) WHERE current=1；部分UNIQUE(gym,member_key) WHERE current=1；revoked仍current=1。解绑/删除current=0；非当前历史30天|
|pairings(pairing_id,gym_id,user_id,revision,state,lookup_digest,ciphertext,nonce,tag,key_id,created_at,expires_at)|部分UNIQUE(gym,user)与UNIQUE(gym,lookup_digest) WHERE state=active；时间到期即时判失效，不依赖索引理解now；创建事务先把已到期active变expired并销毁展示密文，再尝试随机码。索引覆盖created/expires清理|
|rate_buckets(gym_id,kind,subject_id,window_start,count,window_end)|PK(gym,kind,subject,window_start)，CHECK(count>=0)，UTC固定桶；环境靠独立DB|
|operations(gym_id,actor_id,type,key,body_hmac,result_json,applied_revision,created_at)|复合PK全scope；result无token/原引用/码；created索引用于24小时清理；个人删除时清除|
|schedule_draft(gym_id,revision,coverage_start,coverage_end,courses_json)|PK(gym)，唯一可变草稿；JSON内容需应用验证，保存稳定courseId及item revision|
|schedule_head(gym_id,revision,state,current_snapshot_id?)|PK(gym)，一次发布只有一个指针；withdrawn仍保留revision，public绝不max历史推断|
|schedule_snapshots(id,gym_id,publication_revision,coverage_start,coverage_end,courses_json,published_at,retired_at?)|UNIQUE(gym,publication_revision)，不可变课程集合；覆盖本地日期与UTC课程均存，当前无actor|
|audit(id,gym_id,actor_id?,subject_id?,action,result,revision,time,reason_category)|time索引；只枚举理由不自由贴凭据，90天，账号删除去关联保留事实；operator读最多100|
|deletion_jobs(job_id,gym_id,user_id?,receipt_digest,state,accepted_at,completed_at?,next_retry_at,phase,cursor,error_count,last_error_category)|UNIQUE(receipt_digest)，user只有deleting期间用；完成最小化user关联；可恢复进度，不靠内存队列|
|cleanup_progress(gym_id,task,phase,cursor,last_success_at,next_run_at,error_count,last_error_category)|PK(gym,task)，批次数据+游标同事务，重启补跑|

SQLite写锁阻止两条并发成功CAS；UPDATE ... WHERE revision=?必须检查changes=1。用户、Binding、Registry、角色的校验在持锁事务中，而不是仅CAS某一个最终表。更新失败回滚全部业务审计/台账；错误桶需要按表述的独立失败事务提交，并在提交前重新检查账号/角色和可定位请求。

### 会话与计数实现约束

session有效 iff account active && revokedAt=null && now<createdAt+7日 && now<lastInteractiveAt+24小时；持续poll不变更lastInteractiveAt。本人明确点击查看/主动业务动作和operator维护写才续闲置。第6有效session事务撤最早createdAt（相同时sessionId排序）再插入。登录适配器外调前计120/min全局桶；失败同样计；测试适配器只允许test，store缺AppID/AppSecret则身份不可用，不fallback。

配对生成3/600秒按user；只有接受成功的新请求计，重复key不计，碰撞耗尽回滚计数与旧请求撤销。核验错误5/600秒按operator，以及能定位时按requester；若任一桶已5，拒绝后续尝试直到窗口切换。无效/过期/消费/核验不符计，unknown只operator；版本冲突/服务器错误不计。固定桶floor(nowMs/windowMs)*windowMs，桶到windowEnd+1小时清理。多进程共用DB事务，无内存替身。登录成功后新session不换user，不清桶。

## 5. 密钥、配对与删除回执

本地每环境每店keys.json中四个独立随机32字节键：memberHmac、pairLookupHmac、pairEncryption、intentHmac。单独于未来AppSecret（由服务环境的权限受限文件注入，不进入keys.json或客户端）。init只显式首次生成，服务读不到就失败。配置/keys文件0700父目录、0600文件，运行账户独占。DB保存四键指纹用于检测意外替换；不得把指纹当恢复密钥。CP0只生成/校验/绑定指纹，不实现配对或会员加密业务。

memberKey=HMAC-SHA256(memberHmac, gymId + 域分隔 + trim(memberRef))；此稳定键不可随部署旋转，否则唯一性/撤销失效，丢失须停止核验并报指挥者，不能自动替换。pair lookup另键HMAC并含gymId；随机码用crypto.randomInt(0,100000000).toString().padStart(8,'0')，无取模偏差。每次尝试用SAVEPOINT仅回滚冲突候选，不吞其他DB错误；5次碰撞rollback整个创建事务（包括原码失效和计数）。

展示副本采用AES-256-GCM，随机12字节nonce/16字节tag，AAD绑定environment/gymId/pairingId/expiresAt，ciphertext仅8字节码。密文有keyId，解密只本人有效请求；终态立即清空密文/nonce/tag，过期读不展示，小时清理物理删除。GCM认证失败503并记录类别，不返回乱码。禁止在operations/audit/cache中另存明码。计划密钥轮换必须显式迁移，保留旧查询/解密键至全部短期数据过期；当前版本指纹阻止静默轮换。memberHmac变更需单独产品/迁移合同。

删除回执客户端必须用平台安全随机能力生成32字节并在发送前持久保存，再算SHA256只将摘要放W。若客户端所需安全随机/摘要能力不可用，停止删除，不降级Math.random；CP3用受控测试验证，CP6验证真实平台能力。回执原文仅保留本机用于查询，header传输；完整token/receipt/body一律禁日志。服务端仅索引摘要，持有回执只能查最小状态。deleting期间身份映射仍在，防登录重建；完成清理事务才删映射，不能先解锁后删除。

## 6. 删除和小时清理执行器

Node服务启动时立即执行到期任务，之后每小时；部署维护者可同配置CLI run/status/retry，无HTTP公共入口。执行器CP3/CP5实现，CP0不伪造一个空清理函数称已完成。每批最多100行；每任务的候选选择、删除、cursor/phase更新同一BEGIN IMMEDIATE事务。多进程竞争同一任务时取得锁后重读progress，不能拿事务前候选推进游标。批次成功后让出事件循环，新的数据仍按时间及主键范围下批扫描。失败回滚本批；另一个短事务记录error_count/category、nextRetryAt=下一小时。DB不可用时仅输出无敏感状态错误，恢复后从持久进度续跑，不能写假完成。

删除接受即deleting，全session失效、role清除、Binding关闭、pairing作废。分阶段清理sessions/pairings/operations/consents/历史和当前个人Binding；去关联audit/observation_events actor；最后一个事务验证无剩余个人关联，删wechat mapping及account个人字段，把job userId置null、标completed、completedAt。所有阶段幂等；写业务均拒绝deleting。进度失败保留身份锁，超过24小时delayed=true仍拒绝访问；retry复用jobId，不重建账号。完成后新code登录产生全新userId；Registry防回生快照无userId所以保留，撤销资格仍需explicit restore。

| 清理对象 | 候选及不可删例外 |
| --- | --- |
|observation events|publishedAt+30日≤now；head自足且无actor，不删除当前state/revision/time/level|
|schedule snapshots|已非当前，且coverage本地结束换算UTC后+30日≤now；保留当前草稿/head/withdrawn控制，不用事件恢复旧发布|
|pairings|到期/终态即不可用；下一小时清除请求与密文摘要，消费事实只存无码审计|
|sessions|终止后24小时内清除；执行器按terminateAt≤now-23小时选取，保证下一小时前不超24小时；终止时刻=min(revokedAt,绝对,最后交互+闲置)；故障有延迟状态，不恢复权限|
|operations|createdAt+24小时≤now；逻辑到期独立于清理|
|rate buckets|windowEnd+1小时≤now；错误保留不延长限流窗口|
|audit|time+90日≤now；删前账号清理去关联，不保存原始凭据|
|closed bindings|closedAt+30日≤now，账号删除提前清；current revoked不得作为历史删除|
|member registry|当前最小资格及revoked防回生快照不做时间删除，无账户个人关联；明确馆方恢复或服务结束另合同处理|
|deletion receipts|completedAt+7日≤now，逻辑到期即unknown；未完成任务不按7日清除|

当前本机**无应用自动备份、云副本或导出任务**。SQLite DELETE journal是事务恢复文件，不是额外备份；secure_delete覆盖已释放单元并不保证SSD/文件系统快照或机器备份全副本擦除。CP0没有真实个人数据；上线前必须核实OS/托管快照及保留周期，未核实不能承诺全副本即时删除或备份恢复。这不降低在线去关联与删除完成语义。

## 7. 课表与时间的确定性

courseId跨改期/取消稳定；草稿单一，每次保存完整集合校验item revision。发布必须同时CAS draft/publication版本，coverage 1–14个本地日，课程必须与coverage相交。scheduled排序后前endAt>后startAt为冲突，首尾相接允许；cancelled保留不参与当前课及冲突。跨日/周查询按startAt<queryEnd && endAt>queryStart，当前课startAt≤now<endAt。无覆盖unpublished，覆盖内空empty，withdrawn不回退；没有15分钟课表TTL。

客户端输入包括本地年月日时分和明确UTC offset（例如+08:00）。服务端计算候选UTC，用Intl.DateTimeFormat指定店IANA时区回算并核对年月日时分和offset；DST缺失无法匹配而拒绝；重复时刻要求用户明确选择偏移后匹配，缺少offset不可猜测。无需Date.parse猜本地时区。日期型会员到期同样按次日本地00:00排他求UTC，异常时区日期不能默默更正。已发布后时区只读；改时区须显式撤回、受控维护确认重新换算并发布，非普通客户端配置写。

观察expire判断now≥observedAt+900秒，非法未来/缺失时间unavailable。客户端建立serverNow+单调经过时间基准，不用本机墙钟增TTL；离线重启无可信年龄只能展示历史。轮询/重新GET/修改课表/清理均不改变观察时间或revision。

## 8. CP0契约审阅映射与后续验证门

| AC | 本文件证据 | 实现验收所在CP |
| --- | --- | --- |
|I01|§2 scope/带键body摘要/intent/24h/CAS，§3逐操作|CP2/3/4/5故障/并发/迟到结果测试|
|F01|§3 sessions、§4会话表和精确生命周期|CP1/5身份与跨进程验证|
|R01|§4持久化固定桶、计数与回滚边界|CP1登录、CP3配对、CP5整体|
|R02|§4唯一索引、§5安全随机/GCM/碰撞SAVEPOINT|CP3受控碰撞和DB日志检查|
|X01/C02|§3删除操作及回执、§6身份锁/分批清理/备份边界|CP3/5失败重启、CP6平台能力|
|C01|§6逐对象期限/当前快照例外/游标|CP2/3/4领域，CP5完整清理|
|Z01|§3发布双CAS、§4单头、§7时区和区间|CP4/5|

无API需要新增产品范围；框架/SQLite选择是本合同允许的技术决定。固定参数与三份基线一致；后续若实现不能满足本契约，报告具体冲突，不在代码内静默放宽。
