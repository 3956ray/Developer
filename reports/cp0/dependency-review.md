# CP0 运行时、依赖与安全门禁

核查日期：2026-09-16（Asia/Taipei）。本轮未下载/安装第三方代码，无npm依赖、install脚本、外部SDK、云资源或生产凭证。工具仅执行已安装运行时与本任务编写的代码；Python仅用于作者写文件，不是应用运行依赖。

| 组件 | 版本/来源/许可 | 用途与决策 |
| --- | --- | --- |
|Node|本机v24.18.0，路径/二进制SHA见runtime.json；[官方LTS发布](https://nodejs.org/en/blog/release/v24.18.0)、[该版本LICENSE](https://github.com/nodejs/node/blob/v24.18.0/LICENSE)，Node核心MIT、内置组件各自许可|使用已存在版本，未重新下载安装；读取官方来源不等同验证本机发行包签名。本机安装来源的完整供应链签名未独立核验；不声称零漏洞或允许公网发布|
|SQLite|实际查询3.53.1，由Node内置；[官方公共领域声明](https://www.sqlite.org/copyright.html)|使用磁盘DB、短同步事务；无第三方绑定包、原生安装脚本或扩展加载|
|node:sqlite|[固定v24.18.0 API](https://nodejs.org/download/release/v24.18.0/docs/api/sqlite.html)，release candidate稳定性标签|接口尚非稳定等级2，锁定运行时并用迁移/回滚/多进程/重启测试约束；适合当前本地CP0，不据此承诺未来托管与负载能力|
|node:http/crypto/test|随Node版本；[crypto](https://nodejs.org/download/release/v24.18.0/docs/api/crypto.html)、[官方test文档](https://nodejs.org/docs/latest-v24.x/api/test.html)|健康接口、安全随机密钥、内置集成测试；test网页当前指向24.21，实际行为以24.18执行结果为准，无引入新版API|
|微信原生骨架|作者新建，无组件库/插件/SDK|project/app配置，WXML/JS/WXSS；官方project/app文档访问工具返回Internal Error，未据此声称官方校验成功。实际微信编译、基础库与AppID能力NOT_RUN|

SQLite官方[事务](https://www.sqlite.org/lang_transaction.html)与[PRAGMA](https://www.sqlite.org/pragma.html)用于核对BEGIN IMMEDIATE、同步/日志/安全删除等选项。工程没有开放任意SQL，CLI探针值固定且只在test；无远程写接口；部署绑定环境/门店/密钥；扩展禁用、defensive、参数绑定、日志只输出脱敏状态，不记录配置或密钥。SQL版本及完整性测试来自实际运行，非前置预检照抄。

安全核查范围为当前本地功能：只读官方说明、检查全部新增依赖为空、审阅自写代码的文件/网络/SQL边界并测试失败关闭。未运行全量CVE扫描、未验证安装二进制发行签名、未做公网服务审计。未来引入第三方包必须先记录固定版本/官方来源/许可/安全状况，再下载；未来发布前复核受支持补丁和部署暴露面。本轮不需联网才能运行服务/测试；npm输出的升级通知未执行。

密钥仅测试/空白本地store使用，每次初始化crypto.randomBytes(32)独立生成，四用途分离，权限0600；不提交`.runtime/`。没有AppSecret、真实openid、会员引用或配对码。数据库无应用自动备份；OS/设备快照状态未核实，不承诺全副本擦除。

初次HTTP测试在沙箱内listen返回EPERM（tests.log）。随后自动权限审查允许本机临时端口测试，7/7通过（tests-local-network.log）；这不是上传/部署或微信官方能力授权。
