# 计费引擎完整计算流程（中文）

## 一、总览

```
BillingRequest
  → BillingService.calculate()
      → SegmentBuilder.buildSegments()           # 按方案变更切分时间轴为多个段
      → for each segment:
          → CalculationWindowFactory.create()    # 构建计算窗口
          → BillingConfigResolver.resolveXxx()   # 解析计费规则、优惠规则、计费模式
          → PromotionEngine.evaluate()           # 聚合所有优惠（免费时段 + 免费分钟数）
          → BillingCalculator.calculate()        # 委托 BillingRule 执行具体计费
      → ResultAssembler.assemble()               # 汇总所有分段结果为 BillingResult
```

**核心思想**：将一段连续时间按计费方案切分为多个段（Segment），每段独立解析规则、聚合优惠、执行计费，最后汇总。

---

## 二、输入参数：BillingRequest

| 字段 | 类型 | 含义 |
|------|------|------|
| `id` | String | 计费请求标识 |
| `beginTime` | LocalDateTime | 计费开始时间 |
| `endTime` | LocalDateTime | 计费结束时间 |
| `calcEndTime` | LocalDateTime | 计算结束时间（可选），控制计算进度 |
| `schemeId` | String | 单一方案 ID（无方案变更时） |
| `schemeChanges` | List\<SchemeChange\> | 方案变更时间轴（多方案场景） |
| `externalPromotions` | List\<PromotionGrant\> | 外部优惠（如优惠券） |
| `segmentCalculationMode` | SegmentCalculationMode | 分段计算方式：GLOBAL_ORIGIN / SEGMENT_LOCAL / SINGLE |
| `previousCarryOver` | BillingCarryOver | 上次计算的结转状态（CONTINUE 模式） |
| `timeRoundingMode` | TimeRoundingMode | 时间取整模式（CEIL_BEGIN_TRUNCATE_END 等） |
| `context` | Map\<String, Object\> | 自定义上下文参数，传递给 BillingConfigResolver |

### SchemeChange 结构

| 字段 | 含义 |
|------|------|
| `changeTime` | 变更发生的时间点 |
| `lastSchemeId` | 变更前的方案 ID |
| `nextSchemeId` | 变更后的方案 ID |

### SegmentCalculationMode 含义

| 模式 | 含义 |
|------|------|
| `SINGLE` | 仅单段计费 |
| `SEGMENT_LOCAL` | 每段从自身 beginTime 开始计算 |
| `GLOBAL_ORIGIN` | 全局时间原点，所有段共享同一个计算起点 |

---

## 三、CONTINUE 模式处理

**触发条件**：`request.getPreviousCarryOver() != null`

### 3.1 计算起点调整

```
if (carryOver.getLastTruncatedUnitStartTime() != null)
    actualBeginTime = carryOver.getLastTruncatedUnitStartTime()  // 从截断单元起点开始
    truncatedUnitChargedAmount = carryOver.getTruncatedUnitChargedAmount()  // 保存已收金额
else if (carryOver.getCalculatedUpTo() != null)
    actualBeginTime = carryOver.getCalculatedUpTo()  // 从上次计算终点继续
```

**目的**：避免重复收费。截断单元（未完整计费的单元）需要从它的起点重新计算，而不是从终点继续。

### 3.2 边界检查

```
if (actualBeginTime >= endTime) → 返回空结果（无需计费）
```

### 3.3 累计金额恢复

```
previousAccumulatedAmount = carryOver.getAccumulatedAmount()  // 传递给后续分段
```

---

## 四、分段构建：SegmentBuilder

### 4.1 单方案场景

当 `schemeId` 非空且无 `schemeChanges`：

```
Segment {
    id: "schemeId-0",
    beginTime: request.beginTime,
    endTime: request.endTime,
    schemeId: request.schemeId
}
```

### 4.2 多方案场景

遍历 `schemeChanges`，每两个变更点之间产生一个 Segment：

```
SchemeChange 1: t0 → t1, scheme=A → B  →  Segment(id="A-0", begin=t0, end=t1, schemeId="A")
SchemeChange 2: t1 → t2, scheme=B → C  →  Segment(id="B-1", begin=t1, end=t2, schemeId="B")
最后一段: t2 → request.endTime         →  Segment(id="C-2", begin=t2, end=endTime, schemeId="C")
```

**ID 生成规则**：`schemeId + "-" + 自增计数器`

---

## 五、计算窗口：CalculationWindowFactory

每个 Segment 对应一个 CalculationWindow：

```java
CalculationWindow {
    calculationBegin: segmentCalculationMode == GLOBAL_ORIGIN
        ? request.beginTime       // 全局原点
        : segment.beginTime,      // 段内原点
    calculationEnd: segment.endTime,
    clipBegin: segment.beginTime,   // 截取起点（账单展示用）
    clipEnd: segment.endTime        // 截取终点
}
```

### CONTINUE 模式窗口调整

```
if (window.calculationBegin < actualBeginTime)
    window.calculationBegin = actualBeginTime
```

确保计算窗口起点不早于 CONTINUE 模式调整后的实际起点。

---

## 六、规则解析：BillingConfigResolver

三个解析方法，全部基于 `schemeId + window.begin + window.end + context`：

| 方法 | 返回值 | 含义 |
|------|--------|------|
| `resolveChargingRule()` | RuleConfig | 计费规则配置（如 DayNightConfig） |
| `resolvePromotionRules()` | List\<PromotionRuleConfig\> | 优惠规则列表 |
| `resolveBillingMode()` | BillingMode | 计费模式（UNIT_BASED / CONTINUOUS） |

**设计模式**：接口方法，具体实现由调用方提供（如数据库查询、配置文件、内存 Map 等）。

---

## 七、优惠聚合：PromotionEngine

**输入**：BillingContext（包含 promotionRules、externalPromotions、promotionCarryOver）

**输出**：PromotionAggregate（包含合并后的 freeTimeRanges、usages、promotionCarryOver）

### 7.1 优惠来源

```
优惠来源 = 优惠规则（PromotionRuleConfig） + 外部优惠（PromotionGrant）

每条优惠的类型：
- FREE_RANGE: 免费时间段（如 01:00-04:00 免费）
- FREE_MINUTES: 免费分钟数（如赠送 30 分钟）
```

### 7.2 处理流程

```
1. 解析优惠规则
   for each PromotionRuleConfig:
       grants = PromotionRule.grant(context, config)
       → FREE_RANGE 加入 timeRangePromotions
       → FREE_MINUTES 加入 freeMinutesPromotions

2. 加入外部优惠
   for each externalPromotion:
       → FREE_RANGE 加入 timeRangePromotions
       → FREE_MINUTES 加入 freeMinutesPromotions

3. 应用优惠结转状态（CONTINUE 模式）
   remainingMinutes = carryOver.getRemainingMinutesConverted()  // 上次剩余免费分钟
   usedFreeRanges = carryOver.getUsedFreeRanges()               // 上次已用免费时段

   → applyRemainingMinutes(): 用 remainingMinutes 替换 freeMinutesPromotions 的分钟数
   → filterUsedFreeRanges(): 从 timeRangePromotions 中排除 usedFreeRanges 已使用的部分

4. 合并显式免费时间段
   rangeMergeResult = FreeTimeRangeMerger.merge(timeRangePromotions, beginTime, endTime)
   explicitFreeRanges = rangeMergeResult.getMergedRanges()

5. 免费分钟数转为时间段
   minuteResult = FreeMinuteAllocator.allocate(
       freeMinutesPromotions,
       explicitFreeRanges,    // 避开已有的免费时段
       window
   )
   → 在 explicitFreeRanges 之间的空隙中分配免费分钟数
   → 生成 generatedFreeRanges

6. 最终合并
   finalMergeResult = FreeTimeRangeMerger.merge(
       explicitFreeRanges + generatedFreeRanges,
       window.calculationBegin,
       window.calculationEnd
   )
   finalFreeRanges = finalMergeResult.getMergedRanges()

7. 构建结转输出
   outputCarryOver = buildPromotionCarryOver(usages, finalFreeRanges, calculationEndTime)

8. 返回 PromotionAggregate
   {
       freeTimeRanges: finalFreeRanges,
       freeMinutes: 免费分钟数总和,
       usages: 优惠使用统计,
       promotionCarryOver: outputCarryOver
   }
```

---

## 八、免费时间段合并：FreeTimeRangeMerger

### 8.1 核心逻辑

按**优先级**合并重叠的免费时间段，高优先级（数字小）覆盖低优先级。

### 8.2 处理步骤

```
1. 预处理（preprocessRanges）
   - 过滤无效时间段（beginTime >= endTime）
   - 截取在 [overallStart, overallEnd] 范围内的部分
   - 完全在窗口外的范围记录到 discardedRanges
   - 超出窗口边界的部分记录到 discardedRanges
   - 窗口外的免费时段记录到 boundaryReferences（延伸参考，不参与结算）

2. 按优先级排序
   - 优先级数字小的在前（高优先级）
   - 同优先级按 beginTime 排序

3. 按优先级分组处理
   for each priority group (从高到低):
       a. handleSamePriorityCoverage(): 同优先级时间段重叠处理
          - 开始早的覆盖开始晚的
          - 重叠部分记录到 discardedRanges
          - 非重叠部分保留
       b. mergeDifferentPriority(): 与高优先级结果合并
          - 低优先级时间段被高优先级覆盖的部分记录到 discardedRanges
          - 剩余部分加入最终结果

4. 最终整理（finalizeResult）
   - 合并相邻的、同优先级的、同 ID 的时间段
   - 过滤空时间段
```

### 8.3 输出结构

```
TimeRangeMergeResult {
    mergedRanges: List<FreeTimeRange>     // 最终生效的免费时间段
    discardedRanges: List<FreeTimeRange>  // 被舍弃的部分（重叠、截断）
}
```

### 8.4 FreeTimeRange 关键字段

| 字段 | 含义 |
|------|------|
| `beginTime` / `endTime` | 时间段起止 |
| `id` | 唯一标识 |
| `priority` | 优先级（数字越小优先级越高） |
| `promotionType` | FREE_RANGE / FREE_MINUTES |
| `rangeType` | 时间段类型（如 BUBBLE 气泡型） |
| `conditional` | 是否条件免费（不参与时间轴切分） |
| `conditionalUntil` | 条件免费的窗口截止时间 |
| `source` | 来源标识 |

---

## 九、免费分钟数分配：FreeMinuteAllocator

### 9.1 核心逻辑

将免费分钟数像"水流入石头缝隙"一样，填充到显式免费时间段之间的空隙中。

### 9.2 算法流程

```
输入：
- freeMinutesPromotions: 按优先级排序的免费分钟数列表
- explicitFreeRanges: 已合并的免费时间段（已排序）
- window: 计算窗口

游标 cursor = window.calculationBegin
遍历显式免费时间段之间的空隙：

for each gap between explicitFreeRanges:
    gapLength = gap.endTime - gap.beginTime
    while gap 还有未覆盖的分钟:
        取下一个 freeMinutesPromotion
        available = granted - used
        if available >= gapLength:
            整个 gap 标记为免费
            cursor 跳过 gap
        else:
            gap 的前 available 分钟标记为免费
            cursor += available
            继续用下一个 freeMinutes 覆盖剩余部分
```

### 9.3 输出

```
FreeMinuteAllocationResult {
    generatedFreeRanges: List<FreeTimeRange>  // 由免费分钟数生成的免费时间段
    promotionUsages: List<PromotionUsage>     // 每个优惠的使用统计
}
```

---

## 十、规则计费：BillingCalculator

### 10.1 入口

```java
BillingSegmentResult calculate(BillingContext context, PromotionAggregate promotionAggregate)
```

### 10.2 流程

```
1. 根据 RuleConfig.type 从 BillingRuleRegistry 获取 BillingRule 实现
2. 校验计费模式支持（rule.supportedModes() 必须包含 context.billingMode）
3. 类型检查：rule.configClass() 必须匹配 rawConfig 的实际类型
4. 委托：rule.calculate(context, config, promotionAggregate)
```

### 10.3 BillingRule 接口

```java
interface BillingRule<C extends RuleConfig> {
    BillingSegmentResult calculate(BillingContext context, C config, PromotionAggregate promotions);
    Class<C> configClass();
    Set<BillingMode> supportedModes();
    default Map<String, Object> buildCarryOverState(BillingSegmentResult result);
}
```

### 10.4 通用计费流程（不区分具体规则）

```
rule.calculate() 通用流程：

1. 恢复规则状态（CONTINUE 模式）
   ruleState = AbstractTimeBasedRule.restoreState(context.getRuleState())
   if FROM_SCRATCH:
       ruleState = initializeState(calcBegin)

2. 根据计费模式分支
   if UNIT_BASED:
       → 按固定单元长度切分时间轴
       → 对每个单元独立计算：
           - 检查是否被免费时段完全覆盖
           - 覆盖 → free=true, chargedAmount=0
           - 否则 → free=false, chargedAmount=unitPrice
       → 应用周期封顶

   if CONTINUOUS:
       → splitTimeAxis(): 以免费时段边界为 cut point 切分时间轴
         （conditional=true 的免费时段跳过，不产生 cut point）
       → organizeByCycle(): 按 24 小时周期组织片段
       → generateUnitsForCycle(): 每个周期内生成计费单元
         - 免费 fragment → 一个 free=true 的单元
         - 非免费 fragment → 按 unitMinutes 切分为多个收费单元
       → 应用周期封顶

3. 构建 BillingSegmentResult
   {
       segmentId,
       billingUnits: List<BillingUnit>,
       promotionUsages: List<PromotionUsage>,
       chargedAmount: BigDecimal,
       feeEffectiveStart,     // 费用稳定窗口起点
       feeEffectiveEnd,       // 费用稳定窗口终点
       calculationEndTime,    // 计算终点（可能延伸超过窗口终点）
       ruleOutputState,       // 规则状态（CONTINUE 用）
       promotionAggregate     // 优惠聚合结果（用于构建结转状态）
   }
```

### 10.5 免费覆盖检查

**完全覆盖判定**：`range.beginTime <= unitBegin && range.endTime >= unitEnd`

```java
// 单元被免费时段完全覆盖
if (freeTimeRanges 中存在 range 满足:
    !range.getBeginTime().isAfter(unitBegin) &&
    !range.getEndTime().isAfter(unitEnd)) {
    unit.free = true
    unit.freePromotionId = range.id
    unit.chargedAmount = 0
}
```

**注意**：条件免费（`conditional=true`）的免费时段：
- 不参与 splitTimeAxis 的 cut point 生成
- 但在单元级别的免费覆盖检查中**仍然参与**

---

## 十一、结果汇总：ResultAssembler

### 11.1 输入

```
- BillingRequest（原始请求）
- List<BillingSegmentResult>（各分段计费结果）
```

### 11.2 汇总逻辑

```
1. 合并所有 BillingUnit
   allUnits = 所有 segmentResults 的 billingUnits 展平

2. 合并所有 PromotionUsage
   allUsages = 所有 segmentResults 的 promotionUsages 展平

3. 汇总金额
   totalAmount = sum(segmentResult.chargedAmount)

4. 累计金额
   accumulatedAmount = allUnits 最后一个单元的 accumulatedAmount
   finalAmount = accumulatedAmount ?? totalAmount

5. 费用稳定时间窗口
   effectiveFrom = 最后一个分段的 feeEffectiveStart
   effectiveTo = 所有分段 feeEffectiveEnd 的最小值（保守策略）

6. 计算结束时间
   calculationEndTime = 最后一个分段的 calculationEndTime

7. 构建结转状态（BillingCarryOver）
   {
       calculatedUpTo: calculationEndTime,
       segments: Map<segmentId, SegmentCarryOver>,
       lastTruncatedUnitStartTime: 最后一个截断单元的 beginTime,
       truncatedUnitChargedAmount: 截断单元的 chargedAmount,
       accumulatedAmount: 累计金额
   }

   SegmentCarryOver:
   {
       ruleState: 规则输出状态,
       promotionState: 优惠结转状态
   }
```

### 11.3 输出：BillingResult

| 字段 | 含义 |
|------|------|
| `units` | 所有计费单元列表 |
| `promotionUsages` | 优惠使用统计 |
| `finalAmount` | 最终金额（含累计） |
| `effectiveFrom` | 费用稳定窗口起点 |
| `effectiveTo` | 费用稳定窗口终点 |
| `calculationEndTime` | 计算终点（CONTINUE 下次起点参考） |
| `carryOver` | 结转状态（用于下次 CONTINUE） |
| `firstUnitMerged` | 第一个单元是否合并（已废弃） |

---

## 十二、关键数据结构

### 12.1 BillingUnit

| 字段 | 含义 |
|------|------|
| `beginTime` / `endTime` | 单元起止时间 |
| `durationMinutes` | 持续分钟数 |
| `unitPrice` | 单元单价 |
| `originalAmount` | 原始金额（优惠前） |
| `chargedAmount` | 实收金额 |
| `free` | 是否免费 |
| `freePromotionId` | 免费优惠 ID |
| `conditionalFree` | 是否条件免费 |
| `conditionalFreeUntil` | 条件免费窗口截止时间 |
| `accumulatedAmount` | 累计金额（从开始到此单元） |
| `isTruncated` | 是否截断单元（未完整计费） |
| `ruleData` | 规则附加数据（简化计算标记、不确定性元数据等） |

### 12.2 BillingSegmentResult

| 字段 | 含义 |
|------|------|
| `segmentId` | 分段 ID |
| `billingUnits` | 计费单元列表 |
| `promotionUsages` | 优惠使用统计 |
| `chargedAmount` | 本段收费金额 |
| `feeEffectiveStart` | 费用稳定起点 |
| `feeEffectiveEnd` | 费用稳定终点 |
| `calculationEndTime` | 计算终点 |
| `ruleOutputState` | 规则输出状态 |
| `promotionAggregate` | 优惠聚合结果 |

### 12.3 PromotionCarryOver（优惠结转）

| 字段 | 含义 |
|------|------|
| `remainingMinutes` | 剩余免费分钟数（Map\<promotionId, remaining\>） |
| `usedFreeRanges` | 已使用的免费时段列表 |

### 12.4 BillingCarryOver（总结转）

| 字段 | 含义 |
|------|------|
| `calculatedUpTo` | 计算到了哪里 |
| `segments` | 各分段结转状态（Map\<segmentId, SegmentCarryOver\>） |
| `lastTruncatedUnitStartTime` | 最后一个截断单元的起点 |
| `truncatedUnitChargedAmount` | 截断单元已收金额 |
| `accumulatedAmount` | 累计总金额 |

---

## 十三、分步执行模式：prepareContexts + calculateWithContexts

BillingService 提供两步执行模式：

```
1. prepareContexts(request)
   → 构建分段
   → 为每段解析规则、聚合优惠
   → 返回 List<SegmentContext>（BillingContext + PromotionAggregate）

2. calculateWithContexts(contexts, request)
   → 对每个 SegmentContext 执行 billingCalculator.calculate()
   → ResultAssembler.assemble()
```

**适用场景**：需要在规则解析和实际计费之间插入自定义逻辑（如用户确认、状态检查等）。

---

## 十四、简化计算（Simplified Calculation）

当满足以下条件时，启用简化计算（按整周期批量计算，跳过逐单元切割）：

1. `config.simplifiedSupported != false`（配置未明确禁用）
2. `configResolver.getSimplifiedCycleThreshold() > 0`（阈值已配置）
3. `cycleCapAmount > 0`（周期封顶金额有效）

**简化计算流程**：

```
1. 计算覆盖的完整周期数
2. 每个周期生成一个 BillingUnit（代表整个周期）
3. unit.ruleData 标记简化信息：
   { isSimplified: true, simplifiedCycleCount, simplifiedCycleAmount }
4. 剩余不完整周期按正常流程计算
```

**CONTINUE 模式恢复**：

```
ruleState 中存储：cycleIndex, cycleAccumulated, cycleBoundary
从 ruleState 恢复后，跳过已计算的周期，从当前 cycleIndex 继续
```

---

## 十五、完整流程时序图

```
BillingRequest
    │
    ▼
┌─────────────────────────────────────┐
│ BillingService.calculate()          │
│                                     │
│ 1. 确定 CONTINUE 模式               │
│    actualBeginTime = ...            │
│    previousAccumulatedAmount = ...  │
│                                     │
│ 2. SegmentBuilder.buildSegments()   │
│    → [Segment1, Segment2, ...]      │
│                                     │
│ 3. for each segment:                │
│    ┌─────────────────────────────┐  │
│    │ a. CalculationWindowFactory │  │
│    │ b. BillingConfigResolver    │  │
│    │    - chargingRule           │  │
│    │    - promotionRules         │  │
│    │    - billingMode            │  │
│    │ c. 恢复规则状态 (CONTINUE)  │  │
│    │ d. 构建 BillingContext      │  │
│    │ e. PromotionEngine.evaluate │  │
│    │    ├─ 解析规则优惠          │  │
│    │    ├─ 加入外部优惠          │  │
│    │    ├─ 应用结转状态          │  │
│    │    ├─ FreeTimeRangeMerger   │  │
│    │    ├─ FreeMinuteAllocator   │  │
│    │    └─ 最终合并              │  │
│    │ f. BillingCalculator.calc   │  │
│    │    └─ BillingRule.calculate │  │
│    │       ├─ 恢复状态           │  │
│    │       ├─ 切分时间轴/单元    │  │
│    │       ├─ 免费覆盖检查       │  │
│    │       ├─ 生成计费单元       │  │
│    │       └─ 应用周期封顶       │  │
│    │ g. 更新累计金额             │  │
│    └─────────────────────────────┘  │
│                                     │
│ 4. ResultAssembler.assemble()       │
│    ├─ 合并所有单元                  │
│    ├─ 合并优惠使用                  │
│    ├─ 汇总金额                      │
│    ├─ 构建结转状态                  │
│    └─ 返回 BillingResult            │
└─────────────────────────────────────┘
```
