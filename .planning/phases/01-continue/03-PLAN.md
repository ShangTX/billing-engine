---
phase: 01-continue
plan: 03
type: execute
wave: 3
depends_on: [01, 02]
files_modified:
- README.md
- README_CN.md
autonomous: true
requirements: [CONTINUE-16, CONTINUE-17, CONTINUE-18, CONTINUE-19]
user_setup: []
must_haves:
  truths:
    - "README.md documents new fields: lastTruncatedUnitStartTime, firstUnitMerged, mergedFromPrevious"
    - "README_CN.md documents new fields in Chinese"
    - "ContinueModeTest runs without errors"
    - "Truncated unit merge scenario shows correct billing (no double-charge)"
  artifacts:
    - path: "README.md"
      provides: "English documentation"
      contains: "lastTruncatedUnitStartTime"
    - path: "README_CN.md"
      provides: "Chinese documentation"
      contains: "截断单元"
  key_links: []
---

<objective>
Update documentation and verify fix with tests.
Purpose: Document new fields and validate that CONTINUE mode merge works correctly.
Output: Updated README files and passing tests.
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

<tasks>

<task type="auto">
  <name>Task 1: Update README.md with new field documentation</name>
  <files>README.md</files>
  <read_first>
  - README.md
  - core/src/main/java/cn/shang/charging/billing/pojo/SegmentCarryOver.java
  - core/src/main/java/cn/shang/charging/billing/pojo/BillingResult.java
  - core/src/main/java/cn/shang/charging/billing/pojo/BillingUnit.java
  - .planning/phases/01-continue/01-CONTEXT.md
  </read_first>
  <action>
Update README.md to document the new fields per CONTINUE-16.

Find the relevant sections (SegmentCarryOver, BillingResult, BillingUnit) and add field documentation:

For SegmentCarryOver section, add:
```
| lastTruncatedUnitStartTime | LocalDateTime | Last truncated unit's start time for CONTINUE mode merge. null if last unit was complete. |
```

For BillingResult section, add:
```
| firstUnitMerged | Boolean | Whether the first unit was merged from previous truncated unit. Only meaningful in CONTINUE mode. |
```

For BillingUnit section, add:
```
| mergedFromPrevious | Boolean | Whether this unit was merged from a previous truncated unit. true means this unit's begin time is at the previous truncated position. |
```

Also add a section explaining CONTINUE mode truncated unit handling:
```
### CONTINUE Mode Truncated Unit Handling

When the last billing unit of a calculation is truncated (isTruncated=true):
1. The truncated unit's start time is saved in `SegmentCarryOver.lastTruncatedUnitStartTime`
2. On CONTINUE calculation, the first unit merges with the truncated unit
3. `BillingResult.firstUnitMerged=true` indicates the caller should:
   - Update the previous result's last unit with the current result's first unit
   - Remove the current result's first unit (it's merged into previous)
```
  </action>
  <verify>
  <automated>grep -q "lastTruncatedUnitStartTime\|firstUnitMerged\|mergedFromPrevious" README.md</automated>
  </verify>
  <acceptance_criteria>
  - README.md contains documentation for lastTruncatedUnitStartTime
  - README.md contains documentation for firstUnitMerged
  - README.md contains documentation for mergedFromPrevious
  - README.md contains CONTINUE mode truncated unit handling section
  </acceptance_criteria>
</task>

<task type="auto">
  <name>Task 2: Update README_CN.md with new field documentation</name>
  <files>README_CN.md</files>
  <read_first>
  - README_CN.md
  - README.md (for reference)
  - .planning/phases/01-continue/01-CONTEXT.md
  </read_first>
  <action>
Update README_CN.md to document the new fields in Chinese per CONTINUE-17.

Add corresponding Chinese documentation for the same fields as in README.md:

For SegmentCarryOver section:
```
| lastTruncatedUnitStartTime | LocalDateTime | 最后一个截断单元的开始时间，用于 CONTINUE 模式合并计算。如果最后单元完整则为 null |
```

For BillingResult section:
```
| firstUnitMerged | Boolean | 第一个单元是否为合并单元（从上一次截断单元合并）。仅在 CONTINUE 模式有意义 |
```

For BillingUnit section:
```
| mergedFromPrevious | Boolean | 此单元是否从上一次截断单元合并而来。true 表示此单元的开始时间在上次计算的截断单元位置 |
```

Add Chinese section for CONTINUE mode:
```
### CONTINUE 模式截断单元处理

当计算的最后一个计费单元被截断时（isTruncated=true）：
1. 截断单元的开始时间保存在 `SegmentCarryOver.lastTruncatedUnitStartTime`
2. CONTINUE 计算时，第一个单元与截断单元合并
3. `BillingResult.firstUnitMerged=true` 表示调用方应：
   - 用当前结果的第一个单元更新上一次结果的最后一个单元
   - 删除当前结果的第一个单元（已合并到上一次结果中）
```
  </action>
  <verify>
  <automated>grep -q "lastTruncatedUnitStartTime\|firstUnitMerged\|mergedFromPrevious\|截断单元" README_CN.md</automated>
  </verify>
  <acceptance_criteria>
  - README_CN.md contains Chinese documentation for lastTruncatedUnitStartTime
  - README_CN.md contains Chinese documentation for firstUnitMerged
  - README_CN.md contains Chinese documentation for mergedFromPrevious
  - README_CN.md contains CONTINUE 模式截断单元处理 section
  </acceptance_criteria>
</task>

<task type="auto">
  <name>Task 3: Run tests and verify fix</name>
  <files>bill-test/src/main/java/cn/shang/charging/ContinueModeTest.java</files>
  <read_first>
  - bill-test/src/main/java/cn/shang/charging/ContinueModeTest.java
  - .planning/phases/01-continue/01-CONTEXT.md
  </read_first>
  <action>
Compile and run ContinueModeTest to verify the fix per CONTINUE-18, CONTINUE-19.

Steps:
1. Compile all modules: `mvn clean compile`
2. Run ContinueModeTest: `mvn exec:java -pl bill-test -Dexec.mainClass="cn.shang.charging.ContinueModeTest"`

Key test cases to verify:
- `testIrregularTime_MultiContinue_RelativeTime()` - Multiple CONTINUE calculations consistency
- Any test with truncated units should show no double-charging

Expected behavior after fix:
- First CONTINUE calculation's last unit is truncated (isTruncated=true)
- Second CONTINUE calculation's first unit has mergedFromPrevious=true
- Second CONTINUE calculation's BillingResult has firstUnitMerged=true
- No duplicate billing for the truncated portion
  </action>
  <verify>
  <automated>mvn compile -pl core -q && echo "Compile success"</automated>
  </verify>
  <acceptance_criteria>
  - `mvn clean compile` completes without errors
  - ContinueModeTest runs and key tests pass
  - Truncated unit merge scenario shows correct amounts (no double-charge)
  - One-time calculation amount equals sum of multiple CONTINUE calculations
  </acceptance_criteria>
</task>

</tasks>

<verification>
Run full test suite: `mvn clean compile && mvn exec:java -pl bill-test -Dexec.mainClass="cn.shang.charging.ContinueModeTest"`
</verification>

<success_criteria>
- Documentation updated with all new fields
- Tests pass
- Fix verified: no duplicate billing in CONTINUE mode
</success>

<output>
After completion, create `.planning/phases/01-continue/01-03-Summary.md`
</output>