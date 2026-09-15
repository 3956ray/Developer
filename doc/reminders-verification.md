# V2-REMINDERS-001：提醒实现与验证

## 范围与绑定

- Checkpoint：CP7。仅 thinkV2；不含备份、语音、日历、AI 或图谱。
- 基线：`e363ea92615c8b0594388e9a9992e0782ab545aa`。
- attempt：`6cbedbcd-fe1e-4ed7-8e89-f2ed4c27fae3`。
- 合同 SHA256：`040ae8f22272a04ebacb89597b0cbf504f925d4660657a1055d88622713b4874`。
- 完成条件：B1–B10 实现、必要主机/模拟器验证、状态与缺口明确、APK 和最终提交绑定后交指挥官审核。
- 最终提交、APK 摘要与证据文件摘要由外部 `/private/tmp/thinkv2-V2-REMINDERS-001-6cbedbcd/result.json` 绑定，避免提交自引用。

## 行为与实现

`ReminderRule` 计算一次/每天/每周多个选日的当地墙上时钟实例，不复制笔记。DST 缺失时刻选之后第一个有效分钟；重叠时刻选首次偏移。持久化游标阻止回拨重发。

SQLite v3 新增计划、发生记录和对账元数据，v2 原笔记、草稿、分类不改写。每条真实笔记只有一个 reminder ID，人工改规则增加 revision。软删除在同事务内关闭提醒并增加 revision；随后撤销 OS 闹钟/通知。恢复仅保留关闭的配置，界面提醒需人工重新启用。

`ReminderEngine` 分离保存意图、系统接受调度与通知调用结果。SQLite 和 AlarmManager 并非一个事务：失败保留 ERROR/PENDING/CANCEL_PENDING，通过冷启动、系统恢复 Job 或用户重试对账。所有笔记写入和回调共用应用级串行 worker，避免软删除与 post 并发。

先持久化 RESERVED 及实例游标，再调用 notify，最后记 POSTED 或 POST_FAILED。进程若在此窗口中断则恢复为 UNKNOWN，不自动补发同实例；重复计划继续下一周期。单次收尾读取已落盘事件：POSTED 保持 COMPLETED，UNKNOWN 不冒称 MISSED。POSTED 只表示系统调用返回，始终不声称用户已读。

真正进程内第一次 Activity 启动做 COLD 对账；Activity 重建不会重复执行。冷启动发现过去未处理的实例保守跳过并合并记录；原有效迟到闹钟可提交一次，若已跨越多个重复周期则合并跳过。汇总文案“有提醒可能未送达”，记录可查看对应笔记和重设。汇总通知也先 claim，避免崩溃后重复汇总。

Android 使用 `setAndAllowWhileIdle(RTC_WAKEUP, ...)` 非精确闹钟，无精确权限。通知内容固定通用文案，私密标题和正文不传入通知；锁屏 publicVersion 同样通用。不同有效周期可再次提醒，重复实例由数据库去重。系统仍可限流/延迟。API 行为依据 [Android 官方闹钟说明](https://developer.android.com/develop/background-work/services/alarms)。

## B1–B10 验收矩阵

| 项 | 实现及证据 | 边界 |
|---|---|---|
| B1 | 每笔记唯一计划/稳定 ID、新 revision；once/daily/weekly；周多选非空验证，规则及表单测试 | 每笔记不支持多个时刻 |
| B2 | 通用 PRIVATE/public 通知；singleTask 复用现有 Activity；真实 ID 深链，保留其他草稿；不存在/回收站准确提示 | 无真实锁屏解锁/父亲使用证据 |
| B3 | 改规则撤销旧闹钟和当前通知；关闭/软删除使旧 revision 无效；正文编辑不改计划；查看不关闭重复计划 | 系统声音/振动不等于 notify 成功 |
| B4 | UTC/DST/时区/回拨固定时钟测试；每个日期时刻+revision 去重 | 自然 DST 日设备到达未观测 |
| B5 | 平台非精确闹钟、界面延迟说明，无精确权限 | 不承诺准点 |
| B6 | Boot/time/timezone/package receiver → JobScheduler；COLD 门控；失败重试、重复广播无双发 | 系统强停需用户再次打开 |
| B7 | MISSED/UNKNOWN/POSTED/POST_FAILED 独立，单次可重设；多周期汇总，不逐条补发；三种崩溃窗口测试 | 不声称已读或崩溃窗口确定未送达 |
| B8 | 权限/频道检查，BLOCKED 保留规则，显式通知权限/设置入口，恢复对账；电池后台状态可见 | 不自动改电池设置；OEM 待小米 15 |
| B9 | 软删除事务关闭、OS 撤销，恢复仍关闭并提示；失败撤销重试与旧回调测试 | 备份格式由后续独立任务处理 |
| B10 | 主机固定时钟 + 独立模拟器实际闹钟/通知/界面/恢复证据分开 | 自然每日连续两次、自然每周、小米 15、家庭使用均未验证 |

## 测试环境与命令

既有 JBR、离线 Gradle 依赖，不新增依赖、不联网下载。命令：

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --no-daemon --console=plain
```

专用 AVD `ThinkV2Reminders`，Android 37.1 / arm64-v8a / 16KB 镜像，1080×1920、420dpi，磁盘位于 `/private/tmp/thinkv2-reminders-emulator`。ADB server 5043，只定向 `127.0.0.1:5585`。没有操作真机。模拟器禁用音频、摄像头，DNS 127.0.0.1。

Android 用 `adb -P 5043 -s 127.0.0.1:5585 shell am instrument -w -r -e class <类> com.example.thinkv2.test/androidx.test.runner.AndroidJUnitRunner`。依次运行原4类、ReminderUiTest、AndroidReminderTest；每组前仅清空本单合成模拟器的 app 数据，避免原有空库假设受新测试影响。

主机测试：原33 + 新19 = 52。新用例覆盖时区、DST gap/overlap、每周多选、权限、调度异常、撤销异常、软删除/恢复、时钟回拨、迟到回调、三种崩溃窗口、汇总、正文无影响及 v2 迁移失败回滚。

## 实际结果与证据

- 完整离线构建、52 主机测试、lint 通过；lint 0 error，保留模板依赖版本/资源等既有警告。
- 最终 Android 验证共6项：原4项回归、ReminderUiTest、AndroidReminderTest。每组独立合成数据。
- `AndroidReminderTest` 最终 72.602s 通过，真实一次性系统闹钟发生于本地 2026-09-16 02:12，ledger 计划 epoch `1789495920000`、通知 ACK `1789495946446`，延迟约26.4秒。该单次由系统回调，未人工调用 fire。
- 同一 test 后续两次 DAILY 使用固定时钟推进、真实 NotificationManager 提交；第一条未清除，第二次 postTime 增长且无 ONLY_ALERT_ONCE。此证据是通知提交/更新，不是自然每日送达或实际听到声音。
- `after-boot.json` 为实际模拟器重启后自动 BOOT/SCHEDULED；无手动强制运行 Job。
- `after-timezone-new-york.json` 与 `after-timezone-taipei.json` 都有 TIMEZONE_CHANGED，保持同ID、revision6、2026-09-23当地09:00，UTC分别13:00与01:00，相差12h。
- `after-update.json` 为同APK覆盖安装后 PACKAGE_REPLACED/SCHEDULED；`after-time-change.json` 为隔离时钟临时前移后的 TIME_CHANGED/SCHEDULED。
- `after-cold-link.json` 采到 COLD/PENDING；后续 `after-cold-queued-boot.json` 和 `after-isolated-cold.json` 为 BOOT/SCHEDULED（系统恢复任务随后覆盖最近原因）。`os-alarm-excerpt.txt` 确认同计划的待发 OS 闹钟。设备证据证明启动后恢复收敛，不宣称这一过程中只有 COLD 执行；独立 COLD 算法由主机用例覆盖。
- `cold-link.xml/png`、`background-link.xml` 证实真实目标正文；`back-after-link.xml` 证实返回首页而非叠加编辑页。后台/冷启动额外场景通过系统启动实际深链，测试内通知场景通过真实 notification.contentIntent.send。
- `reminder-list.png/xml` 与 `reminder-form.png/xml` 已按实际页面标题核验并目视检查。

APK（外部结果目录）：

| 文件 | 字节 | SHA256 |
|---|---:|---|
| app-debug.apk | 11988561 | `7fcc4dec617095ddcc24aa790da02f374981c13c8681e7f2c2290e9f51bd014f` |
| app-debug-androidTest.apk | 1093394 | `120fb2cea1f5e25720771bb909eee49c0235a644374d6a9562256c577107cada` |
| app-debug-androidTest-os-verified.apk | 1093394 | `088dd31e1cbbf1695c6d6b592650c12516564ebef661b9b299018eb6fba5612c` |

## 测试中发现并修正

- 指挥官审查发现单次 post ACK 后、收尾前崩溃误标 MISSED；按事件结果恢复并补测试。
- 指挥官审查发现 `onlyAlertOnce` 可能抑制下一周期，普通提醒改为 false。未清第一条通知时验证第二次系统 postTime 更新及标志，不据此宣称响铃/震动已验证。
- Activity 重建与进程冷启动分开；重建时不跳过仍有效迟到回调。
- 前两次真实闹钟实验已触发，但 ActivityScenario 重建失败。最初推测另开 Activity；系统日志 result code=3 证实投递原顶部 Activity，纠正归因：setIntent 后 Scenario 按旧 Intent 过滤生命周期，漏收 RESUMED。测试在实际重建前恢复 harness 启动 Intent 身份，实际重建与内容断言仍保留。应用明确 singleTask + NEW_TASK/CLEAR_TOP/SINGLE_TOP 单任务语义；不把该改动冒称为已证实的双 Activity 修复。

原 NotesUiTest 最终回归曾在第二次分类创建时，点击后未等弹窗出现就找“新建分类”而失败。保留 android-regression-sync-failure.log；仅测试增加关闭键盘、等待滚动空闲与等待目标弹窗，不修改产品或移除断言。新编译测试 APK 用于最终4项回归，18.35s 全部通过；此前实际提醒系统测试使用的测试 APK 另存 `app-debug-androidTest-os-verified.apk`（SHA256 `088dd31e1cbbf1695c6d6b592650c12516564ebef661b9b299018eb6fba5612c`）。两份测试 APK 的产品代码和提醒测试源码相同，只分类回归同步不同。

## 环境与采样限制

初次模拟器在恢复验证中退出134；保留 emulator-first-exit134.log，以同一合成磁盘重启并使用 swiftshader、禁用 Vulkan 后完成恢复验证。未修改产品来绕过该环境故障。

早期 `after-timezone.json` 仍为 BOOT，且原时区本已是台北，不能作为时区变化通过证据；后续真正纽约↔台北切换快照才是验证依据。冷启动采样曾只等待 last_reason=COLD，因后续合法 BOOT 覆盖而超时，保留完整时间线；不把单一最近原因当独占执行证据。早期提醒列表截图误在编辑页采集，已在真实页面标题出现后重新采集和核对。

首次两次系统测试失败日志及 ActivityTaskManager 日志保留在外部结果目录。中间构建未保存独立 APK 摘要，只作为故障诊断历史；最终候选应用/测试 APK 摘要如上，最终通过日志和提交绑定。

## 待最终用户验证

自然每日连续两次、自然每周选日、长期电池/Doze/OEM 行为、响铃与振动的实际感知、真实锁屏解锁、小米 15 及父亲使用均未验证。压缩时钟两周期和固定时钟 DST 算法不替代这些证据。工程完成后交用户统一小米 15 验收。

验证结束后关闭本单模拟器和独立 ADB 5043；所有系统操作只发生在隔离合成环境。
