# CP1 实现与契约细化

以CP0已评审technical-contract及不变PRD为业务真源；本文件只说明当前实现与后续预留。

## 已实现接口

- GET /health，GET /v1/privacy（公共用途说明版本cp1-purpose-v1）。
- POST /v1/sessions/exchange：仅code/privacyNoticeVersion/consent，成功201，仅创建业务身份；测试响应simulation=true。
- GET /v1/session?interaction=poll|interactive：返回sessionRevision与accountRevision；默认poll。只有interactive更新lastInteractiveAt，不改变sessionRevision（其版本管理撤销）或绝对期限。
- POST /v1/session/logout：W；只撤销当前令牌。旧token重试401，不能从幂等缓存绕过权限。
- GET /v1/operator/role：本人isOperator及roleRevision；无角色时absent。
- GET /v1/operator/audit?limit=1..100：受实时operator鉴权，近90天记录；本CP只实现最近列表，before分页保留后续维护界面集成，传未知参数拒绝。
- GET /v1/operations/:operationId?type=session.logout：同一业务主体的历史结果；当前令牌必须仍有效，历史不代表当前session。
- 本机scripts/role.mjs inspect/apply/result；操作类型role.grant/revoke，维护者scope独立。不存在任何HTTP授予接口。

所有HTTP解析拒绝未知字段、嵌套/转义重复键、非JSON内容、超长/畸形输入；最多64KiB/32层，输出不回显原始数据。无客户端userId/gymId/role/openid授权入口。SQL按部署DB隔离；业务表属于固定gym metadata，账户额外检查gym_id。

## 原子性与版本

登录先在BEGIN IMMEDIATE之后采样时间并原子计数，再持久化一次性code HMAC占位，出事务后调用平台，最后新事务创建唯一身份、会话、隐私同意记录和审计。平台/审计失败不撤销已提交尝试计数；新code才能重试。code HMAC为域隔离带密钥摘要，不是明码；并发相同code只有一个外调机会。

身份映射唯一(appId,openId)，业务user随机UUID。有效session限制5，事务中以created_at/session_id稳定排序撤最早再插新令牌摘要。期限由服务端判断，认证/角色/账户状态在写锁内读取。角色撤销不撤普通会话。账号deleting字段及拒绝逻辑仅预留，没有删除入口/任务。

role grants与logout：规范body HMAC、24小时scope台账、intent[-30秒未来,+300秒年龄]、expectedRevision、审计同事务。鉴权优先于返回结果；过期台账不可重放旧intent。CLI查询结果同维护者scope，要求本机授权账户。

CP0约定的通用角色/业务敏感数据结构在002迁移按当前范围落地；未来配对、MemberRegistry、课程、清理表不提前创建。test_login_fixtures只保存合成subject、code HMAC及预定结果，不是门店身份源。identity_namespace阻止切换AppID/模拟来源后继承旧身份。

## 客户端

用户主动确认用途说明后才wx.login；拒绝仍可公共浏览。完整token仅放私有缓存和实例字段，不进setData或日志；缓存按environment/baseUrl隔离。模拟环境/模拟身份有文字标签，微信登录不等于会员或operator。

首次读取/回前台先显示待确认；隐藏停止定时器并使旧请求结果失效。session刷新锁保留至真实请求结束，期间的新刷新只排队一个，手动interactive优先；旧请求完成后才发新请求，不出现重叠。失败不标有效，401清缓存。刷新不调用wx.login。

退出需安全随机UUID；无平台安全随机能力时不伪造请求。写超时查询原操作，401可证明当前授权已失效，不声称数据删除。没有真实AppID的工具验证，页面行为证据为Node VM执行原生页面JS及模拟wx API，不是屏幕/真机。

## 物理保留及未实现

CP1没有小时清理执行器；会话/限流/幂等逻辑到期不依赖物理清理。后续按PRD清理会话、审计、桶和台账；login_codes（带键一次性摘要）按attempted_at+24小时清理，微信code本身短时一次性且从不存明文；测试fixtures只存在测试临时DB、由测试结束清理。官方session_key仅在平台响应校验瞬时使用，立即丢弃；不落DB、客户端、日志。

没有新增第三方依赖。官方文档工具访问失败，另核对PM已归档官方流程证据（SRC-20260916-gym-wechat-01），固定HTTPS交换路径/字段使用独立transport契约测试；没有以该测试代替微信服务器实测。真实主体/隐私后台、AppID、域名、证书、工具、设备均未验证。
