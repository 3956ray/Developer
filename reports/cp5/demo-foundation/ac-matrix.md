# D1 逐AC结果

所有新增数据均合成；D1工程含原生VM逻辑，非微信工具端到端。场景前置为独占demo测试目录，完整构建/配置hash见verification与source/manifest；实际命令见主报告。DM03仅本阶段基础；DM12仅本阶段完整性。

|AC|D1工程|完整演示|实际结果/范围|证据（test/或本报告目录）|
|---|---|---|---|---|
|AC-DM01|PASS|PARTIAL|新目录/重复保护、store/配置/namespace拒绝；真实disabled公共浏览兼容|demo-foundation.test.mjs; demo-http.test.mjs; native-demo.test.mjs|
|AC-DM02|PASS|PARTIAL|300秒/重放/并发/尝试消费/同意及前置限流/失败不可重用/小时清理/新鲜认证；VM无wx.login和拒绝不请求|demo-foundation.test.mjs; demo-http.test.mjs; native-demo.test.mjs|
|AC-DM03|PASS|PARTIAL|角色基础：首次无权、CLI grant、revoke后重发票及重启仍403；删除中拒绝，完成后新ID无角色/关联；完整动态场景留D2|demo-http.test.mjs; demo-foundation.test.mjs; existing membership/deletion regression|
|AC-DM04|PASS|PARTIAL|两个真实HTTP客户端同一观察，实际进程重启不续TTL，同key重放/异body409/普通403/停服失败|demo-http.test.mjs|
|AC-DM05|NOT_RUN|NOT_RUN|本阶段范围外，D2或D3另派|none|
|AC-DM06|NOT_RUN|NOT_RUN|本阶段范围外，D2或D3另派|none|
|AC-DM07|NOT_RUN|NOT_RUN|本阶段范围外，D2或D3另派|none|
|AC-DM08|NOT_RUN|NOT_RUN|本阶段范围外，D2或D3另派|none|
|AC-DM09|NOT_RUN|NOT_RUN|本阶段范围外，D2或D3另派|none|
|AC-DM10|NOT_RUN|NOT_RUN|本阶段范围外，D2或D3另派|none|
|AC-DM11|NOT_RUN|NOT_RUN|本阶段范围外，D2或D3另派|none|
|AC-DM12|PASS|PARTIAL|本阶段新迁移+五迁移旧库升级、冻结hash、110回归、操作说明及最终manifest；完整演示留D2/D3|demo-foundation.test.mjs; final-tests.log; check.log; verification.json|

所有原生业务工具/官方身份/真机/门店层NOT_RUN；旧CP5截图不作为新构建证据。v1.0的30AC不删除，原有工程用110测试回归，L01/W01/T01/T02真实层不升级。
