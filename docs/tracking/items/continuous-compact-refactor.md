# CONTINUOUS 直接产出 compact + 移除 CompactMerger

- ID: TODO-20260708-001
- 类型: refactor
- 优先级: P2
- source_git: 8d4c20f
- 状态: done
- completed_at: 2026-07-09
- completed_git: （见实现提交）

## 实施结果（2026-07-09 完成）

### 方案（snap + 时间点定价）
原受阻项 1 的解法：改造日夜边界 Provider，移除单元对齐后通过 snap + 时间点定价保证正确性。

- **日夜边界 snap 到 unit edge**（`splitDayNightBoundary=false`）：跨越单元 `[unitStart, unitEnd]` 整体归入 `blockWeight` 占优侧同质段。snap 方向按占优侧与边界类型（dayEnd/dayBegin）判定：day 占优 + dayEnd -> snap unitEnd（归前段 day）；day 占优 + dayBegin -> snap unitStart（归后段 day）；night 占优 + dayEnd -> snap unitStart（归后段 night）；night 占优 + dayBegin -> snap unitEnd（归前段 night）。边界恰好落在 unit edge 时非跨越单元，直接用原边界。
- **时间点定价（不用窗口算 dayMinutes）**：snap 到 unitStart 时后段起点落在 b 前非占优侧，段起点时间点会给错单价，故 snap provider 在 `snapUsDayFlag` 记录归属；`buildSegmentForDayNight` 段归属 = `snapUsDayFlag.getOrDefault(current, isInDay(current))`，`unitPrice = day ? dayUnitPrice : nightUnitPrice`。其余段（纯段 / snap unitEnd 前段）起点必在占优侧，用段起点时间点 `isInDay(current)` 即可。
- BLOCK_WEIGHT（默认）下 snap 归属等价原窗口判定，金额回归不变（`DayNightParkingParityTest` 69.50、`DayNightContinuousCrossPeriodTest` 9.40/10.90 均保持）。

### 已完成
- **移除 4 个 CONTINUOUS 策略的单元对齐 provider**（DayNight/RelativeTime/NaturalTime/CompositeTime）。DayNight 日夜边界 snap 到 unit edge；其余 3 策略 period 边界切断，段不跨 period，定价用 period 价（时间点等价）。
- **`applyCapAndAccumulate` 一段多 BillingUnit**：段拆为 compact（`subCount` 个整单元）+ truncated（`remainder` 余数）。`isTruncated = remainder > 0`（任何段），修复 PROPORTIONAL 非末段不足段 bug。免费段/封顶/末段不足免费整段免费不拆分。
- **封顶 budget 分拆**：cycleCap 与 periodCap 统一为 budget-based（取最严约束），封顶时 compact 拆为收费整单元 + 部分削减单元 + 免费单元，保留 cap 免费标记（`CYCLE_CAP`/`PERIOD_CAP`）。移除 post-hoc `applyPeriodCapToUnits`/`recomputeAccumulatedAmounts`。
- **移除 `CompactMerger`**：`ResultAssembler` 直接 `flatMap`，跨分段不合并（保留分段边界，更直观）。删除 `CompactMerger.java` + `CompactMergerTest.java`。
- 全量 117 个测试通过，无回归。

### 行为变化
- CONTINUOUS 长周期直接产出 compact（少边界、少对象、少迭代）。
- 跨分段连续相同单元不再合并（保留分段边界）。
- `DayNightUnitBased`（UNIT_BASED）移除 CompactMerger 后不合并，每单元独立产出（保留单元边界）。
- PROPORTIONAL 非末段不足段按比例收费（bug 修复，FULL_CHARGE 不受影响）。

## 验证命令
- 编译：`./mvnw -pl core,bill-test -am compile`
- 全量测试：`./mvnw test`（117 通过）

## 背景

### 现状
- `BoundaryDrivenLoop` 含**单元对齐 provider**，每 `unitMinutes` 切断一次 -> 同质段 ≤ 单元
- `applyCapAndAccumulate` 一段一 BillingUnit（`subCount` 通常 = 1）
- `CompactMerger` 在 `ResultAssembler` 中**跨分段**合并连续相同单元

### 问题
1. 长周期多迭代（每单元切断）
2. 多 BillingUnit 对象（每单元一个，再合并）
3. `CompactMerger` 跨分段合并丢失分段边界（不直观）
4. **PROPORTIONAL 非末段不足段 bug**：`isTruncated` 只末段判定，免费段切断单元产生的非末段不足段（如 19:30-20:00，30min）`isTruncated=false`，PROPORTIONAL 模式下按全额 `unitPrice` 收（应按比例）

## 方案

### 1. 移除单元对齐 provider（4 个 CONTINUOUS 策略）
- `DayNightContinuousStrategy.calculateBoundaryDriven`
- `RelativeTimeContinuousStrategy.calculate`
- `CompositeTimeContinuousStrategy.calculateBoundaryDriven`
- `NaturalTimeContinuousStrategy.calculate`

移除后边界只剩：`cycleEnd` + 时段边界（日夜/period/自然时段）+ `freeRangeEdges` + `calcEnd`。同质段 = 其他边界切断的片段，可能 > 1 单元、= 1 单元、< 1 单元。DayNight 日夜边界 snap 到 unit edge，保证段边界对齐 unit、跨越单元归占优侧。

### 2. applyCapAndAccumulate 改造（一段多 BillingUnit）

一段产出 compact（`subCount>0`）+ truncated（`remainder>0`）：

```
for seg:
  segMinutes = seg.durationMinutes()
  unitMinutes = semantics.unitMinutes(...)
  subCount = segMinutes / unitMinutes
  remainder = segMinutes % unitMinutes

  # 免费段/封顶/不足免费：整段免费（不拆分）
  if seg.isFree() || cycleCapped || periodCapped || incompleteFree:
    产出 1 个免费 BillingUnit（segMinutes，charged=0）
    continue

  # combined 预算（cycleCap 与 periodCap 取最严）
  budget = min(cycleBudget, periodBudget)

  # compact 部分（subCount > 0：整单元，封顶时拆为收费 + 部分削减 + 免费）
  if subCount > 0:
    fullTotal = originalPerSub * subCount
    compactCharged = min(fullTotal, budget)
    if compactCharged < fullTotal:  # 封顶削减，按 unitPrice 拆分
      产出 charged compact + partial unit + free unit
    else:
      产出 compact BillingUnit（subCount 单元，compact=subCount>1，charged=fullTotal）

  # truncated 部分（remainder > 0：不足单元）
  if remainder > 0:
    truncCharged = min(computeIncompleteCharge(...), budget)
    产出 truncated BillingUnit（remainder min，isTruncated=true，charged=truncCharged）
```

关键改变：
- **isTruncated = remainder > 0**（任何段，非仅末段）-> 修复 PROPORTIONAL 非末段不足段 bug
- **免费段不拆分**（整段免费，保持现状语义）
- **封顶 budget 取 cycleCap/periodCap 最严约束**，封顶时 compact 拆分保留 cap 免费标记
- **compact 直接产出**（`subCount > 1`，无需 CompactMerger）

### 3. 移除 CompactMerger
- `ResultAssembler.assemble`：移除 `CompactMerger.merge`，直接 `flatMap`
- 删除 `CompactMerger.java` + `CompactMergerTest.java`

## 与 PERIOD 时长模式的关系

思路类似（边界驱动切断无单元对齐 + 段内整除部分 + 余数部分），可参考 `DurationSupport.chargeByMode` 的整除+余数逻辑。但**不照搬 PERIOD**：
- PERIOD 产出 1 个 `DurationSegment`（整除+余数合并为一个 chargedAmount）
- 本方案产出 2 个 `BillingUnit`（compact + truncated 分开，保留 compact 多单元标记 + isTruncated 标记）

保持原设计（compact + truncated 分开），以保留标记清晰性。

## 待讨论问题（已确认）

1. ~~**PROPORTIONAL 行为改变**：修复非末段不足段 bug（按比例），但可能破坏依赖现状（全额）的测试。是否接受？~~
   **结论：接受修复。** 这是明确的 bug，`isTruncated = remainder > 0`，任何段有不足单元部分都按比例收费。FULL_CHARGE 不受影响。

2. ~~**DayNightUnitBased**：不用 BoundaryDrivenLoop，移除 CompactMerger 后连续相同单元不合并。是否需单独改造（内部 compact）？~~
   **结论：接受不合并。** 保留分段边界，更直观，不增加代码量。

3. ~~**CompactConsistencyAssert 不兼容封顶削减**：`subCharged × count == chargedAmount` 假设 compact 未封顶。改造后封顶削减的 compact `chargedAmount = budget < unitPrice × subCount`，断言失败。需调整。~~
   **结论：放宽为 `≤`。** 原则：结果正确、逻辑简单、输出易懂。`chargedAmount <= unitPrice × count`，允许封顶削减。

4. ~~**产出形式**：compact + truncated 分开（原设计）vs 合并为 1 个 BillingUnit（像 PERIOD）。~~
   **结论：分开。** compact 保留 `compact=true` 标记 + `count`，truncated 保留 `isTruncated=true`。一段最多多个 BillingUnit，标记清晰，查询语义明确。

5. ~~**跨分段不合并**：移除 CompactMerger，分段边界保留。是否符合直观性偏好？~~
   **结论：接受不合并。** 分段边界保留，直观，与问题 2 结论一致。

## 影响评估

### DayNightUnitBasedStrategy
不用 BoundaryDrivenLoop，固定单元对齐。移除 CompactMerger 后不合并。建议接受（直观）。

### 简化路径（generateUnitsByGlobalGaps）
头尾/优惠段走 `calculateBoundaryDriven` -> `applyCapAndAccumulate`，改造后一段多 BillingUnit。简化段（`buildSimplifiedUnit`）不调 `applyCapAndAccumulate`，不受影响。

### PROPORTIONAL 行为改变
| 场景 | 现状 | 改造后 |
|------|------|--------|
| 末段不足（isTruncated） | PROPORTIONAL 按比例 ✓ | 不变 ✓ |
| 非末段不足（免费段切断） | isTruncated=false，全额 ✗ | isTruncated=true，按比例 ✓（修复） |
| FULL_CHARGE 不足段 | unitPrice（全额） | 不变 |

### 跨分段不合并
分段边界保留，直观。

## 测试影响

| 测试 | 影响 |
|------|------|
| `CompactMergerTest` | 删除 |
| `CompactParityAndConsistencyTest` | 金额不变；自洽性通过 |
| `DayNightUnitBasedRuleTest` | fixedAlignment 期望改为 2 个独立单元（不合并） |
| `DayNightParkingParityTest` | 金额不变（69.50），单元数减少（compact） |
| `DayNightContinuousCrossPeriodTest` | 金额不变（9.40/10.90） |
| `FreeMinutesMaterializationTest` | CONTINUOUS 用例无不足段，不受影响 |
| `DurationBillingModeTest` | PERIOD/GLOBAL，不受影响 |
| `CompositeTimeSmokeTest` | unitBased 期望改为 1 compact（CONTINUOUS compact） |
| `CompositeTimePeriodCapTest` | periodCap 封顶分拆，通过 |

## 涉及文件

### 修改
- `core/.../charge/rules/ContinuousStrategy.java`（`applyCapAndAccumulate` 改造：一段多 BillingUnit + 封顶分拆）
- `core/.../charge/rules/daynight/DayNightContinuousStrategy.java`（移除单元对齐 + snap + 时间点定价）
- `core/.../charge/rules/relativetime/RelativeTimeContinuousStrategy.java`（移除单元对齐）
- `core/.../charge/rules/compositetime/CompositeTimeContinuousStrategy.java`（移除单元对齐）
- `core/.../charge/rules/naturaltime/NaturalTimeContinuousStrategy.java`（移除单元对齐）
- `core/.../settlement/ResultAssembler.java`（移除 `CompactMerger.merge`，直接 flatMap）

### 删除
- `core/.../charge/rules/CompactMerger.java`
- `bill-test/.../CompactMergerTest.java`

### 测试更新
- `CompactParityAndConsistencyTest`（一致性验证）
- `DayNightUnitBasedRuleTest`（fixedAlignment 不合并）
- `CompositeTimeSmokeTest`（unitBased compact）

### 文档
- `docs/superpowers/archive/billing-engine-current-flow-zh.md` / `calculation-flow-zh.md`（流程图移除 CompactMerger）
- `billing-engine-capabilities(-zh).md`（CompactMerger 能力移除）
- `USER_GUIDE.md`（compact 说明）
- `AGENTS.md`（关键类列表移除 CompactMerger）

## 风险

1. `applyCapAndAccumulate` 改造复杂（一段多 BillingUnit，budget 拆分，免费段/封顶/不足单元交互）
2. PROPORTIONAL 行为改变（修复 bug，但可能破坏测试）
3. DayNightUnitBased 不合并（输出更多单元）
4. 简化路径 + 改造后 `applyCapAndAccumulate` 交互需验证
5. `CompactConsistencyAssert` 不兼容封顶削减
6. `periodCap` 削减对 compact + truncated 单元需验证

## 收益

1. 少边界（移除单元对齐），少迭代
2. 少 BillingUnit 对象（直接 compact）
3. 移除 CompactMerger（少步骤、少代码）
4. 保留分段边界（直观）
5. 修复 PROPORTIONAL 非末段不足段 bug
6. 统一逻辑（subCount + remainder）

## 参考

- 详细方案：`/.claude/plans/continuous-compact-refactor.md`、`/.claude/plans/continuous-compact-impl.md`
- PERIOD 整除+余数逻辑参考：`DurationSupport.chargeByMode`
