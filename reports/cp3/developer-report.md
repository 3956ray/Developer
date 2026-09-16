# GYM-CP3-MEMBERSHIP-001 / CP3

RESULT: COMPLETE

当前CP实现与本地验证已完成，等待指挥者ACCEPTED。未进入CP4。

## 基线与源码

唯一项目 `/Users/orderly_ray/Projects/gym-miniapp`；基线 `6729bb7f2579a559e44a920d5cf92347e3791564`，开始时工作树干净。最终提交hash及提交后clean状态在交接消息提供（避免提交自引用）。`tested-source.json`记录最终测试前全部源码/配置/文档哈希；交付前逐项重新校验。`manifest.json`覆盖交付文件（排除自身、.git、忽略runtime）。所有原CP0–CP2报告及冻结产品文件保持原样。

产品输入hash：PRD `7d9948f302f13119120c03043902fba7455437764eb57d4923efebda4254e1bd`；验收 `89cded4a468deaa20e6090d19b35dc31c77b6a01e3e45081d3d996484188ae71`；checkpoint `13060548afb17731e033364817f0e7ee6b0569c334bbfc4138e308ee64678882`。最终check重新核验。

## 文件与行为

| 文件 | 用途 |
|---|---|
| server/migrations/004-membership.sql | Registry/Binding/配对/限流/删除job/清理进度、唯一约束、清理索引；旧迁移不改 |
| server/membership.mjs、member-crypto.mjs | 安全码/加密、限流、资格生命周期、IANA期限、CAS/幂等/审计、删除接受和回执 |
| server/member-cleanup.mjs、scripts/member-cleanup.mjs | 分批持久清理、失败/重试/启动恢复、本机维护CLI |
| server/identity.mjs、protocol.mjs、config.mjs | 领域事务入口、fresh auth、目标路径入摘要、subject清理、有效IANA时区、用途版本 |
| server/http.mjs、main.mjs | 会员API路由、回执认证、执行器启动/停机 |
| miniprogram/lib/member-page.js、receipt.js | 本人状态/配对/解绑/删除、安全回执、未知结果恢复、确认目标冻结 |
| miniprogram/pages/membership/*、member-operator/* | 原生会员和馆方核验页，权限/确认、冻结表单、服务端期限转换、无原引用持久缓存 |
| miniprogram/lib/session.js、pages/me/*、pages/maintenance/*、app.json | 缓存清除、用途/主动重新认证及入口 |
| test/membership.test.mjs | 服务端边界/回滚/恢复/清理/时区 |
| test/member-integration.test.mjs、member-worker.mjs | 实际进程/锁/限流竞争与HTTP客户端/重启恢复 |
| test/native-membership.test.mjs | VM生命周期、状态、未知结果、缓存和确认竞态 |
| test/foundation.test.mjs | 仅调整新增迁移数量/故障编号，保留既有基础验证 |
| AGENTS.md、README.md、doc/cp3-implementation.md | 当前阶段规则、运行命令与实现说明 |

## 逐AC证据及层级

| AC | 已通过证据 | 未验证层 |
|---|---|---|
| M01/M02/R02 | membership测试：8位前导零、AES副本、同key同码、600000ms精确到期、一次消费、5碰撞SAVEPOINT/全回滚；integration实际双进程争同member/同requester仅一次成功；原生提交/取消及馆方bind流程 | 实际微信/现场核验NOT_RUN |
| R01 | 新进程+新会话不能绕3次生成/5错误；operator/requester均持久计数；unknown只operator；实际持锁跨600s边界按获取锁后时钟计数；计数DB失败回滚；删除竞态不重建个人桶 | 门店负载/部署NOT_RUN |
| M03 | revoked优先、占槽、普通bind拒绝恢复、显式恢复同关联或解绑后重绑；pending/fixed/no_fixed三模式；fixed精确到期；日期非法/闰年/跨年/非+8/DST/午夜重复和缺失 | 门店实际时区和有效期凭据NOT_RUN |
| M04 | operator实时权限、普通用户拒绝；基线跨环境/店DB配置隔离回归；查码/inspect最小结果不返回身份/token；原始memberRef只内存处理、HMAC落库 | 真实馆方授权NOT_RUN |
| M05/X01 | 认证300000ms接受/300001ms拒绝；解绑保留Registry；删除立即401及登录ACCOUNT_DELETING；真实持锁delete→bind拒绝，bind→delete关闭；新身份无继承 | 微信再次登录/真机NOT_RUN |
| C02 | 回执先持久化后发；HTTP丢弃删除响应可凭回执查pending；注入清理失败→failed，完整服务进程重启恢复completed；超过24h delayed；完成7d边界unknown；原生旧完成回执不误登出新账号 | 客户端真实存储/平台随机能力NOT_RUN |
| L01/P01 | cp3用途与拒绝、原生拒绝无写；clear清所有个人操作缓存留receipt；实际馆方未知响应持久cache不含原引用/码/token；隐藏销毁原表单 | 真机隐私UI/现场说明NOT_RUN |
| D01 | SQLite持久状态、两个实际HTTP客户端读相同资格；独立进程读写、服务整体重启，不以内存替身作为持久化证据 | 部署环境NOT_RUN |
| I01 | 路径目标纳入摘要避免不同bindingId复用key；CAS/幂等/单消费；注入audit失败整体回滚；原生unknown查询和同key重试，冲突重新确认；确认期间目标/表单改变无混合提交 | 微信实际网络中断NOT_RUN |
| C01 | 删除批次100行上限、跨DB重启phase恢复、最后事务检查个人关联；桶end+1h和回执7d精确清理；revoked最小登记保留；既有CP2 30d/当前观察头/完成后小时调度回归 | 长期运行和外部备份NOT_RUN |

## 最终命令、退出码

工作目录均为项目根。`npm test`需要本地回环端口权限，已由自动审批通过；无公网监听/上传。

| 命令 | 结果 | 证据 |
|---|---|---|
| `npm test` | exit 0，68/68通过（既有45项+CP3新增23项） | final-tests.log |
| `npm run check` | exit 0，JS/JSON/页面引用/冻结基线hash；不是微信编译 | final-check.log |
| `git diff --check` | exit 0 | verification.json |
| tested-source逐项SHA256再核验 | 全部一致 | verification.json |
| `node -p 'JSON.stringify({node:process.version,sqlite:process.versions.sqlite,openssl:process.versions.openssl})'` | Node24.18.0/SQLite3.53.1/OpenSSL3.5.7 | dependency-review.md |

开发过程专项命令为 `node --test test/membership.test.mjs`、`node --test test/native-membership.test.mjs`、`node --test test/member-integration.test.mjs`，对应同名日志exit 0；早期日志仅辅助，最终结果以final-*为准。没有新增第三方依赖，详见dependency-review。

## 合成资料与范围

身份fixture由test/identity-support在独立随机.runtime测试目录写入，仅test适配器；原生wx/HTTP替身仅测试VM。进程间输入文件在私有stateDir、0600，测试结束删除；报告仅测试名称/通过状态，无原码、原引用、token或真实人员身份。DB存储失败/清理失败为测试触发器及test-only hook。store拒绝这些hook/测试身份，服务入口不能注入时钟或随机源。

新增expiry-preview是现有日期期限的服务端转换；不是新产品功能。加入操作台账subject_id和清理索引是完成既定删除所需。先前审查发现的日期RangeError、路径未入摘要、失败计数删除竞态、旧receipt清新token、固定+08:00、个人缓存漏清、确认对象/表单漂移均修复并有测试。没有改冻结参数/产品范围，没有产品决策阻碍。

## 交付限制

实际微信编译/开发工具/预览/上传/真机、真实AppID/AppSecret、门店时区/原系统现场核验均NOT_RUN。源码本地通过不表示已上线或现场验收；无支付/门禁/扫码/课表。没有创建远端或部署。文件系统/设备备份不由应用控制，未承诺物理全副本擦除。停止在CP3，待指挥者验收。
