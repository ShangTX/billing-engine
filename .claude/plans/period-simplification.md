# PERIOD 时长模式简化计算 + CONTINUOUS bubble 校验 + 简化逻辑合一

## 背景与现状

- **CONTINUOUS 简化**：仅 `DayNightContinuousStrategy` / `CompositeTimeContinuousStrategy` 各有一份 `generateUnitsByGlobalGaps`（全局空隙算法，两份几乎重复，连 `Range` 内部类都重复），产出 `BillingUnit`。`RelativeTimeContinuousStrategy` / `NaturalTimeContinuousStrategy` 无简化。
- **时长模式**：`DurationSupport.buildPeriodMode` / `buildGlobalMode` 完全无简化，长周期（如停 30 天）产出海量 `DurationSegment`。`buildPeriodMode` 已支持 bubble（effective 周期切分），`buildGlobalMode` 已支持 bubble（cycleCount 减 bubble 时长）。
- **用户需求**：
  1. CONTINUOUS 模式校验 bubble 存在 → 报错（CONTINUOUS 不支持 bubble）
  2. PERIOD 时长模式支持 bubble + 简化
  3. 简化逻辑一份，不写两份

## 核心设计洞察

**简化段内无 bubble**：简化只发生在 gap（无优惠空隙）内的完整周期块。bubble 段是优惠段，不在 gap 内。因此 gap 内 effective offset = 自然 offset（线性增长），不需要递推算所有 effective 周期边界。

**只需算 gap 起始的 effective offset**：
```
effectiveOffset(gap.begin) = (gap.begin - origin) - bubbleDurationBefore(gap.begin)
startK = ceil(effectiveOffset(gap.begin) / cycleMinutes)
endK = floor(effectiveOffset(gap.end) / cycleMinutes)   // gap 内无 bubble，effectiveOffset(gap.end) = effectiveOffset(gap.begin) + (gap.end - gap.begin)
简化段起始 = gap.begin + (startK * cycleMinutes - effectiveOffset(gap.begin))   // gap 内自然 = effective
简化段结束 = gap.begin + (endK * cycleMinutes - effectiveOffset(gap.begin))
cycleCount = endK - startK
若 cycleCount > threshold → 简化
```

- CONTINUOUS（无 bubble）：`bubbleDurationBefore = 0`，`effectiveOffset = 自然 offset`，退化为现有 `generateUnitsByGlobalGaps` 的 `beginOffset/cycleMinutes` 对齐。**行为不变**。
- PERIOD（有 bubble）：`bubbleDurationBefore > 0`，effective 边界后移。**支持 bubble**。

这让 CONTINUOUS 和 PERIOD 共用同一份核心算法（gaps + effective offset + 阈值判断），仅产出类型（BillingUnit vs DurationSegment）和详细路径不同。

## 实施步骤

### 步骤 1：新增 `SimplificationSupport`（核心算法一份）

位置：`core/src/main/java/cn/shang/charging/charge/rules/SimplificationSupport.java`

公共静态方法：
- `List<Range> computeGaps(LocalDateTime calcBegin, LocalDateTime calcEnd, List<FreeTimeRange> freeTimeRanges)`：算无优惠空隙（bubble 段是优惠，不在 gap 内）。从现有 `generateUnitsByGlobalGaps` 的 gaps 算法提取。
- `long bubbleDurationBefore(LocalDateTime time, List<FreeTimeRange> bubbleRanges)`：time 之前（相对 calcBegin）的 bubble 段总时长。
- `SimplifiedBlock findSimplifiedBlock(Range gap, LocalDateTime origin, List<FreeTimeRange> bubbleRanges, int cycleMinutes, int threshold)`：按上述公式算 startK/endK/simplifiedBegin/simplifiedEnd/cycleCount，超阈值返回 `SimplifiedBlock`，否则 null。

内部类型：
- `Range(LocalDateTime begin, LocalDateTime end)`（从两个策略提取，消除重复）
- `SimplifiedBlock(LocalDateTime begin, LocalDateTime end, int cycleCount, int startK, int endK)`

### 步骤 2：CONTINUOUS bubble 校验

- 新增 `ContinuousStrategy.assertNoBubbleSupported(List<FreeTimeRange> freeTimeRanges)`：若存在 `rangeType == BUBBLE` 抛 `IllegalArgumentException("CONTINUOUS 模式不支持 BUBBLE 免费时段，请使用 DURATION_PERIOD/DURATION_GLOBAL 模式")`。
- 4 个 CONTINUOUS 策略（DayNight/CompositeTime/RelativeTime/NaturalTime）的 `calculate` 开头调用该校验。

### 步骤 3：DayNight/CompositeTime 简化重构（用 SimplificationSupport）

- `generateUnitsByGlobalGaps` 改用 `SimplificationSupport.computeGaps` + `findSimplifiedBlock`（传入 `bubbleRanges=List.of()`，因 CONTINUOUS 已校验无 bubble）。
- 删除两个策略中重复的 `Range` 内部类和 gaps 算法。
- 产出仍是 `BillingUnit`（`ContinuousStrategy.buildSimplifiedUnit`），用 `SimplifiedBlock.startK` / `cycleCount`。
- 头尾/优惠段仍走各自 `calculateBoundaryDriven`（不变）。
- **行为不变**（无 bubble，effective = 自然），仅为消除重复 + 为 PERIOD 复用铺路。

### 步骤 4：PERIOD 简化（DurationSupport + DurationPeriodStrategy）

`DurationSupport` 新增：
- `DurationResult buildPeriodModeSimplified(segments, cycleCap, semantics, config, cycleOrigin, freeTimeRanges, threshold)`：
  1. 从 `freeTimeRanges` 分离 `bubbleRanges`（rangeType=BUBBLE）
  2. `computeGaps(calcBegin, calcEnd, freeTimeRanges)` 算 gaps
  3. 遍历 gaps：
     - gap 之前的优惠段（含 bubble）→ 走 `buildPeriodMode` 详细（子区间）
     - `findSimplifiedBlock(gap, calcBegin, bubbleRanges, cycleMinutes, threshold)`：
       - 返回 SimplifiedBlock → 头尾片段走 `buildPeriodMode` 详细，中间完整周期块 → `buildSimplifiedDurationSegment`
       - 返回 null → 整个 gap 走 `buildPeriodMode` 详细
  4. 末尾优惠段走 `buildPeriodMode` 详细
  5. 合并所有段，`chargedAmount` = 简化段 chargedAmount 之和 + 详细段 totalCharged
- `DurationSegment buildSimplifiedDurationSegment(begin, end, cycleCount, cycleCap)`：
  - `periodLabel = "SIMPLIFIED"`，`chargedMinutes = cycleCount * cycleMinutes`
  - `unitPrice = cycleCap`，`chargedAmount = cycleCap × cycleCount`
  - `periodCap = null`，`freePromotionId = null`，`originalAmount = cycleCap × cycleCount`

`DurationPeriodStrategy.calculate` 改造：
- 简化启用判断：`isSimplificationEnabled(config, resolver, context, cycleCap) && threshold > 0 && !hasFreeMinutes`（与 CONTINUOUS 一致，FREE_MINUTES 时保守不简化）
- 启用时调用 `buildPeriodModeSimplified`，否则走现有 `buildPeriodMode`
- `BillingSegmentResult` 构造不变（`durationSegments` 含简化段 + 详细段混合）

### 步骤 5：测试

`bill-test` 新增：
- `PeriodSimplificationTest`：
  - 无 bubble 长周期简化（与 CONTINUOUS 简化对账，验证金额一致）
  - 有 bubble 长周期简化（effective 边界后移，验证简化段位置 + 金额）
  - 短周期不简化（< 阈值，走详细）
  - FREE_MINUTES 存在时不简化
  - 简化段 + 头尾详细段混合，总额正确
- `ContinuousBubbleValidationTest`：配 BUBBLE 免费段 + CONTINUOUS 模式 → 抛异常

## 不做（范围控制）

- **GLOBAL 简化**：用户未要求。GLOBAL 段跨周期合并，段数已较少，简化收益小。可在后续单独评估。
- **RelativeTime/NaturalTime CONTINUOUS 简化**：当前无，用户未要求。提取 `SimplificationSupport` 后补上成本低，但超出本次范围。

## 涉及文件

**新增**：
- `core/.../charge/rules/SimplificationSupport.java`
- `bill-test/.../PeriodSimplificationTest.java`
- `bill-test/.../ContinuousBubbleValidationTest.java`

**修改**：
- `core/.../charge/rules/ContinuousStrategy.java`（新增 `assertNoBubbleSupported`）
- `core/.../charge/rules/daynight/DayNightContinuousStrategy.java`（bubble 校验 + 重构用 SimplificationSupport）
- `core/.../charge/rules/compositetime/CompositeTimeContinuousStrategy.java`（bubble 校验 + 重构用 SimplificationSupport）
- `core/.../charge/rules/relativetime/RelativeTimeContinuousStrategy.java`（bubble 校验）
- `core/.../charge/rules/naturaltime/NaturalTimeContinuousStrategy.java`（bubble 校验）
- `core/.../charge/rules/DurationSupport.java`（`buildPeriodModeSimplified` + `buildSimplifiedDurationSegment`）
- `core/.../charge/rules/DurationPeriodStrategy.java`（简化入口分派）

## 验证

- `mvn install -pl core -am` 编译通过
- `mvn test -pl bill-test -am` 新增测试通过
- 现有 `BubbleFreeRangeTest` / `DayNightCycleBoundaryTest` / `BillingPlaygroundTest` 不回归
- 简化路径与详细路径金额对账（无 bubble 时简化总额 = 详细总额）
