# CONTINUOUS compact 重构实施计划

承接 [continuous-compact-refactor.md](../../docs/tracking/items/continuous-compact-refactor.md) 的受阻项，按用户方案完成：改造日夜边界 Provider（snap + 时间点定价），打通"移除单元对齐 -> 一段多 BillingUnit -> 移除 CompactMerger"链路。

## 核心方案（用户确认）

**snap + 时间点定价**：日夜边界 snap 到 unit edge，跨越单元归占优侧同质段；snap 时记录归属，段构造直接用归属单价，**不再用窗口算 dayMinutes**。

- snap 维护 `snapUsFlags: Map<LocalDateTime, Boolean>`（snap 到 us 的后段记录归属 day?）。
- 段归属 = `snapUsFlags.getOrDefault(current, isInDay(current))`。
  - snap us 后段（占优侧落于 b 后，段起点在 b 前非占优侧）：用 snap 归属。
  - 其余段（纯段 / snap ue 前段，起点在占优侧）：用段起点时间点 `isInDay(current)`。
- `unitPrice = day ? dayUnitPrice : nightUnitPrice`。
- BLOCK_WEIGHT（默认 + 全部测试）行为不变：snap 归属 = dayMinutes>=blockWeight，等价原窗口判定。

## 实施步骤

### 步骤1：DayNight snap 逻辑修复 + 时间点定价

文件：`core/.../daynight/DayNightContinuousStrategy.java`

- **修复编译错误**：`countDayMinutes` 未定义 -> 用 `priceResolver.calculateDayNightMinutes(unitStart, unitEnd, config)[0]`。
- **修复 snap 方向**（现反了）：占优侧归属。
  - `belongsToDay = dayMinutes >= blockWeight × duration`
  - `usInDay = isInDay(unitStart)`（判 b 是 dayEnd[us in day] 还是 dayBegin[us in night]）
  - `snapped = belongsToDay ? (usInDay ? ue : us) : (usInDay ? us : ue)`
- **修复 b==unit edge 不 snap**：`exactBoundary.equals(unitStart)||equals(unitEnd)` 时直接用 exactBoundary。
- **snap 用完整 unit** `[unitStart, unitEnd]` 算 dayMinutes（不再用 effectiveEnd 截断）。
- **记录 snapUsFlags**：snap==us 时 `snapUsFlags.put(us, belongsToDay)`。
- **buildSegmentForDayNight 改时间点定价**：
  ```
  boolean day = snapUsFlags.getOrDefault(current, isInDay(current, dayBeginMin, dayEndMin));
  BigDecimal unitPrice = day ? config.getDayUnitPrice() : config.getNightUnitPrice();
  ```
  移除 `determineUnitPriceForContinuous(current, next)` 调用（该函数保留供 priceAt/时长模式用）。
- `snapUsFlags` 在 `calculateBoundaryDriven` 内创建，snap provider lambda 与 segmentBuilder lambda 闭包共享。
- split=true（默认）：snapUsFlags 空，exactBoundary 切断，段纯，`isInDay(current)` 定价 ✓。

### 步骤2：applyCapAndAccumulate 一段多 BillingUnit（compact + truncated）

文件：`core/.../charge/rules/ContinuousStrategy.java`

改造 `applyCapAndAccumulate` 段处理（替换现"一段一 BillingUnit"）：
```
subCount = segMinutes / unitMinutes
remainder = segMinutes % unitMinutes
isLastShort = isLast && seg.end==calcEnd && subCount==0   // 末段不足单元

// periodCap 切换（不变）

cycleCapped = !free && maxCharge>0 && cycleAccumulated>=maxCharge
incompleteFree = isLastShort && !free && !cycleCapped && isIncompleteFree(segMinutes, unitMinutes, mode, ...)

if (free || cycleCapped || incompleteFree):
    产出 1 免费单元（segMinutes, charged=0, isTruncated=(subCount==0&&remainder>0)）
    // cycleAccumulated 不变（免费/封顶不累计）
    // 周期切换判定
    continue

if (subCount > 0):   // compact 部分
    fullTotal = originalPerSub × subCount
    budget = max(0, maxCharge - cycleAccumulated)
    compactCharged = min(fullTotal, budget).setScale(2, HALF_UP)
    产出 compact（duration=subCount×unitMinutes, originalAmount=fullTotal, charged=compactCharged,
                 compact=(subCount>1), count=subCount, isTruncated=false, begin=seg.begin, end=seg.begin+subCount×unitMinutes）
    accumulated += compactCharged; cycleAccumulated += compactCharged

if (remainder > 0):   // truncated 部分
    truncOrig = computeIncompleteCharge(unitPrice, remainder, unitMinutes, mode, ...)
    budget = max(0, maxCharge - cycleAccumulated)
    truncCharged = min(truncOrig, budget).setScale(2, HALF_UP)
    truncFree = isIncompleteFree(remainder, ...) || truncCharged==0
    产出 truncated（duration=remainder, unitPrice, originalAmount=truncOrig, charged=truncCharged,
                    compact=false, count=1, isTruncated=true, free=truncFree,
                    begin=seg.begin+subCount×unitMinutes, end=seg.end）
    accumulated += truncCharged; cycleAccumulated += truncCharged

// 周期切换判定（seg.endTime）
```

关键：
- `isTruncated = remainder > 0`（任何段）-> 修复 PROPORTIONAL 非末段不足段 bug（已部分改，此步骤统一）。
- 免费段/封顶/末段不足免费：整段免费（1 单元），不拆分。
- budget 在 compact→truncated 间分配（compact 先扣）。
- compact 子单元等长（unitMinutes），`CompactConsistencyAssert` 子单元自洽。
- 删除策略尾部重复的 isTruncated/accumulated 重算逻辑（DayNight/Relative/Natural/Composite 的 calculate 尾部）-- 由 applyCapAndAccumulate 统一产出，尾部重算冗余但保留也无害；视测试决定是否清理。

### 步骤3：移除其他 3 策略的单元对齐 provider

- `RelativeTimeContinuousStrategy.calculate`：删除第 4 个 provider（单元对齐，108-122 行）。定价已用 `period.getUnitPrice()`（时间点），移除安全。
- `NaturalTimeContinuousStrategy.calculate`：删除第 3 个 provider（单元对齐，96-104 行）。period 边界切断，段不跨 period，`calculateUnitPrice(current,next)` 返回 period 价（时间点等价）。
- `CompositeTimeContinuousStrategy.calculateBoundaryDriven`：删除第 4 个 provider（单元对齐，263-272 行）。同 NaturalTime。

### 步骤4：移除 CompactMerger

- `core/.../settlement/ResultAssembler.java`：`assemble` 移除 `CompactMerger.merge`，直接 `flatMap`。
- 删除 `core/.../charge/rules/CompactMerger.java`。
- 删除 `bill-test/.../CompactMergerTest.java`。

### 步骤5：测试更新

- **删除** `CompactMergerTest.java`。
- `CompactConsistencyAssert.assertCompactConsistent`：封顶削减断言放宽 `subCharged × count == chargedAmount` -> `<=`（封顶削减时 chargedAmount < unitPrice×count）。
- `CompactParityAndConsistencyTest`：金额不变（compact 仍产出），自洽性验证通过；`dayNight_compactWithDailyCap` 验证封顶 budget 拆分。
- `DayNightUnitBasedRuleTest.fixedAlignment_basicCalculation`：移除 CompactMerger 后 UNIT_BASED 不合并 -> 期望改为 2 个独立单元（非 compact），或给 DayNightUnitBased 加内部 compact（**默认接受不合并**，更新断言）。
- `DayNightContinuousCrossPeriodTest`：验证 9.40 / 10.90 不变。
- `DayNightParkingParityTest`：验证 69.50 不变（snap + 时间点定价等价原 BLOCK_WEIGHT 窗口）。
- `DayNightParkingParity`/`RelativeTimeParkingParity` 等：单元数减少（compact），金额不变。
- 全量测试：`./mvnw test`。

### 步骤6：文档同步

- `docs/tracking/items/continuous-compact-refactor.md`：状态 -> done，记录 snap + 时间点定价方案 + completed_git。
- `docs/TODO.md`：删除 TODO-20260708-001 行。
- `docs/DONE.md`：追加完成记录。
- `docs/billing-engine-calculation-flow-zh.md` / `billing-engine-current-flow-zh.md`：流程图移除 CompactMerger；CONTINUOUS 段说明改为 snap + 时间点定价 + 一段多 BillingUnit。
- `docs/billing-engine-capabilities.md` + `-zh.md`：移除 CompactMerger 能力；CONTINUOUS compact 直接产出说明。
- `docs/USER_GUIDE.md`：compact 说明更新（段内直接产出，不跨分段合并）。
- `AGENTS.md`：关键类列表移除 CompactMerger（140、225 行）。
- `.claude/plans/continuous-compact-refactor.md`：标注实施完成，指向本文件。

## 验证命令

- 编译：`./mvnw -pl core,bill-test -am compile`
- 全量测试：`./mvnw test`
- 重点测试：`./mvnw test -pl bill-test -Dtest=DayNightContinuousCrossPeriodTest,DayNightParkingParityTest,CompactParityAndConsistencyTest,DayNightUnitBasedRuleTest,CompactMergerTest`

## 风险与回退

1. **DayNightParkingParityTest 69.50**：snap 方向修正 + 时间点定价，BLOCK_WEIGHT 下应等价。若失败，对比 snap 边界与旧逐单元归属差异。
2. **applyCapAndAccumulate 复杂交互**：免费段 + 封顶 + 不足 + periodCap。逐场景验证。
3. **DayNightUnitBased 不合并**：测试断言更新；若需合并，单独加内部 compact（独立于本次）。
4. **简化路径**：`generateUnitsByGlobalGaps` 头尾片段走 applyCapAndAccumulate，一段多 BillingUnit 交互验证。
5. **crossPeriodMode 非 BLOCK_WEIGHT + split=false**：本次统一为 BLOCK_WEIGHT snap 语义（测试仅覆盖 BLOCK_WEIGHT）；若需保留其他模式，后续扩展。
