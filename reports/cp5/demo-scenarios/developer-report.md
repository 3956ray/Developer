# GYM-DEMO-SCENARIOS-001 / CP5-D2

RESULT: COMPLETE（开发交付，等待Leader验收）。D2 ENGINEERING: PASS；NATIVE BUSINESS TOOL / OFFICIAL IDENTITY / DEVICE / VENUE: NOT_RUN。未开始D3。

## 范围、基线与批准说明

唯一项目 `/Users/orderly_ray/Projects/gym-miniapp`；基线 `aae08a1badfb58c6a4c8dda4db8f7a6d5f312cbb`，开始工作树clean。已验收D1、原七产品文档和001–006迁移及历史报告保持。两份Leader批准的PM澄清精确副本/hash追加到baseline/source索引（approved-addenda.json）：外部键不可观测回收边界、失效候选显式重plan。未改原四份v1.1 hash，未新增外部schema字段。

复用Node24/SQLite与既有权限/事务/API，零新依赖。新增007迁移，不重建业务系统；仅test/simulation/test-app/demo.enabled的demo-* scope。默认客户端仍unconfigured，tracked/private urlCheck保持true，无外部连接/部署/上传/预览。

## 最终实现

1. **有限场景**：demo run start/step/status/resume/play与显式cancel。忙闲、会员、课表与独立解绑/删除/退出均经原HTTP。每个写检查当前actor/session/operator；别名不能冒充其他合成主体。进度保留原operationId、创建时间/CAS、最小版本及用户HMAC；token、配对码、原始会员引用、删除回执不写进度。配对码由当前会员会话临时读取且必须匹配原pairing/revision。
2. **中断/并发**：scope唯一未终结run和进程锁；play包含等待期间也独占且最多三步。实际已死PID标interrupted，live/不确定PID不抢。resume先查原台账，提交只补进度；未提交且原窗口内需明确retry，过期未知needs_review。初审之外自查补强：未知sent请求遇后续401/403不能变成可取消的“确定失败”；只有首次明确拒绝才回到prepared。撤销/限流/人工改版本暂停，不自动换会话、grant或改旧时间/key。
3. **JSON适配**：register独占来源；plan严格full v1/64KiB/200含补取消/1–14天/显式offset与DST/重叠/未知字段，保存候选差异。apply当前operator、确认hash与整草稿替换、双版本CAS；草稿/映射/批次applied/源游标/审计同事务。publish独立，稳定UUID，旧覆盖内遗漏课程同ID显式取消，映射不因清理/缺行删除。
4. **版本及来源**：低revision/同已知revision异hash409；当前applied同hash返原结果不双写；到期候选重plan绑定当前版本。按批准澄清，普通未过期plan仍复用；显式重plan需指定失效旧batch和调用者确认的当前版本，同事务替换、旧batch永久拒绝，竞争至多一个后继。草稿import/import-edited来源，publish复制不可变快照；公共仅白名单摘要，无batchId/外部键/actor，不把接收时间冒充capturedAt。
5. **有界清理**：接入原启动/小时调度。候选24h失效、applied明细与终态run30天；保持活跃/待核对、映射/版本底线和当前来源。按Leader初审修正为一个共享100条删除预算，包含demo_steps；先删步骤再删已无步骤的父run，无额外级联行。失败事务回滚，批间让出事件循环。

## 实际验证

- `npm test`：exit0，**130/130 PASS**（D1 110＋D2新增20）；最终源码输出final-tests.log，不复用旧通过结论。
- `npm run check`：exit0，check.log；语法/JSON、9份产品基线hash、页面引用；不是微信编译。
- `node --test test/schedule-import.test.mjs test/import-process.test.mjs test/demo-runner.test.mjs`：初期16/16；后续补充及最终修复以130全套为准。
- `node --test test/demo-cleanup.test.mjs`：共享预算专项通过，cleanup-budget.log；混合积压实际删除 **100＋100＋90条**（含步骤），观察到批间让出，故障回滚，needs_review仍保留。
- 实际HTTP：有限等级变化、暂停、角色撤销后新session仍403；会员核验/撤销/恢复/抢占409/解绑/删除/重启，新userId无旧role/binding且revoked registry保留；课表私有草稿、发布、同ID取消改期、撤回、空/覆盖外。
- 实际进程：SIGKILL发生在HTTP业务已提交、进度未提交之间；重启后原operationId补进度，只有一条事件。两进程apply仅一审计/revision；两显式replan仅一个后继。apply进程提交后未交结果即被杀，重开数据库查batch/重放原结果不二次写。CLI实际注册/plan/apply/status及run start/step/status；SQLite锁503且状态不变，非私有凭证文件拒绝。
- 边界与失败：未知/到期原意图不重写，401/403/429暂停，成员主体不匹配拒绝；合法改名改期/消失再出现保留ID；200输入及取消并集溢出、重复键、错scope、DST、覆盖0/15天、重叠整批拒绝；候选24h重plan、人工CAS、导入游标触发器故障整体回滚、清理失败重试与映射/当前provenance保留。

逐AC见ac-matrix.md/json：DM03、DM05–09、DM12当前工程PASS；DM09-RP1–RP5 PASS。演示会员标签/模拟核验说明已加入原生源码并随既有VM回归；本轮未进行原生UI/网络验收。自然900秒与真机不由受控工程时钟替代。

## 文件与操作说明

|文件组|目的|
|---|---|
|server/migrations/007-demo-scenarios.sql|进度/锁、来源/映射/批次/版本、provenance与清理状态|
|server/demo-runner.mjs、demo-scenarios.mjs；scripts/demo-run.mjs、demo.mjs|有限HTTP场景、原意图恢复、角色/主体/并发保护与CLI|
|server/schedule-import.mjs；scripts/import-schedule.mjs、private-input.mjs|严格JSON候选/确认应用/显式重plan，私有凭证读取|
|server/schedules.mjs|复用校验的同步导入事务接口、人工来源标记/发布复制/公开白名单|
|server/demo-cleanup.mjs、cleanup-runners.mjs|共享100条删除预算、小时执行器接线|
|miniprogram会员控制器与两页WXML|演示资格与模拟核验说明，无网络开关操作|
|test/demo-runner、schedule-import、import-process、demo-cleanup、demo-process-worker|新增20项实际工程验收/故障辅助进程；旧迁移计数和测试SQL改用显式列|
|doc/demo-scenarios.md、examples三份JSON、README/AGENTS|数据字典、具体命令、合成输入、未来外部适配边界与当前阶段|
|doc/baseline两澄清/sources；reports/cp5/demo-scenarios|批准规则精确副本和本次最终证据|

## 限制、清理、交接

外部键复用给另一实体在当前schema中不可观测；仅保证永久映射不重分UUID，确知回收需停止apply交Leader人工决定，不声称自动识别。未来数据库仍需授权/schema/稳定键/时区/增量删除契约；当前不连接真实数据库、不双向回写。

所有测试由独占fixture持有服务/worker；等待自有子进程close后再关DB删除，不保留有效凭证到报告。配置安全摘要/hash提取到configuration-evidence.json。默认配置和工具安全设置未改。历史manifest保持原提交意义；当前manifest/source-manifest只用本目录。最终提交号及clean在提交后交接消息核实。停止于D2，等待ACCEPTED，不自动进入D3。
