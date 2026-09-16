# CP3 会员配对、核验与删除实现

合同 GYM-CP3-MEMBERSHIP-001；基线 6729bb7f2579a559e44a920d5cf92347e3791564。冻结产品三文档未修改；无第三方依赖。CP0技术合同适用；本文件记录实现落点，不变更参数或后续CP范围。

## 数据与事务

新增迁移004，保留001–003不变。独立数据库由metadata约束environment/gymId/keys，表内账号带gymId，其他表通过同库与外键隔离。Registry保存经trim、保留大小写/前导零的引用HMAC；Binding含用户，两个部分唯一索引约束当前用户及当前memberKey。撤销仍current=1。配对有当前用户/当前摘要唯一索引，revision由账号单调序列提供；清理后首次读可absent，新请求仍新ID/递增序列。

配对crypto.randomInt生成8位字符串；每候选SAVEPOINT，仅唯一摘要碰撞重试，5次失败回滚旧码终止、revision和计数。AES-256-GCM展示副本只对本人有效请求解密；终态清密文，过期不返回码，小时清理物理删除。幂等台账只存pairingId；重放返回当前配对态（已清理为unavailable），不复活码。

新写、当前权限/身份检查、CAS、审计和操作台账同BEGIN IMMEDIATE事务；路径bindingId加入body摘要。后台操作台账subject_id便于删除请求者的关联记录。核验失败回滚后独立事务计数，重新确认可定位请求及active账号，避免删除竞态重建个人计数。固定UTC桶3次生成/600s；operator和可定位requester各5错误/600s；unknown只operator，冲突/基础设施错误不计。

## API（均 /v1）

| 入口 | 边界 |
|---|---|
| GET /me/membership、/me/pairing | 当前本人会话；资格派生态/当前版本，码只本人有效请求 |
| POST /me/pairing、/me/pairing/cancel | UUID key、5分钟意图、CAS；创建/取消 |
| POST /operator/pairing/lookup | 当前operator，精确码；无用户身份/token |
| POST /operator/members/inspect | 当前operator，精确引用及可选配对；返回独立registry/requester/currentBinding版本 |
| POST /operator/members/expiry-preview | 当前operator，本地结束日期→门店IANA次日午夜UTC；缺失/重复午夜明确报错 |
| POST /operator/members/bind、restore | 三方CAS、frontDeskConfirmed、明确expiry；原子消费，普通bind不能恢复revoked |
| POST /operator/members/:bindingId/reverify、revoke | 当前关联/Registry CAS、路径纳入幂等摘要；续期不能恢复revoked |
| POST /me/membership/unbind | 最近认证≤300000ms，绑定/Registry/配对CAS，关闭关联保留Registry |
| POST /me/account/delete | 最近认证≤300000ms，account CAS；仅接收32字节回执token的SHA256摘要 |
| POST /deletions/status | Authorization: DeletionReceipt；仅状态/时间/延迟，无身份；未知不视为完成 |

所有期限按锁内服务端时刻；fixed_until以now≥until过期，pending_confirmation不当有效，no_fixed_expiry明确无固定期限。日期验证拒绝无效日/溢出；IANA午夜多解/无解拒绝自动选择，可提交经回算确认的明确UTC。

## 删除与清理

接受事务立即deleting、所有会话撤销、角色移除、关联关闭、码取消并建持久job。身份映射保留至最后完成事务，期间登录不能重建。每批≤100行，按已删除集合推进phase；候选、批次和phase同锁事务；失败回滚并记录类别/重试时间。

阶段清理会话、码、关联、actor/subject操作台账、同意、会员桶；审计和观察actor去关联；测试模式移除合成映射fixture。最后显式检查个人关联已清，原子移除映射/account并将job userId置空标completed。Registry不做时间清理，revoked防回生保留。完成后新登录新userId，无角色/关联继承。

保留期：终态/过期码下轮清；桶windowEnd+1h；关闭绑定30d；终止会话按23h阈值供小时清理，24h内目标；幂等/登录码24h；审计90d；完成回执7d。失败会延期但不恢复权限；启动强制恢复，之后完成时间+1h调度。观察30d沿用CP2执行器。

## 原生端

用途版本cp3-purpose-v1；主动登录与拒绝仍保留。会员页面用服务端时间+单调钟，离线/未知时不显示有效码。回执使用平台安全随机32字节、ASCII SHA256（标准向量比对），发送前持久保存；清会话和所有个人操作缓存，保留回执。历史完成回执不清新账号token。

个人确认冻结目标/版本/会话；前台表单修订号和核对对象贯穿异步inspect、确认弹窗、日期转换及提交，变化要求重新核对。前台未知响应只持久化userId/key/type/path，不保存引用/码/请求body；原body仅内存，隐藏销毁。重试保持原key，重启无原资料须查询当前状态、现场重新确认。成功/查询原结果后刷新当前状态，历史操作结果不直接当资格。

## 验证边界

Node实际SQLite、独立进程、回环HTTP客户端及VM原生控制器有测试证据。没有实际微信AppID/AppSecret、微信开发工具编译/预览/上传、真机或线下核验，不能据此声明现场可用。没有部署/远端变更；课表留CP4。
