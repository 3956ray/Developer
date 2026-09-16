# CP5-D2 分层验收

全部业务数据/身份合成，HTTP/SQLite真实执行。测试前置为独占demo-*目录与有效本scope会话，operator由维护者显式授予。输入/构建/配置摘要见主报告和manifest。

|AC|D2工程|实际结果|证据（test/或本目录）|
|---|---|---|---|
|AC-DM01|NOT_RUN|D1 accepted or D3 future; not claimed newly completed in D2|D1 historical evidence / future D3|
|AC-DM02|NOT_RUN|D1 accepted or D3 future; not claimed newly completed in D2|D1 historical evidence / future D3|
|AC-DM03|PASS|member scenario bind/revoke/restore, explicit member-b409, unbind/delete/restart/new alias session; revoked registry and no role/binding resurrection|demo-runner.test.mjs + D1 deletion regressions|
|AC-DM04|NOT_RUN|D1 accepted or D3 future; not claimed newly completed in D2|D1 historical evidence / future D3|
|AC-DM05|PASS|finite crowd play (controlled wait), one live runner including waits, 401/403/429 pause, actor match, manual conflict, original operation recovery after actual SIGKILL/service restart, aged unknown needs_review|demo-runner.test.mjs; original 899/900 observation tests|
|AC-DM06|PASS|real HTTP membership and draft/publish/cancel/reschedule/withdraw; stable IDs, public isolation, explicit empty/outside, demo labels added (source/VM only)|demo-runner.test.mjs; native-membership/schedule regression|
|AC-DM07|PASS|source ownership/registration, read-only plan, confirmed atomic draft apply, independent publish, stable mapping after omissions/reappearance|schedule-import.test.mjs; import-process.test.mjs|
|AC-DM08|PASS|strict unknown fields/size/duplicate/offset/DST/coverage/overlap, 200 input and cancellation union limit, safe row error, full omission cancellation|schedule-import.test.mjs; schedules.test.mjs|
|AC-DM09|PASS|same hash replay, lower/changed version reject, draft/publication CAS, rollback, expired candidate replacement, immutable provenance, process-lost response, lock503, retained mapping/cursor, shared100-row cleanup|schedule-import.test.mjs; import-process.test.mjs; demo-cleanup.test.mjs|
|AC-DM10|NOT_RUN|D1 accepted or D3 future; not claimed newly completed in D2|D1 historical evidence / future D3|
|AC-DM11|NOT_RUN|D1 accepted or D3 future; not claimed newly completed in D2|D1 historical evidence / future D3|
|AC-DM12|PASS|new migration007, old migrations/docs/reports protected, final130 tests/static/hash, data dictionary/examples/CLI and evidence separation|final-tests.log; check.log; verification.json|
|DM09-RP1|PASS|explicit stale replacement changes only candidates; original source revision retained|schedule-import.test.mjs; import-process.test.mjs|
|DM09-RP2|PASS|old batch permanently rejected; current candidate cannot refresh TTL|schedule-import.test.mjs; import-process.test.mjs|
|DM09-RP3|PASS|apply-first returns original; two real replan processes one successor|schedule-import.test.mjs; import-process.test.mjs|
|DM09-RP4|PASS|confirmed local version change409 and injected rollback preserve old candidate; new candidate CAS|schedule-import.test.mjs; import-process.test.mjs|
|DM09-RP5|PASS|ordinary unexpired plan reuses candidate; explicit branch preserves high-water/hash protection|schedule-import.test.mjs; import-process.test.mjs|

D3原生联网/自然900秒等待/截图均NOT_RUN；play工程测试等待受控，不能声称实际等了900秒。旧30AC与D1证据保留，130全套包含既有回归；官方身份/真机/门店不升级为PASS。
