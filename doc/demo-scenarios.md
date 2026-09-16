# CP5-D2：有限场景与本机课表JSON适配

本阶段只在已验收D1的显式demo scope执行。Node24/SQLite、原HTTP API与权限/幂等/期限不变，没有通用连接平台。微信网络、真实900秒等待画面、原生端到端留D3；本阶段未操作工具开关或上传。

## 1. 数据落点与最小规则

每个 `.runtime/test/demo-*/state.sqlite` 独立；仅本机维护者CLI持有读写权限。迁移007追加下列结构，001–006不改、不删除旧业务库。

|表/列|用途与写入归属|保留|
|---|---|---|
|demo_runs|run_id、固定scenario版本名、anchor_date、step_index、state、created/updated/terminal_at、last_revision_json、safe error_code；CLI进度事务|终态30天；非终态/needs_review保留|
|demo_steps|run+step唯一；actor_alias与本scope业务userId HMAC、HTTP path/method、operation_type、原始operationId/requestCreatedAt/CAS的安全request_json、prepared/sent/committed及最小结果|随所属终态run清理；不存token、明文配对码、原始会员引用/删除回执|
|demo_runner_lock|scope唯一；随机owner、本机PID与run_id；事务内抢占；live PID拒绝409|命令退出释放；PID确实不存在时标中断并释放；PID无法确认时保守拒绝|
|source_bindings|scope唯一来源；source_id/gym_id、安全source_label、schema/mapping版本=1、time_zone、applied_revision/hash/result|来源高水位/原已应用结果持续保留|
|source_entity_map|source+external_id固定映射本地UUID course_id|缺行、取消、重启、清理均不删除或重分配|
|import_versions|source+revision唯一对应原内容SHA256|保留最小hash以拒绝已知版本异内容；不存原输入文件|
|import_batches|batch_id、source/revision/hash/capturedAt、prepared/expires/applied时刻、candidate/replaced/applied、标准候选与差异/最小结果|候选24h失效后小时清理；applied明细30天；旧batch不复活|
|schedule_draft.provenance_json|manual 或 import/import-edited；导入来源/批次/版本/原capturedAt/importedAt，人工改动另加editedAt|与草稿同事务；不会靠批次清理丢来源|
|schedule_snapshots.provenance_json|publish将当时草稿来源原子复制到不可变快照；旧记录默认manual|沿用原快照保留规则，当前快照不删|
|demo_cleanup|next_run_at/last_success/error_count/deleted_total/last_error|启动/每小时执行，失败下小时重试|

清理**每事务共享最多100条被删除记录，包含demo_steps**。先删合格导入，再用剩余额度删终态步骤，最后仅删除已无步骤的终态run，级联不会额外删行。批间setImmediate让出事件循环；失败全批回滚。清理不删映射、来源版本底线、当前草稿/来源或未完成进度。

领域业务仍只有既有服务写入。场景使用真实HTTP session、role、CAS、幂等事务；导入在 `identity.authorized(operator)` 的同一同步事务中调用最小课表导入接口，草稿+映射+batch结果+来源游标+审计一起提交。plan不动业务或持久映射，apply不发布，publish仍独立确认。

公开 `/v1/schedule` 保留 `source=gym-schedule`，仅新增provenance的kind/sourceLabel/capturedAt/importedAt/editedAt/simulation白名单；无batchId、外部键、actor。未导入时没有虚构capturedAt。导入/人工编辑失败不改变原公开时间或当前快照。

## 2. 先准备D1服务与私有会话

先按 `doc/demo-foundation.md` 新建demo-local并启动，已存在目录只启动同一config。三个alias主动交换票据获得会话；operator仍必须经原role CLI显式grant。runner不发行票据、不授予/恢复角色。

凭证文件不是产品输入示例，不放Git。场景使用 `.runtime/test/demo-local/sessions.json`（0600），结构为member-a/member-b/operator-a三个属性，值是各自当前会话token；import单独使用 `import-session.json`（0600），结构仅 `{ "token": "当前operator会话" }`。这些是形状说明，不可将占位文字当有效令牌。

可逐个运行以下管道（将DEMO_ACTOR显式改为三个已允许alias之一）；票据不进入argv/日志，stdout只有synthetic userId供既有role CLI核对：

```sh
DEMO_ACTOR=member-a
node scripts/demo.mjs ticket .runtime/test/demo-local/config.json "$DEMO_ACTOR" | node --input-type=module -e '
import {readFileSync,writeFileSync,existsSync} from "node:fs";
const alias=process.argv[1],config=JSON.parse(readFileSync(".runtime/test/demo-local/config.json"));
const code=readFileSync(0,"utf8").trim();
const r=await fetch(`http://127.0.0.1:${config.port}/v1/sessions/exchange`,{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({code,consent:true,privacyNoticeVersion:"cp3-purpose-v1"})});
const b=await r.json();if(!r.ok||!b.ok)throw new Error(b.error?.code||"UNCONFIRMED");
const file=config.stateDir+"/sessions.json",sessions=existsSync(file)?JSON.parse(readFileSync(file)):{};
sessions[alias]=b.data.token;writeFileSync(file,JSON.stringify(sessions),{mode:0o600});
if(alias==="operator-a")writeFileSync(config.stateDir+"/import-session.json",JSON.stringify({token:b.data.token}),{mode:0o600});
console.log({alias,userId:b.data.userId});' "$DEMO_ACTOR"
```

会话由操作者文件或stdin提供，runner内仅内存使用。文件非0600/0400、不是普通文件或是符号链接即拒绝；不接受命令行token。注销/删除/限流/角色撤销仍由服务端决定；新票不恢复权限。不同alias不能用同一用户冒充，读取session后会核对scope内的合成主体映射；重新认证可换同主体的新session，但删除后新userId不能继承旧步骤。

## 3. 有限场景CLI

`<runId>`由start返回，替换为实际值；anchorDate必须由操作者指定，重启不自动改日期。默认端口来自config；若config用随机端口0，给run命令明确 `--port <ready日志的实际端口>`。不停止未知占用进程。

```sh
node scripts/demo.mjs run .runtime/test/demo-local/config.json start crowd-v1 --anchor-date 2026-09-17 --sessions-file .runtime/test/demo-local/sessions.json
node scripts/demo.mjs run .runtime/test/demo-local/config.json step <runId> --sessions-file .runtime/test/demo-local/sessions.json
node scripts/demo.mjs run .runtime/test/demo-local/config.json status <runId>
node scripts/demo.mjs run .runtime/test/demo-local/config.json play <runId> --interval-seconds 60 --sessions-file .runtime/test/demo-local/sessions.json
```

start只建立安全初始版本与进度，不写领域数据；step至多一个业务写，play仅crowd-v1，最多剩余三步，间隔60秒，完成即停。整个play持有scope锁，等待时其他runner也409。scope只能存在一个未终结run。已有观察/课表/关联默认拒绝start；只有明确审阅现有状态后给start增加 `--confirm existing`。不在服务启动时运行场景。

|场景|有限步骤与说明|
|---|---|
|crowd-v1|operator较空→适中→较忙；停止后900秒自然过期，不缩短TTL|
|observation-pause-v1 / observation-withdraw-v1|独立一次暂停/撤回；已有状态需start明确确认|
|membership-v1|member-a申请→operator合成核验绑定→撤销→member-a新请求→operator显式恢复→member-b申请→人工确认的冲突尝试|
|schedule-v1|anchor日起7天草稿，两合成课→发布→同ID取消一课/另一课移到次日→再发布→撤回|
|schedule-empty-v1|指定新覆盖的空草稿→单独发布；不在覆盖的日期仍为无覆盖。旧覆盖内课程依法补取消，不强行删历史课程伪造空课|
|lifecycle-unbind-v1 / lifecycle-delete-v1|单独本人敏感动作，每个step另加 `--confirm sensitive`；仍需5分钟认证|
|lifecycle-logout-v1|本人明确退出一次；不替换新的业务身份或会员资格|

membership-v1最后一步默认暂停，不自动抢绑；明确测试冲突时使用 `resume <runId> --confirm member-conflict`，预期BINDING_CONFLICT/409且其他会员数据不变。完成负向证据后可显式 `cancel <runId> --confirm cancel` 终结该run。

删除前，操作者在私有sessions文件增加一次生成的64字符hex `deletionReceipt`（安全随机32字节）；只给此删除动作使用，不写输出或run记录。进度仅保存摘要；同一次删除结果未知时必须继续提供原回执，通过原 `/v1/deletions/status`确认，不生成新回执重发。删除不能代替原馆合同注销。

## 4. 中断和失败恢复

```sh
node scripts/demo.mjs run .runtime/test/demo-local/config.json resume <runId> --sessions-file .runtime/test/demo-local/sessions.json
```

resume先核对当前session/actor，再查**原operationId**；已提交只补进度，不多写、不改时间或key。未提交且仍在原intent窗口，必须操作者明确 `--confirm retry` 才能重试相同请求。配对码只经当前会员会话即时取用；必须原pairingId/revision仍活跃，过期即暂停，不能换新码拼旧请求。

401/403/429/本地或业务版本冲突暂停，不能自动换session或恢复role。窗口已过：确定未发送的prepared要求重新确认；曾发送但无法查证的为needs_review，禁止盲重放或取消后偷偷换key。先报告/人工核对；需要重新观察时显式开始新的已确认流程，不能把原观察时间改新。普通read/重启/status/清理不会推进步骤或续观察TTL。

进程在HTTP已提交、进度未提交之间终止：status检测已死PID将run标interrupted；resume用原台账对账。PID仍存在或无法确认时不抢锁，避免两个runner同时写。PID重用可能需要维护者核实，不自动猜测进程归属。网络/存储未知结果不能用“失败”假称未提交；原台账清理后无法确认则人工处理。

## 5. JSON来源与plan/apply/publish

合成文件在 `examples/demo-schedule-source.json`、`demo-schedule-v1.json`、`demo-schedule-v2.json`。它们的gymId为demo-local、日期固定；按新demo scope/明确anchorDate复制修改后使用，不修改捕获时间来伪装同步成功。V2省略的旧覆盖内课程将成为同ID cancelled。

```sh
node scripts/import-schedule.mjs register .runtime/test/demo-local/config.json examples/demo-schedule-source.json --confirm
node scripts/import-schedule.mjs plan .runtime/test/demo-local/config.json examples/demo-schedule-v1.json --session-file .runtime/test/demo-local/import-session.json
node scripts/import-schedule.mjs apply .runtime/test/demo-local/config.json <batchId> <contentHash> --confirm-replace --session-file .runtime/test/demo-local/import-session.json
node scripts/import-schedule.mjs status .runtime/test/demo-local/config.json <batchId> --session-file .runtime/test/demo-local/import-session.json
```

register由本机维护者确认，一个scope只允许一个来源；不能通过换sourceId绕过原映射。plan展示新增/变化/显式取消/遗漏转取消/移出覆盖，以及旧公开覆盖替换范围和绑定draft/publication版本。任何错误整批拒绝，不推进游标。字段schema全量v1固定；UTF-8、≤64KiB，原始文件SHA256（字节内容变化包括空白会改变hash），≤200课且补取消后也≤200，1–14天半开覆盖，严格UTC/本地offset、DST和重叠规则。

错误区分403权限、409版本/hash/CAS、422输入/到期、503存储或读取不可用；courseRow为courses数组内1开始的行号，field为安全字段名，不回显秘密或原文。任意未知/个人资料/连接字段不接受；课程名称/来源文本应仅含获准非个人内容，不能靠自由文本字段塞会员资料。

apply仅确认整份草稿替换；它不改变公开课表。发布仍用既有原生维护页（D3）或原HTTP `POST /v1/operator/schedule/publish`，提交新operationId、最近serverNow、当前draft/publication双版本及replaceCoverageConfirmed=true；会话只从私有文件载入，不能放命令行。

可用下面无token输出的HTTP片段审阅当前草稿与版本；用于另行确认发布/重plan：

```sh
node --input-type=module -e '
import {readFileSync} from "node:fs";
const c=JSON.parse(readFileSync(".runtime/test/demo-local/config.json"));
const {token}=JSON.parse(readFileSync(c.stateDir+"/import-session.json"));
const r=await fetch(`http://127.0.0.1:${c.port}/v1/operator/schedule/draft`,{headers:{authorization:"Bearer "+token}});
const b=await r.json();if(!r.ok||!b.ok)throw new Error(b.error?.code||"UNCONFIRMED");console.log(b.data);'
```

写入回应丢失先用batch status；同当前applied revision/hash返原结果，不重新改草稿；低版本/同已知版本异hash409。候选24h到期不能apply，普通同revision/hash的plan届时替换旧候选、重新绑定当前版本并重新审阅。低版本即使原batch明细清理仍被来源高水位阻止。

## 6. 获批准的显式失效候选重plan

普通plan对未过期同revision/hash仍返回原候选，不静默追逐人工编辑。仅当原候选绑定的本地版本已失效，由维护者读出当前版本并明确指定旧batch：

```sh
node scripts/import-schedule.mjs replan .runtime/test/demo-local/config.json examples/demo-schedule-v1.json <oldBatchId> <currentDraftRevision> <currentPublicationRevision> --confirm-replan --session-file .runtime/test/demo-local/import-session.json
```

不存在的版本使用literal `absent`，其余为正整数。此操作不改来源revision；同事务核对旧batch+原revision/hash+调用者确认的当前双版本，生成新差异后使旧batch永久不可apply。未失效候选不能借此延长寿命；再次变化/竞争失败409，无半替换。apply先成功则返回原applied结果，不能重写；更高来源高水位依然优先409。新候选必须重新审阅再单独apply/publish。依据两份 `doc/baseline/clarification-*.md` 批准说明，原四份v1.1文件未改。

## 7. 未来外部数据库边界与当前限制

当前只读版本化full JSON，没有自动轮询、增量CDC、外部数据库连接或回写。未来适配器须另行取得授权、schema/稳定主键、时区、字段语义、变更/删除与对账契约，仅产生本接口形状的候选；不能签发身份、绑定会员、授予角色或直接更新公开快照。不承诺未知数据库零改动直连。

source+externalId永久保持同UUID，合法改名改期/消失后返回复用；没有实体代际字段，无法客观检测来源回收键给另一实体，不加入猜测算法。明确获悉回收则停止该批apply并上报Leader，不能删映射/换来源/删库绕过；提交后发现也不自动回滚或重绑。

## 8. 验证命令与证据层

```sh
node --test test/demo-runner.test.mjs test/schedule-import.test.mjs test/import-process.test.mjs test/demo-cleanup.test.mjs
npm test
npm run check
```

HTTP/SQLite/进程中断/并发/CLI/VM及受控时间属于工程层。play测试用受控等待验证有限次数和锁；900秒准确边界沿用领域时钟测试，真实自然等待截图留D3。每个测试独占fixture，先停所有自有子进程并等待close，再关DB和删除目录。实际输出与逐AC见 `reports/cp5/demo-scenarios/`。
