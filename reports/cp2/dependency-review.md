# CP2 依赖与环境边界

无新增第三方依赖、下载或安装脚本。沿用已验收Node24.18.0、内置SQLite3.53.1及http/crypto/test/vm/child_process；运行时来源/许可与release-candidate风险见CP0依赖报告。新增代码均为本任务自写。

数据库只增加一份可校验迁移，参数绑定、当前角色/版本检查、事务审计沿用CP1；公共快照不含actor/openid/token。测试事件、operator、code与业务token均合成，私有临时fixture在.runtime并不入Git。维护页待确认缓存只存业务userId和请求体，无完整token副本；操作结果查询仍须当前服务端鉴权。

新增原生时钟使用wx.getPerformance().now，时区格式使用Intl.DateTimeFormat；本轮只以VM/Node能力验证逻辑，真实微信基础库兼容性未验证。缺单调时钟时不显示新鲜，缺Intl时使用明确UTC标签。没有用电脑墙钟替代可信时间。

使用本机临时HTTP端口执行测试，已通过权限审查；无外部微信调用、预览上传、云服务、门店或真实会员数据。无真实馆名、营业/联系电话、观察点与人员来源，均保留未配置。未实施全量CVE/部署安全审计，既有运行时签名/OS快照限制继续有效，不据本次通过声称真实平台或全副本删除通过。
