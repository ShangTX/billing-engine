# Engine Boundary And Rule Complexity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 收敛 `billing-engine` 的核心职责边界，降低规则大类复杂度，并把 simplification / 查询层相关协议提升为更稳定的公共设计。

**Architecture:** 先用分析型与契约型测试把当前行为锁住，再把“公共契约”和“规则私有语义”拆开。优先处理 `billing-api` 直接理解 `ruleData` 的边界泄漏，再抽出 simplification 公共协议，最后为超大规则类设计内部职责拆分骨架。`bill-test` 的角色同时被重新界定，避免测试工具层反向影响核心设计。

**Tech Stack:** Java 21, Maven Toolchains, `core`, `billing-api`, `bill-test`, JUnit 5

---

## Execution Rule

每次修改前后都必须通过同一组工具型测试或样例对比计费结果：

- 修改前先记录结果。
- 修改后重跑同一组样例。
- 若语义不应变化，则结果必须一致。
- 若语义应变化，则必须在提交和文档中明确记录变化原因。

## File Structure

- `core/src/main/java/cn/shang/charging/billing/pojo/`
  - 放可提升为公共契约的元数据模型。
- `core/src/main/java/cn/shang/charging/charge/rules/AbstractTimeBasedRule.java`
  - simplification 公共逻辑的集中位置。
- `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java`
  - 规则私有逻辑拆分的第一参考对象。
- `core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeRule.java`
  - 简化周期、封顶与查询投影边界的第二参考对象。
- `core/src/main/java/cn/shang/charging/charge/rules/compositetime/CompositeTimeRule.java`
  - 多模式、多语义规则的第三参考对象。
- `billing-api/src/main/java/cn/shang/charging/wrapper/BillingTemplate.java`
  - 查询精确回退与 simplification 边界的主要入口。
- `billing-api/src/main/java/cn/shang/charging/wrapper/BillingResultViewer.java`
  - 只应消费公共契约，不应理解规则私有结构。
- `bill-test/src/main/java/cn/shang/charging/generator/`
  - 生成器和测试工具的定位收敛位置。

### Task 1: 固化当前边界问题的回归与分析样本

**Files:**
- Create: `bill-test/src/test/java/cn/shang/charging/EngineBoundarySmokeTest.java`
- Modify: `bill-test/src/test/java/cn/shang/charging/DayNightParkingParityTest.java`
- Modify: `bill-test/src/test/java/cn/shang/charging/RelativeTimeParkingParityTest.java`
- Test: `bill-test/src/test/java/cn/shang/charging/EngineBoundarySmokeTest.java`
- Test: `bill-test/src/test/java/cn/shang/charging/DayNightParkingParityTest.java`

- [ ] **Step 1: 写失败测试，固定当前边界问题和当前可接受语义**

```java
@Test
void simplifiedUnits_shouldBeExplicitlyDetectableWithoutRuleSpecificKeys() {
    Map<String, Object> ruleData = Map.of("isSimplified", true);
    assertFalse(ruleData.containsKey("isSimplified"),
            "当前实现仍依赖规则私有 key，测试应先失败以固定问题");
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -t C:/Users/shang/.m2/toolchains.xml -f billing/pom.xml -pl bill-test -am "-Dtest=EngineBoundarySmokeTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: FAIL with `当前实现仍依赖规则私有 key`

- [ ] **Step 3: 用最小测试代码记录当前状态**

```java
@Test
void billingTemplate_currentlyDetectsSimplifiedUnitsThroughRuleDataMap() {
    BillingUnit unit = BillingUnit.builder()
            .ruleData(Map.of("isSimplified", true))
            .build();
    assertTrue(EngineBoundaryInspector.isCurrentSimplifiedEncoding(unit));
}
```

- [ ] **Step 4: 运行样本测试**

Run: `mvn -t C:/Users/shang/.m2/toolchains.xml -f billing/pom.xml -pl bill-test -am "-Dtest=EngineBoundarySmokeTest,DayNightParkingParityTest,RelativeTimeParkingParityTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: parity 测试按当前结论通过；边界样本测试清晰表达当前实现状态

- [ ] **Step 5: Commit**

```bash
git add bill-test/src/test/java/cn/shang/charging
git commit -m "test: lock engine boundary baseline"
```

### Task 2: 提升 simplification 公共契约，收敛 `ruleData` 泄漏

**Files:**
- Create: `core/src/main/java/cn/shang/charging/billing/pojo/SimplifiedUnitMeta.java`
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/AbstractTimeBasedRule.java`
- Modify: `billing-api/src/main/java/cn/shang/charging/wrapper/BillingTemplate.java`
- Create: `bill-test/src/test/java/cn/shang/charging/SimplifiedUnitMetaTest.java`
- Test: `bill-test/src/test/java/cn/shang/charging/SimplifiedUnitMetaTest.java`

- [ ] **Step 1: 为新的公共模型写失败测试**

```java
@Test
void simplifiedUnitMeta_shouldRoundTripFromBillingUnitRuleData() {
    BillingUnit unit = BillingUnit.builder().ruleData(Map.of("isSimplified", true)).build();
    SimplifiedUnitMeta meta = SimplifiedUnitMeta.from(unit);
    assertNotNull(meta);
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -t C:/Users/shang/.m2/toolchains.xml -f billing/pom.xml -pl bill-test -am "-Dtest=SimplifiedUnitMetaTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: FAIL because `SimplifiedUnitMeta` 尚不存在

- [ ] **Step 3: 实现公共契约并让 `BillingTemplate` 改读该契约**

```java
public record SimplifiedUnitMeta(
        int cycleIndex,
        int simplifiedCycleCount,
        BigDecimal simplifiedCycleAmount,
        boolean simplified
) {
    public static SimplifiedUnitMeta from(BillingUnit unit) { ... }
}

private boolean isSimplifiedUnit(BillingUnit unit) {
    SimplifiedUnitMeta meta = SimplifiedUnitMeta.from(unit);
    return meta != null && meta.simplified();
}
```

- [ ] **Step 4: 运行测试验证**

Run: `mvn -t C:/Users/shang/.m2/toolchains.xml -f billing/pom.xml -pl bill-test -am "-Dtest=SimplifiedUnitMetaTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/cn/shang/charging/billing/pojo/SimplifiedUnitMeta.java core/src/main/java/cn/shang/charging/charge/rules/AbstractTimeBasedRule.java billing-api/src/main/java/cn/shang/charging/wrapper/BillingTemplate.java bill-test/src/test/java/cn/shang/charging/SimplifiedUnitMetaTest.java
git commit -m "refactor: extract simplification metadata contract"
```

### Task 3: 限制查询层只消费公共契约

**Files:**
- Modify: `billing-api/src/main/java/cn/shang/charging/wrapper/BillingResultViewer.java`
- Modify: `billing-api/src/main/java/cn/shang/charging/wrapper/BillingTemplate.java`
- Create: `bill-test/src/test/java/cn/shang/charging/BillingApiBoundaryTest.java`
- Test: `bill-test/src/test/java/cn/shang/charging/BillingApiBoundaryTest.java`

- [ ] **Step 1: 写失败测试，要求查询层不解析规则私有结构**

```java
@Test
void resultViewer_shouldWorkWithoutInspectingRuleSpecificRuleData() {
    BillingUnit unit = BillingUnit.builder()
            .chargedAmount(new BigDecimal("10.00"))
            .accumulatedAmount(new BigDecimal("10.00"))
            .valueSpec(new FixedValueSpec(new BigDecimal("10.00")))
            .ruleData(Map.of("vendorSpecific", "x"))
            .build();
    assertDoesNotThrow(() -> viewer.createQuerySummary(result, queryTime));
}
```

- [ ] **Step 2: 运行测试确认边界清晰**

Run: `mvn -t C:/Users/shang/.m2/toolchains.xml -f billing/pom.xml -pl bill-test -am "-Dtest=BillingApiBoundaryTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: PASS after移除对 `ruleData` 私有细节的额外依赖

- [ ] **Step 3: 收紧 `billing-api` 代码职责**

```java
// BillingResultViewer 不新增任何对 ruleData 的解析
// BillingTemplate 只通过 SimplifiedUnitMeta 这一公共契约判断是否需要精确重算
```

- [ ] **Step 4: 运行回归测试**

Run: `mvn -t C:/Users/shang/.m2/toolchains.xml -f billing/pom.xml -pl bill-test -am "-Dtest=BillingApiBoundaryTest,BillingResultViewerTest,DayNightQueryValueTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add billing-api/src/main/java/cn/shang/charging/wrapper bill-test/src/test/java/cn/shang/charging
git commit -m "refactor: narrow billing-api to public engine contracts"
```

### Task 4: 先按计费模式拆分超大规则类

**Files:**
- Create: `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightContinuousCalculator.java`
- Create: `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightUnitBasedCalculator.java`
- Create: `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightValueSpecFactory.java`
- Modify: `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java`
- Create: `docs/tracking/items/daynight-rule-split-followup.md`
- Test: `bill-test/src/test/java/cn/shang/charging/DayNightParkingParityTest.java`
- Test: `bill-test/src/test/java/cn/shang/charging/DayNightQueryValueTest.java`

- [ ] **Step 1: 先写失败测试，保证拆分不改行为**

```java
@Test
void dayNightSplit_shouldPreserveParkingParityCase() {
    BillingResult result = createService(config).calculate(request);
    assertEquals(new BigDecimal("69.50"), result.getFinalAmount());
}
```

- [ ] **Step 2: 运行测试确认当前基线**

Run: `mvn -t C:/Users/shang/.m2/toolchains.xml -f billing/pom.xml -pl bill-test -am "-Dtest=DayNightParkingParityTest,DayNightQueryValueTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: PASS

- [ ] **Step 3: 只抽职责，不改语义**

```java
public final class DayNightContinuousCalculator { ... }
public final class DayNightUnitBasedCalculator { ... }
public final class DayNightValueSpecFactory { ... }

public class DayNightRule extends AbstractTimeBasedRule<DayNightConfig> {
    @Override
    public BillingSegmentResult calculate(...) {
        return context.getBillingMode() == BConstants.BillingMode.CONTINUOUS
                ? continuousCalculator.calculate(...)
                : unitBasedCalculator.calculate(...);
    }
}
```

- [ ] **Step 3.1: 先把模式边界固定下来**

```java
// Rule 只做模式分发和公共汇总，不直接内嵌两套模式的大段实现。
public class DayNightRule extends AbstractTimeBasedRule<DayNightConfig> {
    private final DayNightContinuousCalculator continuousCalculator = new DayNightContinuousCalculator();
    private final DayNightUnitBasedCalculator unitBasedCalculator = new DayNightUnitBasedCalculator();
}
```

Expected:
- `CONTINUOUS` 的时间轴切分、查询投影、条件免费、截断单元逻辑进入 `DayNightContinuousCalculator`
- `UNIT_BASED` 的固定单元切分、周期封顶、截断恢复逻辑进入 `DayNightUnitBasedCalculator`
- 原 `DayNightRule` 保留公共入口、模式选择、状态汇总

- [ ] **Step 3.2: 约定 `DayNightRule` 的第二层目标结构**

Target structure:

```java
DayNightRule
├── DayNightContinuousCalculator
├── DayNightUnitBasedCalculator
├── DayNightPriceResolver
├── DayNightValueSpecFactory
└── DayNightCycleStateManager
```

Rules:
- `DayNightRule` 只保留规则元信息、配置校验、模式分发和结果装配
- `DayNightContinuousCalculator` 负责连续时间轴切分与连续模式封顶
- `DayNightUnitBasedCalculator` 负责固定单元切分与单元模式封顶
- `DayNightPriceResolver` 负责 `DAY/NIGHT/MIXED` 判定与跨边界单元价格计算
- `DayNightValueSpecFactory` 负责 `FixedValueSpec` / `StepValueSpec` / `MixedUnitValueSpec` / `CappedValueSpec`
- `DayNightCycleStateManager` 负责周期边界、累计金额、封顶状态与 `RuleState` 恢复/输出

Non-goals:
- 此阶段不把 `DayNight` 的私有逻辑过早抽成跨规则通用组件
- 此阶段不要求 `RelativeTimeRule` / `CompositeTimeRule` 同步一起重构

- [ ] **Step 4: 运行回归测试**

Run: `mvn -t C:/Users/shang/.m2/toolchains.xml -f billing/pom.xml -pl bill-test -am "-Dtest=DayNightParkingParityTest,DayNightQueryValueTest,BillingResultViewerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/cn/shang/charging/charge/rules/daynight docs/tracking/items/daynight-rule-split-followup.md
git commit -m "refactor: split dayNight rule responsibilities"
```

### Task 5: 收敛 `bill-test` 的工具层定位

**Files:**
- Modify: `bill-test/src/main/java/cn/shang/charging/generator/BillingTestCaseGenerator.java`
- Modify: `bill-test/src/main/java/cn/shang/charging/generator/BillingTestCaseGeneratorRunner.java`
- Modify: `docs/USER_GUIDE.md`
- Modify: `docs/billing-engine-capabilities-zh.md`
- Test: `bill-test/src/test/java/cn/shang/charging/generator/BillingTestCaseGeneratorTest.java`

- [ ] **Step 1: 写失败测试，固定生成器“只输出结果样本”的定位**

```java
@Test
void generator_shouldProduceSamplesWithoutEmbeddingExpectedAmounts() {
    List<GeneratedBillingCase> cases = generator.generate(request);
    assertNull(cases.getFirst().getExpectedAmount());
}
```

- [ ] **Step 2: 运行测试确认定位**

Run: `mvn -t C:/Users/shang/.m2/toolchains.xml -f billing/pom.xml -pl bill-test -am "-Dtest=BillingTestCaseGeneratorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: PASS

- [ ] **Step 3: 收敛生成器和 Runner 的说明**

```java
/**
 * 样本生成器仅生成输入与结果，供人工校验或外部比对使用，
 * 不承载引擎正式能力判断。
 */
```

- [ ] **Step 4: 更新文档并回归**

Run: `mvn -t C:/Users/shang/.m2/toolchains.xml -f billing/pom.xml -pl bill-test -am "-Dtest=BillingTestCaseGeneratorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add bill-test/src/main/java/cn/shang/charging/generator docs/USER_GUIDE.md docs/billing-engine-capabilities-zh.md
git commit -m "docs: clarify bill-test tool responsibilities"
```

## Current Midpoint Conclusions

- `DayNightRule` 已完成模式拆分，并进一步拆出价格解析、`valueSpec` 工厂与周期状态管理部件。
- `RelativeTimeRule` 已完成模式拆分，并进一步拆出 period 定位器与连续模式封顶处理器。
- 这一轮重构已经验证：先按模式拆，再按规则内部稳定职责拆，是当前最稳的路径。
- 当前不建议直接为所有规则预设统一重构框架；应在 `DayNightRule`、`RelativeTimeRule`、`CompositeTimeRule` 各自拆清楚后，再统一分析可抽取的公共逻辑。
