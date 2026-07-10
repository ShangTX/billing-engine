# DayNight 日夜边界 Provider 与定价架构改造

## 元数据

- **ID**: TODO-20260710-001
- **类型**: refactor
- **优先级**: P1
- **状态**: pending
- **创建时间**: 2026-07-10
- **创建提交**: af29fb4
- **相关测试**:
  - `DayNightContinuousCrossPeriodTest`
  - `DayNightParkingParityTest`

## 背景

DayNight CONTINUOUS 模式 `splitDayNightBoundary=false` 需要重新设计边界生成和定价架构。

### 当前问题

1. **Snap 逻辑错误**：使用固定的 `cycleOriginBegin` 锚点导致边界对齐错误，免费时段后单元边界未重新对齐
2. **价格重复判断**：在段构建时重复计算 `isInDay()`，效率低
3. **架构不够通用**：难以支持其他规则（如 RelativeTime）

## 架构设计方案

### 核心思路：外部状态管理

引入 `PricingState` 对象管理定价状态，在边界循环外维护：

```java
class PricingState {
    BigDecimal currentUnitPrice;  // 当前单价（随边界切换而变化）
    int unitMinutes;              // 单位时间
    LocalDateTime cycleOrigin;    // 周期起点（用于单元对齐）
    // 规则特定的状态...
}
```

### 边界循环流程

1. **初始化状态**：根据规则配置设置初始价格
2. **遍历边界**：
   - 周期循环边界（cycleEnd）→ 不修改状态，仅标记封顶周期
   - 免费时段边界（freeRangeEdges）→ 不修改价格状态，免费标记在段构建时处理
   - 日夜边界（dayNightBoundary）→ 修改 `currentUnitPrice`（day→night 或 night→day）
   - RelativeTime 边界 → 修改 `currentUnitPrice`（不同阶段价格）
3. **段构建**：直接从 `PricingState` 读取 `currentUnitPrice`

### 优点

- **避免重复判断**：边界提供器已经处理了价格切换
- **通用性强**：不同规则可以用不同的状态修改逻辑
- **职责清晰**：边界提供器负责状态转换，段构建器负责段生成

## 实现细节

### 1. splitDayNightBoundary=true（切断模式）

**行为**：在精确的 dayBegin/dayEnd 处切断，生成纯 day 段或 night 段

**实现要点**：
- 直接返回精确的日夜边界时间点（如 08:00、20:00）
- 边界到达时修改状态：`currentUnitPrice = dayUnitPrice 或 nightUnitPrice`
- 段构建器直接使用状态中的价格

### 2. splitDayNightBoundary=false（跨时段归属模式）

**行为**：日夜边界 snap 到单元边界，跨时段单元整体归属到占优侧

**参数说明**：
- `segmentStart`：当前段的起点时间（动态，来自 BoundaryDrivenLoop 的 current 参数）
- `exactBoundary`：精确的日夜边界时间（08:00 或 20:00）
- `unitMinutes`：单元时长（分钟）
- `dayBeginMin`：白天开始分钟数（从 00:00 起，如 480 表示 08:00）
- `dayEndMin`：白天结束分钟数（从 00:00 起，如 1200 表示 20:00）
- `blockWeight`：跨时段归属阈值（如 0.5 表示白天占比≥50%归 day）

**Snap 算法**：

1. 计算单元边界对齐：
   - `minutesFromStart = Duration(segmentStart, exactBoundary)`
   - `unitIndex = floor(minutesFromStart / unitMinutes)`
   - `unitStart = segmentStart + unitIndex * unitMinutes`
   - `unitEnd = unitStart + unitMinutes`

2. 判断边界是否恰好落在单元边界：
   - 如果 `exactBoundary == unitStart || exactBoundary == unitEnd`，直接返回 `exactBoundary`

3. 跨时段单元归属判断：
   - `dayMinutes = countDayMinutes(unitStart, unitEnd, dayBeginMin, dayEndMin)`
   - `belongsToDay = (dayMinutes / unitMinutes) >= blockWeight`

4. Snap 到占优侧单元边界：
   - 如果 `belongsToDay`：返回 `isInDay(unitStart) ? unitEnd : unitStart`
   - 否则（night 占优）：
     - 如果是 `dayBegin` 边界：返回 `unitStart`
     - 如果是 `dayEnd` 边界：返回 `unitEnd`

### 示例场景

**配置**：
- dayBegin=08:00, dayEnd=20:00
- unitMinutes=60
- blockWeight=0.5
- 免费时段：05:13-05:43（START_FREE 30分钟）

**期望流程**：
1. 05:13-05:43：免费时段
2. 05:43：免费结束后，单元边界重新对齐到 05:43
3. 05:43-06:43：第一单元（night 时段）
4. 06:43-07:43：第二单元（night 时段）
5. 07:43-08:43：跨日夜单元（包含 dayBegin=08:00）
   - `exactBoundary = 08:00`（dayBegin）
   - `segmentStart = 07:43`
   - `unitStart = 07:43, unitEnd = 08:43`
   - `dayMinutes = countDayMinutes(07:43, 08:43) = 60分钟（08:00-08:43）`
   - `belongsToDay = (60/60) >= 0.5 = true`
   - snap 结果：`unitEnd = 08:43`
   - 该单元归属 day，价格=dayUnitPrice

## 需要修改的文件

### 1. BoundaryDrivenLoop.java
- 修改 `run` 方法签名：接受 `PricingState` 参数
- 在循环过程中将 `PricingState` 传递给边界提供器和段构建器

### 2. BoundaryProvider.java
- 修改接口签名：`List<LocalDateTime> apply(LocalDateTime current, LocalDateTime end, PricingState state)`
- 边界提供器可以访问和修改状态

### 3. DayNightContinuousStrategy.java
- 实现 `createDayNightBoundaryProvider` 方法（当前为空框架）
- 实现 `snapToUnitBoundary` 方法
- 实现 `countDayMinutes` 方法（已删除，需重新添加）
- 修改段构建器：从 `PricingState` 读取价格

### 4. 其他规则文件
- `ContinuousStrategy.java`：需要适配新的 `PricingState` 架构
- `BoundaryProviders.java`：cycleEnd、freeRangeEdges 等边界提供器需要适配新接口

## 测试验证

### 单元测试用例
- `DayNightContinuousCrossPeriodTest`：验证跨时段归属逻辑
- `DayNightParkingParityTest`：验证与旧版本语义一致性

### 集成测试
- 免费时段后的边界对齐（05:13-05:43 免费 → 05:43 单元边界对齐）
- 跨日夜单元归属（07:43-08:43 归属 day）
- Compact 单元生成（多个同价单元合并）

## 注意事项

1. **单元边界对齐关键**：使用 `segmentStart`（动态）而非 `cycleOriginBegin`（固定）
2. **状态初始化**：边界循环开始前，需要根据段起点时间判断初始价格
3. **边界顺序**：确保日夜边界在正确的时机修改状态
4. **免费时段处理**：免费标记在段构建时处理，不修改价格状态

## 后续优化

- 提取 `PricingState` 为独立类，支持不同规则扩展
- 统一各规则（DayNight、RelativeTime）的定价架构
- 性能优化：减少边界计算次数

## 参考资料

- [DayNightContinuousStrategy.java](../core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightContinuousStrategy.java)
- [BoundaryDrivenLoop.java](../core/src/main/java/cn/shang/charging/charge/rules/BoundaryDrivenLoop.java)
- [BoundaryProvider.java](../core/src/main/java/cn/shang/charging/charge/rules/BoundaryProvider.java)