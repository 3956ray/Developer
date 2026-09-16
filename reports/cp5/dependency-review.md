# 依赖与边界

没有新增npm包、下载、安装或未经审查执行。Node 24.18.0及内置SQLite/crypto/Intl等沿用已验收来源。已安装官方微信开发者工具仅运行帮助、open和本地engine build初始化诊断，未改变其安全设置/账号/安装文件。没有执行preview/upload。

新增cleanup-runners只组合现有三执行器；受控时钟/计时器仅test环境允许，正常入口不注入。测试数据由私有.runtime生成并在关闭进程/计时器/数据库后清理。产品日志和报告不记录完整token、配对码、回执或原会员引用。
