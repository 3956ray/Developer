# V2-BACKUP-RESTORE-001 / CP8

## 绑定与完成条件

- 目标项目：thinkV2；基线 `1dde8ec7bd34caac991097c9f3962958cc753fab`。
- attempt：`a7044d94-1422-4102-9994-0c6c80dcee68`。
- 合同 SHA256：`99200bf59c911bd93230a783437bac15a54e8de0915f5eda011fb548404f2dc0`。
- 本单只实现主动备份和合并恢复，不做整库覆盖、ASR、日历、AI、关系图谱或发布。
- 完成条件：C1–C10实现、严格格式与事务测试、实际SAF选择/读写/取消/失败证据、固定提交和APK摘要后交指挥官。
- 最终提交和文件摘要由外部 `/private/tmp/thinkv2-V2-BACKUP-RESTORE-001-a7044d94/result.json` 绑定。

## 实现

格式规范见 [backup-format.md](backup-format.md)。SQLite v4只新增导入回执和冲突副本来源表。快照包含正式记录、草稿（含独有草稿及inactive水位）、分类墓碑、回收站、标题所有权、时间/版本和提醒配置；不含OS句柄、音频、日志、密钥或路径。关系尚未实现，必须为空。

`StrictJson`在输入流上限制64MiB文件、32MiB解码payload、层级/节点数量；严格UTF-8、ASCII十六进制转义、类型和唯一键。`BackupCodec`对解码原始字节计算SHA，验证全部引用和规则后才产生候选。

`BackupRepository`预览只读。比较整个记录组合和分类映射；确认时在同事务重核本机指纹。默认保留本机并创建有明确标签的新ID副本，草稿/提醒引用随映射重写。也可选择跳过全部冲突项和依赖。相同exportId/hash再次导入不重复，不同hash复用ID拒绝；UI明确确认后不会补入本次跳过项。导入规则一律disabled，导入路径不调用系统调度。

`BackupWriter`只接收本次系统CreateDocument新建目标。写入前核实实际“未完成-”前缀，必要时先改名；写入关闭、读回校验完成后才改正式名，并核实已去掉全部开头的未完成标记。支持提供者给正式名添加唯一编号。清理只有在当前句柄可确认、删除返回成功且再次查询确认消失时才声称成功；否则明确说无法确认。

`BackupScreen`展示明文/云盘提示、预览计数、冲突行为和确认。文件失败输出不当作成功备份，导出错误不显示无效的“重新预览”入口。仅inactive且没有正式记录的内部水位保留，但没有“查看恢复记录”入口；冲突副本在首页、编辑器和回收站标注，不篡改原始标题所有权。

## C1–C10 对照

| 条目 | 实现与验证方向 |
|---|---|
| C1 | CreateDocument / OpenDocument；显式用户操作，本机目录建议，无后台上传 |
| C2 | UTF-8 JSON封套、版本1、原始payload长度/SHA、严格base64 |
| C3 | 一致快照与空库逐字段恢复，包含独有草稿、墓碑、inactive水位和提醒规则 |
| C4 | 明文提示；实际名称未完成标记、close与读回检查、成功后正式命名，失败清理可确认性 |
| C5 | 文件/解码/数量/层级上限、类型/必填/ID/引用/规则/未知字段校验，全份拒绝 |
| C6 | 只读预览，展示新增/相同/冲突/回收站/草稿/分类/提醒数量 |
| C7 | 整个bundle冲突判断，新ID一致映射，保留本机，分类不按同名合并，显式副本标签 |
| C8 | 单事务apply，SQL故障回滚；相关本机状态变化要求重新预览；exportId/hash幂等 |
| C9 | 导入提醒disabled且不调度；现有本机提醒不改；每条可查看记录提供后续入口 |
| C10 | 主机故障用例、平台SQLite逐字段验证、实际本机SAF和测试专用ENOSPC提供者 |

## 构建与测试

使用既有JBR、平台API与已缓存依赖，无新增依赖或下载：

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --no-daemon --console=plain
```

主机最终79项：原52、codec10、merge10、writer7。覆盖严格格式、Unicode、损坏SHA/截断、未知版本/字段、重复ID、悬空引用、上限、正文/草稿/规则冲突、分类映射、预览变化、幂等、事务故障、改名/写入/关闭/读回失败和不能证明清理的句柄。

模拟器 `ThinkV2Backup`：独立目录 `/private/tmp/thinkv2-backup-emulator`；Android37.1 arm64-v8a/16KB，1080×1920/420dpi，swiftshader、禁Vulkan/音频/摄像头，DNS127.0.0.1。ADB5045只定向127.0.0.1:5587；不枚举或操作真机。

最终离线构建成功；主机79项零失败，lint零error、14warning（既有依赖升级/资源/上下文等提示及一个冗余标签提示）。产品APK SHA256：`d718a08ce4531368988cbc7ae4bcdf7fc5fae94eb471682ed165e80a355cb554`；测试APK SHA256：`0af2d74c770557e2014062e0d1f9c3b6bb247a5f2b9fc6dc0aa0265de3c24fb2`。最终回归统一使用这两个APK。

Android仅用明确类过滤的instrumentation；各旧用例的空库前提通过清空本单合成app数据保持。`AndroidBackupTest`生成硬编码合成正文/草稿/分类/回收站，不来自私人资料。SAF流程数据快照和截图均为此夹具。

最终Android共8项通过：基础/持久化/分类/笔记UI4项（15.429s），提醒UI1项（5.264s），真实AlarmManager/通知/跳转/压缩周期1项（42.660s），平台备份与文件名探针2项（0.317s）。日志归档为外部结果目录的`android-*.log`。提醒自然天数、OEM行为和本单未重跑的开机恢复不扩充声明，历史提醒证据仍按原报告界限理解。

## 实际 SAF 闭环

原始证据位于 `doc/evidence/backup/`；所有文字来自 `AndroidBackupTest.platformSnapshotRestoreAndSyntheticUiFixture` 硬编码合成夹具。普通导入导出经系统选择器授权，未使用探针的shell权限。

| 操作 | 证据 | 结果 |
|---|---|---|
| 取消导出/导入 | `export-cancelled`、`import-cancelled` PNG/XML与前后JSON | 源库逐表不变 |
| 用户去掉默认未完成前缀后导出 | `prefix-user-name`、`prefix-export-result` | 实际生成 `backup-valid.thinkbackup.json`，3241字节，payload2240字节，独立解码/SHA一致 |
| 保留默认文件名导出 | `default-export-picker`、`default-export-result` | 实际生成 `thinkV2-2026-09-15.thinkbackup.json`，与上一文件payload一致、exportId不同 |
| 故障提供者写入 | `enospc-picker`、`enospc-result`、`enospc-events.xml` | 固定计数rename=1/open=1/ENOSPC=1；写入失败提示，源库不变 |
| 损坏SHA导入 | `invalid-import-result` | 全份拒绝，源库不变 |
| 同内容只读预览后取消 | `source-preview`、`after-preview-cancel.json` | 相同3条可查看记录，未写入 |
| 空库预览和提交 | `empty-restore-preview`、`empty-restore-result`、`restored-empty.json` | 2正式记录、4草稿行、2分类逐字段一致；1提醒只保留规则并DISABLED；UI显示3条可查看记录 |
| 重复文件 | `repeat-preview`、`repeat-unchanged.json` | 已导入提示、确认禁用，数据库不变 |
| 第二份导出冲突 | `conflict-preview`、`skip-preview`、`conflict-restore-result` | 原始enabled配置与本机恢复后disabled不同，整个bundle冲突；跳过策略只读预览1；切回COPY提交1副本 |
| 副本与再导入 | `restored-home`、`restored-conflict.json`、`conflict-repeat-preview` | 新ID的正式/草稿/规则对应，原本机行全部保留、origin1、提醒均关闭；再导入无新增 |

只含inactive水位的`backup-discarded`确实保留在SQLite，但不列为可查看记录。源库含一个回收站记录及其草稿、一个独有草稿、分类墓碑和未保存的手动标题草稿。空库恢复未用“忽略时间/版本”的弱比较：notes、drafts、categories原始SQL行完全一致。

`python3 doc/evidence/backup/verify-evidence.py`独立验证payload字节/SHA、前后快照、提醒配置、冲突映射、回执和实际ENOSPC计数；结果见`verification.txt`。页面截图已目视检查导出成功、错误、预览及恢复结果。列表可滚动；没有将设备截图当作TalkBack验收。

ENOSPC本次`space-fixed`文件在测试provider元数据中已不存在；旧错误夹具生成的`space-case`仍留有记录并在证据中明确区分。产品在delete后权限被系统撤销，无法再次查询确认删除，因此保守提示“无法确认清理”，没有错误地声称清理成功。

## 调试历史与证据界限

- 一次构建请求因自动审批服务连接失败被拒绝，未执行；核对项目规则与范围后同一离线命令重试成功。
- 首版测试文件提供者独立test APK进程缺少kotlin.Pair，queryRoots崩溃。改成平台Java夹具；未给产品添加依赖或放宽解析。失败截图/日志在外部结果目录，不能当作成功导出证据。
- 原`.partial`后缀方案在Downloads失败。实际探针先只发现rename返回URI查询空游标；后续children元数据确认实际`.partial.json`文件与返回标识不一致。改为保持JSON扩展名的“未完成-”前缀，并强化清理确认。早期删除返回true不能证明文件真的清理，保留记录，不沿用早期成功清理文案作证明。
- 文件名探针只创建合成空文件，用测试权限观察提供者元数据并清理，不替代产品的正常SAF用户授权读写验证。
- 测试provider重命名最初返回相同非null ID，违反平台合同，框架随后撤销该URI权限，故首次停在名称阶段。按本地SDK `DocumentsProvider.java` 282–283、1253–1268改为ID未变返回null，仅修测试夹具。旧失败保存在`rename-grant-failure-*`，不能算ENOSPC；修复后的固定事件计数才证明进入写入。
- ENOSPC使用仅test APK中的真实DocumentsProvider及ProxyFileDescriptorCallback注入，不伪称真的填满设备磁盘。

- 最终提醒UI回归一次因模拟器宿主进程退出134、ADB offline而中断，没有测试结论；保留外部`interrupted-reminder-ui.log`。重启同一隔离AVD后补跑，不能将中断算作通过。

## 未验证

小米15、父亲/家庭实际使用、真实整盘耗尽、非本机云提供者的行为、不同Android/OEM版本及人工TalkBack验收仍未验证。文件是明文，base64不加密；SHA不能认证备份来源。后续关系/加密/整库覆盖不是本单能力。
