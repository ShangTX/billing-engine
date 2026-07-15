# 优化自定义规则扩展体验

---
id: TODO-20260623-001
type: feature
priority: P2
status: todo
source_git: 4e081b3
created_at: 2026-06-23
completed_at:
completed_git:
---

## 背景

本事项仍然有效，但原始描述基于 2026-06 的旧规则架构，已经不再适合作为实现依据。

原描述关注 `AbstractTimeBasedRule`、`RuleState`、`CONTINUE`、`splitTimeAxis`、`TimeFragment`
等旧模型。后续已完成的规则抽象重构（TODO-20260706-002）已经将这些基础替换为当前四层架构：

```text
RuleSemantics（规则族语义）
→ BoundaryDrivenLoop（边界驱动调度原语）
→ ModeStrategy（CONTINUOUS / UNIT_BASED / DURATION_PERIOD / DURATION_GLOBAL）
→ BillingRule 门面（按 CalculationMode 分派）
```

因此，本事项不再是“整理 `AbstractTimeBasedRule` 模板方法”，而是要回答当前调用方真正会遇到的问题：

> 在现有四层架构下，外部开发者如何用最少样板、最少踩坑，实现并接入一个生产可用的自定义计费规则？

## 当前现状

### 已解决的问题

- `AbstractTimeBasedRule` 已废弃/移除，旧继承模型不再是扩展入口。
- `CONTINUE` 续算模式已移除，`RuleState`、`previousAccumulatedAmount`、`PromotionCarryOver` 等旧关注点不再需要自定义规则处理。
- `BoundaryDrivenLoop`、`BoundaryProvider`、`BoundaryProviders`、`HomogeneousSegment` 已成为独立调度原语。
- `ContinuousStrategy` 承载 CONTINUOUS 共享的封顶、累计、compact、不足单元和简化相关能力。
- `DurationPeriodStrategy` / `DurationGlobalStrategy` 与 `DurationSupport` 已能通过 `RuleSemantics` 复用时长模式公共逻辑。
- `RuleSupport` 承载策略侧 FREE_MINUTES 时段化、条件生效过滤等公共能力。
- `BillingRule.supportedCalculationModes()` 已成为模式兼容声明入口。

### 仍存在的问题

1. **扩展路径不清晰**
   - 本阶段已先补充路径 A：直接实现 `BillingRule`，并在示例中复用 `BoundaryDrivenLoop`。
   - 后续如果要推进路径 B，仍需说明什么时候应实现 `RuleSemantics` 并复用公共策略。
   - 后续仍需解释 `DURATION_PERIOD`、`DURATION_GLOBAL` 两类时长规则分别需要补哪些语义。

2. **公共抽象缺少面向外部作者的契约说明**
   - `RuleSemantics` 虽然是 public 接口，但当前主要服务内置规则，缺少外部实现指南。
   - `BoundaryProvider` 需要满足“只返回 current 之后最近边界、不携带计费状态、不产生副作用”等约束，但文档未集中说明。
   - `HomogeneousSegment` 的字段语义、免费段判定、`ruleData` 使用边界、compact 合并条件缺少规则作者视角说明。

3. **Spring Boot 自定义注册体验偏弱**
   - 手动 Java 组装可以直接调用 `BillingRuleRegistry.register(...)`。
   - Starter 场景下，自定义规则通常需要覆盖整个 `BillingRuleRegistry` bean 或自行拿到 registry 后注册，缺少更轻量的 customizer 入口。
   - 本阶段先不新增 starter 扩展 API，仅在指南中说明当前可用注册方式。

4. **缺少可运行示例和验证基线**
   - 本阶段已新增 `docs/guides/custom-rule-guide.md`，提供路径 A 文档示例。
   - 测试中缺少“外部自定义规则”的最小示例。
   - 没有 `BillingRuleTestBase` 或规则作者验证清单来覆盖总额、明细、免费时段、免费分钟、封顶、compact、时长模式、等效金额重算等关键契约。

5. **内置规则仍有可提炼的样板**
   - 各门面规则已较薄，但 `validateConfig`、模式分派、时长模式调用公共策略、CONTINUOUS 段构造仍有重复样板。
   - 是否抽取更高层的规则族骨架需要谨慎评估，避免重新引入“继承大基类”的旧问题。

## 目标

1. 明确当前架构下自定义规则的推荐扩展路径和边界。
2. 为规则作者提供从最小规则到语义驱动时间规则的渐进式指南。
3. 降低 Spring Boot 场景下注册自定义计费规则的样板成本。
4. 提供规则实现验证清单或轻量测试基线，帮助外部规则保持纯计算、可追溯和模式兼容。
5. 在不改变现有规则行为的前提下，评估是否需要小型公共辅助 API，而不是重新引入重型继承基类。

## 推荐扩展路径

### 路径 A：极简规则，直接实现 `BillingRule`

适用场景：

- 规则不需要周期、封顶、免费分钟、时长模式等公共能力。
- 规则只产出一个或少量 `BillingUnit`。
- 示例：整段免费、整段固定价、业务侧已经算好金额的适配规则。

需要说明：

- `RuleConfig#getType()` 必须与 registry 注册的 ruleType 一致。
- `configClass()` 必须匹配实际配置类型。
- `supportedCalculationModes()` 只声明自己真实支持的模式。
- `calculate(...)` 必须是纯计算，不能访问数据库、缓存、远程服务或全局状态。

### 路径 B：时间计费规则，复用语义驱动公共策略

适用场景：

- 规则有周期、时段、单元、单价、封顶等时间语义。
- 希望复用 `DURATION_PERIOD` / `DURATION_GLOBAL` 的公共时长逻辑。
- CONTINUOUS 模式可用 `BoundaryDrivenLoop` 切同质段，再交给 `ContinuousStrategy.applyCapAndAccumulate(...)`。

推荐结构：

```text
MyRuleConfig implements RuleConfig
MyRuleSemantics implements RuleSemantics<MyRuleConfig>
MyContinuousStrategy implements BillingRule<MyRuleConfig>（如需要 CONTINUOUS）
MyRule implements BillingRule<MyRuleConfig>（门面，按 CalculationMode 分派）
```

需要说明：

- `RuleSemantics#cycleOrigin`、`initialCycleBoundary`、`isCycleBoundary`、`nextCycleBoundary` 决定周期切换。
- `unitMinutes`、`priceAt`、`periodBoundaryProvider` 决定同质段与价格。
- `cycleCap`、`periodCap`、`periodKey`、`periodLabel` 决定封顶与结果标签。
- `BoundaryProvider` 必须无副作用，且只返回 `(current, calcEnd]` 中最近的一个边界。
- 免费分钟应通过 `RuleSupport.materializeFreeMinutes(...)` 或时长模式对应方法处理。

### 路径 C：特殊规则或新模式，独立实现策略

适用场景：

- 规则不是时间计费，例如未来 `times` 次数计费。
- 规则使用固定单元对齐且完整覆盖才免费，类似 `UNIT_BASED`。
- 规则语义无法被 `RuleSemantics` 表达，或复用公共策略反而更复杂。

需要说明：

- 可以直接实现 `BillingRule`，但必须自行保证结果结构、优惠 usage、累计金额和模式兼容。
- 不应为了复用而把规则私有语义泄漏到通用查询层或核心管道。

## 范围

包含：

- 更新 `docs/USER_GUIDE.md` 第 16 章，说明当前推荐扩展路径。
- 新增 `docs/guides/custom-rule-guide.md`，提供渐进式自定义规则指南。
- 增加一个最小自定义规则示例，验证 registry、模式校验、配置类型校验和基本计费结果应如何覆盖。
- 记录 Spring Boot starter 当前自定义注册方式，但不新增 registry customizer。
- 整理规则作者验证清单，覆盖纯计算、无副作用、模式支持、优惠交互、封顶、compact、时长模式、等效金额重算。

不包含：

- 新增业务计费规则能力。
- 改变现有内置规则对外行为。
- 恢复或重建 `AbstractTimeBasedRule` 继承体系。
- 恢复 `CONTINUE` 续算模式或 `RuleState`。
- 引入会强制所有规则继承的大型模板基类。
- 停车业务专属规则实现。

## 可能的实现方案

### 方案 1：文档 + 示例优先

先补足指南和示例测试，不新增公共 API。

优点：

- 风险低，不影响现有行为。
- 能立即解决“怎么写”的认知成本。

缺点：

- Spring Boot 注册仍需用户手动处理 registry。
- 不能减少代码样板，只减少理解成本。

### 方案 2：增加轻量 registry customizer

在 v3/v4 starter 中新增类似：

```java
@FunctionalInterface
public interface BillingRuleRegistryCustomizer {
    void customize(BillingRuleRegistry registry);
}
```

自动配置创建 `BillingRuleRegistry` 后依次调用所有 customizer。

优点：

- 保持 core 不变。
- 不要求自定义规则实现新增 `ruleType()`。
- Spring Boot 用户可以用一个 bean 注册自定义规则，不需要覆盖整个 registry。

缺点：

- starter 需要同步修改 v3/v4 两个模块。
- 仍需用户显式写 `registry.register("myRule", new MyRule())`。

### 方案 3：自动收集规则 bean

Starter 注入所有 `BillingRule<?>` bean，并要求通过额外接口或注解声明 ruleType。

优点：

- 使用体验最自动化。

缺点：

- 当前 `BillingRule` 不包含 `ruleType()`，需要新接口或注解。
- 容易引入 type 声明重复、覆盖顺序等问题。
- 对现有 API 侵入更大，暂不推荐作为第一步。

## 本阶段决策

采用“路径 A + 方案 1”：

1. 先用文档和示例明确直接实现 `BillingRule` 的扩展方式。
2. 示例规则可以复用 `BoundaryDrivenLoop`，但不引入 `RuleSemantics` 路径。
3. 暂不改变 `BillingRule` 接口，不新增 starter customizer，不引入新的重型基类或自动扫描规则注解。
4. 若后续确认 Spring Boot 注册样板仍是痛点，再单独评估 `BillingRuleRegistryCustomizer`。

## 验收标准

- `docs/USER_GUIDE.md` 第 16 章改为当前四层架构下的自定义规则说明。
- 新增 `docs/guides/custom-rule-guide.md`，至少包含：
  - 极简规则示例。
  - 基于 `RuleSemantics` 的时间规则实现骨架。
  - 手动 Java 注册方式。
  - Spring Boot 注册方式。
  - 规则作者验证清单。
- 至少新增一个测试或示例，展示自定义 `BillingRule` 可被注册并参与计费。
- 本阶段不新增 starter customizer；指南需说明当前 Spring Boot 注册方式和限制。
- 现有测试通过，内置规则行为不变。

## 相关文件

- `core/src/main/java/cn/shang/charging/charge/rules/BillingRule.java`
- `core/src/main/java/cn/shang/charging/charge/rules/BillingRuleRegistry.java`
- `core/src/main/java/cn/shang/charging/charge/rules/RuleSemantics.java`
- `core/src/main/java/cn/shang/charging/charge/rules/BoundaryDrivenLoop.java`
- `core/src/main/java/cn/shang/charging/charge/rules/BoundaryProvider.java`
- `core/src/main/java/cn/shang/charging/charge/rules/BoundaryProviders.java`
- `core/src/main/java/cn/shang/charging/charge/rules/ContinuousStrategy.java`
- `core/src/main/java/cn/shang/charging/charge/rules/DurationPeriodStrategy.java`
- `core/src/main/java/cn/shang/charging/charge/rules/DurationGlobalStrategy.java`
- `core/src/main/java/cn/shang/charging/charge/rules/DurationSupport.java`
- `core/src/main/java/cn/shang/charging/charge/rules/RuleSupport.java`
- `billing-v3-spring-boot-starter/src/main/java/cn/shang/charging/spring/boot/autoconfigure/BillingAutoConfiguration.java`
- `billing-v4-spring-boot-starter/src/main/java/cn/shang/charging/spring/boot/autoconfigure/BillingAutoConfiguration.java`
- `docs/USER_GUIDE.md`
- `docs/guides/custom-rule-guide.md`
- `bill-test/src/main/java/cn/shang/charging/examples/ProgressiveDailyCapRule.java`
- `bill-test/src/test/java/`

## 备注

- 本事项承接 TODO-20260514-007 和 TODO-20260706-002：前者识别规则复杂度问题，后者完成架构重构，本事项聚焦“外部规则作者如何使用新架构”。
- 后续实现前仍需检查 `docs/TODO.md`，避免与其他扩展体验或 starter 改动事项重复。
- 若在实现中发现需要更大 API 变更，应先补充 `docs/superpowers/specs/` 设计文档和 `docs/superpowers/plans/` 实施计划。

## 进展

- 2026-07-15：按用户确认采用路径 A + 方案 1；新增 `docs/guides/custom-rule-guide.md`，用 `peakOffPeak` 示例展示直接实现 `BillingRule` 并复用 `BoundaryDrivenLoop`；更新 `docs/USER_GUIDE.md` 第 16 章作为入口。不新增公共 API，不实现 starter customizer。
- 2026-07-15：补充可编译的独立 Java 示例 `bill-test/src/main/java/cn/shang/charging/examples/PeakOffPeakRule.java`，将配置作为嵌套 `Config` 放在同一文件，并用中文注释说明规则逻辑、边界切分和结果组装。
- 2026-07-15：补充更有代表性的非线性自然日累计封顶示例 `ProgressiveDailyCapRule`：每小时 5 元、自然日周期、增量封顶数组 `[35, 10, 15, 20]`，数组最后一项复用于后续自然日；不使用单元边界，分别支持 `CONTINUOUS`、`DURATION_PERIOD`、`DURATION_GLOBAL` 三种模式。
