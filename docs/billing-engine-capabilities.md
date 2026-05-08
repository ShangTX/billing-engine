# Billing Engine Capabilities

This document describes the capabilities that are implemented in the current codebase. It is a working reference for design and implementation discussions, not a historical design note.

Last reviewed: 2026-05-08

---

## 1. Scope

The project is a time-based billing engine for parking, venue rental, device rental, and other time charging scenarios.

The engine focuses on deterministic calculation:

```
BillingRequest
  -> BillingService
  -> SegmentBuilder
  -> BillingConfigResolver
  -> PromotionEngine
  -> BillingCalculator
  -> BillingRule
  -> ResultAssembler
  -> BillingResult
```

The `core` module performs pure calculation. The `billing-api` module adds convenient APIs, time rounding, query-time views, and promotion equivalent amount analysis.

---

## 2. Main Modules

| Module | Capability |
|--------|------------|
| `core` | Pure billing calculation, promotion aggregation, rule execution, carry-over state |
| `billing-api` | `BillingTemplate`, query summaries, exact-query fallback, promotion equivalent amount calculation |
| `billing-v3-spring-boot-starter` | Spring Boot 3.0.x to 3.4.x auto-configuration |
| `billing-v4-spring-boot-starter` | Spring Boot 3.5.x to 4.x auto-configuration |
| `bill-test` | Integration examples, regression tests, billing result generator |

---

## 3. Billing Inputs and Segmentation

`BillingRequest` supports:

- Single-scheme calculation through `schemeId`.
- Multi-scheme calculation through `schemeChanges`.
- External promotions through `externalPromotions`.
- Continuation through `previousCarryOver`.
- Partial calculation through `calcEndTime`.
- Time rounding through `timeRoundingMode`.
- Caller-defined context through `context`.
- Exact-query fallback control through `disableSimplification`.

Segment calculation modes:

| Mode | Behavior |
|------|----------|
| `SINGLE` | One segment for the whole request |
| `SEGMENT_LOCAL` | Each segment uses its own begin time as the calculation origin |
| `GLOBAL_ORIGIN` | Segments are clipped from a shared global time axis |

---

## 4. Billing Modes

| Mode | Current meaning |
|------|-----------------|
| `UNIT_BASED` | Fixed unit length from the calculation origin. A free range must fully cover a unit to make it free. |
| `CONTINUOUS` | The time axis can be split by free ranges and rule boundaries. Generated units may have variable lengths. |

Rules must declare supported modes through `BillingRule.supportedModes()`.

---

## 5. Implemented Charging Rules

### `dayNight`

Implemented by `DayNightRule`.

Capabilities:

- 24-hour day/night cycle.
- `dayBeginMinute` and `dayEndMinute` define the day period.
- `dayUnitPrice` and `nightUnitPrice` define the two prices.
- `blockWeight` determines the final price of a mixed day/night unit.
- `maxChargeOneDay` applies a daily cap.
- Supports both `UNIT_BASED` and `CONTINUOUS`.
- Emits `valueSpec` for stable units, conditional free units, mixed day/night units, and capped units.

Important query behavior:

- Mixed day/night units preserve rule-specific intra-unit valuation through `MixedUnitValueSpec`.
- Query-time value may increase or decrease inside a unit because it represents "what would be charged if billing ended at this query time".
- Daily cap is encoded into the hit unit's `valueSpec`, so settled amount and query-time amount stay aligned.

### `relativeTime`

Implemented by `RelativeTimeRule`.

Capabilities:

- Configurable relative periods inside a cycle.
- Period-specific unit length and price.
- Cycle-level cap through `maxChargeOneCycle`.
- Simplified cycle calculation support.

Current limitation:

- It has not yet been migrated to rule-specific `valueSpec` for mixed intra-unit query behavior.

### `compositeTime`

Implemented by `CompositeTimeRule`.

Capabilities:

- Composite periods combined with natural-time pricing.
- Period-level and cycle-level behavior.
- Cross-period handling through configured modes.
- Simplified calculation support.

Current limitation:

- It has not yet been migrated to rule-specific `valueSpec` for complex intra-unit query behavior.

### `flatFree`

Implemented as a rule that returns a free unit covering the requested billing window. It is implemented but may require manual registration depending on how the engine is constructed.

### Reserved Rule Constants

Some constants are currently reserved and not implemented as working billing rules, including `times`, `naturalTime`, and `nrTimeMix`.

---

## 6. Promotions

Implemented promotion grant types:

| Type | Meaning |
|------|---------|
| `FREE_RANGE` | Explicit free time range |
| `FREE_MINUTES` | Free minutes allocated into non-free gaps |

Reserved or partially documented promotion types:

| Type | Status |
|------|--------|
| `AMOUNT` | Reserved, not implemented as a complete promotion rule |
| `DISCOUNT` | Reserved, not implemented as a complete promotion rule |

Implemented promotion rules:

| Rule | Capability |
|------|------------|
| `freeMinutes` | Grants free minutes that are allocated across available gaps |
| `startFree` | Grants an initial free range from segment start |

`StartFreePromotionConfig.validateQueryTime=true` is no longer modeled through `BillingUnit.conditionalFree`. It is represented by a `StepValueSpec` on affected billing units: free before the condition boundary and normal price after the boundary.

Free range type:

| Range type | Meaning |
|------------|---------|
| `NORMAL` | Standard free range |
| `BUBBLE` | Bubble free range metadata; it participates in free range modeling as a distinct range type |

---

## 7. Promotion Aggregation

`PromotionEngine` collects rule-based and external grants, then produces a `PromotionAggregate`.

Current aggregation stages:

1. Collect grants from `PromotionRuleConfig`.
2. Add external `PromotionGrant` entries from the request.
3. Restore promotion carry-over in `CONTINUE` mode.
4. Merge explicit `FREE_RANGE` promotions through `FreeTimeRangeMerger`.
5. Allocate `FREE_MINUTES` through `FreeMinuteAllocator`.
6. Merge explicit and generated free ranges.
7. Build promotion carry-over state.

`FreeTimeRangeMerger` preserves range metadata such as priority, source, range type, and conditional metadata. Query-time conditional behavior is interpreted later by rule-generated `valueSpec`, not by viewer-side field patching.

---

## 8. Unit Valuation and Query-Time Amounts

`BillingUnit` contains the settled full-unit amounts and an optional `valueSpec`.

Important fields:

| Field | Meaning |
|-------|---------|
| `chargedAmount` | Final amount after the whole unit settles |
| `accumulatedAmount` | Total amount after the whole unit settles |
| `valueSpec` | Unit-level projection model for query-time amount |
| `ruleData` | Rule-private metadata, including simplified unit markers |

The current core valuation protocol is:

| Type | Role |
|------|------|
| `UnitValueSpec` | Interface for projecting a unit value at a query time |
| `UnitValueProjection` | Projection result: `currentAmount` and `nextChangeTime` |
| `UnitValueEvaluator` | Validates input and projection invariants |
| `FixedValueSpec` | Stable unit value |
| `StepValueSpec` | Step value, used by conditional start-free behavior |
| `PiecewiseTimeValueSpec` | Generic time-segment expression model |
| `DayNightRule.MixedUnitValueSpec` | Day/night rule-specific mixed unit projection |
| `DayNightRule.CappedValueSpec` | Day/night rule-specific cap wrapper |

Query amount formula for the hit unit:

```
queryAmount = unit.accumulatedAmount - unit.chargedAmount + valueAt(unit, queryTime)
```

This keeps `accumulatedAmount` as the settled prefix total while replacing the hit unit's full settled amount with its query-time projection.

---

## 9. Query APIs

`BillingResultViewer.createQuerySummary(result, queryTime)`:

- Rejects `queryTime` after `result.calculationEndTime`.
- Finds the unit containing the query time.
- Evaluates the hit unit through `UnitValueEvaluator`.
- Uses `valueSpec.nextChangeTime` as the query summary's `effectiveTo`.
- Falls back to `FixedValueSpec(chargedAmount)` for old results that do not carry `valueSpec`.

`BillingTemplate.calculateWithQuery(request, queryTime)`:

- Calculates normally first.
- Creates a query summary.
- If the hit unit is a simplified unit, recalculates once with `disableSimplification=true`.
- Returns the detailed calculation result and the query summary.

`BillingResultViewer.viewAtTime(result, queryTime)` still provides a filtered result view based on finished units. For precise in-unit query amounts, prefer `createQuerySummary()` or `BillingTemplate.calculateWithQuery()`.

---

## 10. Simplified Calculation

`AbstractTimeBasedRule` supports simplified cycle calculation for long spans.

Simplified units use `ruleData` similar to:

```json
{
  "isSimplified": true,
  "cycleIndex": 1,
  "simplifiedCycleCount": 10,
  "simplifiedCycleAmount": 120.00
}
```

Simplification intentionally drops intra-unit detail. When an exact query hits a simplified unit, `billing-api` performs an exact recalculation by setting `BillingRequest.disableSimplification=true`.

This keeps long-range calculation efficient while preserving exact query behavior.

---

## 11. CONTINUE Mode

`CONTINUE` mode is driven by `BillingCarryOver`.

Main carry-over data:

- `calculatedUpTo`
- per-segment carry-over state
- `lastTruncatedUnitStartTime`
- `truncatedUnitChargedAmount`
- `accumulatedAmount`

If the previous calculation ended inside a billing unit, the next calculation starts from the truncated unit's begin time and uses carry-over amounts to avoid double charging.

Promotion carry-over keeps remaining free minutes and used free ranges so future calculations can continue the same promotion state.

---

## 12. Promotion Equivalent Amounts

`PromotionEquivalentCalculator` lives in `billing-api`.

It calculates equivalent promotion amounts by comparing full calculation results. The query-time `valueSpec` mechanism does not change the promotion equivalent amount contract as long as the full settled result has consistent `chargedAmount`, `accumulatedAmount`, and promotion usages.

---

## 13. Test and Diagnostic Support

Current test support includes:

- Regression tests for `UnitValueEvaluator`.
- Query summary tests for `BillingResultViewer` and simplified-unit fallback.
- Day/night query value tests for mixed units, capped units, and conditional start-free.
- Runnable examples in `bill-test`.
- `BillingTestCaseGenerator`, which generates billing result JSON for manual inspection without expected results.

The generator currently focuses on `dayNight` and defines common, promotion, and rule-specific feature flags for future expansion.

---

## 14. Known Gaps

Current known gaps are tracked in `docs/TODO.md` and `docs/tracking/items/`.

Important current gaps include:

- `AMOUNT` and `DISCOUNT` promotion rules are not fully implemented.
- Reserved rule constants such as `times`, `naturalTime`, and `nrTimeMix` are not implemented.
- `relativeTime` and `compositeTime` do not yet have the same rich rule-specific `valueSpec` coverage as `dayNight`.
- Minute-by-minute `valueSpec` is planned as an extension point but is not implemented yet.

---

## 15. Related Documents

| Document | Purpose |
|----------|---------|
| `docs/billing-engine-capabilities-zh.md` | Chinese version of this capability document |
| `docs/billing-engine-calculation-flow-zh.md` | Chinese calculation flow reference |
| `docs/USER_GUIDE.md` | User-facing guide |
| `docs/TODO.md` | Active backlog and issue index |
| `docs/DONE.md` | Completed backlog archive |
| `docs/superpowers/specs/2026-04-20-unit-value-spec-design.md` | `valueSpec` design |
| `docs/superpowers/plans/2026-04-20-unit-value-spec-implementation.md` | `valueSpec` implementation plan |
