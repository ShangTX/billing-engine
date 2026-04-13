# FlatFreeRule 设计文档

## 需求

新增一个免费计费规则，该规则：
1. 忽略所有优惠（FREE_RANGE、FREE_MINUTES 等）
2. 返回免费计费单元，费用始终为 0
3. 支持 CONTINUOUS 和 UNIT_BASED 两种计费模式
4. 支持 CONTINUE 模式：保存 ruleOutputState 以便调用方识别结果归属并合并

## 新增文件

### 1. FlatFreeConfig

```
core/src/main/java/cn/shang/charging/charge/rules/flatfree/FlatFreeConfig.java
```

| 字段 | 类型 | 说明 |
|------|------|------|
| id | String | 规则配置 ID |

- 实现 `RuleConfig` 接口
- `getType()` 返回 `"flatFree"`

### 2. FlatFreeRule

```
core/src/main/java/cn/shang/charging/charge/rules/flatfree/FlatFreeRule.java
```

不继承 `AbstractTimeBasedRule`，因为不需要周期、日夜区分等复杂逻辑。

#### 接口实现

| 方法 | 行为 |
|------|------|
| `configClass()` | `FlatFreeConfig.class` |
| `supportedModes()` | `EnumSet.of(CONTINUOUS, UNIT_BASED)` |
| `calculate()` | 根据 BillingMode 分发到 CONTINUOUS 或 UNIT_BASED 方法 |
| `buildCarryOverState()` | 使用接口默认实现（返回 `result.getRuleOutputState()`） |

#### calculate() 行为

**CONTINUOUS 模式：**
- 返回单个 `BillingUnit`，覆盖整个计算窗口 `[calcBegin, calcEnd]`
- `durationMinutes = 窗口总分钟数`
- `chargedAmount = 0`, `originalAmount = 0`, `unitPrice = 0`, `free = true`, `freePromotionId = "FLAT_FREE"`

**UNIT_BASED 模式：**
- 同样返回单个 `BillingUnit`，覆盖整个计算窗口 `[calcBegin, calcEnd]`
- `chargedAmount = 0`, `originalAmount = 0`, `unitPrice = 0`, `free = true`, `freePromotionId = "FLAT_FREE"`
- 不标记 `isTruncated`（只有一个完整单元）

**两个模式共同点：**
- 忽略 `PromotionAggregate`（不处理任何优惠）
- `promotionUsages` = 空列表
- `feeEffectiveStart` = `calcBegin`, `feeEffectiveEnd` = `calcEnd`

#### ruleOutputState

参考 DayNightRule 的 `buildRuleOutputState(state)` 模式，保存：

```json
{
  "flatFree": {
    "calculatedUpTo": "<calcEnd 时间点>"
  }
}
```

- `calculatedUpTo` 表示本次计算结束的时间点
- 调用方在 CONTINUE 模式下可通过 `BillingCarryOver.segments[segmentId].ruleState` 获取
- 用于识别"新产生的计费单元属于同一个继续计算"，与上一个结果的 `calculatedUpTo` 衔接

## 修改文件

### BConstants.java

`ChargeRuleType` 类新增常量：

```java
public static String FLAT_FREE = "flatFree"; // 统一免费计费
```

## 使用方式

用户实现 `BillingConfigResolver.resolveChargingRule()` 时：

```java
@Override
public RuleConfig resolveChargingRule(String schemeId, LocalDateTime begin, LocalDateTime end, Map<String, Object> context) {
    if ("free-scheme".equals(schemeId)) {
        return FlatFreeConfig.builder()
                .id("flat-free-001")
                .build();
    }
    // 其他方案返回正常规则...
}
```

## 测试

- CONTINUOUS / UNIT_BASED 模式：都返回单个免费单元，金额为 0
- CONTINUE 模式：ruleOutputState 正确携带 calculatedUpTo，可从上次结果继续
- 优惠参数被正确忽略
