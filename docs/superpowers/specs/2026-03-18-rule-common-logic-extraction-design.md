# 计费规则共用逻辑提取评估

## 日期
2026-03-18

## 目标
评估不同计费规则之间提取共用逻辑的可行性，将公共特性（模式支持、不足单元计费模式等）变为可配置特性。

---

## 一、当前代码分析

### 1.1 共同逻辑（高度相似或完全相同）

| 逻辑 | 相似度 | 说明 |
|------|--------|------|
| `RuleState` 结构 | 100% | 完全相同的字段：cycleIndex, cycleAccumulated, cycleBoundary |
| `restoreState()` | 100% | 完全相同的实现 |
| `toMap()` | 100% | 完全相同的实现 |
| `buildCarryOverState()` | 100% | 完全相同的实现 |
| `findFreePromotionId()` | 100% | 完全相同的实现 |
| `findFreeTimeRangeById()` | 100% | 完全相同的实现 |
| `calculateEffectiveFrom()` | 100% | 完全相同的实现 |
| `calculateEffectiveTo()` | 95% | 高度相似，只有周期长度不同（24h vs 1440min） |
| 封顶处理模式 | 90% | 模式相同，从最后一个单元开始削减 |
| `calculate()` 入口 | 100% | 模式判断逻辑完全相同 |

### 1.2 各自独有逻辑

| 规则 | 独有逻辑 | 说明 |
|------|---------|------|
| **DayNightRule** | 日夜时段判断 | `determinePeriodType()`, `isInDayPeriod()` |
| | 日夜分钟数计算 | `calculateDayNightMinutes()` |
| | blockWeight 判断 | 混合时段时根据比例选日价/夜价 |
| | 单价确定 | dayUnitPrice / nightUnitPrice |
| **RelativeTimeRule** | 时间段配置 | `periods` 列表，每个时段有独立配置 |
| | 按时间段生成单元 | `generateUnitsInPeriod()` |
| | 时间段边界查找 | `findNextPeriodBoundary()` |
| | 多单价支持 | 每个时间段可有不同单价和单元长度 |

### 1.3 代码量统计

```
DayNightRule.java:      ~900 行
RelativeTimeRule.java:  ~950 行
共同逻辑:               ~300 行 (33%)
```

---

## 二、提取方案评估

### 方案 A：抽象基类 + 模板方法

```java
public abstract class AbstractTimeBasedRule<C extends RuleConfig> implements BillingRule<C> {

    // 共同实现
    protected abstract String getRuleType();
    protected abstract int getCycleMinutes();

    // 状态管理（共同实现）
    protected RuleState restoreState(Map<String, Object> stateMap) { ... }
    protected Map<String, Object> toMap(RuleState state) { ... }

    // 免费时段检测（共同实现）
    protected String findFreePromotionId(...) { ... }

    // 费用稳定窗口（共同实现）
    protected LocalDateTime calculateEffectiveFrom(...) { ... }
    protected LocalDateTime calculateEffectiveTo(...) { ... }

    // 模板方法
    @Override
    public BillingSegmentResult calculate(BillingContext context, C config, PromotionAggregate promotionAggregate) {
        if (context.getBillingMode() == BConstants.BillingMode.UNIT_BASED) {
            return calculateUnitBased(context, config, promotionAggregate);
        } else {
            return calculateContinuous(context, config, promotionAggregate);
        }
    }

    // 抽象方法
    protected abstract BillingSegmentResult calculateUnitBased(...);
    protected abstract BillingSegmentResult calculateContinuous(...);
}
```

**优点**：
- 消除重复代码
- 统一状态管理
- 渐进式重构

**缺点**：
- Java 单继承限制
- 未来扩展可能受限

### 方案 B：策略模式 + 组合

```java
// 状态管理策略
public interface RuleStateStrategy {
    String getRuleType();
    RuleState restoreState(Map<String, Object> stateMap);
    Map<String, Object> toMap(RuleState state);
}

// 封顶策略
public interface CapStrategy {
    BigDecimal applyCap(List<BillingUnit> units, BigDecimal maxCharge, BigDecimal carryOver);
}

// 单价计算策略
public interface UnitPriceStrategy<C extends RuleConfig> {
    BigDecimal determineUnitPrice(LocalDateTime begin, LocalDateTime end, C config);
}
```

**优点**：
- 更灵活的组合
- 不受继承限制
- 各策略可独立演进

**缺点**：
- 复杂度增加
- 需要更多接口

### 方案 C：公共特性配置化

```java
public class RuleFeatureConfig {
    /** 支持的计费模式 */
    private Set<BillingMode> supportedModes;

    /** 不足单元计费模式：FULL（全额）/ PROPORTIONAL（按比例） */
    private InsufficientUnitMode insufficientUnitMode;

    /** 是否支持封顶 */
    private boolean capEnabled;

    /** 封顶类型：DAILY / CYCLE */
    private CapType capType;

    /** 周期长度（分钟） */
    private int cycleMinutes;
}

// 规则接口扩展
public interface BillingRule<C extends RuleConfig> {
    // 现有方法
    BillingSegmentResult calculate(...);
    Class<C> configClass();
    Set<BConstants.BillingMode> supportedModes();

    // 新增：特性配置
    default RuleFeatureConfig getFeatureConfig() {
        return RuleFeatureConfig.defaultConfig();
    }
}
```

**优点**：
- 规则只需声明特性，无需实现模式判断
- 公共特性可被引擎统一处理
- 未来新特性易于添加

**缺点**：
- 需要引擎配合
- 现有规则需要适配

---

## 三、结论

### 推荐方案：方案 A + 方案 C 组合

**分阶段实施**：

**Phase 1：抽象基类（短期）**
- 创建 `AbstractTimeBasedRule`
- 提取状态管理、免费时段检测、费用窗口计算
- DayNightRule 和 RelativeTimeRule 继承基类

**Phase 2：特性配置（中期）**
- 引入 `RuleFeatureConfig`
- 将模式支持、不足单元模式等变为可配置特性
- 引擎层统一处理公共特性

**Phase 3：策略拆分（长期）**
- 将 calculateUnitBased/calculateContinuous 拆分为独立策略
- 支持规则运行时切换策略

---

## 四、待确认问题

### Q1：不足单元计费模式是否需要规则级别配置？

**当前行为**：不足一个单元长度收全额

**可选模式**：
- FULL：全额收费（当前）
- PROPORTIONAL：按实际时长比例收费

**问题**：
- 这个配置应该是规则级别还是方案级别？
- 是否所有规则都需要支持两种模式？

### Q2：模式支持的配置化程度？

**当前**：每个规则硬编码 `supportedModes()`

**可选项**：
- 保持硬编码（安全，编译期检查）
- 改为配置（灵活，但可能导致运行时错误）

### Q3：封顶逻辑是否可以完全抽象？

**当前差异**：
- DayNightRule：按比例削减
- RelativeTimeRule：从最后一个单元开始削减

**问题**：
- 是否需要统一封顶策略？
- 还是保持各自实现？

### Q4：CONTINUOUS 模式的单元生成逻辑如何抽象？

**当前差异**：
- DayNightRule：按免费时段边界切分
- RelativeTimeRule：按免费时段边界切分 + 时间段边界

**问题**：
- 边界判断逻辑如何抽象？
- 是否需要引入"边界提供者"接口？

---

## 五、影响范围

### 需要修改的文件

1. **新增**：
   - `AbstractTimeBasedRule.java`
   - `RuleFeatureConfig.java`

2. **修改**：
   - `BillingRule.java` - 新增 `getFeatureConfig()` 方法
   - `DayNightRule.java` - 继承基类
   - `RelativeTimeRule.java` - 继承基类
   - `BillingCalculator.java` - 可能需要处理公共特性

3. **测试**：
   - 所有现有测试需要通过
   - 新增基类测试

---

## 六、风险评估

| 风险 | 级别 | 缓解措施 |
|------|------|---------|
| 重构影响现有功能 | 中 | 渐进式重构，保证测试通过 |
| 过度抽象 | 低 | 只提取真正相同的代码 |
| 性能影响 | 低 | 纯代码重组，无运行时开销 |

---

## 七、下一步行动

1. **确认待确认问题**的答案
2. 创建详细实施计划
3. Phase 1 实施（预计 1-2 天）