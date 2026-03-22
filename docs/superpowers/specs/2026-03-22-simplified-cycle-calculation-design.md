# 长期计费简化计算设计

## 日期
2026-03-22

---

## 一、背景

### 问题陈述

停车场长期停车场景（超过1周甚至几个月），逐周期计算会生成大量计费单元，计算效率低且结果冗长。

例如：停车30天，生成 30×48 = 1440 个计费单元（假设30分钟一个单元），但实际金额就是 30×封顶金额。

### 目标

对连续无优惠的周期进行简化计算，减少计算量和计费单元数量。

---

## 二、设计决策

### 决策1：简化触发条件

| 条件 | 说明 |
|------|------|
| 阈值来源 | `BillingConfigResolver.getSimplifiedCycleThreshold()` 返回阈值 N |
| 触发条件 | 连续无优惠周期数 > N 时启用简化 |
| 规则控制 | 配置 `simplifiedSupported` 可禁用简化（默认支持） |

### 决策2：简化单元表示

| 字段 | 来源 | 说明 |
|------|------|------|
| beginTime | 周期起点 | 简化段的起始时间（完整周期边界） |
| endTime | 周期终点 | 简化段的结束时间（完整周期边界） |
| chargedAmount | 周期数 × 封顶金额 | 简化段的总金额 |
| ruleData | Map（见下表） | 简化信息 |

**ruleData 结构（简化单元）**：

| 字段 | 类型 | 说明 |
|------|------|------|
| cycleIndex | Integer | 简化起始周期索引 |
| simplifiedCycleCount | Integer | 简化的周期数量 |
| simplifiedCycleAmount | BigDecimal | 单周期金额（封顶金额） |
| isSimplified | Boolean | 标记为简化单元 |

### 决策3：简化执行位置

由各计费规则内部实现，在周期生成阶段判断和执行简化。

### 决策4：CONTINUE 模式支持

完全支持，简化单元的状态正确结转和恢复：
- `cycleIndex` = 起始索引 + 周期数
- `cycleAccumulated` = 封顶金额

### 决策5：扩展性预留

未来支持多种简化方式：
- 按周期合并（当前实现）
- 按周聚合
- 按月聚合
- 按年聚合
- 自定义配置

---

## 三、架构设计

### 3.1 组件职责

```
BillingConfigResolver
    └── getSimplifiedCycleThreshold() → 返回简化阈值 N

AbstractTimeBasedRule
    ├── isSimplifiedSupported(C config) → 是否支持简化
    ├── getCycleCapAmount(C config) → 获取周期封顶金额
    ├── buildSimplifiedUnit(...) → 生成简化单元
    └── getCycleBoundary(...) → 计算周期边界时间

DayNightRule / RelativeTimeRule / CompositeTimeRule
    └── 在生成周期时判断并执行简化
```

### 3.2 新增方法

**BillingConfigResolver**：

```java
/**
 * 获取简化计算的周期阈值
 * @return 连续无优惠周期数超过此值时启用简化，0 表示禁用简化
 */
int getSimplifiedCycleThreshold();
```

**AbstractTimeBasedRule**：

```java
/**
 * 子类实现：是否支持简化计算
 */
protected abstract boolean isSimplifiedSupported(C config);

/**
 * 子类实现：获取指定周期的封顶金额
 */
protected abstract BigDecimal getCycleCapAmount(C config);

/**
 * 构建简化单元
 */
protected BillingUnit buildSimplifiedUnit(
    int beginCycleIndex,
    int cycleCount,
    BigDecimal cycleCapAmount,
    LocalDateTime calcBegin);

/**
 * 计算周期边界时间
 */
protected LocalDateTime getCycleBoundary(int cycleIndex, LocalDateTime calcBegin);
```

### 3.3 配置扩展

**各规则配置新增字段**（可选）：

```java
/**
 * 是否支持简化计算，null 表示默认支持
 */
private Boolean simplifiedSupported;
```

---

## 四、实现流程

### 4.1 简化判断流程（以 DayNightRule.calculateUnitBased 为例）

```
1. 检查简化是否启用：
   │
   ├─ config.simplifiedSupported != false
   └─ configResolver.getSimplifiedCycleThreshold() > 0
   │
   └─ 若任一条件不满足，跳过简化，正常逐周期计算

2. 预计算有优惠的周期索引集合：
   │
   ├─ 遍历 PromotionAggregate.freeTimeRanges
   ├─ 计算每个优惠时段覆盖的周期索引
   └─ 汇总为 Set<Integer> cyclesWithPromotion
   │
   └─ 若 freeMinutes > 0，所有周期都视为有优惠（保守策略）

3. 遍历周期索引生成计费单元：
   │
   ├─ 记录连续无优惠周期段的 [startIndex, count]
   │
   └─ 遇到有优惠周期或计算结束时：
       │
       ├─ 若 count > threshold：生成简化单元
       │   └─ buildSimplifiedUnit(startIndex, count, capAmount, calcBegin)
       │
       └─ 否则：逐周期生成详细单元

4. 更新输出状态：
   │
   ├─ cycleIndex 指向最后一个周期
   ├─ cycleAccumulated 正确设置
   └─ 支持 CONTINUE 模式恢复
```

### 4.2 优惠时段覆盖周期计算

```java
/**
 * 计算优惠时段覆盖的周期索引集合
 */
private Set<Integer> findCyclesWithPromotion(
    LocalDateTime calcBegin,
    LocalDateTime calcEnd,
    List<FreeTimeRange> freeTimeRanges,
    int cycleMinutes) {

    Set<Integer> cycles = new HashSet<>();

    for (FreeTimeRange range : freeTimeRanges) {
        // 计算优惠时段覆盖的周期范围
        int startCycle = (int) Duration.between(calcBegin, range.getBeginTime()).toMinutes() / cycleMinutes;
        int endCycle = (int) Duration.between(calcBegin, range.getEndTime()).toMinutes() / cycleMinutes;

        // 添加所有覆盖的周期索引
        for (int i = startCycle; i <= endCycle; i++) {
            if (i >= 0) {
                cycles.add(i);
            }
        }
    }

    return cycles;
}
```

### 4.3 简化单元生成

```java
protected BillingUnit buildSimplifiedUnit(
    int beginCycleIndex,
    int cycleCount,
    BigDecimal cycleCapAmount,
    LocalDateTime calcBegin) {

    LocalDateTime beginTime = getCycleBoundary(beginCycleIndex, calcBegin);
    LocalDateTime endTime = getCycleBoundary(beginCycleIndex + cycleCount, calcBegin);
    BigDecimal totalAmount = cycleCapAmount.multiply(BigDecimal.valueOf(cycleCount));

    // 构建 ruleData
    Map<String, Object> ruleData = new HashMap<>();
    ruleData.put("cycleIndex", beginCycleIndex);
    ruleData.put("simplifiedCycleCount", cycleCount);
    ruleData.put("simplifiedCycleAmount", cycleCapAmount);
    ruleData.put("isSimplified", true);

    return BillingUnit.builder()
        .beginTime(beginTime)
        .endTime(endTime)
        .durationMinutes((int) Duration.between(beginTime, endTime).toMinutes())
        .unitPrice(cycleCapAmount) // 单周期金额
        .originalAmount(totalAmount)
        .chargedAmount(totalAmount)
        .ruleData(ruleData)
        .build();
}
```

### 4.4 CONTINUE 模式状态恢复

```java
// 从上一个结果的简化单元恢复状态
if (lastUnit.getRuleData() instanceof Map) {
    Map<String, Object> data = (Map<String, Object>) lastUnit.getRuleData();
    if (Boolean.TRUE.equals(data.get("isSimplified"))) {
        // 简化单元后的状态
        int simplifiedCount = (Integer) data.get("simplifiedCycleCount");
        BigDecimal cycleAmount = (BigDecimal) data.get("simplifiedCycleAmount");

        state.setCycleIndex(state.getCycleIndex() + simplifiedCount);
        state.setCycleAccumulated(cycleAmount);
        state.setCycleBoundary(calcBegin.plusMinutes((long) state.getCycleIndex() * getCycleMinutes()));
    }
}
```

### 4.5 CONTINUOUS 模式简化处理

CONTINUOUS 模式的简化逻辑与 UNIT_BASED 模式基本一致，差异如下：

**相同点**：
- 简化判断条件相同（连续无优惠周期数 > 阈值）
- 简化单元生成方式相同
- 状态恢复逻辑相同

**差异点**：
- **时间轴切分顺序**：CONTINUOUS 模式先按免费时段切分时间轴，再在切分后的片段内判断简化
- **简化时机**：在每个时间片段内，按周期组织时进行简化判断
- **免费时段边界**：简化单元边界仍需对齐完整周期边界，不跨越免费时段

**实现位置**：
- `calculateContinuous()` 方法中，在 `organizeByCycle()` 之后、生成计费单元之前进行简化判断

---

## 五、边界情况处理

### 5.1 免费分钟数存在时

如果 `PromotionAggregate.freeMinutes > 0`，保守地将所有周期视为有优惠，不启用简化。

### 5.2 部分周期有优惠

```
计费范围：30 天（周期 0-29）
第 15 天有优惠时段

结果：
- 周期 0-14：检查是否可简化（取决于阈值）
- 周期 15：正常详细计算
- 周期 16-29：检查是否可简化（取决于阈值）
```

### 5.3 封顶金额为 0

如果封顶金额为 0 或未配置，不启用简化。

### 5.4 阈值为 0

`getSimplifiedCycleThreshold()` 返回 0 时，禁用简化功能。

---

## 六、扩展性设计

### 6.1 未来简化方式扩展

预留扩展点，未来可支持：

| 方式 | 说明 |
|------|------|
| 按周期 | 当前实现，合并连续周期 |
| 按周 | 每7个周期合并为一个周单元 |
| 按月 | 每30个周期合并为一个月单元 |
| 按年 | 每365个周期合并为一个年单元 |
| 自定义 | 根据配置的周期数合并 |

### 6.2 扩展实现方式

```java
// 未来可扩展的简化方式枚举
public enum SimplifiedMode {
    BY_CYCLE,   // 按周期合并（当前实现）
    BY_WEEK,    // 按周聚合
    BY_MONTH,   // 按月聚合
    BY_YEAR,    // 按年聚合
    CUSTOM      // 自定义周期数
}

// 配置扩展
private SimplifiedMode simplifiedMode;
private Integer customSimplifiedCycles; // 自定义周期数
```

---

## 七、文件变更清单

### 新增/修改文件

| 文件 | 变更类型 | 说明 |
|------|----------|------|
| `BillingConfigResolver.java` | 修改 | 新增 `getSimplifiedCycleThreshold()` |
| `DayNightConfig.java` | 修改 | 新增 `simplifiedSupported` 字段 |
| `RelativeTimeConfig.java` | 修改 | 新增 `simplifiedSupported` 字段 |
| `CompositeTimeConfig.java` | 修改 | 新增 `simplifiedSupported` 字段 |
| `AbstractTimeBasedRule.java` | 修改 | 新增简化框架方法 |
| `DayNightRule.java` | 修改 | 实现简化计算逻辑 |
| `RelativeTimeRule.java` | 修改 | 实现简化计算逻辑 |
| `CompositeTimeRule.java` | 修改 | 实现简化计算逻辑 |

### 删除引用

| 文件 | 变更类型 | 说明 |
|------|----------|------|
| `CLAUDE.md` | 修改 | 移除 FreeTimeRangePromotionRule 引用（该类实际不存在） |
| `docs/superpowers/specs/2026-03-18-pending-features-overview.md` | 修改 | 更新待删除清单 |

---

## 八、测试验证

### 测试场景

1. **基本简化**：停车 30 天无优惠，验证生成简化单元
2. **部分简化**：停车 30 天，中间某天有优惠，验证分段简化
3. **阈值边界**：停车 8 天（阈值 7），验证刚好超过阈值
4. **禁用简化**：配置禁用或阈值 0，验证正常计算
5. **CONTINUE 模式**：简化后继续计算，验证状态恢复正确
6. **免费分钟**：存在免费分钟时，验证不启用简化

---

## 九、风险与缓解

| 风险 | 级别 | 缓解措施 |
|------|------|---------|
| 简化计算金额偏差 | 低 | 只在确定达到封顶时简化，不足封顶的周期不简化 |
| 状态恢复错误 | 中 | 充分测试 CONTINUE 模式场景 |
| 扩展性不足 | 低 | 预留简化方式枚举和配置扩展点 |