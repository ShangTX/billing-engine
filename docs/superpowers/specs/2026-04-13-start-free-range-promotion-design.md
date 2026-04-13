# StartFreeRange Promotion Rule Design

> **Status:** Draft - Pending User Review

## Goal

新增"前N分钟免费"优惠规则。从计费段起点开始的 N 分钟为免费时段，生成的免费时间段和已有的 FREE_RANGE 按优先级合并，不会像 FREE_MINUTES 那样避开已有免费时段。

## Design

### Architecture

新增一个 `PromotionRule` 实现，配置项仅 `minutes`。规则的 `grant()` 方法计算出免费时间段的 `beginTime`（段起点）和 `endTime`（段起点 + N 分钟），生成 `type = FREE_RANGE` 的 `PromotionGrant`。

该 grant 后续完全走现有 FREE_RANGE 处理路径：
- `FreeTimeRangeMerger.merge()` 按优先级合并
- `PromotionAggregate.freeTimeRanges` 记录合并后的时段
- `PromotionAggregate.usageMap` 记录使用情况（由 `FreeTimeRangeMerger` 的 `consumedMinutes` 追踪）

**无需修改** `PromotionEngine`、`FreeTimeRangeMerger`、`PromotionGrant` 等核心类。

### Bug Fix: Source 字段传递

当前 `PromotionEngine.convertTimeRangeFromRule()` 转换时**未传递 `source` 字段**，导致最终 `FreeTimeRange` 无法区分优惠来源（规则 vs 优惠券）。修复方式：

1. **`FreeTimeRange` 新增 `source` 字段**：
```java
// FreeTimeRange.java
/**
 * 优惠来源：RULE（规则）/ COUPON（优惠券）
 */
private BConstants.PromotionSource source;
```

2. **`PromotionEngine.convertTimeRangeFromRule()` 传递 source**：
```java
// PromotionEngine.java - convertTimeRangeFromRule()
return FreeTimeRange.builder()
    .id(grant.getId())
    .promotionType(grant.getType())
    .beginTime(grant.getBeginTime())
    .endTime(grant.getEndTime())
    .priority(grant.getPriority())
    .rangeType(grant.getRangeType())
    .source(grant.getSource())  // 新增：传递 source 字段
    .build();
```

这样所有规则产生的免费时段，最终都能在结果中区分来源。`StartFreePromotionRule` 生成的 grant 默认 `source = PromotionSource.RULE`，无需显式设置。

### New Types

| Type | File | Purpose |
|------|------|---------|
| `StartFreePromotionConfig` | `core/.../promotion/rules/startfree/StartFreePromotionConfig.java` | 配置类，包含 `id`、`type`、`minutes` |
| `StartFreePromotionRule` | `core/.../promotion/rules/startfree/StartFreePromotionRule.java` | 规则实现，`grant()` 返回单条 FREE_RANGE grant |
| `BConstants.PromotionRuleType.START_FREE` | `core/.../billing/pojo/BConstants.java` | 新增规则类型常量 `"startFree"` |

### Data Flow

```
BillingService.calculate()
  └── RuleResolver.resolvePromotionRules()
        └── StartFreePromotionConfig { minutes: 30 }
              └── PromotionEngine.evaluate()
                    └── StartFreePromotionRule.grant()
                          → PromotionGrant {
                              type: FREE_RANGE,
                              beginTime: segment.beginTime,
                              endTime: segment.beginTime + 30min,
                              id: config.id
                            }
                    └── FreeTimeRangeMerger.merge() ← 与其他 FREE_RANGE 合并
                    └── PromotionAggregate { freeTimeRanges, usageMap }
```

### CONTINUE Mode

N 分钟始终相对于 `segment.beginTime`，不随 `calcBegin` 变化。例如：
- Segment: 08:00-14:00, minutes=30
- 第一次计算窗口 08:00-10:00 → grant: 08:00-08:30
- CONTINUE 窗口 08:00-12:00 → grant 仍然是 08:00-08:30（相对于段起点）

### Promotion Usage Tracking

使用和现有 FREE_RANGE 相同的记录方式。`FreeTimeRangeMerger` 在合并时追踪每个原始 grant 的 `consumedMinutes`，最终记录到 `PromotionAggregate.usageMap`。

### Components

#### StartFreePromotionConfig
```
id: String
type: String = "startFree"
minutes: int
```

#### StartFreePromotionRule
```
configClass() → StartFreePromotionConfig.class
grant(context, config, externalGrants) →
  beginTime = context.segment.beginTime
  endTime = beginTime + config.minutes
  return [PromotionGrant {
    id: config.id,
    type: FREE_RANGE,
    beginTime: beginTime,
    endTime: endTime,
    priority: config.priority (如有)
  }]
```

## Files to Modify

| File | Action |
|------|--------|
| `BConstants.java` | 新增 `PromotionRuleType.START_FREE = "startFree"` |
| `StartFreePromotionConfig.java` | 创建 |
| `StartFreePromotionRule.java` | 创建 |
| `FreeTimeRange.java` | 新增 `source` 字段 |
| `PromotionEngine.java` | `convertTimeRangeFromRule()` 传递 source |
| `FreeTimeRange.copy()` | 复制 source 字段 |
| `FreeTimeRange.copyWithNewId()` | 复制 source 字段 |
| `bill-test/.../StartFreePromotionTest.java` | 创建集成测试 |

## Out of Scope

- 不修改 `PromotionEngine` 核心评估逻辑（只改 `convertTimeRangeFromRule()` 的字段传递）
- 不修改 `FreeTimeRangeMerger` 的合并逻辑
- 不新增 `PromotionType` 枚举值
- 不修改 CONTINUE 模式的状态结转逻辑
