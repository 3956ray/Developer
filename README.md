# 健身房小程序 · CP5

独立原生微信小程序与本地服务基础。当前实现主动登录/登出、可撤销会话、馆方权限CLI与持久化登录限流；CP0健康检查、隔离配置和合成探针保留。已加入人工忙闲观察发布、公共展示、过期/撤销及观察历史清理；已实现会员配对、前台核验、资格恢复/撤销、解绑及持久删除；已加入单一草稿/当前公开快照、今日/本周与当前课程摘要。服务不可对外部署。

## 运行

使用本机 Node **24.18.0**（`.nvmrc`），内置 SQLite 与测试运行器；无 npm 依赖，无需安装。所有命令在本仓库根执行。

```sh
node scripts/init-local.mjs test local-demo
node scripts/db.mjs migrate .runtime/test/local-demo/config.json
node server/main.mjs .runtime/test/local-demo/config.json
```

另一个终端：

```sh
curl --fail http://127.0.0.1:8787/health
node scripts/db.mjs write .runtime/test/local-demo/config.json probe-manual
```

停止服务（Ctrl-C）并用原命令重启，再执行：

```sh
node scripts/db.mjs read .runtime/test/local-demo/config.json probe-manual
```

重启不会重建密钥或清空数据库。初始化已存在目录会失败；重用已有配置启动即可。探针固定为`synthetic-non-member`，只允许test环境的本地CLI，HTTP无写入口。`store`初始化命令为`node scripts/init-local.mjs store local-store`，仅空白本地容器、无真实门店/会员数据，时区仍是待核实测试默认值；不得将其描述为门店验收。默认identity未配置，不可登录。仅显式test身份模式允许test环境simulation=true；store永远拒绝测试替身。

```sh
npm test
npm run check
```

测试创建独立随机命名的`.runtime/test/cp0-*`与`.runtime/store/cp0-*`目录并在结束时删除自己创建的目录。包含真实子进程重启、SIGKILL后持久化、迁移篡改/失败回滚、锁竞争、配置和门店隔离。`check`只检查语法、JSON、基线哈希和页面引用，不是微信编译。

## 文件地图

- `miniprogram/`：原生WXML/JS/WXSS，场馆人工观察、馆方维护及我的主动登录/用途说明/拒绝/刷新/退出；明确测试标签。
- `server/`：本机HTTP健康接口、显式配置校验、SQLite迁移器。
- `scripts/`：首次初始化、数据库诊断与只在test可用的探针CLI，以及本机operator管理CLI。
- `test/`：内置node:test集成验证。
- `doc/baseline/`：已验收产品文档不可变副本与原始路径/哈希；副本里的历史相对外链保留原样，请从原路径查证，不把缺失的研究文件补成新真源。
- `doc/technical-contract.md`：全P0动作与未来实现契约；CP1身份部分已实现；后续逐CP实现，见doc/cp1-implementation.md。
- `reports/cp0/`：合同、依赖核查、运行证据和逐AC交付报告。
- `.runtime/`：忽略的数据库、配置、密钥；禁止提交或发送。

## 配置与数据

`init-local`显式生成environment/gymId/stateDir/simulation/timeZone/host/port及四个独立32字节密钥。权限为目录0700、密钥0600，数据根固定在本项目`.runtime/{test|store}/{gymId}`。允许端口0供测试自动分配；服务只监听127.0.0.1。缺配置不启动，不自动新建或替换丢失密钥；DB部署标记绑定环境、门店及密钥指纹，拷贝到其他作用域会被拒绝。

本地SQLite采用DELETE journal、FULL同步、外键、secure_delete和2秒忙等待，迁移同事务且保存校验和。每次写使用BEGIN IMMEDIATE；锁超时失败，不使用内存回退。数据库仅用于同机本地磁盘，禁止网络共享文件系统。没有应用自动备份、云副本、遥测或外部请求；文件系统/设备备份不在本应用控制范围，未核实，不承诺全副本物理擦除。

## 原生工具边界

根`project.config.json`指向`miniprogram/`，`touristappid`仅占位，无实际AppID；合法域名校验保持打开。未运行微信开发工具编译、登录、预览上传或真机验证。后续配置真实AppID时使用本地私有配置并重新验证官方能力，不把当前骨架当作可运行完整MVP。

## 范围与提交

当前合同GYM-CP5-INTEGRATION-001；交付后等指挥者验收。不得提前实现CP6–CP7。源码归本项目所有，尚未指定对外开源许可；Node及内置组件许可见依赖报告。只允许本地提交，无远端、部署或上传操作。

## CP1 身份配置与安全边界

默认配置无identity或`identity.mode=disabled`，登录返回503，未接入也不假登录。

- **测试替身**：仅在本地test的私有config.json显式设置`"identity":{"mode":"test","appId":"test-app"},"simulation":true`。只有测试预置的一次性code摘要能交换合成身份，任意wx.login code不自动成功。测试fixture由`test/identity-support.mjs`写入独立临时DB，不提供公共种子接口或客户端测试登录按钮；原生页面使用真实wx.login API，测试由VM替身驱动，未声称微信工具端到端通过。
- **官方适配器**：配置`identity.mode=wechat`及真实AppID；AppSecret只放同私有stateDir的`wechat-secret.txt`，0600权限、与四个业务密钥分开。此次没有创建/使用真实AppID或AppSecret，勿把示例当授权。适配器仅访问固定微信HTTPS地址，不跟随重定向、不记录URL/正文/秘密。
- 身份空间首次启用后绑定adapter mode与AppID；不能把已含合成身份的DB切到官方模式。禁用身份后会话接口不可认证。
- 原生`miniprogram/config.js`默认未配置。未来本地联调可显式设置baseUrl和environment；test只允许127.0.0.1 HTTP，store必须HTTPS。修改后需相应工具验证，域名校验仍保持开启。token缓存按环境和baseUrl隔离。
- server/main只接受配置文件，没有HTTP可注入的时钟、fixture或transport。受控时钟/transport仅test环境内的单元测试参数。

### operator 命令（部署维护者，本机OS账户边界）

没有客户端自助授予接口。只有获授权、可读取本地私有配置/DB的部署维护者执行。真实人员授予前先由负责人核对；请求中ownerConfirmed是流程确认，不能替代实际授权。CLI用户不得共享机器登录/私有数据目录。

```sh
node scripts/role.mjs inspect <config-path> <existing-user-id> <gym-id>
node scripts/role.mjs apply <config-path> <request-json-path>
node scripts/role.mjs result <config-path> <maintainer-id> <operation-id> role.grant
```

apply请求为JSON：action=grant/revoke、userId、gymId、maintainerId（受控维护者标签）、reasonCategory（initial-authorization/authorization-renewed/authorization-ended/correction）、ownerConfirmed=true、operationId（新UUIDv4）、requestCreatedAt（当前UTC毫秒ISO）、expectedRevision（inspect结果，首次为字符串absent）。不要把凭证或成员原始资料放进reason。冲突先inspect，重新确认才建立新请求；超时查询原操作，勿自动换key。授予、撤销、审计和幂等同事务；旧token下一管理请求即时403，但仍可作为普通业务用户使用。

### 验证层

`npm test`包含CP0基础、精确期限、并发身份/5会话、跨进程120/min计数、锁内时钟跨分钟、角色撤销竞争、HTTP失败/重启、原生JS生命周期VM测试。涉及本机监听时受环境权限约束；不是微信编译或真机证据。CP1当时仅逻辑到期、撤销；当前CP3已补会话/登录码台账/审计清理与删除功能。完整证据见`reports/cp1/`。

## CP2 人工忙闲

未登录可读`GET /v1/venue`和`GET /v1/observations/current`。目前没有已核实馆名、营业时间和联系方式，因此保留空值；不填示例电话或人数。测试环境的观察始终标“模拟数据”。

在“我的”主动登录后进入“馆方维护”，入口及维护页都查询服务端角色；只有operator可发布较空/适中/较忙，或设置暂无法判断/暂停/撤回。操作需确认、expectedRevision、UUID操作键与serverNow基准时间；发布窗口60秒，控制状态5分钟。后台每次重检当前角色，观察/当前头/审计/幂等同事务，成功新写才延长会话闲置时间。

现场观察15分钟有效，899秒仍有效、900秒过期；撤回不回退旧记录。页面按服务端时间和单调经过时间判断，离线重启、未知时间或网络失败只展示历史记录。隐藏停止轮询，回前台重新确认；每资源仅一在途刷新。操作结果不明时保存原请求，先查询原操作，不自动更换key/时间/版本；超过发布窗口须重新现场观察。

### 观察历史清理

服务启动立即恢复，然后按持久化到期时间安排下一轮（每轮完成后1小时），避免固定tick早于next_run_at漏跑。每批最多100条、批间让出事件循环；publishedAt满30天的历史可清除，当前head、控制状态、revision及原TTL不受影响。失败回滚当前批，保存游标/错误次数，下一小时重试；重启立即补跑。

```sh
node scripts/observation-cleanup.mjs status <config-path>
node scripts/observation-cleanup.mjs run <config-path>
```

CLI仅供已授权本机维护者，run强制补跑当前任务；不新增公共清理接口。CP2实现观察清理；当前CP3已补会员与身份生命周期清理。新增迁移003保留先前迁移不变。详见`doc/cp2-implementation.md`和`reports/cp2/`，原生JS的VM测试不是实际微信编译/真机证据。

## CP3 会员与删除

“我的 → 会员与账户”可申请/取消配对、查看资格、解绑、删除；“馆方维护 → 前台会员核验”需当前 operator 权限。8位配对码保留前导零，600秒有效；码本身不是会员资格。前台须核对原馆凭据和当面请求，精确查询版本后确认绑定。撤销仍占当前关联槽位；解绑/删除不改变最小资格登记，revoked 必须显式核验恢复。

日期型期限按配置 IANA 时区的次日午夜排他换算；使用服务端 expiry-preview，午夜不存在/重复则拒绝自动选择，需馆方确认明确 UTC。pending_confirmation 不显示有效，no_fixed_expiry 需明确选择。配置时区必须经过门店确认，测试默认不能当门店事实。

解绑/删除要求最近5分钟主动认证。删除请求发送前在本机保存安全随机回执；接受后立即停用账号，清理异步恢复，完成后7日内可凭回执查询。回执仅保存在本机，勿共享或清除；未知回执不表示删除成功。当前应用不取消原馆合同、不删除馆方原系统记录。

```sh
node scripts/member-cleanup.mjs status <config-path>
node scripts/member-cleanup.mjs run <config-path>
node scripts/member-cleanup.mjs retry <config-path> <job-id>
```

仅授权本机维护者使用；retry 将现有未完成 job 置为可重试，不创建新任务，随后 run 执行。启动自动恢复，此后按完成时间每小时安排；每批最多100行，失败保留账号停用、身份锁和进度。超过24小时未完成回执显示延迟。

实现及 API 见 `doc/cp3-implementation.md`，验证见 `reports/cp3/developer-report.md`。原生测试为受控 VM；实际微信编译、真机和现场核验均尚未运行。

## CP4 课表维护与发布

“场馆”无需登录可查今日/本周和当前排课摘要；空日、未发布、覆盖外、覆盖结束、撤回与网络未知分别显示。没有真实课程时保留空态，不预置课程。“无排课”不表示场地无人。课程没有忙闲观察的15分钟TTL。

“馆方维护 → 课表草稿与发布”编辑一个完整草稿，覆盖1–14个门店本地日期（起含止不含）。课程必须填本地开始/结束及明确UTC偏移；服务端按IANA时区回算，拒绝不存在时刻和错误偏移。重复时刻由操作者明确选择偏移。跨午夜/周一课程保留完整区间；取消/改期保留courseId。旧覆盖内课程不能直接漏掉，须标取消；移出新覆盖范围须明确操作。

保存不改变公开数据；发布需明确确认将整体替换的范围，并校验草稿和公开版本。scheduled重叠阻止发布，首尾相接允许。撤回不回退历史。一旦当前发布存在，配置时区变动会拒绝启动/读写；需用原配置先撤回，然后受控维护确认新时区、重新保存换算草稿，再确认发布。没有小程序端改时区接口。

```sh
node scripts/schedule-cleanup.mjs status <config-path>
node scripts/schedule-cleanup.mjs run <config-path>
```

旧快照在非当前且覆盖结束UTC+30天后可清理，每批100、失败回滚并持久记录重试状态，启动恢复、每小时再跑；保留当前快照/草稿/撤回控制。课表实体不新增个人作者关联，审计/操作台账使用既有删除去关联机制。

详见 `doc/cp4-implementation.md` 和 `reports/cp4/developer-report.md`。当前仍只完成本地源码/合成测试，真实微信工具/真机/门店课表均未验证。

## CP5 工程与工具结果分层

工程集成增加三个清理任务共用的启动/小时调度入口，验证实际持久事务、跨域TTL/撤销资格不变、失败后下一小时恢复、旧幂等结果清理后旧意图拒绝。原生端修正删除迟到响应不得清除新会话，以及页面隐藏后旧登录确认/角色查询不得继续登录或跳转。

本机官方工具Stable 2.02.2608070已发现，但CLI明确返回服务端口关闭；目标项目没有成功导入/编译/模拟器页面证据。保持urlCheck=true、touristappid占位及未配置客户端服务，不绕过域名/身份验证。未执行preview/upload。CP5整体BLOCKED，工程PASS不能替代工具验收；详见reports/cp5/developer-report.md与30AC分层矩阵。
