# gym-miniapp Agent 规则

## 项目定位

- 单店健身房原生微信小程序；当前CP2人工观察与公共展示；CP0、CP1已验收。
- 技术栈：原生JS/WXML/WXSS，Node 24.18.0 ESM、内置SQLite与node:test，零第三方npm依赖。
- Git根必须为`/Users/orderly_ray/Projects/gym-miniapp`，写Git前核对，不使用父级Projects历史。
- 入口：`miniprogram/app.js`、`server/main.mjs`；目录和命令见README。

## 工程规范

- 只执行指挥者派发的唯一CP，产品真源为`doc/baseline/`及sources.json指定原文；基线不可静默修改。
- `.runtime/`是生成数据，不提交数据库、密钥、配置或真实会员资料。
- SQL参数绑定；同步事务禁止await；已应用迁移不可编辑，只能新增迁移。
- 新依赖先核对官方来源、固定版本、许可、安全与适用性，记录后才下载/执行。
- 缺配置/密钥/数据库失败关闭，不自动切到测试或内存数据。

## 常用命令

- 初始化：`node scripts/init-local.mjs test local-demo`（仅首次）。
- 迁移：`node scripts/db.mjs migrate .runtime/test/local-demo/config.json`。
- 启动：`node server/main.mjs .runtime/test/local-demo/config.json`。
- 角色检查：`node scripts/role.mjs inspect <config> <userId> <gymId>`；变更须先读README的授权与请求文件说明。
- 观察清理：`node scripts/observation-cleanup.mjs run <config>`；状态：`node scripts/observation-cleanup.mjs status <config>`。
- 测试：`npm test`；语法/基线/配置检查：`npm run check`。

## Agent 使用与完成条件

- 当前有界连续工作由主agent完成；只有主agent可按适用授权委派，subagent不得再委派。
- 通过当前CP必要验证，报告逐AC证据与未验证层；COMPLETE不代表指挥者ACCEPTED。
- 不触碰项目外文件，不部署/上传/发布，不使用真实凭证或门店数据，不扩展后续CP。
