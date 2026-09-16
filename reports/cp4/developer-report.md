# GYM-CP4-SCHEDULE-001 / CP4

RESULT: COMPLETE

当前CP及清理返工已完成，等待指挥者ACCEPTED；未进入CP5。首次提交582488d未获接受：Leader最终测试9/10、ENOTEMPTY及runner挂起。失败原文、根因与修复验证见revision/teardown-report.md，不能以原84项本地通过覆盖该失败。

## 基线与精确源码

唯一项目 `/Users/orderly_ray/Projects/gym-miniapp`；起始clean，已验收基线 `22369e6320a64b96949acc31f195461134bba52d`。最终本地提交hash与提交后clean状态在交接消息提供，避免提交自引用。`tested-source.json`在最终测试前固定全部源码/配置/文档，交付前重新验SHA；`manifest.json`覆盖交付文件，排除自身及忽略的runtime/Git。

冻结PRD、矩阵、Checkpoint及旧CP0–CP3报告不修改，最终check重新校验三份产品hash：PRD `7d9948f302f13119120c03043902fba7455437764eb57d4923efebda4254e1bd`；矩阵 `89cded4a468deaa20e6090d19b35dc31c77b6a01e3e45081d3d996484188ae71`；Checkpoint `13060548afb17731e033364817f0e7ee6b0569c334bbfc4138e308ee64678882`。

## 文件与行为

| 文件 | 目的 |
|---|---|
| server/migrations/005-schedule.sql | 单草稿、单当前head、不可变快照和清理进度；不新增个人关联 |
| server/schedules.mjs | 完整草稿、课程修订、发布双CAS/取消/改期/撤回、公共范围/摘要与角色/事务边界 |
| server/schedule-time.mjs | 日历覆盖1–14天、半开区间、IANA+明确offset回算、跨日/周/DST |
| server/schedule-cleanup.mjs、scripts/schedule-cleanup.mjs | 30d非当前历史、100行批次、失败/恢复、run/status |
| server/database.mjs | 当前published时禁止配置时区漂移 |
| server/identity.mjs、http.mjs、main.mjs、observations.mjs | 课表领域操作/结果实时角色校验、API、定时清理、venue同事务摘要 |
| miniprogram/lib/schedule-public.js、pages/venue/* | 今日/本周、当前课程、覆盖/历史/离线差异、可信时钟、单在途/生命周期 |
| miniprogram/pages/schedule-operator/* | 原生完整草稿编辑/取消改期、确认范围、发布/撤回、冻结确认和unknown恢复 |
| miniprogram/lib/session.js、pages/maintenance/*、app.json | 角色入口、个人课表操作缓存清理、原生页面注册 |
| test/schedules.test.mjs | 服务端语义/时间/清理/回滚/权限/个人删除与TTL |
| test/schedule-integration.test.mjs、schedule-worker.mjs | 真实进程竞争/持锁顺序、双HTTP客户端、重启及清理失败恢复 |
| test/native-schedule.test.mjs | 原生VM展示/表单/确认/迟到/未知写/覆盖结束边界 |
| test/foundation.test.mjs | 迁移数变5、失败迁移编号006，原测试范围保留 |
| README.md、AGENTS.md、doc/cp4-implementation.md | 当前阶段规则、运行命令、实现澄清与限制 |

## 逐AC与层级

| AC | 本地通过证据 | 未验证 |
|---|---|---|
| S01/B01 | 保存草稿公共仍unpublished；完整发布一次切head；两独立HTTP客户端无需token读取同revision；原生今日/本周入口 | 真实微信/门店课表NOT_RUN |
| S02 | 明确空集合需发布；每日covered；退出覆盖不复用旧表；撤回head无指针；UI区分从未发布/所选日期不在覆盖/周部分覆盖/覆盖已结束/空日/撤回；endAt精确边界 | 真机展示NOT_RUN |
| S03/Z01 | stable courseId取消/改期，item CAS；范围内遗漏拒绝；scheduled重叠阻发布、首尾相接允许、取消不当前；真实双进程草稿/发布各只有一次CAS成功；实际草稿持锁先提交后旧确认发布409 | 真实多人馆方流程NOT_RUN |
| Z01 时间 | start≤now<end、跨午夜/周一相交；1/14天与非法日/范围；DST缺失拒绝、重复明确两个offset得到不同UTC；23/25小时与跳过日；published时区变动拒绝启动，撤回后仍需新时区重存草稿 | 门店IANA及真实排课核定NOT_RUN |
| S04 | HTTP非法请求非2xx、基线请求层HTTP/业务双判断；原生离线历史不可当当前，无样例补空；同资源一在途、隐藏停止、迟到响应无覆盖；回前台立即降级；unknown先查原key再当前状态/同key重试 | 真实微信弱网NOT_RUN |
| M04 | operator读写/结果实时权限；实际角色撤销写锁在前则发布403；已有跨环境/门店DB配置与身份隔离回归 | 真实馆方名单NOT_RUN |
| D01 | 双HTTP客户端、全服务进程重启后revision/内容持续；真实进程共享SQLite而非内存模拟 | 部署NOT_RUN |
| I01 | 发布审计失败整个事务回滚；同key重放一次、合法正文变更409；draft/publication双CAS；确认期间表单/版本改变不提交，异步转换后隐藏无写；冲突保留编辑 | 微信真实未知响应NOT_RUN |
| C01 | 每批100、失败批次回滚、DB重开续清；服务启动清理故障→持久failed→整进程重启成功；当前快照/草稿保留，撤回后清历史不复活 | 长期运维/外部备份NOT_RUN |
| X01/P01回归 | 课表无新增个人字段；删除操作者清audit/operations链接，共享发布不删；退出/删除清课表pending缓存 | 真机存储NOT_RUN |
| O03回归 | 修改/发布课表前后观察snapshot原时间/TTL完全相同，旧68项回归继续通过 | 现场忙闲NOT_RUN |

## 命令和证据

工作目录均为本项目根；本地回环HTTP需要的自动审批已通过，无公网/部署。

| 命令 | 退出/结果 | 日志 |
|---|---|---|
| `npm test` | exit0，87/87（原84+清理故障验证3项） | final-tests.log |
| `npm run check` | exit0，JS/JSON/原生引用/冻结基线hash | final-check.log |
| `git diff --check` | exit0 | verification.json |
| tested-source逐文件重验 | 全部一致 | verification.json |
| `node --test test/schedules.test.mjs` | exit0，专项辅助 | schedule-tests.log |
| `node --test test/schedule-integration.test.mjs` | exit0，实际进程/HTTP专项 | integration-tests.log |
| `node --test test/native-schedule.test.mjs` | exit0，原生VM专项 | native-tests.log |

最终以final-*日志及精确tested-source为准；full-tests/check/initial-regression为开发过程辅助。无新增依赖，见dependency-review.md。

## 合成来源、实现澄清与范围

课程仅测试文件构造“合成测试课程”，没有应用默认课程/seed；身份使用既有test-only fixture。真实进程请求临时放私有.runtime/随机目录0600，测试结束清理，日志只输出测试结果，不含token/真实资料。测试hook不得store，服务入口不能注入时钟。

新增view=today/week及time-preview是现有本地日期/明确偏移UI的服务端解析，非新产品功能。日期范围边界取该日首个实际时刻，跨DST不是固定24h；具体课程时刻仍必须给明确offset。原生完整表单支持移出新覆盖外旧课，覆盖内不能隐式删除。课表固定单例路径没有可变资源ID；operationType与完整正文确定目标，原会员动态路径保护保留。清理用尚未删除候选集合+事务计数/next-run作为持久进度，不依赖内存游标。细节见doc/cp4-implementation.md。

指挥者早审的覆盖结束/部分覆盖区分、回前台立即降级已修复并有原生测试。无产品真源/参数改动，无功能范围偏移或产品决策阻碍。

## 停止与限制

微信开发工具编译/预览/上传、真机、真实门店时区/课程核定均NOT_RUN。没有部署/远端/真实平台凭证/支付预约教练系统/CP5工程验收。课表本地发布测试只写隔离合成DB，不是对外发布。等待指挥者对最终提交验收。

## CP4 清理返工补交

合同GYM-CP4-TEARDOWN-REVISE-001，基线582488dcbc37369b88312b36210e9a352d136d61。仅测试harness和报告变更，产品源码不变。当前final日志、tested-source和manifest对应返工后源码，首次交付版本已保存在revision/submitted-*。详见revision/teardown-report.md。
