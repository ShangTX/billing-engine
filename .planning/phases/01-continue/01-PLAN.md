---
phase: 01-continue
plan: 01
type: execute
wave: 1
depends_on: []
files_modified:
- core/src/main/java/cn/shang/charging/billing/pojo/SegmentCarryOver.java
- core/src/main/java/cn/shang/charging/billing/pojo/BillingResult.java
- core/src/main/java/cn/shang/charging/billing/pojo/BillingUnit.java
- core/src/main/java/cn/shang/charging/billing/pojo/BillingContext.java
autonomous: true
requirements: [CONTINUE-01, CONTINUE-02, CONTINUE-03, CONTINUE-04, CONTINUE-05, CONTINUE-06, CONTINUE-07, CONTINUE-11]
user_setup: []
must_haves:
  truths:
    - "SegmentCarryOver contains lastTruncatedUnitStartTime field (LocalDateTime type)"
    - "isTruncated=false 时 lastTruncatedUnitStartTime is null"
    - "BillingResult contains firstUnitMerged field (Boolean type)"
    - "BillingUnit contains mergedFromPrevious field (Boolean type)"
    - "BillingContext contains previousTruncatedUnitStartTime field (LocalDateTime type)"
  artifacts:
    - path: "core/src/main/java/cn/shang/charging/billing/pojo/SegmentCarryOver.java"
      provides: "Truncated unit start time for CONTINUE mode"
      contains: "lastTruncatedUnitStartTime"
    - path: "core/src/main/java/cn/shang/charging/billing/pojo/BillingResult.java"
      provides: "Result with merge flag"
      contains: "firstUnitMerged"
    - path: "core/src/main/java/cn/shang/charging/billing/pojo/BillingUnit.java"
      provides: "Unit with merge marker"
      contains: "mergedFromPrevious"
    - path: "core/src/main/java/cn/shang/charging/billing/pojo/BillingContext.java"
      provides: "Context with previous truncated unit info"
      contains: "previousTruncatedUnitStartTime"
  key_links: []
---

<objective>
Extend data structures to support CONTINUE mode truncated unit merge.
Purpose: Provide necessary fields in SegmentCarryOver, BillingResult, BillingUnit, and BillingContext to enable truncated unit tracking and merge indication.
Output: Modified data structures with new fields for truncated unit handling.
</objective>

<execution_context>
@$HOME/.claude/get-shit-done/workflows/execute-plan.md
@$HOME/.claude/get-shit-done/templates/summary.md
</execution_context>

<context>
@.planning/PROJECT.md
@.planning/ROADMAP.md
@.planning/STATE.md
@.planning/phases/01-continue/01-CONTEXT.md
</context>

<interfaces>

<!-- Key types and contracts the executor needs. Extracted from codebase. -->
<!-- Executor should use these directly — no codebase exploration needed. -->

From core/src/main/java/cn/shang/charging/billing/pojo/SegmentCarryOver.java:
```java
public class SegmentCarryOver {
    private Map<String, Object> ruleState;    // 规则状态
    private PromotionCarryOver promotionState;  // 优惠结转状态
    // NEW: lastTruncatedUnitStartTime to be added
}
```

From core/src/main/java/cn/shang/charging/billing/pojo/BillingResult.java:
```java
public class BillingResult {
    private List<BillingUnit> units;
    private List<PromotionUsage> promotionUsages;
    private BigDecimal finalAmount;
    private LocalDateTime effectiveFrom;
    private LocalDateTime effectiveTo;
    private LocalDateTime calculationEndTime;
    private BillingCarryOver carryOver;
    // NEW: firstUnitMerged to be added
}
```

From core/src/main/java/cn/shang/charging/billing/pojo/BillingUnit.java:
```java
public class BillingUnit {
    private LocalDateTime beginTime;
    private LocalDateTime endTime;
    private int durationMinutes;
    private BigDecimal unitPrice;
    private BigDecimal originalAmount;
    private boolean free;
    private Boolean isTruncated;  // Already exists
    // NEW: mergedFromPrevious to be added
}
```
From core/src/main/java/cn/shang/charging/billing/pojo/BillingContext.java:
```java
public class BillingContext {
    private String id;
    private LocalDateTime beginTime;
    private LocalDateTime endTime;
    private BConstants.ContinueMode continueMode;
    private BConstants.BillingMode billingMode;
    private BillingSegment segment;
    private CalculationWindow window;
    private List<PromotionGrant> externalPromotions;
    private Map<String, Object> ruleState;
    private PromotionCarryOver promotionCarryOver;
    private List<PromotionRuleConfig> promotionRules;
    private RuleConfig chargingRule;
    private BillingConfigResolver billingConfigResolver;
    // NEW: previousTruncatedUnitStartTime to be added
}
```
</interfaces>

<tasks>

<task type="auto">
  <name>Task 1: Add lastTruncatedUnitStartTime to SegmentCarryOver</name>
  <files>core/src/main/java/cn/shang/charging/billing/pojo/SegmentCarryOver.java</files>
  <read_first>
  - core/src/main/java/cn/shang/charging/billing/pojo/SegmentCarryOver.java
  - .planning/phases/01-continue/01-CONTEXT.md
  </read_first>
  <action>
Add `lastTruncatedUnitStartTime` field (LocalDateTime type) to SegmentCarryOver.java.
The field must be properly documented with Javadoc comment explaining its purpose: CONTINUE mode truncated unit merge.

Per D-01, D-02, D-03 from CONTEXT.md:
Implementation steps:
1. Add field: `private LocalDateTime lastTruncatedUnitStartTime;`
2. Add Javadoc comment: "截断单元的开始时间，用于 CONTINUE 模式合并计算"
</action>
  <verify>
  <automated>grep -q "lastTruncatedUnitStartTime" core/src/main/java/cn/shang/charging/billing/pojo/SegmentCarryOver.java</automated>
  </verify>
  <acceptance_criteria>
  - SegmentCarryOver.java contains field `lastTruncatedUnitStartTime`
  - Field has LocalDateTime type
  - Javadoc comment describes purpose for CONTINUE mode
  </acceptance_criteria>
</task>

<task type="auto">
  <name>Task 2: Add firstUnitMerged to BillingResult and mergedFromPrevious to BillingUnit</name>
  <files>core/src/main/java/cn/shang/charging/billing/pojo/BillingResult.java, core/src/main/java/cn/shang/charging/billing/pojo/BillingUnit.java</files>
  <read_first>
  - core/src/main/java/cn/shang/charging/billing/pojo/BillingResult.java
  - core/src/main/java/cn/shang/charging/billing/pojo/BillingUnit.java
  - .planning/phases/01-continue/01-CONTEXT.md
  </read_first>
  <action>
Add merge-related fields to BillingResult and BillingUnit per D-04, D-06 from CONTEXT.md.

In BillingResult.java:
1. Add field: `private Boolean firstUnitMerged;`
2. Add Javadoc: "第一个单元是否为合并单元（从上一次截断单元合并）"

In BillingUnit.java:
1. Add field: `private Boolean mergedFromPrevious;`
2. Add Javadoc: "是否从上一次截断单元合并而来"
</action>
  <verify>
  <automated>grep -q "firstUnitMerged" core/src/main/java/cn/shang/charging/billing/pojo/BillingResult.java && grep -q "mergedFromPrevious" core/src/main/java/cn/shang/charging/billing/pojo/BillingUnit.java</automated>
  </verify>
  <acceptance_criteria>
  - BillingResult.java contains field `firstUnitMerged` (Boolean type)
  - BillingUnit.java contains field `mergedFromPrevious` (Boolean type)
  - Both fields have Javadoc comments describing merge purpose
  </acceptance_criteria>
</task>

<task type="auto">
  <name>Task 3: Add previousTruncatedUnitStartTime to BillingContext</name>
  <files>core/src/main/java/cn/shang/charging/billing/pojo/BillingContext.java</files>
  <read_first>
  - core/src/main/java/cn/shang/charging/billing/pojo/BillingContext.java
  - .planning/phases/01-continue/01-CONTEXT.md
  </read_first>
  <action>
Add `previousTruncatedUnitStartTime` field to BillingContext.java per D-11 from CONTEXT.md.
This field will be used by BillingService to pass the previous truncated unit start time from carryover to the calculation context.

Implementation steps:
1. Add field: `private LocalDateTime previousTruncatedUnitStartTime;`
2. Add Javadoc: "从 carryOver 恢复的截断单元开始时间，用于合并计算"
</action>
  <verify>
  <automated>grep -q "previousTruncatedUnitStartTime" core/src/main/java/cn/shang/charging/billing/pojo/BillingContext.java</automated>
  </verify>
  <acceptance_criteria>
  - BillingContext.java contains field `previousTruncatedUnitStartTime`
  - Field has LocalDateTime type
  - Javadoc comment describes its purpose for CONTINUE mode merge calculation
  </acceptance_criteria>
</task>

</tasks>

<verification>
Run `mv compile -pl core` and verify all modifications compile correctly without errors.
</verification>

<success_criteria>
- All four files modified with new fields
- All new fields have proper type (LocalDateTime or Boolean)
- All new fields have Javadoc documentation
- Build passes without errors
</success>

<output>
After completion, create `.planning/phases/01-continue/01-01-Summary.md`
</output>