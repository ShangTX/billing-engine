# 计费引擎能力文档

本文描述当前代码中已经实现的能力，用于后续设计讨论和实现对齐。它不是历史方案记录。

最后复核日期：2026-07-06

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
| `core` | 计费计算、优惠聚合、规则执行 |
| `billing-api` | `BillingTemplate`、查询摘要、精确查询回退、优惠等效金额计算 |
| `billing-v3-spring-boot-starter` | Spring Boot 3.0.x 到 3.4.x 自动装配 |
| `billing-v4-spring-boot-starter` | Spring Boot 3.5.x 到 4.x 自动装配 |
| `bill-test` | 集成示例、回归测试、计费结果生成器 |

---

## 3. 输入与分段

`BillingRequest` 当前支持：

- 通过 `schemeId` 进行单方案计费。
- 通过 `schemeChanges` 进行多方案切换计费。
- 通过 `externalPromotions` 传入外部优惠（跨段共享可用量池，整笔停车享一次，多分段不重复）。
- 通过 `calcEndTime` 控制局部计算终点。
- 通过 `timeRoundingMode` 控制时间取整。
- 通过 `context` 传递调用方上下文。
- 通过 `disableSimplification` 控制是否禁用简化计算。

分段计算模式：

| 模式 | 行为 |
|------|------|
| `SINGLE` | 整个请求只生成一个分段 |
| `SEGMENT_LOCAL` | 每个分段以自身开始时间作为起算点 |

> **GLOBAL_ORIGIN 已废弃（TODO-20260706-003）**：原"全局起算 + 分段截取"减法方案（4B）半成品止血后未实现，externalPool 跨段共享已替代其外部优惠一致性目标。`SegmentCalculationMode` 仅保留 `SINGLE` / `SEGMENT_LOCAL`，`SEGMENT_LOCAL` 作为扩展点。4A 减法方案设计见 `docs/designs/segment-promotion-consistency.md`。

---

## 4. 计费模式

`CalculationMode` 单一枚举四值（合并自原 `BillingMode` + `DurationMode`，平级）：

| 模式 | 当前语义 |
|------|----------|
| `CONTINUOUS` | 边界驱动循环为唯一计算路径：找到最近边界（免费时段起止、时段结束、周期结束、单元对齐、calcEnd）跳过去，一次迭代产出一个同质段，compact 单元为自然产物 |
| `UNIT_BASED` | 固定单元对齐 + 完整覆盖才免费，不走边界驱动公共循环；当前仅 `dayNight` 门面下 `DayNightUnitBasedStrategy` 承载 |
| `DURATION_PERIOD` | 周期内时长计费，周期封顶 + 时段封顶，产出 `DurationSegment` |
| `DURATION_GLOBAL` | 全局时长计费，封顶按周期数倍乘，产出 `DurationSegment`；唯一消费 `SMART_FREE_MINUTES` 的模式 |

### 模式特性矩阵

| 特性 | CONTINUOUS | UNIT_BASED | DURATION_PERIOD | DURATION_GLOBAL |
|------|-----------|-----------|-----------------|-----------------|
| 产出结构 | BillingUnit | BillingUnit | DurationSegment | DurationSegment |
| 切分模型 | 边界驱动切断 | 固定单元对齐 | 边界驱动分钟流 | 边界驱动分钟流 |
| 公共调度层 | 用 | 不用 | 用 | 用 |
| FREE_MINUTES 处理 | 前置时段化(起点) | 前置时段化(起点) | 前置时段化(起点) | 前置时段化(起点) + SMART_FREE_MINUTES |
| SMART_FREE_MINUTES | 报错 | 报错 | 报错 | 规则侧优先高价分配 |
| compact 合并 | 有 | 无 | 无 | 无 |
| 简化计算 | 全局空隙 | 无 | 无 | 无 |
| 封顶基准 | 逐周期封顶 | 每日封顶 | 周期内封顶 | 全局封顶 × 周期数 |

### 四层架构

```
层0  RuleSemantics（规则族实现，描述"是什么"）
      周期/时段/单元边界 provider + 价格函数 + PeriodLabeler
      + 封顶配置 + 周期切换判定 + 不足单元配置
层1  BoundaryDrivenLoop（纯调度，0 计费语义，不变）
      BoundaryProvider / HomogeneousSegment
层2  ModeStrategy（4 个实现，描述"怎么算"）
      ContinuousStrategy / DayNightUnitBasedStrategy
      DurationPeriodStrategy / DurationGlobalStrategy
      各接收 (RuleSemantics, context, aggregate)，复用层1
层3  BillingRule 门面（纯分派）
      DayNightRule / RelativeTimeRule / ...
      构造 RuleSemantics，按 calculationMode 委托对应 ModeStrategy
```

正交收益：新增规则族 → 实现 `RuleSemantics` + 门面，4 模式自动可用；新增模式 → 实现一个 `ModeStrategy`，所有规则族自动可用（N+M 实现点而非 N×M）。

计费规则通过 `BillingRule.supportedCalculationModes()` 声明支持的模式，门面按请求模式分派：

- `dayNight` 门面声明 4 种模式全支持，分派到 `DayNightContinuousStrategy`（CONTINUOUS）/`DayNightUnitBasedStrategy`（UNIT_BASED）/`DurationPeriodStrategy`/`DurationGlobalStrategy`（接收 `DayNightSemantics`）
- `relativeTime` / `naturalTime` / `compositeTime` 声明 `CONTINUOUS` / `DURATION_PERIOD` / `DURATION_GLOBAL`（不含 `UNIT_BASED`），各自由 `*ContinuousStrategy` + 通用时长策略承载
- `flatFree` 声明 `CONTINUOUS` / `UNIT_BASED`

边界驱动框架关键抽象：

| 抽象 | 职责 |
|------|------|
| `BoundaryProvider` | 边界来源接口，规则注册自己的边界（免费时段、时段结束、周期结束、单元对齐等） |
| `BoundaryProviders` | 边界来源工厂 + `findNearest` 最近边界查找 |
| `HomogeneousSegment` | 同质段，边界驱动循环的最小产出 |
| `HomogeneousSegmentCalculator` | 同质段 → BillingUnit（含 compact 合并） |
| `CompactMerger` | 通用 compact 合并器，跨分段连续相同单元合并 |
| `BoundaryDrivenLoop` | 公共循环入口（`run`），纯调度，CONTINUOUS 与时长策略共享；UNIT_BASED 不走该层 |

---

## 5. 已实现计费规则

### `dayNight`

由 `DayNightRule` 门面实现，按 `CalculationMode` 分派到 `DayNightContinuousStrategy`（CONTINUOUS）/`DayNightUnitBasedStrategy`（UNIT_BASED）/`DurationPeriodStrategy`/`DurationGlobalStrategy`（PERIOD/GLOBAL，接收 `DayNightSemantics`）。

能力：

- 24 小时日夜周期。
- `dayBeginMinute` 和 `dayEndMinute` 定义白天时段。
- `dayUnitPrice` 和 `nightUnitPrice` 定义日夜价格。
- `blockWeight` 决定跨日夜混合单元的最终价格。
- `maxChargeOneDay` 支持每日封顶。
- `CONTINUOUS` 模式下已接入边界驱动循环，产出 compact 单元。
- UNIT_BASED 语义由 `DayNightUnitBasedStrategy` 承载（门面下策略，固定单元对齐 + 完整覆盖才免费）。
- `DURATION_PERIOD` / `DURATION_GLOBAL` 时长模式由通用 `DurationPeriodStrategy` / `DurationGlobalStrategy` 承载（声明即支持，无需规则族私有实现）。

查询行为：

- 单元内查询金额代表"如果此刻结束计费应收多少"，因此可能随查询时间增加或减少。

### `relativeTime`

由 `RelativeTimeRule` 实现。

能力：

- 支持周期内多个相对时间段。
- 每个时段可配置单元长度和价格。
- 支持周期封顶 `maxChargeOneCycle`。
- 支持简化周期计算。
- `CONTINUOUS` 模式下已接入边界驱动循环，产出 compact 单元。

当前限制：


### `compositeTime`

由 `CompositeTimeRule` 实现。

能力：

- 组合时段和自然时段价格。
- 支持周期和时段级别的复杂规则。
- 支持跨时段处理模式。
- 支持简化计算。
- `CONTINUOUS` 模式下已接入边界驱动循环，产出 compact 单元。

当前限制：


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
| `FREE_MINUTES` | 可分配到非免费空隙中的免费分钟数（窗口起点附近分配，前置时段化） |
| `SMART_FREE_MINUTES` | 智能免费分钟数，仅 `DURATION_GLOBAL` 模式消费，规则侧按单价降序优先高价分配；非 GLOBAL 模式报错；与 `FREE_MINUTES` 共用 `freeMinutes` 字段，按 `priority` 排序各自分配 |

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


免费时段类型：

| 类型 | 含义 |
|------|------|
| `NORMAL` | 普通免费时段 |
| `BUBBLE` | 气泡型免费时段元数据，作为独立 range type 参与建模 |

---

## 7. 优惠聚合

`PromotionEngine` 收集规则优惠和外部优惠，并输出 `PromotionAggregate`。外部优惠（`externalPromotions`）跨段共享可用量池（`ExternalPromotionPool`），整笔停车享一次：每段从池取剩余量，段后从 `PromotionUsage` 回写扣减，多分段不重复。方案内优惠每段独立。AMOUNT/DISCOUNT 整笔一次性，不参与免费段切分，由 `AmountDiscountApplier` 事后结算。

当前流程：

1. 从 `PromotionRuleConfig` 收集优惠 grant。
2. 加入请求中的外部 `PromotionGrant`。
3. 汇总 AMOUNT/DISCOUNT 优惠。
4. 通过 `FreeTimeRangeMerger` 合并显式 `FREE_RANGE`。
5. 产出规范中间形式：合并后的 `FREE_RANGE` 时段 + 未时段化的 `FREE_MINUTES` 列表（`freeMinutesList`）+ `AMOUNT`/`DISCOUNT` 标量。

`FREE_MINUTES` 时段化下放到策略侧（TODO-20260702-004）：`PromotionEngine` 不再集中时段化，避免聚合层按"规则+模式"决定产出形式。CONTINUOUS/UNIT_BASED/DURATION_PERIOD 策略经 `RuleSupport.materializeFreeMinutes`（`FreeMinuteAllocator`）自行时段化（与 `FREE_RANGE` 合并）；DURATION_GLOBAL 策略同样时段化（FREE_MINUTES 在窗口起点附近分配），并额外消费 `SMART_FREE_MINUTES`（按单价降序优先高价分配，规则侧用 `RuleSemantics.priceAt` 切同价时段）。`SMART_FREE_MINUTES` 由聚合层标量透传（`smartFreeMinutesList`），不参与时段化，不计入简化计算的总免费分钟数判断。`PromotionUsage`（FREE_MINUTES/FREE_RANGE/SMART_FREE_MINUTES）与 `PromotionCarryOver` 由策略侧产出，`PromotionCarryOver` 经 `PromotionAggregateUtil.buildCarryOver` 构建后写回 aggregate。非 GLOBAL 模式遇到 `SMART_FREE_MINUTES` 由 `BillingCalculator` 抛异常。

`FreeTimeRangeMerger` 会保留优先级、来源、range type 等元数据。

---

## 10. 简化计算

`ContinuousStrategy`（层 2 通用骨架）支持长周期简化计算，采用"全局空隙"实现：从 `freeTimeRanges` 直接算无优惠空隙（优惠时段之间的间隙 + 头尾），每个 gap 对齐周期边界算覆盖周期数；gap 周期数 > 阈值则产出简化单元（`min(总应收, cycleCap × 周期数)`），否则正常生成明细。旧切段模型（`splitTimeAxis`/`TimeFragment`/`organizeByCycle`/`CycleFragments`）已删除。

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

## 12. 优惠等效金额

`PromotionEquivalentCalculator`（TODO-20260706-003 从 `billing-api` 迁入 `core` 的 `cn.shang.charging.billing` 包）使用消去法精确计算每个优惠的等效金额：依次排除某优惠后重算，差额即为该优惠的等效金额。

- **按需计算**：`BillingRequest.equivalentAmountSpec`（`EquivalentAmountSpec`，`promotionIds` + `types`，`null`=不限）控制。`null`（默认）= 不计算，`PromotionUsage.equivalentAmount` 保持策略侧"原价之和"近似值，`BillingResult.totalEquivalentAmount` 为 `null`；非 `null` 时按规格过滤计算，回填到 `PromotionUsage.equivalentAmount`（覆盖近似值）与 `BillingResult.totalEquivalentAmount`（与 `finalAmount` 同级）。
- **多段 + 外部优惠**：`calculateWithContexts` 重放 `PromotionEngine.evaluate`（externalPool reset + 每段 evaluate + writeBack 推进），`cloneAndExclude` 在源层（externalPromotions / promotionRules 按 id）排除，跨段去重在每次消去迭代中重放。
- **优惠来源**：`PromotionUsage.source`（`RULE` 方案内 / `COUPON` 外部等）从 `FreeTimeRange.source` / `FreeMinutes.source` 透传，调用方可区分方案内与外部优惠。


---

## 13. 测试与诊断支持

当前测试支持包括：

- `UnitValueEvaluator` 回归测试。
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
- 不足单元计费方式配置（`IncompleteUnitChargeMode` 的 PROPORTIONAL/FREE/THRESHOLD 档位）尚未接入计费逻辑，截断单元一律按 FULL_CHARGE 收全额（TODO-20260626-001）。
- `SMART_FREE_MINUTES` 仅 `DURATION_GLOBAL` 模式支持；其余模式遇之报错（按设计，复杂度锁定在 GLOBAL 内）。
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
| `docs/superpowers/specs/2026-07-02-duration-rule-and-promotion-two-tier-design.md` | 前置架构设计：时长规则与优惠两级模型（已由 2026-07-06 spec 落地并扩展） |
| `docs/superpowers/specs/2026-07-06-rule-abstraction-refactor-design.md` | 规则抽象重构设计：模式行为驱动 + 规则语义注入（四层架构，已实现） |


