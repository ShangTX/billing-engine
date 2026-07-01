# 计费引擎能力文档

本文描述当前代码中已经实现的能力，用于后续设计讨论和实现对齐。它不是历史方案记录。

最后复核日期：2026-06-30

---

## 1. 范围

本项目是一个时间计费引擎，适用于停车收费、场地租赁、设备租赁和其他按时间计费的场景。

核心计算链路：

```
BillingRequest
  -> BillingService
  -> SegmentBuilder
  -> BillingConfigResolver
  -> PromotionEngine
  -> BillingCalculator
  -> BillingRule
  -> ResultAssembler
  -> BillingResult
```

`core` 模块只负责纯计算。`billing-api` 模块提供便捷 API、时间取整、查询时点视图和优惠等效金额分析。

---

## 2. 模块能力

| 模块 | 能力 |
|------|------|
| `core` | 计费计算、优惠聚合、规则执行、结转状态 |
| `billing-api` | `BillingTemplate`、查询摘要、精确查询回退、优惠等效金额计算 |
| `billing-v3-spring-boot-starter` | Spring Boot 3.0.x 到 3.4.x 自动装配 |
| `billing-v4-spring-boot-starter` | Spring Boot 3.5.x 到 4.x 自动装配 |
| `bill-test` | 集成示例、回归测试、计费结果生成器 |

---

## 3. 输入与分段

`BillingRequest` 当前支持：

- 通过 `schemeId` 进行单方案计费。
- 通过 `schemeChanges` 进行多方案切换计费。
- 通过 `externalPromotions` 传入外部优惠。
- 通过 `previousCarryOver` 继续上次计算。
- 通过 `calcEndTime` 控制局部计算终点。
- 通过 `timeRoundingMode` 控制时间取整。
- 通过 `context` 传递调用方上下文。
- 通过 `disableSimplification` 控制是否禁用简化计算。

分段计算模式：

| 模式 | 行为 |
|------|------|
| `SINGLE` | 整个请求只生成一个分段 |
| `SEGMENT_LOCAL` | 每个分段以自身开始时间作为起算点 |
| `GLOBAL_ORIGIN` | 所有分段共享全局时间轴，再裁剪到当前分段 |

---

## 4. 计费模式

| 模式 | 当前语义 |
|------|----------|
| `CONTINUOUS` | 边界驱动循环为唯一计算路径：找到最近边界（免费时段起止、时段结束、周期结束、单元对齐、calcEnd）跳过去，一次迭代产出一个同质段，compact 单元为自然产物 |
| `UNIT_BASED` | 独立计费规则类型，固定单元对齐 + 完整覆盖才免费。不再作为普通规则内置模式 |

计费规则必须通过 `BillingRule.supportedModes()` 声明自己支持的模式。

**UNIT_BASED 降级**（TODO-20260630-001 已完成）：

- 普通规则（`dayNight`/`relativeTime`/`naturalTime`/`compositeTime`）只支持 `CONTINUOUS`，边界驱动为唯一计算路径
- `UNIT_BASED` 语义由独立规则类承载，当前已实现 `DayNightUnitBasedRule`（日夜 UNIT_BASED），其余按需添加
- `BillingMode.UNIT_BASED` 枚举值保留，供独立 UNIT_BASED 规则声明支持
- 时长计费模式（按时长累加、免费时段扣除分钟）待引入，作为与单元计费并列的新模式

边界驱动框架关键抽象：

| 抽象 | 职责 |
|------|------|
| `BoundaryProvider` | 边界来源接口，规则注册自己的边界（免费时段、时段结束、周期结束、单元对齐等） |
| `BoundaryProviders` | 边界来源工厂 + `findNearest` 最近边界查找 |
| `HomogeneousSegment` | 同质段，边界驱动循环的最小产出 |
| `HomogeneousSegmentCalculator` | 同质段 → BillingUnit（含 compact 合并） |
| `CompactMerger` | 通用 compact 合并器，跨分段连续相同单元合并 |

---

## 5. 已实现计费规则

### `dayNight`

由 `DayNightRule` 实现。

能力：

- 24 小时日夜周期。
- `dayBeginMinute` 和 `dayEndMinute` 定义白天时段。
- `dayUnitPrice` 和 `nightUnitPrice` 定义日夜价格。
- `blockWeight` 决定跨日夜混合单元的最终价格。
- `maxChargeOneDay` 支持每日封顶。
- `CONTINUOUS` 模式下已接入边界驱动循环，产出 compact 单元。
- UNIT_BASED 语义由独立规则 `DayNightUnitBasedRule` 承载（固定单元对齐 + 完整覆盖才免费）。
- 已为稳定单元、条件免费单元、跨日夜混合单元和封顶单元生成 `valueSpec`。

查询行为：

- 跨日夜混合单元通过 `MixedUnitValueSpec` 保留规则私有的单元内求值逻辑。
- 单元内查询金额代表"如果此刻结束计费应收多少"，因此可能随查询时间增加或减少。
- 每日封顶会进入命中单元的 `valueSpec`，保证查询金额和最终结算金额一致。
- compact 单元的查询投影由 `BillingResultViewer.projectCompactUnit` 处理：按子单元时长定位 queryTime 落在第 k 个子单元，累计金额 = (k+1) × 子单元单价。

### `relativeTime`

由 `RelativeTimeRule` 实现。

能力：

- 支持周期内多个相对时间段。
- 每个时段可配置单元长度和价格。
- 支持周期封顶 `maxChargeOneCycle`。
- 支持简化周期计算。
- `CONTINUOUS` 模式下已接入边界驱动循环，产出 compact 单元。

当前限制：

- 尚未像 `dayNight` 一样补齐规则私有的复杂 `valueSpec`。

### `compositeTime`

由 `CompositeTimeRule` 实现。

能力：

- 组合时段和自然时段价格。
- 支持周期和时段级别的复杂规则。
- 支持跨时段处理模式。
- 支持简化计算。
- `CONTINUOUS` 模式下已接入边界驱动循环，产出 compact 单元。

当前限制：

- 尚未像 `dayNight` 一样补齐规则私有的复杂 `valueSpec`。

### `naturalTime`

由 `NaturalTimeRule` 实现。

能力：

- 24 小时自然周期，按自然时段划分。
- 每个时段有独立价格，统一单元时长。
- 跨时段处理可配置（复用 `CrossPeriodMode`）。
- 支持每日封顶 `maxChargeOneDay`。
- `CONTINUOUS` 模式下已接入边界驱动循环，产出 compact 单元。

### `flatFree`

已实现为返回覆盖整个计费窗口的免费单元。根据构造方式不同，可能需要手动注册。

### 预留规则常量

| 常量 | 状态 | 说明 |
|------|------|------|
| `nrTimeMix` | 已废弃 | 被 `compositeTime` 整体覆盖（CompositePeriod + NaturalPeriod） |
| `times` | 预留 | 按次数计费，非时间计费场景，需另行设计 |

---

## 6. 优惠能力

已实现优惠 grant 类型：

| 类型 | 含义 |
|------|------|
| `FREE_RANGE` | 明确的免费时间段 |
| `FREE_MINUTES` | 可分配到非免费空隙中的免费分钟数 |

预留或未完整实现的优惠类型：

| 类型 | 状态 |
|------|------|
| `AMOUNT` | 已实现为优惠类型能力；当前通过 `PromotionEngine` 汇总并由 `AmountDiscountApplier` 应用，不属于独立 `PromotionRuleType` |
| `DISCOUNT` | 已实现为优惠类型能力；当前通过 `PromotionEngine` 汇总并由 `AmountDiscountApplier` 应用，不属于独立 `PromotionRuleType` |

已实现优惠规则：

| 规则 | 能力 |
|------|------|
| `freeMinutes` | 授予免费分钟数，并在可用空隙中分配 |
| `startFree` | 从分段开始授予起始免费时间段 |

`StartFreePromotionConfig.validateQueryTime=true` 不再通过 `BillingUnit.conditionalFree` 表达。当前实现会在受影响单元上生成 `StepValueSpec`：条件窗口内按免费投影，超过条件窗口后按正常价格投影。

免费时段类型：

| 类型 | 含义 |
|------|------|
| `NORMAL` | 普通免费时段 |
| `BUBBLE` | 气泡型免费时段元数据，作为独立 range type 参与建模 |

---

## 7. 优惠聚合

`PromotionEngine` 收集规则优惠和外部优惠，并输出 `PromotionAggregate`。

当前流程：

1. 从 `PromotionRuleConfig` 收集优惠 grant。
2. 加入请求中的外部 `PromotionGrant`。
3. 在 `CONTINUE` 模式下恢复优惠结转。
4. 通过 `FreeTimeRangeMerger` 合并显式 `FREE_RANGE`。
5. 通过 `FreeMinuteAllocator` 分配 `FREE_MINUTES`。
6. 合并显式免费时段和生成的免费时段。
7. 生成新的优惠结转状态。

`FreeTimeRangeMerger` 会保留优先级、来源、range type 和 conditional 元数据。查询时点的条件行为由规则生成的 `valueSpec` 解释，不再由查询层直接修改字段。

---

## 8. 单元求值与查询金额

`BillingUnit` 同时保存完整单元结算金额和可选的 `valueSpec`。

关键字段：

| 字段 | 含义 |
|------|------|
| `chargedAmount` | 单元完整结束后的最终金额 |
| `accumulatedAmount` | 单元完整结束后的累计金额 |
| `valueSpec` | 单元内查询投影模型 |
| `ruleData` | 规则私有数据，包括简化单元标记 |
| `compact` | 是否为 compact 单元（合并了 N 个连续相同子单元） |
| `count` | compact 单元代表的子单元数量，非 compact 始终为 1 |

compact 单元由边界驱动循环自然产出：连续 N 个相同单价、相同时长、相同免费状态、相同 valueSpec、未被截断、时间连续的子单元合并为一个 compact 单元。截断单元（`isTruncated=true`）始终以非 compact 形式输出。compact 单元的 `accumulatedAmount` 指向合并段最后一个子单元的累计值。

当前公共求值协议：

| 类型 | 职责 |
|------|------|
| `UnitValueSpec` | 单元在查询时点的投影接口 |
| `UnitValueProjection` | 投影结果，包含 `currentAmount` 和 `nextChangeTime` |
| `UnitValueEvaluator` | 校验输入和投影不变量 |
| `FixedValueSpec` | 固定值单元 |
| `StepValueSpec` | 阶跃值单元，用于条件起始免费 |
| `PiecewiseTimeValueSpec` | 通用时间分段表达模型 |
| `DayNightRule.MixedUnitValueSpec` | 日夜规则私有的混合单元投影 |
| `DayNightRule.CappedValueSpec` | 日夜规则私有的封顶投影包装 |

命中单元的查询金额公式：

```
queryAmount = unit.accumulatedAmount - unit.chargedAmount + valueAt(unit, queryTime)
```

这个公式把 `accumulatedAmount` 当作完整结算后的前缀累计，再用查询时点投影替换命中单元的完整金额。

---

## 9. 查询 API

`BillingResultViewer.createQuerySummary(result, queryTime)`：

- 拒绝超过 `result.calculationEndTime` 的查询时间。
- 定位命中的计费单元。
- 通过 `UnitValueEvaluator` 计算命中单元的查询投影。
- 使用 `valueSpec.nextChangeTime` 作为 `QuerySummary.effectiveTo`。
- 对没有 `valueSpec` 的旧结果，回退为 `FixedValueSpec(chargedAmount)`。

`BillingTemplate.calculateWithQuery(request, queryTime)`：

- 先执行正常计算。
- 生成查询摘要。
- 如果命中单元是简化单元，则设置 `disableSimplification=true` 再精确重算一次。
- 返回精确结果和查询摘要。

`BillingResultViewer.viewAtTime(result, queryTime)` 仍可返回按已完成单元过滤后的视图。需要精确单元内查询金额时，应优先使用 `createQuerySummary()` 或 `BillingTemplate.calculateWithQuery()`。

---

## 10. 简化计算

`AbstractTimeBasedRule` 支持长周期简化计算。

简化单元会在 `ruleData` 中记录类似结构：

```json
{
  "isSimplified": true,
  "cycleIndex": 1,
  "simplifiedCycleCount": 10,
  "simplifiedCycleAmount": 120.00
}
```

简化计算会有意丢弃单元内部细节。精确查询命中简化单元时，`billing-api` 会通过 `disableSimplification=true` 触发一次精确重算。

这样可以同时保留长时间计费效率和查询时点精度。

---

## 11. CONTINUE 模式

`CONTINUE` 模式由 `BillingCarryOver` 驱动。

主要结转数据：

- `calculatedUpTo`
- 分段级结转状态
- `lastTruncatedUnitStartTime`
- `truncatedUnitChargedAmount`
- `accumulatedAmount`

如果上次计算结束在一个计费单元内部，下次计算会从该截断单元的开始时间重新计算，并通过结转金额避免重复收费。

优惠结转会保存剩余免费分钟数和已使用免费时段，供后续计算继续同一优惠状态。

---

## 12. 优惠等效金额

`PromotionEquivalentCalculator` 位于 `billing-api`。

它通过对比完整计费结果计算优惠等效金额。只要完整结果中的 `chargedAmount`、`accumulatedAmount` 和 `promotionUsages` 保持一致，查询时点的 `valueSpec` 机制不会改变优惠等效金额的契约。

---

## 13. 测试与诊断支持

当前测试支持包括：

- `UnitValueEvaluator` 回归测试。
- `BillingResultViewer` 查询摘要和简化单元回退测试。
- 日夜混合单元、封顶单元、条件起始免费的查询值测试。
- `bill-test` 中的可运行示例。
- `BillingTestCaseGenerator`，用于生成只含计费结果 JSON 的人工检查用例。

生成器当前主要覆盖 `dayNight`，并已经定义公共、优惠、规则私有功能点，方便后续扩展。

---

## 14. 已知缺口

当前缺口以 `docs/TODO.md` 和 `docs/tracking/items/` 为准。

重要缺口包括：

- `AMOUNT` 和 `DISCOUNT` 已作为优惠类型能力接入，但当前仍不是独立 `PromotionRuleType`。
- `times` 仍为预留规则常量；`nrTimeMix` 已废弃并由 `compositeTime` 覆盖。
- `relativeTime` 和 `compositeTime` 尚未拥有和 `dayNight` 同等级别的复杂 `valueSpec` 覆盖。
- 分钟级 `valueSpec` 是已预留的扩展方向，但当前尚未实现。
- 不足单元计费方式配置（`IncompleteUnitChargeMode` 的 PROPORTIONAL/FREE/THRESHOLD 档位）尚未接入计费逻辑，截断单元一律按 FULL_CHARGE 收全额（TODO-20260626-001）。
- 时长计费模式（按分钟累加、免费时段扣除分钟）待引入，作为单元计费的并列新模式。
- 物化索引预估收入能力：引擎只提供实现可能（产出 validMinutes/accumulatedAmount 等），存储/索引由业务层实现（TODO-20260630-002）。

---

## 15. 相关文档

| 文档 | 用途 |
|------|------|
| `docs/billing-engine-capabilities.md` | 本文档的英文版 |
| `docs/billing-engine-calculation-flow-zh.md` | 中文计算流程参考 |
| `docs/USER_GUIDE.md` | 面向使用者的指南 |
| `docs/TODO.md` | 当前待办和问题索引 |
| `docs/DONE.md` | 已完成事项归档 |
| `docs/superpowers/specs/2026-04-20-unit-value-spec-design.md` | `valueSpec` 设计文档 |
| `docs/superpowers/plans/2026-04-20-unit-value-spec-implementation.md` | `valueSpec` 实施计划 |


