---
phase: 01-continue
plan: 02
type: execute
wave: 2
depends_on: [01]
files_modified:
- core/src/main/java/cn/shang/charging/settlement/ResultAssembler.java
- core/src/main/java/cn/shang/charging/billing/BillingService.java
- core/src/main/java/cn/shang/charging/charge/rules/AbstractTimeBasedRule.java
autonomous: true
requirements: [CONTINUE-05, CONTINUE-08, CONTINUE-09, CONTINUE-10, CONTINUE-12, CONTINUE-13, CONTINUE-14, CONTINUE-15]
user_setup: []
must_haves:
  truths:
    - "ResultAssembler.extractLastTruncatedUnitStartTime() correctly extracts from BillingUnit list"
    - "ResultAssembler.buildBillingCarryOver() sets lastTruncatedUnitStartTime in SegmentCarryOver"
    - "BillingService.calculate() extracts previousTruncatedUnitStartTime from carryOver"
    - "BillingService.calculate() passes previousTruncatedUnitStartTime to BillingContext"
    - "AbstractTimeBasedRule adjusts first unit begin time when previousTruncatedUnitStartTime exists"
    - "AbstractTimeBasedRule sets mergedFromPrevious=true on merged first unit"
  artifacts:
    - path: "core/src/main/java/cn/shang/charging/settlement/ResultAssembler.java"
      provides: "Extracts truncated unit info and sets in carryOver"
      contains: "extractLastTruncatedUnitStartTime"
    - path: "core/src/main/java/cn/shang/charging/billing/BillingService.java"
      provides: "Passes previous truncated unit info to context"
      contains: "previousTruncatedUnitStartTime"
    - path: "core/src/main/java/cn/shang/charging/charge/rules/AbstractTimeBasedRule.java"
      provides: "Merges truncated unit with first unit"
      contains: "mergedFromPrevious"
  key_links: []
---

<objective>
Implement business logic for CONTINUE mode truncated unit merge.
Purpose: Extract truncated unit info, pass it through context, and merge in rule calculation.
Output: Working merge logic that prevents duplicate billing.
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

From core/src/main/java/cn/shang/charging/settlement/ResultAssembler.java:
```java
public class ResultAssembler {
    public BillingResult assemble(BillingRequest request, List<BillingSegmentResult> segmentResultList);
    private BillingCarryOver buildBillingCarryOver(List<BillingSegmentResult> segmentResultList, LocalDateTime calculationEndTime);
    // NEW: extractLastTruncatedUnitStartTime() to be added
}
```

From core/src/main/java/cn/shang/charging/billing/BillingService.java:
```java
public class BillingService {
    public BillingResult calculate(BillingRequest request) {
        // Around line 105-114: CONTINUE mode state restoration
        // Need to extract previousTruncatedUnitStartTime from SegmentCarryOver
        // Need to pass to BillingContext.builder()
    }
}
```

From core/src/main/java/cn/shang/charging/charge/rules/AbstractTimeBasedRule.java:
```java
public abstract class AbstractTimeBasedRule<C extends RuleConfig> implements BillingRule<C> {
    @Override
    public BillingSegmentResult calculate(BillingContext context, C config, PromotionAggregate promotionAggregate) {
        // Need to check context.getPreviousTruncatedUnitStartTime()
        // If exists, adjust first unit begin time and set mergedFromPrevious=true
    }
}
```

</interfaces>

<tasks>

<task type="auto">
  <name>Task 1: Add extractLastTruncatedUnitStartTime to ResultAssembler</name>
  <files>core/src/main/java/cn/shang/charging/settlement/ResultAssembler.java</files>
  <read_first>
  - core/src/main/java/cn/shang/charging/settlement/ResultAssembler.java
  - core/src/main/java/cn/shang/charging/billing/pojo/BillingUnit.java
  - core/src/main/java/cn/shang/charging/billing/pojo/SegmentCarryOver.java
  - .planning/phases/01-continue/01-CONTEXT.md
  </read_first>
  <action>
Add `extractLastTruncatedUnitStartTime` method to ResultAssembler.java per CONTINUE-08.

Implementation:
1. Add private method:
```java
/**
 * 提取最后一个截断单元的开始时间
 * 如果最后一个单元 isTruncated=true，返回其 beginTime
 * 否则返回 null
 */
private LocalDateTime extractLastTruncatedUnitStartTime(List<BillingUnit> billingUnits) {
    if (billingUnits == null || billingUnits.isEmpty()) {
        return null;
    }
    BillingUnit lastUnit = billingUnits.get(billingUnits.size() - 1);
    if (lastUnit.getIsTruncated() != null && lastUnit.getIsTruncated()) {
        return lastUnit.getBeginTime();
    }
    return null;
}
```

2. In buildBillingCarryOver(), after building SegmentCarryOver, add:
```java
LocalDateTime lastTruncatedUnitStartTime = extractLastTruncatedUnitStartTime(result.getBillingUnits());
segmentCarryOver.setLastTruncatedUnitStartTime(lastTruncatedUnitStartTime);
```
Or use builder pattern if SegmentCarryOver uses builder.
  </action>
  <verify>
  <automated>grep -q "extractLastTruncatedUnitStartTime" core/src/main/java/cn/shang/charging/settlement/ResultAssembler.java</automated>
  </verify>
  <acceptance_criteria>
  - ResultAssembler.java contains method `extractLastTruncatedUnitStartTime`
  - Method returns LocalDateTime
  - Method correctly checks last unit's isTruncated flag
  - buildBillingCarryOver calls this method and sets SegmentCarryOver.lastTruncatedUnitStartTime
  </acceptance_criteria>
</task>

<task type="auto">
  <name>Task 2: Update BillingService to pass previousTruncatedUnitStartTime</name>
  <files>core/src/main/java/cn/shang/charging/billing/BillingService.java</files>
  <read_first>
  - core/src/main/java/cn/shang/charging/billing/BillingService.java
  - core/src/main/java/cn/shang/charging/billing/pojo/SegmentCarryOver.java
  - core/src/main/java/cn/shang/charging/billing/pojo/BillingContext.java
  - .planning/phases/01-continue/01-CONTEXT.md
  </read_first>
  <action>
Update BillingService.calculate() to extract and pass previousTruncatedUnitStartTime per CONTINUE-10, CONTINUE-12.

Implementation location: Around lines 105-131 in calculate() method.

1. Add variable declaration after line 107 (after promotionCarryOver declaration):
```java
LocalDateTime previousTruncatedUnitStartTime = null;
```

2. Inside the `if (segmentCarryOver != null)` block, add:
```java
previousTruncatedUnitStartTime = segmentCarryOver.getLastTruncatedUnitStartTime();
```

3. In BillingContext.builder() chain, add:
```java
.previousTruncatedUnitStartTime(previousTruncatedUnitStartTime)
```
  </action>
  <verify>
  <automated>grep -q "previousTruncatedUnitStartTime" core/src/main/java/cn/shang/charging/billing/BillingService.java</automated>
  </verify>
  <acceptance_criteria>
  - BillingService.calculate() declares `LocalDateTime previousTruncatedUnitStartTime = null`
  - SegmentCarryOver extraction block calls `getLastTruncatedUnitStartTime()`
  - BillingContext.builder() includes `.previousTruncatedUnitStartTime(previousTruncatedUnitStartTime)`
  </acceptance_criteria>
</task>

<task type="auto">
  <name>Task 3: Update AbstractTimeBasedRule to handle truncated unit merge</name>
  <files>core/src/main/java/cn/shang/charging/charge/rules/AbstractTimeBasedRule.java</files>
  <read_first>
  - core/src/main/java/cn/shang/charging/charge/rules/AbstractTimeBasedRule.java
  - core/src/main/java/cn/shang/charging/billing/pojo/BillingContext.java
  - core/src/main/java/cn/shang/charging/billing/pojo/BillingUnit.java
  - .planning/phases/01-continue/01-CONTEXT.md
  </read_first>
  <action>
Update AbstractTimeBasedRule.calculate() to handle truncated unit merge per CONTINUE-13, CONTINUE-14, CONTINUE-15.

This is the core merge logic. The implementation depends on how units are generated in this rule.

Key changes:
1. At the beginning of calculate(), get previousTruncatedUnitStartTime:
```java
LocalDateTime previousTruncatedStart = context.getPreviousTruncatedUnitStartTime();
```

2. If previousTruncatedStart != null, adjust the first unit's beginTime:
   - The first unit should start from previousTruncatedStart instead of window.getCalculationBegin()
   - This effectively merges the truncated portion with the new portion

3. After creating the first BillingUnit, if merge happened:
```java
firstUnit.setMergedFromPrevious(true);
```

4. Also need to adjust the calculation to NOT charge for the truncated portion again (it was already charged in the previous calculation).

IMPORTANT: The exact implementation depends on how AbstractTimeBasedRule generates units. Read the file carefully to understand the unit generation logic before modifying.
  </action>
  <verify>
  <automated>grep -q "previousTruncatedUnitStartTime\|mergedFromPrevious" core/src/main/java/cn/shang/charging/charge/rules/AbstractTimeBasedRule.java</automated>
  </verify>
  <acceptance_criteria>
  - AbstractTimeBasedRule.calculate() reads context.getPreviousTruncatedUnitStartTime()
  - When previousTruncatedStart exists, first unit begin time is adjusted
  - First unit gets mergedFromPrevious=true when merged
  - First unit does NOT double-charge the truncated portion
  </acceptance_criteria>
</task>

<task type="auto">
  <name>Task 4: Set firstUnitMerged in ResultAssembler</name>
  <files>core/src/main/java/cn/shang/charging/settlement/ResultAssembler.java</files>
  <read_first>
  - core/src/main/java/cn/shang/charging/settlement/ResultAssembler.java
  - core/src/main/java/cn/shang/charging/billing/pojo/BillingResult.java
  - core/src/main/java/cn/shang/charging/billing/pojo/BillingUnit.java
  - .planning/phases/01-continue/01-CONTEXT.md
  </read_first>
  <action>
Update ResultAssembler.assemble() to detect and set firstUnitMerged flag per CONTINUE-05.

Implementation:
1. In assemble() method, after building allUnits, check first unit:
```java
Boolean firstUnitMerged = detectFirstUnitMerged(segmentResultList);
```

2. Add helper method:
```java
/**
 * 检查第一个单元是否是合并单元
 */
private Boolean detectFirstUnitMerged(List<BillingSegmentResult> segmentResultList) {
    if (segmentResultList == null || segmentResultList.isEmpty()) {
        return null;
    }
    BillingSegmentResult firstSegment = segmentResultList.get(0);
    List<BillingUnit> units = firstSegment.getBillingUnits();
    if (units == null || units.isEmpty()) {
        return null;
    }
    return units.get(0).getMergedFromPrevious();
}
```

3. In BillingResult.builder(), add:
```java
.firstUnitMerged(firstUnitMerged)
```
  </action>
  <verify>
  <automated>grep -q "firstUnitMerged\|detectFirstUnitMerged" core/src/main/java/cn/shang/charging/settlement/ResultAssembler.java</automated>
  </verify>
  <acceptance_criteria>
  - ResultAssembler.assemble() calls detectFirstUnitMerged()
  - detectFirstUnitMerged() checks first unit's mergedFromPrevious flag
  - BillingResult.builder() includes .firstUnitMerged(firstUnitMerged)
  </acceptance_criteria>
</task>

</tasks>

<verification>
Run `mvn compile -pl core` and verify all modifications compile correctly.
</verification>

<success_criteria>
- All business logic files modified
- Truncated unit info correctly extracted and passed
- Merge logic prevents duplicate billing
- Build passes without errors
</success>

<output>
After completion, create `.planning/phases/01-continue/01-02-Summary.md`
</output>