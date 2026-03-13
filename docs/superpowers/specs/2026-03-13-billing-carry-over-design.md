# BillingCarryOver + CONTINUE 模式设计

## 背景

计费引擎需要支持"预测计算 + 增量更新"场景：
1. 预测阶段：预先计算一整天的费用明细，存储结果
2. 查询阶段：按时间点搜索已计算的费用
3. 增量阶段：时间过去后，增量更新实际费用

## 需求决策

| 维度 | 决策 |
|------|------|
| 使用场景 | 预测计算 + 增量更新（时间窗口扩展） |
| 优惠变化 | 从头重新计算（FROM_SCRATCH） |
| 结转内容 | 时间进度 + 优惠使用 + 周期累计（全部） |
| 存储方式 | 引擎返回，调用方存储 |
| 优惠结转 | 使用剩余值 |
| 周期累计 | 精确结转周期边界信息 |
| 周期定义 | 规则各自定义 |
| 跨分段 | 按 segmentId 独立结转 |
| 结转结构 | 通用 Map 结构，后续可演进到接口+实现分离 |
| 规则状态结构 | 使用内部类定义，序列化为 Map 存储 |

### 计费单元延伸

最后一个计费单元会被计费结束时间截断，为支持有效缓存和 CONTINUE 模式，需要将截断的单元延伸到完整长度：

| 维度 | 决策 |
|------|------|
| 延伸边界 | 周期边界和时间段边界取最近的 |
| 收费影响 | 无，收费金额不变（全额收费） |
| 单元存储 | 替换为延伸后的单元 |
| CONTINUE 起点 | 使用延伸后的实际计算时间 |

**示例**：
```
计费结束时间：9:00
原截断单元：8:30-9:00（30分钟）
下一个周期边界：次日 8:00
下一个时间段边界：9:15（如 RelativeTimeRule 的 period 边界）
延伸后单元：8:30-9:15（取最近的边界）
收费金额：不变
```

## 数据结构

### BillingCarryOver（顶层结转对象）

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BillingCarryOver {
    /** 已计算到的时间点（延伸后的 calculationEndTime） */
    private LocalDateTime calculatedUpTo;

    /** 按分段ID存储的结转状态 */
    private Map<String, SegmentCarryOver> segments;
}
```

### SegmentCarryOver（分段级结转）

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SegmentCarryOver {
    /** 规则状态，key 为规则类型，value 为规则自定义结构 */
    private Map<String, Object> ruleState;

    /** 优惠结转状态 */
    private PromotionCarryOver promotionState;
}
```

### PromotionCarryOver（优惠结转）

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromotionCarryOver {
    /** 剩余免费分钟数，key 为 promotionId */
    private Map<String, Integer> remainingMinutes;

    /** 已使用的免费时段（复用 FreeTimeRange） */
    private List<FreeTimeRange> usedFreeRanges;
}
```

### TimeRange

复用 `promotion/pojo/FreeTimeRange`，该类已包含 `beginTime`、`endTime`、`id` 等字段，适合追踪已使用的免费时段。

### BillingSegment 新增字段

```java
/** 分段唯一标识 */
private String id;
```

**ID 生成策略**：由 `SegmentBuilder` 在构建分段时生成，格式建议 `schemeId-seq`（如 `scheme-A-0`、`scheme-B-1`），确保同一计费请求内唯一。

## API 变更

### BillingRequest 新增字段

```java
/** 上一次计费结果（用于 CONTINUE 模式） */
private BillingCarryOver previousCarryOver;
```

### BillingResult 新增字段

```java
/** 本次计算后的结转状态（供下次 CONTINUE 使用） */
private BillingCarryOver carryOver;

/** 实际计算到的时间点（延伸后，用于缓存有效性判断和 CONTINUE 起点） */
private LocalDateTime calculationEndTime;
```

### BillingContext 变更

```java
// 删除
// private BillingProgress progress;

// 新增
/** 从 carryOver 恢复的规则状态 */
private Map<String, Object> ruleState;
```

### BillingSegmentResult 变更

```java
// 取消注释
/** 本分段结束后的结转状态 */
private BillingCarryOver carryOverAfter;

// 新增
/** 实际计算到的时间点（延伸后，最后一个单元的结束时间） */
private LocalDateTime calculationEndTime;

/** 规则计算过程中的输出状态（供 buildCarryOverState 提取） */
private Map<String, Object> ruleOutputState;
```

## 计算流程

```
BillingService.calculate()
│
├── FROM_SCRATCH 模式
│   ├── 构建 BillingContext（continueMode=FROM_SCRATCH, ruleState=null）
│   ├── 执行计费（现有逻辑）
│   └── 返回结果 + 新生成的 carryOver
│
└── CONTINUE 模式
    ├── 验证 previousCarryOver 不为空
    ├── 构建 BillingContext（continueMode=CONTINUE, ruleState=从 carryOver 恢复）
    ├── 调整计算窗口：beginTime = carryOver.calculatedUpTo
    ├── 执行计费（规则根据 ruleState 恢复状态）
    └── 返回结果 + 更新后的 carryOver
```

## 完整数据流

```
BillingService.calculate(request)
│
├── 1. 确定模式
│   ├── previousCarryOver != null → CONTINUE 模式
│   └── previousCarryOver == null → FROM_SCRATCH 模式
│
├── 2. 调整计算窗口
│   └── CONTINUE: actualBegin = carryOver.calculatedUpTo
│
├── 3. 构建分段（SegmentBuilder）
│   └── 每个分段生成唯一 id
│
├── 4. 逐段计算
│   │
│   ├── 4.1 恢复分段状态
│   │   ├── segmentCarryOver = request.previousCarryOver.segments[segment.id]
│   │   └── ruleState = segmentCarryOver?.ruleState
│   │
│   ├── 4.2 构建 BillingContext
│   │   └── context.ruleState = ruleState
│   │
│   ├── 4.3 优惠聚合（PromotionEngine）
│   │   ├── promoCarryOver = segmentCarryOver?.promotionState
│   │   └── aggregate = promotionEngine.evaluate(context, promoCarryOver)
│   │
│   ├── 4.4 执行计费（BillingCalculator → BillingRule）
│   │   └── rule.calculate(context, config, aggregate)
│   │       ├── 从 context.ruleState 恢复状态
│   │       ├── 计算逻辑
│   │       └── 输出 BillingSegmentResult
│   │
│   └── 4.5 生成分段结转状态
│       ├── newRuleState = rule.buildCarryOverState(segmentResult)
│       ├── newPromoState = buildPromotionCarryOver(aggregate)
│       └── segmentCarryOver = { ruleState, promotionState }
│
├── 5. 组装最终结果
│   ├── carryOver.calculatedUpTo = request.endTime
│   ├── carryOver.segments = Map<segmentId, SegmentCarryOver>
│   └── BillingResult.carryOver = carryOver
│
└── 6. 返回 BillingResult
```

## 边界情况处理

| 场景 | 处理方式 |
|------|---------|
| `previousCarryOver` 为 null 但模式是 CONTINUE | 抛出 `IllegalStateException`，提示需要提供结转状态 |
| `calculatedUpTo` >= request.endTime | 直接返回已有结果，不执行计算 |
| segmentId 不匹配 | 未匹配的分段从 FROM_SCRATCH 开始，多余的分段状态忽略 |
| ruleState 缺少预期 key | 规则自行处理，使用 `getOrDefault` 提供默认值 |
| 优惠已用完 | `remainingMinutes` 为 0 或不存在时，不影响计费 |

## ContinueMode 与 BillingMode 关系

**ContinueMode**（继续模式）和 **BillingMode**（计费模式）是正交的两个维度：

| 模式 | 维度 | 说明 |
|------|------|------|
| FROM_SCRATCH / CONTINUE | ContinueMode | 是否从上次结果继续计算 |
| CONTINUOUS / UNIT_BASED | BillingMode | 计费单位如何划分 |

- **两种模式可以任意组合**：CONTINUE 模式可用于 CONTINUOUS 或 UNIT_BASED
- **结转状态结构相同**：无论哪种 BillingMode，`BillingCarryOver` 结构一致
- **规则必须支持**：规则应正确处理 CONTINUE 模式下的状态恢复

## 序列化说明

`BillingCarryOver` 需要被序列化存储（调用方负责），涉及特殊类型处理：

| 类型 | 序列化格式 | 说明 |
|------|-----------|------|
| LocalDateTime | `yyyy-MM-dd HH:mm:ss` | 与现有 `JacksonUtils` 一致 |
| BigDecimal | 字符串或数字 | Jackson 默认处理 |
| Map<String, Object> | JSON 对象 | 保持灵活性 |

**序列化示例**：
```json
{
  "calculatedUpTo": "2026-03-13 10:00:00",
  "segments": {
    "scheme-A-0": {
      "ruleState": {
        "cycleIndex": 0,
        "cycleAccumulated": 15.00,
        "cycleBoundary": "2026-03-14 08:00:00"
      },
      "promotionState": {
        "remainingMinutes": {"promo-1": 10},
        "usedFreeRanges": []
      }
    }
  }
}
```

**版本兼容性**：如果 `RuleState` 结构变化，建议新增字段而非修改现有字段，保持向后兼容。

## 规则接口变更

### BillingRule 新增方法

```java
/**
 * 从计费结果提取状态，用于下次 CONTINUE 计算
 * @param result 本分段的计费结果
 * @return 需要结转的状态数据（存入 SegmentCarryOver.ruleState）
 */
default Map<String, Object> buildCarryOverState(BillingSegmentResult result) {
    return Collections.emptyMap();
}
```

### 规则实现示例（RelativeTimeRule）

```java
public class RelativeTimeRule implements BillingRule<RelativeTimeConfig> {

    /** 规则状态结构 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RuleState {
        private int cycleIndex;
        private BigDecimal cycleAccumulated;
        private LocalDateTime cycleBoundary;
    }

    // 从 Map 恢复
    private RuleState restoreState(Map<String, Object> stateMap) {
        if (stateMap == null) return null;
        return RuleState.builder()
                .cycleIndex((Integer) stateMap.getOrDefault("cycleIndex", 0))
                .cycleAccumulated((BigDecimal) stateMap.getOrDefault("cycleAccumulated", BigDecimal.ZERO))
                .cycleBoundary((LocalDateTime) stateMap.get("cycleBoundary"))
                .build();
    }

    // 序列化为 Map
    private Map<String, Object> toMap(RuleState state) {
        Map<String, Object> map = new HashMap<>();
        map.put("cycleIndex", state.getCycleIndex());
        map.put("cycleAccumulated", state.getCycleAccumulated());
        map.put("cycleBoundary", state.getCycleBoundary());
        return map;
    }

    @Override
    public BillingSegmentResult calculate(BillingContext context, RelativeTimeConfig config, PromotionAggregate promotionAggregate) {
        // 恢复状态
        RuleState state = restoreState(context.getRuleState());
        if (state == null) {
            state = RuleState.builder()
                    .cycleIndex(0)
                    .cycleAccumulated(BigDecimal.ZERO)
                    .build();
        }

        // ... 使用 state 进行计算

        // 构建结果
        BillingSegmentResult result = new BillingSegmentResult();
        // ... 设置其他字段

        // 将最终状态存储到 ruleOutputState（供 buildCarryOverState 提取）
        result.setRuleOutputState(toMap(state));

        return result;
    }

    @Override
    public Map<String, Object> buildCarryOverState(BillingSegmentResult result) {
        // 从 result 提取状态
        return result.getRuleOutputState() != null
                ? result.getRuleOutputState()
                : Collections.emptyMap();
    }
}
```

### 规则实现示例（DayNightRule）

```java
public class DayNightRule implements BillingRule<DayNightConfig> {

    /** 规则状态结构 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RuleState {
        private int cycleIndex;              // 当前24小时周期索引
        private BigDecimal cycleAccumulated; // 当前周期累计金额
        private LocalDateTime cycleBoundary; // 周期边界时间（beginTime + 24h）
    }

    // 从 Map 恢复
    private RuleState restoreState(Map<String, Object> stateMap) {
        if (stateMap == null) return null;
        return RuleState.builder()
                .cycleIndex((Integer) stateMap.getOrDefault("cycleIndex", 0))
                .cycleAccumulated((BigDecimal) stateMap.getOrDefault("cycleAccumulated", BigDecimal.ZERO))
                .cycleBoundary((LocalDateTime) stateMap.get("cycleBoundary"))
                .build();
    }

    // 序列化为 Map
    private Map<String, Object> toMap(RuleState state) {
        Map<String, Object> map = new HashMap<>();
        map.put("cycleIndex", state.getCycleIndex());
        map.put("cycleAccumulated", state.getCycleAccumulated());
        map.put("cycleBoundary", state.getCycleBoundary());
        return map;
    }

    @Override
    public BillingSegmentResult calculate(BillingContext context, DayNightConfig config, PromotionAggregate promotionAggregate) {
        // 恢复状态
        RuleState state = restoreState(context.getRuleState());
        if (state == null) {
            // FROM_SCRATCH: 初始化状态
            state = RuleState.builder()
                    .cycleIndex(0)
                    .cycleAccumulated(BigDecimal.ZERO)
                    .cycleBoundary(context.getBeginTime().plusHours(24))
                    .build();
        }

        // ... 使用 state 进行日夜分时段计算

        // 构建结果
        BillingSegmentResult result = new BillingSegmentResult();
        // ... 设置其他字段

        // 将最终状态存储到 ruleOutputState
        result.setRuleOutputState(toMap(state));

        return result;
    }

    @Override
    public Map<String, Object> buildCarryOverState(BillingSegmentResult result) {
        return result.getRuleOutputState() != null
                ? result.getRuleOutputState()
                : Collections.emptyMap();
    }
}
```

## 优惠结转处理

### PromotionAggregate 新增字段

```java
/** 从 carryOver 恢复的剩余分钟数 */
private Map<String, Integer> remainingMinutesFromCarryOver;

/** 已使用的免费时段（从 carryOver 恢复） */
private List<FreeTimeRange> usedFreeRangesFromCarryOver;
```

### PromotionEngine 变更

```
evaluate(context, promoCarryOver)
├── 如果 promoCarryOver 为空：按现有逻辑计算
└── 如果 promoCarryOver 不为空：
    ├── 注入 remainingMinutes 到 PromotionAggregate
    ├── 注入 usedFreeRanges 到 PromotionAggregate
    └── FreeMinuteAllocator / FreeTimeRangeMerger 使用结转数据
```

### 免费时段追踪逻辑

当免费时段部分使用时，需要记录已使用的部分：
- 第一次计算使用 09:00-10:00，记录到 usedFreeRanges
- 第二次 CONTINUE 计算时，检查 usedFreeRanges，发现 10:00-11:00 仍可用

## 测试用例

### 测试1：基础 CONTINUE 流程

```
第一次计算 FROM_SCRATCH：
  时间：08:00-10:00
  预期：返回 carryOver，calculatedUpTo = 10:00

第二次计算 CONTINUE：
  时间：08:00-12:00（原时间窗口）
  carryOver：上次结果
  预期：实际计算 10:00-12:00，carryOver.calculatedUpTo = 12:00
```

### 测试2：优惠分钟数结转

```
第一次：30分钟免费，用了20分钟，剩余10分钟
第二次：继续使用剩余10分钟
预期：两次总共使用30分钟
```

### 测试3：周期封顶结转

```
第一次：周期封顶20元，累计15元
第二次：继续累计，超过20元时触发封顶
预期：封顶逻辑正确延续
```

### 测试4：跨分段结转

```
方案切换场景：
  Segment1 (scheme-A): 08:00-12:00
  Segment2 (scheme-B): 12:00-14:00

第一次计算完整，第二次 CONTINUE 从 14:00 继续
预期：每个分段的状态独立结转
```

### 测试5：免费时段追踪

```
优惠券：09:00-11:00 免费时段
第一次：使用了该时段
第二次：该时段已标记为已使用，不会重复使用
```

### 测试6：免费时段部分使用后继续

```
优惠券：免费时段 09:00-11:00

第一次计算：08:00-10:00
  实际使用免费时段：09:00-10:00（部分使用）
  usedFreeRanges 记录：[09:00-10:00]

第二次计算 CONTINUE：10:00-12:00
  剩余免费时段：10:00-11:00（继续使用后半部分）
  预期：10:00-11:00 免费，11:00-12:00 收费
```

### 测试7：计费单元延伸

```
配置：
  单元长度：60分钟
  时间段边界：120分钟（从计费起点）

计费时间：08:00-09:00（1小时）
原截断单元：08:00-09:00（被结束时间截断）
下一个时间段边界：10:00（120分钟边界）
下一个周期边界：次日 08:00（24小时边界）

延伸后单元：08:00-10:00（取最近的边界：时间段边界）
收费金额：全额（不变）
calculationEndTime：10:00
effectiveTo：10:00（延伸后，缓存有效期延长）

CONTINUE 场景：下次从 10:00 继续，避免重复计算延伸部分
```

## 计费单元延伸实现

### 延伸逻辑

最后一个计费单元的延伸由规则在计算时处理：

```
计算最后一个单元时：
1. 获取单元原始结束时间（未截断的完整单元）
2. 查找下一个边界：
   - 下一个周期边界：currentCycleEnd + 24h
   - 下一个时间段边界：根据 RelativeTimeRule 的 periods 配置
3. 取最近的边界作为延伸终点
4. 更新单元 endTime 为延伸后的时间
5. 设置 calculationEndTime = 延伸后的单元结束时间
6. 收费金额不变
```

### 边界计算规则

**周期边界**：
- DayNightRule：从计费起点开始，每24小时为一个周期
- RelativeTimeRule：同上

**时间段边界**：
- RelativeTimeRule：根据 `RelativeTimePeriod.endMinute` 计算
  - 例如：period1 = 0-120分钟，period2 = 120-1440分钟
  - 计费起点 08:00，第一个时间段边界在 10:00（120分钟）

### 与 CONTINUE 模式的关联

```
第一次计算：
  计费时间：08:00-09:00
  延伸后：08:00-10:00
  calculationEndTime：10:00
  carryOver.calculatedUpTo：10:00

第二次计算（CONTINUE）：
  请求时间：08:00-12:00
  从 calculatedUpTo（10:00）继续计算
  实际计算：10:00-12:00
  延伸后：10:00-11:00（下一个边界）
  ...
```

## 文件变更清单

### 新增文件

| 文件路径 | 说明 |
|---------|------|
| `core/.../billing/pojo/BillingCarryOver.java` | 顶层结转对象 |
| `core/.../billing/pojo/SegmentCarryOver.java` | 分段级结转 |
| `core/.../billing/pojo/PromotionCarryOver.java` | 优惠结转 |

### 修改文件

| 文件路径 | 变更内容 |
|---------|---------|
| `core/.../billing/pojo/BillingRequest.java` | 新增 previousCarryOver 字段 |
| `core/.../billing/pojo/BillingResult.java` | 新增 carryOver、calculationEndTime 字段 |
| `core/.../billing/pojo/BillingContext.java` | 删除 progress，新增 ruleState |
| `core/.../billing/pojo/BillingSegmentResult.java` | 取消 carryOverAfter 注释，新增 calculationEndTime、ruleOutputState 字段 |
| `core/.../billing/BillingSegment.java` | 新增 id 字段 |
| `core/.../billing/SegmentBuilder.java` | 生成分段 id |
| `core/.../billing/BillingService.java` | 实现 CONTINUE 模式逻辑 |
| `core/.../settlement/ResultAssembler.java` | 汇总 calculationEndTime |
| `core/.../charge/rules/BillingRule.java` | 新增 buildCarryOverState 方法 |
| `core/.../charge/rules/relativetime/RelativeTimeRule.java` | 实现 RuleState 内部类 + 状态恢复 + 计费单元延伸 |
| `core/.../charge/rules/daynight/DayNightRule.java` | 实现 RuleState 内部类 + 状态恢复 + 计费单元延伸 |
| `core/.../promotion/PromotionEngine.java` | 支持 promoCarryOver 参数 |
| `core/.../promotion/pojo/PromotionAggregate.java` | 新增结转相关字段 |

### 删除文件

| 文件路径 | 说明 |
|---------|------|
| `core/.../billing/pojo/BillingProgress.java` | 不再需要 |

## 后续演进

当前采用通用 Map 结构（方案A），后续可演进到"接口+实现分离"（方案C）：
- 定义 `RuleCarryOver` 接口
- 每个规则实现自己的强类型 CarryOver 类
- 使用 Jackson 多态序列化

这样既保持类型安全，又保持扩展性。