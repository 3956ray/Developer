# CP4 只读日历导入验证

任务 V2-CALENDAR-IMPORT-001；attempt `9b7b85da-ae29-4198-9f6e-a7e2df71784d`；合同 SHA256 `6428f6f45b8af2686011caa3813378ffbbe6ca56dd51e471da31eb1340b17223`。
基线 `c22345b1ef18335e11df15f4595879464a385f5b`。本单不恢复已暂停的语音工作；日常 Vosk 与隔离候选边界不变。

## 实现与限制

| 条款 | 行为与代码 |
|---|---|
| D1 | `CalendarImportScreen/Model`：用户触发 READ_CALENDAR，选择单个日历与有限日期后才读事件。拒绝、取消、撤销均无自动导入。产品没有 WRITE_CALENDAR、INTERNET，也没有源写入 API。 |
| D2 | `AndroidCalendarSource/CalendarData`：Instances 定位范围内事件，按 event_id 去重读取 master；另外读取移动/取消例外及其 parent。标题、描述、毫秒、起止时区、全天、重复规则、例外原时刻/状态、源提醒和身份保留为带 null 的原始 JSON。日常正文只放可读原文摘要，编辑器可打开不可编辑的来源快照。 |
| D3 | 真实新建/已有/变更/未转换/跳过计数，可逐条取消选择、选现有分类或未分类。空标题但有正文可导入，只有标题亦可；二者皆空跳过。重复、例外、提醒标为未转换；无自动本机提醒、无 AI 请求。 |
| D4 | `CalendarRepository`：账户名/类型+calendar_id+event_id 的来源键，内容 SHA256 版本映射。相似标题日期不去重。变更默认不选，只能确认新副本。notes/drafts/categories/mapping 状态与源快照均重检，本地 note+mapping 一个事务；最后一次源重检失败即回滚。 |
| D5 | 独立合成模拟器真实 Calendar Provider、Compose UI、系统权限及 SQLite；下文记录实际证据。 |
| D6 | 小米15 Provider 差异、私人日历选择与家庭试用尚未验证，留到用户最终设备阶段。 |

**原子性边界：** Android Calendar Provider 与本机 SQLite 无跨提供者事务。提交前在本地事务内再读源快照，可拒绝已观测的删除/修改/权限失效；最后一次源读取与本地 commit 之间仍有不可消除的竞争窗口。不能声称锁住了源日历。导入保存的是经确认并重检的快照，不会修改源。

**身份边界：** Provider 账户或日历重建后 ID 可改变或复用；无法凭标题时间可靠恢复身份。每批必须核对身份风险，重建来源可能再次出现“新来源”。相同来源+相同指纹已有映射时跳过，旧笔记即使编辑、留草稿或进回收站也不会覆盖或复活。

**支持上限：** 单次最多366天、100个可选日历、300个源事件、5000个展开实例、每事件100条提醒、4MiB来源内容；单事件序列化完整快照最多128KiB。此单事件上限在预览前检查，不截断原文，为Android CursorWindow保留余量：原始快照与可读原文同一映射行的重复内容仍远小于常见单行窗口限制。去重重检最多每本地相关表10000行、文字32MiB，超限明确失败并保留原数据。不可显示的日历明确拒绝，不自行修改 visible；Provider null、缺失列或查询异常不会当作空成功。

**持久化与备份：** SQLite schema4→5只新增 `calendar_imports`，保留历史数据。备份格式未修改：可导出已导入笔记正文，但不包含原始日历快照、指纹和去重映射。恢复后无法靠当前备份保证日历去重，需后续专门 CP8 兼容工作；UI 已说明。导入不增加第三方依赖。

## 合成环境与证据

专用 AVD ThinkV2Calendar，Android37.1，arm64；无个人账户，网络关闭。ADB 固定 server5050、serial127.0.0.1:5589，不枚举真机。测试夹具拥有独立 test APK UID 的 READ/WRITE_CALENDAR；fixture Provider 受 shell DUMP 权限保护。产品只查询，所有源 seed/edit/delete 都由夹具执行。每次调用有 UUID+method envelope，成功响应/结果身份不符或 stderr 非空立即失败，不能误读旧文件。

夹具包含中文多行描述、只有标题、全天、不同起止时区、开始于范围前的每日 master、移动例外、取消例外、同名同日不同 ID 两条、空内容一条及15分钟源提醒。源比较读取合成 Calendars/Events/Reminders 全列；初始化先触发一次 Instances 缓存构建，再取得比较基线，避免将 Provider 自身初始化缓存与应用写入混淆。

- `ui3`：真实 Provider/UI 测试通过，首次9条/9映射/0本地提醒；取消0写入；原始内容和分类逐字段相同。`source-before.json == source-after.json`。
- 原笔记改为手动标题并保留未保存正文草稿，夹具修改对应来源后，重新预览默认不选变更。明确新副本后10条；原 notes/drafts/mapping 全行保留；相对受控源修改后的基线，源再比较相等。
- Activity recreate 后9条 SAME；另行 force-stop/重新启动，实际 UI 为新增0、已有9。本机10笔记、1草稿、10映射保持。
- 预览后系统撤销 READ_CALENDAR 会杀死进程；重启授权提示选择 Don’t allow。源全列与本地全表快照均未变，另行成功保存合成文字，本机笔记从10增为11、日历映射仍10，证明文字保存不依赖日历权限。

证据目录 `doc/evidence/calendar/`；仅合成文字可截图/落盘。本机私人文字不进入日志、遥测或外发。

## 测试状态

Host 99项通过（原91+日历8）。日历覆盖原文/时区、标题-only、分类、重试/重开去重、相似身份与重建、改源显式副本、本机草稿/回收站全行保护、最终源删除/权限失效及第二条映射写失败全事务回滚、本机陈旧状态、最终源读取期间取消的全事务回滚、schema4迁移保留。历史v2迁移回滚测试也保留。

### 最终结果与复现

最终标准命令成功（`final-build3`，37秒）：

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --no-daemon --console=plain
```

99 host / 0失败 / 0跳过；lint 0错误、19警告（依赖更新建议、未用资源、持有Context等；保留原始报告，不声称零警告）。

真实设备定向命令如下，每个有状态用例使用清空后的**合成应用**数据；日历夹具来自独立test APK：

```sh
adb -P 5050 -s 127.0.0.1:5589 shell am instrument -w -e class com.example.thinkv2.calendar.CalendarImportDeviceTest#METHOD com.example.thinkv2.test/androidx.test.runner.AndroidJUnitRunner
```

最终大小保护版三个 METHOD 均为 `OK (1 test)`：

- `actualProviderUiFaithfulImportCancelIdempotenceAndSourceUnchanged`
- `actualSourceEditDeletionAndLocalChangeRejectStalePreview`
- `androidLargeSourceFailsClosedAndAcceptedRowsRemainReadableAfterReopen`

实际超限测试使用900000字节中文description，明确 `event_limit`，没有截断/笔记/映射写入，源快照相同；90000字节description正常导入，关闭重开 AndroidSql 后逐字段读取映射、原文、正文及再次预览均正常。该证据来自真实Android CursorWindow，不以host SQLite代替。

最终APK SHA256：`325d96965a977bff7aaaf3a51f1f960b04bc5674a0154f2f6567cad39745fcd2`。
测试APK SHA256：`5377d8ebeb49656ead0648df880a7caf2de9d07c2b6105de21290fcb4ee516e8`。
APK权限反查没有WRITE_CALENDAR/INTERNET；只有既有Vosk/JNA/Compose native libs，无SenseVoice/sherpa/ONNX候选产物。

受影响既有Android回归8项均通过：ExampleInstrumentedTest、AndroidPersistenceTest、AndroidLifecycleTest、NotesUiTest、ReminderUiTest、AndroidReminderTest各1项；AndroidBackupTest 2项。真实AlarmManager等待87.469秒通过。它们运行在已含取消修复、尚未增加单事件上限的APK `a4c18889a5ca3256c24c514e7c0b381fb08881b4ab6d8762d336618edda6771a`；之后只改日历大小拒绝边界/UI说明，三个日历设备用例与完整host/build/lint在最终APK重跑。权限弹窗/force-stop手动截图为更早的同一实现阶段，取消guard与大小guard不改变权限和重启映射逻辑，保留证据版本界限。

文字UI首次回归搜索后列表被软键盘遮住；测试现明确关闭键盘后查看结果，随后完整文字/分类/回收站流程通过，不改产品搜索逻辑。

证据索引：

- `final-size-guard/`：最终三项日历日志、最终源前后全列JSON、逐字段本地JSON及大字段摘要。
- `final-device/`：取消修复版两项日历与原有8项回归日志及合成快照。
- `ui/`：实际系统权限提示、拒绝、进程重启预览、拒绝权限后成功文字保存的PNG/XML/DB表快照。
- `apk-inspection.json`、`apk-permissions.txt`、`host-summary.json`、`lint-results.xml`、`build.log`：构建与边界证据。
- 完整外部归档：`/private/tmp/thinkv2-V2-CALENDAR-IMPORT-001-9b7b85da/`，包含未经空白整理的raw-evidence、各轮失败日志、host XML、两版APK、合同、脚本及绑定最终commit的 `result.json`。

本单完成后停止专用AVD/server5050；未触碰真机、私人日历或其他项目。建议下一步由指挥官审查CP4交付；备份来源元数据兼容与D6设备试用需另单授权。

仓库内日志仅整理行尾空格/末尾空行，原始输出保存在外部raw-evidence。

首轮测试夹具失败记录保留：Kotlin 独立 test APK 进程缺少 Intrinsics，改为 Java 无新依赖；第二轮 Runtime.exec 将重定向字符串作为参数，改 executeShellCommandRwe 并发读取 stdout/stderr。两次均在产品导入前失败，不作为产品成功证据。

API依据：[Calendar Provider](https://developer.android.com/identity/providers/calendar-provider)、[Events](https://developer.android.com/reference/android/provider/CalendarContract.Events)、[Instances](https://developer.android.com/reference/android/provider/CalendarContract.Instances)。
