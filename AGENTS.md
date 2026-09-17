# gym-miniapp Agent 规则

## 项目定位

- 单店健身房原生微信小程序；当前CP5-D2有限场景与JSON课表导入（v1.1及批准澄清）；D1已验收；CP0–CP4及清理返工已验收。
- 技术栈：原生JS/WXML/WXSS，Node 24.18.0 ESM、内置SQLite与node:test，零第三方npm依赖。
- Git根必须为当前小程序独立工作树（`git rev-parse --show-toplevel`），写Git前核对；换机使用 Developer 仓库的 `gym-miniapp` 分支，不在 Developer/main 执行产品开发。
- 入口：`miniprogram/app.js`、`server/main.mjs`；目录和命令见README。

## 工程规范

- 只执行指挥者派发的唯一CP，产品真源为`doc/baseline/`及sources.json指定原文；v1.0冻结；v1.1仅在显式test演示模式覆盖指定条款，四文档hash见sources.json。
- `.runtime/`是生成数据，不提交数据库、密钥、配置或真实会员资料。
- SQL参数绑定；同步事务禁止await；已应用迁移不可编辑，只能新增迁移。
- 新依赖先核对官方来源、固定版本、许可、安全与适用性，记录后才下载/执行。
- 缺配置/密钥/数据库失败关闭，不自动切到测试或内存数据。

- 演示仅test + simulation=true + identity=test/test-app + demo.enabled=true + demo-*；不因alias授予角色，不回退假官方登录。
- 当前只D2；不得提前做D3网络开关/原生联网，见doc/demo-scenarios.md。
- 场景业务写走原HTTP API，凭证仅stdin/私有文件；导入apply经当前operator与原课表校验，同事务更新草稿/映射/批次/游标，发布另确认。
- 导入/场景清理共享每批100条删除预算，包含步骤；外部键与显式重plan按两份clarification基线，不猜测键回收。

## 常用命令

- 演示：`node scripts/demo.mjs init demo-local`；凭证：`node scripts/demo.mjs ticket <config> member-a`（300秒、不可记录有效凭证）。
- 场景：`node scripts/demo.mjs run <config> start crowd-v1 --anchor-date <date> --sessions-file <private-file>`；导入：`node scripts/import-schedule.mjs plan <config> <json-file> --session-file <private-file>`。
- 初始化：`node scripts/init-local.mjs test local-demo`（仅首次）。
- 迁移：`node scripts/db.mjs migrate .runtime/test/local-demo/config.json`。
- 启动：`node server/main.mjs .runtime/test/local-demo/config.json`。
- 角色检查：`node scripts/role.mjs inspect <config> <userId> <gymId>`；变更须先读README的授权与请求文件说明。
- 观察清理：`node scripts/observation-cleanup.mjs run <config>`；状态：`node scripts/observation-cleanup.mjs status <config>`。
- 会员清理：`node scripts/member-cleanup.mjs run <config>`；`status <config>`；`retry <config> <jobId>`。
- 课表清理：`node scripts/schedule-cleanup.mjs run <config>`；`status <config>`。
- 测试：`npm test`；语法/基线/配置检查：`npm run check`。

## Agent 使用与完成条件

- 当前有界连续工作由主agent完成；只有主agent可按适用授权委派，subagent不得再委派。
- 通过当前CP必要验证，报告逐AC证据与未验证层；COMPLETE不代表指挥者ACCEPTED。
- 不触碰项目外文件，不部署/上传/发布，不使用真实凭证或门店数据，不扩展后续CP。

## 换机交接

先读 `GYM-HANDOFF.md`；旧绝对路径为历史来源，按 Leader 的迁移说明映射。源文件改动仅迁移规则，不表示 D2 已验收。
