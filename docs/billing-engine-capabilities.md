# Billing Engine Capabilities

This document describes the capabilities that are implemented in the current codebase. It is a working reference for design and implementation discussions, not a historical design note.

Last reviewed: 2026-07-06

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
| `core` | Pure billing calculation, promotion aggregation, rule execution |
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

> **GLOBAL_ORIGIN deprecated (TODO-20260706-003)**: The "global origin + segment clipping" subtraction scheme (4B) was never implemented; the cross-segment `externalPool` sharing replaces its external-promotion consistency goal. `SegmentCalculationMode` keeps only `SINGLE` / `SEGMENT_LOCAL` (SEGMENT_LOCAL as extension point). The 4A subtraction design is documented in `docs/designs/segment-promotion-consistency.md`.

---

## 4. Billing Modes

`CalculationMode` is a single enum with four peer values (merged from the former `BillingMode` + `DurationMode`):

| Mode | Current meaning |
|------|-----------------|
| `CONTINUOUS` | The boundary-driven loop is the only calculation path: find the nearest boundary (free-range start/end, period end, cycle end, unit alignment, calcEnd) and jump to it, producing one homogeneous segment per iteration; compact units are a natural byproduct. |
| `UNIT_BASED` | Fixed unit alignment + full-coverage-free; does not use the boundary-driven loop. Currently carried only by `DayNightUnitBasedStrategy` under the `dayNight` facade. |
| `DURATION_PERIOD` | Duration billing within a cycle, with cycle cap and period cap. Emits `DurationSegment`. |
| `DURATION_GLOBAL` | Global duration billing, caps multiplied by cycle count. Emits `DurationSegment`. The only mode that consumes `SMART_FREE_MINUTES`. |

### Mode feature matrix

| Feature | CONTINUOUS | UNIT_BASED | DURATION_PERIOD | DURATION_GLOBAL |
|---------|-----------|-----------|-----------------|-----------------|
| Output structure | BillingUnit | BillingUnit | DurationSegment | DurationSegment |
| Splitting model | Boundary-driven cut | Fixed unit alignment | Boundary-driven minute stream | Boundary-driven minute stream |
| Shared scheduling layer | Yes | No | Yes | Yes |
| FREE_MINUTES handling | Pre-materialized (from start) | Pre-materialized (from start) | Pre-materialized (from start) | Pre-materialized (from start) + SMART_FREE_MINUTES |
| SMART_FREE_MINUTES | Error | Error | Error | Rule-side highest-price-first allocation |
| compact merge | Yes | No | No | No |
| Simplified calculation | Global-gap | None | None | None |
| Cap basis | Per-cycle cap | Daily cap | In-cycle cap | Global cap × cycle count |

### Four-layer architecture

```
Layer 0  RuleSemantics (rule-family implementation, describes "what it is")
         cycle/period/unit boundary providers + price function + PeriodLabeler
         + cap config + cycle-boundary predicate + incomplete-unit config
Layer 1  BoundaryDrivenLoop (pure scheduling, zero billing semantics, stable)
         BoundaryProvider / HomogeneousSegment
Layer 2  ModeStrategy (4 implementations, describe "how to compute")
         ContinuousStrategy / DayNightUnitBasedStrategy
         DurationPeriodStrategy / DurationGlobalStrategy
         each receives (RuleSemantics, context, aggregate) and reuses Layer 1
Layer 3  BillingRule facade (pure dispatch)
         DayNightRule / RelativeTimeRule / ...
         builds RuleSemantics, delegates to the matching ModeStrategy by calculationMode
```

Orthogonal benefit: adding a rule family → implement `RuleSemantics` + a facade, all 4 modes become available; adding a mode → implement one `ModeStrategy`, all rule families gain it (N+M implementation points instead of N×M).

Rules declare supported modes via `BillingRule.supportedCalculationModes()`; the facade dispatches by the requested mode:

- `dayNight` declares all 4 modes, dispatching to `DayNightContinuousStrategy` (CONTINUOUS) / `DayNightUnitBasedStrategy` (UNIT_BASED) / `DurationPeriodStrategy` / `DurationGlobalStrategy` (receiving `DayNightSemantics`).
- `relativeTime` / `naturalTime` / `compositeTime` declare `CONTINUOUS` / `DURATION_PERIOD` / `DURATION_GLOBAL` (no `UNIT_BASED`); each is carried by its `*ContinuousStrategy` + the shared duration strategies.
- `flatFree` declares `CONTINUOUS` / `UNIT_BASED`.

Boundary-driven framework abstractions:

| Abstraction | Responsibility |
|-------------|----------------|
| `BoundaryProvider` | Boundary source interface; rules register their own boundaries (free ranges, period ends, cycle ends, unit alignment, etc.) |
| `BoundaryProviders` | Boundary source factory + `findNearest` |
| `HomogeneousSegment` | Homogeneous segment, the minimal product of the boundary-driven loop |
| `HomogeneousSegmentCalculator` | Homogeneous segment → BillingUnit (with compact merge) |
| `CompactMerger` | Generic compact merger, merges consecutive identical units across segments |
| `BoundaryDrivenLoop` | Public loop entry (`run`), pure scheduling; shared by CONTINUOUS and duration strategies; UNIT_BASED does not use it |

---

## 5. Implemented Charging Rules

### `dayNight`

Implemented by the `DayNightRule` facade, dispatching by `CalculationMode` to `DayNightContinuousStrategy` (CONTINUOUS) / `DayNightUnitBasedStrategy` (UNIT_BASED) / `DurationPeriodStrategy` / `DurationGlobalStrategy` (PERIOD/GLOBAL, receiving `DayNightSemantics`).

Capabilities:

- 24-hour day/night cycle.
- `dayBeginMinute` and `dayEndMinute` define the day period.
- `dayUnitPrice` and `nightUnitPrice` define the two prices.
- `blockWeight` determines the final price of a mixed day/night unit.
- `splitDayNightBoundary` (default `true`) controls whether CONTINUOUS splits units at the day/night boundary: when `false`, a unit spanning the boundary is priced by `crossPeriodMode` (default `BLOCK_WEIGHT`) + `blockWeight` (legacy semantics).
- `maxChargeOneDay` applies a daily cap.
- UNIT_BASED semantics are carried by `DayNightUnitBasedStrategy` (a strategy under the facade: fixed unit alignment + full-coverage-free).
- `DURATION_PERIOD` / `DURATION_GLOBAL` are carried by the shared `DurationPeriodStrategy` / `DurationGlobalStrategy` (declared-on support, no rule-family-private implementation needed).
- Incomplete-unit charge mode (`IncompleteUnitChargeMode`: FULL_CHARGE/PROPORTIONAL/FREE/THRESHOLD_MINUTES/THRESHOLD_RATIO) is wired into all calculation modes: CONTINUOUS/UNIT_BASED handle the truncated unit (`isTruncated` last segment), DURATION_PERIOD/DURATION_GLOBAL handle the remainder of a homogeneous segment that is short of `unitMinutes` (the integral part is always charged; only the remainder follows the mode). Default `FULL_CHARGE` ("round up to a full unit"), `PROPORTIONAL` charges proportionally.

Important query behavior:

- Query-time value may increase or decrease inside a unit because it represents "what would be charged if billing ended at this query time".

### `relativeTime`

Implemented by `RelativeTimeRule`.

Capabilities:

- Configurable relative periods inside a cycle.
- Period-specific unit length and price.
- Cycle-level cap through `maxChargeOneCycle`.
- Simplified cycle calculation support.
- CONTINUOUS mode is wired into the boundary-driven loop and produces compact units.

Current limitation:


### `compositeTime`

Implemented by `CompositeTimeRule`.

Capabilities:

- Composite periods combined with natural-time pricing.
- Period-level and cycle-level behavior.
- Cross-period handling through configured modes.
- Simplified calculation support.
- CONTINUOUS mode is wired into the boundary-driven loop and produces compact units.

Current limitation:


### `naturalTime`

Implemented by `NaturalTimeRule`.

Capabilities:

- 24-hour natural cycle, partitioned into natural periods.
- Each period has its own price with a uniform unit length.
- Configurable cross-period handling (reuses `CrossPeriodMode`).
- Daily cap through `maxChargeOneDay`.
- CONTINUOUS mode is wired into the boundary-driven loop and produces compact units.

### `flatFree`

Implemented as a rule that returns a free unit covering the requested billing window. It is implemented but may require manual registration depending on how the engine is constructed.

### Reserved Rule Constants

| Constant | Status | Notes |
|----------|--------|-------|
| `nrTimeMix` | Deprecated | Fully covered by `compositeTime` (CompositePeriod + NaturalPeriod) |
| `times` | Reserved | Per-occurrence billing for non-time scenarios; needs separate design |

---

## 6. Promotions

Implemented promotion grant types:

| Type | Meaning |
|------|---------|
| `FREE_RANGE` | Explicit free time range |
| `FREE_MINUTES` | Free minutes allocated into non-free gaps (allocated near the window start, pre-materialized) |
| `SMART_FREE_MINUTES` | Smart free minutes, consumed only in `DURATION_GLOBAL` mode; the rule side allocates them highest-price-first using `RuleSemantics.priceAt`. Non-GLOBAL modes throw. Shares the `freeMinutes` field with `FREE_MINUTES`; multiple grants allocate independently by `priority`. |

> Amount/discount (AMOUNT/DISCOUNT) has been removed from the engine; the business system settles them on the final amount.

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

`PromotionEngine` collects rule-based and external grants, then produces a `PromotionAggregate`. External promotions (`externalPromotions`) share a cross-segment pool (`ExternalPromotionPool`), used once per parking: each segment takes the remaining amount from the pool, and writes back deductions from `PromotionUsage` after the segment, not duplicated across segments. In-scheme promotions are segment-local.

Current aggregation stages:

1. Collect grants from `PromotionRuleConfig`.
2. Add external `PromotionGrant` entries from the request.
3. Merge explicit `FREE_RANGE` promotions through `FreeTimeRangeMerger`.
4. Produce a canonical intermediate form: merged `FREE_RANGE` ranges + unmaterialized `FREE_MINUTES` list (`freeMinutesList`) + `SMART_FREE_MINUTES` scalar passthrough.

`FREE_MINUTES` materialization is delegated to strategies (TODO-20260702-004): `PromotionEngine` no longer materializes centrally, avoiding the aggregation layer coupling to "rule + mode" to decide output form. CONTINUOUS/UNIT_BASED/DURATION_PERIOD strategies materialize via `RuleSupport.materializeFreeMinutes` (`FreeMinuteAllocator`), merged with `FREE_RANGE`; the DURATION_GLOBAL strategy also materializes FREE_MINUTES (allocated near the window start) and additionally consumes `SMART_FREE_MINUTES` (highest-price-first allocation using `RuleSemantics.priceAt` to split equal-price windows). `SMART_FREE_MINUTES` is passed through as a scalar (`smartFreeMinutesList`), is not materialized at the aggregate layer, and is not counted in the simplification total-free-minutes check. `PromotionUsage` (FREE_MINUTES/FREE_RANGE/SMART_FREE_MINUTES) and `PromotionCarryOver` are produced strategy-side; `PromotionCarryOver` is built via `PromotionAggregateUtil.buildCarryOver` and written back to the aggregate. Non-GLOBAL modes throw on `SMART_FREE_MINUTES` (enforced by `BillingCalculator`).

`FreeTimeRangeMerger` preserves range metadata such as priority, source, and range type.

---

## 10. Simplified Calculation

`ContinuousStrategy` (the Layer 2 shared skeleton) supports long-span simplified calculation using a "global-gap" implementation: it derives promotion-free gaps directly from `freeTimeRanges` (gaps between free ranges plus head/tail), aligns each gap to cycle boundaries, and counts covered cycles. If a gap covers more cycles than the threshold, a simplified unit is produced (`min(total chargeable, cycleCap × cycle count)`); otherwise normal detail is generated. The legacy splitting model (`splitTimeAxis`/`TimeFragment`/`organizeByCycle`/`CycleFragments`) has been removed.

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

`PromotionEquivalentCalculator` (TODO-20260706-003: moved from `billing-api` to `core` package `cn.shang.charging.billing`) computes each promotion's equivalent amount via elimination: exclude a promotion, recompute, and the delta is that promotion's equivalent amount.

- **On-demand calculation**: `BillingRequest.equivalentAmountSpec` (`EquivalentAmountSpec`, `promotionIds` + `types`, `null`=any) controls it. `null` (default) = not computed; `PromotionUsage.equivalentAmount` is `null` and `BillingResult.totalEquivalentAmount` is `null`. When non-`null`, results are filtered by spec, backfilled into `PromotionUsage.equivalentAmount` and summed into `BillingResult.totalEquivalentAmount` (same level as `finalAmount`). The strategy side no longer computes an approximation; equivalent amounts come solely from the elimination method on demand.
- **Multi-segment + external promotions**: `calculateWithContexts` replays `PromotionEngine.evaluate` (externalPool reset + per-segment evaluate + writeBack advance); `cloneAndExclude` excludes at the source layer (externalPromotions / promotionRules by id), so cross-segment dedup is replayed each elimination iteration.
- **Promotion source**: `PromotionUsage.source` (`RULE` in-scheme / `COUPON` external, etc.) is propagated from `FreeTimeRange.source` / `FreeMinutes.source`, letting callers distinguish in-scheme from external promotions.


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

- Reserved rule constants such as `times` remain unimplemented; `nrTimeMix` is deprecated and covered by `compositeTime`.
- `SMART_FREE_MINUTES` is supported only in `DURATION_GLOBAL` mode; other modes throw on it (by design, complexity is confined to GLOBAL).
- Materialized-index revenue estimation: the engine only provides the implementation surface (producing validMinutes/accumulatedAmount etc.); storage/indexing is up to the business layer (TODO-20260630-002).

---

## 15. Related Documents

| Document | Purpose |
|----------|---------|
| `docs/billing-engine-capabilities-zh.md` | Chinese version of this capability document |
| `docs/billing-engine-calculation-flow-zh.md` | Chinese calculation flow reference |
| `docs/USER_GUIDE.md` | User-facing guide |
| `docs/TODO.md` | Active backlog and issue index |
| `docs/DONE.md` | Completed backlog archive |
