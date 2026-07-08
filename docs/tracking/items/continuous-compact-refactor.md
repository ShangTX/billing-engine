# CONTINUOUS 直接产出 compact + 移除 CompactMerger

- ID: TODO-20260708-001
- 类型: refactor
- 优先级: P2
- source_git: 8d4c20f
- 状态: 待讨论

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

移除后边界只剩：`cycleEnd` + 时段边界（日夜/period/自然时段）+ `freeRangeEdges` + `calcEnd`。同质段 = 其他边界切断的片段，可能 > 1 单元、= 1 单元、< 1 单元。

### 2. applyCapAndAccumulate 改造（一段多 BillingUnit）

一段产出 compact（`subCount>0`）+ truncated（`remainder>0`）：

```
for seg:
  segMinutes = seg.durationMinutes()
  unitMinutes = semantics.unitMinutes(...)
  subCount = segMinutes / unitMinutes
  remainder = segMinutes % unitMinutes

  # 免费段/封顶/不足免费：整段免费（不拆分）
  if seg.isFree() || cycleCapped || incompleteFree:
    产出 1 个免费 BillingUnit（segMinutes，charged=0）
    continue

  # compact 部分（subCount > 0：整单元）
  if subCount > 0:
    fullTotal = originalPerSub * subCount
    budget = max(0, maxCharge - cycleAccumulated)
    compactCharged = min(fullTotal, budget)
    产出 compact BillingUnit（subCount 单元，compact=subCount>1，charged=compactCharged）
    cycleAccumulated += compactCharged; accumulated += compactCharged

  # truncated 部分（remainder > 0：不足单元）
  if remainder > 0:
    truncCharged = computeIncompleteCharge(unitPrice, remainder, unitMinutes, mode)
    budget = max(0, maxCharge - cycleAccumulated)
    truncCharged = min(truncCharged, budget)
    产出 truncated BillingUnit（remainder min，isTruncated=true，charged=truncCharged）
    cycleAccumulated += truncCharged; accumulated += truncCharged
```

关键改变：
- **isTruncated = remainder > 0**（任何段，非仅末段）-> 修复 PROPORTIONAL 非末段不足段 bug
- **免费段不拆分**（整段免费，保持现状语义）
- **封顶 budget 在 compact + truncated 间分配**
- **compact 直接产出**（`subCount > 1`，无需 CompactMerger）

### 3. 移除 CompactMerger
- `ResultAssembler.assemble`：移除 `CompactMerger.merge`，直接 `flatMap`
- 删除 `CompactMerger.java` + `CompactMergerTest.java`

## 与 PERIOD 时长模式的关系

思路类似（边界驱动切断无单元对齐 + 段内整除部分 + 余数部分），可参考 `DurationSupport.chargeByMode` 的整除+余数逻辑。但**不照搬 PERIOD**：
- PERIOD 产出 1 个 `DurationSegment`（整除+余数合并为一个 chargedAmount）
- 本方案产出 2 个 `BillingUnit`（compact + truncated 分开，保留 compact 多单元标记 + isTruncated 标记）

保持原设计（compact + truncated 分开），以保留标记清晰性。

## 待讨论问题

1. **PROPORTIONAL 行为改变**：修复非末段不足段 bug（按比例），但可能破坏依赖现状（全额）的测试。是否接受？
2. **DayNightUnitBased**：不用 BoundaryDrivenLoop，移除 CompactMerger 后连续相同单元不合并。是否需单独改造（内部 compact）？建议接受不合并（直观）。
3. **CompactConsistencyAssert 不兼容封顶削减**：[第 49 行](../../../bill-test/src/test/java/cn/shang/charging/CompactConsistencyAssert.java) `subCharged × count == chargedAmount` 假设 compact 未封顶。改造后封顶削减的 compact `chargedAmount = budget < unitPrice × subCount`，断言失败。需调整（允许 `chargedAmount <= unitPrice × count`）。
4. **产出形式**：compact + truncated 分开（原设计）vs 合并为 1 个 BillingUnit（像 PERIOD）。原设计保留标记清晰性，但一段两 BillingUnit。
5. **跨分段不合并**：移除 CompactMerger，分段边界保留。符合直观性偏好。

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
| `CompactParityAndConsistencyTest` | 金额不变；`CompactConsistencyAssert` 封顶削减断言需调整；`dayNight_compactWithDailyCap` 会失败 |
| `DayNightUnitBasedRuleTest` | 注释更新；compact 验证更新（不再合并） |
| `DayNightParkingParityTest` | 金额不变，单元数减少（合并） |
| `DayNightContinuousCrossPeriodTest` | 金额不变，单元合并 |
| `FreeMinutesMaterializationTest` | CONTINUOUS 用例无不足段，不受影响 |
| `DurationBillingModeTest` | PERIOD/GLOBAL，不受影响 |

## 涉及文件

### 修改
- `core/.../charge/rules/ContinuousStrategy.java`（`applyCapAndAccumulate` 改造）
- `core/.../charge/rules/daynight/DayNightContinuousStrategy.java`（移除单元对齐）
- `core/.../charge/rules/relativetime/RelativeTimeContinuousStrategy.java`（移除单元对齐）
- `core/.../charge/rules/compositetime/CompositeTimeContinuousStrategy.java`（移除单元对齐）
- `core/.../charge/rules/naturaltime/NaturalTimeContinuousStrategy.java`（移除单元对齐）
- `core/.../settlement/ResultAssembler.java`（移除 `CompactMerger.merge`）

### 删除
- `core/.../charge/rules/CompactMerger.java`
- `bill-test/.../CompactMergerTest.java`

### 测试更新
- `CompactParityAndConsistencyTest`（封顶一致性）
- `DayNightUnitBasedRuleTest`
- 其他单元数断言

### 文档
- `billing-engine-current-flow-zh.md` / `calculation-flow-zh.md`（流程图移除 CompactMerger）
- `billing-engine-capabilities(-zh).md`（CompactMerger 能力移除）
- `USER_GUIDE.md`（compact 说明）

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

- 详细方案：`/.claude/plans/continuous-compact-refactor.md`
- PERIOD 整除+余数逻辑参考：`DurationSupport.chargeByMode`
