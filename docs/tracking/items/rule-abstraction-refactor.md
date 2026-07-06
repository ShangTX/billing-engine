# 计费引擎抽象重构:模式行为驱动 + 规则语义注入

---
id: TODO-20260706-002
type: refactor
priority: P1
status: done
source_git: dfaa576
created_at: 2026-07-06
completed_at: 2026-07-06
completed_git: 02162b6
---

## 背景

现有抽象存在 5 个结构性问题:

1. 4 份近乎拷贝的 `applyCapAndAccumulate`(ContinuousStrategy / RelativeTimeRule / NaturalTimeRule / CompositeTimeRule)
2. `AbstractTimeBasedRule` 身份混乱(基类 + 工具宿主 + 旧切段模型容器)
3. 两套切段模型并存(`splitTimeAxis`/`TimeFragment` vs `BoundaryDrivenLoop`/`HomogeneousSegment`)
4. DurationMode 实现锁在 dayNight(`DayNightDurationStrategy` ~90% 通用逻辑)
5. 模式分派散落(`BillingCalculator` 校验 / `DayNightRule` 分派 / 旧规则无分派点)

根源:正交维度选错——按"规则族 × 模式"组织实现,但规则族差异是参数,模式差异才是逻辑。当前 N×M 实现点,DurationMode 接入后将失控。

同时,模式枚举 `BillingMode` + `DurationMode` 互斥分派,合并能消除冗余;`prepareContexts` 漏解析 durationMode + externalPool,导致 `PromotionEquivalentCalculator` 等效金额在时长模式/外部优惠下错误。

详见 [2026-07-06 spec](../superpowers/specs/2026-07-06-rule-abstraction-refactor-design.md)。

## 目标

建立四层架构(规则语义 → 调度原语 → 模式策略 → 门面),实现模式行为与规则语义正交解耦:

- 规则族提供 `RuleSemantics`(周期/时段/单元/价格/封顶语义)
- 4 个 `ModeStrategy` 消费语义,各自实现模式逻辑
- 门面纯分派,按 `CalculationMode` 委托

收益:N+M 实现点(规则族提供语义 + 模式提供行为),消除 4 份重复,DurationMode 通用化,FREE_MINUTES 分配公共化,删旧切段模型。

## 范围

### 包含(8 阶段,见 spec 第 7 节)

- 阶段 0:GLOBAL 时段化修复(独立 TODO-20260706-001,先行)
- 阶段 1:`CalculationMode` 合并(决策 A)
- 阶段 2:3 规则族门面化(RelativeTime/NaturalTime/CompositeTime)
- 阶段 3:抽 `ContinuousStrategy` 通用骨架 + 简化全局空隙(决策 C)
- 阶段 4:抽 `DurationSupport` + 拆两个时长策略(决策 B)
- 阶段 5:`SMART_FREE_MINUTES`(决策 D)
- 阶段 6:共享解析逻辑 `resolveSegmentContext`(决策 E)
- 阶段 7:废弃旧模型(`AbstractTimeBasedRule` / `TimeFragment` / `splitTimeAxis` 等)
- 阶段 8:文档同步

### 不包含

- 新增计费规则类型(仅重构现有 4 类)
- 新增优惠类型(除 SMART_FREE_MINUTES 外)
- 对外 API 兼容(已确认不考虑,可放心大改)

## 验收标准

按阶段验收,每阶段测试通过且行为不变(除明确变更):

- 阶段 0:见 TODO-20260706-001
- 阶段 1:`CalculationMode` 替代双 enum,所有现有测试通过
- 阶段 2:3 规则族门面化,不继承 `AbstractTimeBasedRule`,现有测试通过
- 阶段 3:`applyCapAndAccumulate` 合并为 1 份,简化改全局空隙,现有测试通过
- 阶段 4:`DurationPeriodStrategy` / `DurationGlobalStrategy` 通用化,4 规则族声明 `supportedCalculationModes` 即支持时长模式
- 阶段 5:`SMART_FREE_MINUTES` 在 GLOBAL 下按优先高价分配,非 GLOBAL 报错
- 阶段 6:`prepareContexts` 与 `calculate` 解析一致,`PromotionEquivalentCalculator` 等效金额准确
- 阶段 7:旧模型删除,无残留引用
- 阶段 8:文档全部同步

## 相关文件

- spec:[2026-07-06-rule-abstraction-refactor-design.md](../superpowers/specs/2026-07-06-rule-abstraction-refactor-design.md)
- plan:[2026-07-06-rule-abstraction-refactor.md](../superpowers/plans/2026-07-06-rule-abstraction-refactor.md)
- 涉及文件:`core/src/main/java/cn/shang/charging/charge/rules/` 全部、`billing/BillingService.java`、`billing/BillingCalculator.java`、`billing/pojo/BConstants.java`、`billing-api/src/main/java/cn/shang/charging/wrapper/PromotionEquivalentCalculator.java` 等

## 备注

5 个设计决策(A-E)已与用户逐个确认,详见 spec 5.4-5.7:

- A:`CalculationMode` 合并,删旧 enum
- B:拆 `DurationPeriodStrategy` / `DurationGlobalStrategy` + 共享 `DurationSupport`
- C:保留简化,改全局空隙实现,删旧切段模型,ruleData 保时段明细
- D:`SMART_FREE_MINUTES` 类型,仅 GLOBAL 消费,规则侧按优先高价分配,非 GLOBAL 报错
- E:`prepareContexts` 补 calculationMode + externalPool,抽共享 `resolveSegmentContext`

阶段 0(GLOBAL 时段化修复)独立为 TODO-20260706-001,与本项并行先行。

## 进度

- ✅ 阶段 0:见 TODO-20260706-001
- ✅ 阶段 1:`CalculationMode` 合并(commit 1fac17d)
- ✅ 阶段 2:3 规则族门面化(commit 4104480 起)
- ✅ 阶段 3:`ContinuousStrategy` 通用 `applyCapAndAccumulate` + `RuleSemantics` + 简化全局空隙(commit 4104480 / 59c34e1)
- ✅ 阶段 4:`DurationSupport` + `DurationPeriodStrategy` / `DurationGlobalStrategy`(commit 8a4ec71)
- ✅ 阶段 5:`SMART_FREE_MINUTES` 优先高价分配(commit 657c79d)
- ✅ 阶段 6:共享 `resolveSegmentContext`(commit ad94bcf)
- ✅ 阶段 7:废弃 `AbstractTimeBasedRule` + `SimplifiedUnitMeta`(本提交)
  - `RuleSupport` 承载 `materializeFreeMinutes`(FREE_MINUTES 时段化)
  - `ContinuousStrategy` 承载 `isSimplificationEnabled` / `buildSimplifiedUnit` / `getCycleBoundary` / `computeIncompleteCharge` / `isIncompleteFree`
  - 4 个 ContinuousStrategy(DayNight/RelativeTime/NaturalTime/CompositeTime)改 `implements BillingRule`,删死代码(`hasComplexFeatures`/`isSimplifiedSupported`/`RuleState`/`initializeState`/`isSimplifiedUnit`/`extractSimplifiedUnitMeta`)
  - 简化单元改用 ruleData Map(键:`isSimplified`/`cycleIndex`/`simplifiedCycleCount`/`simplifiedCycleAmount`),`SimplifiedUnitMetaTest` 固化契约
  - 90 测试全绿
- ✅ 阶段 8:文档同步(本提交)
