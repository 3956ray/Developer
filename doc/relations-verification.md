# CP3 手工笔记关系

任务 `V2-EXPLICIT-RELATIONS-001`；attempt `3653a2dd-eb41-4831-9a4e-38734870da34`；冻结合同SHA256 `981a5e0733148b37aee52d293ea3f2ae614b7c23f0c1f605560b14bed4fcb701`；基线 `186204ee45b2f2664fd8073e53a63fe118e0df04`。对照PM E8 R1–R5，仅本单CP3，不开始CP8、语音或真实云服务。

## R1–R5范围核对

| 条款 | 实现与验证 |
|---|---|
| R1 | `RelationRepository`在原SQLite增加`note_relations`：UUID边ID、规范排序端点a/b、manual来源、创建时间；唯一无向pair及CHECK阻止重复/自环，插入触发器拒绝不存在/回收站端点。只存ID，不复制正文；没有分类/AI推断边。 |
| R2 | `RelationsScreen/Model`保留原默认笔记列表；编辑器保存当前草稿后进入“相关笔记”。当前有效草稿优先，否则读取正式笔记的实时标题/分类/摘要。搜索和显式上一/下一页，每页50条；按钮包含标题和完整稳定ID的无障碍标签，至少56dp触摸高度。创建/移除有确认，无图形拖动或颜色依赖。 |
| R3 | 本单不实现图谱，没有图谱入口或图谱完成声明。关系列表即实际可用入口。 |
| R4 | 查询过滤回收站端点，原边ID不删除；恢复原笔记ID后关系重现。移除只删除边。创建/移除/迁移均有事务，失败没有乐观成功。标题/分类不缓存副本。 |
| R5 | 合成Android同名精确ID跳转、删除恢复/移除、异常回滚、分页与1000笔记/5000关系规模验证；实际时间与无障碍范围在下文单列。 |

## 分页、身份与限制

每个视图在同一读事务中取得有效总数和至多50条结果，UI只持有当前页。已有关系按不可变创建时间/边ID排序，候选按笔记更新时间/笔记ID排序。搜索最多128字符、8个词，LIKE通配符按字面转义；标题和正文匹配是SQLite LIKE语义（中文可直接匹配，ASCII不区分大小写），不宣称所有Unicode大小写折叠一致。

分页令牌仅属于当前数据库连接，结合`PRAGMA data_version`（其他连接提交）与`total_changes()`（当前连接写入）。非首页继续翻页时令牌不符则丢弃旧页，提示刷新并从第一页继续；不会把变动前后的OFFSET页拼成重复/漏读结果。该保护保守覆盖数据库其他功能的写入，可能要求额外刷新。令牌不跨进程保存，重新打开从第一页开始。

相关按钮只能用于已正式保存、未进回收站的真实笔记；草稿独有记录须先保存。相同标题通过独立记录ID和正文摘要区分，打开行为只使用ID。数据库尚无永久删除功能，端点软删除不破坏关系身份。界面查询只读取当前标题/分类/100字符正文摘要；关系表不保存这些文字。

schema6→7只新增关系表、索引与插入约束触发器；旧笔记/草稿、提醒、日历来源、已接受AI来源及ID保留。备份格式未改变，仍输出空`relations`占位；界面/README明确关系尚不能随当前备份恢复，留独立CP8。

## 验证环境与命令

专用ThinkV2Relations，Android37.1 arm64，2 CPU/2GiB，1080×1920/420dpi；ADB5050，唯一serial127.0.0.1:5589。飞行模式、Wi-Fi关闭，无个人账户/私人设备，不启动AI合成或真实服务。无新增依赖、权限或语音实现变更。

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --offline :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --no-daemon --console=plain
adb -P 5050 -s 127.0.0.1:5589 shell am instrument -w -e class com.example.thinkv2.relations.RelationsDeviceTest#METHOD com.example.thinkv2.test/androidx.test.runner.AndroidJUnitRunner
```

每个有状态设备用例使用清空后的合成App数据。规模夹具直接在同一Android SQLite持久库事务内建立1000真实笔记与5000合法手工边，来源笔记度数999；没有第二份演示笔记库。

无障碍证据严格区分：平台UiAutomation的AccessibilityNodeInfo标签、焦点、点击与1.5倍系统字体测试，不等于实际TalkBack语音操作。未运行TalkBack服务朗读，Xiaomi/家庭及真实TalkBack体验仍待最终设备阶段。

## 当前测试记录

- host覆盖约束/反向重复、自环/missing/trash、软删除恢复边ID、移除不动笔记、草稿标题/分类实时读取、字面搜索、50条分页、写后异常回滚、旧移除请求不能删除重建新边、schema6迁移失败回滚与旧表逐行保留、跨连接分页失效。
- 首轮规模无障碍检查发现说明标签与点击动作分属两个平台节点；产品改为同一语义节点提供标签、Button角色与操作。测试也区分键盘输入焦点和无障碍焦点。最终焦点/点击通过，保留诊断及重试日志，不把失败轮当作通过。

## 最终结果

工程范围完成；对照冻结合同R1–R5，没有下一Checkpoint实现。

- 最终标准构建成功（33秒）；115 host / 0失败 / 0错误 / 0跳过。lint 0错误、19警告，原始报告保留。
- 最终APK的3项关系设备用例通过：同名精确ID导航、取消建边/重复拒绝、草稿标题与分类改名、目标软删隐藏/原edgeId恢复、删边不改笔记；实际Android写入后异常全回滚、UI无成功并可重试；1000/5000规模/分页变动保护/大字体/平台无障碍焦点与点击。
- 13项既有Android回归通过：文字/分类/回收站/提醒/备份8项，日历3项，语音生命周期2项。运行在产品 `02dec1fb03ad17d92e762989bb8f2625a4c22d2d7e41ccb7f60fc61edd6253d3`；之后仅把关系失败提示改为“关系操作未完成，请重试；笔记内容仍保留。”，没有功能变化。最终版本再跑标准host/build/lint与3项关系设备测试。回归不是重开语音质量或真实AI验证。
- 真实force-stop/启动检查保留1000笔记、5000边及全部本地表逐行一致，界面显示999条邻居、当前1–50。此手动截图来自失败提示文案调整前版本，持久化/分页逻辑相同；与前后快照绑定，不假称来自另一次规模种子。

### 规模与可访问性测量

最终规模用例：1000笔记、5000边，源节点999邻居；第一、二页各50条、合并100个唯一端点。翻页间分别删除边、新建边、修改标题，旧分页均明确失效，实际点刷新后从第一页继续，保持精确端点。全量合成端点表与显示的前两页ID在证据内。

10次顺序SQL页查询中位数 **1.391ms**、最大 **2.939ms**；原始微秒列表完整保存。这是已建立合成数据库后的查询，不称冷启动。通过真实编辑器ID进入关系页并等待UI稳定的一次测量为 **285ms**，不冒称UI P95或小米表现。

1.5倍系统字体下实际节点具有精确ID标签、点击与无障碍焦点动作；ACTION_ACCESSIBILITY_FOCUS成功并读取到isAccessibilityFocused=true，再ACTION_CLICK打开正确笔记ID。该节点keyboardInputFocusable=false单独记录；没有用输入焦点代替无障碍焦点。UiAutomation请求触摸探索作为测试服务，结束恢复flags及font_scale=1.0。**没有实际运行TalkBack服务朗读**；目标机TalkBack体验仍未验证。

### 制品与证据

product APK SHA256 `c777c739b8f1e78d642d440a61cad32a0e86c161fc638c1401a7e483f9aa1986`；test APK `a91e58302fa5d3bd943560c561f3ac9458388085b218278afd09dfe15f5b4916`。无新权限/第三方依赖；仅既有Vosk/JNA/Compose native库，没有候选ASR或产品测试证书。AI真实供应商仍provider_verified=false，语音002仍暂停partial。

`doc/evidence/relations/`包含设备逐项日志、原始SQL/界面测量、1000/5000数据库、平台节点诊断、重启PNG/XML与完整前后表快照、host/Android汇总、lint/构建和APK检查。仓库日志仅整理行尾空白；原始输出及APK归档在 `/private/tmp/thinkv2-V2-EXPLICIT-RELATIONS-001-3653a2dd/`，result.json绑定最终clean commit、合同和manifest。

本单停止专用ThinkV2Relations和ADB5050。建议指挥官审查本CP3工程交付，关系备份兼容留独立CP8；Xiaomi15、实际TalkBack/家庭与真实供应商条件另行验证，不自动开始下一单。

参考：[SQLite data_version](https://sqlite.org/pragma.html#pragma_data_version)、[total_changes](https://www.sqlite.org/c3ref/total_changes.html)。
