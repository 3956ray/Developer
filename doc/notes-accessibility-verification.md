# CP3 笔记可读性与无障碍验证

任务 `V2-NOTES-ACCESSIBILITY-001`；attempt `288f5fe5-ba80-4381-be49-c7abf3c328c7`；合同SHA256 `bcc6df204fefd1d191a7be21cc097af4a59b43b1b967ffb6c8c6cc0c4678427f`；已验收基线 `29482c49d17fc9c6470c0ea964419288b8931f67`。仅笔记UI细化，不是语音质量、小米真机或家庭验收。

## 观察与限定修改

| 实际问题 | 修改 | 证据 |
|---|---|---|
| 2.0字体、320dp窄屏首页固定Column挤掉列表；IME弹出后搜索框不见 | 首页整体LazyColumn滚动；标题/新增独立全宽行，搜索提到次级入口之前；保留全部入口 | before/home-font2-narrow与home-font2-narrow-ime；最终六组home/search截图与实际ID打开 |
| 横排按钮在窄屏大字下逐字换行；分类/回收站固定说明占据列表空间 | FlowRow按可用空间换行；分类/回收站共用单一可滚动列表 | 六组categories/trash及确认界面 |
| 关系卡片和朗读暴露完整UUID，同名记录缺少有用上下文 | 标题、分类、真实摘要用于展示/朗读；稳定ID仅用于回调和测试tag；确认信息可滚动 | baseline-relations截图、同名候选实际打开与DB断言 |
| 实际平台测到保存按钮仅32dp可访问高度，但视觉56dp；滚动子节点溢出并覆盖顶栏 | 编辑滚动区显式裁切，顶栏置于滚动内容上层；主要按钮合并语义并明确Button角色 | diagnostics中的small-target截图与节点；最终platformChecks严格尺寸/角色断言 |
| 2.0窄屏IME中保存被分成两行 | 保存与返回等宽，返回可见文字简化而语义保留草稿说明 | 最终editor-ime与横屏截图 |
| 部分关系确认按钮原默认高度低于56dp | 明确min56dp，确认说明可滚动；分类删除标题简短、分类名置于可滚动说明 | 最终确认截图与平台bounds |

仅修改NotesScreen、LifecycleScreen、RelationsScreen的布局/语义，以及共用AccessibleButton语义修饰符；不修改NoteRepository/NotesModel或任何schema、备份格式、依赖、权限、业务操作和同意规则。原标题/摘要预览仍可省略显示，完整文本在编辑器可读；UUID不作为读屏主标签。同名操作始终绑定原稳定ID。

## 验证方法与边界

本attempt新建专用ThinkV2Accessibility模拟器，Android37.1 arm64，主机麦克风和相机禁用，Wi-Fi关闭/飞行模式，ADB5056仅定向127.0.0.1:5595；无私人资料或真机。窄屏为840×1600px、420dpi，逻辑宽320dp。矩阵逐项使用系统font_scale1.0/1.5/2.0及系统明暗主题；截图/JSON保存实际Configuration，编辑流程还实际请求横竖屏旋转与Activity重建/后台恢复。

测试先检验真实空态，再创建三个有稳定ID的同名合成笔记。实际UI滚动到卡片、打开、改正文/手动标题/分类，IME可见截图；旋转/后台/返回后对真实SQLite草稿断言。搜索匹配另一ID后打开；新建/保存；分类新增/删除/改名与删除取消；关系建立确认、平台焦点/点击精确打开；移入回收站确认、恢复同ID与草稿；放弃取消与最终保存。保存后的正文/标题/分类和关系逐字段断言。每组有独立数据库快照，不以截图替代操作。

平台UiAutomation触摸探索、焦点及ACTION_CLICK与实际TalkBack服务验证分别记录；节点测试不能证明朗读语音正确。按钮边界来自平台节点，重要按钮检查宽/高至少56dp（1dp像素取整容差）及Button角色。截图先等待Compose与平台窗口稳定；窗口关闭后的缓存读取需重新获取，未降低断言。

实际TalkBack仅用系统映像已预装17.0.0.889642762服务，未下载/安装。实际绑定与绿色焦点有证据，但UiAutomation内外的有界触摸/键盘注入未可靠完成应用导航；模拟器硬件事件也未建立成功路线。服务导航及其弹窗朗读焦点恢复**未验证**，不能据此推断是App还是注入路径限制。失败源码保存在fixtures/TalkBackServiceProbe.kt，未保留为默认自动改系统设置的测试；原始失败日志另存。此前service设置（null/0）已恢复。语音输出未录音，发音未验证。

键盘探针另外检查主要按钮实际FocusState和平台focused节点，不硬设focused=true。主要按钮的语义合并保留真实onFocusChanged、FocusRequester的返回值和enabled控制的可聚焦资格；记录Android初始焦点可能恢复到搜索框的情况，验证时明确请求“新增文字”作为起点再发送Tab。平台root.findFocus可能返回非focused根View，保留原值并递归核对实际虚拟节点的focused属性。

技术依据仅使用[Android焦点行为文档](https://developer.android.com/develop/ui/compose/touch-input/focus/change-focus-behavior)和[TalkBack官方键盘映射](https://support.google.com/accessibility/android/answer/6110948?hl=en-GB)，不把文档当作本机通过证据。

## 未验证条件

小米15触摸/键盘/厂商无障碍、真实家庭操作与真实语音发音/误触率仍待验证；通用ASR质量未验收，Voice002保持parked partial，provider_verified=false。此次不新增功能，不推进其他Checkpoint。

## 最终结果与版本绑定

- 主机单元测试127项：0失败、0错误、0跳过；最终lint为0错误、18警告。离线assembleDebug/assembleDebugAndroidTest/testDebugUnitTest/lintDebug通过；后续只改测试与报告，产品APK未变。
- 本次设备验证为3个方法的8次执行：六组字体×主题矩阵、一次真实键盘焦点及弹窗取消恢复、一次普通IME正文末尾补证。六组共66次平台主要按钮尺寸/角色检查通过。
- 既有回归20项及真实Vosk UI回归2项通过。前者覆盖笔记、持久化、生命周期、分类/回收站、关系、提醒、日历导入、备份和语音状态/权限；后者使用RAM内合成语音，不构成一般ASR质量评估。
- 普通IME补证：先前测试向下滑回标题，不能证明末尾可操作，完整保留在diagnostics/normal-ime-wrong-direction。修正探针使用未裁剪正文边界定位尾部，实际指针点击将光标置于287/287，续写后297/297；正文底部899px、IME顶部983px，最终截图可见尾文和光标手柄。返回后SQLite正文完全一致。最终运行键盘提示已关闭，未再次出现；不声称此运行重新关闭了提示。正文靠显式滚动到尾部验证，不声称键盘自动滚动。
- 实际TalkBack有界探针未通过导航验证；不计入上述通过项。只确认服务绑定/绿色焦点与设置恢复，平台语义及键盘通过不能替代TalkBack验收。

所有APK保存在外部attempt归档；精确SHA见evidence中的artifact-shas.json：

| 测试APK | 绑定证据 |
|---|---|
| matrix-and-keyboard-androidTest.apk，8c2cada6… | 六组矩阵、keyboard-final；状态5项、命令权限/生命周期2项、Example/Persistence/Lifecycle各1项 |
| regression-androidTest.apk，273f7d52… | Reminder、Reminder DB、Backup2、Calendar、Relations、Backup restore、普通语音生命周期2；真实Vosk UI2项 |
| app-debug-androidTest.apk，13305d5c… | 最终normal-ime与重新绑定的NotesUiTest |

三个测试APK对应相同产品APK44668032…，已核对模拟器实际安装APK相同。矩阵之后只适配既有测试的LazyColumn滚动查找与关系内部稳定tag，新增末尾补证；业务源码未改变。apk-inspection.json确认19个manifest/native/model ZIP项与已验收基线逐字节一致，无新增权限/依赖/模型。

报告附件根目录为`doc/evidence/notes-accessibility/`：matrix按六组保存截图、平台节点、配置和DB；keyboard-final保存真实焦点证据；normal-ime保存尾部前后与持久化结果；regression及voice-regression保存通过日志；talkback-manual/services保存服务缺口；diagnostics保留失败探针。manifest.json逐文件绑定证据。外部归档另保留原始日志、APK、补丁与最终commit，不将失败尝试覆盖成成功。

验证命令：使用Android Studio JBR，`./gradlew --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --no-daemon --console=plain`；设备运行入口与配置见fixtures的专用ADB5056脚本，逐方法am instrument。NotesImeTest须font_scale=2.0、light、wm size=840x1600，直接运行该类；合成数据运行前清空仅本模拟器App。

达到代码与设备补证交付点后停止。实际TalkBack导航、小米15及家庭验收仍需后续授权场景；不推进下一Checkpoint。
