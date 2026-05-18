# Billing-Engine 工作交接

## 当前分支

`conditional-free-fix`

## 最近提交

- `d70eb72` - feat: 收敛核心引擎职责边界与规则复杂度

## 已完成工作

### 核心引擎重构 (2026-05-18)

1. **CompositeTimeRule 重构**
   - 将状态更新逻辑抽离到 `CompositeTimeSimplifiedCycleStateManager`
   - 新增独立 Calculator: `CompositeTimeUnitBasedCalculator`, `CompositeTimeContinuousCalculator`
   - 新增 `CompositeTimeCrossPeriodPriceResolver` 处理跨时段定价

2. **NaturalTimeRule 新增**
   - 支持多自然时段计费（按日/周/月等自然周期）
   - 配置类: `NaturalTimeConfig`
   - 实现: `NaturalTimeRule`, `NaturalTimeUnitBasedCalculator`, `NaturalTimeContinuousCalculator`

3. **DayNightRule/RelativeTimeRule 重构**
   - 抽取 `DayNightPriceResolver`, `RelativeTimePeriodResolver` 等
   - 统一使用 `SimplifiedCycleStateHelper` 处理周期状态

4. **IncompleteUnitChargeMode 支持**
   - 新增枚举: `FULL_CHARGE`, `PROPORTIONAL`, `FREE`, `THRESHOLD_MINUTES`, `THRESHOLD_RATIO`
   - 配置字段已添加到 `DayNightConfig`, `RelativeTimeConfig`, `NaturalTimeConfig`

5. **优惠扩展**
   - `PromotionGrant` 新增 `AMOUNT`, `DISCOUNT` 类型支持
   - 新增 `AmountDiscountApplier` 处理金额减免和折扣

### 测试覆盖

- `DayNightParkingParityTest` - 日夜规则停车语义验证
- `RelativeTimeParkingParityTest` - 相对时间规则停车语义验证
- `CompositeTimeSmokeTest` - 组合时间规则冒烟测试
- `NaturalTimeSmokeTest` - 自然时间规则冒烟测试
- `EngineBoundarySmokeTest` - 引擎边界冒烟测试
- `BillingApiBoundaryTest` - API 边界测试
- `SimplifiedUnitMetaTest` - 简化单元元数据测试

### 文档

- 7 项 TODO 已迁移到 `docs/DONE.md`
- 详情文档在 `docs/tracking/items/`

## 待后续工作

### pengbo-park 适配层（未提交）

在 `java_monorepo/pengbo-park` 中已实现但未提交：

- `BillingEngineChargingMapper.java` - ChargingDTO → BillingRequest 映射
- `BillingEngineChargingService.java` - ChargingService 新实现 (@Primary)
- `BillingEngineGateTest.java` - 准入测试（2 测试通过）
- `BillingTestSupport.java` - 测试辅助类

待用户指令后提交。

### 待完善事项

1. **finishCharging() 实现**
   - 缓存清理逻辑
   - 计费详情持久化

2. **业务场景验证**
   - 预估收入链路
   - 出场收费链路
   - 多天停车、封顶场景

3. **发布准备**
   - 版本号更新
   - 发布到 Maven Central

## 关键文件索引

```
billing/
├── core/src/main/java/cn/shang/charging/
│   ├── billing/pojo/
│   │   ├── BConstants.java          # 常量定义（IncompleteUnitChargeMode 等）
│   │   └ SimplifiedUnitMeta.java    # 简化单元元数据
│   ├── charge/rules/
│   │   ├── AbstractTimeBasedRule.java
│   │   ├── AbstractContinuousCapHandler.java
│   │   ├── SimplifiedCycleStateHelper.java
│   │   ├── daynight/
│   │   │   ├── DayNightRule.java
│   │   │   ├── DayNightConfig.java
│   │   │   ├── DayNightPriceResolver.java
│   │   │   ├── DayNightUnitBasedCalculator.java
│   │   │   ├── DayNightContinuousCalculator.java
│   │   │   └ DayNightCycleStateManager.java
│   │   ├── relativetime/
│   │   │   ├── RelativeTimeRule.java
│   │   │   ├── RelativeTimeConfig.java
│   │   │   ├── RelativeTimePeriodResolver.java
│   │   │   ├── RelativeTimeUnitBasedCalculator.java
│   │   │   ├── RelativeTimeContinuousCalculator.java
│   │   │   ├── RelativeTimeContinuousCapHandler.java
│   │   │   └ RelativeTimeSimplifiedCycleStateManager.java
│   │   ├── naturaltime/
│   │   │   ├── NaturalTimeRule.java
│   │   │   ├── NaturalTimeConfig.java
│   │   │   ├── NaturalTimePeriodResolver.java
│   │   │   ├── NaturalTimeUnitBasedCalculator.java
│   │   │   ├── NaturalTimeContinuousCalculator.java
│   │   │   ├── NaturalTimeCrossPeriodPriceResolver.java
│   │   │   └ NaturalTimeCycleStateManager.java
│   │   └ compositetime/
│   │   │   ├── CompositeTimeRule.java
│   │   │   ├── CompositeTimeSimplifiedCycleStateManager.java
│   │   │   ├── CompositeTimeUnitBasedCalculator.java
│   │   │   ├── CompositeTimeContinuousCalculator.java
│   │   │   ├── CompositeTimeCrossPeriodPriceResolver.java
│   │   │   ├── CompositeTimePeriodResolver.java
│   │   │   └ CompositeTimeContinuousCapHandler.java
│   ├── promotion/
│   │   ├── PromotionEngine.java
│   │   ├── AmountDiscountApplier.java
│   │   └ pojo/
│   │       ├── PromotionGrant.java
│   │       └ PromotionAggregate.java
├── bill-test/src/test/java/cn/shang/charging/
│   ├── DayNightParkingParityTest.java
│   ├── RelativeTimeParkingParityTest.java
│   ├── CompositeTimeSmokeTest.java
│   ├── NaturalTimeSmokeTest.java
│   ├── EngineBoundarySmokeTest.java
│   ├── BillingApiBoundaryTest.java
│   └ SimplifiedUnitMetaTest.java
├── docs/
│   ├── DONE.md
│   ├── TODO.md
│   └ tracking/items/
│       ├── engine-boundary-and-rule-complexity.md
│       ├── reserved-charge-rule-types.md
│       ├── cross-period-unit-handling.md
│       ├── multi-natural-period-rule.md
│       ├── promotion-amount-discount.md
│       ├── incomplete-unit-charge-modes.md
│       └ billing-test-generator-rule-coverage.md
```

## 测试运行

```bash
# 使用 Java 21
cd billing
mvn -t C:/Users/shang/.m2/toolchains.xml test
```