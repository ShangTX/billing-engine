# 输出结果优化 + 废弃 GLOBAL_ORIGIN + 等效金额增强 设计

**关联**: TODO-20260706-003
**日期**: 2026-07-06
**状态**: 设计中

---

## 1. 背景与问题

规则抽象重构（TODO-20260706-002）落地后，输出层与等效金额计算仍存 6 项已确认优化（见 TODO item 背景）。本设计统一解决。

### 1.1 现状

- `PromotionUsage` 无 `source` 字段；`FreeTimeRange` / `PromotionGrant` 已有 `source`，`FreeMinutes` 无。
- `PromotionUsage.equivalentAmount` 由策略侧按"免费段原价之和"近似填充（`PromotionAggregateUtil.buildFreeRangeUsages`）；`PromotionEquivalentCalculator` 的消去法精确值未回填。
- `PromotionEquivalentCalculator.cloneAndExclude` 在聚合后按 ID 过滤 `FreeTimeRange` / `FreeMinutes`；`calculateWithContexts` 直接用缓存的 `SegmentContext.promotionAggregate`，不重放 `PromotionEngine.evaluate`。
- `GLOBAL_ORIGIN` 半成品止血已 3 版本（TODO-20260702-001 done），externalPool 替代其外部优惠一致性目标。
- 等效金额全量计算，无法按需。
- `BillingResult` 无 `totalEquivalentAmount`。

### 1.2 核心矛盾

消去法要求"排除某优惠后重算"，但外部优惠跨段去重发生在 `ExternalPromotionPool.writeBack`（计费推进时）。当前 `calculateWithContexts` 用缓存的 `SegmentContext.promotionAggregate`（prepareContexts 时聚合，未推进 writeBack），多段场景下：

- 缓存聚合假设外部优惠全量可用（prepareContexts 无 writeBack）。
- 实际 calculate 会 writeBack 扣减跨段剩余量。
- 消去法迭代用缓存聚合 → 跨段扣减未重算 → 多段 + 外部优惠等效金额错误。

## 2. 设计

### 2.1 PromotionUsage.source 透传

`PromotionUsage` 加 `private BConstants.PromotionSource source;`。

三个 usage 产出点透传：

| 产出点 | source 来源 |
|--------|------------|
| `PromotionAggregateUtil.buildFreeRangeUsages`（FREE_RANGE） | `FreeTimeRange.source` |
| `FreeMinuteAllocator`（FREE_MINUTES） | `FreeMinutes.source` |
| `DurationGlobalStrategy.allocateSmartFreeMinutes`（SMART_FREE_MINUTES） | `FreeMinutes.source` |

`FreeMinutes` 加 `private BConstants.PromotionSource source;`，`PromotionEngine.convertMinutesFromRule` 透传 `grant.getSource()`。

**决策 A**：`FreeTimeRangeMerger` 合并时保留 source（合并段取首个来源的 source）。当前 merger 是否透传 source 需确认；若丢弃，FREE_RANGE usage 的 source 可能丢失。验证后决定是否补 merger 透传。

### 2.2 等效金额多段 + 外部优惠修复（核心）

#### 2.2.1 calculateWithContexts 重新 evaluate

`SegmentContext` 缓存的 `promotionAggregate` 是 prepareContexts 时（externalPool 全量）的快照。消去法每次迭代需重放"externalPool reset → 各段 evaluate → writeBack 推进"完整链路。

**改造**：`calculateWithContexts` 不再用 `ctx.getPromotionAggregate()`，而是每段重新 `promotionEngine.evaluate(ctx.getBillingContext())`。但 `BillingContext.externalPromotions` 在 resolveSegmentContext 时取的是 `externalPool.remaining()` 快照，需每段 evaluate 前 refresh。

**方案**：`calculateWithContexts` 每段：
1. `externalPool` 已在循环外 reset（保持现状）。
2. 重新构造 `BillingContext`（clone，`externalPromotions = externalPool.remaining()`）。
3. `promotionEngine.evaluate(refreshedContext)` → 新聚合。
4. `billingCalculator.calculate(refreshedContext, newAggregate)`。
5. `externalPool.writeBack(usages)`。

**决策 B**：`SegmentContext.promotionAggregate` 保留（prepareContexts 产出，单段场景 / 非 calculator 路径仍可用），但 `calculateWithContexts` 改为重放 evaluate。这样消去法与 calculate 路径在多段下行为一致。

#### 2.2.2 cloneAndExclude 改源层排除

当前 `cloneAndExclude` 在聚合后按 ID 过滤 `FreeTimeRange` / `FreeMinutes`。问题：外部优惠的跨段去重由 pool 扣减，不在聚合层；聚合层过滤只能去单段内的免费段，无法表达"排除整个外部优惠 grant"。

**改造**：`cloneAndExclude` 在源层排除：
- `SegmentContext.billingContext.externalPromotions` 按 id 过滤（排除指定 id 的外部 grant）。
- `SegmentContext.billingContext.promotionRules` 按 promotionRuleConfig.id 过滤（排除指定 id 的方案内规则）。
- 不再调用 `PromotionAggregateUtil.exclude`（聚合层过滤废弃）。
- `externalPool` 仍共享（每次迭代 reset 同一实例）。

重放 evaluate 时，被排除的 grant 不会进入 `timeRangePromotions` / `freeMinutesPromotions`，自然不出现在聚合与 usage。

**决策 C**：`PromotionAggregateUtil.exclude` 保留（可能其他路径用），但 calculator 不再调用。验证无其他调用方后可标 deprecated。

#### 2.2.3 BillingContext 重建

`SegmentContext.billingContext` 是不可变快照（resolveSegmentContext 产出）。`calculateWithContexts` 重建时需 clone 并替换 `externalPromotions`。由于 `BillingContext` 用 `@Builder`，可用 `toBuilder()` 重建。

但 `externalPool.remaining()` 依赖每段 writeBack 推进，重建 context 必须在每段 evaluate 前（循环内）。

### 2.3 等效金额按需计算（EquivalentAmountSpec）

新建 `core/src/main/java/cn/shang/charging/billing/pojo/EquivalentAmountSpec.java`：

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class EquivalentAmountSpec {
    Set<String> promotionIds;  // null=不限 id（所有优惠），非空=指定优惠 id
    Set<BConstants.PromotionType> types;  // null=不限类型，非空=指定类型
}
```

`BillingRequest` 加 `private EquivalentAmountSpec equivalentAmountSpec;`（null=不计算，默认 null）。

`PromotionEquivalentCalculator.calculate(request)`：
- 读 `request.getEquivalentAmountSpec()`。
- spec == null → 不计算，返回空 Map（calculator 仍可被外部直接调用，但 BillingService 不调用）。
- spec != null → `extractAndSortRanges` 按 spec 过滤（id ∈ promotionIds 若非空 + type ∈ types 若非空），只对这些优惠做消去法。

### 2.4 BillingService 回填

`BillingService.calculate`：
1. 正常计算 `BillingResult`。
2. 若 `request.getEquivalentAmountSpec() != null`：
   - 调 `PromotionEquivalentCalculator.calculate(request)` 得 Map<promotionId, equivalent>。
   - 遍历 `result.promotionUsages`，按 promotionId 匹配回填 `equivalentAmount`（覆盖策略侧近似值）。
   - `totalEquivalentAmount` = Map 值之和。
3. 否则 `totalEquivalentAmount = null`，usage.equivalentAmount 保持策略侧值。

**决策 D**：`PromotionEquivalentCalculator` 当前在 billing-api 模块，依赖 `BillingService`。`BillingService` 在 core 模块要调用它会形成循环依赖（core → billing-api → core）。

**解决方案**：把等效金额回填逻辑放在 `BillingService.calculate`，但 calculator 实例由外层注入。core 不直接依赖 billing-api。两种选择：
- (a) `BillingService` 暴露一个 `Function<BillingRequest, Map<String, BigDecimal>> equivalentCalculator` 可选注入字段（默认 null），由 billing-api 的 `BillingTemplate` 注入 `PromotionEquivalentCalculator::calculate`。
- (b) 把 `PromotionEquivalentCalculator` 移到 core 模块（它只依赖 `BillingService.prepareContexts` / `calculateWithContexts`，都在 core）。

**决策 D 选 (b)**：calculator 移到 core 的 `cn.shang.charging.billing` 包。它只依赖 core 已有类（BillingService、SegmentContext、PromotionAggregateUtil、FreeTimeRange），无 billing-api 依赖。billing-api 的 `BillingTemplate` 改为直接 `new PromotionEquivalentCalculator(billingService)`（包路径变，import 调整）。

### 2.5 BillingResult.totalEquivalentAmount

`BillingResult` 加 `private BigDecimal totalEquivalentAmount;`（与 `finalAmount` 同级）。`@Builder(toBuilder=true)` 已有，自动支持。

### 2.6 废弃 GLOBAL_ORIGIN

删除：
- `BConstants.SegmentCalculationMode.GLOBAL_ORIGIN` 枚举值（保留 `SINGLE` / `SEGMENT_LOCAL`）。
- `CalculationWindow.clipBegin` / `clipEnd` 字段。
- `CalculationWindowFactory` GLOBAL_ORIGIN 分支（只保留 SEGMENT_LOCAL/SINGLE 逻辑：`calculationBegin = segment.getBeginTime()`）。
- `BillingService.validateGlobalOrigin` 方法 + 两处调用。
- `GlobalOriginGuardTest`（枚举值已不存在，测试无意义）。

调整：
- `SchemeSwitchTest` 场景2（GLOBAL_ORIGIN 单分段）改 SEGMENT_LOCAL，或删除该场景（场景2 只打印不断言）。
- `BillingTestCaseGenerator` / `TestFeature.GLOBAL_ORIGIN`：移除 GLOBAL_ORIGIN feature 或映射到 SEGMENT_LOCAL。
- `CalculationWindowFactory.create`：`segmentCalculationMode` 参数保留（SINGLE/SEGMENT_LOCAL 行为一致），未来恢复 4A 时加回分支。

**保留**：
- `BillingRequest.segmentCalculationMode` 字段（调用方仍可设 SEGMENT_LOCAL 或不设）。
- `SegmentCalculationMode` 枚举（SEGMENT_LOCAL 作为扩展点）。
- `segment-promotion-consistency.md`（4A 设计参考，顶部加废弃标注）。

### 2.7 segment-promotion-consistency.md 标注

顶部加：

> **[废弃标注 2026-07-06]** GLOBAL_ORIGIN 已废弃（TODO-20260706-003）。externalPool 跨段共享已替代其外部优惠一致性目标。本文档保留作 4A 减法方案的设计参考，当前实现不依赖 GLOBAL_ORIGIN。

## 3. 影响分析

### 3.1 模块依赖

calculator 从 billing-api 移到 core。billing-api 的 `BillingTemplate` import 调整。无新模块依赖。

### 3.2 行为兼容

- `equivalentAmountSpec` 默认 null → 等效金额不计算 → 行为与现状一致（usage.equivalentAmount 仍是策略侧近似值）。
- `totalEquivalentAmount` 默认 null → 新字段，调用方未读时不影响。
- `PromotionUsage.source` 新字段 → 调用方未读时不影响；序列化多一个字段。
- GLOBAL_ORIGIN 删除 → 调用方若显式设 GLOBAL_ORIGIN 编译失败（枚举值不存在）。这是 breaking change，但 GLOBAL_ORIGIN 半成品止血期已明确仅单分段可用（等价 SEGMENT_LOCAL），调用方应已迁移。

### 3.3 性能

- 等效金额按需：spec=null 时不计算，零开销。
- calculateWithContexts 重放 evaluate：每次消去迭代多一次 evaluate（原本用缓存）。消去法本身是 O(n) 次重算，evaluate 增量可接受。
- 单段场景：calculateWithContexts 重放 evaluate 与 calculate 路径一致，无额外开销差异（evaluate 本就在 calculate 路径）。

## 4. 决策记录

- **决策 A**：FreeTimeRangeMerger 是否透传 source — 实现时验证，若丢弃则补透传。
- **决策 B**：SegmentContext.promotionAggregate 保留，calculateWithContexts 改重放 evaluate。
- **决策 C**：PromotionAggregateUtil.exclude 保留但 calculator 不再用。
- **决策 D**：calculator 移到 core 模块，避免循环依赖。
- **决策 E**：GLOBAL_ORIGIN 枚举值删除（breaking），segmentCalculationMode 字段与 SEGMENT_LOCAL 保留。
- **决策 F**：等效金额回填覆盖策略侧近似值（消去法更精确）。
