# Codebase Concerns

**Analysis Date:** 2026-03-30

## Tech Debt

**SettlementEngine - Unimplemented Stub:**
- Issue: `SettlementEngine.settle()` returns `null` instead of implementing settlement logic
- Files: `core/src/main/java/cn/shang/charging/settlement/SettlementEngine.java` (line 12)
- Impact: Settlement functionality is not available; settlement-related fields in `BillingResult` may not be populated
- Fix approach: Implement settlement calculation or remove the class if not needed

**BillingResult.of() - Incomplete Factory Method:**
- Issue: Static factory method has `// TODO` comment and returns `null`
- Files: `core/src/main/java/cn/shang/charging/billing/pojo/BillingResult.java` (line 45-46)
- Impact: Cannot convert `ChargingResult` + `SettlementResult` to `BillingResult` via factory method
- Fix approach: Implement conversion logic or remove unused method

**PromotionGrant.source Field - Needs Refactoring:**
- Issue: `source` field has TODO comment indicating it should be moved to caller side
- Files: `core/src/main/java/cn/shang/charging/promotion/pojo/PromotionGrant.java` (line 26)
- Impact: Field location may cause confusion about where source information should be managed
- Fix approach: Evaluate whether field should remain or move; update documentation

**FreeTimeRange Features - Undefined TODO:**
- Issue: Comment `// TODO 免费时间段特性` indicates planned features not yet implemented
- Files: `core/src/main/java/cn/shang/charging/promotion/pojo/FreeTimeRange.java` (line 38)
- Impact: Unknown features are pending; unclear what additional functionality is needed
- Fix approach: Define and document specific features needed, then implement

## Known Bugs

**No critical bugs detected in current code.**

The codebase uses defensive null handling throughout. The "bugs" are primarily incomplete implementations (stubs) rather than runtime defects.

## Security Considerations

**Serialization Type Safety:**
- Risk: `PromotionCarryOver.remainingMinutes` uses `Map<String, Object>` which loses type information after JSON serialization; values may become Number or String
- Files: `core/src/main/java/cn/shang/charging/billing/pojo/PromotionCarryOver.java` (lines 28-29)
- Current mitigation: `getRemainingMinutesConverted()` method provides type-safe access via `TypeConversionUtil`
- Recommendations: Document that callers should use converted getter after deserialization; consider using typed wrapper class

**TypeConversionUtil Silent Failures:**
- Risk: Utility returns `null` on conversion failures without logging or throwing exceptions
- Files: `core/src/main/java/cn/shang/charging/util/TypeConversionUtil.java` (multiple lines)
- Current mitigation: Returns null for invalid inputs; callers must check for null
- Recommendations: Consider logging warnings for unexpected conversion failures; callers should validate results

**No Authentication/Authorization in Core:**
- Risk: Core module has no authentication or authorization mechanisms
- Files: Entire `core` module
- Current mitigation: By design - core module is pure calculation engine without side effects
- Recommendations: Authentication/authorization should be implemented at API layer (billing-api) or application layer (Spring Boot starters)

**BigDecimal Precision Loss:**
- Risk: `TypeConversionUtil.toBigDecimal()` uses `doubleValue()` for Number types, which may cause precision loss for Double/Float inputs
- Files: `core/src/main/java/cn/shang/charging/util/TypeConversionUtil.java` (line 105)
- Current mitigation: Comment warns about precision loss
- Recommendations: For financial calculations, ensure input data comes from String or BigDecimal sources

## Performance Bottlenecks

**Large Rule Implementations:**
- Problem: Rule classes are very large with complex nested logic
- Files:
  - `core/src/main/java/cn/shang/charging/charge/rules/compositetime/CompositeTimeRule.java` (1678 lines)
  - `core/src/main/java/cn/shang/charging/charge/rules/relativetime/RelativeTimeRule.java` (1508 lines)
  - `core/src/main/java/cn/shang/charging/charge/rules/daynight/DayNightRule.java` (1430 lines)
- Cause: Complex time calculations, multiple calculation modes, extensive state management
- Improvement path: Consider extracting helper classes for specific calculations; document calculation flow clearly

**SuppressWarnings for Generics:**
- Problem: Extensive use of `@SuppressWarnings("unchecked")` indicates type safety issues
- Files: Multiple rule files and test files
- Cause: Generic type casting during state serialization/deserialization
- Improvement path: Consider using typed state objects instead of raw Map manipulation

## Fragile Areas

**State Serialization/Deserialization:**
- Files:
  - `core/src/main/java/cn/shang/charging/charge/rules/AbstractTimeBasedRule.java` (lines 222-320)
  - `core/src/main/java/cn/shang/charging/billing/pojo/PromotionCarryOver.java`
- Why fragile: Rule state stored in `Map<String, Object>` requires manual type conversion after JSON round-trip
- Safe modification: Use `TypeConversionUtil` for all type conversions; test serialization round-trips
- Test coverage: Serialization round-trip tests exist in `ContinueModeTest` and `PromotionCarryOverTest`

**Time Range Calculations:**
- Files: `core/src/main/java/cn/shang/charging/promotion/FreeTimeRangeMerger.java`
- Why fragile: Complex merge logic with priority handling, coverage detection, boundary references
- Safe modification: Add comprehensive test cases before modifying merge algorithms
- Test coverage: Covered by `BubbleFreeTimeRangeTest`

**CONTINUE Mode State Recovery:**
- Files:
  - `core/src/main/java/cn/shang/charging/billing/BillingService.java` (lines 105-114)
  - `core/src/main/java/cn/shang/charging/settlement/ResultAssembler.java` (lines 116-153)
- Why fragile: State recovery from `BillingCarryOver` requires correct segment ID matching and state deserialization
- Safe modification: Ensure segment IDs are stable across invocations; test state recovery thoroughly
- Test coverage: Covered by `ContinueModeTest`

## Scaling Limits

**In-Memory Calculation Only:**
- Current capacity: Single request processing; no batch support
- Limit: Large time ranges with many billing units may cause memory pressure
- Scaling path: Add batch processing support; consider streaming results for large calculations

**No Caching Built-In:**
- Current capacity: Each calculation runs fresh
- Limit: Repeated calculations for same time ranges waste computation
- Scaling path: Cache layer should be added at application layer (Spring Boot starters), not core

## Dependencies at Risk

**Lombok Dependency:**
- Risk: Heavy Lombok usage for POJOs; IDE and build tools must support Lombok annotation processing
- Impact: Build failures if Lombok not configured correctly
- Migration plan: Standard Lombok usage; no migration needed, but ensure build environment has Lombok plugin

**JDK 21 Requirement:**
- Risk: Project requires JDK 21+ (enforced via Maven Toolchains)
- Impact: Cannot run on older JDK versions
- Migration plan: If JDK 21 unavailable, consider backporting to JDK 17 with record types

## Missing Critical Features

**Settlement Calculation:**
- Problem: `SettlementEngine` is stub; no adjustment/discount/settlement logic
- Blocks: Settlement adjustments field in `BillingResult` cannot be populated via engine

**Batch Processing:**
- Problem: No batch API for processing multiple billing requests
- Blocks: High-volume scenarios require separate invocation per request

**Async/Reactive Support:**
- Problem: All calculations are synchronous blocking calls
- Blocks: Cannot integrate with reactive frameworks without wrapper implementation

## Test Coverage Gaps

**Core Module Unit Tests:**
- What's not tested: `core/src/test` directory is empty - no unit tests in core module
- Files: All core classes
- Risk: Core logic only tested via integration tests in `bill-test` module
- Priority: High - Add unit tests for core classes, especially utility classes like `TypeConversionUtil`

**Test File Organization:**
- What's not tested: Most tests are in `bill-test/src/main/java` (integration tests) instead of `bill-test/src/test/java`
- Files: `bill-test/src/main/java/cn/shang/charging/*Test.java`
- Risk: Integration tests cannot be run via standard Maven test phase; require `exec:java`
- Priority: Medium - Reorganize or document test execution approach

**BillingService Unit Tests:**
- What's not tested: No unit tests for `BillingService` orchestrator
- Files: `core/src/main/java/cn/shang/charging/billing/BillingService.java`
- Risk: Orchestration logic only tested indirectly via integration tests
- Priority: Medium

**SegmentBuilder Tests:**
- What's not tested: No dedicated tests for segment building logic
- Files: `core/src/main/java/cn/shang/charging/billing/SegmentBuilder.java`
- Risk: Scheme change handling may have edge cases not covered
- Priority: Medium

**billing-api Unit Tests:**
- What's tested: `BillingResultViewer` and `PromotionEquivalentCalculator` have unit tests
- Files: `bill-test/src/test/java/cn/shang/charging/BillingResultViewerTest.java`, `PromotionEquivalentCalculatorTest.java`
- Coverage: Good coverage for billing-api wrapper classes
- Priority: Low - Current coverage adequate

---

*Concerns audit: 2026-03-30*