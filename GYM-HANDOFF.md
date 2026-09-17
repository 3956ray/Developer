# 健身房小程序换机交接 · 2026-09-17

## 当前事实

- 用户指定此任务为小程序指挥者，PM 调研/正式需求、dev 编码，由 Leader 逐项验收。
- 用户确认没有馆方真实后端，批准自有后端动态模拟与可扩展存储/接口。采用既有 Node 24.18.0 + 内置 SQLite、零 npm 第三方依赖；演示数据由脚本生成并持久化。
- v1.0 三份冻结基线、v1.1 四份演示增量及两份 D2 澄清均保留；PM 真源在 ProductManager，源码含精确副本及 hash。
- CP0–CP4、CP5-D1 已验收；D1 commit `aae08a1badfb58c6a4c8dda4db8f7a6d5f312cbb`。
- CP5-D2 开发交付 commit `ada2d60b96ab712a01e9e0cae9d7ccbcc91cc0b7`，开发报告130/130；Leader 尚未完成独立最终回归与所有 manifest 复核，不是 ACCEPTED。
- 初审发现清理每批超过100行，dev 已修正共享预算（含步骤）。下次复核 `server/demo-cleanup.mjs`、专项证据和 source/manifest 后运行最终回归；不要重新开始开发。
- 原控制快照仍为 IN_PROGRESS/dispatched；迁移副本已据已完成交付对齐 AWAITING_REVIEW/completed，owner=leader，保留原快照为证据。

## 下一步与停止边界

1. 恢复路径、绑定新 Leader/PM/dev 任务、检查 Git 状态和三份配置，运行 leader_check。
2. 独立完成 D2 验收；通过才写 acceptance 并更新状态。
3. 下一唯一任务 CP5-D3：官方微信开发工具中，显式演示登录，会员/运营两角色，忙闲、会员、课表和导入发布；对照真实 HTTP，验证重启、断网，以及一次真实自然900秒过期。不能用受控时钟测试冒充原生证据。
4. 网络仍为阻碍：已观测工具拦截本机HTTP。若采用临时项目开关“不校验合法域名、web-view（业务域名）、TLS版本以及HTTPS证书”，必须先取得用户对此组合开关的明确确认；仅当前项目/本次本地联调，结束恢复，当前未获确认。换机迁移不等于此项授权。
5. D3 通过后停止本轮演示 MVP；官方身份/真机 CP6、真实馆方 CP7 单独验收。不得上传/发布小程序或接真实数据。

## 工具与身份

用户已经在旧电脑选择并创建测试号；项目 AppID `wxef96b440323d98b4`（非密钥）。旧工具2.02.2608070、基础库3.17.2；CLI服务端口曾禁用，GUI可操作。新电脑须自行登录/恢复该测试号的访问权限。未迁移微信或 Codex 登录状态。默认小程序未配置后端，urlCheck=true；运行配置、票据与SQLite库不迁移，以命令重新生成。

## 角色/模型设置

旧 Leader `01a0a64a-8f38-7503-a202-29ed08322b14`；PM `01a08198-b2ec-7b11-9bc3-89ed40433df5`（调研健身房实时人数产品）；dev `01a0a64b-85f8-7790-88d9-fd1188ece566`（确定小程序开发范围）。它们是旧电脑历史标识，不保证新电脑可继续。
PM 自带 `.codex/config.toml`、知识库 agent roster/规则和 skills；Developer/main 保留三个角色 TOML 与原 disabled 配置。已带上 karpathy-guidelines 与 miniapp-devtools-cli-repair。模型别名可能依赖旧账号/供应商，新电脑需选择可用模型；不复制供应商凭证或自动开启停用配置。

## 资料定位

- `orchestration/history/`、`pending/`、`reviews/`：原派单和验收日志。
- 源码 `reports/cp5/demo-scenarios/`：D2报告、AC矩阵、测试、source-manifest/manifest。
- 源码 `doc/demo-scenarios.md`：数据字典、接口/场景命令及未来适配限制。
- 导入当前为 JSON v1 全量课表，计划→人工确认应用草稿→单独发布；未来数据库要补 schema/稳定键/时区/删除语义适配，不承诺即插即用。
- 不保存原始聊天数据库；本文件是工作交接摘要，正式文档与实际测试仍是事实来源。
