# thinkV2 备份格式 v1

扩展名 `.thinkbackup.json`；单个严格 UTF-8 JSON。写入期间目标必须有 `未完成-` 前缀（保留JSON扩展名以兼容提供者），关闭输出并读回校验成功后才改正式名。不支持必要改名的提供者将明确失败。完成名不得再含开头的未完成标记；支持提供者在JSON扩展名前添加唯一编号。文件包含私人明文；base64不是加密，SHA256不是来源认证。

## 封套

必填且只允许以下字段：

| 字段 | 类型与语义 |
|---|---|
| format | 字符串 `thinkV2-backup` |
| schemaVersion | 整数 `1` |
| exportId | 稳定导出 ID |
| createdAtUTC | `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`，有效UTC时刻 |
| payload | 标准base64，解码结果是UTF-8 JSON原始字节 |
| payloadBytes | 解码后的精确字节数 |
| payloadSha256 | 解码原始字节的SHA256，64字符小写十六进制 |

键序和空白不参与重排；SHA基于原始payload字节。浮点数、重复对象键、非法UTF-8/代理项、非ASCII十六进制Unicode转义、尾随数据、未知字段或版本均拒绝。严格base64校验包含padding及未使用位，不接受空白或URL-safe变体。

## payload

必填数组：`notes`、`drafts`、`categories`、`reminders`、`origins`、`relations`。当前未实现关系，`relations`必须为空；非空关系明确拒绝，不静默删除。

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
- 关系数组上限100000，但本版本只接受空数组。
- JSON层级32、对象最多64键、键名最多128字符、总值节点最多1000000。
- 超限拒绝整份文件，不截断。输入字节与base64解码流均在读取过程中限额。

## 合并与幂等

预览不写入。比较整个记录组合（正式版本、草稿、分类映射、提醒配置、来源），不只比较正文。默认保留本机并给冲突副本分配新ID，所有依赖引用跟随映射。同名分类冲突创建独立分类并标“恢复副本”；名称按码点截断。

可以选择“跳过全部冲突项及其依赖”：跳过冲突分类时，依赖它的备份记录也跳过，避免误接本机分类。预览列出实际行为。确认后同exportId+payloadHash被记为已处理，再导入不会补入本次跳过项；同exportId不同hash拒绝。

确认时在单一SQLite事务内重核本机指纹，覆盖笔记/草稿/分类/来源与完整提醒行；变化则要求重新预览。插入、索引字段、引用、来源和导入回执一起提交或回滚。现有本机记录/提醒不覆盖。导入代码不调用AlarmManager或NotificationManager。
