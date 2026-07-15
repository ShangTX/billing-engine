# 计费引擎完整计算流程

本文描述当前代码中的主计算链路。能力边界和规则覆盖范围见 `docs/billing-engine-capabilities-zh.md`。

最后复核日期：2026-06-30

---

## 1. 总览

```
BillingRequest
  -> BillingService.calculate()
      -> SegmentBuilder.buildSegments()
      -> for each segment:
          -> CalculationWindowFactory.create()
          -> BillingConfigResolver.resolveChargingRule()
          -> BillingConfigResolver.resolvePromotionRules()
          -> BillingConfigResolver.resolveBillingMode()
          -> PromotionEngine.evaluate()
          -> BillingCalculator.calculate()
      -> ResultAssembler.assemble()
```

主流程由 `BillingService` 编排。它不直接读取数据库，也不直接决定业务规则；规则和优惠配置由调用方实现的 `BillingConfigResolver` 提供。

---

## 2. 输入：`BillingRequest`

| 字段 | 含义 |
|------|------|
| `id` | 请求标识 |
| `beginTime` / `endTime` | 计费起止时间 |
| `calcEndTime` | 可选计算终点，用于局部计算和 CONTINUE 场景 |
| `schemeId` | 单方案计费 ID |
| `schemeChanges` | 多方案切换时间轴 |
| `segmentCalculationMode` | 分段起算方式 |
| `externalPromotions` | 外部传入的优惠 grant |
| `previousCarryOver` | 上次计算的结转状态 |
| `timeRoundingMode` | 时间取整模式 |
| `disableSimplification` | 是否禁用简化计算 |
| `context` | 传给配置解析器的调用方上下文 |

---

## 3. CONTINUE 处理

当 `request.previousCarryOver != null` 时进入继续计算路径。

核心语义：

- 如果存在 `lastTruncatedUnitStartTime`，本次从该截断单元的开始时间重新计算。
- 如果不存在截断单元，则从 `calculatedUpTo` 继续。
- `accumulatedAmount` 会作为累计金额基数继续使用。
- 分段级 `ruleState` 和 `promotionState` 会传回规则和优惠引擎。

这样可以避免“上次截断单元”和“本次新单元”之间重复收费。

---

## 4. 分段：`SegmentBuilder`

单方案场景：

```
schemeId + beginTime/endTime -> one BillingSegment
```

多方案场景：

```
schemeChanges -> multiple BillingSegment
```

每个分段包含：

- `id`
- `beginTime`
- `endTime`
- `schemeId`

---

## 5. 计算窗口：`CalculationWindowFactory`

每个分段会生成一个 `CalculationWindow`：

| 字段 | 含义 |
|------|------|
| `calculationBegin` | 规则实际起算点 |
| `calculationEnd` | 规则实际计算终点 |
| `clipBegin` | 输出裁剪起点 |
| `clipEnd` | 输出裁剪终点 |

`SegmentCalculationMode` 决定 `calculationBegin`：

| 模式 | 行为 |
|------|------|
| `SINGLE` | 单段计算 |
| `SEGMENT_LOCAL` | 每个分段从自身开始时间起算 |
| `GLOBAL_ORIGIN` | 所有分段共享请求开始时间作为全局原点 |

在 CONTINUE 模式下，窗口起点不能早于恢复后的实际起点。

---

## 6. 配置解析：`BillingConfigResolver`

每个分段会解析三类配置：

| 方法 | 返回 | 用途 |
|------|------|------|
| `resolveChargingRule()` | `RuleConfig` | 当前分段使用的计费规则 |
| `resolvePromotionRules()` | `List<PromotionRuleConfig>` | 当前分段使用的优惠规则 |
| `resolveBillingMode()` | `BillingMode` | 当前分段的计费模式 |

这是业务侧接入引擎的主要扩展点。

---

## 7. 优惠聚合：`PromotionEngine`

`PromotionEngine.evaluate(context)` 输出 `PromotionAggregate`。

处理顺序：

1. 执行优惠规则，收集规则 grant。
2. 加入请求中的外部 grant。
3. 恢复 CONTINUE 优惠结转。
4. 合并显式 `FREE_RANGE`。
5. 将 `FREE_MINUTES` 分配到可用空隙。
6. 合并最终免费时段。
7. 汇总 AMOUNT / DISCOUNT 优惠。
8. 生成新的 PromotionCarryOver。

输出中的 `freeTimeRanges` 会交给计费规则决定如何影响计费单元；`AMOUNT` / `DISCOUNT` 不参与免费时段切分，而是在计费结果生成后统一结算。

---

## 8. 规则执行：`BillingCalculator`

`BillingCalculator.calculate(context, promotionAggregate)` 做三件事：

1. 根据 `RuleConfig.type` 从 `BillingRuleRegistry` 获取规则实现。
2. 校验规则是否支持当前 `BillingMode`。
3. 校验配置类型后调用 `BillingRule.calculate()`。

具体的单元切割、单价判断、封顶、规则状态输出由各 `BillingRule` 实现。当前已实现的主要规则包括 `dayNight`、`relativeTime`、`naturalTime`、`compositeTime` 和 `flatFree`。

`CONTINUOUS` 模式下，时间计费规则（`dayNight`/`relativeTime`/`naturalTime`/`compositeTime`）通过 `AbstractTimeBasedRule.runBoundaryDrivenLoop` 公共循环切割时间轴：每次迭代从当前位置查询所有边界来源中最近的边界，跳到那里产出一个同质段（`HomogeneousSegment`），再由各规则的 `applyCapAndAccumulate` 转换为 `BillingUnit`（含封顶、累计金额、compact 合并、截断标记）。边界来源由各规则通过 `BoundaryProvider` 注册（免费时段起止、时段结束、周期结束、单元对齐、calcEnd 等）。compact 单元是该循环的自然产物，无需后处理合并。

---

## 9. 计费单元：`BillingUnit`

当前 `BillingUnit` 的关键语义：

| 字段 | 含义 |
|------|------|
| `beginTime` / `endTime` | 单元起止时间 |
| `durationMinutes` | 单元分钟数 |
| `unitPrice` | 单元价格，由具体规则解释 |
| `originalAmount` | 优惠前金额 |
| `chargedAmount` | 单元完整结束后的最终金额 |
| `accumulatedAmount` | 单元完整结束后的累计金额 |
| `free` / `freePromotionId` | 是否由非条件免费完全覆盖及对应优惠 ID |
| `valueSpec` | 单元内查询时点投影模型 |
| `ruleData` | 规则私有数据，例如周期序号或简化单元标记 |
| `isTruncated` | 是否被 `calcEndTime` 截断 |
| `compact` | 是否为 compact 单元（合并了 N 个连续相同子单元） |
| `count` | compact 单元代表的子单元数量，非 compact 始终为 1 |

`conditionalFree` 和 `conditionalFreeUntil` 已不再是主模型字段。条件起始免费通过 `StepValueSpec` 表达。

---

## 10. 单元求值：`valueSpec`

`valueSpec` 的职责是回答：

```
如果计费在 queryTime 这一刻结束，命中单元当前应收多少？
```

公共协议：

- `UnitValueSpec.project(queryTime, unitBeginTime, unitEndTime)`
- `UnitValueProjection(currentAmount, nextChangeTime)`
- `UnitValueEvaluator.evaluate(...)`

已实现通用表达：

- `FixedValueSpec`
- `StepValueSpec`
- `PiecewiseTimeValueSpec`

`DayNightRule` 还包含规则私有表达：

- `MixedUnitValueSpec`
- `CappedValueSpec`

---

## 11. 查询摘要：`BillingResultViewer.createQuerySummary`

查询摘要只基于已经计算出的 `BillingResult`。

处理规则：

1. 如果 `queryTime > calculationEndTime`，直接报错。
2. 找到包含 `queryTime` 的命中单元。
3. 对命中单元执行 `UnitValueEvaluator`。
4. 使用以下公式计算查询金额：

```
queryAmount = unit.accumulatedAmount - unit.chargedAmount + valueAt(unit, queryTime)
```

5. `effectiveTo` 使用 `UnitValueProjection.nextChangeTime`，而不是简单使用单元结束时间。

如果旧结果没有 `valueSpec`，查询层会退化为 `FixedValueSpec(chargedAmount)`。

---

## 12. `BillingTemplate.calculateWithQuery`

`calculateWithQuery(request, queryTime)` 是推荐的查询入口。

流程：

1. 先执行一次正常计费。
2. 生成 `QuerySummary`。
3. 如果命中单元是简化单元，复制请求并设置 `disableSimplification=true`。
4. 重新计算一次精确结果。
5. 基于精确结果重新生成 `QuerySummary`。

这使长期计费可以继续使用简化计算，同时保证查询命中简化单元时仍返回精确结果。

---

## 13. 简化计算

`AbstractTimeBasedRule` 提供长周期简化能力。

简化单元通过 `ruleData` 标记：

```json
{
  "isSimplified": true,
  "cycleIndex": 1,
  "simplifiedCycleCount": 10,
  "simplifiedCycleAmount": 120.00
}
```

简化单元不承诺保存完整单元内部细节。精确查询由 `billing-api` 层触发禁用简化的重算。

---

## 14. 汇总：`ResultAssembler`

`ResultAssembler.assemble()` 合并所有分段结果：

- 通过 `CompactMerger.merge` 合并 `BillingUnit`，跨分段的连续相同单元合并为 compact 单元（跨分段边界不合并）。
- 合并 `PromotionUsage`。
- 计算最终金额和累计金额。
- 计算 `effectiveFrom`、`effectiveTo` 和 `calculationEndTime`。
- 生成新的 `BillingCarryOver`。

输出为 `BillingResult`。

---

## 15. 优惠等效金额

优惠等效金额由 `billing-api` 中的 `PromotionEquivalentCalculator` 计算。

它基于完整结算结果做对比分析，不依赖查询时点投影；金额减免和折扣优惠的最终应用由 `AmountDiscountApplier` 完成。只要完整结果中的 `chargedAmount`、`accumulatedAmount` 和 `promotionUsages` 一致，`valueSpec` 不会改变优惠等效金额语义。

---

## 16. 相关文档

| 文档 | 用途 |
|------|------|
| `docs/billing-engine-capabilities-zh.md` | 当前能力中文说明 |
| `docs/billing-engine-capabilities.md` | 当前能力英文说明 |
| `docs/USER_GUIDE.md` | 调用方使用指南 |
| `docs/TODO.md` | 待办和问题索引 |
| `docs/superpowers/specs/2026-04-20-unit-value-spec-design.md` | `valueSpec` 设计 |

