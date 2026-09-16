# GYM-CP5-INTEGRATION-001

RESULT: BLOCKED

ENGINEERING: PASS；WECHAT_TOOL: BLOCKED；DEVICE/VENUE: NOT_RUN。CP5需工程与工具同时通过，目前不能完整通过。已停止在CP5，未进入CP6。

## 基线、范围和结果

项目 `/Users/orderly_ray/Projects/gym-miniapp`，基线e8c004e194b8a332c05c206927d3f196c67a33d9，开始clean。最终提交与提交后clean在交接消息给出；精确源码tested-source.json、交付manifest.json及verification.json。冻结三文档、CP0–CP4报告和旧迁移不改，无新增依赖/真实数据/部署/上传。

[30AC分层矩阵](ac-matrix.md)逐条列工程场景、测试文件/标题和工具/设备/门店状态。27条适用工程层PASS，W01/T01/T02留既定后续真实层NOT_RUN；不宣称30条产品验收全通过。全部Node/SQLite/实际进程/HTTP与原生VM测试通过同一最终源码；具体平台能力仍无证据。

## 缺陷与必要修复

1. **删除迟到响应误清新会话**：member-page发送删除后，旧响应finally曾无条件clearPersonal。若在另一页面新登录，会清掉新token/操作缓存。现在仅当缓存仍是该请求的token才清；迟到成功不以旧generation更新当前页面。新增真实控制器VM延迟响应测试，验证新token、新课表pending保留而旧删除receipt仍保留。
2. **隐藏后旧确认/角色回调继续动作**：My登录弹窗捕获generation，确认及wx.login返回后检查页面仍可见且generation一致；馆方入口角色返回同样检查，避免隐藏后登录/跳转。新增拒绝迟到确认与导航计数测试。
3. **完整清理接线证据**：server/main现调用cleanup-runners统一启动原观察/会员/课表执行器，生产参数与周期不变。受控时钟/计时器仅test允许；测试执行同一入口的实际SQLite清理，验证三个startup与hourly任务、一个域失败不阻另两个、下一小时恢复、停止后无挂起计时器。
4. **嵌套资源清理顺序**：测试beforeRemove回调改为LIFO，保证集成用runner先停止再关闭基础DB；仍先停止所有自有子进程，保留CP4失败清理保障。无产品测试断言削弱。

新增综合证据还覆盖课程/绑定/撤销/删除/清理不续观察TTL、不恢复revoked资格；24小时操作结果清理后旧intent即使用新会话也拒绝；一次安全随机碰撞成功重抽不影响另一用户请求。

## 文件

| 文件 | 作用 |
|---|---|
| miniprogram/lib/member-page.js | 删除响应与当前会话归属保护 |
| miniprogram/pages/me/index.js | 隐藏页面迟到确认/权限响应保护 |
| server/cleanup-runners.mjs、main.mjs | 三执行器同一生产接线，供受控时间集成验证 |
| test/integrated-lifecycle.test.mjs | 跨域TTL/资格、删除、小时失败恢复、24h旧intent、1次碰撞 |
| test/native-membership.test.mjs、native-session.test.mjs | 新会话保留、迟到导航/登录确认回归 |
| test/process-support.mjs | fixture附属资源按LIFO关闭 |
| README.md、AGENTS.md | 当前CP及真实工具阻塞边界 |
| reports/cp5 | 30AC矩阵、CLI真实输出、工具能力/阻塞、精确源码与结果 |

## 命令与证据

- `npm test`：exit0，94/94，final-tests.log（原87+新增7）。
- `npm run check`：exit0，final-check.log；JS/JSON/引用/冻结hash，不是微信编译。
- `node --test test/integrated-lifecycle.test.mjs test/native-session.test.mjs test/native-membership.test.mjs`：exit0，integration-tests.log，受控时钟和VM仅工程层。
- `git diff --check`与源码/交付哈希核对：通过，verification.json。
- CLI帮助exit0；sandbox open exit255 EPERM；宿主open和本地engine build均exit246端口关闭。没有伪造编译成功、基础库或目标页面截图，详见[工具状态](tool-status.md)。

## 工具层阻塞与复现

本机实际官方工具Stable 2.02.2608070可见；目标基础库UNKNOWN。CLI路径及完整命令/日志已记录。最小复现：`/Applications/wechatwebdevtools.app/Contents/MacOS/cli open --project /Users/orderly_ray/Projects/gym-miniapp`，宿主exit246明确IDE service port disabled。需要用户/工具管理员确认受控CLI能力，或人工将本目标导入并提供可继续操作的目标界面。本轮未自动改变安全设置。GUI系统选择框自动化未成功完成目标导入，可能仍停在该对话框；未操作其原来打开的非本项目内容。

本项目仍touristappid占位、urlCheck=true、前端服务unconfigured，没有擅用其他项目AppID/身份。实际测试AppID/官方工具账号权限和合法开发服务配置须负责人核实；尚未走到能证明账号拒绝或基础库兼容的阶段。CLI服务端口是已观测首要阻塞，账号/AppID是后续待确认条件。

未执行preview/upload；技能默认preview建议由明确合同禁令覆盖。没有真实目标工具截图：正常/空/失败/过期图像均未取得，不用浏览器HTML、VM或其他项目代替。缺失不是PASS。

## 数据和清理边界

合成身份/引用/码只在私有.runtime测试fixture，测试关闭所有自有服务/计时器/DB后删除。报告无完整token、配对码、删除回执或原会员引用。CLI/AX版本摘要不包含其他项目的AppID或路径。没有应用备份/遥测/云副本；不承诺操作系统/设备全副本擦除。实际官方登录、隐私配置、真机和现场资格均留后续授权验证。
