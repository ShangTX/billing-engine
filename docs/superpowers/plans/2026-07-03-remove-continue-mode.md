# 实施计划：彻底去掉 CONTINUE 续算模式（TODO-20260703-001）

**关联 spec**: `docs/superpowers/specs/2026-07-03-remove-continue-mode-design.md`
**详情**: `docs/tracking/items/remove-continue-mode.md`
**基线**: 95 测试全绿（`mvn -pl bill-test -am test`）

---

## 实施顺序

按依赖关系分阶段，每阶段结束编译通过。整体作为一次实现提交（改动量大但内聚，分阶段提交反而割裂语义）。

### 阶段 1：POJO 层

- 删除 `BillingCarryOver`、`SegmentCarryOver`、`PromotionCarryOver` 三个 POJO。
- `BillingRequest`：删 `previousCarryOver` 字段。
- `BillingResult`：删 `carryOver`、`accumulatedAmount`（已 deprecated）字段。
- `BillingContext`：删 `previousAccumulatedAmount`、`truncatedUnitChargedAmount`、`ruleState`、`promotionCarryOver`、`continueMode` 字段。
- `BillingSegmentResult`：删 `carryOverAfter`（若仅 CONTINUE 用）、`ruleOutputState`（若仅 CONTINUE 用，需确认简化单元是否用）。
- `BillingUnit`：保留 `accumulatedAmount`（查询用）、`isTruncated`（不足单元计费用）。

### 阶段 2：BillingService 简化

- 删 CONTINUE 起点恢复（`isContinueMode`/`actualBeginTime` 调整/`previousAccumulatedAmount`/`truncatedUnitChargedAmount` 恢复）。
- `actualBeginTime = request.getBeginTime()` 直接用。
- 删 `previousAccumulatedAmount` 跨段传递（`calculateSegmentAccumulatedAmount`）。
- 空结果分支：删 `carryOver` 字段。
- `BillingContext` 构建：删 `continueMode`/`ruleState`/`promotionCarryOver`/`previousAccumulatedAmount`/`truncatedUnitChargedAmount`。

### 阶段 3：AbstractTimeBasedRule 简化

- 删 `RuleState` 内部类、`restoreState`/`restoreStateWithSimplification`/`restoreStateFromSimplifiedUnit`/`initializeState`/`toMap`/`getRuleType`（若仅 RuleState 用）。
- 删 `buildCarryOverState`（接口实现）。
- 保留：简化框架（`isSimplificationEnabled`/`buildSimplifiedUnit`/`isSimplifiedUnit`/`extractSimplifiedUnitMeta`/`findCyclesWithPromotion`）、边界驱动、时间轴切分、周期组织、不足单元计费、valueSpec。

### 阶段 4：策略侧简化（5 规则）

`ContinuousStrategy`/`DayNightUnitBasedStrategy`/`RelativeTimeRule`/`NaturalTimeRule`/`CompositeTimeRule`：
- 删 `restoreState`/`initializeState` 调用、`ruleOutputState` 输出。
- `accumulatedAmount` 初始值：`context.getPreviousAccumulatedAmount()` → `BigDecimal.ZERO`。
- 删 `truncatedUnitChargedAmount` 扣减逻辑。
- 删 `buildCarryOverState`（若有）。
- `PromotionAggregateUtil.buildCarryOver` 调用删除（004 写回 carryOver 的逻辑撤除）。
- 保留：`materializeFreeMinutes`（004）、`isTruncated` 设置、不足单元计费、封顶、compact、valueSpec。

`DayNightDurationStrategy`：本就不参与 CONTINUE，仅需删 `buildCarryOver` 写回（004）。

### 阶段 5：ResultAssembler 简化

- 删 `buildBillingCarryOver`/`extractPromotionCarryOver`/`extractAccumulatedAmount`/`extractLastTruncatedUnitStartTime`/`extractTruncatedUnitChargedAmount`。
- `finalAmount` 统一为 `各分段 chargedAmount 之和`（删 CONTINUE 分支、`isContinueMode` 判断、`extractAccumulatedAmountFromUnits`）。
- `BillingResult` 构建：删 `carryOver` 字段。
- 保留：`CompactMerger.merge`、`effectiveFrom/To`、`calculationEndTime`、`firstUnitMerged`（若保留）。

### 阶段 6：PromotionEngine 简化

- 删 CONTINUE 恢复：`applyRemainingMinutes`/`filterUsedFreeRanges`/`subtractFreeRanges`、`context.getPromotionCarryOver()` 读取。
- 保留：grant 收集、FREE_RANGE 合并、中间形式产出（004）、AMOUNT/DISCOUNT 汇总。

### 阶段 7：PromotionAggregateUtil 简化

- 删 `buildCarryOver`（004 迁移来，整体撤除）、`filterCarryOver`。
- `exclude`：删 `filterCarryOver` 调用，只过滤 `freeTimeRanges`/`freeMinutesList`/`usages`。

### 阶段 8：CompactMerger 简化

- 保留 `accumulatedAmount` 合并（取末值，查询用）。
- 删 `mergedFromPrevious`（CONTINUE 跨段标记）。

### 阶段 9：BillingRule 接口

- 删 `buildCarryOverState` 默认方法（若有）。

### 阶段 10：billing-api

- `PromotionEquivalentCalculator`：`cloneAndExclude` 经 `PromotionAggregateUtil.exclude`（已简化），无直接改动。
- `BillingResultViewer`：查询公式不变（仍用 `unit.accumulatedAmount`，语义段内累计）。
- 其他 wrapper 检查 `carryOver`/`previousCarryOver` 引用并清理。

### 阶段 11：Spring autoconfig（v3/v4）

- 检查 `BillingAutoConfiguration` 是否有 carryOver 相关 bean，清理。

### 阶段 12：测试

- 删 `ContinueModeTest`、`PromotionCarryOverTest`、`GeneratedContinueStep`、generator CONTINUE 步骤。
- 其他测试：移除 `previousCarryOver` 设置、`carryOver` 断言、`accumulatedAmount`（BillingResult 层）断言。
- `ExternalPromotionPoolTest`：确认不依赖 carryOver（应不依赖）。
- 跑 `mvn -pl bill-test -am test`，修复编译/断言错误。

### 阶段 13：文档同步

- README/README_CN：删"支持从上次结果继续计算"。
- USER_GUIDE：删 §12、`previousCarryOver`/`carryOver`/`BillingCarryOver` 字段、`calcEndTime` CONTINUE 用途。
- 能力文档（中英）：删 CONTINUE 能力、`BillingCarryOver`。
- 流程文档：删 CONTINUE 章节、finalAmount 分支简化、当前流程图更新。
- AGENTS.md：若有 CONTINUE 引用更新。
- pom.xml 版本：3.0.0-SNAPSHOT（major bump）。

---

## 验证

- `mvn -pl bill-test -am test`：除 CONTINUE 专属测试删除外，其余全绿。
- 重点回归：查询（`BillingResultViewer`/`calculateWithQuery`）、不足单元计费、简化计算、外部优惠池、FREE_MINUTES 时段化（004）。
- 编译：core / billing-api / bill-test / 两个 starter 全部编译通过。

## 提交

1. `[claude-code|opus-4-8|superpowers] refactor: 彻底去掉 CONTINUE 续算模式（TODO-20260703-001）` — 实现 + 文档同步。
2. `[claude-code|opus-4-8|superpowers] docs: TODO-20260703-001 去掉 CONTINUE 迁移 DONE` — TODO→DONE。

trailer：`Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`。

## 风险与回退

- **风险**：`accumulatedAmount` 语义变化影响跨段查询。缓解：保留字段 + 测试验证跨段 compact 合并后查询。
- **风险**：删 `ruleOutputState` 影响简化单元识别。缓解：简化单元 `ruleData` 标记保留，`calculateWithQuery` 不依赖 `ruleOutputState`。
- **风险**：删 `PromotionCarryOver` 影响 004 的 `buildCarryOver` 调用链。缓解：004 写回 carryOver 的逻辑整体撤除，`pool.writeBack` 不依赖 carryOver。
- **回退**：单次实现提交，若验证失败可 `git revert`。
