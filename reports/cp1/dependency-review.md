# CP1 依赖、平台适配器与fixture边界

无新增第三方依赖、下载、install脚本或SDK。沿用已验收Node24.18.0、SQLite3.53.1；内置http/crypto/test/vm/child_process/fs足够。node:sqlite的release-candidate及本机二进制发行签名未独立核验限制沿用CP0，不扩大到公网/托管许可。

本次尝试读取微信官方code2Session、wx.login和登录流程文档，web工具均返回无法打开；没有用第三方镜像作为权威技术来源。补读PM已核验的官方证据`/Users/orderly_ray/Documents/Products Manager/product-knowledge-base/raw/SRC-20260916-gym-wechat-01.md`，来源[官方登录流程](https://developers.weixin.qq.com/miniprogram/dev/framework/open-ability/login.html)，确认临时code一次使用、服务端建立业务态和session_key不下发。固定[code2Session官方地址](https://developers.weixin.qq.com/miniprogram/dev/OpenApiDoc/user-login/code2Session.html)的实际平台响应本轮未验证。

适配器只请求https://api.weixin.qq.com/sns/jscode2session，服务端配置AppID/AppSecret，不接受客户端自报openid。拒绝重定向，5秒超时，响应上限16KiB；HTTP错误/无效结构/非零平台错误不创建身份；40029/40163/40226归为code不可用，其余平台失败503。严格检查openid/session_key存在和格式，只返回openid给内部身份事务，session_key/unionid不持久化。transport测试检验方法/路径/参数与错误行为，未真正发送官方交换请求。网络异常和平台文本全部转为固定错误，不传播带秘密URL。

官方适配器fixture由本项目测试自写：合成平台ID、16字节合成session_key与错误JSON，仅测试内存；不是真实凭证。业务替身fixture单独以HMAC(code)+synthetic_subject+outcome保存到UUID临时test DB；test mode需environment=test且simulation=true，store拒绝；命名空间禁止切模式或AppID继承旧身份。测试时钟与transport仅test允许注入，不存在HTTP注入入口。

原生JS由Node VM加载真实页面源码，wx API、网络与时间回调由测试mock；该证据只说明客户端逻辑，不代表真实基础库支持getRandomValues等能力。缺安全随机时退出写请求失败关闭。没有真实AppID/Secret、openid、会员资料、微信工具编译、预览上传或真机。代码无姓名/手机号/头像/位置/人脸采集调用。

已检查完整token仅返回一次并私有缓存，服务DB存摘要；code只存带键摘要。HTTP/CLI日志不记录body、Authorization、完整token或平台原始响应。reports只留测试名称、统计、非会员探针与合成门店信息，运行目录忽略。
