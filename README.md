# 健身房小程序 · CP1

独立原生微信小程序与本地服务基础。当前实现主动登录/登出、可撤销会话、馆方权限CLI与持久化登录限流；CP0健康检查、隔离配置和合成探针保留。没有观察、会员配对/核验/删除或课表业务。服务不可对外部署。

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

- `miniprogram/`：原生WXML/JS/WXSS，场馆骨架及我的主动登录/用途说明/拒绝/刷新/退出；明确测试标签。
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

当前合同GYM-CP1-IDENTITY-001；交付后等指挥者验收。不得提前实现CP2–CP7。源码归本项目所有，尚未指定对外开源许可；Node及内置组件许可见依赖报告。只允许本地提交，无远端、部署或上传操作。

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

`npm test`包含CP0基础、精确期限、并发身份/5会话、跨进程120/min计数、锁内时钟跨分钟、角色撤销竞争、HTTP失败/重启、原生JS生命周期VM测试。涉及本机监听时受环境权限约束；不是微信编译或真机证据。会话/登录码台账/审计物理清理留后续CP，当前逻辑到期、撤销立即生效；无删除功能。完整证据见`reports/cp1/`。
