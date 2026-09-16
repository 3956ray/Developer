# CP2 人工观察实现说明

合同GYM-CP2-OBSERVATION-001；继承CP1身份基础和已评审CP0技术契约。未改变冻结PRD，也未实现CP3及以后业务。

## API与事务

- GET /v1/venue：公共gym空资料与observation快照；无真实资料不填假值。GET /v1/observations/current：同一当前快照。读取时捕获的serverNow与状态判定一致，HTTP层不换成另一时刻。
- POST /v1/operator/observations：W + level=quiet/moderate/busy + observedJustNow=true。发布intent最多60秒、未来最多30秒；首次expectedRevision=absent，之后为head版本。
- POST /v1/operator/observations/control：W + state=unknown/paused/withdrawn + reasonCategory枚举；普通5分钟intent，不生成新鲜等级。
- GET /v1/operations/:key?type=observation.publish|observation.control：当前主体作用域；先验证当前operator，再返回历史提交事实或unknown，不能用旧角色读缓存。

写入在BEGIN IMMEDIATE后取得服务时间及当前session/role；CAS、head、event、audit、幂等和session.lastInteractiveAt同事务。失败全部回滚；成功幂等重放不再写审计/续TTL/续闲置。同key不同body409，冲突不能覆盖；其他主体同key不是同一scope。角色撤销之前已提交保留，撤销提交后未提交写拒绝。

003迁移增加observation_head、observation_events、observation_cleanup。head不含actor且自足，不依赖MAX历史挑选当前；控制/撤回也递增head版本并清空当前等级时间。events含必要业务actor用于历史追溯。没有人数、百分比、等待估算、门禁、课程或会员领域字段。未知请求字段（含simulation/numeric occupancy）拒绝，store配置沿用原有隔离和模拟禁用。

## 有效性与客户端

publishedAt=observedAt=发布事务的服务端时刻，validUntil=+900秒，now≥validUntil即过期。未来/非法/不一致时间返回unavailable，并输出固定后台错误类别，不把异常改成较空。GET、重新登录、轮询、重启和清理都不写head。过期只把旧等级放弱化历史字段。

原生场馆、维护页共享observation-page/view。文字表达全部状态；空、过期、暂停、撤回、离线/错误与有效记录不同。页面首次/前台/手动刷新及60秒轮询，隐藏停止，旧请求结果按generation失效，未完成请求结束前只排队一次。每秒只在本地重算剩余有效时间。

可信时间为response.serverNow加请求单调耗时（保守计算，不因网络延迟增加有效时长）及后续单调经过时间。使用wx.getPerformance().now；缺能力、返回无效或倒退就丢弃基准，绝不使用Date.now续鲜。重启缓存只有原快照，不持久化可信时钟；离线显示历史。时间格式优先店IANA时区，若Intl能力不可用明确回退UTC标签；实际基础库兼容性留工具/真机层验证。

维护页先检查角色，发请求前取得当前本人业务ID用于限定待确认操作。现场确认弹窗打开时冻结所见revision，避免后台轮询悄悄换版本。intent保存到私有缓存成功后才发送；只保存userId和业务操作字段，不另存token或微信身份。超时只能查询原操作/重发相同请求；超过窗口要求重新巡视/确认。历史committed只提示事实并重新GET当前状态，不直接用旧写响应恢复等级。已知冲突/校验失败明确提示重新确认，权限失效禁止继续。

## 可恢复的观察清理

仅删除publishedAt满30天的观察events；当前head始终保留，包括已撤回和很旧观察的原始时间。每批最多100，删除和cursor/deleted_total更新在同一事务；下一批让出事件循环。当前轮cutoff固定，失败保留已提交游标，未提交批次回滚；错误状态和计数单独持久化。启动恢复running/failed任务，正常完成后next_run_at=完成时间+1小时。

调度使用下一到期时间的单次timeout，不使用可能错过next_run_at的固定interval。测试让每批耗时5秒，证明201条分3批后下一个小时仍实际运行；失败下个小时重试。DB本身不可用时只能输出固定错误类别，恢复后从持久进度补跑，不假报成功。

CLI status/run读取同一配置和DB，run用于受控维护者补跑。不实现其他领域清理、任务管理UI、备份或部署。当前无应用自动备份，文件系统快照限制沿用CP0。

## 验证层

既有CP0/CP1回归保留。CP2包含真实HTTP双读客户端、整服务进程重启、两位operator的独立进程CAS/幂等竞争与撤销屏障；数据库批次故障/重开恢复；native源码VM执行状态、时间和交互测试。所有数据合成；没有实际微信编译、AppID、真机或现场观察成功声明。
