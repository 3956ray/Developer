# GYM-DEMO-FOUNDATION-001 / CP5-D1

RESULT: COMPLETE（开发交付，等待指挥者验收）。

D1 ENGINEERING: PASS；NATIVE LOGIC VM: PASS；NATIVE BUSINESS TOOL / OFFICIAL IDENTITY / DEVICE / VENUE: NOT_RUN。v1.1演示仍须D2/D3独立验收，未提前实现。没有馆方后端、AppSecret或域名不是本阶段阻塞。

## 基线与行为

源码根 `/Users/orderly_ray/Projects/gym-miniapp`，基线 `f22664d5f5b2320966df53c874eaaf120297be6b`，开始工作树干净。合同task-contract.json的七份输入逐字校验；四份v1.1精确副本进入doc/baseline并追加sources索引。v1.0三文件、001–005历史迁移、全部旧报告未改；本地工具测试号与urlCheck保持原状，客户端默认仍unconfigured。

新增独占demo初始化及CLI短期票据，复用原Node/SQLite、session/role/审计/幂等/领域写入。demo要求test+simulation+test-app+demo.enabled+demo-*。票据32随机字节、300秒；数据库只留scope/主体HMAC及时刻。consent/notice/格式/前置限流通过后，在login_codes事务内消费；事务已提交后会话失败也不可重用。清理接入原会员小时保留任务，过期读写即时失效，不依赖物理删除。

operator-a不自带权限。原生我的页新增掩码输入、演示用途同意/拒绝、新票重新认证；不调用wx.login生成演示身份，无自动回退或注入session。health和每个响应核对namespace；个人/待写/观察缓存/回执统一scope含gym和身份模式。

初审发现并修复兼容问题：非demo且未显式限定identityMode的无令牌公共GET允许identity.disabled等合法身份状态，不把身份未配置当公开读取前置；显式demo仍全部严格匹配，带token和交换请求仍严格。新增原生请求模块连接真实disabled后端的venue/schedule回归；鉴权不匹配清会话后公共读取仍可用。

## 实际结果

- `npm test` exit0：**110/110 PASS**（原94＋新增16），final-tests.log；身份/角色/限流/删除/原生缓存/三清理执行器等完整回归，非沿用旧94。
- 双客户端和真实进程证据在同一日志：观察revision=1，observedAt=2026-09-16T04:54:16.126Z，validUntil=2026-09-16T05:09:16.126Z；两个客户端及重启后完全相同，仅一事件；重放不重复、异body409、普通403。停服务请求失败，重启不清状态或授角色。
- 实际CLI发行票据；实际role CLI grant/revoke；撤销后新票登录和重启仍403。两实际服务进程交换同票，仅一次201。持久限流不因重启/票据重置。
- 受控时钟：299999成功/300000失败；删除login_codes后旧票仍失败；同意/前置限流不消费、会话事务失败消费、尝试台账事务失败不消费；小时清理失败恢复；5会话上限、5分钟敏感认证和删除期间锁定/完成后新ID无角色。
- 原生VM：拒绝和隐藏不发送交换；meta错gym/environment/mode/simulation/demo关闭动作并清token；凭证不进page.data/storage；官方失败不回退；未知结果提示新票，不自动重登。
- `npm run check` exit0，check.log：语法/JSON/七文档hash/页面引用，不声称微信编译。
- 逐AC见ac-matrix.md/json。DM01/02/04本阶段工程PASS，DM03角色基础PASS，DM12当前范围PASS；完整演示PARTIAL；DM05–11 NOT_RUN。

## 改动文件及目的

|文件组|用途|
|---|---|
|server/migrations/006-demo-tickets.sql、server/demo.mjs、scripts/demo.mjs|新增票据表/HMAC/消费、隔离初始化和发行CLI|
|scripts/init-local.mjs、server/config.mjs|叶目录独占创建与显式demo配置校验|
|server/identity.mjs、wechat.mjs、http.mjs、member-cleanup.mjs|同尝试事务消费、adapter隔离、namespace元信息、到期小时清理|
|miniprogram/lib/session.js、pages/me/index.js/.wxml|显式演示输入/同意/重新认证、每响应命名空间与公共兼容|
|miniprogram/lib/member-page.js、observation-page.js、pages/member-operator/index.js、schedule-operator/index.js|共享新缓存scope，不跨门店/身份模式复用|
|test/demo-foundation.test.mjs、demo-http.test.mjs、demo-support.mjs、native-demo.test.mjs|新增16项D1工程/真实HTTP/VM验收及合成fixture|
|test/foundation.test.mjs、native-membership/observation/schedule.test.mjs|迁移数更新为6、原控制器回归使用新scope契约；未削弱原断言|
|doc/baseline四增量及sources.json、doc/demo-foundation.md、README.md、AGENTS.md|冻结增量索引、数据/命令/配置与阶段规则|
|reports/cp5/demo-foundation|当前合同、逐AC、实际日志、源码/config/交付哈希|

## 清理与限制

所有测试服务/worker由fixture生命周期先停止并等待close，再关闭DB并删除自己独占的目录；新demo fixture没有遗留。未停止其他服务、未操作真实store或会员。报告不含有效票据、token、配对码、回执或密钥；具体检查见verification.json。实际HTTP配置SHA记录于final-tests.log并提取到configuration-evidence.json，临时DB/密钥已删除。

没有场景runner、JSON导入、外部数据库、网络安全开关、预览/上传/部署或原生业务截图。测试与票据命令只用于隔离合成环境；手动演示命令在doc/demo-foundation.md，官方模式保留其独立约束。原CP5根报告/manifest作为历史提交证据保持不变，当前精确源码与哈希使用本目录source-manifest.json与manifest.json。最终commit/clean交接在提交后消息给出。停止于D1，等待ACCEPTED。
