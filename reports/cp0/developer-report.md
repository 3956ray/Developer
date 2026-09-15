# GYM-CP0-FOUNDATION-001 交付

RESULT: COMPLETE

CP：CP0。仅表示合同交付完整；等待指挥者ACCEPTED，不进入CP1。

## 基线与归属

目标 `/Users/orderly_ray/Projects/gym-miniapp` 创建前不存在；本任务新建目录。先读工作区AGENTS/建项模板、合同及PRD验收意见，三份正式输入SHA验证一致后读全文并原样复制。

- PRD：`7d9948f302f13119120c03043902fba7455437764eb57d4923efebda4254e1bd`
- 验收：`89cded4a468deaa20e6090d19b35dc31c77b6a01e3e45081d3d996484188ae71`
- Checkpoint：`13060548afb17731e033364817f0e7ee6b0569c334bbfc4138e308ee64678882`

原路径和副本对应见`doc/baseline/sources.json`。无项目基线提交（新建仓库）；源文件最终SHA见`manifest.json`。使用`git init -b codex/cp0-foundation`独立初始化后，show-toplevel精确为本项目（git-boundary.txt）。未使用父级Projects提交历史；最终提交hash与提交后状态在指挥者交接消息中回报，避免在提交内自引用hash。

## 修改文件与目的

- `.gitignore`、`.nvmrc`、`package.json`、`AGENTS.md`、`README.md`：独立边界、固定运行时、可复现命令、私密运行数据排除。
- `project.config.json`、`miniprogram/`：原生场馆/我的两个静态入口，无假数据/假登录；AppID占位。
- `server/config.mjs`：显式test/store、gym配置，限定本项目存储、本机端口，密钥/文件权限/作用域校验，拒绝simulation。
- `server/database.mjs`、`server/migrations/001-foundation.sql`：真正磁盘迁移、哈希台账、部署隔离标记、事务与固定合成探针表。
- `server/main.mjs`：GET /health读真实数据库；其余API404；无业务写HTTP。
- `scripts/init-local.mjs`、`scripts/db.mjs`、`scripts/check.mjs`：本地首次初始化、迁移/探针、语法/JSON/原生页面引用/产品副本检查。
- `test/foundation.test.mjs`：7项有界集成测试；包括独立进程退出重启、SIGKILL、多进程写锁、跨环境同gymId隔离。
- `doc/baseline/`、`doc/technical-contract.md`：不可变产品参考与全P0接口/数据/事务/密钥/清理技术契约；后续业务实现明确未进行。
- `reports/cp0/`：任务合同、依赖/运行时核查、命令输出、逐AC交付及manifest。

## 命令与结果

| 命令 | 退出码/结果 | 证据 |
| --- | --- | --- |
|node --version + 内置SQLite查询|0；v24.18.0、SQLite 3.53.1|runtime.json|
|git init -b codex/cp0-foundation；git rev-parse --show-toplevel|0，独立仓库|git-boundary.txt|
|npm test（初次沙箱）|1；6通过，1启动失败；明确EPERM|tests.log；未删掉失败证据|
|npm test（允许本机监听，最终源码）|0；7通过0失败|tests-local-network.log|
|npm run check|0；语法/JSON/基线哈希/原生引用PASS|check.log；不称微信编译|

测试的durable probe场景实际执行独立`node scripts/db.mjs migrate`、`node server/main.mjs <config>`、HTTP GET /health、独立CLI写`probe-restart`，SIGTERM等待子进程退出，换PID重启后独立CLI读取原行，再SIGKILL并重启读同一行。日志记录迁移hash、integrity_check=ok、HTTP返回、原created_at与固定合成值；无内存替代。迁移篡改拒绝且台账不变，新迁移DDL失败回滚，多进程竞争失败后释放锁可写。

所有数据合成，store只建立空白隔离容器；同gymId但不同environment有不同DB及密钥，store探针写拒绝，跨环境复制DB被拒绝。另一个test门店读不到探针；simulation=true、缺配置/钥、权限过宽、密钥改变拒绝。测试清理仅删除自己UUID命名目录。

## 逐AC与证据层

| AC/合同项 | 本次状态 | 证据与界限 |
| --- | --- | --- |
|AC-E01 工程|PASS|独立Git、运行时核查、实际迁移/HTTP健康、README命令及测试；工具层NOT_RUN|
|AC-D01 基础|PASS|进程重启/SIGKILL原探针保留、同gym跨环境及其他店隔离、失败关闭；业务领域重启留后续CP|
|AC-I01 契约|PASS（设计层）|technical-contract §2/3：全scope、HMAC body、24h、5分钟/观察60秒intent、CAS、鉴权优先、历史/当前分离和逐动作事务；业务幂等测试NOT_RUN|
|AC-F01 契约|PASS（设计层）|§3/4：身份唯一、7日/24小时、主动动作与轮询分离、5会话、角色事务排序；身份功能NOT_RUN|
|AC-R01 契约|PASS（设计层）|§4：固定UTC桶、持久化原子计数、未知码单维/已知双维、故障回滚；限流业务NOT_RUN|
|AC-R02 契约|PASS（设计层）|§4/5：前导0、安全随机、活跃唯一、5碰撞rollback旧请求、AES-GCM短时展示、独立HMAC与稳定密钥；配对功能NOT_RUN|
|AC-C01 契约|PASS（设计层）|§6：启动/小时、有界批次/进度、每对象期限和当前快照例外；清理执行器NOT_RUN|
|AC-C02 / X01 契约|PASS（设计层）|§3/5/6：提交前回执、deleting身份锁、立即撤销、持久任务/去关联/完成7日、无应用备份与文件系统限制；删除功能NOT_RUN|
|AC-Z01 契约|PASS（设计层）|§3/4/7：单当前指针、草稿/发布双CAS、1–14日、跨日重叠/DST输入确定性；课表功能NOT_RUN|
|原生manifest/scaffold|PASS（文件/语法层）|check.log；微信工具编译/官方登录/双系统真机NOT_RUN|

## 未验证与风险

1. 微信工具实际导入/编译、基础库版本、touristappid可用性未验证；官方配置文档抓取失败已记录。真实AppID、官方登录、域名/证书、预览上传、两系统真机均NOT_RUN。
2. CP1–CP4业务路由、权限、会员、观察、课表及CP5清理执行器仅有契约，均未实现/未测试，不以本次7项测试覆盖它们。
3. SQLite node绑定为release candidate；固定Node版本及本地测试证明当前范围适用，升级/公网/负载/托管不在此次验证范围。本机安装发行签名及全量CVE审计未完成；无新增第三方下载。
4. 无应用自动备份；文件系统/设备快照未核实，未承诺全副本物理擦除。无真实用户数据。
5. 实际门店时区、营业/联系方式、馆方人员、会员与课表仍待后续阶段；当前Asia/Taipei只是工程基准。

## 范围检查与下一步

只有本项目新增文件；未修改Leader/PM文档、Android配置、thinkV2或其他产品。未实现CP1–CP7、未部署、未上传预览、未购买、未公开发布、未用真实凭证或会员资料。指挥者首轮审阅提出的Registry版本取得和accountRevision缺口已补齐：增加operator精确memberRef核对、GET /session分列accountRevision，以及逐动作版本来源表；只修契约，未开通业务。没有范围/产品参数偏移。建议指挥者验收CP0后另行派CP1；本任务在交付后停止。
