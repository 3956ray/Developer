# GYM-CP1-IDENTITY-001 / CP1

RESULT: COMPLETE

提交当前CP交付，等待指挥者ACCEPTED；不进入CP2。工程PASS不等于微信平台、工具、真机或门店PASS。

## 基线

独立根：`/Users/orderly_ray/Projects/gym-miniapp`。开始时工作树干净，基线提交`0fdede77301c6c21d0e6fdd7cfe9865a937cb8d7`。正式三文档重新复算哈希与合同一致，未修改产品基线或CP0评审契约：

- PRD `7d9948f302f13119120c03043902fba7455437764eb57d4923efebda4254e1bd`
- 验收 `89cded4a468deaa20e6090d19b35dc31c77b6a01e3e45081d3d996484188ae71`
- Checkpoint `13060548afb17731e033364817f0e7ee6b0569c334bbfc4138e308ee64678882`

当前合同保存为task-contract.json。最终文件SHA在manifest.json，最终提交和提交后Git状态随交接消息提供；避免提交自引用hash。

## 文件与行为

| 文件/目录 | 作用 |
| --- | --- |
|server/migrations/002-identity.sql|身份唯一映射、账号/会话、用途同意、角色、登录次数、一次性code摘要、审计、操作台账；未创建后续领域表|
|server/identity.mjs|7日绝对/24小时闲置、5会话、登出、角色inspect/grant/revoke、锁内当前权限、持久化120/min、同事务审计及幂等|
|server/wechat.mjs|官方固定HTTPS交换适配器及严格错误处理；显式隔离测试适配器，无fallback|
|server/protocol.mjs|严格JSON、未知字段/重复键拒绝、规范body HMAC、intent校验与安全错误|
|server/config.mjs、http.mjs、main.mjs|身份模式/稳定命名空间、私密AppSecret文件、受控HTTP端点；默认无身份配置不能登录|
|scripts/role.mjs|本机授权维护者的版本化inspect/apply/result，无客户端授予入口|
|miniprogram/config.js、lib/session.js、pages/me/*|主动用途同意/拒绝、真实wx.login调用点、私有token缓存隔离、模拟标记、前后台串行刷新、失效/失败、退出和未知结果保留原key|
|test/foundation.test.mjs|保留CP0全部7个场景，迁移数量改为2并复制全部已应用迁移；session端点预期由不存在改为401；没有删弱化持久化/隔离验证|
|test/identity*.mjs、concurrency.test.mjs、role-worker.mjs、http-identity.test.mjs、native-session.test.mjs|身份/平台/权限/时间/并发/跨进程/HTTP/原生JS状态证据，所有fixture合成|
|AGENTS、README、doc/cp1-implementation.md|更新当前CP、可执行命令、版本读取和实现边界；PRD不变|
|reports/cp1|合同、测试输出、依赖/fixture说明、逐AC报告和文件SHA|

## 命令及实际结果

| 命令 | 退出码/结果 | 日志 |
| --- | --- | --- |
|node --test test/identity.test.mjs test/concurrency.test.mjs test/native-session.test.mjs（初期）|初期15 PASS；加入IPC锁测试后一次断言失败并中止，未隐去|identity-tests.log保留失败原因：IPC message还带undefined handle，不是业务状态失败|
|node --test test/concurrency.test.mjs test/native-session.test.mjs|0，修复测试驱动并验证锁和生命周期|lifecycle-concurrency-tests.log|
|node --test test/identity.test.mjs|0，含最终额外授权/回滚/intent边界|identity-final-tests.log|
|node --test test/native-session.test.mjs|0，8场景，包括失联退出保留key|native-tests.log|
|npm test（最终）|0，30 PASS，0 FAIL/取消/跳过，含原CP0七项|final-tests.log|
|npm run check|0，JS语法/JSON/原生引用/三份产品哈希PASS|check.log|

本机HTTP测试经权限审查运行127.0.0.1临时端口；不访问真实微信交换或外部服务。官方适配器验证使用独立transport；原生页面使用Node VM，不是微信工具截图。

## 逐AC工程证据

| AC/证据层 | 状态 | 场景与证据 |
| --- | --- | --- |
|AC-L01 工程|PASS|官方transport成功/无效40029、40163、40226/错误码/缺字段/错误JSON/HTTP500/网络异常；code摘要防重放；仅业务身份，不默认operator/会员；HTTP日志无token/code；test fixture与store/身份空间隔离。identity、http测试|
|AC-F01 工程|PASS|23:59:59.999有效、24h边界401；6d23:59:59.999有效、7d边界401；poll不续闲置，interactive不续绝对；第6会话撤最早；并发首次唯一；logout只当前；deleting预留锁；DB错误不认证。identity测试|
|AC-M04 角色基础|PASS|伪role/openid/gym拒绝；他店token401；CLI inspect给版本；grant/revoke审计同事务；旧token管理403、普通session仍有效；role/cache历史不复权；审计失败回滚。concurrency测试以真实子进程和持有写锁的事务屏障验证两种顺序：先撤销后受保护写失败；先授权写提交后撤销不删除历史。屏障用直接SQL暴露锁提交点，业务service路径另独立测试|
|AC-R01 登录|PASS|120次包含平台失败、第121次429和正确Retry-After；UTC下一分钟恢复；四真实进程共用120桶，重启仍限流；数据库不可用503无内存fallback；取时在BEGIN IMMEDIATE之后，用受控时钟证明从满旧桶跨入新桶。identity/concurrency/http测试|
|AC-P01 基础|PASS|用途说明可打开、拒绝无wx.login/请求，公共场馆仍独立；只有同意主动调用；不索取无关敏感能力；模拟标记、token不进视图，HTTP成功回调仍判业务状态。native测试|
|AC-I01 CP1共享部分|PASS|CLI/body变化409、CAS冲突、24h旧intent不能重执行、认证先于旧logout结果；300000/300001ms和未来30000/30001ms边界；logout审计失败不撤会话；不明结果重试保留同一key。identity/native测试|
|CP0全回归|PASS|原7个基础场景，迁移重放/失败回滚、探针SIGTERM/SIGKILL重启、同gym跨环境、配置失败、锁竞争。final-tests.log|
|原生生命周期工程层|PASS|已登录→隐藏（超过期限不信任客户端时间）→回前台挂起先待确认、失败不标有效、401清缓存；隐藏前迟到响应不重建新鲜态；A挂起hide/show后B排队、A晚到+手动请求最大并发1。native测试|
|真实微信/工具/真机层|NOT_RUN|无真实AppID/AppSecret、官方交换、实际编译/基础库验证、两系统真机/隐私后台/合法域名证书|

## Leader审阅修正

1. 登录限流取时移到写锁取得后，并新增满旧窗口→新窗口用例。
2. onShow先清除有效声明显示待确认；onHide使旧响应代际失效。
3. 不再通过清_refreshing假装取消请求；原请求结束前只排队，防止旧finally开启第三请求；生命周期用例证明max in-flight=1。
4. 两种角色锁提交顺序新增独立子进程证据，不只顺序函数断言。

## 未验证、限制与范围

- 缺真实AppID只阻塞真实平台层。官方文档工具读取失败，使用PM原始官方证据及已知协议做工程适配器测试；不能据此声称平台真实可用。
- 原生平台安全随机API/基础库兼容性未测，无安全随机时写请求失败关闭；无工具/真机视觉验收。
- CP1仅逻辑到期/撤销，后续小时物理清理、会员配对/核验/删除、人工忙闲、课表均未实现。operator最近审计只最近100条，before分页留后续维护界面集成，不影响当前角色基础验收。
- 默认应用无备份，OS快照/托管、安装发行签名及全量CVE审计未核实，沿用CP0限制。没有新增依赖或下载。
- 真实门店人员名单、会员资料、主体、用途说明对外配置均未使用。维护者确认字段不替代真实馆方授权。
- 没有修改项目外文件、冻结产品文档或CP0报告；无部署、上传预览、购买或发布；无扩大产品范围。

建议指挥者审阅当前提交后决定是否ACCEPTED。本开发任务交付后停止，不自动开始CP2。
