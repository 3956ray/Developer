# 实际微信工具层：BLOCKED

技能：/Users/orderly_ray/.codex/skills/miniapp-devtools-cli-repair/SKILL.md，已读playbook。用户合同明确禁止preview/upload，所以没有运行技能默认推荐的preview；使用已安装CLI帮助、open及本地engine build诊断，不把静态检查或Node VM称编译。

## 观测与实际命令

CLI `/Applications/wechatwebdevtools.app/Contents/MacOS/cli`；目标项目始终 `/Users/orderly_ray/Projects/gym-miniapp`。

| 命令/操作 | 实际结果 | 证据 |
|---|---|---|
| cli --help；open/auto/agent/engine/engine build --help | exit0，能力帮助可读取；有Electron签名诊断行，不能仅据该行判断IDE不可用 | cli-*-help.log、cli-help.log |
| cli open --project 目标目录（sandbox） | exit255：listen EPERM 127.0.0.1:3799 | cli-open.log |
| 同一open在已审批宿主执行 | exit246：IDE service port disabled | cli-open-host.log |
| cli engine build 目标目录 --logPath reports/cp5/engine-build-detail.log（宿主） | exit246，同样在初始化被端口关闭阻塞；没有build详细文件或成功产物 | cli-build.log |
| CUA读取已安装官方工具窗口 | 真实工具标题为Stable 2.02.2608070；当前窗口属于非本项目，未操作其代码 | 本次会话原生AX观测；摘要tool-version.json |
| GUI项目菜单→导入→选择本目标绝对路径 | 系统路径面板可显示目标路径，但键盘确认/粘贴/选择未可靠推进；未确认导入或编译 | 本次会话CUA观测；无成功截图 |

首次CUA读取耗时约219秒，其后操作没有建立目标项目窗口。停止重复无变化尝试；尝试退出路径框也未确认关闭成功，宿主可能仍保留导入对话框。没有关闭或修改原来打开的其他项目，没有复制其AppID、代码或配置。未将无关项目界面截图当目标证据。

Info.plist显示36.6.0是Electron外壳版本，不能作为微信工具产品版本。实际目标基础库版本未取得，填UNKNOWN；不能从其他项目基础库推断。本项目appid仍touristappid、urlCheck=true、客户端baseUrl/environment未配置；未使用其他项目AppID或真实登录身份。

## 阻塞与责任方

1. **已实证阻塞**：工具安全设置中的服务端口关闭，官方CLI无法连接。最小复现是上述open，输出exit246。由用户/工具管理员确认并配置受控CLI能力（如需要token由其安全配置），或人工完成目标项目导入及本地编译；不默认扩大IDE安全能力。
2. **GUI自动化路径受阻**：当前CUA对系统文件选择框输入/确认不可靠，目标导入未成功。由宿主操作员将唯一目标目录导入并保留可定位界面后，可再继续本地编译与原生交互；禁止借另一个项目的编译结果替代。
3. **后续平台条件待核实**：当前占位touristappid的登录/基础库/模拟器能力尚未验证。受控测试AppID、官方工具账号权限及合法开发服务配置由项目负责人提供/确认。不能把该项猜测为已出现的账号报错；目前最先出现的错误是CLI端口关闭。

## 缺失证据

目标导入、实际编译、目标基础库、场馆/我的/维护端正常/空/失败/过期截图和模拟器交互全部BLOCKED/NOT_RUN。screenshots清单为空是证据缺口，不以HTML或VM图片补齐。没有preview二维码、上传、分发、部署、绕过域名验证或未审查依赖安装。
