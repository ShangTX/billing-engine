# 设计：彻底去掉 CONTINUE 续算模式

**状态**: 设计中
**日期**: 2026-07-03
**关联**: TODO-20260703-001、`docs/billing-engine-calculation-flow-zh.md`、`docs/superpowers/archive/billing-engine-current-flow-zh.md`、TODO-20260702-004（PromotionCarryOver 迁移）、TODO-20260626-001（不足单元计费）、TODO-20260702-003（外部优惠池）

---

## 1. 背景与决策

### 1.1 问题

CONTINUE 是全局参数（`previousCarryOver != null`），分段前由 `BillingService` 统一处理，但核心语义（截断单元重算、累计金额续算、周期状态恢复、优惠结转）只对单元计费类有意义（spec 3.1："时长计费类不参与 CONTINUE"）。导致：

- **逻辑混乱**：BillingService 全局恢复 `previousAccumulatedAmount`/`truncatedUnitChargedAmount`，时长策略不读；纯时长请求空转，跨段规则不一致时语义不清。
- **架构耦合**：carryOver/ruleState/截断重算贯穿 BillingService / 策略 / Assembler / PromotionEngine / 9 个 POJO，阻碍新架构演进。
- **业务语义不贴切**：假设"单方案续算、规则类型不变"，对混合规则、分段续算支持不清。

### 1.2 决策

**彻底去掉 CONTINUE**，连共享概念（accumulatedAmount 跨段累计、ruleState、PromotionCarryOver）一并清理。未来在新架构上重设计"继续计算"。

依据：外部基本无人用（breaking 可接受）、业务可暂缓（无需立即替代）、彻底清（最大化简化）。版本：major bump（3.0）。

---

## 2. accumulatedAmount 去留（关键决策）

`BillingUnit.accumulatedAmount` 当前服务两个目的：

1. **CONTINUE 累计**：跨段传递 `previousAccumulatedAmount`，finalAmount CONTINUE 分支取 `last unit.accumulatedAmount`。
2. **查询时点金额**：`BillingResultViewer` 公式 `queryAmount = unit.accumulatedAmount - unit.chargedAmount + valueAt(unit, queryTime)`。

### 决策：保留 accumulatedAmount 为"段内累计"，查询逻辑不变

理由：
- 查询是核心能力（`calculateWithQuery`），不能破坏。
- 查询通常在某段内进行，段内累计（`accumulatedAmount = 段内前序单元 chargedAmount 之和`）足以支撑查询公式。
- 去掉 CONTINUE 后，`accumulatedAmount` 语义从"跨段累计（含 previousAccumulatedAmount）"收敛为"段内累计"——每段从 0 开始累加，不跨段。

### 连带改动

- **策略侧**：`ContinuousStrategy`/`DayNightUnitBasedStrategy`/`CompositeTimeRule` 计算累计时，`accumulatedAmount` 初始值从 `context.getPreviousAccumulatedAmount()` 改为 `BigDecimal.ZERO`；删除 `truncatedUnitChargedAmount` 扣减逻辑。
- **CompactMerger**：保留 `accumulatedAmount` 合并（取合并段最后子单元累计值），但 `mergedFromPrevious` 跨段标记删除（compact 跨段合并仍保留，但不再有"来自前段"的 CONTINUE 语义）。
- **ResultAssembler finalAmount**：删除 CONTINUE 分支，单元模式统一为 `totalAmount`（各段 chargedAmount 之和）。`accumulatedAmount` 不再用于 finalAmount。
- **BillingResult.accumulatedAmount**：已 `@deprecated`，删除。
- **BillingResultViewer**：查询公式不变（仍用 `unit.accumulatedAmount`），但语义从"跨段累计"变"段内累计"。跨段查询场景（queryTime 落在后段）需确认：命中单元的 `accumulatedAmount` 是该段内累计，公式仍成立（`accumulatedAmount - chargedAmount + valueAt` 给出该段内 queryTime 前的累计）。

### 风险

- 跨段 compact 合并后，`accumulatedAmount` 是合并单元的累计（跨段）。去掉 `previousAccumulatedAmount` 后，跨段合并的 `accumulatedAmount` 仍由 `CompactMerger` 维护（取最后子单元值）——查询合并单元时，`accumulatedAmount` 反映跨段累计。这与"段内累计"语义有出入，但 CompactMerger 本就是跨段合并，`accumulatedAmount` 取合并末值，查询公式仍自洽。需测试验证。

---

## 2.1 ValueSpec 删除（查询语义统一）

`ValueSpec`（UnitValueSpec 体系）当前服务投影查询——在已计算的全段结果上，查询任意时间点的费用（`BillingResultViewer.createQuerySummary` 公式 `queryAmount = accumulatedAmount - chargedAmount + valueAt(unit, queryTime)`）。

### 决策：删除 ValueSpec，查询统一为"算到 queryTime"

理由：
1. **双轨语义**：ValueSpec（查询投影）与 `IncompleteUnitChargeMode`（截断计费）是两套独立的"单元内未完成费用"语义，可能配置不一致，导致"查询到 queryTime"与"算到 queryTime"结果不同。
2. **CONTINUE 删除后分离价值减弱**：CONTINUE 时代"中间点查询 + 续算"需要投影（避免重算）；删 CONTINUE 后"查询到 queryTime"和"算到 queryTime"语义应统一。
3. **复杂度**：ValueSpec 体系（6 种实现 + Evaluator + Projection）服务投影查询，删除后查询统一走 `IncompleteUnitChargeMode`（一套语义）。

### 删除范围

- `billing/value/UnitValueSpec.java`、`FixedValueSpec.java`、`StepValueSpec.java`、`ProportionalValueSpec.java`、`PiecewiseTimeValueSpec.java`
- `billing/value/UnitValueEvaluator.java`、`UnitValueProjection.java`
- `charge/rules/daynight/DayNightValueSpecFactory.java`（MixedUnitValueSpec、CappedValueSpec）
- `BillingUnit.valueSpec` 字段
- 策略侧 valueSpec 生成逻辑（`computeIncompleteValueSpec`、`valueSpecFactory.create*`）
- `billing-api/wrapper/BillingResultViewer.java`（createQuerySummary、viewAtTime、投影公式、compact 投影）
- `billing-api/wrapper/BillingTemplate.calculateWithQuery`（投影 + disableSimplification 重算流程）

### 查询替代方案

`calculateWithQuery` 改为：`request.endTime = queryTime` 直接计算，读 `finalAmount`。命中简化单元时 `disableSimplification` 重算（保留精确查询能力）。

`IncompleteUnitChargeMode` 成为唯一的"单元内未完成费用"语义（截断计费 + 查询统一）。需确认 `IncompleteUnitChargeMode` 的各档位（FULL_CHARGE/PROPORTIONAL/FREE/THRESHOLD）覆盖原 ValueSpec 场景。

### 连带影响

- **条件免费（`conditional`/`conditionalUntil`/`StepValueSpec` 删除）**：条件免费是**投影查询专用概念**（`FreeTimeRange.conditional` 字段说明：queryTime ≤ conditionalUntil 才生效）。删投影查询后，条件免费就是普通免费段，直接在优惠聚合阶段处理：
  - `StartFreePromotionRule` 直接产出普通 `FREE_RANGE` grant（`[begin, begin+N]` 免费段），删除 `validateQueryTime`/`conditional` 分支
  - `FreeTimeRange`/`PromotionGrant` 删 `conditional`/`conditionalUntil` 字段
  - `FreeTimeRangeMerger`/`ExternalPromotionPool` 删 conditional 传递
  - `ContinuousStrategy` 删 `isConditional` 分支 + `StepValueSpec`，条件免费段按普通免费段处理
  - `StartFreePromotionConfig.validateQueryTime` 删除
  - **语义变化**：条件免费段从"收全额 + 投影显示免费"改为"真免费"（`chargedAmount=0, free=true`）。当前"收全额+投影"本身割裂（计费与查询不一致），改后一致。`StartFreePromotionTest` 等测试需调整。
  - 无需"计费时拆分"，无需 `IncompleteUnitChargeMode`——条件免费段就是普通免费段。
- **compact 单元投影**：`projectCompactUnit` 删除，compact 单元查询改为直接算到 queryTime。
- **`BillingResultViewer` 退役**：查询逻辑简化到 `BillingTemplate`（直接算到 queryTime）。`viewAtTime`、`createQuerySummary`、`QuerySummary` 删除。
- **`disableSimplification` 重算**：保留（简化单元精确查询需要），但重算后直接读 `finalAmount`，不投影。

### 风险

- **查询性能**：每次查询重算（非"一次计算 + 多次投影"）。多数业务场景可接受，高频多时间点查询场景需缓存。
- **条件免费语义**：`StepValueSpec` 删除后，条件免费查询需确认 `IncompleteUnitChargeMode` 覆盖。
- **`BillingResultViewer` 退役范围**：需评估 `viewAtTime`/`createQuerySummary`/`QuerySummary` 是否有其他用途。

### 归档

ValueSpec 与投影查询机制已归档于 `docs/superpowers/archive/2026-07-03-continue-mode-and-value-spec-design.md`，供未来重新实现参考。

---

## 3. isTruncated 保留（非 CONTINUE 专属）

`BillingUnit.isTruncated` 标记单元被 `calcEndTime` 截断，触发**不足单元计费**（`IncompleteUnitChargeMode`，TODO-20260626-001）：截断单元按 PROPORTIONAL/FREE/THRESHOLD 计费，而非全额。

### 决策：保留 isTruncated 与不足单元计费

- `calcEndTime` 保留（局部计算用，非 CONTINUE 专属）。
- `isTruncated` 保留，仍由策略侧设置（`segMinutes < unitMinutes && endTime == calcEnd`）。
- `IncompleteUnitChargeMode` 计费逻辑（`AbstractTimeBasedRule.computeIncompleteCharge` 等）保留。
- **删除**：`BillingCarryOver.lastTruncatedUnitStartTime`、`truncatedUnitChargedAmount`（CONTINUE 扣减用）。

---

## 4. 删除范围

### 4.1 POJO 删除

- `BillingCarryOver`（整体删除）
- `SegmentCarryOver`（整体删除）
- `PromotionCarryOver`（整体删除，004 刚迁移到策略侧 buildCarryOver，整体撤除）

### 4.2 对外字段删除

- `BillingRequest.previousCarryOver`
- `BillingResult.carryOver`
- `BillingResult.accumulatedAmount`（已 deprecated）
- `BillingContext.previousAccumulatedAmount` / `truncatedUnitChargedAmount` / `ruleState` / `promotionCarryOver` / `continueMode`
- `BillingSegmentResult.carryOverAfter`（若仅 CONTINUE 用）
- `BillingRule.buildCarryOverState` 接口方法

### 4.3 BillingService 简化

删除：
- CONTINUE 起点恢复（`actualBeginTime` 调整逻辑、`previousAccumulatedAmount`/`truncatedUnitChargedAmount` 恢复）
- `previousAccumulatedAmount` 跨段传递（`calculateSegmentAccumulatedAmount`）
- `isContinueMode` 分支

保留：
- `actualBeginTime = request.getBeginTime()`（直接用）
- 边界检查（`actualBeginTime >= endTime` 返回空结果）——但空结果的 `carryOver` 字段删除

### 4.4 策略侧简化

`ContinuousStrategy`/`DayNightUnitBasedStrategy`/`RelativeTimeRule`/`NaturalTimeRule`/`CompositeTimeRule` 删除：
- `restoreState`/`restoreStateWithSimplification`/`initializeState` 调用（RuleState 恢复）
- `previousAccumulatedAmount` 读取（`accumulatedAmount` 初始值改 ZERO）
- `truncatedUnitChargedAmount` 扣减
- `ruleOutputState` 输出 / `buildRuleOutputState`
- `buildCarryOverState`

保留：
- 边界驱动循环、单元生成、封顶、compact、valueSpec、不足单元计费、简化计算（简化单元本身，不含 RuleState 恢复）

### 4.5 AbstractTimeBasedRule 简化

删除：
- `RuleState` 内部类
- `restoreState`/`restoreStateWithSimplification`/`restoreStateFromSimplifiedUnit`/`initializeState`/`toMap`
- `buildCarryOverState`
- `getRuleType`（若仅 RuleState key 用）

保留：
- 简化计算框架（`isSimplificationEnabled`/`buildSimplifiedUnit`/`isSimplifiedUnit`/`extractSimplifiedUnitMeta`/`findCyclesWithPromotion`）
- 边界驱动循环、时间轴切分、周期组织、不足单元计费、valueSpec

注：简化单元不再需要 RuleState 恢复（CONTINUE 场景），但简化单元的 `ruleData`（`isSimplified`/`cycleIndex`/`simplifiedCycleCount`/`simplifiedCycleAmount`）仍保留——`calculateWithQuery` 命中简化单元时禁用简化重算仍需识别简化单元。

### 4.6 ResultAssembler 简化

删除：
- `buildBillingCarryOver`/`extractPromotionCarryOver`/`extractAccumulatedAmount`/`extractLastTruncatedUnitStartTime`/`extractTruncatedUnitChargedAmount`
- finalAmount CONTINUE 分支

finalAmount 统一为：
```
finalAmount = 各分段 chargedAmount 之和
```
（时长模式与单元模式统一，不再区分 CONTINUE）

### 4.7 PromotionEngine 简化

删除：
- CONTINUE 恢复 `remainingMinutes`/`usedFreeRanges`（`applyRemainingMinutes`/`filterUsedFreeRanges`/`subtractFreeRanges`）
- `context.getPromotionCarryOver()` 读取

保留：
- 规则 grant 收集、外部 grant 收集、FREE_RANGE 合并、中间形式产出（004）、AMOUNT/DISCOUNT 汇总

### 4.8 PromotionAggregateUtil 简化

删除：
- `buildCarryOver`（004 迁移来的，整体撤除）
- `exclude` 中的 `filterCarryOver`

### 4.9 CompactMerger 简化

- 保留 `accumulatedAmount` 合并（取末值，查询用）
- 删除 `mergedFromPrevious`（CONTINUE 跨段标记）

### 4.10 测试

删除：
- `ContinueModeTest`（整体）
- `PromotionCarryOverTest`（整体）
- `GeneratedContinueStep`、generator CONTINUE 步骤

调整：
- 其他测试中 `new BillingRequest()` 设置 `previousCarryOver` 的代码移除
- `BillingResult.carryOver` 断言移除

---

## 5. 连带影响与风险

| 项 | 影响 | 处理 |
|----|------|------|
| 查询时点金额 | `accumulatedAmount` 语义从跨段→段内累计 | 保留字段，查询公式不变，测试验证跨段场景 |
| 简化计算 | 简化单元 RuleState 恢复删除 | 简化单元本身保留（`ruleData` 标记），`calculateWithQuery` 禁用简化重算仍工作 |
| 外部优惠池（003） | 不受影响 | `ExternalPromotionPool` 保留 |
| FREE_MINUTES 时段化（004） | `PromotionCarryOver` 删除，`buildCarryOver` 撤除 | 策略侧不再写回 carryOver；`pool.writeBack` 仍从 `segmentResult.promotionUsages` 扣减（不依赖 carryOver） |
| 不足单元计费（001-001） | 不受影响 | `isTruncated` 保留 |
| `PromotionEquivalentCalculator` | `cloneAndExclude` 的 `filterCarryOver` 删除 | exclude 简化（只过滤 freeTimeRanges/freeMinutesList/usages） |
| major bump | breaking API | README/USER_GUIDE 移除"继续计算"，版本 3.0 |

---

## 6. 版本与文档

- **版本**：3.0.0（major bump，breaking）。
- **README**/README_CN：移除"支持从上次结果继续计算"特性。
- **USER_GUIDE**：移除 §12 继续计算、`previousCarryOver`/`carryOver`/`BillingCarryOver` 字段说明、`calcEndTime` 的 CONTINUE 用途（保留局部计算用途）。
- **能力文档**（中英）：移除 CONTINUE 能力、`BillingCarryOver`。
- **流程文档**：移除 CONTINUE 处理章节、finalAmount 分支简化。
- **AGENTS.md**：若有 CONTINUE 引用，更新。

---

## 7. 实施顺序

见 `docs/superpowers/plans/2026-07-03-remove-continue-mode.md`。

## 8. 未来：新"继续计算"设计方向（不在本 TODO 范围）

去掉 CONTINUE 后，未来"继续计算"可在新架构上重新设计，可能方向：
- 调用方侧状态管理：调用方保存上次 `calculationEndTime`，下次用 `beginTime = 上次终点` + `calcEndTime = 新终点` 计算，引擎无状态。
- 引擎侧显式续算 API：独立于分段/规则类型的续算接口，按计费类型分别处理。
- 截断单元对齐：要求 `calcEndTime` 对齐单元边界，避免截断重算需求。

这些方向待业务需求驱动时立项讨论。
