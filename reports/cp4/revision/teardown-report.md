# CP4 teardown revision

RESULT: COMPLETE（待指挥者验收，未进入CP5）

合同 GYM-CP4-TEARDOWN-REVISE-001，返工基线582488dcbc37369b88312b36210e9a352d136d61。原提交未获接受；Leader实际9/10、exit1，ENOTEMPTY发生于identity-support的after hook，服务仍存活导致runner挂起。原失败日志原样保留leader-failure.log；原84/84和原源码清单在submitted-*，不抹除历史失败。

## 根因

setupIdentity先注册关闭DB/删除目录的after hook；各测试随后注册service停止hook。Node按该注册顺序执行，fixture先被删除，仍活跃的小时清理/SQLite进程可重建文件，触发ENOTEMPTY。较早hook报错也不能作为后续hook必能执行的保证。旧stop只等exit，未覆盖所有进程/stdout关闭；启动失败发生在返回service并注册hook之前时没有完整归属，IPC等待也可能无界。

## 修复文件

- test/process-support.mjs：每fixture唯一lifetime；所有spawn/fork必须关联显式登记的config路径。创建进程即纳入归属，单个清理hook先停止全部子进程，await close（包括stdio），再关闭DB/额外资源、rm目录。关闭出错保留fixture并报错，不忽略ENOTEMPTY，不重试删除刷绿。
- 服务启动共用startService；超时/非法readiness/早退出均先stop并await，再抛原错。stop正常SIGTERM有2秒期限，超时仅对自有ChildProcess执行SIGKILL并等待2秒，同时保留失败。自有子进程总时长30秒守卫，IPC单次等待5秒；超时不能被吞成通过。
- identity-support改用统一lifetime，按db.isOpen关闭，不吞DB关闭异常；observations.test重开DB注册beforeRemove，避免另一条hook晚于删除。
- foundation、http-identity、concurrency、observation/member/schedule-integration全部自有服务/worker接同一归属机制，移除独立较晚停止hook。原业务断言与真实竞争步骤保留。
- teardown.test/driver/child：新增多个服务+worker关闭顺序；启动无ready/非法JSON/早退；故意断言失败的独立runner，要求exit1且后续hook证实子进程已停/目录已删。

故意失败的嵌套runner须去掉继承的NODE_TEST_CONTEXT，否则不会按独立测试runner执行；第一次新探针因此不符合预期，targeted12/13与full86/87失败保留initial-*.log（仅移除空行尾空格以通过Git whitespace检查，错误内容不变）。修正仅隔离runner环境，不放宽exit1/TEARDOWN_COMPLETE等断言。

## 验证

所有命令目录 /Users/orderly_ray/Projects/gym-miniapp。

- `node --test test/schedule-integration.test.mjs test/schedules.test.mjs test/teardown.test.mjs`：13/13、exit0，targeted-tests.log。包含原失败的完整HTTP/重启/清理场景及新增失败路径结构验证。
- `npm test`：87/87、exit0，../final-tests.log；所有共用helper受影响测试覆盖。
- `npm run check`：exit0，../final-check.log。
- `git diff --check`和最终SHA复核：通过，../verification.json。

不是重复运行同一错误直到变绿：首次探针失败后修正明确的NODE_TEST_CONTEXT传递问题，然后执行目标与完整回归。没有全局pkill/killall，只有已登记的ChildProcess对象；正常结束、断言失败、启动失败均验证清理，应用行为/服务源码/冻结产品文档及CP0–CP3报告未修改。

最终本地跟进提交与clean状态在交接消息给出。微信工具/真机/真实门店仍NOT_RUN；停在CP4等待验收。
