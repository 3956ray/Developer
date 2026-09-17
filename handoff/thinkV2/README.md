# thinkV2 换电脑交接入口

**状态：用户已明确批准公开内部交接资料，正在发布许可安全部分。** 精确远端与附件回执将记录在PUBLICATION_RECEIPT.json。受限原件仍只走私有/离线迁移。

- 原始工程HEAD：`88d38616c5a5947d4ca798d4039c6368fa465624`，CP7已交付，仍待Leader验收；没有新Checkpoint。
- 许可安全历史HEAD：`6cfd50f5eb6e2a8e6df0968886c9bec9b7d1ba14`。18个历史commit均有映射；唯一树差异是删除SenseVoice词表。其余1585个Git tree条目的路径、模式和blob SHA完全相同，包括所有产品源码和工程证据。
- Developer原main：`c9ce5c9bfa319f9ec36c70b548c8cde5079ef008`，public。保留原规则、项目索引和历史；未force push、未改visibility。
- 13个正式临时验收归档全部永久保全。公开候选保存其中4109个原路径文件及一份安全历史bundle；受限SenseVoice评估APK只在私有原件中。

## 从哪里恢复

1. 阅读[新电脑恢复指南](NEW_COMPUTER.md)。
2. `public-commit-map.json`：原始commit到安全导出commit；不是原始commit哈希保持不变。
3. `source-equivalence.json`：源码等价检查与唯一排除项。
4. `public-artifact-manifest.json`：内容寻址证据清单、每文件大小/哈希和排除原因。
5. `restore_evidence.py`：验证/还原未来经批准交付的证据包；不下载、不运行其中程序。
6. `restricted-exclusions.json`：受限资产和仅保留私有部分；保持旧SenseVoice PAUSE。
7. `PRIVATE_TRANSFER.md`：完整原始历史及全部本地原件的离线拷贝位置。
8. `thread-rebinding.template.json`：新机器创建任务后填入新ID；旧thread只作历史引用。

本目录包含内部路径/历史任务元数据，用户已在获知风险后明确批准公开。该批准不覆盖受限模型分发限制。包清单与SHA在本地`LOCAL_PACKAGE_IDENTITIES.json`，该文件不含凭据。

并行更新：最终只读检查发现其他任务将远端main推进到`27746c2590d429ec4975cdd682991dc998a24c31`，本地交接已保留并合并该治理/索引更新；没有读取或改动其他项目业务代码，仍未推送本任务。
