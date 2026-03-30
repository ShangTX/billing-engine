# Testing Patterns

**Analysis Date:** 2026-03-30

## Test Framework

**Runner:**
- JUnit 5 (Jupiter) version 5.10.0
- Config: Maven Surefire Plugin version 3.1.2

**Assertion Library:**
- JUnit 5 built-in assertions (`org.junit.jupiter.api.Assertions`)
- Static imports: `assertEquals`, `assertNotNull`, `assertSame`, `assertTrue`, `assertNull`

**Run Commands:**
```bash
mvn test                              # Run all tests
mvn test -pl bill-test                # Run tests in bill-test module
mvn exec:java -pl bill-test -Dexec.mainClass="cn.shang.charging.DayNightTest"  # Run integration test
```

## Test File Organization

**Location:**
- Unit tests: `bill-test/src/test/java/` (co-located with test module)
- Integration tests: `bill-test/src/main/java/` (executable main methods)

**Naming:**
- Test files: `{ClassName}Test.java`
- Example: `BillingResultViewerTest.java`, `PromotionEquivalentCalculatorTest.java`

**Structure:**
```
bill-test/
├── src/main/java/cn/shang/charging/     # Integration tests (main method)
│   ├── DayNightTest.java
│   ├── RelativeTimeTest.java
│   ├── PromotionTest.java
│   └── ...
└── src/test/java/cn/shang/charging/     # Unit tests (JUnit)
    ├── BillingResultViewerTest.java
    ├── PromotionEquivalentCalculatorTest.java
    └── test/TypeConversionUtilTest.java
```

## Test Structure

**Suite Organization:**
```java
// From BillingResultViewerTest.java
class BillingResultViewerTest {

    private final BillingResultViewer viewer = new BillingResultViewer();

    @Test
    void testViewAtTime_nullQueryTime_returnsOriginal() {
        BillingResult result = BillingResult.builder()
            .finalAmount(BigDecimal.TEN)
            .build();

        BillingResult view = viewer.viewAtTime(result, null);

        assertSame(result, view);
    }

    @Test
    void testViewAtTime_filtersUnits() {
        // Test implementation
    }
}
```

**Patterns:**
- Setup: Field initialization or `@BeforeEach` method
- Test naming: `test{MethodName}_{Condition}_{ExpectedResult}`
- Arrange-Act-Assert pattern

**Setup Pattern:**
```java
// From PromotionEquivalentCalculatorTest.java
@BeforeEach
void setUp() {
    billingService = mock(BillingService.class);
    calculator = new PromotionEquivalentCalculator(billingService);
}
```

## Mocking

**Framework:** Mockito version 5.10.0

**Patterns:**
```java
// Static import for Mockito
import static org.mockito.Mockito.*;

// Create mock
billingService = mock(BillingService.class);

// Stub methods
when(billingService.prepareContexts(request)).thenReturn(List.of());
when(billingService.calculateWithContexts(any(), eq(request))).thenReturn(result);

// Mock nested objects
SegmentContext context = SegmentContext.builder()
    .promotionAggregate(mock(PromotionAggregate.class))
    .build();
```

**What to Mock:**
- External dependencies: `BillingService`, `BillingConfigResolver`
- Complex objects not under test: `PromotionAggregate`

**What NOT to Mock:**
- POJOs under test (use builder to create)
- Value objects: `BillingResult`, `BillingUnit`, `PromotionUsage`

## Fixtures and Factories

**Test Data:**
```java
// Create test data using builders
BillingResult result = BillingResult.builder()
    .units(List.of(unit1, unit2, unit3))
    .finalAmount(BigDecimal.valueOf(3))
    .build();

BillingUnit unit1 = BillingUnit.builder()
    .beginTime(t8).endTime(t9).chargedAmount(BigDecimal.ONE).build();
```

**Location:**
- Test data created inline within test methods
- No separate fixture files or factory classes

**Integration Test Data:**
```java
// From DayNightTest.java - create complete service setup
static BillingService getBillingService(BConstants.BillingMode billingMode) {
    var billingConfigResolver = new BillingConfigResolver() {
        @Override
        public BConstants.BillingMode resolveBillingMode(String schemeId, Map<String, Object> context) {
            return billingMode;
        }
        // ... other methods
    };
    // Build complete service pipeline
    return new BillingService(...);
}
```

## Coverage

**Requirements:** None explicitly enforced

**Coverage Tool:** Not detected in Maven configuration

## Test Types

**Unit Tests:**
- Focus on single class/method behavior
- Mock external dependencies
- Located in `src/test/java/`
- Example: `TypeConversionUtilTest.java` tests utility methods

**Integration Tests:**
- Test complete billing pipeline
- Create real service instances with mock resolvers
- Located in `src/main/java/` with `main()` method
- Example: `DayNightTest.java`, `PromotionTest.java`

**E2E Tests:** Not used

## Common Patterns

**Async Testing:**
- Not applicable - no async operations in core module

**Error Testing:**
```java
// From TypeConversionUtilTest.java
@Test
void testToLocalDateTime_fromNull() {
    assertNull(TypeConversionUtil.toLocalDateTime(null));
}

@Test
void testToBigDecimal_fromString() {
    BigDecimal result = TypeConversionUtil.toBigDecimal("123.456");
    assertNotNull(result);
    assertEquals(0, new BigDecimal("123.456").compareTo(result));
}
```

**Builder-Based Test Construction:**
```java
// Create complex objects fluently
PromotionUsage usage = PromotionUsage.builder()
    .promotionId("promo1")
    .type(BConstants.PromotionType.FREE_RANGE)
    .usedFrom(LocalDateTime.of(2024, 1, 1, 9, 0))
    .usedTo(LocalDateTime.of(2024, 1, 1, 10, 0))
    .build();
```

**Mock Return Sequence:**
```java
// From PromotionEquivalentCalculatorTest.java
when(billingService.calculateWithContexts(any(), eq(request)))
    .thenReturn(withPromo)
    .thenReturn(withoutPromo);
```

## Integration Test Pattern

**Test Runner Pattern:**
```java
// From DayNightTest.java
public class DayNightTest {
    public static void main(String[] args) {
        System.out.println("========== 日夜分时段计费测试 ==========\n");

        testSingleCycle();
        testCrossCycle();
        testCap();
        // ...
    }

    static void testSingleCycle() {
        var billingService = getBillingService(BConstants.BillingMode.CONTINUOUS);
        var request = new BillingRequest();
        // Setup request...

        var result = billingService.calculate(request);

        System.out.println("结果: finalAmount = " + result.getFinalAmount());
        System.out.println(JacksonUtils.toJsonString(result));
    }
}
```

**Service Setup Pattern:**
```java
// Inline BillingConfigResolver implementation
var billingConfigResolver = new BillingConfigResolver() {
    @Override
    public RuleConfig resolveChargingRule(...) {
        return new DayNightConfig()
            .setId("daynight-1")
            .setBlockWeight(new BigDecimal("0.5"))
            .setDayBeginMinute(740)
            .setDayEndMinute(1140)
            .setDayUnitPrice(new BigDecimal("2"))
            .setNightUnitPrice(new BigDecimal("1"))
            .setMaxChargeOneDay(new BigDecimal("100"))
            .setUnitMinutes(60);
    }
};
```

## Assertions Style

**Equality Assertions:**
```java
assertEquals(2, view.getUnits().size());
assertEquals(BigDecimal.valueOf(2), view.getFinalAmount());
assertEquals(0, new BigDecimal("123.456").compareTo(result));  // BigDecimal comparison
```

**Reference Assertions:**
```java
assertSame(input, result);  // Same object reference
```

**Null Assertions:**
```java
assertNull(view.getCarryOver());
assertNotNull(result);
```

**Boolean Assertions:**
```java
assertTrue(equivalents.isEmpty());
```

**Floating Point Assertions:**
```java
assertEquals(123.456, result.doubleValue(), 0.0001);  // Delta for precision
```

## Test Naming Conventions

**Method Names:**
- Pattern: `test{MethodName}_{Scenario}_{ExpectedResult}`
- Examples:
  - `testViewAtTime_nullQueryTime_returnsOriginal`
  - `testCalculate_noPromotions_returnsEmptyMap`
  - `testCalculate_singlePromotion`
  - `testCalculate_negativeEquivalent_clampedToZero`

**Test Class Names:**
- `{ClassName}Test.java` for unit tests
- `{Feature}Test.java` for integration tests (e.g., `DayNightTest.java`, `PromotionTest.java`)

## Testing Utilities

**JSON Output:**
```java
// From bill-test module
System.out.println(JacksonUtils.toJsonString(result));
```

**Time Creation:**
```java
LocalDateTime t8 = LocalDateTime.of(2024, 1, 1, 8, 0);
LocalDateTime t9 = LocalDateTime.of(2024, 1, 1, 9, 0);
```

---

*Testing analysis: 2026-03-30*