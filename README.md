# 健身房小程序 · CP0

独立原生微信小程序与本地服务基础。当前只实现两个静态入口、健康检查、迁移、隔离配置和合成持久化探针。没有登录、观察、会员或课表业务接口。服务不可对外部署。

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

重启不会重建密钥或清空数据库。初始化已存在目录会失败；重用已有配置启动即可。探针固定为`synthetic-non-member`，只允许test环境的本地CLI，HTTP无写入口。`store`初始化命令为`node scripts/init-local.mjs store local-store`，仅空白本地容器、无真实门店/会员数据，时区仍是待核实测试默认值；不得将其描述为门店验收。CP0不支持simulation适配器，两个环境均拒绝simulation=true。

```sh
npm test
npm run check
```

测试创建独立随机命名的`.runtime/test/cp0-*`与`.runtime/store/cp0-*`目录并在结束时删除自己创建的目录。包含真实子进程重启、SIGKILL后持久化、迁移篡改/失败回滚、锁竞争、配置和门店隔离。`check`只检查语法、JSON、基线哈希和页面引用，不是微信编译。

## 文件地图

- `miniprogram/`：原生WXML/JS/WXSS，场馆、我的静态骨架；无假数据、假登录。
- `server/`：本机HTTP健康接口、显式配置校验、SQLite迁移器。
- `scripts/`：首次初始化、数据库诊断与只在test可用的探针CLI。
- `test/`：内置node:test集成验证。
- `doc/baseline/`：已验收产品文档不可变副本与原始路径/哈希；副本里的历史相对外链保留原样，请从原路径查证，不把缺失的研究文件补成新真源。
- `doc/technical-contract.md`：全P0动作与未来实现契约；CP1起串行实现。
- `reports/cp0/`：合同、依赖核查、运行证据和逐AC交付报告。
- `.runtime/`：忽略的数据库、配置、密钥；禁止提交或发送。

## 配置与数据

`init-local`显式生成environment/gymId/stateDir/simulation/timeZone/host/port及四个独立32字节密钥。权限为目录0700、密钥0600，数据根固定在本项目`.runtime/{test|store}/{gymId}`。允许端口0供测试自动分配；服务只监听127.0.0.1。缺配置不启动，不自动新建或替换丢失密钥；DB部署标记绑定环境、门店及密钥指纹，拷贝到其他作用域会被拒绝。

本地SQLite采用DELETE journal、FULL同步、外键、secure_delete和2秒忙等待，迁移同事务且保存校验和。每次写使用BEGIN IMMEDIATE；锁超时失败，不使用内存回退。数据库仅用于同机本地磁盘，禁止网络共享文件系统。没有应用自动备份、云副本、遥测或外部请求；文件系统/设备备份不在本应用控制范围，未核实，不承诺全副本物理擦除。

## 原生工具边界

根`project.config.json`指向`miniprogram/`，`touristappid`仅占位，无实际AppID；合法域名校验保持打开。未运行微信开发工具编译、登录、预览上传或真机验证。后续配置真实AppID时使用本地私有配置并重新验证官方能力，不把当前骨架当作可运行完整MVP。

## 范围与提交

唯一合同GYM-CP0-FOUNDATION-001；交付后等指挥者验收。不得提前实现CP1–CP7。源码归本项目所有，尚未指定对外开源许可；Node及内置组件许可见依赖报告。只允许本地提交，无远端、部署或上传操作。
