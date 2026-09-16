# CP5：30 AC 分层矩阵

共同条件：源码为tested-source.json精确哈希；单店隔离test配置、Node实际SQLite/进程/HTTP及原生JS VM，均合成数据。工程PASS仅指本地可复现层；官方微信交换、隐私声明、基础库和设备行为不在替身PASS内。所有工程测试在final-tests.log同一源码一次完整运行。工具阻塞原因及真实命令见tool-status.md。

| AC | 场景/输入与工程结果 | 精确证据（test目录文件/测试标题关键词） | 工程 | 工具 | 真机 | 门店 |
|---|---|---|---|---|---|---|
| B01 | 公开场馆/今日本周、未配置空态，无登录前置 | observations.test.mjs: B01/O01/O02; schedule-integration.test.mjs: two public HTTP clients; native-schedule.test.mjs | PASS | BLOCKED | NOT_RUN | NOT_RUN |
| O01 | 两客户端相同观察revision/时间、持久化和60秒轮询 | observation-integration.test.mjs: two independent unsigned clients; native-observation.test.mjs | PASS | BLOCKED | NOT_RUN | NOT_RUN |
| O02 | 899/900边界，课程/会员/清理不续TTL | observations.test.mjs: B01/O01/O02; integrated-lifecycle.test.mjs: cross-domain writes | PASS | BLOCKED | NOT_RUN | NOT_RUN |
| O03 | 未更新/暂停/撤回、坏时间不回退旧等级 | observations.test.mjs: control states and bad timestamps; integrated-lifecycle.test.mjs: preserves controls | PASS | BLOCKED | NOT_RUN | NOT_RUN |
| O04 | 写响应丢失、CAS/幂等/审计同事务，旧意图不自动重写 | observations.test.mjs: CAS, single audit/idempotence; native-observation.test.mjs | PASS | BLOCKED | NOT_RUN | NOT_RUN |
| O05 | 前后台单在途/重建时钟，离线历史不新鲜 | native-observation.test.mjs; native-session.test.mjs: foreground polls never relogin | PASS | BLOCKED | NOT_RUN | NOT_RUN |
| L01 | 一次性code/错误/重放拒绝；适配器合约仅替身验证 | identity.test.mjs: invalid/replayed/platform code; official adapter transport contract; http-identity.test.mjs | PASS | BLOCKED | NOT_RUN | NOT_RUN |
| M01 | 未绑定→配对→前台核验→同身份读取服务器关联 | membership.test.mjs: leading zero, encrypted code; member-integration.test.mjs; native-membership.test.mjs | PASS | BLOCKED | NOT_RUN | NOT_RUN |
| M02 | 600秒/单消费/唯一性、错误限流、不抢占 | membership.test.mjs: one use and exact 600s expiry; member-integration.test.mjs: same member and same requester races | PASS | BLOCKED | NOT_RUN | NOT_RUN |
| M03 | revoked优先、明确期限与到期、显式恢复 | membership.test.mjs: revoked precedence; explicit pending/fixed/no-expiry; native-membership.test.mjs: member states | PASS | BLOCKED | NOT_RUN | NOT_RUN |
| M04 | 当前角色/命名空间，角色撤销先提交则旧管理写拒绝 | identity.test.mjs: foreign-store token denied; concurrency.test.mjs; schedule-integration.test.mjs: role write lock | PASS | BLOCKED | NOT_RUN | NOT_RUN |
| M05 | 新鲜认证/解绑/删除、回执、缓存及新会话不被旧响应清除 | membership.test.mjs: fresh auth boundaries; native-membership.test.mjs: CP5 late deletion response | PASS | BLOCKED | NOT_RUN | NOT_RUN |
| P01 | 用途与拒绝/主动登录分开，隐藏后的旧确认不得登录 | native-session.test.mjs: public/refusal path; CP5 login confirmation received after hiding; native-membership.test.mjs | PASS | BLOCKED | NOT_RUN | NOT_RUN |
| S01 | 私有草稿→完整原子快照、公共同revision | schedules.test.mjs: draft private; schedule-integration.test.mjs: two public HTTP clients | PASS | BLOCKED | NOT_RUN | NOT_RUN |
| S02 | 未发布/空日/覆盖结束/覆盖外/部分覆盖/撤回区分 | schedules.test.mjs: coverage replacement/withdrawal; native-schedule.test.mjs: exact coverage end | PASS | BLOCKED | NOT_RUN | NOT_RUN |
| S03 | 稳定ID取消改期、跨日周相交/并发冲突 | schedules.test.mjs: stable ID; local/UTC exact boundaries; schedule-integration.test.mjs | PASS | BLOCKED | NOT_RUN | NOT_RUN |
| S04 | HTTP/业务错误与离线历史、unknown原key查询/重试 | native-schedule.test.mjs: native public; native operator unknown publication; native-session.test.mjs | PASS | BLOCKED | NOT_RUN | NOT_RUN |
| D01 | 隔离SQLite持久化、多进程/重启、不自动模拟回退 | foundation.test.mjs; identity.test.mjs: adapter isolation; observation/member/schedule-integration.test.mjs | PASS | BLOCKED | NOT_RUN | NOT_RUN |
| W01 | 真实iOS/Android、合法域名证书、非调试官方登录 | 后续CP6；无设备/真实服务链路证据 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |
| T01 | 真实现场观察→会员读取与7天效果指标 | 后续CP7；无门店观察或试点样本 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |
| T02 | 真实原系统核验及一周核定课表 | 后续CP7；无真实会员/馆方课程资料 | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |
| F01 | 闲置/绝对期限/5会话/退出/删除，后台轮询不续期 | identity.test.mjs: exact idle/absolute boundaries; concurrent first exchange; native-session.test.mjs | PASS | BLOCKED | NOT_RUN | NOT_RUN |
| R01 | 120/分钟与3/600、5/600持久化、双维/unknown计数 | concurrency.test.mjs; member-integration.test.mjs: independent processes/session switching; identity.test.mjs: DB failure | PASS | BLOCKED | NOT_RUN | NOT_RUN |
| R02 | 1次碰撞成功重抽、5次全回滚、码密文和单消费 | integrated-lifecycle.test.mjs: one secure pairing collision; membership.test.mjs: five collisions rollback | PASS | BLOCKED | NOT_RUN | NOT_RUN |
| I01 | 跨域scope/body/CAS，24小时淘汰后旧intent不执行 | identity/observations/membership/schedules.test.mjs; integrated-lifecycle.test.mjs: old intents cannot execute | PASS | BLOCKED | NOT_RUN | NOT_RUN |
| X01 | 真实进程bind/delete双顺序、角色撤销竞争、无资格回生 | member-integration.test.mjs: real write locks; concurrency.test.mjs; integrated-lifecycle.test.mjs: cross-domain writes | PASS | BLOCKED | NOT_RUN | NOT_RUN |
| Z01 | 1–14日/半开/DST显式offset/跨周/单当前发布 | schedules.test.mjs: local/UTC; schedule-integration.test.mjs: draft-write lock before publication | PASS | BLOCKED | NOT_RUN | NOT_RUN |
| C01 | 100行批次、各领域保留期/当前例外；实际入口小时接线失败恢复 | observations/membership/schedules.test.mjs cleanup cases; integrated-lifecycle.test.mjs: production cleanup wiring | PASS | BLOCKED | NOT_RUN | NOT_RUN |
| C02 | deleting身份锁、去关联/延迟/重启恢复、回执7日 | membership.test.mjs: lost-response receipt and bounded deletion; member-integration.test.mjs: failing cleanup/new identity; integrated-lifecycle.test.mjs | PASS | BLOCKED | NOT_RUN | NOT_RUN |
| E01 | 独立根/依赖/迁移/清理、工具真实导入编译分层 | foundation.test.mjs; teardown.test.mjs; final-check.log; tool-status.md | PASS | BLOCKED | NOT_RUN | NOT_RUN |

## 结算

27项适用工程层PASS；W01/T01/T02留既定CP6/CP7，NOT_RUN。工具层未取得目标项目编译/交互证据，BLOCKED；不能把27项工程PASS写成30项产品验收通过。顶层RESULT BLOCKED，未进入CP6。

所有涉及原生页面的空/正常/过期/失败图像均缺真实目标工具截图；已通过的VM逻辑测试不填补该缺口。L01/P01实际平台配置未验证，门店实体核验不是合成bind事务可以证明的事实。
