# V2-NOTES-CORE-001 验证报告

RESULT=COMPLETE：当前文字核心实现与下述工程验证完成，待指挥官审查；不代表完整App、父亲试用或发布完成。

- task_id：V2-NOTES-CORE-001；checkpoint：CP3。
- attempt_id：f7dcb206-1d16-4648-a8bb-7273722413ae。
- assigned_thread_id：01a0a5d3-8ec3-7071-82f6-a49f80ba7d1d。
- contract_sha256：82e89639cc4968d9d636891a8fe236967d8937db02a0bb080a1d17b897f4ff92，已按排序紧凑JSON重算匹配。
- Git根：`/Users/orderly_ray/Projects/thinkV2`，分支`codex/v2-notes-core`；独立模板基线`16e30aa`，没有父仓库提交或旧think代码复制。
- 新PM基线原样同步为`product-baseline-decision-2026-09-16.md`，SHA256 `47716f64ffac8c2fa00974e9a1df9c5b61169955b445eca335209d9c5f639cdd`。
- 最终提交、文件摘要与APK身份由外部结果 `/private/tmp/thinkv2-V2-NOTES-CORE-001-f7dcb206/result.json` 绑定，避免报告自引用。

## 改动与逐项证据

| 验收 | 实现 | 实际证据 |
| --- | --- | --- |
| 空库/稳定ID/真实正式保存/同ID更新 | `notes/Note.kt`、`NoteRepository.kt`、`AndroidSql.kt`；平台SQLite事务，非空正文约束，未知库拒绝清除 | host重建测试及AndroidPersistenceTest实际Android SQLite重建均通过；UI真实新建/编辑完成 |
| 自动/手动标题与分类纠正 | 24 Unicode码点首句标题；MANUAL不被正文覆盖；分类自由文字，空为未分类 | host Unicode/标题所有权测试；Android重建保留人工标题/分类，UI改分类及保存通过 |
| 持久化草稿与迟到写入保护 | 草稿单表，500ms debounce，串行数据库worker，revision/baseRevision及已结束草稿版本水位 | host已提交草稿关闭重开、保存/放弃后的迟到写入拒绝、丢弃后重新编辑、改回原文、重复保存、不编辑直接SAVE/KEEP路径通过 |
| 保存失败保持旧版本与草稿 | 正式保存、搜索字段、草稿结束标记同一事务；错误留在编辑器 | host及Android SQLite真实RAISE(ABORT)触发器覆盖；host ViewModel保存失败与成功重试通过 |
| 实际搜索 | 标题/正文lowercase字段，绑定LIKE字面量、多词AND；完整标题相等→标题包含→正文→更新时间/ID；最多100条分页，128码点/8词上限 | host中文/转义通配符/多词/排序/改后索引一致/分页；UI搜索并编辑后旧词消失通过 |
| 命中片段 | 原正文命中附近120码点窗口，不破坏UTF-16代理对，查询时不截成固定前三行 | Unicode片段测试；真实UI展示，非生成摘要 |
| 可读界面与诚实入口 | 首页/编辑器，56dp主操作、18sp正文；保存状态固定，普通滚动表单保持焦点；语音禁用且明确不可用 | Android合成UI测试；浅色1.0字号、深色1.5字号首页/编辑器截图检查 |
| 丢弃确认与删除范围 | 丢弃仅清草稿，保留正式版本；确认弹窗 | host丢弃与迟到写入回归通过，UI确认实现可审查；按最新PM指令无永久删除/API，回收站留S2 |
| 私密数据边界 | allowBackup=false，云备份/设备迁移各域exclude；无网络/麦克风权限，无ASR/假波形/示例种子 | Manifest/XML及第一方源码检查；不声称验证OEM所有备份行为 |

## 执行命令和结果

工作目录均为项目Git根；使用已安装JBR与缓存，`--offline`，无依赖下载。

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --no-daemon --console=plain
```

最终构建18秒成功。20项host测试（14仓库/模型规则、5ViewModel、1模板），0失败/错误/跳过；lint 0 errors、12 warnings（模板已有的1冗余label、7无用颜色、4依赖新版本提示，不屏蔽）。最终日志和JUnit XML在`evidence/`。

host测试用新写的测试桥执行生产Kotlin SQL，对接Python标准库真实磁盘SQLite，不用伪造查询结果。1000条合成笔记/40次查询的最终host P95=10.958667ms，包含测试桥开销，不含Compose或Android端到端渲染；这是该语料/机器参考，不是所有数据规模承诺。LIKE仍可能扫描候选行，没有声称使用全文索引。

仅目标临时模拟器：独立ADB端口5039、serial `127.0.0.1:5581`。使用SDK现有Android37.1/arm64-v8a/16KB page size/rev9，1080×1920、420dpi，独立新建AVD数据目录，不复用用户数据。

```sh
adb -P 5039 -s 127.0.0.1:5581 install -r app/build/outputs/apk/debug/app-debug.apk
adb -P 5039 -s 127.0.0.1:5581 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -P 5039 -s 127.0.0.1:5581 shell am instrument -w -r com.example.thinkv2.test/androidx.test.runner.AndroidJUnitRunner
```

最终Android instrumentation：**OK (3 tests)**，7.181秒：Android SQLite重建/事务故障、新建/草稿提交/Activity recreate/正式保存/搜索/分类纠正/未编辑保存整条UI路径，加模板包名断言。

另执行最终APK强停→读取测试数据库→冷启动，确认1条正式笔记、0条活动草稿，正文/标题/分类及UI计数保留；`evidence/cold-reopen.json`保留检查摘要。没有读取真实父亲数据。浅色/深色和1.5字体截图在`evidence/`，已实际查看，不是渲染草图。

## 发现并处理的问题

- 初次Gradle沙箱缓存锁拒绝：获批缓存访问后同离线命令成功。
- 模板Espresso3.5.1与API37.1反射不兼容：仅换用核对过的现有缓存测试组件Espresso3.7.0/ext-junit1.3.0；见`build-review.md`及精确对象摘要。
- 新增测试初次“改回原文”用例没有先落盘中间草稿，修正为真实中间草稿→改回→正式保存场景，并修复KEEP保留旧草稿的边界。
- UI草稿状态离屏导致断言失败：固定状态到编辑器滚动区外；保留原断言和recreate路径。
- 随后UI表单滚动触发焦点节点回收错误：固定数量的编辑器输入项改为普通滚动Column，完整测试通过。
- 深色禁用语音文案偏暗：提高不可用文案对比度，继续保持禁用，最终APK回归通过。
- 失败日志保留于本轮外部目录，不抹去失败尝试，也不把尝试当通过。

## APK与保留物

- APK：`/Users/orderly_ray/Projects/thinkV2/app/build/outputs/apk/debug/app-debug.apk`
- 本轮固定副本：`/private/tmp/thinkv2-V2-NOTES-CORE-001-f7dcb206/app-debug.apk`
- 11,860,183 bytes；SHA256 `5b707ab363ac848acbecfdc63d6e8a450a02cfd105b3f2a4a1d18df172a77a5e`。
- 只安装至本轮新建模拟器，没有真机安装/分发/推送/发布。

## 未验证与下一步

真机/小米系统、TalkBack人工遍历、真实父亲使用、其他API版本/屏幕尺寸、自然后台长时间行为、磁盘满设备情形未验证。Activity recreate和已提交草稿DB重建有证据，但不将其等同所有进程死亡/旋转/键盘组合已通过。尚未提交的最后500ms输入可能丢失；正式保存与已提交草稿不作同样承诺。

基础分类是文字，不是稳定分类实体；无回收站、提醒、备份导入导出、ASR或日历迁移。本单结束停止。唯一建议：指挥官验收后进入新PM的S2独立CP3分类管理/回收站单，迁移现有分类文字时必须保留笔记与草稿。
