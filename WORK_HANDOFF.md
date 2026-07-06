# Billing-Engine 工作交接

## 当前分支

`incomplete-unit-charge`

## 最近提交

- `02162b6` - refactor: 废弃 AbstractTimeBasedRule 与旧模型（TODO-20260706-002 阶段7）
- `1fac17d` - refactor: CalculationMode 合并替代双 enum（TODO-20260706-002 阶段1）

> 注：本文为历史交接记录，部分内容（核心引擎重构、测试覆盖）保留作背景。当前架构以四层为准（`RuleSemantics` → `BoundaryDrivenLoop` → `ModeStrategy` → `BillingRule` 门面），详见 `AGENTS.md` 与 `docs/billing-engine-capabilities-zh.md`。

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
- `SimplifiedUnitMetaTest` - 简化单元 ruleData Map 契约测试（旧 SimplifiedUnitMeta 已删除）

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

> 以下为四层架构重构（TODO-20260706-002）后的当前结构。旧 Calculator 类（`*ContinuousCalculator`/`*UnitBasedCalculator`）、`AbstractTimeBasedRule`、`AbstractContinuousCapHandler`、`SimplifiedCycleStateHelper`、`SimplifiedUnitMeta` 均已删除。

```
charge/
├── core/src/main/java/cn/shang/charging/
│   ├── billing/pojo/
│   │   ├── BConstants.java          # 常量定义（CalculationMode / PromotionType / IncompleteUnitChargeMode 等）
│   │   └ (简化单元元数据改由 ruleData Map 承载，见 ContinuousStrategy.buildSimplifiedUnit)
│   ├── charge/rules/
│   │   ├── BillingRule.java             # 规则接口（calculate / configClass / supportedCalculationModes）
│   │   ├── BillingRuleRegistry.java
│   │   ├── RuleSemantics.java           # 层0 规则族语义接口
│   │   ├── RuleSupport.java             # FREE_MINUTES 时段化（materializeFreeMinutes）
│   │   ├── BoundaryDrivenLoop.java      # 层1 公共循环入口（run）
│   │   ├── BoundaryProvider.java / BoundaryProviders.java
│   │   ├── HomogeneousSegment.java / HomogeneousSegmentCalculator.java
│   │   ├── CompactMerger.java
│   │   ├── ContinuousStrategy.java      # 层2 CONTINUOUS 通用骨架（applyCapAndAccumulate / 简化单元 / 不足单元）
│   │   ├── DurationPeriodStrategy.java  # 层2 DURATION_PERIOD（接收 RuleSemantics）
│   │   ├── DurationGlobalStrategy.java  # 层2 DURATION_GLOBAL（接收 RuleSemantics，消费 SMART_FREE_MINUTES）
│   │   ├── DurationSupport.java         # 时长策略共享工具（segmentCharge / buildPeriodMode / buildGlobalMode）
│   │   ├── CalculationContext.java
│   │   ├── daynight/
│   │   │   ├── DayNightRule.java           # 层3 门面
│   │   │   ├── DayNightConfig.java
│   │   │   ├── DayNightSemantics.java      # 层0 语义实现
│   │   │   ├── DayNightContinuousStrategy.java  # implements BillingRule，委托通用 ContinuousStrategy
│   │   │   ├── DayNightUnitBasedStrategy.java   # UNIT_BASED 策略
│   │   │   ├── DayNightPriceResolver.java
│   │   │   └ DayNightPeriodType.java
│   │   ├── relativetime/
│   │   │   ├── RelativeTimeRule.java
│   │   │   ├── RelativeTimeConfig.java / RelativeTimePeriod.java
│   │   │   ├── RelativeTimeSemantics.java
│   │   │   ├── RelativeTimeContinuousStrategy.java
│   │   │   └ RelativeTimePeriodResolver.java
│   │   ├── naturaltime/
│   │   │   ├── NaturalTimeRule.java
│   │   │   ├── NaturalTimeConfig.java
│   │   │   ├── NaturalTimeSemantics.java
│   │   │   ├── NaturalTimeContinuousStrategy.java
│   │   │   ├── NaturalTimeCrossPeriodPriceResolver.java
│   │   │   └ NaturalTimePeriodResolver.java
│   │   └ compositetime/
│   │       ├── CompositeTimeRule.java
│   │       ├── CompositeTimeConfig.java / CompositePeriod.java / NaturalPeriod.java / CrossPeriodMode.java
│   │       ├── CompositeTimeSemantics.java
│   │       ├── CompositeTimeContinuousStrategy.java
│   │       ├── CompositeTimeCrossPeriodPriceResolver.java
│   │       └ CompositeTimePeriodResolver.java
│   ├── promotion/
│   │   ├── PromotionEngine.java
│   │   ├── AmountDiscountApplier.java
│   │   ├── ExternalPromotionPool.java      # 外部优惠跨段共享可用量池（FREE_MINUTES/SMART_FREE_MINUTES）
│   │   └ pojo/
│   │       ├── PromotionGrant.java
│   │       └ PromotionAggregate.java       # 含 smartFreeMinutesList（标量透传）
├── bill-test/src/test/java/cn/shang/charging/
│   ├── DayNightParkingParityTest.java
│   ├── RelativeTimeParkingParityTest.java
│   ├── CompositeTimeSmokeTest.java
│   ├── NaturalTimeSmokeTest.java
│   ├── EngineBoundarySmokeTest.java
│   ├── BillingApiBoundaryTest.java
│   └ SimplifiedUnitMetaTest.java          # 简化单元 ruleData Map 契约测试
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