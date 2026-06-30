# 时间计费引擎

[English](README.md) | [使用指南](docs/USER_GUIDE.md) | [能力文档](docs/billing-engine-capabilities-zh.md)

一个可扩展、可追溯的时间计费引擎，适用于停车收费、场地租赁、设备租赁和其他按时间计费的场景。

## 提供能力

- 新增计费规则时尽量不修改核心引擎。
- 输出完整计费单元，便于审计和调试。
- 支持从上次结果继续计算。
- 支持收费方案随时间切换。
- 支持免费时间段和免费分钟数优惠。
- 通过 `billing-api` 支持查询时点金额计算。

## 环境要求

- JDK 21+
- Maven 3.6+

JDK 25 同样兼容，已在 OpenJDK 25 上验证。

## 安装

### 推荐：`billing-api`

```xml
<dependency>
    <groupId>io.github.shangtx</groupId>
    <artifactId>billing-api</artifactId>
    <version>2.1.2</version>
</dependency>
```

### Spring Boot Starter

```xml
<!-- Spring Boot 3.0.x - 3.4.x -->
<dependency>
    <groupId>io.github.shangtx</groupId>
    <artifactId>billing-v3-spring-boot-starter</artifactId>
    <version>2.1.2</version>
</dependency>

<!-- Spring Boot 3.5.x - 4.x -->
<dependency>
    <groupId>io.github.shangtx</groupId>
    <artifactId>billing-v4-spring-boot-starter</artifactId>
    <version>2.1.2</version>
</dependency>
```

## 最小用法

实现 `BillingConfigResolver`，创建 `BillingTemplate`，然后调用 `calculate()`。

```java
BillingRequest request = new BillingRequest();
request.setBeginTime(beginTime);
request.setEndTime(endTime);
request.setSchemeId("scheme-1");
request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);

BillingResult result = billingTemplate.calculate(request);
```

完整的手动装配、规则注册、Spring Boot 接入、查询时点金额、继续计算和自定义规则说明见 [使用指南](docs/USER_GUIDE.md)。

## 当前能力说明

- 已实现计费规则包括 `dayNight`、`relativeTime`、`naturalTime`、`compositeTime` 和 `flatFree`。
- 已实现优惠能力主要包括 `FREE_RANGE`、`FREE_MINUTES`、`freeMinutes` 和 `startFree`。
- `CONTINUOUS` 模式采用边界驱动循环，连续相同单元合并为 compact 单元，显著减少细粒度计费场景下的结果体积。
- `UNIT_BASED` 模式计划降级为独立计费规则类型，普通规则只保留 `CONTINUOUS`（见 TODO）。
- `AMOUNT`、`DISCOUNT` 是预留优惠类型，`times` 是预留计费类型（非时间计费场景）。
- 查询时点金额由命中单元的 `valueSpec` 计算，不再直接读取 `accumulatedAmount`；compact 单元按子单元投影。

完整能力矩阵和已知限制见 [计费引擎能力文档](docs/billing-engine-capabilities-zh.md)。

## 文档

| 文档 | 用途 |
|------|------|
| [使用指南](docs/USER_GUIDE.md) | 面向调用者的主要使用文档 |
| [能力文档](docs/billing-engine-capabilities-zh.md) | 已实现能力和当前限制 |
| [计算流程](docs/billing-engine-calculation-flow-zh.md) | 中文计算流程参考 |
| [TODO](docs/TODO.md) | 当前待办和已知问题 |

## 许可证

MIT License
