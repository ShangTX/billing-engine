# 计费模式功能设计

## 概述

实现两种计费模式：CONTINUOUS（连续时间计费）和 UNIT_BASED（计费单位模式），支持规则声明支持的模式并在计算时校验。

## 语义说明

系统存在两种"模式"概念，分别代表不同维度：

| 维度 | 枚举 | 值 | 含义 |
|------|------|-----|------|
| 继续模式 | `ContinueMode` | FROM_SCRATCH / CONTINUE | 是否从上次结果继续计算 |
| 计费模式 | `BillingMode` | CONTINUOUS / UNIT_BASED | 计费单位如何划分 |

**本次新增 `BillingMode`，并将原 `BillingMode` 重命名为 `ContinueMode`。**

## 变更内容

### 1. 枚举重命名与新增

**重命名 `BillingMode` → `ContinueMode`**

位置：`BConstants.java`

```java
public enum ContinueMode {
    FROM_SCRATCH, // 从开始时间计算
    CONTINUE      // 从上一次的结果继续计算
}
```

**新增 `BillingMode` 枚举**

```java
public enum BillingMode {
    CONTINUOUS, // 连续时间计费模式
    UNIT_BASED  // 计费单位模式
}
```

### 2. RuleResolver 改名（Breaking Change）

`RuleResolver` → `BillingConfigResolver`

新增方法：

```java
BillingMode resolveBillingMode(String schemeId);
```

**影响范围**：
- `BillingService.java`（字段、构造器、调用）
- `PromotionEngine.java`（构造器参数）

### 3. BillingRule 接口新增方法

```java
Set<BillingMode> supportedModes();
```

### 4. BillingContext 变更

新增字段：

```java
private BillingMode billingMode;
```

同时修正现有注释（当前注释 STATELESS/CACHE/ERSIST 与实际 FROM_SCRATCH/CONTINUE 不匹配）。

### 5. BillingService 集成

在构建 `BillingContext` 时获取并设置计费模式：

```java
// 解析计费模式
BillingMode billingMode = billingConfigResolver.resolveBillingMode(segment.getSchemeId());

// 构建 BillingContext
BillingContext context = BillingContext.builder()
    .id(request.getId())
    .beginTime(request.getBeginTime())
    .endTime(request.getEndTime())
    .segment(segment)
    .window(window)
    .chargingRule(chargingRule)
    .promotionRules(promotionRules)
    .externalPromotions(request.getExternalPromotions())
    .billingMode(billingMode) // 新增
    .build();
```

### 6. BillingCalculator 校验逻辑

在 `calculate()` 方法开头统一校验：

```java
if (!billingRule.supportedModes().contains(context.getBillingMode())) {
    throw new IllegalStateException(
        "Rule " + billingRule.getClass().getSimpleName() +
        " (type=" + ruleConfig.getType() + ") does not support billing mode: " +
        context.getBillingMode()
    );
}
```

### 7. 现有规则实现

`DayNightRule` 和 `RelativeTimeRule` 声明支持两种模式：

```java
@Override
public Set<BillingMode> supportedModes() {
    return EnumSet.of(BillingMode.CONTINUOUS, BillingMode.UNIT_BASED);
}
```

内部暂不区分行为，后续演进时抽离公共逻辑。

### 8. Null 处理

- `resolveBillingMode()` 不应返回 `null`，外部实现必须提供有效值
- 若外部无法确定，应抛出异常而非返回 `null`
- 规则的 `supportedModes()` 必须返回非空 Set

## 涉及文件

| 文件 | 变更类型 |
|------|----------|
| `BConstants.java` | 重命名枚举 + 新增枚举 |
| `RuleResolver.java` | 重命名为 BillingConfigResolver + 新增方法 |
| `BillingRule.java` | 新增方法 |
| `BillingContext.java` | 新增字段 + 修正注释 |
| `BillingService.java` | 更新引用 + 调用 resolveBillingMode |
| `BillingCalculator.java` | 新增校验逻辑 |
| `PromotionEngine.java` | 更新 RuleResolver 引用为 BillingConfigResolver |
| `DayNightRule.java` | 实现 supportedModes() |
| `RelativeTimeRule.java` | 实现 supportedModes() |

## 后续演进方向

当前实现方式为规则内部判断，未来应将可复用的计费模式逻辑抽离到引擎层面统一处理。