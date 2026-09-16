# CP4 课表实现说明

合同 GYM-CP4-SCHEDULE-001，基线22369e6320a64b96949acc31f195461134bba52d。产品v1.0三文件不改；无第三方依赖。实现原生WXML/JS界面和实际SQLite持久服务，不将VM当微信工具/真机验收。

## 单例、版本和数据

迁移005新增schedule_draft、schedule_head、schedule_snapshots、schedule_cleanup；旧迁移不变。独立DB由metadata绑定环境/门店/密钥，所有课表表为同店内部实体，没有跨店读写入口。草稿单例，head单例；快照按随机ID与publication_revision唯一。withdrawn的head指针为空，绝不以max历史找当前。

全量保存校验draftRevision及每项course.revision；现有courseId用于取消/改期，字段未变保留item revision，变化+1。新项UUID/revision absent，重复ID拒绝；范围内旧项不可遗漏，需显式cancelled；新覆盖外可明确移除。草稿允许临时scheduled重叠，发布最终阻止重叠。边界相接允许；cancelled不参与冲突与当前课。

发布在同BEGIN IMMEDIATE下检查当前operator、draft/publication双CAS、完整集合和replaceCoverageConfirmed，再插入不可变课程快照、旧快照retired、切换单head、审计、操作台账。撤回递增head版本、退役旧快照、清当前指针。新写5分钟/幂等24小时沿用身份领域事务；同key正文不同409、同key重放不重复写。课表三写均固定单例路径，通过operationType+完整body摘要区分目标；不存在客户端提供的动态路径ID。动态会员路径仍沿用CP3资源摘要保护。

## API

所有路径 /v1；公共响应带serverNow/environment/simulation/timeZone/source/publicationRevision。公共不返回草稿、actor或身份。

| 路径 | 输入/行为 |
|---|---|
| GET /venue | 同事务加入scheduleSummary：今日、本周起日、当前课程/覆盖状态，保留原观察时间和TTL |
| GET /schedule?from=YYYY-MM-DD&to=YYYY-MM-DD | 1–14本地日，半开区间；每日covered/state/course集合；public只当前快照 |
| GET /schedule?view=today或week | 客户端无需设备时区推日期；week为门店本地周一至下周一；不能同时传from/to |
| GET /operator/schedule/draft | 角色保护；草稿和当前发布版本、覆盖、课程修订 |
| POST /operator/schedule/time-preview | 角色保护；start/end各含local YYYY-MM-DDTHH:mm、明确offset；返回校验后的UTC起止 |
| PUT /operator/schedule/draft | 普通W + expectedRevision标量、coverage{startDate,endDate}和完整courses；每项id/name/status/revision/startAt/endAt/localInput |
| POST /operator/schedule/publish | W + expectedRevision{draft,publication}、replaceCoverageConfirmed=true |
| POST /operator/schedule/withdraw | W + publication expectedRevision、枚举reasonCategory |

public state区分query范围有覆盖的published、无覆盖unpublished、withdrawn；publicationState另表头状态，coverage保留当前发布元数据。每一天独立covered，不因周中一天有课就把整周当已发布。覆盖外不返回旧快照课程。没有覆盖与明确空集合不同；取消课保留可见取消标记。

## 时间与时区

UTC用标准毫秒ISO；课程本地时刻必须给显式offset，服务端先算UTC再用Intl指定IANA回算，年月日时分不匹配拒绝。DST缺失时刻没有有效候选；重复时刻只有明确正确offset才接受。start<end，课程需与覆盖区间相交。公共每日/周按区间相交，当前课start≤now<end且当前日期在覆盖内。

日期覆盖长度以本地日历日期差计算，不以24小时倍数替代DST天。日期边界取该日期开始的首个实际时刻；重复午夜仍属于同一天，跳过整日则该日期区间零长度。这是日期集合边界规则，课程具体时刻输入仍严格要求显式偏移。23/25小时日、跳过日期、跨年与闰年非法日有测试。技术JSON负载65KB、课程数组最多200、名称1–100字符作为输入安全上限；日期限制在0100–9998以避免扩展年份/纪元歧义。

当前published绑定时区；DB打开及服务读写发现配置不一致则失败，不静默改历史。withdraw允许原环境维护者撤回，再由受控本机配置修改时区；旧草稿标draftTimeZone，必须重新保存按新时区验证，才能发布。

## 原生状态与确认

公共页面与观察各自单在途请求，60秒轮询、隐藏停止、generation拒迟到回调，回来立即清可信时钟并降为历史/待确认。基于serverNow+单调钟，在课程结束与覆盖结束精确边界改变摘要。只保留本次页面内上次结果，不用fixture/过往周补空。显示所选日期不在覆盖、部分覆盖、覆盖已结束、撤回、显式空日等，并始终说明无排课不代表场地无人。

馆方完整表单、明确保存与替换范围确认，捕获form/base revisions/generation/token；异步UTC转换后再次比对，不把旧确认绑定新版本。轮询不覆盖dirty编辑；冲突保留编辑，需明确放弃/重新读最新草稿。pending操作先私有持久存同key/body，再发送；未知先查原操作并读当前，重试同key、超5分钟要求重新核对。登出/删除清schedule操作缓存；所有写和结果查询重检当前角色。隐藏/生命周期变化不能用旧成功响应刷新当前页。

## 清理与删除

旧快照retired且coverageEndUTC+30d≤now，每批最多100，排除当前指针。候选删除、deleted_total、next_run_at同事务，失败回滚并保存error_count/类别/下一小时；删除集合本身是持久进度，重启重新扫描尚未删除候选，不以脆弱内存游标推进。启动强制补跑，之后完成时间+1h调度，CLI run/status。

课表实体无userId/actor；新个人关联只在既有audit/operations，由CP3删除执行器去关联/删除。删除操作者不删除共享草稿/公开事实；有专门测试。观察头及原900秒TTL不受课表写入和清理影响。
