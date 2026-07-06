# Billing Engine Capabilities

This document describes the capabilities that are implemented in the current codebase. It is a working reference for design and implementation discussions, not a historical design note.

> **Note**: This English version is currently out of date. The Chinese version (`docs/billing-engine-capabilities-zh.md`) is the authoritative, up-to-date reference. Updates are pending.

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
- External promotions through `externalPromotions` (cross-segment shared pool, used once per parking, not duplicated across segments).
- Partial calculation through `calcEndTime`.
- Time rounding through `timeRoundingMode`.
- Caller-defined context through `context`.
- Exact-query fallback control through `disableSimplification`.

Segment calculation modes:

| Mode | Behavior |
|------|----------|
| `SINGLE` | One segment for the whole request |
| `SEGMENT_LOCAL` | Each segment uses its own begin time as the calculation origin |
| `GLOBAL_ORIGIN` | Segments are clipped from a shared global time axis. **Half-finished**: subtraction unimplemented, multi-segment double-counts; only single-segment allowed (equivalent to SEGMENT_LOCAL); UNIT_BASED incompatible (TODO-20260702-001) |

---

## 4. Billing Modes

| Mode | Current meaning |
|------|-----------------|
| `CONTINUOUS` | The time axis can be split by free ranges and rule boundaries. Generated units may have variable lengths. |
| `UNIT_BASED` | Fixed unit length from the calculation origin. A free range must fully cover a unit to make it free. Does not use the boundary-driven loop. |
| `PERIOD` | Duration billing within a cycle, with cycle cap and period cap. Emits `DurationSegment`. |
| `GLOBAL` | Global duration billing, caps multiplied by cycle count. Emits `DurationSegment`. |

Rules declare supported `BillingMode` (CONTINUOUS/UNIT_BASED) via `BillingRule.supportedModes()` and supported `DurationMode` (PERIOD/GLOBAL) via `supportedDurationModes()`; the two dimensions are symmetric. DurationMode≠NONE routes to a duration strategy, otherwise the BillingMode routes to a unit strategy — naturally mutually exclusive.

**Facade + strategy structure** (TODO-20260702-002):

- Each rule family has one `ChargeRuleType`, one facade rule (e.g. `DayNightRule`), and one shared config. The facade dispatches to independent strategy implementations by mode and holds no billing logic itself.
- The `dayNight` facade declares `supportedModes()={CONTINUOUS, UNIT_BASED}` + `supportedDurationModes()={PERIOD, GLOBAL}`, dispatching to `ContinuousStrategy`/`DayNightUnitBasedStrategy`/`DayNightDurationStrategy`.
- Other rule families (`relativeTime`/`naturalTime`/`compositeTime`) currently support only `CONTINUOUS` and are facade-ized on demand.

---

## 5. Implemented Charging Rules

### `dayNight`

Implemented by the `DayNightRule` facade, dispatching to `ContinuousStrategy` (CONTINUOUS) / `DayNightUnitBasedStrategy` (UNIT_BASED) / `DayNightDurationStrategy` (PERIOD/GLOBAL).

Capabilities:

- 24-hour day/night cycle.
- `dayBeginMinute` and `dayEndMinute` define the day period.
- `dayUnitPrice` and `nightUnitPrice` define the two prices.
- `blockWeight` determines the final price of a mixed day/night unit.
- `maxChargeOneDay` applies a daily cap.
- UNIT_BASED semantics are carried by `DayNightUnitBasedStrategy` (a strategy under the facade: fixed unit alignment + full-coverage-free).

Important query behavior:

- Query-time value may increase or decrease inside a unit because it represents "what would be charged if billing ended at this query time".

### `relativeTime`

Implemented by `RelativeTimeRule`.

Capabilities:

- Configurable relative periods inside a cycle.
- Period-specific unit length and price.
- Cycle-level cap through `maxChargeOneCycle`.
- Simplified cycle calculation support.

Current limitation:


### `compositeTime`

Implemented by `CompositeTimeRule`.

Capabilities:

- Composite periods combined with natural-time pricing.
- Period-level and cycle-level behavior.
- Cross-period handling through configured modes.
- Simplified calculation support.

Current limitation:


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


Free range type:

| Range type | Meaning |
|------------|---------|
| `NORMAL` | Standard free range |
| `BUBBLE` | Bubble free range metadata; it participates in free range modeling as a distinct range type |

---

## 7. Promotion Aggregation

`PromotionEngine` collects rule-based and external grants, then produces a `PromotionAggregate`. External promotions (`externalPromotions`) share a cross-segment pool (`ExternalPromotionPool`), used once per parking: each segment takes the remaining amount from the pool, and writes back deductions from `PromotionUsage` after the segment, not duplicated across segments. In-scheme promotions are segment-local. AMOUNT/DISCOUNT are one-shot per parking, do not participate in free-range splitting, and are settled by `AmountDiscountApplier` afterwards.

Current aggregation stages:

1. Collect grants from `PromotionRuleConfig`.
2. Add external `PromotionGrant` entries from the request.
3. Summarize AMOUNT/DISCOUNT promotions.
4. Merge explicit `FREE_RANGE` promotions through `FreeTimeRangeMerger`.
5. Produce a canonical intermediate form: merged `FREE_RANGE` ranges + unmaterialized `FREE_MINUTES` list (`freeMinutesList`) + `AMOUNT`/`DISCOUNT` scalars.

`FREE_MINUTES` materialization is delegated to strategies (TODO-20260702-004): `PromotionEngine` no longer materializes centrally, avoiding the aggregation layer coupling to "rule + mode" to decide output form. CONTINUOUS/UNIT_BASED/PERIOD strategies materialize via `FreeMinuteAllocator.allocateAndMerge` (merged with `FREE_RANGE`); the GLOBAL strategy does not materialize, deducting `chargedMinutes` by minute, equivalent in final amount to the materialized path. `PromotionUsage` (FREE_MINUTES/FREE_RANGE) and `PromotionCarryOver` are produced strategy-side; `PromotionCarryOver` is built via `PromotionAggregateUtil.buildCarryOver` and written back to the aggregate.

`FreeTimeRangeMerger` preserves range metadata such as priority, source, and range type.

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

## 12. Promotion Equivalent Amounts

`PromotionEquivalentCalculator` lives in `billing-api`.


---

## 13. Test and Diagnostic Support

Current test support includes:

- Regression tests for `UnitValueEvaluator`.
- Runnable examples in `bill-test`.
- `BillingTestCaseGenerator`, which generates billing result JSON for manual inspection without expected results.

The generator currently focuses on `dayNight` and defines common, promotion, and rule-specific feature flags for future expansion.

---

## 14. Known Gaps

Current known gaps are tracked in `docs/TODO.md` and `docs/tracking/items/`.

Important current gaps include:

- `AMOUNT` and `DISCOUNT` promotion rules are not fully implemented.
- Reserved rule constants such as `times`, `naturalTime`, and `nrTimeMix` are not implemented.

---

## 15. Related Documents

| Document | Purpose |
|----------|---------|
| `docs/billing-engine-capabilities-zh.md` | Chinese version of this capability document |
| `docs/billing-engine-calculation-flow-zh.md` | Chinese calculation flow reference |
| `docs/USER_GUIDE.md` | User-facing guide |
| `docs/TODO.md` | Active backlog and issue index |
| `docs/DONE.md` | Completed backlog archive |
