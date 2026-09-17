# 私有原件离线迁移

永久目录：`/Users/orderly_ray/Projects/thinkV2-migration-private-20260917/`（权限700，不在公开Developer工作树）。

- `thinkV2-original-all.bundle`：未改写的原始完整Git历史，包含受限评估词表。
- `archives/`：13个正式验收归档，4110文件，包括原始评估APK；未因公开限制丢弃。
- `supplemental-tmp/`：正式归档之外的任务日志、脚本、静态审查对象与AVD定义。原始受限模型输入仅私有留存。
- `skills/`：karpathy-guidelines及scan-untrusted-code个人技能原件。
- `complete-original-transfer.tar.gz`：以上内容与原始refs/清单的可复制包。确切大小和SHA见LOCAL_PACKAGE_IDENTITIES.json。

仅通过用户选择的私有存储或离线介质拷贝。不要将本包或原始bundle附到public Release、Git LFS或公开Git分支。旧SenseVoice PAUSE不变，保全不授权执行/分发。

未迁移：账号/OAuth/PAT/API key、TLS私钥、全局聊天数据库、全局Codex认证/插件连接、Android debug签名私钥、SDK/Gradle缓存、可重建AVD数据磁盘。AVD配置与外部观察日志保留；测试TLS私钥重新生成。具体排除清单见temporary-state-exclusions.json。

本次没有设置私有云目的地，也没有原始私有包远端备份；旧机器删除前须实际完成拷贝并校验。Developer现有远端main仍未被本次任务更改。

同时复制最终`Developer-handoff-local.bundle`及外部`MIGRATION_RESULT.json`/`LOCAL_PACKAGE_IDENTITIES.json`。最新交接文档从Developer bundle的codex/migrate-thinkv2-20260917分支恢复；完整原件包保留原始项目与历史材料，包自己的SHA必须来自外部校验文件，不可能写入自身。
