# DayNight 日夜边界 Provider 与定价架构改造

## 元数据

- **ID**: TODO-20260710-001
- **类型**: refactor
- **优先级**: P1
- **状态**: done
- **创建时间**: 2026-07-10
- **创建提交**: af29fb4
- **完成时间**: 2026-07-15
- **完成提交**: 709ae21
- **相关测试**:
  - `DayNightContinuousCrossPeriodTest`
  - `DayNightParkingParityTest`

## 背景

DayNight CONTINUOUS 模式 `splitDayNightBoundary=false` 需要重新设计边界生成和定价架构。

### 当前问题

1. **Snap 逻辑错误**：使用固定的 `cycleOriginBegin` 锚点导致边界对齐错误，免费时段后单元边界未重新对齐
2. **价格重复判断**：在段构建时重复计算 `isInDay()`，效率低
3. **架构不够通用**：难以支持其他规则（如 RelativeTime）

## 当前决策

### 核心思路：公共循环纯调度，规则内定价

已放弃外部 `PricingState` / `ThreadLocal` 方案。原因是
`splitDayNightBoundary=false` 下，一个跨日夜单元的价格由完整单元
`[unitStart, unitEnd)` 的 day/night 占比决定，不一定等于段起点所在时段。
首个计费单元跨 `dayBegin` 且按 `blockWeight` 归属后一个日夜分段时，外部初始状态会天然失真。

当前方案：

1. `BoundaryDrivenLoop` 只负责调度：从所有 `BoundaryProvider` 找最近边界并产出同质段。
2. `BoundaryProvider` 只负责提供边界，不修改计费状态。
3. 每个规则族在自己的 segment builder / semantics 中计算该段价格。
4. DayNight 的 snap 归属逻辑保留在 `DayNightContinuousStrategy` 内，不扩散到其他规则。

### 优点

- **语义清晰**：边界是调度结果，价格是规则私有语义。
- **DayNight 特例隔离**：只有 DayNight 需要 snap 归属，其他规则仍直接截断边界。
- **避免副作用**：移除 `ThreadLocal` 和 provider 修改状态，`findNearest` 可安全查询多个 provider。
- **可推广**：其他规则沿用“provider 给边界、segment builder 定价”的模式。

## 实现细节

### 1. splitDayNightBoundary=true（切断模式）

**行为**：在精确的 dayBegin/dayEnd 处切断，生成纯 day 段或 night 段

**实现要点**：
- 直接返回精确的日夜边界时间点（如 08:00、20:00）
- 段构建器按 `[current, next)` 计算价格
- 边界造成的不足单元由 `IncompleteUnitChargeMode` 处理

### 2. splitDayNightBoundary=false（跨时段归属模式）

**行为**：日夜边界 snap 到单元边界，跨时段单元整体归属到占优侧

**参数说明**：
- `current`：当前段的起点时间（动态，来自 BoundaryDrivenLoop 循环中的 current 参数，即 HomogeneousSegment 的起点）
- `exactBoundary`：精确的日夜边界时间（08:00 或 20:00）
- `unitMinutes`：单元时长（分钟）
- `dayBeginMin`：白天开始分钟数（从 00:00 起，如 480 表示 08:00）
- `dayEndMin`：白天结束分钟数（从 00:00 起，如 1200 表示 20:00）
- `blockWeight`：跨时段归属阈值（如 0.5 表示白天占比≥50%归 day）

**Snap 算法**：

1. 计算单元边界对齐：
   - `minutesFromCurrent = Duration(current, exactBoundary)`
   - `unitIndex = floor(minutesFromCurrent / unitMinutes)`
   - `unitStart = current + unitIndex * unitMinutes`
   - `unitEnd = unitStart + unitMinutes`

2. 判断边界是否恰好落在单元边界：
   - 如果 `exactBoundary == unitStart || exactBoundary == unitEnd`，直接返回 `exactBoundary`

3. 跨时段单元归属判断：
   - `dayMinutes = countDayMinutes(unitStart, unitEnd, dayBeginMin, dayEndMin)`
   - `belongsToDay = (dayMinutes / unitMinutes) >= blockWeight`

4. Snap 到占优侧边界：
   - `dayBegin` 边界且归属 day：返回 `unitStart`，让跨界单元整体并入后续 day 段
   - `dayBegin` 边界且归属 night：返回 `unitEnd`，让跨界单元整体并入前置 night 段
   - `dayEnd` 边界且归属 day：返回 `unitEnd`，让跨界单元整体并入前置 day 段
   - `dayEnd` 边界且归属 night：返回 `unitStart`，让跨界单元整体并入后续 night 段

### 示例场景

**配置**：
- dayBegin=08:00, dayEnd=20:00
- unitMinutes=60
- blockWeight=0.5
- 免费时段：05:13-05:43（START_FREE 30分钟）

**期望流程**：
1. 05:13-05:43：免费时段
2. 05:43：免费结束后，BoundaryDrivenLoop 循环中 current=05:43（新起点）
3. 05:43-06:43：第一单元（night 时段）
4. 06:43-07:43：第二单元（night 时段）
5. 07:43 循环时遇到 dayBegin=08:00：
   - 当前循环：current=07:43
   - Provider被调用：nextBoundary(07:43, calcEnd)
   - 找到08:00（dayBegin）在范围内
   - 从current=07:43开始对齐单元边界：
     - minutesFromCurrent = Duration(07:43, 08:00) = 17分钟
     - unitIndex = floor(17/60) = 0
     - unitStart = 07:43 + 0*60 = 07:43
     - unitEnd = 07:43 + 60 = 08:43
   - exactBoundary=08:00 落在 (07:43, 08:43) 内（非边界）
   - dayMinutes = countDayMinutes(07:43, 08:43) = 43分钟（08:00-08:43）
   - belongsToDay = (43/60) >= 0.5 = true
   - dayBegin 边界且跨界单元归属 day，所以 snap 到 unitStart = 07:43
6. 07:43-08:43：snap结果，该单元归属 day，价格=dayUnitPrice
7. 后续单元：08:43-09:43, 09:43-10:43（都是day时段）

**结果**：05:43-07:43 compact×2（night）+ 07:43-10:43 compact×3（day）

## 修改范围

### 1. BoundaryDrivenLoop.java
- `run` 入口保留纯调度参数：`calcBegin/calcEnd/providers/segmentBuilder`
- `SegmentBuilder` 只接收 `[begin, end)`，不再接收外部状态

### 2. BoundaryProvider.java / BoundaryProviders.java
- `BoundaryProvider#nextBoundary(current, calcEnd)` 不再接收定价状态，且每个 provider 只返回自身最近边界
- `BoundaryProviders.findNearest` 不再传递外部状态，只在各 provider 的最近候选之间取最小值

### 3. DayNightContinuousStrategy.java
- `createDayNightBoundaryProvider` 只返回最近的精确边界或 snap 后边界
- `buildSegmentForDayNight` 使用 `DayNightPriceResolver.determineUnitPriceForContinuous(current, next, config)` 定价
- `dayEndMinute=1440` 使用 `date.atStartOfDay().plusMinutes(...)` 语义处理，避免 `withHour(24)` 异常
- CONTINUOUS 简化先做一个无优惠完整周期明细对账；完整周期未达到 `maxChargeOneDay` 时不启用 cap 乘周期数的简化

### 4. 其他规则文件
- RelativeTime / NaturalTime / CompositeTime / DurationPeriod / DurationGlobal 均移除空状态初始化
- 各规则继续在自己的 segment builder 或 `RuleSemantics#priceAt` 中计算价格

## 测试验证

### 单元测试用例
- `DayNightContinuousCrossPeriodTest`：验证跨时段归属逻辑
- `DayNightParkingParityTest`：验证与旧版本语义一致性

### 集成测试
- 免费时段后的边界对齐（05:13-05:43 免费 → 05:43 单元边界对齐）
- 跨日夜单元归属（07:43-08:43 归属 day）
- Compact 单元生成（多个同价单元合并）

### 已验证命令

- `mvn -pl bill-test -am "-Dtest=DayNightContinuousCrossPeriodTest,ContinuousSimplificationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
- `mvn -pl bill-test -am "-Dtest=DayNightParkingParityTest,DurationBillingModeTest,FreeMinutesAllocationModeTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
- `mvn test`

验证结果：2026-07-15 全量 `mvn test` 通过，129 tests, 0 failures。

## 注意事项

1. **单元边界对齐关键**：使用 `current`（循环中的动态参数）作为锚点对齐单元边界，而非 `cycleOriginBegin`（固定）
2. **current参数语义**：current 是 HomogeneousSegment 的起点，每次循环动态变化（上一段的终点）
3. **snap时机**：Provider 在每次循环中被调用，current 参数就是当前段的起点，用于单元对齐
4. **免费时段处理**：免费标记在段构建时处理，不修改价格状态

## 后续优化

- 性能优化：减少边界计算次数
- 将 `BillingPlaygroundTest` 的 DayNight 场景整理成更清晰的手工验算入口

## 参考资料

- [DayNightContinuousStrategy.java](../core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightContinuousStrategy.java)
- [BoundaryDrivenLoop.java](../core/src/main/java/cn/shang/charging/charge/rules/BoundaryDrivenLoop.java)
- [BoundaryProvider.java](../core/src/main/java/cn/shang/charging/charge/rules/BoundaryProvider.java)
