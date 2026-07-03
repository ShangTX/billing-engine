# 计费规则门面策略结构重构

---
id: TODO-20260702-002
type: refactor
priority: P1
status: todo
source_git: 81ca938
created_at: 2026-07-02
completed_at:
completed_git:
---

## 背景

新设计（`docs/superpowers/specs/2026-07-02-duration-rule-and-promotion-two-tier-design.md` 3.1）确定计费规则采用"门面 + 策略"结构：每个规则族一个 `ChargeRuleType`、一个门面规则、一个共享 config，门面按模式分派到独立策略实现。

当前实现与新设计有三处方向性冲突（均源自已完成 TODO 的实现）：

1. **DayNightRule 臃肿**（TODO-20260630-003 已完成）：CONTINUOUS 和时长模式作为规则内模式分支并存于 DayNightRule（976 行），两条路径共享边界驱动循环但几乎不共享其他逻辑。`AbstractTimeBasedRule`（1023 行）背着 265 行时长产出基础设施，4 个 CONTINUOUS 规则中 3 个从不使用。

2. **UNIT_BASED 独立 type**（TODO-20260630-001 已完成）：`DayNightUnitBasedRule` 作为独立规则注册在 `DAY_NIGHT` type 下，与 `DayNightRule`（CONTINUOUS）共用 type、无法同时注册。新设计要求 UNIT_BASED 回归 `dayNight` 门面下策略（UnitBasedStrategy），一个 type 支持多模式，修复注册缺口。

3. **DurationMode 作为规则内模式**：`DayNightRule.calculate` 顶层 switch 分派 CONTINUOUS vs 时长。新设计要求时长模式作为门面下 DurationStrategy，PERIOD/GLOBAL 方法级分离。

## 目标

- `DayNightRule` 改为门面，按请求模式分派到独立策略，自身只分派不扛逻辑
- 四个策略独立实现：ContinuousStrategy / UnitBasedStrategy / DurationStrategy（内含 PERIOD/GLOBAL 两个 build 函数，方法级分离）
- 边界驱动循环（`runBoundaryDrivenLoop` + `BoundaryProviders` + `HomogeneousSegment`）从 `AbstractTimeBasedRule` 提取为独立工具，单元策略和时长策略共享，UNIT_BASED 策略不走
- `AbstractTimeBasedRule` 重新内聚为"CONTINUOUS 策略基类"，删掉 265 行时长产出基础设施（搬到时长策略）
- 两个模式维度对称：`supportedModes()`（CONTINUOUS/UNIT_BASED）+ `supportedDurationModes()`（PERIOD/GLOBAL）都保留，DurationMode 不降级
- 修复注册缺口：`dayNight` type 一个门面支持多模式，不需覆盖注册

## 范围

包含：

### 门面与策略

- `DayNightRule` 改门面：`supportedModes()={CONTINUOUS, UNIT_BASED}`，`supportedDurationModes()={PERIOD, GLOBAL}`，`calculate` 按请求模式分派
- 新增策略实现：
  - `ContinuousStrategy`（继承 `AbstractTimeBasedRule`，CONTINUOUS 路径）
  - `UnitBasedStrategy`（固定单元对齐 + 完整覆盖才免费，不走公共循环；由 `DayNightUnitBasedRule` 重构而来）
  - `DurationStrategy`（implements 策略接口，内含 `buildDurationSegmentsPeriodMode`/`buildDurationSegmentsGlobalMode` 两个 build 函数）
- 模式分派：DurationMode≠NONE 走时长策略，否则按 BillingMode 走单元策略

### 循环原语提取

- `runBoundaryDrivenLoop` + `BoundaryProviders` + `HomogeneousSegment` 提取为独立工具（如 `BoundaryDrivenLoop`）
- CONTINUOUS 策略和时长策略调用工具，不通过继承
- `AbstractTimeBasedRule` 删除时长产出基础设施（`PeriodResolver`/`DurationResult`/`buildDurationSegments*`/`segmentCharge`/`applyCycleCap`），搬至时长策略

### UNIT_BASED 回归门面

- `DayNightUnitBasedRule` 重构为 `UnitBasedStrategy`，不再是独立注册的规则，而是 `dayNight` 门面下的策略
- `BillingRuleRegistry` 一个 `dayNight` type 注册 `DayNightRule` 门面

### 接口对称

- `BillingRule` 保留 `supportedModes()` + `supportedDurationModes()` 两个接口
- `BillingCalculator` 校验两个维度
- `resolveDurationMode` 不并入 config，仍作为独立解析维度

不包含：

- 优惠两级模型（TODO-20260702-003）
- FREE_MINUTES 时段化下放（TODO-20260702-004）
- 其他规则族（relativeTime 等）的门面化（按需扩展，当前只 DayNight）
- GLOBAL_ORIGIN 窗口截取（TODO-20260702-001 止血，截取细节待下一阶段）

## 验收标准

- `DayNightRule` 是门面，`calculate` 只分派不扛计费逻辑
- 四个策略独立实现，CONTINUOUS/UNIT_BASED/PERIOD/GLOBAL 各自内聚
- 边界驱动循环为独立工具，`AbstractTimeBasedRule` 不再含时长产出基础设施
- `dayNight` type 一个注册项支持 CONTINUOUS/UNIT_BASED/时长，不需覆盖注册
- `supportedModes()` + `supportedDurationModes()` 两个接口对称保留
- 现有 CONTINUOUS/UNIT_BASED/时长测试通过，行为不变
- `DayNightUnitBasedRule` 不再独立注册，作为 `UnitBasedStrategy` 存在

## 相关文件

- `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java`（改门面）
- `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightUnitBasedRule.java`（重构为 UnitBasedStrategy）
- `core/src/main/java/cn/shang/charging/charge/rules/AbstractTimeBasedRule.java`（删时长基础设施，内聚为 CONTINUOUS 基类）
- `core/src/main/java/cn/shang/charging/charge/rules/BoundaryProvider.java` / `BoundaryProviders.java` / `HomogeneousSegment.java`（提取为工具）
- `core/src/main/java/cn/shang/charging/charge/rules/BillingRule.java`（两接口对称）
- `core/src/main/java/cn/shang/charging/billing/BillingCalculator.java`（两维度校验）
- `core/src/main/java/cn/shang/charging/charge/rules/BillingRuleRegistry.java`（dayNight 一个注册项）
- 新增策略类文件

## 备注

- 整合已完成 TODO-20260630-001（UNIT_BASED 独立规则）和 TODO-20260630-003（时长模式）的重构：两者实现按新设计重构为门面下策略
- 是 TODO-20260702-003（优惠两级模型）和 TODO-20260702-004（时段化下放）的基础——策略结构先立，优惠处理才能落到策略侧
- 优先级 P1：新设计实现的核心，其他演进依赖此结构
- 循环原语工具形态（静态工具 vs 独立类注入）是实现细节，见 spec §5 开放问题
