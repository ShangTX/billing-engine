# 计费引擎计算流程

本文描述计费引擎**当前**的计算链路与语义。分段与优惠一致性的完整论证见 `docs/designs/segment-promotion-consistency.md`。

最后更新日期：2026-07-06

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
          -> BillingConfigResolver.resolveCalculationMode()
          -> PromotionEngine.evaluate()
          -> BillingCalculator.calculate()
      -> ResultAssembler.assemble()
```

主流程由 `BillingService` 编排。它不直接读取数据库，也不直接决定业务规则；规则和优惠配置由调用方实现的 `BillingConfigResolver` 提供。

### 四层架构与计算流程图

四层架构：`RuleSemantics`（层 0，描述"是什么"）→ `BoundaryDrivenLoop`（层 1，纯调度）→ 4 个 `ModeStrategy`（层 2，描述"怎么算"）→ `BillingRule` 门面（层 3，纯分派）。`calculate` 与 `prepareContexts` 共用 `resolveSegmentContext` 解析分段上下文。每个计费规则族一个 `ChargeRuleType`、一个门面规则（如 `DayNightRule`）、一个共享 config；门面按 `CalculationMode` 分派到对应 `ModeStrategy`，自身只分派不扛逻辑。

```mermaid
flowchart TD
    Req["BillingRequest<br/>beginTime/endTime<br/>schemeChanges<br/>externalPromotions (FREE_MINUTES/SMART_FREE_MINUTES/FREE_RANGE)"]

    Req --> SB["SegmentBuilder.buildSegments<br/>按 schemeChanges 切分段"]

    SB --> Pool["外部优惠全局可用量池<br/>FREE_MINUTES/SMART_FREE_MINUTES/FREE_RANGE: 跨段共享剩余量"]

    Pool --> Loop{"遍历每个 BillingSegment"}

    Loop --> CWF["CalculationWindowFactory.create<br/>SEGMENT_LOCAL / SINGLE: 段起点起算<br/>(GLOBAL_ORIGIN 已废弃 TODO-20260706-003)"]

    CWF --> Cfg["BillingConfigResolver<br/>resolveChargingRule → RuleConfig.type<br/>resolvePromotionRules (本段方案内优惠)<br/>resolveCalculationMode (单一枚举四值)<br/>外部优惠可能被方案内优惠覆盖而未使用"]

    Cfg --> PE["PromotionEngine.evaluate<br/>入参: 剩余外部优惠 + 本段方案内优惠规则<br/>产出规范中间形式<br/>FREE_RANGE(时段) + FREE_MINUTES(分钟数) + SMART_FREE_MINUTES(标量透传)"]

    PE --> BC["BillingCalculator.calculate<br/>按 type 取门面规则<br/>校验 supportedCalculationModes<br/>SMART_FREE_MINUTES 仅 DURATION_GLOBAL 允许，否则抛异常"]

    BC --> Type{"RuleConfig.type"}

    Type -->|dayNight| Facade["DayNightRule 门面<br/>supportedCalculationModes={CONTINUOUS,UNIT_BASED,DURATION_PERIOD,DURATION_GLOBAL}<br/>按请求模式分派到策略"]

    Facade --> Mode{"请求 CalculationMode"}

    Mode -->|CONTINUOUS| S1["DayNightContinuousStrategy (implements BillingRule)<br/>委托通用 ContinuousStrategy<br/>RuleSupport.materializeFreeMinutes 时段化 FREE_MINUTES<br/>调 BoundaryDrivenLoop<br/>产出 BillingUnit"]
    Mode -->|UNIT_BASED| S2["DayNightUnitBasedStrategy<br/>固定单元对齐<br/>materializeFreeMinutes 时段化 FREE_MINUTES<br/>产出 BillingUnit<br/>仅支持 SEGMENT_LOCAL"]
    Mode -->|DURATION_PERIOD| S3["DurationPeriodStrategy (静态工具, 接收 DayNightSemantics)<br/>调 BoundaryDrivenLoop + DurationSupport.buildPeriodMode<br/>周期内时长 + 周期/时段封顶<br/>materializeFreeMinutes 时段化 FREE_MINUTES<br/>产出 DurationSegment"]
    Mode -->|DURATION_GLOBAL| S4["DurationGlobalStrategy (静态工具, 接收 DayNightSemantics)<br/>调 BoundaryDrivenLoop 识别日夜/免费边界<br/>同质收费桶汇总 + 尾周期封顶<br/>materializeFreeMinutes + SMART_FREE_MINUTES 优先高价分配<br/>产出 begin/end 为空的收费汇总 DurationSegment"]

    S1 --> Shared["层1 公共调度层<br/>BoundaryProvider / BoundaryProviders<br/>HomogeneousSegment / BoundaryDrivenLoop.run<br/>纯调度，零计费语义"]
    S3 --> Shared
    S4 --> Shared

    Shared --> Writeback["按来源分辨本段实际使用的外部优惠<br/>（PromotionUsage 记 promotionId + usedMinutes + type）<br/>回写扣减全局可用量池"]
    S2 --> Writeback

    Writeback --> Seg1["BillingSegmentResult"]
    Seg1 --> Loop

    Loop -->|所有段完成| RA["ResultAssembler.assemble"]

    RA --> Merge["flat汇总 BillingUnit（compact 段内直接产出）<br/>合并 DurationSegment<br/>合并 PromotionUsage"]

    Merge --> F1["finalAmount = 各分段 chargedAmount 之和"]

    F1 --> Out["BillingResult<br/>units / durationSegments<br/>promotionUsages<br/>finalAmount"]
```

要点：

- **四层架构，门面纯分派**：`RuleSemantics`（层 0）→ `BoundaryDrivenLoop`（层 1）→ `ModeStrategy`（层 2）→ `BillingRule` 门面（层 3）。每个规则族一个 type（如 `dayNight`），一个门面规则 + 一个共享 config。门面按请求 `CalculationMode` 分派到 `ModeStrategy`，自身只分派不扛逻辑。流程图以 `dayNight` 为示例，其他规则族（`relativeTime`/`naturalTime`/`compositeTime`/`flatFree`）结构相同，各自门面按声明的模式分派；时长模式由通用 `DurationPeriodStrategy`/`DurationGlobalStrategy` 承载（接收各规则族的 `*Semantics`）。
- **单一 CalculationMode 枚举**：合并自原 `BillingMode` + `DurationMode`，四值平级（CONTINUOUS/UNIT_BASED/DURATION_PERIOD/DURATION_GLOBAL），消除互斥双维度分派。门面一个 `switch(calculationMode)` 分派。
- **产出结构两类**：单元计费类（CONTINUOUS/UNIT_BASED，产 `BillingUnit`）与时长计费类（DURATION_PERIOD/DURATION_GLOBAL，产 `DurationSegment`）切分模型、封顶语义、优惠消费不同，各自独立策略。
- **公共调度层共享**：CONTINUOUS 策略和时长策略都调用 `BoundaryDrivenLoop.run`，该层只含边界调度原语，零计费语义。UNIT_BASED 策略不走该层。
- **解析逻辑共享**：`calculate` 与 `prepareContexts` 共用 `resolveSegmentContext`（解析 calculationMode + externalPool 等），消除不同步，保证 `PromotionEquivalentCalculator` 等效金额与 `calculate` 一致。
- **外部优惠全局一致**：分段前建立外部优惠可用量池，跨段共享剩余量；每段 evaluate 时剩余外部优惠与本段方案内优惠按优先级聚合，外部优惠可能被方案内优惠覆盖而未使用。按优惠来源从本段结果分辨实际使用量，回写扣减池，下段拿到正确的剩余外部优惠。
- **FREE_MINUTES / SMART_FREE_MINUTES 处理**：聚合产出规范中间形式（FREE_RANGE 为时段、FREE_MINUTES 为分钟数、SMART_FREE_MINUTES 为标量透传），不集中时段化。各策略经 `RuleSupport.materializeFreeMinutes` 自行时段化 FREE_MINUTES；DURATION_GLOBAL 额外消费 SMART_FREE_MINUTES（按 `RuleSemantics.priceAt` 切同价时段，按单价降序优先高价分配，与普通免费段按 `priority` 排序各自分配）。

分段每段独立计算，不传规则/优惠/累计状态。

---

## 2. 输入：`BillingRequest`

| 字段 | 含义 |
|------|------|
| `id` | 请求标识 |
| `beginTime` / `endTime` | 计费起止时间 |
| `calcEndTime` | 可选计算终点，用于局部计算 |
| `schemeId` | 单方案计费 ID |
| `schemeChanges` | 多方案切换时间轴 |
| `segmentCalculationMode` | 分段起算方式 |
| `externalPromotions` | 外部传入的优惠 grant |
| `timeRoundingMode` | 时间取整模式 |
| `disableSimplification` | 是否禁用简化计算 |
| `context` | 传给配置解析器的调用方上下文 |

`timeRoundingMode` 由 `billing-api` 的 `BillingTemplate` 在调用 core 前处理：默认 `TRUNCATE_BOTH` 全部向下取整；`CEIL_BEGIN_TRUNCATE_END` 对计费时间收窄、对外部 `FREE_RANGE` 优惠时间段放宽。直接调用 `BillingService` 时不执行该接入层归一化。

---

## 3. 分段：`SegmentBuilder`

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

**期望语义**：分段每段独立计算，不传任何规则/优惠/累计状态，`ResultAssembler` 拼接结果。跨段规则类型/方案不同，周期封顶跨段无意义。

---

## 4. 计算窗口：`CalculationWindowFactory`

每个分段会生成一个 `CalculationWindow`：

| 字段 | 含义 |
|------|------|
| `calculationBegin` | 规则实际起算点 |
| `calculationEnd` | 规则实际计算终点 |

> `clipBegin` / `clipEnd`（GLOBAL_ORIGIN 减法用）已于 TODO-20260706-003 删除。

`SegmentCalculationMode` 决定 `calculationBegin`：

| 模式 | 行为 |
|------|------|
| `SINGLE` | 单段计算 |
| `SEGMENT_LOCAL` | 每个分段从自身开始时间起算 |

> **GLOBAL_ORIGIN 已废弃（TODO-20260706-003）**：externalPool 跨段共享替代其外部优惠一致性目标。`SEGMENT_LOCAL` 作为扩展点保留。

---

## 5. 配置解析：`BillingConfigResolver`

每个分段会解析三类配置：

| 方法 | 返回 | 用途 |
|------|------|------|
| `resolveChargingRule()` | `RuleConfig` | 当前分段使用的计费规则 |
| `resolvePromotionRules()` | `List<PromotionRuleConfig>` | 当前分段使用的优惠规则 |
| `resolveCalculationMode()` | `CalculationMode` | 当前分段的计算模式（CONTINUOUS/UNIT_BASED/DURATION_PERIOD/DURATION_GLOBAL，单一枚举） |

这是业务侧接入引擎的主要扩展点。

`BillingService` 中 `calculate` 与 `prepareContexts` 都经 `resolveSegmentContext` 解析分段上下文（含 `calculationMode` 与 `externalPool`），保证两条路径解析一致。

---

## 6. 优惠聚合：`PromotionEngine`

`PromotionEngine.evaluate(context)` 输出 `PromotionAggregate`。

处理顺序：

1. 执行优惠规则，收集规则 grant。
2. 加入请求中的外部 grant。
3. 合并显式 `FREE_RANGE`。

产出规范中间形式：`freeTimeRanges`（仅 FREE_RANGE，已合并）、`freeMinutesList`（未时段化 FREE_MINUTES）、`smartFreeMinutesList`（SMART_FREE_MINUTES 标量透传，不时段化、不计入简化判定）、`freeMinutes`（标量，简化判定用，不含 SMART）。

时段化是策略侧职责：CONTINUOUS/UNIT_BASED/DURATION_PERIOD 策略经 `RuleSupport.materializeFreeMinutes` 时段化 FREE_MINUTES；DURATION_GLOBAL 同样时段化 FREE_MINUTES，并额外消费 `SMART_FREE_MINUTES`（按 `RuleSemantics.priceAt` 切同价时段，按单价降序优先高价分配）。`activationMode=END_WITHIN_RANGE` 的条件优惠只在 DURATION_PERIOD/DURATION_GLOBAL 中支持，时长策略在合并/分配后按整笔计费结束时间过滤最终免费段和 `PromotionUsage`，不重排其他优惠。非 GLOBAL 模式遇 `SMART_FREE_MINUTES` 由 `BillingCalculator` 抛异常；非时长模式遇条件生效优惠也由 `BillingCalculator` 抛异常。

---

## 7. 规则执行：`BillingCalculator`

`BillingCalculator.calculate(context, promotionAggregate)` 做三件事：

1. 根据 `RuleConfig.type` 从 `BillingRuleRegistry` 获取门面规则实现。
2. 校验规则是否支持当前 `CalculationMode`（`supportedCalculationModes()`）；非 `DURATION_GLOBAL` 模式遇 `SMART_FREE_MINUTES` 抛异常；非时长模式遇 `activationMode=END_WITHIN_RANGE` 抛异常。
3. 校验配置类型后调用 `BillingRule.calculate()`，门面按请求 `CalculationMode` 分派到 `ModeStrategy`。

每个计费规则族一个 type、一个门面规则、一个共享 config。门面声明 `supportedCalculationModes()`（管 CONTINUOUS/UNIT_BASED/DURATION_PERIOD/DURATION_GLOBAL），按请求模式分派到独立策略实现。规则族包括 `dayNight`、`relativeTime`、`naturalTime`、`compositeTime` 和 `flatFree`，各规则族按需声明支持的模式。

**单元计费类**（产 `BillingUnit`）：
- CONTINUOUS 策略：各规则族的 `*ContinuousStrategy`（`implements BillingRule`）委托通用 `ContinuousStrategy`，经 `BoundaryDrivenLoop.run` 公共循环切割时间轴，每次迭代产出同质段（`HomogeneousSegment`），再由 `applyCapAndAccumulate` 转换为 `BillingUnit`（含封顶、累计金额、compact 合并、截断标记）。周期切换通过 `RuleSemantics.isCycleBoundary` 注入，periodCap 通过 `RuleSemantics.periodLabeler` 注入。
- UNIT_BASED 策略：固定单元对齐 + 完整覆盖才免费，不走边界驱动公共循环。

**时长计费类**（产 `DurationSegment`）：复用边界驱动循环。`DurationPeriodStrategy`（周期内时长计费 + 周期封顶，`DurationSupport.buildPeriodMode`）；通用 `DurationGlobalStrategy`（全局时长计费，`DurationSupport.buildGlobalMode`）按同质收费桶汇总，并对完整周期与尾周期分别应用时段封顶和周期封顶；同时消费 `SMART_FREE_MINUTES` 优先高价分配。

边界驱动循环（`BoundaryDrivenLoop.run` + `BoundaryProviders` + `HomogeneousSegment`）是纯调度层，零计费语义，CONTINUOUS 策略和时长策略共享；UNIT_BASED 策略不走该层。

---

## 8. 计费单元：`BillingUnit`

`BillingUnit`（CONTINUOUS / UNIT_BASED 模式产出）的关键语义：

| 字段 | 含义 |
|------|------|
| `beginTime` / `endTime` | 单元起止时间 |
| `durationMinutes` | 单元分钟数 |
| `unitPrice` | 单元价格，由具体规则解释 |
| `originalAmount` | 优惠前金额 |
| `chargedAmount` | 单元完整结束后的最终金额 |
| `accumulatedAmount` | 段内累计金额（从分段起点累加，不跨段） |
| `free` / `freePromotionId` | 是否被优惠完全覆盖及对应优惠 ID |
| `ruleData` | 规则私有数据，例如周期序号或简化单元标记 |
| `isTruncated` | 是否被 `calcEndTime` 截断（不足单元计费触发条件） |
| `compact` | 是否为 compact 单元（合并了 N 个连续相同子单元） |
| `count` | compact 单元代表的子单元数量，非 compact 始终为 1 |

---

## 9. 时长计费段：`DurationSegment`

`DurationSegment`（DURATION_PERIOD / DURATION_GLOBAL 模式产出）将时间轴视为连续分钟流，按时段类型分组。`DURATION_GLOBAL` 输出为收费汇总桶，`beginTime` / `endTime` 为 `null`，免费信息通过 `PromotionUsage` 跟踪：

| 字段 | 含义 |
|------|------|
| `beginTime` / `endTime` | 段起止时间；`DURATION_GLOBAL` 汇总桶为空 |
| `periodLabel` | period 性质（"day"/"night"/"period-1"，规则自定义；`compositeTime` 为 `r:x-y|n:a-b`） |
| `chargedMinutes` | 收费分钟数（免费段=0） |
| `unitPrice` | 单价 |
| `chargedAmount` | 应收（时段封顶后，周期封顶前） |
| `periodCap` | 该时段封顶金额（null=无封顶） |
| `freePromotionId` | 免费段对应的 FreeTimeRange.id（非免费段 null），用于聚合 PromotionUsage |
| `originalAmount` | 按规则原价（封顶前；免费段非 0，用于等效优惠金额） |

封顶落盘策略：时段封顶落盘到 `chargedAmount`；周期封顶不落盘，只影响 `BillingSegmentResult.chargedAmount`。PERIOD 模式免费段用 `chargedMinutes=0` 表达；GLOBAL 不落盘免费段，免费原因与使用分钟走 `PromotionUsage` 汇总。

---

## 10. 简化计算

`ContinuousStrategy`（层 2 通用骨架）提供长周期简化能力，采用"全局空隙"实现：从 `freeTimeRanges` 直接算无优惠空隙，每个 gap 对齐周期边界算覆盖周期数，gap 周期数 > 阈值则产出简化单元。旧切段模型（`splitTimeAxis`/`TimeFragment`/`organizeByCycle`/`CycleFragments`）已删除。

简化单元通过 `ruleData` 标记：

```json
{
  "isSimplified": true,
  "cycleIndex": 1,
  "simplifiedCycleCount": 10,
  "simplifiedCycleAmount": 120.00
}
```

简化单元不承诺保存完整单元内部细节。精确查询由调用方设置 `disableSimplification=true` 触发重算。

---

## 11. 汇总：`ResultAssembler`

`ResultAssembler.assemble()` 合并所有分段结果：

- flat 汇总各分段 `BillingUnit`（compact 由 CONTINUOUS 策略段内直接产出，跨分段不合并，保留分段边界）。
- 合并 `DurationSegment`（时长模式）。
- 合并 `PromotionUsage`。
- 计算最终金额：统一为各分段 `chargedAmount` 之和。
- 计算 `calculationEndTime`。

输出为 `BillingResult`。

---

## 12. 优惠等效金额

优惠等效金额由 `core` 模块 `cn.shang.charging.billing.PromotionEquivalentCalculator` 计算，`billing-api` 的 `BillingTemplate` 只作为便捷入口调用该能力。

它基于完整结算结果做对比分析：通过 `PromotionAggregateUtil.exclude` 过滤 `freeMinutesList`/`freeTimeRanges`，重算取差值。只要完整结果中的 `chargedAmount` 和 `promotionUsages` 一致，等效金额语义不变。

---

## 13. 相关文档

| 文档 | 用途 |
|------|------|
| `docs/billing-engine-capabilities-zh.md` | 当前能力中文说明 |
| `docs/billing-engine-capabilities.md` | 当前能力英文说明 |
| `docs/USER_GUIDE.md` | 调用方使用指南 |
| `docs/designs/segment-promotion-consistency.md` | 分段与优惠一致性架构讨论 |
| `docs/TODO.md` | 待办和问题索引 |
| `docs/DONE.md` | 已完成事项索引 |
