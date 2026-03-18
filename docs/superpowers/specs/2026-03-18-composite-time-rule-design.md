# CompositeTimeRule 混合计费规则设计

## 日期
2026-03-18

## 状态
**⚠️ 未完成确认** - 用户有其他特性需要补充

---

## 一、业务场景

结合 RelativeTimeRule 的"相对时间段分层"和 DayNightRule 的"自然时段多价格"，实现两层嵌套的计费逻辑。

**示例场景**：
```
24小时周期
├── 相对时间段1 (0-120分钟) - 首两小时优惠时段
│   ├── 单元长度: 60分钟
│   ├── 独立封顶: 5元
│   └── 自然时段价格:
│       ├── 04:00-08:00: 1元
│       ├── 08:00-23:00: 2元
│       └── 23:00-次日04:00: 1.5元
├── 相对时间段2 (120-1440分钟) - 正常计费时段
│   ├── 单元长度: 30分钟
│   └── 自然时段价格:
│       ├── 04:00-08:00: 2元
│       ├── 08:00-23:00: 4元
│       └── 23:00-次日04:00: 3元
└── 周期封顶: 50元
```

---

## 二、数据结构设计

### 2.1 配置类

```java
/**
 * 混合计费规则配置
 */
@Data
public class CompositeTimeConfig implements RuleConfig {

    /** 周期封顶金额（必填） */
    private BigDecimal maxChargeOneCycle;

    /** 相对时间段列表 */
    private List<CompositePeriod> periods;
}

/**
 * 相对时间段配置
 */
@Data
public class CompositePeriod {

    /** 相对开始分钟（相对于计费起点，0-1440） */
    private int beginMinute;

    /** 相对结束分钟 */
    private int endMinute;

    /** 计费单元长度（分钟） */
    private int unitMinutes;

    /** 时间段独立封顶（可选） */
    private BigDecimal maxCharge;

    /** 跨自然时段处理模式 */
    private CrossPeriodMode crossPeriodMode;

    /** 自然时段价格列表 */
    private List<NaturalPeriod> naturalPeriods;
}

/**
 * 自然时段配置
 */
@Data
public class NaturalPeriod {

    /** 自然时段开始分钟（一天内的分钟数，0-1440） */
    private int beginMinute;

    /** 自然时段结束分钟 */
    private int endMinute;

    /** 单元价格 */
    private BigDecimal unitPrice;
}

/**
 * 跨自然时段处理模式
 */
public enum CrossPeriodMode {

    /** 按时间比例判断用哪个价格（类似 DayNightRule 的 blockWeight） */
    BLOCK_WEIGHT,

    /** 取较高价格 */
    HIGHER_PRICE,

    /** 取较低价格 */
    LOWER_PRICE,

    /** 按比例拆分计算 */
    PROPORTIONAL,

    /** 取开始时间所在时段的价格 */
    BEGIN_TIME_PRICE,

    /** 取结束时间所在时段的价格 */
    END_TIME_PRICE,

    /** 取开始时间价格，并用自然时段边界截断单元 */
    BEGIN_TIME_TRUNCATE
}
```

---

## 三、核心算法

### 3.1 计费流程

```
1. 从计费起点开始，按24小时划分周期
2. 每个周期内：
   a. 遍历相对时间段（CompositePeriod）
   b. 在每个相对时间段内：
      - 计算该时间段在当前周期内的实际时间范围
      - 按单元长度生成计费单元
      - 对每个单元，根据自然时段配置计算价格
      - 应用跨自然时段处理模式
   c. 应用时间段独立封顶（如有配置）
3. 应用周期封顶
4. 生成结果
```

### 3.2 关键逻辑

#### 3.2.1 自然时段价格匹配

```java
/**
 * 获取时间点所在的自然时段
 */
private NaturalPeriod findNaturalPeriod(LocalDateTime time, List<NaturalPeriod> naturalPeriods) {
    int dayMinute = time.getHour() * 60 + time.getMinute();
    for (NaturalPeriod period : naturalPeriods) {
        if (isInPeriod(dayMinute, period.getBeginMinute(), period.getEndMinute())) {
            return period;
        }
    }
    return null;
}
```

#### 3.2.2 跨自然时段处理

```java
/**
 * 计算计费单元价格（考虑跨时段）
 */
private BigDecimal calculateUnitPrice(LocalDateTime begin, LocalDateTime end,
                                       CompositePeriod period) {
    NaturalPeriod beginPeriod = findNaturalPeriod(begin, period.getNaturalPeriods());
    NaturalPeriod endPeriod = findNaturalPeriod(end, period.getNaturalPeriods());

    // 同一时段
    if (beginPeriod == endPeriod) {
        return beginPeriod.getUnitPrice();
    }

    // 跨时段处理
    return switch (period.getCrossPeriodMode()) {
        case BLOCK_WEIGHT -> calculateByBlockWeight(begin, end, period);
        case HIGHER_PRICE -> max(beginPeriod.getUnitPrice(), endPeriod.getUnitPrice());
        case LOWER_PRICE -> min(beginPeriod.getUnitPrice(), endPeriod.getUnitPrice());
        case PROPORTIONAL -> calculateProportional(begin, end, period);
        case BEGIN_TIME_PRICE -> beginPeriod.getUnitPrice();
        case END_TIME_PRICE -> endPeriod.getUnitPrice();
        case BEGIN_TIME_TRUNCATE -> throw new UnsupportedOperationException("需要截断逻辑");
    };
}
```

---

## 四、待确认事项

### 4.1 配置细节

- [ ] 自然时段是否必须覆盖全天（0-1440）？
- [ ] 相对时间段是否必须首尾相连？
- [ ] 时间段独立封顶与周期封顶的优先级？

### 4.2 边界情况

- [ ] 跨天的计费单元如何处理自然时段？
- [ ] CONTINUOUS 模式下如何按免费时段边界切分？
- [ ] 延伸逻辑如何处理两层边界？

### 4.3 其他特性

> **⚠️ 用户待补充其他计费系统特性**

---

## 五、实现计划（待完成确认后制定）

1. 数据结构定义
2. 配置校验逻辑
3. UNIT_BASED 模式实现
4. CONTINUOUS 模式实现
5. 封顶逻辑
6. 延伸逻辑
7. 测试用例

---

## 六、与其他规则对比

| 特性 | DayNightRule | RelativeTimeRule | CompositeTimeRule |
|------|--------------|------------------|-------------------|
| 时间分层 | 无 | 相对时间段 | 相对时间段 + 自然时段 |
| 价格维度 | 2（日/夜） | 每段一个 | 每段内多价格 |
| 封顶层级 | 周期 | 周期 | 时间段 + 周期 |
| 跨时段处理 | blockWeight | 边界截断 | 7种模式 |
| 复杂度 | 低 | 中 | 高 |