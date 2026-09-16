# thinkV2 备份格式 v2（显式兼容 v1）

扩展名 `.thinkbackup.json`；单个严格 UTF-8 JSON。写入期间目标必须有 `未完成-` 前缀（保留JSON扩展名以兼容提供者），关闭输出并读回校验成功后才改正式名。不支持必要改名的提供者将明确失败。完成名不得再含开头的未完成标记；支持提供者在JSON扩展名前添加唯一编号。文件包含私人明文；base64不是加密，SHA256不是来源认证。

## 封套

必填且只允许以下字段：

| 字段 | 类型与语义 |
|---|---|
| format | 字符串 `thinkV2-backup` |
| schemaVersion | 导出整数 `2`；读取仅接受 `1` 或 `2` |
| exportId | 稳定导出 ID |
| createdAtUTC | `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`，有效UTC时刻 |
| payload | 标准base64，解码结果是UTF-8 JSON原始字节 |
| payloadBytes | 解码后的精确字节数 |
| payloadSha256 | 解码原始字节的SHA256，64字符小写十六进制 |

键序和空白不参与重排；SHA基于原始payload字节。浮点数、重复对象键、非法UTF-8/代理项、非ASCII十六进制Unicode转义、尾随数据、未知字段或版本均拒绝。严格base64校验包含padding及未使用位，不接受空白或URL-safe变体。

## payload

v2必填数组：`notes`、`drafts`、`categories`、`reminders`、`origins`、`relations`、`vocabulary`、`calendar`、`ai`、`receipts`。每个层级仅允许下列精确字段，缺失或多出字段拒绝。

v1仅允许前六个数组且relations必须为空。新读取器明确标出v1缺失词表、日历来源、AI接受来源和关系，保留本机这些数据；不能补回原文件未包含的信息。v1文件不能携带v2新增字段。冻结的已发布v1读取器会在schemaVersion检查拒绝v2，不静默丢字段。

- 通用record字段：`id, body, title, titleMode, categoryId, categoryName, categorySource, created, updated, revision`。`titleMode`为AUTO/MANUAL；categorySource为auto/manual。正文原样保存，不执行HTML或命令。
- notes：record字段加`deletedAt`（0为正常，正值为回收站）。正式笔记正文不可全空白。
- drafts：`record, baseRevision, active`。包含独有草稿及inactive水位；无正式记录时baseRevision=-1，否则必须等于正式版本。内部inactive-only水位会恢复，但不当作可打开记录展示。
- categories：`id, name, revision, active`。保留已删除分类墓碑，使回收站及其草稿引用完整。不同ID不会因同名自动合并。
- reminders：`id, noteId, revision, repeat, start, hour, minute, weekdays, enabled`。repeat为ONCE/DAILY/WEEKLY；weekday位0为周一、位6为周日，每周至少一位。只存规则，不含PendingIntent、通知ID或触发日志。恢复时enabled统一关闭、调度状态DISABLED、游标为空。
- origins：`recordId, exportId, sourceId`。标记备份冲突副本；不修改原始标题或标题所有权。来源ID是出处信息，recordId必须指向本文件记录。

ID为1–128个ASCII字母/数字及`._:-`，首字符须为字母或数字。笔记/草稿共用记录ID，数组内不得重复。时间为0至9999年末毫秒值；允许设备时钟回拨造成updated早于created。revision非负并预留1024增长空间。分类名非空、最多80码点。提醒引用必须是正式笔记；回收站规则不得启用。

## 保护上限

- 整个文件64MiB；解码payload32MiB。
- 笔记与草稿的联合记录ID最多10000，分类、提醒、来源数组各最多10000。
- 关系数组上限100000；日历、AI接受来源、回执分别最多10000；词表最多200项。
- 日历原始payload每条最多128KiB；所有大正文、来源快照和AI文本读取前检查总字节，最终编码仍受32MiB总上限。
- JSON层级32、对象最多64键、键名最多128字符、总值节点最多1000000。
- 超限拒绝整份文件，不截断。输入字节与base64解码流均在读取过程中限额。

## 合并与幂等

预览不写入。比较整个记录组合（正式版本、草稿、分类映射、提醒配置、备份来源、日历来源、全部已接受AI来源），不只比较正文。默认保留本机并给冲突副本分配新ID，所有依赖引用跟随映射。同名分类冲突创建独立分类并标“恢复副本”；名称按码点截断。

可以选择“跳过全部冲突项及其依赖”：跳过冲突分类时，依赖它的备份记录也跳过，避免误接本机分类。预览列出实际行为。确认后同exportId+payloadHash被记为已处理，再导入不会补入本次跳过项；同exportId不同hash拒绝。

确认时在单一SQLite事务内重核本机指纹，覆盖所有导出持久字段（包括词表、关系、日历、AI来源与回执）和完整提醒行；变化则要求重新预览。插入、索引字段、引用、来源和导入回执一起提交或回滚。现有本机记录/提醒不覆盖。导入代码不调用AlarmManager或NotificationManager。

## v2 新增字段及语义

| 数组 | 精确字段 | 校验与恢复 |
|---|---|---|
| vocabulary | `from,to` | 非空、不同、各最多80个UTF-16单元、不含控制字符、完整纠错对唯一；保留顺序。 |
| relations | `id,a,b,source,created` | a<b，source固定manual，created>0；端点必须是本文件正式笔记，可处于回收站；边ID和无向端点对各唯一。 |
| calendar | `sourceKey,fingerprint,noteId,originalPayload,originalText,importedNoteHash` | 三个hash为64位小写SHA256，noteId唯一且指向正式笔记；payload必须是原始calendar/event/reminders结构（字段值字符串或null），按已实现日历契约重算sourceKey与fingerprint并检查规范payload。originalText是当时生成的不可变文字，不按新设备时区规则重写。 |
| ai | `id,requestId,recordId,field,endpoint,model,inputHash,proposed,chosen,acceptedAt,noteRevision` | ID唯一，requestId/field/recordId组合唯一；recordId必须是正式记录或草稿，field为title/category，inputHash为SHA256；endpoint为无用户信息、query/fragment的HTTPS来源地址。proposed/chosen为已接受操作的不可变来源字符串，可能包含原候选分类ID或名称，**不是当前分类外键**。它们不会生成配置、网络请求或分类。 |
| receipts | `exportId,hash,importedAt` | exportId唯一，hash为SHA256。回执表示文件已经处理，可能包含主动跳过项，不代表文件每一行都已恢复。保留原导入回执，避免换设备后重复导入已处理文件；与本机同ID不同hash冲突，整单拒绝，不覆盖。当前文件不能自称已导入。 |

AI配置、凭据、启用状态、未接受结果、原始音频、运行库/模型、日志、缓存及通知运行状态均不在字段白名单中。AI endpoint/model仅为已接受操作的出处，不能据此恢复可用服务配置。已有凭据仍由本机Keystore密文独立保管，绝不参与备份；恢复成功同步清除内存中的启用/同意/建议，请求被取消，重新启用须本机确认。新进程原本就默认关闭。

### 词表事务所有权

SQLite schema7→8在一个数据库事务内读取旧voice-corrections SharedPreferences、校验完整词表、写入correction_vocabulary，并重建来源唯一约束。任何步骤失败回滚schema和数据，旧preferences原文保留。提交后只读写SQLite；旧preferences是惰性遗留副本，不会再次覆盖数据库，也不导出。词表编辑用旧列表作比较，过期编辑不能覆盖已恢复词表。

导出在同一SQLite事务中读取所有字段。恢复按本机顺序保留已有纠错对，再依文件顺序追加不同的完整(from,to)对；同一from的不同to均保留为人工确认建议。超过200项整单拒绝，先整理再预览，不截断。恢复后内存词表重新读取。

### 来源与关系冲突

- 日历来源与AI来源参与记录组合比较，来源不同也产生记录冲突。新ID记录与其草稿/提醒/来源同步映射；SKIP不把来源接到本机冲突记录。
- schema8日历note_id仍唯一，主键为source_key/fingerprint/note_id；一个源版本可有多个**明确恢复的冲突副本**。相同源版本再次日历导入仍直接跳过；源变化仍要求显式另建，不更新原笔记。importedNoteHash保留原始值，副本ID变化可能使localChanged保守为true。原始payload/text/sourceKey/fingerprint保持不变。
- AI接受ID冲突另分配ID，recordId跟随记录映射；原requestId、proposed/chosen、endpoint/model、时间和版本作为来源原样保留。唯一约束扩至requestId/field/recordId以容纳已确认副本。
- 关系端点跟随完整记录映射并重新排序。任一端点SKIP则关系SKIP。若目标端点对已存在，保留本机边ID与属性，预览映射到该边；若边ID碰撞但目标端点不同，COPY模式生成新边ID，SKIP模式跳过。不会把两个相同端点对插成双边。预览记录实际映射与计数。
- 回收站中的合法关系需要恢复：在同一事务中暂移除仅针对新建操作的active-endpoint触发器，插入已校验的正式笔记端点边，再恢复相同触发器SQL后提交。其他连接看不到中间状态；故障测试核对DDL和数据一起回滚。关系表CHECK/唯一约束始终保留。
- 新导入提醒disabled，不调度OS通知；SAME跳过的既有本机计划保持原样。未来任何字段变化须新版本契约，不能在v2静默扩展。
