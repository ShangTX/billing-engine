# CONTINUOUS 直接产出 compact + 移除 CompactMerger 方案

## 背景与问题

### 现状
- `BoundaryDrivenLoop` 含**单元对齐 provider**，每 `unitMinutes` 切断一次 -> 同质段 ≤ 单元
- `applyCapAndAccumulate` 一段一 BillingUnit（`subCount = segMinutes / unitMinutes`，通常 = 1）
- `CompactMerger` 在 `ResultAssembler` 中**跨分段**合并连续相同单元

### 问题
1. **多迭代**：长周期每单元切断，BoundaryDrivenLoop 迭代 O(单元数)
2. **多对象**：每单元一个 BillingUnit，再合并
3. **跨分段合并丢失分段边界**：CompactMerger 合并分段 1 末 + 分段 2 首，分段边界消失，不直观
4. **PROPORTIONAL 非末段不足段 bug**：`isTruncated` 只末段判定，免费段切断单元产生的非末段不足段（如 19:30-20:00，30min）`isTruncated=false`，PROPORTIONAL 模式下按全额 `unitPrice` 收（应按比例）

## 方案

### 1. 移除单元对齐 provider（4 个 CONTINUOUS 策略）

| 策略 | 位置 |
|------|------|
| DayNightContinuousStrategy | `calculateBoundaryDriven` 第 4 个 provider |
| RelativeTimeContinuousStrategy | `calculate` 第 4 个 provider |
| CompositeTimeContinuousStrategy | `calculateBoundaryDriven` 第 4 个 provider |
| NaturalTimeContinuousStrategy | `calculate` 第 3 个 provider |

移除后边界只剩：`cycleEnd` + 时段边界（日夜/period/自然时段）+ `freeRangeEdges` + `calcEnd`。同质段 = 其他边界切断的片段，可能 > 1 单元、= 1 单元、< 1 单元。

### 2. applyCapAndAccumulate 改造（一段多 BillingUnit）

#### 现状（一段一 BillingUnit）
```
for seg:
  subCount = segMinutes / unitMinutes  // 通常 = 1
  isTruncated = 末段 && segMinutes < unitMinutes
  charged = 三支（免费/截断/正常）
  产出 1 个 BillingUnit
```

#### 改造后（一段产出 compact + truncated）
```
for seg:
  segMinutes = seg.durationMinutes()
  unitMinutes = semantics.unitMinutes(...)
  subCount = segMinutes / unitMinutes
  remainder = segMinutes % unitMinutes

  # 免费段/封顶/不足免费：整段免费（不拆分）
  if seg.isFree() || cycleCapped || incompleteFree:
    产出 1 个免费 BillingUnit（segMinutes，charged=0，free=true）
    continue

  # compact 部分（subCount > 0：整单元）
  if subCount > 0:
    fullTotal = originalPerSub * subCount
    budget = max(0, maxCharge - cycleAccumulated)
    compactCharged = min(fullTotal, budget)
    产出 compact BillingUnit:
      duration = subCount * unitMinutes
      unitPrice, originalAmount = fullTotal
      chargedAmount = compactCharged
      compact = (subCount > 1), count = subCount
      isTruncated = false
    cycleAccumulated += compactCharged; accumulated += compactCharged

  # truncated 部分（remainder > 0：不足单元）
  if remainder > 0:
    truncCharged = computeIncompleteCharge(unitPrice, remainder, unitMinutes, mode)
    budget = max(0, maxCharge - cycleAccumulated)
    truncCharged = min(truncCharged, budget)
    产出 truncated BillingUnit:
      duration = remainder
      unitPrice, originalAmount = computeIncompleteCharge...(同 charged 语义)
      chargedAmount = truncCharged
      compact = false, count = 1
      isTruncated = true
    cycleAccumulated += truncCharged; accumulated += truncCharged
```

#### 关键改变
- **isTruncated = remainder > 0**（任何段，非仅末段）-> 修复 PROPORTIONAL 非末段不足段 bug
- **免费段不拆分**（整段免费，保持现状语义）
- **封顶 budget 在 compact + truncated 间分配**（compact 先扣预算，truncated 再扣剩余）
- **compact 直接产出**（subCount > 1，无需 CompactMerger）
- **periodCap**：仍按 period 边界切断，period 内多单元 compact；`applyPeriodCapToUnits` 对 period 内的 compact + truncated 单元削减

### 3. 移除 CompactMerger

- [ResultAssembler.assemble](core/src/main/java/cn/shang/charging/settlement/ResultAssembler.java:39)：移除 `CompactMerger.merge`，直接 `flatMap` 所有分段 BillingUnit
- 删除 `CompactMerger.java`
- 删除 `CompactMergerTest.java`
- `DayNightUnitBasedRuleTest` 第 43 行注释更新

## 影响评估

### 4.1 DayNightUnitBasedStrategy
不用 BoundaryDrivenLoop，固定单元对齐（每 unitMinutes 一个 BillingUnit）。移除 CompactMerger 后连续相同单元**不合并**。
- **建议**：接受不合并（输出更多单元，但保留单元边界，直观）。如需合并，单独改造 DayNightUnitBased 内部 compact（但与 CONTINUOUS 改造独立）。

### 4.2 简化路径（generateUnitsByGlobalGaps）
- 头尾/优惠段走 `calculateBoundaryDriven` -> `applyCapAndAccumulate`。改造后一段多 BillingUnit，头尾片段可能产出 compact + truncated。OK。
- 简化段本身（`buildSimplifiedUnit`）不调 `applyCapAndAccumulate`，**不受影响**。

### 4.3 PROPORTIONAL 行为改变（修复 bug）
| 场景 | 现状 | 改造后 |
|------|------|--------|
| 末段不足（isTruncated） | PROPORTIONAL 按比例 ✓ | 按比例 ✓（不变） |
| 非末段不足（免费段切断，如 19:30-20:00 30min） | isTruncated=false，charged=unitPrice（全额）✗ | isTruncated=true，charged=按比例 ✓（修复） |
| FULL_CHARGE 不足段 | unitPrice（全额） | unitPrice（不变） |

**影响**：CONTINUOUS + PROPORTIONAL + 免费段切断单元的场景，金额可能变化（非末段不足段从全额变按比例）。需排查测试。

### 4.4 跨分段不合并
移除 CompactMerger，分段边界保留。`BillingResult.units` 含所有分段的单元（含 compact），不跨分段合并。更直观。

### 4.5 compact 标记
- `applyCapAndAccumulate` 的 `isCompact = subCount > 1`（段内多单元）-> 直接产出 compact
- `CompactMerger` 的跨段 compact -> 移除

## 测试影响

| 测试 | 影响 |
|------|------|
| `CompactMergerTest` | **删除**（CompactMerger 移除） |
| `CompactParityAndConsistencyTest` | 金额不变（compact 仍产出），`CompactConsistencyAssert` 一致性需验证（compact 子单元语义改变：段内 compact vs 跨段合并） |
| `DayNightUnitBasedRuleTest` | 注释更新；若验证 compact 数量需更新（DayNightUnitBased 不再合并） |
| `DayNightParkingParityTest` | CONTINUOUS + blockWeight，单元合并（compact），**金额不变**，单元数减少 |
| `DayNightContinuousCrossPeriodTest` | CONTINUOUS + split，单元合并，**金额不变**（split=true 切断后每段纯 day/night，合并；split=false 跨日夜归属后合并） |
| `FreeMinutesMaterializationTest` | CONTINUOUS + PROPORTIONAL + FREE_MINUTES，`continuous_freeMinutes_materialized` 无不足段（9:00-12:00 整 3 单元），**不受影响**；其他用例多为 GLOBAL/PERIOD |
| `DurationBillingModeTest` | PERIOD/GLOBAL 模式，`DurationSupport` 不用 CompactMerger，**不受影响** |
| `CompactParityAndConsistencyTest.dayNight_compactWithDailyCap` | 封顶场景，compact + 封顶削减，需验证改造后 budget 拆分正确 |
| 其他 CONTINUOUS 测试 | 单元数变化（合并），金额不变 |

## 涉及文件

### 修改
- `core/.../charge/rules/ContinuousStrategy.java`（`applyCapAndAccumulate` 改造：一段多 BillingUnit + budget 拆分）
- `core/.../charge/rules/daynight/DayNightContinuousStrategy.java`（移除单元对齐 provider）
- `core/.../charge/rules/relativetime/RelativeTimeContinuousStrategy.java`（移除单元对齐 provider）
- `core/.../charge/rules/compositetime/CompositeTimeContinuousStrategy.java`（移除单元对齐 provider）
- `core/.../charge/rules/naturaltime/NaturalTimeContinuousStrategy.java`（移除单元对齐 provider）
- `core/.../settlement/ResultAssembler.java`（移除 `CompactMerger.merge`，直接 flatMap）

### 删除
- `core/.../charge/rules/CompactMerger.java`
- `bill-test/.../CompactMergerTest.java`

### 测试更新
- `CompactParityAndConsistencyTest`（一致性验证调整）
- `DayNightUnitBasedRuleTest`（注释 + compact 验证）
- 其他受影响测试（单元数断言调整）

### 文档
- `docs/billing-engine-current-flow-zh.md`（流程图移除 CompactMerger）
- `docs/billing-engine-capabilities(-zh).md`（CompactMerger 能力移除）
- `docs/billing-engine-calculation-flow-zh.md`（流程图移除 CompactMerger）
- `docs/USER_GUIDE.md`（compact 说明更新）

## 风险

1. **applyCapAndAccumulate 改造复杂**：一段多 BillingUnit，budget 在 compact + truncated 间分配，免费段/封顶/不足单元交互。需充分测试。
2. **PROPORTIONAL 行为改变**：修复 bug，但可能破坏依赖现状（非末段不足段全额）的测试。需排查。
3. **DayNightUnitBased 不合并**：输出更多单元（用户接受直观性）。
4. **简化路径交互**：改造后 applyCapAndAccumulate 在简化路径头尾片段的行为需验证。
5. **CompactConsistencyAssert 不兼容封顶削减**：[第 49 行](bill-test/src/test/java/cn/shang/charging/CompactConsistencyAssert.java:49) `subCharged × count == chargedAmount` 假设 compact 未封顶。改造后封顶削减的 compact `chargedAmount = budget < unitPrice × subCount`，断言失败。`CompactParityAndConsistencyTest.dayNight_compactWithDailyCap` 会失败。需调整 `CompactConsistencyAssert`（允许封顶削减：`chargedAmount <= unitPrice × count`）或测试断言。
6. **periodCap 削减**：`applyPeriodCapToUnits` 对 period 内的 compact + truncated 单元削减，逻辑需验证。

## 收益

1. **少边界**：移除单元对齐 provider，BoundaryDrivenLoop 迭代 O(边界数) << O(单元数)
2. **少对象**：长周期直接产出 1 个 compact，不是 N 个单元再合并
3. **移除 CompactMerger**：少一个步骤、少一个类、少测试
4. **保留分段边界**：跨分段不合并，直观
5. **修复 PROPORTIONAL bug**：非末段不足段按比例（一致性）
6. **统一逻辑**：subCount + remainder 处理所有段，不区分末段/非末段

## 实施步骤

1. **改造 applyCapAndAccumulate**：一段产出 compact + truncated，budget 拆分，免费段/封顶/不足交互。单元测试验证。
2. **移除 4 个 CONTINUOUS 策略的单元对齐 provider**。
3. **移除 CompactMerger**：ResultAssembler 改直接 flatMap，删除 CompactMerger + Test。
4. **更新测试**：CompactParityAndConsistencyTest 一致性、DayNightUnitBasedRuleTest、其他单元数断言。
5. **全量测试验证**：金额不变（除 PROPORTIONAL 非末段不足段修复），单元数变化。
6. **文档更新**：流程图、能力说明、USER_GUIDE。

## 待确认（实施时）

- CompactConsistencyAssert 的具体校验内容（是否兼容段内 compact）
- CONTINUOUS + PROPORTIONAL + 免费段切断的现有测试（是否有金额断言依赖现状）
- DayNightUnitBased 是否需内部 compact（建议不接受合并，保持直观）
