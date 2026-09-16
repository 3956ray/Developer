# GYM-CP2-OBSERVATION-001 / CP2

RESULT: COMPLETE

当前CP工作和证据交付完整，等待指挥者ACCEPTED，不进入CP3。

## 基线、范围与源码

独立根：`/Users/orderly_ray/Projects/gym-miniapp`。开始时工作树干净，基线提交`7a9e76c0123ec3c80ab3aa9bde9886f1e8ad6880`。中途额度失败后继续同一任务，未重建工程。最终提交hash与提交后工作树状态在交接消息提供；文件SHA见manifest.json，最终测试前固定的代码SHA见tested-source.json，交付前再次逐项复核。

合同task-contract.json与Leader当前合同一致。三份产品输入重新核验且未修改：

- PRD `7d9948f302f13119120c03043902fba7455437764eb57d4923efebda4254e1bd`
- 验收 `89cded4a468deaa20e6090d19b35dc31c77b6a01e3e45081d3d996484188ae71`
- Checkpoint `13060548afb17731e033364817f0e7ee6b0569c334bbfc4138e308ee64678882`

## 修改文件及行为

| 文件 | 目的 |
| --- | --- |
|server/migrations/003-observations.sql|观察当前头、历史事件、清理进度；旧迁移不变|
|server/observations.mjs|公共场馆/观察快照，人工三级/控制、900秒TTL、异常时间判定、CAS及事务写|
|server/identity.mjs、protocol.mjs|只扩展观察所需的operator幂等写、60秒intent、历史结果实时鉴权及成功互动续闲置；保留CP1行为|
|server/http.mjs、main.mjs|观察API、同一快照时间、启动/定时清理及关闭等待|
|server/observation-cleanup.mjs、scripts/observation-cleanup.mjs|30天、每批100、持久游标/错误/重启恢复、完成后到期调度、本机run/status|
|miniprogram/lib/observation-view.js|文字状态、可信单调时间、精确过期、历史降级和原时间展示|
|miniprogram/lib/observation-page.js|公共/维护页面共享刷新与生命周期、权限、确认冻结版本、原操作缓存/查询/同key重试|
|miniprogram/pages/venue/*、pages/maintenance/*、app.json|原生公共展示与角色保护维护页，不使用HTML替代小程序|
|miniprogram/pages/me/index.js/index.wxml|查询当前role后进入维护页；普通用户无权限|
|test/observations.test.mjs|服务端边界、CAS/幂等/回滚、无权、闲置、清理故障/恢复/调度|
|test/observation-integration.test.mjs、observation-worker.mjs|两个真实HTTP读客户端、进程重启、两位operator独立进程竞争、撤销写锁屏障|
|test/native-observation.test.mjs|原生JS的正常/空/控制/过期/异常/离线/隐藏/迟到响应/未知提交交互|
|test/foundation.test.mjs|仅随003更新迁移数量与故障测试编号；保留原7项范围|
|AGENTS.md、README.md、doc/cp2-implementation.md、reports/cp2|当前CP规则、可复现操作、实现说明和逐AC交付|

没有新增依赖、改动冻结产品基线或CP0/CP1报告，没有修改本项目外文件。

## 命令、退出码与证据

| 命令 | 结果 | 输出 |
| --- | --- | --- |
|node --test test/observations.test.mjs|0；6/6|observation-tests.log|
|node --test test/native-observation.test.mjs|0；最终7/7|native-tests.log|
|npm test（初轮完整）|0；43/43|full-tests.log|
|npm test（最后页面版本固定及两个新增用例后）|0；45/45，无失败/取消/跳过|final-tests.log|
|npm run check|0；语法/JSON/原生页面引用/三份基线SHA通过|check.log|
|node scripts/observation-cleanup.mjs run/status <synthetic-config>|均0；实际迁移与清理状态读取|cleanup-cli.log|
|git diff --check|0|交付前执行；提交前另检查暂存差异|

HTTP测试经本机端口权限审查，只监听127.0.0.1临时端口。最终45项包含已验收CP0/CP1的30项，新增CP2共15项。Leader早期独立43项验证不是本次最终45项源码的替代；开发者最终日志与tested-source.json对应。

## 逐AC工程层

| AC | 状态 | 实际证据 |
| --- | --- | --- |
|AC-B01场馆 / O01|PASS|无登录GET场馆/当前观察，never空态，null馆名/联系/营业；HTTP发布后两个独立无令牌客户端读取同revision/source/observedAt/validUntil；无actor和人数；普通用户不能写。observations及integration测试|
|AC-O02|PASS|后端899秒有效、900秒expired；原生逻辑同边界；GET/会话互动/重启/清理不修改head时间；过期旧等级只放历史。observations及native测试|
|AC-O03|PASS|never、unknown、paused、withdrawn、未来/null时间正确降级；固定后台错误类别；撤回后不扫描旧事件；历史删除后withdrawn不复活。observations/native/HTTP测试|
|AC-O04 / I01观察|PASS|同scope/key/body仅一次event/audit；body变409；新发布60000ms接受、61000ms拒绝，已有提交可历史重放不续TTL；两位operator真实进程CAS仅一成功，同key两个进程仅一事件；审计失败head回滚；撤销后的重放/历史查询403；页面未知结果先查、同key重试、过期不补发、确认弹窗冻结revision。服务/native/integration测试|
|AC-O05|PASS（工程/VM）|首屏/前台/手动/60秒刷新，隐藏停轮询，旧响应代际失效；挂起A→hide/show→B排队→手动刷新最大一在途；serverNow+单调经过时间，倒退/缺时钟/离线重启只历史；失败保留原时间不新鲜。native测试|
|AC-D01观察|PASS|观察发布后真正结束服务进程再启动，另一个HTTP客户端仍取原快照；test/store和密钥/DB隔离沿用通过的原回归；严格字段拒绝simulation及numeric occupancy（含store规则视角），无测试fallback；测试环境文字标模拟。foundation/observations/integration测试|
|AC-C01观察|PASS|精确30天cutoff，每批100；250条中第2批注入失败回滚，cursor/计数保留，关闭DB重开后恢复；head控制/revision/TTL不变，重复清理一致；实际服务启动删除31天历史保留当前快照。fake timer+真实DB让201条分3批各耗时5秒，完成后下一小时实际运行不skipped。cleanup测试/CLI/HTTP启动证据|
|原CP0/CP1回归|PASS|30项保留，变更共享鉴权/协议/迁移计数后完整通过。final-tests.log|
|微信工具/真机/真实馆方层|NOT_RUN|无真实编译、AppID官方交换、基础库验证、真机/现场巡视或真实门店记录|

角色顺序测试明确：观察真实业务写先提交的两条记录在随后撤销后仍保留；第三个真实子进程观察写被事务屏障阻塞，角色撤销先提交后它返回FORBIDDEN，head版本未变化。屏障使用直接SQL暴露角色提交点，写入执行的是实际observation路径，不把假的观察回调当业务证据。

## 审阅反馈与最后变更

- Leader发现固定interval可能早于next_run_at而漏轮；已改为完成后按持久化到期时间单次调度，跨批耗时的确定性数据库/定时测试通过。
- 独立HTTP双读、服务进程重启、两位operator实际观察路径竞争已补齐，不再只用同对象两个读函数代表双客户端。
- 最后页面变更是在确认弹窗打开时冻结expectedRevision，避免轮询悄悄替换用户所见版本；新增对应测试及页面过期/网络失败原时间保留测试。最终全套45项均验证该源码版本。

## 合成数据、未验证项与风险

1. 所有code、业务身份、operator、等级、历史事件均测试合成；没有现场观察或真实会员资料。馆方观察点、馆名/营业/联系人等未核定，显示空态。
2. 原生页面证据为真实JS在Node VM中的状态/交互测试，不是WXML渲染截图、微信工具编译或真机。wx.getPerformance/getRandomValues及Intl实际基础库支持未验证；缺可信时间不显示新鲜，缺格式能力明确UTC标签。
3. 缺真实AppID/域名/证书/设备不影响工程交付；官方登录、真实隐私后台与门店层仍未验证。
4. 仅观察历史清理已实现；没有配对/核验/删除、课表、人数、门禁、预测、支付，也没有其他领域的小时清理或通用任务平台。
5. 运行时release-candidate绑定、安装签名与OS/托管快照未核验限制沿用CP0；无新增依赖或备份，不宣称全副本删除或公网部署适用。

## 范围与下一步

范围无偏移；未部署、上传预览、购买或发布。当前交付后停止，等待指挥者对CP2验收，不能自动开始CP3。
