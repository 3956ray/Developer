# D2外部键回收解释 v1.1

Owner: Product Lead；指挥者批准，PM记录
Last updated: 2026-09-17
Source: [dev问题与指挥者批准](../../raw/SRC-20260917-gym-key-clarification-01.md)；[PRD P5第1、6条](prd-mock-backend-v1.1-2026-09-16.md)；[AC-DM07/DM08/DM09](acceptance-mock-backend-v1.1-2026-09-16.md)
Confidence: High（已批准解释及字段边界）
Related decisions: GYM-MOCK-BACKEND-DECISION-001；GYM-DEMO-SCENARIOS-001
Next review date: 2026-09-30

Decision: **APPROVED（解释）**。仅澄清P5.1“外部回收键必须拒绝并人工决策”的可观测边界，不扩开发范围、不改四份冻结v1.1文件，不新增字段或回收管理系统。dev继续已授权D2，不因本记录等待或暂停其他工作。

Research Quality: 90/100（沿用v1.1内部决策证据质量，本次不新增研究评分）
Validation Level: V0（不因澄清或工程测试升级）

1. **可实现约束**：在该来源命名空间存续期间，`sourceId + externalId`固定映射同一`courseId`。缺行、取消、历史/批次清理及重启不删除映射以重新分配UUID。同键改名、改期或消失后再出现仍按原ID处理，并遵守既有版本、CAS及覆盖规则。
2. **不可观测限制**：当前schema没有实体代际或其他不可变身份信息，不能区分正常改课与上游把同键交给另一实体。不得仅以名称/时间变化判断回收；不增加启发式检测，不声称能自动识别这种回收。
3. **明确证据下的处置**：来源提供方或维护者明确声明/证实键已分配给另一实体时，停止受影响批次的apply并升级Leader人工决定。不得删映射、重分UUID、换sourceId或删库绕过；本轮不新增机器可读回收声明/阻断系统。若提交后才发现，报告已发生影响并暂停后续受影响导入，不自动回滚或重绑。其他无关且已授权D2工作继续。
4. **验收口径**：AC-DM07验证稳定映射及合法改名改期保持ID；AC-DM08验证同批重复externalId拒绝；AC-DM09验证清理/重启后映射保留及原版本/hash/CAS规则。无法观测的回收列为来源契约与人工复核限制，不列自动检测PASS项，也不要求凭空构造自动识别测试。

本记录不派新任务、不更改工程验收结果；有明确回收证据时的业务纠正另由Leader裁定。
