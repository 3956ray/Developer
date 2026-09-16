# CP8 新增数据备份兼容验证

任务 `V2-BACKUP-COMPATIBILITY-001`；attempt `85ebe7d3-3c42-4907-84c0-168c13c79c2a`；冻结合同SHA256 `89c1bb14b1f3ef54516c2b1bed7f98a73e5c430bc71e4218c882bcae51845b2b`；基线 `53fb5da83c841d565f8c2f627de4d81905a1abf6`。仅E9 X1–X3 / C1–C10，不开始下一Checkpoint。

## 实现与验收映射

| 范围 | 实现与证据 |
|---|---|
| X1 / C3 | `BackupData/Repository`在一致SQLite快照导出既有字段与词表、日历原始来源/指纹/映射、已接受AI来源、关系、导入回执。真实Android空库恢复逐字段相等；仅提醒关闭及本次回执为明确差异。 |
| X2 / C2/C5 | 导出格式v2；读取v1精确旧字段并说明缺失，拒绝更高版本/未知字段/不合法引用/校验。冻结基线v1读取器真实解析代码验证拒绝v2。UTF8、深度、字节/数组上限沿用并覆盖新增字段。 |
| X2 / C7/C8 | 来源参与记录冲突比较，保留本机、完整映射副本。日历及AI来源允许明确副本保持原request/source身份，原目标不覆盖；关系去重及ID碰撞映射。回执标记“处理过，可能含跳过项”，不冒称每行恢复。 |
| X2 / C8 | schema8一次性词表迁移和所有恢复字段均在单一SQLite事务；失败回滚DDL/数据/回执。导出指纹含全部新增字段，相关本机变化使预览失效。真实Android写后异常注入覆盖5个新增写入阶段。 |
| X2 / C9 | 导入提醒disabled且不调用OS调度；本机未冲突计划保留。实际恢复按钮使内存AI关闭、同意/建议清除，词表重新加载。凭据不参与备份，也不根据来源endpoint配置服务。 |
| X3 / C1/C4/C6/C10 | 模拟器实际DocumentsUI导出/选择/确认，空库预览零写入、恢复/重复/损坏拒绝/取消/ENOSPC；真实CalendarProvider恢复后同版跳过、变更显式副本，原笔记/草稿不覆盖。 |

精确数据合同、上限、SKIP依赖、词表顺序与同词多改法、回执语义及来源映射见[格式v2](backup-format.md)。迁移失败保留旧preferences和schema7；成功后SQLite独占词表，旧preferences即使仍存在也不能在新进程复活已删词条。

## 环境、命令和边界

ThinkV2Backup专用合成AVD，Android37.1 arm64、2CPU/2GiB、1080×1920/420dpi；仅ADB5051 / serial127.0.0.1:5591。此路径已有早期备份任务的合成Downloads/故障提供者文件，**不是新清空的整台AVD**。每个本轮独立设备用例清空产品App；词表重启检查刻意不清空，两次独立instrumentation进程连续运行。实际SAF只操作本轮`cp8-final*.thinkbackup.json`测试副本，无个人账户、私人设备或真实数据。

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --no-daemon --console=plain
adb -P 5051 -s 127.0.0.1:5591 shell am instrument -w -e class com.example.thinkv2.backup.CLASS#METHOD com.example.thinkv2.test/androidx.test.runner.AndroidJUnitRunner
```

AI回归仅用已有测试信任与本机loopback18443合成TLS服务；无真实供应商请求，不改变provider_verified=false。生产manifest/依赖/ASR运行库及模型未变。CP8仅迁移词表所有权，没有语音质量或候选模型工作。

## 关键证据

- `device/backup-v2-source.json` / `empty-restored.json` / `conflict-restored.json`：真实Android全部备份数据表及提醒事件表、10正式笔记（含1回收站）、草稿/分类/提醒、9日历来源、2条AI接受来源、1条关系、2个纠错对，身份映射另存。新提醒关闭，触发日志为空。
- `device/backup-v2-rollback-comparisons.json`：写入日历、AI、关系、词表、最终回执后分别抛出异常；每次before/after摘要相等，触发器恢复，再试成功。
- `device/backup-v2-receipt-decisions.json`：SKIP处理回执随备份再恢复后依然不补导跳过项，同exportId异hash、文件携带回执与本机异hash冲突均拒绝，原库相等。
- `device/backup-v2-legacy-migration.json`及`migration-new-process.json`：迁移故障不标完成；重试成功后删除词条，新的instrumentation进程不再从旧preferences恢复。
- `device/backup-v2-old-version.json`：实际Android读取冻结v1文件；现有词表保留，未知v2字段拒绝且原库不变。
- `device/backup-v2-category-and-edge-conflicts.json`：独立分类ID映射、笔记副本和边ID碰撞，原笔记/分类/边逐行保留；SKIP分类依赖同时跳过记录和关系。
- `device/backup-v2-state.json`：实际Compose恢复按钮先拒绝过期预览，再重新预览成功；内存AI从enabled变disabled，词表合并后刷新，合成凭据未出现在导出内容。
- `device/backup-v2-calendar-changed-after-restore.json`：真实CalendarProvider来源被合成fixture修改，变更必须明确另建，现有原文和副本原样保留，再导同版跳过。
- `final-saf/`为最终APK实际系统文件流程，保留文件字节、PNG/XML及逐表快照。同步四处覆盖说明前的UI探索流程移至外部归档`pre-final-ui-exploration/`，仅补充诊断，不用于绑定最终APK结论。

各独立设备用例与SAF阶段使用各自合成种子；随机记录ID须与同阶段文件/快照配对，不能跨阶段直接相等比较。

逐表比较覆盖所有备份字段及提醒事件；`reminder_runtime`是前台调度重检的原因/时间戳，不属于备份内容，启动时正常更新，不把它当作恢复资料差异。恢复实现不写该表。

ENOSPC通过测试APK提供者返回真实Android文件描述符写入错误，不等于填满系统磁盘。App没有报告成功，并诚实提示“无法确认未完成文件已清理”；不把清理未知写成完成。SHA仅检测损坏，不认证来源，备份仍是用户主动保存的明文。

## 测试过程中的修正

首轮host有两个旧测试直接写入非SHA字符串作为来源hash，过去这些字段不导出，v2完整校验正确拒绝；夹具改为合法合成来源与真实格式hash后通过。AI延迟接受设备夹具最初用`INSERT INTO ai_acceptances`前缀暂停写入，意外拦截schema8迁移的`INSERT ... SELECT`导致初始化超时；仅把测试拦截限定为接受操作的`INSERT ... VALUES`，保留原延迟/返回保护断言，复测记录单列。没有把该失败归因为产品通过。

最终SAF脚本第一次在重复导入时未等文件选择器抽屉动画结束，点击落在遮挡后的文件列表；修正脚本等待抽屉出现/关闭后完成剩余流程。失败截图、初次日志与完成日志均保留。设备证据只取最终通过的对应阶段，不把中间失败轮算通过。

## 结果与限制

最终计数、APK SHA、回归结果和环境停止记录见本报告末尾的交付记录及外部result.json。小米15实际文件提供者、真实TalkBack/家庭恢复试用仍待最终设备阶段；语音002继续parked partial，真实AI供应商未验证。本单完成后交指挥官独立复核，不自动开始其他开发。

## 最终交付记录

- 最终产品标准构建成功34秒；增加最后一项Android冲突用例、精确限定AI测试注入后，标准命令再次成功15秒。**123 host通过，0失败/错误/跳过；lint 0错误、18警告。**
- **7项CP8设备检查通过，20项既有Android回归通过。**回归为既有文字/分类/回收站/提醒/备份8项、日历3项、语音生命周期2项、关系3项、AI状态4项；逐项记录见android-summary.json。
- 最终产品APK SHA256 `1f246d3ded802097cab0250df0cd6e252335ff3d16b91148b25840e9be2cc4fc`；最终test APK `aa91a166914b0985ceb81b4ed16c148c5e4d71df7cfb925312b218491953edde`。7项CP8及最后2项AI回归用最终test APK；此前18项回归用 `3e0d722a8d74c6a5466072da81dce60e3095ae7a165cf12d09eb01643a1b334d`，只缺最后新增CP8测试方法及AI测试拦截精化，**产品APK完全相同**。两个测试APK均归档。
- 最终SAF成功文件43770字节（实际精确值和校验以文件及manifest为准），全部新增字段往返、预览不写入、重复不新增、损坏拒绝、取消和ENOSPC诚实失败均验证。真实UI日历变更副本只新增一条，已有全部备份字段对应行不变。
- 生产Manifest二进制与基线完全相同，无新增权限/第三方依赖、模型/运行库或产品测试证书。未验证真实TalkBack、小米15或家庭体验，provider_verified=false，语音002仍暂停partial。
- 专用AVD PID93838、ADB5051 PID94042、loopback合成TLS PID99856在交付前停止；不触碰其他模拟器/设备。最终退出核对另存cleanup.json。

仓库证据：`doc/evidence/backup-v2/`。完整原始日志、两个test APK、产品APK、输入manifest、最终补丁和结果绑定在 `/private/tmp/thinkv2-V2-BACKUP-COMPATIBILITY-001-85ebe7d3/`；最终clean commit由外部result.json绑定，避免提交内自引用。只将本CP8工程交付指挥官复核，然后停止。
