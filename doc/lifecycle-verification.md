# V2-NOTES-LIFECYCLE-001 验证报告

RESULT=COMPLETE（本单工程交付，待指挥官审查；非完整App/家庭验收）。

- 日期：2026-09-16；checkpoint=CP3。
- task_id=V2-NOTES-LIFECYCLE-001。
- attempt_id=b74076c2-b4b3-48f9-97ab-cbf236936532。
- assigned_thread_id=01a0a5d3-8ec3-7071-82f6-a49f80ba7d1d。
- contract_sha256=99770fa3f734041a6f15111b970c15d6f2ae5ce5bd5ab7536f9c5c13f5ec5274，重算匹配。
- 基线=e2f6f4a2e91ec367dea034dff263368341212fe6。
- 项目Git根=/Users/orderly_ray/Projects/thinkV2；分支=codex/v2-notes-lifecycle。
- 最终提交与文件摘要由外部`/private/tmp/thinkv2-V2-NOTES-LIFECYCLE-001-b74076c2/result.json`绑定，避免自引用。

## 行为变化

1. schema v2增加分类实体ID/名称/版本/有效状态，笔记和草稿记录category_id及manual/auto来源。旧分类文字保留为名称快照；升级时精确映射所有旧名称，包括只出现于草稿中的名称，不裁剪历史字符串、不重建笔记ID。
2. 分类可创建、改名、重新选择；删除分类需确认，只将相关正常笔记/草稿转为未分类。正式笔记和草稿分类不同的情况下，只变更相关分类，不覆盖另一份正文/分类。改名维持ID和来源；人工选择写manual，尚无自动分类算法。
3. 单笔记软删除使用deleted_at，原正文/标题/分类及最新活动草稿保留，正常首页/搜索/草稿列表隐藏。回收站可恢复同ID；原分类无效时转未分类并明确提示。没有永久笔记删除API/UI或自动清空。
4. 分类改动、软删除和恢复推进版本并同步草稿baseRevision，拒绝迟到旧编辑。普通保存对现有笔记使用UPDATE，避免REPLACE隐含删除行。
5. 首页增加管理分类/回收站入口；编辑器改为真实分类选择器，可直接新建分类；回收站展示原分类、缺失分类提示及草稿保留信息。

## 验收与证据

| 条件 | 测试与实际证据 |
| --- | --- |
| v1笔记/草稿无损升级、重复初始化 | NoteLifecycleTest.migrationPreservesExactLegacyNotesAndDraftOnlyCategories；比对ID、正文、标题所有权、分类原字符串、时间、revision/baseRevision，重开两次映射ID不变 |
| 迁移中途失败不损坏旧库 | host及AndroidLifecycleTest注入真实SQLite UPDATE触发器失败，验证user_version仍1、旧字段/表保持、categories/新列回滚；移除触发器后可重试成功 |
| 实际覆盖安装升级 | 在新AVD先安装本项目S1固定APK+测试APK，3项旧测试生成合成v1数据；覆盖安装本单APK（未清库），原笔记和草稿全部旧字段逐列相等，版本1→2、category_id非空且来源manual。摘要见apk-upgrade.json |
| 稳定分类ID/来源与改名重开 | categoryIdsRenameAndManualProvenanceSurviveReopen，auto来源持久化、手动选择转manual，改名ID不变；UI新建及改名通过 |
| 删分类不丢笔记/另一份分类 | deletingFormalCategoryDoesNotChangeDifferentDraftCategory、deletingDraftCategoryDoesNotChangeFormalCategoryOrBody，分类更改后草稿仍可正常保存 |
| 迟到编辑不能覆盖分类变化 | renamedCategoryInvalidatesOldEditorsButKeepsDraftUsable及删除分类场景；旧revision被拒，新草稿baseRevision正确 |
| 草稿/正文分别保留、恢复同ID | softDeleteRetainsLatestDraftAndRestoreOriginalIdWithMissingCategoryNotice；实际Android平台跨仓库重建恢复；缺失草稿分类也产生提示 |
| 软删除/恢复失败事务回滚 | failedSoftDeleteAndRestoreAreAtomic、categoryMutationFailureRollsBackEverything；真实SQL触发器在中途失败，原行/分类/草稿全部保持；ViewModel显示失败并可重试，不先报成功 |
| 正常搜索一致、无永久删除 | 原搜索/分页测试继续通过；回收站记录不出现在正常搜索，恢复后重新可搜。生产repository不含DELETE FROM notes或永久删除方法 |
| UI完整路径 | NotesUiTest：新建/草稿/recreate/保存/搜索/改分类；管理分类改名；软删除取消后再次确认；删除原分类；回收站缺分类提示；同记录恢复及未编辑保存。最终Android OK(4 tests) |
| 强停/重开 | 完整UI测试后强停应用，读取仅合成数据库，再冷启动；1正式笔记、0活动草稿、0回收站，原正文/标题保留，分类为未分类；删除分类的元数据仍在。见cold-reopen.json |

## 命令与结果

无新增依赖；沿已核缓存/现有JBR、Gradle和SDK，全部离线。

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --no-daemon --console=plain
```

- 最新构建成功，12秒（部分任务UP-TO-DATE）；此前当前生产代码完整执行33项host测试，0失败/错误/跳过，JUnit XML与构建日志保留。
- 33项=原20项行为回归＋11项仓库迁移/生命周期＋2项ViewModel恢复/失败测试。旧测试仅按分类ID返回值和UPDATE故障位置适配，未删掉数据保护断言。
- 最新host 1000条合成笔记搜索P95=13.418833ms，不含Android/Compose，不推广为任意数据规模性能保证。
- lint：0 errors、12既有warnings；无新增抑制或依赖升级。
- Android instrumented：4项全过，16.071秒；平台迁移/生命周期、原平台持久化故障、模板包名、完整UI链。

本次仅新建AVD ThinkV2Lifecycle，使用现存Android37.1 arm64-v8a/16KB页镜像，1080×1920/420dpi；独立ADB5041定向127.0.0.1:5583。没有复用用户AVD磁盘或访问真机。关闭Wi-Fi并设飞行模式。

```sh
adb -P 5041 -s 127.0.0.1:5583 install -r app/build/outputs/apk/debug/app-debug.apk
adb -P 5041 -s 127.0.0.1:5583 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -P 5041 -s 127.0.0.1:5583 shell am instrument -w -r com.example.thinkv2.test/androidx.test.runner.AndroidJUnitRunner
```

全部输入为项目合成数据。升级对比完成后，才清除本轮模拟器测试数据，运行当前UI测试；未清除任何真实数据。新页面截图是实际模拟器画面并已查看，不是生成的设计稿。先前S1报告和证据保持不变，本单证据在`doc/evidence/lifecycle/`。

## 失败记录与调整

首轮5项旧测试失败来自测试API适配：分类名称筛选改为ID、正规化草稿新增ID、正式更新改用UPDATE使旧INSERT故障触发器不再命中。修正精确输入/期望与故障位置后，保留等值、事务回滚、UI失败重试断言，全部通过。指挥官指出AndroidPersistenceTest需使用persistDraft返回的正规化Editing，已按此保留完整草稿等值断言。

原始构建日志保留在外部结果目录；Git中的build.log只去除尾空格。未延长等待掩盖失败，未绕过测试。

## 固定制品

外部目录：`/private/tmp/thinkv2-V2-NOTES-LIFECYCLE-001-b74076c2/`。

- app-debug.apk：11,908,845 bytes，SHA256 `d09f317c166f1cfa96bca4c6c5d74be4c6995126750d81318926d43ea93008ab`。
- app-debug-androidTest.apk：1,093,394 bytes，SHA256 `4166ea4f5fed24084c9e89eec8341dc62726ea349f375de81658b4eb7c7927cb`。
- 同时提供生产构建路径`app/build/outputs/apk/debug/app-debug.apk`；外部副本不会因下单重建而覆盖。

## 限制与下一步

真机/小米系统、人工TalkBack、父亲试用、其他Android版本和尺寸、长时间后台与真实设备磁盘满未验证。当前回收站/分类管理用完整列表，未承诺超大库性能；基于1000条的host参考不能替代设备性能。回收站内的分类快照在分类删除时保留，到恢复时才执行缺失分类回退并提示。

提醒、备份导入导出、ASR、云AI、图谱、日历迁移和发布均不在本单。完成后停止；唯一建议：指挥官验收通过后进入新PM S3/CP7重复提醒单。
