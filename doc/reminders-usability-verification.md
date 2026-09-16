# CP7 提醒界面可读性与操作验证

任务 `V2-REMINDERS-USABILITY-001`，attempt `8da8a67a-0259-4df6-9ae1-924d7c3fad37`，合同SHA256 `f691b2393ed29bec186ea9d2ee7579807e629a6a78ba575873b18e29948377cf`。已验收基线 `8a98e09d1276a17016a4e71f7f7385f6c57e402c`。范围仅CP7提醒表单、计划与触发记录；不是整个App或真实家庭验收。

## 观察与最小修改

| 观察 | 修改与证据 |
|---|---|
| 2.0字体、320dp、验证错误与IME同时存在时，固定返回/标题/错误占据大部分空间，日期框和光标手柄被裁切 | 标题、完整错误/状态说明进入滚动区；错误出现时滚到提示，固定返回保持可达。基线`before/confirmed-ime`只证明裁切与高度受限，不把拍照成功计为纠错操作通过。最终矩阵实际点击并纠正日期、时间和星期后保存 |
| 实际纠错清除错误后，错误区域收起把当前输入框推到视口上方 | 保留真实焦点并在错误消失且IME可见时请求当前输入框滚入视口；修改后使用未裁剪边界严格断言输入框完整可见。对应失败与修复前图片保留在diagnostics和外部原始attempt归档 |
| 开关原点击节点无名称，约52×48dp；文字和状态分散 | 启用开关使用至少56dp整行触摸区，合并名称、Switch角色、checked、真实焦点和动作。未硬编码focused=true |
| 输入文字/背景状态/下次计划/触发原因存在默认或显式16sp | 提升到18sp；日期、时间用简短标签和独立格式说明，完整格式保留 |
| 原noteLabel只返回标题，同标题计划/历史缺少上下文 | 仅扩展只读展示查询为标题、分类和真实摘要，操作与回调仍绑定原ID；内部稳定tag供测试，用户语义不加入UUID |
| 大字卡片长按钮最后一个字孤立换行 | 可见文字改为“修改提醒”“设置提醒”“查看笔记”；完整动作与记录上下文保留在语义标签 |

产品变更只在`ReminderScreen.kt`及`ReminderRepository.kt`的只读noteLabel查询。没有更改ReminderModel、规则解析、保存/撤销/调度引擎、通知内容、权限、schema或依赖。沿用既有accessibleButton帮助主要操作在实际平台节点上呈现完整Button角色和焦点。

## 设备方法

独立合成AVD ThinkV2Reminders，Android37.1 arm64；1080×1920、420dpi，测试覆写840×1600即320dp。系统font_scale 1.0/1.5/2.0与明暗主题组成六组；每次清空仅本AVD的App合成数据。相机/音频输入关闭，无真机/私人数据，Wi-Fi关闭、飞行模式，独立ADB5057只连接127.0.0.1:5597。关闭系统动画以稳定截图，不改产品动画。每张JSON记录实际Configuration和平台节点，截图对应真实系统IME。

每组用实际SQLite创建两条同标题、不同正文的笔记，经真实首页/笔记入口打开提醒。验证空列表、通知/设置主要按钮、启用开关、一次/每天/每周选项、日期格式错误、时间越界、周选日缺失与修正、周一/五选中/取消的实际平台checked状态。输入通过可见中心指针点击，修改前后核对边界、焦点与IME；Tab从日期实际移到时间。Activity重建、横竖屏旋转、后台恢复后未保存表单不变。

显式保存后核对真实规则WEEKLY、10:35、weekdays=17与权限拒绝时BLOCKED/requested=true。修改但返回不会写库；明确关闭后DISABLED/requested=false，复用原计划ID并递增版本；每天/仅一次可保存且无重复计划。第二同标题笔记使用不同正文上下文，通过平台ACTION_CLICK打开精确ID。52条历史是通过实际repository写入的持久化合成夹具，验证50→52分页、记录对应笔记和重新设置路线；不是52次实际通知或自然周期验收。关闭表单时释放滚动/焦点局部状态，保持原有行为；重新打开表单从顶部开始有实际断言。

Compose 1.10.4对Role.Switch的Android桥接使用`roleDescription=Switch`，不会把className改为android.widget.Switch；已核对本地官方sources.jar的AndroidComposeViewAccessibilityDelegateCompat源码。测试同时要求实际roleDescription、可检查/checked状态、有效点击及至少56dp，主要普通按钮要求android.widget.Button。第一次错误地要求Switch类名的断言失败完整保留；修正测试遵循实际平台角色映射，未去掉角色要求。平台节点属性传播需等待窗口稳定后重新取节点。

本单不重复运行上单未建立可靠路径的TalkBack注入研究。平台节点、真实键盘焦点与ACTION_CLICK不等于实际TalkBack导航/朗读，服务验证缺口仍保留。

## 构建与回归入口

使用Android Studio JBR：

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --no-daemon --console=plain
python3 doc/evidence/reminders-usability/fixtures/run-matrix.py
python3 doc/evidence/reminders-usability/fixtures/run-regressions.py
```

命令只能在已授权的专用合成AVD上运行；不要改成泛化connected测试。`ReminderUiTest`仅适配更完整的动作语义标签，原操作和断言保留。新`ReminderUsabilityTest`为真实Activity与数据库的端到端验证。

## 明确保留的缺口

自然每日/每周长周期、跨时区实机行为、小米15、家庭体验和实际TalkBack导航/发音未在本单验收。实际AlarmManager一次触发与固定时钟压缩周期必须分别记录，不能用后者推断自然周期。ASR未重新评估，Voice002保持parked partial；provider_verified=false。完成CP7报告后停止，不推进其他Checkpoint。

## 最终检查结果

- 主机127项测试：0失败、0错误、0跳过；lint 0错误、18警告；离线构建通过。
- 六组独立字体/主题矩阵全部通过；42次主要目标测量均至少56dp，36次实际可见输入检查；每组平台EditText真实键盘焦点通过。矩阵中的52条历史为合成持久化夹具。
- 7项受影响设备回归全部通过：ReminderUiTest1、AndroidReminderTest1、NotesUiTest1、AndroidLifecycleTest1、AndroidBackupTest2、BackupRestoreStateDeviceTest1。
- AndroidReminderTest实际等待AlarmManager触发一次通知（本次用例56.51秒），验证隐藏私密正文、精确笔记跳转、其他草稿保留、规则修改/撤销、回收站/恢复、失效链接和旧回调。用例内另外两个后续周期是固定时钟压缩验证，不是自然每天/每周运行。
- 21个manifest/native/assets/model ZIP项与已验收基线逐字节相同；无新增相应项。已安装APK与交付APK另行核对并记录artifact-shas.json。

最终六组及7项回归使用同一产品APK `23753ee0a9ad763aabad807d3fb1b1055cc55baa883eb29e3771932e671965cc`、同一测试APK `78f194f5b7d4c867a194a1ed1f329e01f3b35f32aba601a175ff083746ab7fa6`。归档位于`/private/tmp/thinkv2-V2-REMINDERS-USABILITY-001-8da8a67a/`，包含最终APK、基线探针、原始失败/中间通过版本、JUnit XML、报告、patch和最终commit绑定的result.json。仓库`doc/evidence/reminders-usability/manifest.json`逐文件绑定证据。

原始矩阵部分IME截图含Gboard字体更新横幅，属于环境状态；这些更小视口下输入框完整可见的断言仍通过。预先打开键盘未可靠消除横幅，12:29补跑图仍有横幅，未标作普通IME；该诊断及原组均保留。最终独立normal-ime补证在字段聚焦时识别Gboard真实OK并点击，随后核对普通键盘截图和相同纠错/持久化断言；不为环境提示修改产品或重跑整个六组矩阵。

版本绑定：六组矩阵、同版本2.0-dark重复检查与7项回归使用matrix-regression-androidTest.apk（78f194f5…）；最终普通IME补证仅增加normalIme条件化测试辅助动作，使用app-debug-androidTest.apk，精确SHA见artifact-shas.json。所有最终矩阵、回归与普通IME补证的产品APK均相同23753ee0…；最终交付测试源码包含可选辅助动作，未修改原路径断言。

普通IME最终证据：`normal-ime/reminders-2.0-dark-normal-correct-date-ime.png`与`normal-ime/reminders-2.0-dark-normal-correct-time-ime.png`，目视无更新横幅且完整字段/光标可见。result.json记录实际关闭1次横幅、原完整流程断言通过。补证测试APK SHA256 `776599dbb576ba0b743a73e11ebb8d212ba47e3c338716b466f679dadb92562e`。

## 失败保留与清理

基线第一次探针在截图完成后因两个Compose root导致dump失败，第二次已正确等待IME且取得真实基线图，均未计作完整纠错路径验收。中间开关节点角色未合并、错误要求Switch类名、平台checked传播尚未稳定的失败日志保留在diagnostics；后者增加窗口稳定等待后重新获取实际节点，并保留Compose与平台双重状态断言。纠错后输入框偏移从失败尝试的实际截图发现并单独修复，最终未裁剪边界断言通过。测试编译时的nullable/实验API声明错误及全部中间构建日志保存在外部raw-logs。

cleanup.json记录：辅助功能设置null/0，Wi-Fi关闭、飞行模式开启，ADB转发为空；本任务模拟器PID62292和ADB5057服务PID62507均已确认退出。没有修改用户真机或其他模拟器。清理后停止，等待Leader裁决。
