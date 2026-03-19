# CompositeTimeRule 混合计费规则设计

## 日期
2026-03-18

## 状态
**✅ 已确认**

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

    /** 不足单元计费模式 */
    private InsufficientUnitMode insufficientUnitMode;

    /** 相对时间段列表 */
    private List<CompositePeriod> periods;
}

/**
 * 不足单元计费模式
 */
public enum InsufficientUnitMode {

    /** 全额收费 */
    FULL,

    /** 按比例收费 */
    PROPORTIONAL
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

    /** 自然时段开始分钟（一天内的分钟数，0-1440，支持跨天表示） */
    private int beginMinute;

    /** 自然时段结束分钟（小于 beginMinute 表示跨天） */
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

## 三、配置校验规则

### 3.1 自然时段校验

- **必须覆盖全天（0-1440分钟）**
- 校验时检查所有 NaturalPeriod 是否完整覆盖 24 小时
- 未覆盖则报错

### 3.2 相对时间段校验

- **必须首尾相连覆盖整个周期（0-1440分钟）**
- 第一个时间段必须从 0 开始
- 最后一个时间段必须结束于 1440
- 相邻时间段的 beginMinute 必须等于前一个的 endMinute
- 有间隙则报错

---

## 四、核心算法

### 4.1 计费流程

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

### 4.2 自然时段价格匹配

自然时段支持跨天表示：
- `beginMinute=1200, endMinute=480` 表示 20:00 到次日 08:00
- 计费单元跨天时无需特殊处理，直接匹配对应时段

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

### 4.3 跨自然时段处理

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

### 4.4 封顶削减规则

**双层封顶**：时间段独立封顶 + 周期封顶

**削减规则**：
1. 时间段封顶：在该时间段内，从最后一个收费单元开始削减
2. 周期封顶：在未达到独立封顶的时间段中，从最后一个计费单元开始削减
3. 已达到独立封顶的时间段，不再参与周期封顶削减

**示例**：
```
时间段1：独立封顶 5元，实际产生 10元
├── 单元1：3元
├── 单元2：3元
├── 单元3：4元
└── 削减：单元3 免费，单元2 从3元变成2元

时间段2：无独立封顶，产生 50元
├── 周期合计：5 + 50 = 55元 > 50元
└── 削减：从最后一个单元削减5元

最终：50元
```

### 4.5 不足单元计费

**应用层级**：
- 内层（自然时段边界）：应用不足单元判断
- 外层（相对时间段边界）：直接截断，截断部分也应用不足单元判断

**配置**：规则级别配置 `InsufficientUnitMode`（FULL / PROPORTIONAL）

**示例**（FULL 模式）：
```
相对时间段1：0-120分钟，单元长度 50分钟
相对时间段2：120-1440分钟，单元长度 30分钟

计费时间：08:00-10:50

时间段1：
├── 08:00-08:50：完整单元，全额
├── 08:50-09:40：完整单元，全额
└── 09:40-10:00：截断（20分钟），全额

时间段2：
├── 10:00-10:30：完整单元，全额
└── 10:30-11:00：延伸（从10:50延伸），全额
```

### 4.6 延伸规则

**延伸目标**：延伸到完整单元长度

**边界处理**：
- 遇到边界则停在更早的边界
- 参与延伸判断的边界：
  - 相对时间段边界
  - 周期边界
  - 免费时间段边界
- **不参与**：自然时段边界（价格变化不影响延伸）

**示例**：
```
最后单元：09:30-10:00（30分钟，单元长度60分钟）
边界：
├── 相对时间段边界：10:00
└── 免费时间段：10:15-11:15

更早的边界：10:00（相对时间段边界）
→ 不延伸
```

---

## 五、CONTINUOUS 模式 - "气泡抽出"模型

### 5.1 概念

普通免费时间段像水管中的气泡被抽出，前后的计费时间在相对位置上直接连接。

```
原始时间轴：|--计费--|--免费--|--计费--|
抽出后：    |--计费--|--计费--|  （相对位置连续）

|==水==|气泡|==水==|  →  气泡被抽出
|==水======水==|        →  水管变短
```

### 5.2 典型案例

```
免费时间段：10:30-11:30（1小时）
计费起点：08:00
相对时间段1：0-120分钟
相对时间段2：120-1440分钟

片段1：08:00-10:30
├── 相对位置：0-150分钟（按原始计费起点算）
├── 0-120分钟：相对时间段1
└── 120-150分钟：相对时间段2

片段2：11:30-12:00
├── 相对位置：210-240分钟（仍按原始计费起点08:00算，跳过免费时段）
└── 属于相对时间段2（210 > 120）
```

**关键规则**：
- 按免费时间段边界切分时间轴
- 每个片段的相对时间段位置，仍按**原始计费起点**计算
- 免费时间段在时间轴上"消失"，前后的片段在相对位置上是连续的

---

## 六、免费时间段（气泡型）- "气泡弹开"模型

### 6.1 概念

气泡型免费时间段会将周期延长，后续相对时间段边界整体后移。

```
水管（时间轴）：
|===水===|气泡|===水===|    → 气泡将水向两端"弹开"

时间轴：
|--计费--|--免费--|--计费--|    → 免费时间段将后续边界"弹开"
```

### 6.2 与普通免费时间段的区别

| 类型 | 模型 | 效果 |
|------|------|------|
| 免费时间段 | 气泡抽出 | 周期时长不变，相对位置连续 |
| 免费时间段（气泡型） | 气泡弹开 | 周期时长延长，后续边界后移 |

### 6.3 典型案例

```
气泡型免费时间段：1.1 11:20-12:20（1小时）
计费起点：1.1 08:00
相对时间段配置：每2小时一段（0-120、120-240、...）

无气泡时：
├── 第1段：08:00-10:00
├── 第2段：10:00-12:00
├── 第3段：12:00-14:00
├── ...
└── 周期终点：次日 08:00

有气泡时（气泡弹开规则）：
├── 第1段：08:00-10:00（不变，在气泡前）
├── 第2段：10:00-11:20（不变，到气泡起点）
│   [气泡：11:20-12:20] 60分钟免费
├── 第3段：12:20-14:20（弹开60分钟）
├── 第4段：14:20-16:20（弹开60分钟）
├── ...
└── 周期终点：次日 09:00（弹开60分钟）
```

### 6.4 时间段边界弹开规则

**核心规则**：
1. **气泡前的段**：边界不变
2. **气泡后的段**：边界整体后移 = 气泡长度

**算法**：
```java
/**
 * 计算弹开后的时间段边界
 * @param originalEndMinute 原结束分钟（相对于周期起点）
 * @param bubbles 气泡列表（气泡型免费时间段，已转换为相对于周期起点的分钟）
 * @return 弹开后的结束分钟
 */
private int calculateExtendedBoundary(int originalEndMinute, List<TimeRange> bubbles) {
    int extension = 0;
    for (TimeRange bubble : bubbles) {
        // 只有在时间段边界之前的气泡才会弹开
        if (bubble.getBeginMinute() <= originalEndMinute + extension) {
            extension += bubble.getLength();
        }
    }
    return originalEndMinute + extension;
}
```

### 6.5 多气泡场景

```
气泡1：09:00-10:00（60分钟）
气泡2：14:00-15:00（60分钟）

第1段（原0-120分钟）：
├── 08:00-09:00（60分钟计费）
├── [气泡1：09:00-10:00]
├── 10:00-11:00（60分钟计费，被弹开60分钟）
└── 弹开后边界：08:00-11:00（原120分钟 + 气泡1 60分钟）

第2段（原120-240分钟）：
├── 11:00-14:00（180分钟计费，但到气泡2起点截断）
├── [气泡2：14:00-15:00]
├── 15:00-16:00（60分钟计费）
└── 弹开后边界：11:00-16:00（原120分钟 + 气泡1 + 气泡2 = 240分钟）

周期总跨度：24小时 + 120分钟气泡 = 26小时
```

### 6.6 对封顶的影响

**时间段封顶**：
- 封顶金额不变
- 计费时间不变（仍是120分钟）
- 但时间跨度延长

**周期封顶**：
- 封顶金额不变
- 周期时间跨度延长

---

## 七、实现计划

### Phase 1: 数据结构定义
1. `CompositeTimeConfig` 配置类
2. `CompositePeriod` 相对时间段配置
3. `NaturalPeriod` 自然时段配置
4. `CrossPeriodMode` 跨时段处理模式枚举
5. `InsufficientUnitMode` 不足单元计费模式枚举

### Phase 2: 配置校验逻辑
1. 自然时段覆盖全天校验
2. 相对时间段首尾相连校验
3. 必填字段校验

### Phase 3: UNIT_BASED 模式实现
1. 相对时间段遍历
2. 计费单元生成
3. 自然时段价格匹配
4. 跨自然时段处理
5. 时间段封顶逻辑
6. 周期封顶逻辑
7. 不足单元计费

### Phase 4: CONTINUOUS 模式实现
1. 按免费时间段边界切分
2. 气泡抽出模型
3. 相对位置计算
4. 封顶逻辑

### Phase 5: 气泡型免费时间段支持
1. 气泡弹开逻辑
2. 时间段边界调整
3. 周期边界调整

### Phase 6: 延伸逻辑
1. 边界判断
2. 延伸计算

### Phase 7: 测试用例
1. 基本场景测试
2. 跨天场景测试
3. 封顶场景测试
4. CONTINUOUS 模式测试
5. 气泡型免费时间段测试
6. 边界情况测试

---

## 八、与其他规则对比

| 特性 | DayNightRule | RelativeTimeRule | CompositeTimeRule |
|------|--------------|------------------|-------------------|
| 时间分层 | 无 | 相对时间段 | 相对时间段 + 自然时段 |
| 价格维度 | 2（日/夜） | 每段一个 | 每段内多价格 |
| 封顶层级 | 周期 | 周期 | 时间段 + 周期 |
| 跨时段处理 | blockWeight | 边界截断 | 7种模式 |
| 复杂度 | 低 | 中 | 高 |