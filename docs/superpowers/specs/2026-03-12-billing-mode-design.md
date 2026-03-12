# 计费模式功能设计

## 概述

实现两种计费模式：CONTINUOUS（连续时间计费）和 UNIT_BASED（计费单位模式），支持规则声明支持的模式并在计算时校验。

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

### 2. RuleResolver 改名

`RuleResolver` → `BillingConfigResolver`

新增方法：

```java
BillingMode resolveBillingMode(String schemeId);
```

### 3. BillingRule 接口新增方法

```java
Set<BillingMode> supportedModes();
```

### 4. BillingContext 新增字段

```java
private BillingMode billingMode;
```

### 5. BillingCalculator 校验逻辑

在 `calculate()` 方法开头统一校验：

```java
if (!billingRule.supportedModes().contains(context.getBillingMode())) {
    throw new IllegalStateException(
        "Rule " + billingRule.getClass().getSimpleName() +
        " does not support billing mode: " + context.getBillingMode()
    );
}
```

### 6. 现有规则实现

`DayNightRule` 和 `RelativeTimeRule` 声明支持两种模式：

```java
@Override
public Set<BillingMode> supportedModes() {
    return EnumSet.of(BillingMode.CONTINUOUS, BillingMode.UNIT_BASED);
}
```

内部暂不区分行为，后续演进时抽离公共逻辑。

## 涉及文件

| 文件 | 变更类型 |
|------|----------|
| `BConstants.java` | 重命名枚举 + 新增枚举 |
| `RuleResolver.java` | 重命名 + 新增方法 |
| `BillingRule.java` | 新增方法 |
| `BillingContext.java` | 新增字段 |
| `BillingCalculator.java` | 新增校验逻辑 |
| `DayNightRule.java` | 实现 supportedModes() |
| `RelativeTimeRule.java` | 实现 supportedModes() |
| 所有引用 RuleResolver 的文件 | 更新引用名称 |

## 后续演进方向

当前实现方式为规则内部判断，未来应将可复用的计费模式逻辑抽离到引擎层面统一处理。