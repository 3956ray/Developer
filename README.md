# Developer 多项目工作区

本仓库保存独立项目的索引、工作区规则和可迁移的 Codex agent 设置。每个子项目有自己的代码、依赖、Git 历史和执行命令；本工作区不是单一产品或 monorepo。

## 项目链接

已有 GitHub 仓库的子项目仅提供链接，不重复上传源码，也不使用 Git submodule。

| 本地子目录 | GitHub 仓库 |
| --- | --- |
| `Faraway-Verse/` | [3956ray/Faraway-Verse](https://github.com/3956ray/Faraway-Verse) |
| `iknewit/` | [3956ray/iknewit](https://github.com/3956ray/iknewit) |
| `peiban/` | [3956ray/peiban](https://github.com/3956ray/peiban) |
| `think/` | [3956ray/think](https://github.com/3956ray/think) |

本仓库不备份这些子项目尚未提交或尚未推送的本地修改。子项目专属的 `AGENTS.md`、`.agents/` 和 `.codex/` 由各自仓库维护。

## 保留的 agent 设置

- `AGENTS.md`：项目边界、委派规则及验证要求。
- `PROJECT_AGENTS_TEMPLATE.md`：建立新子项目时使用的规则模板。
- `.codex/agents/workspace-explorer.toml`：唯读探索角色。
- `.codex/agents/project-worker.toml`：实现与验证角色。
- `.codex/agents/project-reviewer.toml`：独立唯读审查角色。
- `.codex/config.toml.disabled`：原有会话配置备份，保持停用状态，没有自动改名启用。

## 在另一个账号或电脑上使用

1. 使用自己的 GitHub 身份克隆工作区；私有仓库需要该身份拥有访问权限：

   ```bash
   git clone https://github.com/3956ray/Developer.git Projects
   cd Projects
   ```

2. 按需克隆子项目，保持上表的目录名：

   ```bash
   git clone https://github.com/3956ray/Faraway-Verse.git Faraway-Verse
   git clone https://github.com/3956ray/iknewit.git iknewit
   git clone https://github.com/3956ray/peiban.git peiban
   git clone https://github.com/3956ray/think.git think
   ```

3. 在 Codex 中登录要使用的 ChatGPT 账号，并打开工作区。`AGENTS.md` 中的 `/Users/orderly_ray/Projects` 是原电脑路径；若位置不同，请改为新工作区的实际路径。
4. 项目级自定义 agent 定义位于 `.codex/agents/*.toml`。原设置使用 `gpt-5.6` 和 `gpt-5.6-terra`；若新账号或客户端不支持指定模型，请修改相应的 `model` / `model_reasoning_effort`，或移除这两个字段以继承父会话设置。保留角色职责与项目边界约束。
5. 如需启用会话配置，先核对当前客户端支持的配置键，再将 `.codex/config.toml.disabled` 复制为 `.codex/config.toml`。本次只保留原配置，不代表已在新账号上验证兼容性。

官方参考：[Codex 自定义 agent 文档](https://developers.openai.com/codex/subagents)。这些文件用于 Codex 项目配置；普通 ChatGPT 网页聊天不会因克隆仓库就自动加载它们。

账号登录状态、API Key、个人全局配置、全局 skills、插件连接和历史会话不包含在本仓库中；需要时在新环境单独配置。

## 工作区边界与维护

- 开发前确定目标子项目，读取距离工作文件最近的 `AGENTS.md`，使用该项目自己的工具和命令。
- 不假设不同项目共享代码、依赖、环境变量、架构或发布流程。
- 本工作区与任何名为 `Orderly` 的项目无关，除非用户明确指定，否则不得读取或修改其内容。
- 根目录只维护索引、治理规则和模板。新项目以 `PROJECT_AGENTS_TEMPLATE.md` 为起点，删除不适用的条目。
- 新增已有独立仓库的项目时，更新上方链接，并在根 `.gitignore` 中排除对应目录。没有独立仓库的项目需先检查敏感信息及生成文件，再决定纳入版本控制。
- `.gitignore` 排除当前四个独立项目、依赖、构建缓存和常见凭证文件；提交前仍需检查差异。

## thinkV2 本地迁移候选

[交接入口](handoff/thinkV2/README.md)：保留原始88d3861及许可安全历史映射、验收制品清单、新电脑恢复与任务重绑指南。**本候选尚未公开推送，等待内部交接资料公开范围批准**；受限原件只能私有/离线迁移。
