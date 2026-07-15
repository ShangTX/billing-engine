# 彻底去掉 CONTINUE 续算模式

---
id: TODO-20260703-001
type: refactor
priority: P1
status: done
source_git: b5da25f
created_at: 2026-07-03
completed_at: 2026-07-03
completed_git: e4a95de
---

## 背景

当前 CONTINUE 是**全局参数**（`BillingRequest.previousCarryOver != null`），在分段前由 `BillingService.calculate` 统一处理：恢复 `actualBeginTime`（优先 `lastTruncatedUnitStartTime`，否则 `calculatedUpTo`）、`previousAccumulatedAmount`、`truncatedUnitChargedAmount`，并跨段传递 `previousAccumulatedAmount`。

但 CONTINUE 的核心语义（截断单元重算、累计金额续算、扣减已收、周期状态恢复、优惠结转）**只对单元计费类（CONTINUOUS/UNIT_BASED）有意义**，设计原则（spec 3.1）也明确"时长计费类不参与 CONTINUE，不背 carryOver 机制"。这导致：

1. **逻辑混乱**：BillingService 全局恢复 `previousAccumulatedAmount`/`truncatedUnitChargedAmount`，但时长策略不读——纯时长计费请求空转，跨段规则不一致时语义不清。
2. **架构耦合**：CONTINUE 机制（carryOver、ruleState、截断重算）贯穿 BillingService / 策略 / ResultAssembler / PromotionEngine / 9 个 POJO，与单元计费深度耦合，阻碍新架构演进。
3. **业务语义不贴切**：当前 CONTINUE 假设"单方案续算、规则类型不变"，对混合规则类型、分段续算等真实业务场景支持不清。

## 决策

**彻底去掉 CONTINUE 续算模式**，连共享概念（accumulatedAmount 跨段累计、ruleState、PromotionCarryOver 等）一并清理。未来在新架构上重新设计更符合业务的"继续计算"能力。

依据（用户确认）：
- **外部依赖**：基本无人用/仅自用，breaking 可接受。
- **业务需求**："继续计算"可暂缓，去掉后无需立即替代方案。
- **删除范围**：彻底清，最大化简化。

版本策略：major bump（3.0），属于 breaking API change（`BillingResult.carryOver` / `BillingRequest.previousCarryOver` 对外字段移除）。

## 范围

详细删除范围与连带影响见 `docs/superpowers/specs/2026-07-03-remove-continue-mode-design.md`，实施步骤见 `docs/superpowers/plans/2026-07-03-remove-continue-mode.md`。

### CONTINUE 专属（删除）

- POJO：`BillingCarryOver`、`SegmentCarryOver`、`PromotionCarryOver`
- 对外字段：`BillingRequest.previousCarryOver`、`BillingResult.carryOver`、`BillingResult.accumulatedAmount`（已 deprecated）
- `BillingContext`：`previousAccumulatedAmount`、`truncatedUnitChargedAmount`、`ruleState`、`promotionCarryOver`、`continueMode`
- `BillingService`：CONTINUE 起点恢复、`previousAccumulatedAmount` 跨段传递、`calculateSegmentAccumulatedAmount`
- `AbstractTimeBasedRule`：`RuleState`、`restoreState`/`restoreStateWithSimplification`、`buildCarryOverState`、`initializeState`、周期状态恢复
- 策略侧（`ContinuousStrategy`/`DayNightUnitBasedStrategy`/`RelativeTimeRule`/`NaturalTimeRule`/`CompositeTimeRule`）：截断单元重算（从 `lastTruncatedUnitStartTime`）、累计金额续算（从 `previousAccumulatedAmount`）、`ruleState` 恢复/输出
- `ResultAssembler`：`buildBillingCarryOver`、finalAmount CONTINUE 分支、`extractAccumulatedAmount`/`extractLastTruncatedUnitStartTime`/`extractTruncatedUnitChargedAmount`
- `PromotionEngine`：CONTINUE 恢复 `remainingMinutes`/`usedFreeRanges`
- `CompactMerger`：`accumulatedAmount` 维护、`mergedFromPrevious`
- `BillingRule.buildCarryOverState` 接口方法
- 测试：`ContinueModeTest`、`PromotionCarryOverTest`、`GeneratedContinueStep`、generator CONTINUE 步骤

### 非 CONTINUE 专属（保留，需甄别连带影响）

- `calcEndTime`：局部计算用，保留。
- `isTruncated`：触发不足单元计费（`IncompleteUnitChargeMode`，TODO-20260626-001），保留。但 `BillingCarryOver.lastTruncatedUnitStartTime` 删除。
- 不足单元计费（`IncompleteUnitChargeMode`）：独立特性，保留。
- 简化计算：保留，但简化单元的 `RuleState` 恢复（CONTINUE + 简化交叉）随 CONTINUE 去掉。
- `ExternalPromotionPool`（TODO-20260702-003）：外部优惠跨段共享，非 CONTINUE，保留。
- `accumulatedAmount`：**查询时点金额公式依赖**（`queryAmount = unit.accumulatedAmount - unit.chargedAmount + valueAt(...)`），需与 `BillingResultViewer` 协同——见 spec 连带影响。

## 验收标准

- CONTINUE 机制完全移除：无 `previousCarryOver`/`carryOver`/`continueMode`/`ruleState`/`PromotionCarryOver` 残留。
- `accumulatedAmount` 去留明确：若保留则查询逻辑不受影响，若删除则查询公式重写并验证。
- `isTruncated` 保留，不足单元计费行为不变。
- 局部计算（`calcEndTime`）行为不变。
- 简化计算行为不变（除 RuleState 恢复部分）。
- 外部优惠跨段共享（003）行为不变。
- 既有测试（除 CONTINUE 专属测试删除外）全绿。
- 文档同步：README/USER_GUIDE 移除"继续计算"，能力文档/流程文档更新，major bump 说明。

## 相关文件

- `core/src/main/java/cn/shang/charging/billing/`（BillingService、BillingCalculator、pojo/）
- `core/src/main/java/cn/shang/charging/charge/rules/`（AbstractTimeBasedRule、各策略、CompactMerger）
- `core/src/main/java/cn/shang/charging/promotion/`（PromotionEngine、PromotionAggregateUtil、pojo/PromotionCarryOver）
- `core/src/main/java/cn/shang/charging/settlement/ResultAssembler.java`
- `billing-api/src/main/java/cn/shang/charging/wrapper/`（BillingResultViewer、PromotionEquivalentCalculator）
- `bill-test/src/`（CONTINUE 相关测试删除/调整）
- 文档：README、USER_GUIDE、能力文档、流程文档

## 备注

- 与 TODO-20260702-004（FREE_MINUTES 时段化下放）关联：004 将 `PromotionCarryOver` 构建迁移到策略侧，本 TODO 整体删除 `PromotionCarryOver`。
- 与 TODO-20260626-001（不足单元计费）关联：`isTruncated` 保留，不足单元计费不受影响。
- 与 TODO-20260702-003（外部优惠两级模型）关联：`ExternalPromotionPool` 保留，不受影响。
- 优先级 P1：架构清晰化，但 breaking change，需 major bump。
