# 实施计划:计费引擎抽象重构(TODO-20260706-002)

**关联 spec**: `docs/superpowers/specs/2026-07-06-rule-abstraction-refactor-design.md`
**详情**: `docs/tracking/items/rule-abstraction-refactor.md`
**前置**: TODO-20260706-001(GLOBAL 时段化修复,阶段 0,独立先行)
**基线**: 现有测试全绿(`mvn -pl bill-test -am test`)

---

## 实施顺序

按 spec 第 7 节分阶段(阶段 0 见 TODO-20260706-001,本计划覆盖阶段 1-8)。阶段间有依赖,按序进行,每阶段独立提交、可验证。

### 阶段 1:CalculationMode 合并(决策 A)

- `BConstants`:删 `BillingMode` / `DurationMode`,加 `CalculationMode { CONTINUOUS, UNIT_BASED, DURATION_PERIOD, DURATION_GLOBAL }`
- `BillingConfigResolver`:`resolveBillingMode` + `resolveDurationMode` → `resolveCalculationMode`
- `BillingContext`:`billingMode` + `durationMode` → `calculationMode`
- `BillingRule`:`supportedModes` + `supportedDurationModes` → `supportedCalculationModes`
- `BillingCalculator`:两处校验合并为一处
- `BillingService`:`calculate` 两次解析 → 一次;`prepareContexts` 暂同步(阶段 6 完整修)
- 各门面(`DayNightRule` 等):`calculate` 分派改 `switch(calculationMode)`
- `BillingSegmentResult`:`durationMode` → `calculationMode`
- `validateGlobalOrigin`:`UNIT_BASED` 判断改读 `calculationMode`

**验证**:`mvn -pl bill-test -am test` 全绿;无 `BillingMode`/`DurationMode` 残留引用。

### 阶段 2:3 规则族门面化

`RelativeTime` / `NaturalTime` / `CompositeTime` 各自(建议 2a/2b/2c 独立提交):

- Rule 类变纯分派门面,不再 `extends AbstractTimeBasedRule`
- CONTINUOUS 逻辑下沉到 `XxxContinuousStrategy`(独立类,不继承 `AbstractTimeBasedRule`)
- 实现 `XxxSemantics`(`RuleSemantics`),封装周期/时段/价格语义
- 声明 `supportedCalculationModes`(当前仅 `CONTINUOUS`,时长模式阶段 4 加)
- 删 Rule 类内的 `calculateContinuousInternal` / `applyCapAndAccumulate`(迁移到 `ContinuousStrategy`)
- `ContinuousCalculator` 中间层移除

**验证**:3 规则族现有测试全绿(`RelativeTimeParkingParityTest` / `NaturalTimeSmokeTest` / `CompositeTimeSmokeTest` 等);3 个 Rule 类不继承 `AbstractTimeBasedRule`。

### 阶段 3:抽 ContinuousStrategy 通用骨架 + 简化全局空隙(决策 C)

- 提取通用 `applyCapAndAccumulate` 到 `ContinuousStrategy`(唯一一份),周期切换通过 `RuleSemantics.isCycleBoundary` 注入,periodCap 通过 `periodLabeler` 注入
- 4 规则族 CONTINUOUS 策略统一调用通用 `ContinuousStrategy`(各自 `Semantics` 注入差异)
- `generateSimplifiedUnitsForContinuous` 重写为全局空隙实现:
  - 从 `freeTimeRanges` 算无优惠空隙(优惠时段之间的间隙 + 头尾)
  - 每个 gap 对齐周期边界,周期数 > 阈值 → 简化单元(`min(总应收, cycleCap × 周期数)`)
  - `ruleData` 记 `{dayAmount, nightAmount, cycleCount, isSimplified}`
- 删 `findCyclesWithPromotion`(改基于 `freeTimeRanges`)
- 删 `organizeByCycle` / `splitTimeAxis` / `TimeFragment` / `CycleFragments`(旧切段模型)

**验证**:4 规则族 CONTINUOUS 测试全绿(金额不变);简化测试(无优惠后缀、穿插无优惠)金额不变、单元数一致;旧切段模型无引用。

### 阶段 4:抽 DurationSupport + 拆两个时长策略(决策 B)

- 提取 `DurationSupport` 工具(`segmentCharge` / `segmentOriginalCharge` / `DurationResult` / `PeriodResolver` 接口)
- `DayNightDurationStrategy` 拆为 `DurationPeriodStrategy` / `DurationGlobalStrategy`(通用,接收 `RuleSemantics`)
  - `DurationPeriodStrategy`:周期边界 + 周期内累计封顶
  - `DurationGlobalStrategy`:无周期边界 + 全局倍乘封顶(阶段 0 已时段化)
- dayNight 私有部分(`dayNightBoundary` + day/night 标签)归 `DayNightSemantics`
- 3 规则族(阶段 2 已门面化)声明 `supportedCalculationModes` 含 `DURATION_PERIOD`/`DURATION_GLOBAL`,自动获得时长能力
- 删 `DayNightDurationStrategy`(拆解完毕)

**验证**:`DurationBillingModeTest` 全绿;3 规则族新增时长模式冒烟测试(声明即支持);`DayNightDurationStrategy` 无引用。

### 阶段 5:SMART_FREE_MINUTES(决策 D)

- `BConstants.PromotionType` 加 `SMART_FREE_MINUTES`
- `FreeMinutes` / `PromotionAggregate` 支持 `SMART_FREE_MINUTES` 透传(不时段化)
- `DurationGlobalStrategy` 实现优先高价分配:
  - 用 `RuleSemantics.priceAt` + `periodBoundaryProvider` 切同价时段
  - 按单价降序消费 `SMART_FREE_MINUTES`,产出免费段(从时段起点切)
  - 与普通免费段(`FREE_RANGE` + `FREE_MINUTES` 时段化)合并,参与边界驱动
- `BillingCalculator`:非 GLOBAL 模式遇到 `SMART_FREE_MINUTES` 报错
- 同时存在普通 `FREE_MINUTES` + `SMART_FREE_MINUTES`:按 `priority` 排序,各自分配,跳过已占用时段
- `PromotionUsage.type` 区分 `FREE_MINUTES` / `SMART_FREE_MINUTES`,各自记录用量/来源

**验证**:新增 `SMART_FREE_MINUTES` 测试(GLOBAL 优先高价分配金额/明细、非 GLOBAL 报错、同时存在两种免费分钟);`PromotionEquivalentCalculator` 对 `SMART_FREE_MINUTES` 等效金额正确。

### 阶段 6:共享解析逻辑(决策 E)

- 抽 `resolveSegmentContext(request, segment, window, externalPool) → SegmentContext`
- `calculate` / `prepareContexts` 都调用,消除不同步
- `prepareContexts` 补 `calculationMode` + `externalPool`
- `externalPool` 分步重算语义:`prepareContexts` 一次、`calculateWithContexts` 多次重算时 pool 重置(spec 9.1 细化)
- `PromotionEquivalentCalculator` 对齐:baseline 与 `calculate` 实际金额一致

**验证**:`prepareContexts` 路径 `calculationMode` 正确(时长模式等效金额生效);`externalPool` 跨段共享在 `prepareContexts` 路径生效;`PromotionEquivalentCalculatorTest` 全绿,baseline = `calculate` 结果。

### 阶段 7:废弃旧模型

- 删 `AbstractTimeBasedRule`(职责拆解完毕:调度归 `BoundaryDrivenLoop`,工具归 `RuleSupport`,`CONTINUOUS` 基类职责归 `ContinuousStrategy`)
- 删 `SimplifiedUnitMeta`(简化单元改用 `ruleData`)
- 删 `ContinuousCalculator` 中间层(若阶段 2 未删)
- 检查无残留引用

**验证**:编译通过;无 `AbstractTimeBasedRule` / `SimplifiedUnitMeta` 引用;全测试绿。

### 阶段 8:文档同步

- spec:标记状态(按完成度更新为已实现)
- TODO → DONE:TODO-20260706-002 迁移
- `README` / `README_CN`:更新能力说明(`CalculationMode`、`SMART_FREE_MINUTES`)
- `USER_GUIDE`:更新模式说明、`SMART_FREE_MINUTES` 用法
- 能力文档(中英):更新模式矩阵、`SMART_FREE_MINUTES`、简化路径
- 流程文档:更新四层架构、迁移后流程
- `AGENTS.md`:更新架构(四层、`RuleSemantics`、`ModeStrategy`)、关键类列表

**验证**:文档与代码一致;无过时描述。

---

## 验证

- 每阶段:`mvn -pl bill-test -am test` 全绿
- 阶段 4 后:4 规则族时长模式冒烟测试
- 阶段 5 后:`SMART_FREE_MINUTES` 专项测试
- 阶段 6 后:`PromotionEquivalentCalculator` 对齐验证
- 阶段 7 后:无旧模型残留(grep 确认)

## 提交

每阶段一次提交(改动内聚、可独立验证):

1. `[claude-code|opus-4-8|superpowers] refactor: CalculationMode 合并替代双 enum(TODO-20260706-002 阶段1)`
2. `[claude-code|opus-4-8|superpowers] refactor: 3 规则族门面化(TODO-20260706-002 阶段2)`
3. `[claude-code|opus-4-8|superpowers] refactor: ContinuousStrategy 通用骨架 + 简化全局空隙(TODO-20260706-002 阶段3)`
4. `[claude-code|opus-4-8|superpowers] refactor: 拆 DurationPeriod/GlobalStrategy + DurationSupport(TODO-20260706-002 阶段4)`
5. `[claude-code|opus-4-8|superpowers] feat: SMART_FREE_MINUTES 优先高价分配(TODO-20260706-002 阶段5)`
6. `[claude-code|opus-4-8|superpowers] refactor: 共享 resolveSegmentContext(TODO-20260706-002 阶段6)`
7. `[claude-code|opus-4-8|superpowers] refactor: 废弃 AbstractTimeBasedRule 与旧切段模型(TODO-20260706-002 阶段7)`
8. `[claude-code|opus-4-8|superpowers] docs: 规则抽象重构文档同步(TODO-20260706-002 阶段8)`
9. `[claude-code|opus-4-8|superpowers] docs: TODO-20260706-002 规则抽象重构迁移 DONE`

trailer:`Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`

## 风险与回退

- **阶段 2(门面化)**:3 规则族同时改,改动面大。缓解:每规则族独立提交(2a/2b/2c),各自验证。
- **阶段 3(通用骨架)**:周期切换抽象不当导致金额偏差。缓解:用现有 parity 测试逐规则族验证;`isCycleBoundary` 语义明确(各规则族原有的周期切换判定)。
- **阶段 4(时长策略通用化)**:边界 provider 抽象遗漏。缓解:`DurationBillingModeTest` + 3 规则族冒烟;`RuleSemantics` 的 boundary provider 契约清晰。
- **阶段 5(SMART_FREE_MINUTES)**:同时存在两种免费分钟的分配顺序。缓解:`priority` 排序 + 专项测试;消费跳过已占用时段的边界条件。
- **阶段 6(externalPool 分步)**:状态重置语义。缓解:`PromotionEquivalentCalculatorTest` 逐场景验证;pool 重置点明确(prepareContexts 一次,calculateWithContexts 每次重算前重置)。
- **回退**:每阶段独立提交,失败可 `git revert` 单阶段。
