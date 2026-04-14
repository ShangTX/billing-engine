# Billing Engine Capabilities Document

**Document Purpose:** Foundation for design discussions about "price uncertainty" handling pattern
**Generated:** 2026-04-14

---

## 1. Architecture Pipeline Overview

### Core Pipeline Flow

```
BillingRequest → BillingService → SegmentBuilder → BillingConfigResolver → PromotionEngine → BillingCalculator → ResultAssembler → BillingResult
```

**BillingService.java** (`core/.../billing/BillingService.java`)

**Key Method:** `calculate(BillingRequest request)`

### Pipeline Stages

1. **ContinueMode Detection** - Checks `request.getPreviousCarryOver()` to determine if resuming from previous state
2. **Segment Building** - `segmentBuilder.buildSegments(request)` creates `List<BillingSegment>` from scheme changes
3. **Per-Segment Processing** - Creates `CalculationWindow`, resolves charging rules, promotion rules, billing mode
4. **Promotion Evaluation** - `promotionEngine.evaluate(context)` → `PromotionAggregate`
5. **Billing Calculation** - `billingCalculator.calculate(context, promotionAggregate)` → `BillingSegmentResult`
6. **Result Assembly** - `resultAssembler.assemble()` → `BillingResult`

### Data Flow Between Layers

```
BillingRequest (input: time range, schemeId/schemeChanges, externalPromotions)
    ↓
BillingSegment (time slice with beginTime/endTime/schemeId)
    ↓
BillingContext (immutable: segment + window + rules)
    ↓
PromotionAggregate (freeTimeRanges + freeMinutes + promotionUsages + boundaryReferences)
    ↓
BillingSegmentResult (units + promotionUsages)
    ↓
BillingResult (combined units, finalAmount, carryOver)
    ↓
BillingResultViewer (query-time filtering, conditional validation)
```

---

## 2. Billing Modes: CONTINUOUS vs UNIT_BASED

**Enum:** `BConstants.BillingMode`

### CONTINUOUS Mode
- Time axis is split at free time range boundaries
- Unit boundaries are determined by promotion boundaries, not fixed alignment
- Free ranges create natural boundaries in the time axis
- Uses `TimeFragment` inner class for axis splitting

### UNIT_BASED Mode
- Fixed unit length alignment from billing start time
- Units generated at regular intervals (e.g., every 60 minutes)
- Free time ranges must **fully cover** a unit for it to be free
- Partial coverage does NOT make unit free

---

## 3. Segment Calculation Modes

**Enum:** `BConstants.SegmentCalculationMode`

| Mode | Behavior |
|------|----------|
| SINGLE | Only one segment, no scheme changes |
| SEGMENT_LOCAL | Each segment calculates from its own begin time |
| GLOBAL_ORIGIN | All segments share global origin; segments are "cut" from global time axis |

---

## 4. Charging Rules

### 4.1 DayNightRule
**File:** `core/.../charge/rules/daynight/DayNightRule.java` (1513 lines)
- 24-hour cycle with day/night pricing
- `dayBeginMinute`/`dayEndMinute` define day period
- `blockWeight`: when unit spans day/night, use day price if day ratio >= weight
- `maxChargeOneDay`: daily cap
- **CONTINUOUS:** `splitTimeAxis()` fragments time by free ranges
- **UNIT_BASED:** fixed units, free if fully covered

### 4.2 RelativeTimeRule
**File:** `core/.../charge/rules/relativetime/RelativeTimeRule.java` (1610 lines)
- Configurable time periods within 24-hour cycle
- Period-specific unit lengths and prices
- `maxChargeOneCycle`: cycle-level cap

### 4.3 CompositeTimeRule
**File:** `core/.../charge/rules/compositetime/CompositeTimeRule.java` (1735 lines)
- Combines composite periods with natural pricing
- **Unique feature:** period-level caps (not just cycle caps)
- `CrossPeriodMode` for period transitions

### 4.4 AbstractTimeBasedRule
**File:** `core/.../charge/rules/AbstractTimeBasedRule.java` (389 lines)
- Common state: `RuleState` (cycleIndex, cycleAccumulated, cycleBoundary)
- Simplification support: aggregates consecutive identical cycles
- `ruleData` for simplified units: `{isSimplified, cycleIndex, simplifiedCycleCount, simplifiedCycleAmount}`

### 4.5 FlatFreeRule
- Returns single free unit covering entire billing window

---

## 5. Promotion Types and Processing

**Enum:** `BConstants.PromotionType`

| Type | Description |
|------|-------------|
| FREE_RANGE | Explicit free time range with beginTime/endTime |
| FREE_MINUTES | Allocated to gaps between FREE_RANGE promotions |
| AMOUNT | Direct monetary discount |
| DISCOUNT | Percentage-based discount |

### Processing Pipeline (PromotionEngine)

1. **Grant Collection** - From promotion rule configs + external promotions
2. **Carry-Over Application** - Remaining minutes from previous calc, subtract used ranges
3. **FREE_RANGE Merging** - `FreeTimeRangeMerger.merge()` → priority-based merge
4. **FREE_MINUTES Allocation** - `FreeMinuteAllocator.allocate()` in gaps
5. **Final Merge** - Combine explicit and generated ranges
6. **Carry-Over Output** - Build `PromotionCarryOver`

### FreeTimeRangeMerger
- Sorts by priority (lower number = higher priority)
- Higher priority covers lower priority in overlaps
- Records `boundaryReferences` (free ranges outside current window, for extension)
- Records `discardedRanges` (overlapped/out-of-window portions)
- Preserves `conditional`/`conditionalUntil` during all merge operations

### FreeMinuteAllocator
- Fills gaps between explicit FREE_RANGE
- Uses free minutes in priority order
- Tracks `PromotionUsage` for each allocation

---

## 6. Promotion Rule Types

**Constants:** `BConstants.PromotionRuleType`

| Type | Constant | Description |
|------|----------|-------------|
| START_FREE | `"startFree"` | First N minutes from segment start are free |
| FREE_MINUTES | `"freeMinutes"` | Allocates N free minutes within billing window |

### StartFreePromotionRule
**File:** `core/.../promotion/rules/startfree/StartFreePromotionRule.java`
- Generates `PromotionGrant` with type=FREE_RANGE
- Time window: `[segmentBeginTime, segmentBeginTime + config.minutes)`
- If `config.validateQueryTime=true`: sets `conditional=true`, `conditionalUntil=endTime`

---

## 7. Free Time Ranges and Billing Unit Creation

### 7.1 FreeTimeRange Structure
**File:** `core/.../promotion/pojo/FreeTimeRange.java`

```
id, beginTime, endTime, priority, promotionType, rangeType (NORMAL/BUBBLE),
source, conditional, conditionalUntil, data, valid
```

### 7.2 PromotionGrant Structure
**File:** `core/.../promotion/pojo/PromotionGrant.java`

```
id, type, beginTime, endTime, freeMinutes, priority, rangeType,
conditional, conditionalUntil, data
```

### 7.3 Unit Creation Differences by Billing Mode

**CONTINUOUS Mode:**
- `splitTimeAxis()` creates cut points at free range begin/end boundaries
- Each fragment between cut points checked against free ranges
- Free fragment → single billing unit (duration = free range duration)
- Non-free fragment → split by unitMinutes
- **Key consequence:** free ranges change unit boundaries

**UNIT_BASED Mode:**
- Fixed units from beginTime aligned to unitMinutes
- `findFreePromotionId()` checks if unit is **fully covered** by a free range
- Partial coverage → NOT free
- **Key consequence:** free ranges do NOT change unit boundaries

---

## 8. CONTINUE Mode Mechanics

### 8.1 BillingCarryOver Structure
**File:** `core/.../billing/pojo/BillingCarryOver.java`

```
calculatedUpTo, segments (Map), lastTruncatedUnitStartTime,
accumulatedAmount, truncatedUnitChargedAmount
```

### 8.2 Per-Segment Carry-Over (SegmentCarryOver)
```
lastTruncatedUnitStartTime, promotionState (PromotionCarryOver), ruleState (Map)
```

### 8.3 PromotionCarryOver
**File:** `core/.../promotion/pojo/PromotionCarryOver.java`

```
remainingMinutes (Map<String, Object>),
remainingMinutesConverted (Map<String, Integer>),
usedFreeRanges (List<FreeTimeRange>)
```

### 8.4 Restore Flow
1. `lastTruncatedUnitStartTime` → next calculation starts from truncated unit begin
2. `promotionState.remainingMinutes` → updates free minutes list
3. `promotionState.usedFreeRanges` → subtracts already-used portions from new ranges
4. `accumulatedAmount` → base for running total recalculation

---

## 9. Query Time Filtering (Billing API)

### 9.1 BillingResultViewer
**File:** `billing-api/.../wrapper/BillingResultViewer.java` (318 lines)

### 9.2 viewAtTime(result, queryTime)
- Applies conditional free validation
- Filters units: keeps only `endTime <= queryTime`
- Filters promotion usages
- Recalculates amounts

### 9.3 createQuerySummary(result, queryTime)
- Lightweight index-based lookup
- Returns `QuerySummary` with unitIndex, amount, effectiveFrom, effectiveTo
- Also applies conditional free validation

### 9.4 applyQueryTimeValidation(result, queryTime)
**Core logic:**
```
if unit.isConditionalFree() && queryTime > unit.conditionalFreeUntil:
    unit.setConditionalFree(false)
    unit.setFree(false)
    unit.setFreePromotionId(null)
    unit.setChargedAmount(unit.getOriginalAmount())
    recalculate accumulatedAmount
```

---

## 10. Promotion Equivalent Calculation

**File:** `billing-api/.../wrapper/PromotionEquivalentCalculator.java`

**Algorithm: Elimination Method**
1. Calculate baseline with all promotions
2. Sort free ranges by begin time
3. Sequentially exclude promotions one-by-one
4. Difference = promotion's monetary value

---

## 11. The ruleData Field on BillingUnit

**Type:** `Object`

### Current Usage

**Normal Units:** `Integer` → cycleIndex

**Simplified Units:** `Map<String, Object>`
```
{isSimplified: true, cycleIndex: N, simplifiedCycleCount: M, simplifiedCycleAmount: amount}
```

### Purpose
- Rule-specific extension data
- Enables audit trail
- Supports post-calculation analysis

---

## 12. conditionalFree/conditionalFreeUntil on BillingUnit

### Field Definitions
**BillingUnit.java**

```java
private boolean conditionalFree;          // Is this a conditional free unit?
private LocalDateTime conditionalFreeUntil;  // Query time window end
```

### Propagation Path
```
StartFreePromotionConfig.validateQueryTime=true
    → PromotionGrant(conditional=true, conditionalUntil=endTime)
    → FreeTimeRange(conditional=true, conditionalUntil=endTime)
    → TimeFragment(conditionalFree=true, conditionalFreeUntil=endTime, originalPrice=price)
    → BillingUnit(conditionalFree=true, conditionalFreeUntil=endTime, originalAmount=price)
```

### TimeFragment Inner Class (DayNightRule)
```java
private static class TimeFragment {
    LocalDateTime beginTime, endTime;
    boolean isFree;
    String freePromotionId;
    boolean conditionalFree;
    LocalDateTime conditionalFreeUntil;
    BigDecimal originalPrice;  // What this fragment would cost without promotion
}
```

### RelativeTimeRule and CompositeTimeRule
- Same pattern: TimeFragment with conditionalFree fields
- `splitTimeAxis()` signatures updated to propagate conditional info
- `generateUnitsForCycle()` copies conditional fields to BillingUnit

---

## 13. Key POJOs Summary

### BillingRequest
```
id, beginTime, endTime, calcEndTime,
schemeId, schemeChanges, segmentCalculationMode,
externalPromotions, previousCarryOver,
timeRoundingMode, context (Map)
```

### BillingUnit
```
beginTime, endTime, durationMinutes,
unitPrice, originalAmount, chargedAmount, accumulatedAmount,
free, freePromotionId,
conditionalFree, conditionalFreeUntil,
isTruncated, mergedFromPrevious,
ruleData (Object), ruleType (String)
```

### BillingResult
```
units, finalAmount, effectiveFrom, effectiveTo,
calculationEndTime, accumulatedAmount,
promotionUsages, carryOver,
firstUnitMerged, truncatedUnitChargedAmount,
settlementAdjustments, segments
```

### PromotionAggregate
```
freeTimeRanges, freeMinutes,
boundaryReferences, discardedRanges,
usages, promotionCarryOver
```

---

## 14. Current Test Coverage

### ConditionalFreePromotionTest (6 scenarios)
1. Basic conditional free - different queryTime points
2. Conditional free + external permanent free range overlap
3. Conditional free + free minutes coexistence
4. CONTINUE mode with conditional free
5. Exact boundary time (queryTime = conditionalFreeUntil)
6. Multiple conditional free units (90-min free, 30-min units)

### StartFreePromotionTest (6 scenarios)
1. Basic start free (30 minutes)
2. Overlap with external free range
3. Partial coverage (window < N minutes)
4. CONTINUE mode
5. Conditional free - queryTime within range
6. Conditional free - queryTime outside range

### PromotionTest (4 scenarios)
1. Multiple free minutes叠加
2. Free time range merge
3. Rule + external promotions combination
4. Free minutes + free range combined

---

## 15. Key Design Issues for Discussion

### Issue: Free Range Boundaries as Unit Split Points

**Current Behavior (CONTINUOUS mode):**
- `splitTimeAxis()` creates cut points at every free range begin/end
- This changes unit boundaries based on promotions
- Example: 45-min units, 60-min free (00:00-01:00)
  - Unit 1: 00:00-01:00 (free, 60 min) - **different from normal 45-min**
  - Unit 2: 01:00-01:45 (charged, 45 min)
  - Unit 3: 01:45-02:30 (charged, 45 min)

**Problem:** Query at 00:31 sees units starting at 00:00, but the first unit ends at 01:00 instead of 00:45. The billing unit collection is not stable/promotion-independent.

### Proposed Alternative: Price Uncertainty Pattern

Instead of splitting units at free range boundaries:
1. Generate units by normal unitMinutes alignment
2. Mark units that are affected by promotions (fully covered, partially covered, conditional)
3. Store uncertainty metadata in `ruleData`
4. Query layer handles uncertainty resolution

**Benefits:**
- Stable unit collection regardless of promotions
- Unit boundaries are predictable
- Query-time logic can handle various uncertainty types (not just conditional free)

---

## Appendix: File Locations

| Component | Path |
|-----------|------|
| BillingService | core/.../billing/BillingService.java |
| BillingUnit | core/.../billing/pojo/BillingUnit.java |
| DayNightRule | core/.../charge/rules/daynight/DayNightRule.java |
| RelativeTimeRule | core/.../charge/rules/relativetime/RelativeTimeRule.java |
| CompositeTimeRule | core/.../charge/rules/compositetime/CompositeTimeRule.java |
| AbstractTimeBasedRule | core/.../charge/rules/AbstractTimeBasedRule.java |
| PromotionEngine | core/.../promotion/PromotionEngine.java |
| FreeTimeRangeMerger | core/.../promotion/FreeTimeRangeMerger.java |
| FreeMinuteAllocator | core/.../promotion/FreeMinuteAllocator.java |
| BillingResultViewer | billing-api/.../wrapper/BillingResultViewer.java |
| StartFreePromotionRule | core/.../promotion/rules/startfree/StartFreePromotionRule.java |
| StartFreePromotionConfig | core/.../promotion/rules/startfree/StartFreePromotionConfig.java |
| FreeTimeRange | core/.../promotion/pojo/FreeTimeRange.java |
| PromotionGrant | core/.../promotion/pojo/PromotionGrant.java |
