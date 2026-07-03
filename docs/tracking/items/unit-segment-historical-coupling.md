# 单元模式分段计费的历史耦合清理

---
id: TODO-20260701-002
type: refactor
priority: P2
status: todo
source_git: 75f65c3
created_at: 2026-07-01
completed_at:
completed_git:
---

## 背景

讨论时长模式分段计费时，重新审视了现有单元计费类（CONTINUOUS/UNIT_BASED）的 schemeChanges 分段状态传递，发现 `previousAccumulatedAmount` 的跨段传递是历史耦合，非计费正确性需求。

按新设计（`docs/superpowers/specs/2026-07-02-duration-rule-and-promotion-two-tier-design.md` 3.1/3.4），`previousAccumulatedAmount` 是单元计费类策略的展示字段，时长计费类（PERIOD/GLOBAL）无此字段。

## 问题分析

### 当前分段状态传递链条

`BillingService` 分段循环里，第1段算完后向第2段传递：

| 传递的状态 | 用途 | 是否需要 |
|-----------|------|---------|
| `ruleState`（周期状态：cycleIndex/cycleAccumulated/cycleBoundary） | 规则周期封顶 | ❌ 不传——纯分段时 `ruleState=null`，每段 `initializeState` 从零 |
| `promotionCarryOver`（优惠结转） | 免费分钟、已用免费段 | ❌ 不传——只在 `isContinueMode` 时取，纯分段不传 |
| `previousAccumulatedAmount` | `BillingUnit.accumulatedAmount`（累计到该单元的总费用） | ⚠️ 传了，但只为展示层 |

### 为什么不需要跨段状态

1. **周期封顶跨段无意义**：跨段规则类型/方案配置很可能不同（如第1段 DayNight、第2段 RelativeTime），周期、时段、封顶额都不一样。第1段的 cycleAccumulated 对第2段（不同规则/方案）毫无意义。即使同规则，单价/封顶额变化也让累计数字对不上。
2. **优惠跨段无意义**：当前代码也不传，每段独立优惠。外部优惠统计不应依赖分段状态传递（那是续算 carryOver 的职责）。
3. **previousAccumulatedAmount 只为展示**：让第2段 BillingUnit.accumulatedAmount 接着第1段，是展示层便利，非计费正确性。时长计费类（PERIOD/GLOBAL）无此字段，更不需要。

### 当前潜在问题

- **周期封顶跨段失效**：纯 schemeChanges 分段时 ruleState 不传，第2段从零算周期。若方案切换点在周期中间（非边界对齐），周期封顶会重置，可能超额。当前未暴露是因为测试场景的切换点恰好对齐周期边界。
- **previousAccumulatedAmount 混在分段循环**：是历史耦合，与 carryOver（续算）机制混淆。

## 目标

明确分段（schemeChanges）与续算（CONTINUE）是正交的两套机制：
- **分段**：每段独立计算，ResultAssembler 拼接结果，不传任何状态
- **续算（carryOver）**：CONTINUE 专用，解决截断单元+状态衔接

清理 `previousAccumulatedAmount` 在分段循环中的传递，将其限定为续算场景。

## 范围

包含：
- `BillingService` 分段循环：纯 schemeChanges 分段时不传 `previousAccumulatedAmount`
- 区分"续算累计"与"分段展示累计"：`previousAccumulatedAmount` 仅在 `isContinueMode` 时传递
- 文档：明确分段与续算的边界

不包含：
- 时长计费类分段（PERIOD/GLOBAL 无 previousAccumulatedAmount 字段，天然每段独立，见 spec 3.4 模式特性矩阵）
- 周期封顶跨段共享（已确认无意义，不做）

## 验收标准

- 纯 schemeChanges 分段（无 previousCarryOver）：每段独立计算，不传 previousAccumulatedAmount
- CONTINUE 续算：previousAccumulatedAmount 正常传递
- 现有分段测试通过
- 文档说明分段与续算的正交关系

## 相关文件

- `core/src/main/java/cn/shang/charging/billing/BillingService.java`（分段循环、calculateSegmentAccumulatedAmount）

## 备注

- 与门面策略结构重构（TODO-20260702-002）关联：重构后 previousAccumulatedAmount 归属单元计费类策略（CONTINUOUS/UNIT_BASED），本 TODO 的清理在策略结构落地后执行更自然
- 优先级 P2：不影响计费正确性（切换点对齐周期边界时行为不变），仅清理耦合
