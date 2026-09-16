# GYM-CP5-TOOLS-002

RESULT: BLOCKED。工程 PASS（沿用基线94/94）；目标导入、实际本地编译与基础库确认 PASS；完整工具业务验收 BLOCKED；真机/门店 NOT_RUN。停在CP5，等待指挥者验收。

## 已完成与证据

基线 `5be44aad0a22f5f5bf0f0ae319800aba0bfea1e0`。本次保留用户选择“测试账号 → 创建”产生的 project.config.json（测试AppID wxef96b440323d98b4），没有借用其他项目身份。官方工具 Stable 2.02.2608070；控制台实测 WeChatLib 3.17.2，私有配置一致。tracked/private 两处 urlCheck 均保持 true。CLI服务端口未重试/放宽，使用已经正确导入目标项目的GUI。

通过“工具 → 编译”及普通编译完成实际本地构建：app.json、六个页面JSON、代码分析成功及 idle-compile all done（10、14）。六个原生页面已实际打开；这是官方模拟器证据，不是真机。

|证据（本目录）|实际操作与观察|
|---|---|
|01-venue-unconfigured.png|公开场馆入口，服务未配置/连接失败，不要求登录|
|02-my-unconfigured.png、03-purpose.png|我的未登录，查看用途及隐私说明|
|04-membership-unauthenticated.png|从我的进入会员页，要求主动登录|
|05-after-local-compile.png|临时配置本机服务后的真实编译/页面|
|06-runtime-domain-error.png/.txt|基础库版本及实际 request 合法域名错误|
|07-login-purpose-modal.png、08-login-refused.png|主动登录用途确认，选择暂不登录，显示拒绝后仍可浏览|
|09-login-unconfirmed.png|再次同意登录，最终未能确认；没有伪造成功或自动重登|
|10-local-build-panel.png|实际构建面板，六页JSON与代码编译信息|
|11-maintenance-guard.png/.txt|本地编译模式打开维护页，要求先登录，无写控件|
|12-member-operator-guard.png|会员馆方页要求登录/馆方权限，无写表单|
|13-schedule-operator-guard.png|课表馆方页无法确认最新课表，仅可刷新，无写表单|
|14-final-normal-compile.png/.txt|恢复客户端配置后普通编译，代码分析成功与公开失败页|

## 当前阻塞与缺失条件

临时test后端在 http://127.0.0.1:51580 健康检查成功，但微信运行时明确拒绝该地址：不在 request 合法域名列表中，实际列表仅见 https://tcb-api.tencentcloudapi.com。后端curl成功不代表wx.request成功。旧“未导入/无法编译”阻塞已解除；当前主要阻塞是合法服务地址，另缺可完成官方code交换的身份配置。

需要负责人提供/确认可用于此项目的合法HTTPS服务域名、对应账号的平台配置能力与官方身份交换配置；后续配置须同时符合项目环境约束（当前test客户端仅允许loopback HTTP，store要求HTTPS），由指挥者明确后续联调方案。本次没有部署授权，未创建外部服务、改安全校验或注入会话。没有证据证明账号被平台拒绝，也没有证据证明wx.login/code交换成功。

服务identity明确disabled，backend-no-identity.json中账号/会话/绑定/配对/operator/观察头/课表头均为0。因此正常业务数据、已覆盖空日、过期视图、认证后的馆方读写及端到端登录仍BLOCKED；未配置失败态不能算业务空态。三个馆方页的关闭行为不等同实际服务端403验收。平台隐私配置、真机、门店条件仍未验证。30AC中27项工程PASS、W01/T01/T02工程NOT_RUN保持；工具只补局部证据，不升级完整AC为PASS。

## 配置、清理与验证

临时客户端地址记录于 client-config-probe.js 与 probe-configuration.json，仅用于上述实际GUI请求。已按字节恢复 miniprogram/config.js；最终配置仍unconfigured。停止唯一自有后端进程（SIGTERM且确认退出），删除自有 .runtime/test/cp5-tools-002。三项自建本地编译模式已删除；保留其他私有工具配置，GUI留在目标项目普通编译的场馆页。私有配置不提交，观测摘要中的旧哈希对应观测时刻，最终摘要另存。

本次无产品源码修改。server/miniprogram/test/scripts/package及冻结文档、CP0–CP4报告与基线逐字相同，因此沿用基线 final-tests.log 的94/94，不冒称重新执行。当前 npm run check exit0（tools-002/check.log）；实际编译另由PNG/AX证明。源码/config精确哈希见 tested-source.json，交付哈希见 manifest.json，验证结论见 verification.json。

变更范围：用户/工具生成的 project.config.json、README当前状态、CP5报告与原生证据。AppID非凭证；报告不包含AppSecret、token、登录code、回执或真实会员数据。无preview/upload/部署/发布，无CP6。历史工程报告及CLI失败证据保留在 prior-* 与原日志中，不能把旧阻塞当当前状态。最终提交号与clean结果由交接消息给出。
