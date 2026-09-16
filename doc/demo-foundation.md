# CP5-D1：隔离演示身份与一条持久观察

本阶段只完成演示基础，不含场景运行器、课表JSON导入或原生联网验收。v1.1四份增量在 `doc/baseline/`；v1.0原文和001–005迁移保持。所有示例仅合成demo数据；默认客户端仍未配置。

## 存储和身份边界

- `.runtime/test/demo-<name>/config.json`：必须同时 `environment=test`、`simulation=true`、`identity={mode:test,appId:test-app}`、`demo={enabled:true}`；既有四把独立密钥和SQLite同目录。目录0700、密钥/配置0600。store或任一不匹配启动拒绝。初始化只允许新目录，重启不运行init。
- 新迁移006只加 `demo_login_tickets(ticket_hmac,subject_hmac,issued_at,expires_at)` 与到期索引。32随机字节base64url仅CLI stdout输出一次；数据库只留scope绑定HMAC和时刻，不存明文票据/alias。主体HMAC与账号映射稳定，但不是会员资格或角色。
- `POST /v1/sessions/exchange` 保留原body字段 `code/privacyNoticeVersion/consent`。客户端不可选actor/role。先校验字段、用途同意和格式，再持久120/min限流，再同一事务写login_codes并删除凭证行完成消费。未同意/前置限流不消费；登记尝试成功后，即使建session失败也不能再用。300000毫秒边界拒绝；消费删除和到期检查使旧尝试台账清理后也无法复活。
- test演示适配器只接受上述事务消费的主体，不读取旧test_login_fixtures。未启用demo的自动测试fixture分支保留；官方分支不回退演示。
- 会话沿用7天绝对/24小时空闲/最多5个、5分钟敏感认证、退出/撤销、审计与删除。新票据刷新认证时刻，不提升role，不重置限流。operator-a只是合成主体别名，首次登录也是普通用户；只有现有role CLI显式grant才有权限。
- 删除中拒绝同主体重建；清理完成后同alias创建新userId，无原会员关联/operator。会员撤销登记仍按原规则保留。D2再做完整动态会员场景。
- 到期票据加入既有membership_cleanup最后一个保留阶段，每批100，启动/小时调度与失败重试均沿用。失败时到期票据仍不可交换；下一小时重试物理清理。
- 非demo且未显式限定identityMode的无令牌公共GET允许disabled/test/wechat身份元信息，身份未配置不封锁场馆/课表；demo仍要求全部元信息一致，带令牌和身份交换仍严格匹配。
- HTTP成功/失败信封新增 `namespace={environment,gymId,simulation,identityMode,demoEnabled}`；health/session也含相应元信息。原生demo先health核对，随后每个请求核对；不匹配清会话/待写缓存并关闭动作。session、观察缓存、待写和删除回执scope统一含baseUrl/environment/gymId/identityMode/demo标记，旧scope不复用。

## 初始化、启动、停止和重启

在仓库根执行（Node24.18.0，无npm安装）。先检查8787是否被占用；冲突时在**本demo配置**选择空闲端口并同步客户端，不停止未知进程。

```sh
node scripts/demo.mjs init demo-local
node scripts/db.mjs migrate .runtime/test/demo-local/config.json
node server/main.mjs .runtime/test/demo-local/config.json
```

另一个终端：

```sh
curl --fail http://127.0.0.1:8787/health
node scripts/demo.mjs ticket .runtime/test/demo-local/config.json member-a
```

允许alias仅member-a/member-b/operator-a。凭证300秒有效，不截屏/记录终端输出；拒绝登录不消费。丢失交换响应后领新票，不能重试旧票。服务终端Ctrl-C停止，再用同一条`server/main.mjs`命令启动；不得再次init、更换DB或重新grant来“恢复”。重复init明确失败，不改现有目录。

## 可执行HTTP烟测（不需域名开关）

以下测试创建自己独占的随机demo目录、自动选端口并销毁自有fixture，使用实际CLI票据、实际role CLI、独立HTTP客户端和真实服务进程；不修改上面的demo-local。

```sh
node --test test/demo-http.test.mjs
```

预期：两个HTTP客户端取得独立业务会话；operator未grant为403；显式grant后只发布一条quiet；两客户端同revision/observedAt/validUntil；同key重放不多写、异body409、普通用户403；停服请求失败；重启读同数据；撤销后领新票和重启仍403。第二例两个实际服务进程竞争同票，最多一个201，并核对持久限流。

## 手动HTTP登录与授权（票据不进命令行）

在第二终端执行；票据由管道进入stdin，业务令牌只存本demo目录0600临时文件，stdout仅非秘密摘要。未启用shell命令追踪，不把该文件/有效凭证加入Git或报告。

```sh
node scripts/demo.mjs ticket .runtime/test/demo-local/config.json operator-a | node --input-type=module -e '
import {readFileSync,writeFileSync} from "node:fs";
const code=readFileSync(0,"utf8").trim();
const config=JSON.parse(readFileSync(".runtime/test/demo-local/config.json"));
const r=await fetch(`http://127.0.0.1:${config.port}/v1/sessions/exchange`,{
 method:"POST",headers:{"content-type":"application/json"},
 body:JSON.stringify({code,privacyNoticeVersion:"cp3-purpose-v1",consent:true})});
const b=await r.json();if(!r.ok||!b.ok)throw new Error(b.error?.code||"UNCONFIRMED");
writeFileSync(config.stateDir+"/operator-session.json",JSON.stringify(b.data),{mode:0o600});
console.log({userId:b.data.userId,identityMode:b.data.identityMode,simulation:b.data.simulation});'
```

首次grant前，先用上一步userId查当前role revision：

```sh
node scripts/role.mjs inspect .runtime/test/demo-local/config.json <userId> demo-local
```

由维护者新建 `.runtime/test/demo-local/role-request.json`（0600）：

```json
{"action":"grant","userId":"<上步userId>","gymId":"demo-local","maintainerId":"demo-maintainer","reasonCategory":"initial-authorization","ownerConfirmed":true,"operationId":"<新UUID>","requestCreatedAt":"<当前UTC ISO时间>","expectedRevision":"absent"}
```

这是占位模板，不是可原样提交的请求。expectedRevision必须使用inspect实际值，已有撤销不得用模板偷偷恢复；重新授权需要新的明确确认。执行和结果查询沿用既有命令：

```sh
node scripts/role.mjs apply .runtime/test/demo-local/config.json .runtime/test/demo-local/role-request.json
node scripts/role.mjs result .runtime/test/demo-local/config.json demo-maintainer <operationId> role.grant
```

发布一条观察的HTTP示例（从本地0600会话文件读取token，不写命令行/日志）：

```sh
node --input-type=module -e '
import {readFileSync,writeFileSync} from "node:fs";import {randomUUID} from "node:crypto";
const c=JSON.parse(readFileSync(".runtime/test/demo-local/config.json"));
const s=JSON.parse(readFileSync(c.stateDir+"/operator-session.json"));
const base=`http://127.0.0.1:${c.port}`;
const snapshot=await (await fetch(base+"/v1/observations/current")).json();
if(!snapshot.ok)throw new Error("READ_UNCONFIRMED");
const body={operationId:randomUUID(),requestCreatedAt:snapshot.serverNow,
 expectedRevision:snapshot.data.revision,level:"quiet",observedJustNow:true};
writeFileSync(c.stateDir+"/observation-request.json",JSON.stringify(body),{flag:"wx",mode:0o600});
const r=await fetch(base+"/v1/operator/observations",{method:"POST",
 headers:{"content-type":"application/json",authorization:"Bearer "+s.token},body:JSON.stringify(body)});
const b=await r.json();console.log({status:r.status,ok:b.ok,operation:b.operation,error:b.error});'
curl --fail http://127.0.0.1:8787/v1/observations/current
```

示例只做一次请求，不自动重试；先独占保存observation-request.json，已有文件时拒绝生成新写入。结果确认前保留此文件，不能直接删文件换新key。写入结果不明必须保留原operationId/请求并查询原操作；可复现的响应丢失、重放与冲突测试见烟测和原幂等测试。不将此片段当作D2恢复运行器。使用完删除本demo下自建临时session文件；业务DB保留供重启验证。

## 原生入口（本阶段仅VM验证）

后续D3使用的显式客户端配置示意：

```js
module.exports = {
 baseUrl: 'http://127.0.0.1:8787', environment: 'test', gymId: 'demo-local',
 identityMode: 'test', demo: { enabled: true }
};
```

默认仓库不启用。我的页输入掩码凭证，阅读演示用途后同意；拒绝不请求。hide/拒绝/交换前清输入，凭证不放page.data或本地缓存；重新认证需领新票。demo不调用wx.login；官方失败不切demo。元信息不符关闭操作并清会话，修正配置后重新加载小程序再主动登录。

D1不修改urlCheck、不执行原生网络业务、不使用AppSecret或部署。D3需另行获得本项目组合安全开关的明确授权或批准HTTPS方案。工程HTTP通过不是wx.request、官方登录、真机或门店证据。
