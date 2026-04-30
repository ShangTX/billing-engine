package cn.shang.charging.generator;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.PromotionRuleConfig;
import cn.shang.charging.billing.pojo.RuleConfig;
import cn.shang.charging.billing.pojo.TimeRoundingMode;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.charge.rules.daynight.DayNightRule;
import cn.shang.charging.promotion.FreeMinuteAllocator;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.pojo.FreeTimeRangeType;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionConfig;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionRule;
import cn.shang.charging.promotion.rules.startfree.StartFreePromotionConfig;
import cn.shang.charging.promotion.rules.startfree.StartFreePromotionRule;
import cn.shang.charging.settlement.ResultAssembler;
import cn.shang.charging.wrapper.BillingResultViewer;
import cn.shang.charging.wrapper.QuerySummary;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Generates billing result samples for manual judgment.
 * <p>
 * The first version uses realistic time-window templates plus small deterministic jitter.
 * It intentionally does not produce expected amounts.
 */
public class BillingTestCaseGenerator {

    private static final String DEFAULT_SCHEME_ID = "generated-scheme";
    private static final LocalDateTime BASE_DAY = LocalDateTime.of(2026, 4, 20, 0, 0);

    public List<GeneratedBillingCase> generate(TestGenerationRequest generationRequest) {
        validate(generationRequest);

        Random random = new Random(generationRequest.getSeed());
        List<GeneratedBillingCase> cases = new ArrayList<>();
        for (int i = 0; i < generationRequest.getCount(); i++) {
            cases.add(generateCase(generationRequest, i, random));
        }
        return cases;
    }

    private GeneratedBillingCase generateCase(TestGenerationRequest generationRequest, int index, Random random) {
        Set<TestFeature> features = normalizeFeatures(generationRequest.getFeatures());
        DayNightConfig ruleConfig = createDayNightConfig(features, index);
        List<PromotionRuleConfig> promotionConfigs = createPromotionConfigs(features, index);
        BConstants.BillingMode billingMode = selectBillingMode(features);
        BillingService billingService = createBillingService(ruleConfig, promotionConfigs, billingMode, features);
        BillingResultViewer viewer = new BillingResultViewer();

        BillingRequest request = createRequest("case-" + (index + 1), features, index, random);
        BillingResult result;
        List<QuerySummary> querySummaries;
        List<GeneratedContinueStep> continueSteps = new ArrayList<>();

        if (features.contains(TestFeature.CONTINUE)) {
            ContinueScenario scenario = calculateContinueScenario(request, billingService, viewer, features);
            result = scenario.finalResult();
            querySummaries = scenario.finalQuerySummaries();
            continueSteps = scenario.steps();
            request = scenario.finalRequest();
        } else {
            result = billingService.calculate(request);
            querySummaries = createQuerySummaries(result, viewer, features);
        }

        return GeneratedBillingCase.builder()
                .caseId(request.getId())
                .chargeRuleType(generationRequest.getChargeRuleType())
                .features(features)
                .request(request)
                .ruleConfig(ruleConfig)
                .promotionConfigs(promotionConfigs)
                .externalPromotions(request.getExternalPromotions())
                .result(result)
                .querySummaries(querySummaries)
                .continueSteps(continueSteps)
                .build();
    }

    private void validate(TestGenerationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("generation request must not be null");
        }
        if (!BConstants.ChargeRuleType.DAY_NIGHT.equals(request.getChargeRuleType())) {
            throw new IllegalArgumentException("first generator version only supports dayNight");
        }
        if (request.getCount() <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
    }

    private Set<TestFeature> normalizeFeatures(Set<TestFeature> requestedFeatures) {
        Set<TestFeature> features = new LinkedHashSet<>();
        if (requestedFeatures != null) {
            features.addAll(requestedFeatures);
        }
        if (!features.contains(TestFeature.UNIT_BASED) && !features.contains(TestFeature.CONTINUOUS)) {
            features.add(selectDefaultModeFeature(features));
        }
        if (!features.contains(TestFeature.SEGMENT_LOCAL) && !features.contains(TestFeature.GLOBAL_ORIGIN)) {
            features.add(TestFeature.SEGMENT_LOCAL);
        }
        if (features.contains(TestFeature.MULTI_PROMOTION)) {
            features.add(TestFeature.FREE_MINUTES);
            features.add(TestFeature.EXTERNAL_FREE_RANGE);
        }
        if (features.contains(TestFeature.CONDITIONAL_START_FREE)) {
            features.add(TestFeature.START_FREE);
            features.add(TestFeature.QUERY_TIME);
        }
        return features;
    }

    private TestFeature selectDefaultModeFeature(Set<TestFeature> features) {
        if (features.contains(TestFeature.BUBBLE_FREE_RANGE) || features.contains(TestFeature.SIMPLIFICATION)) {
            return TestFeature.CONTINUOUS;
        }
        return TestFeature.UNIT_BASED;
    }

    private DayNightConfig createDayNightConfig(Set<TestFeature> features, int index) {
        BigDecimal maxChargeOneDay = features.contains(TestFeature.DAY_NIGHT_DAILY_CAP)
                ? new BigDecimal("6.00")
                : new BigDecimal("50.00");
        return DayNightConfig.builder()
                .id("generated-day-night-" + (index + 1))
                .dayBeginMinute(8 * 60)
                .dayEndMinute(19 * 60)
                .unitMinutes(60)
                .blockWeight(new BigDecimal("0.5"))
                .dayUnitPrice(new BigDecimal("2.00"))
                .nightUnitPrice(new BigDecimal("1.00"))
                .maxChargeOneDay(maxChargeOneDay)
                .simplifiedSupported(features.contains(TestFeature.SIMPLIFICATION) ? Boolean.TRUE : null)
                .build();
    }

    private List<PromotionRuleConfig> createPromotionConfigs(Set<TestFeature> features, int index) {
        List<PromotionRuleConfig> configs = new ArrayList<>();
        if (features.contains(TestFeature.FREE_MINUTES)) {
            configs.add(FreeMinutesPromotionConfig.builder()
                    .id("rule-free-minutes-" + (index + 1))
                    .type(BConstants.PromotionRuleType.FREE_MINUTES)
                    .priority(10)
                    .minutes(30 + index % 3 * 15)
                    .build());
        }
        if (features.contains(TestFeature.START_FREE)) {
            configs.add(StartFreePromotionConfig.builder()
                    .id("rule-start-free-" + (index + 1))
                    .priority(5)
                    .minutes(60)
                    .validateQueryTime(features.contains(TestFeature.CONDITIONAL_START_FREE))
                    .build());
        }
        return configs;
    }

    private BillingRequest createRequest(String caseId, Set<TestFeature> features, int index, Random random) {
        TimeWindow timeWindow = selectTimeWindow(features, index, random);
        BillingRequest request = new BillingRequest();
        request.setId(caseId);
        request.setBeginTime(timeWindow.begin());
        request.setEndTime(timeWindow.end());
        request.setSchemeId(DEFAULT_SCHEME_ID);
        request.setSchemeChanges(List.of());
        request.setSegmentCalculationMode(features.contains(TestFeature.GLOBAL_ORIGIN)
                ? BConstants.SegmentCalculationMode.GLOBAL_ORIGIN
                : BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setExternalPromotions(createExternalPromotions(features, timeWindow, index));
        if (features.contains(TestFeature.TIME_ROUNDING)) {
            request.setTimeRoundingMode(TimeRoundingMode.CEIL_BEGIN_TRUNCATE_END);
        }
        return request;
    }

    private TimeWindow selectTimeWindow(Set<TestFeature> features, int index, Random random) {
        LocalDateTime day = BASE_DAY.plusDays(index);
        int minuteNoise = random.nextInt(11);

        if (features.contains(TestFeature.SIMPLIFICATION)) {
            return new TimeWindow(day.withHour(8).withMinute(30), day.plusDays(4 + index % 2).withHour(18).withMinute(20));
        }
        if (features.contains(TestFeature.DAY_NIGHT_CROSS_PERIOD_UNIT)
                || features.contains(TestFeature.DAY_NIGHT_MIXED_VALUE_SPEC)) {
            LocalDateTime begin = day.withHour(18).withMinute(40 + minuteNoise % 10);
            return new TimeWindow(begin, day.withHour(22).withMinute(20 + minuteNoise % 5));
        }
        if (features.contains(TestFeature.BUBBLE_FREE_RANGE)) {
            return new TimeWindow(day.withHour(8).withMinute(30 + minuteNoise), day.withHour(18).withMinute(30));
        }
        if (features.contains(TestFeature.CONTINUE)) {
            return new TimeWindow(day.withHour(8).withMinute(15 + minuteNoise), day.withHour(18).withMinute(30));
        }
        if (index % 4 == 0) {
            return new TimeWindow(day.withHour(8).withMinute(30 + minuteNoise), day.withHour(18).withMinute(30));
        }
        if (index % 4 == 1) {
            return new TimeWindow(day.withHour(17).withMinute(45 + minuteNoise), day.withHour(22).withMinute(20));
        }
        if (index % 4 == 2) {
            return new TimeWindow(day.withHour(21).withMinute(30 + minuteNoise), day.plusDays(1).withHour(8).withMinute(40));
        }
        return new TimeWindow(day.withHour(0).withMinute(10 + minuteNoise), day.withHour(23).withMinute(50));
    }

    private List<PromotionGrant> createExternalPromotions(Set<TestFeature> features, TimeWindow timeWindow, int index) {
        List<PromotionGrant> promotions = new ArrayList<>();
        if (features.contains(TestFeature.EXTERNAL_FREE_RANGE) || features.contains(TestFeature.MULTI_PROMOTION)) {
            LocalDateTime begin = timeWindow.begin().plusHours(2).withMinute(0).withSecond(0).withNano(0);
            promotions.add(PromotionGrant.builder()
                    .id("external-free-range-" + (index + 1))
                    .type(BConstants.PromotionType.FREE_RANGE)
                    .source(BConstants.PromotionSource.COUPON)
                    .priority(20)
                    .beginTime(begin)
                    .endTime(begin.plusMinutes(45))
                    .rangeType(FreeTimeRangeType.NORMAL)
                    .build());
        }
        if (features.contains(TestFeature.BUBBLE_FREE_RANGE)) {
            LocalDateTime begin = timeWindow.begin().plusHours(3).withMinute(0).withSecond(0).withNano(0);
            promotions.add(PromotionGrant.builder()
                    .id("bubble-free-range-" + (index + 1))
                    .type(BConstants.PromotionType.FREE_RANGE)
                    .source(BConstants.PromotionSource.COUPON)
                    .priority(15)
                    .beginTime(begin)
                    .endTime(begin.plusMinutes(60))
                    .rangeType(FreeTimeRangeType.BUBBLE)
                    .build());
        }
        if (features.contains(TestFeature.EXTERNAL_FREE_MINUTES)) {
            promotions.add(PromotionGrant.builder()
                    .id("external-free-minutes-" + (index + 1))
                    .type(BConstants.PromotionType.FREE_MINUTES)
                    .source(BConstants.PromotionSource.COUPON)
                    .priority(30)
                    .freeMinutes(45)
                    .build());
        }
        return promotions;
    }

    private ContinueScenario calculateContinueScenario(
            BillingRequest fullRequest,
            BillingService billingService,
            BillingResultViewer viewer,
            Set<TestFeature> features) {
        LocalDateTime splitTime = fullRequest.getBeginTime()
                .plusMinutes(java.time.Duration.between(fullRequest.getBeginTime(), fullRequest.getEndTime()).toMinutes() / 2);

        BillingRequest firstRequest = copyRequest(fullRequest);
        firstRequest.setId(fullRequest.getId() + "-continue-1");
        firstRequest.setEndTime(splitTime);
        BillingResult firstResult = billingService.calculate(firstRequest);

        BillingRequest secondRequest = copyRequest(fullRequest);
        secondRequest.setId(fullRequest.getId() + "-continue-2");
        secondRequest.setPreviousCarryOver(firstResult.getCarryOver());
        BillingResult secondResult = billingService.calculate(secondRequest);

        List<GeneratedContinueStep> steps = List.of(
                GeneratedContinueStep.builder()
                        .stepId(firstRequest.getId())
                        .request(firstRequest)
                        .result(firstResult)
                        .querySummaries(createQuerySummaries(firstResult, viewer, features))
                        .build(),
                GeneratedContinueStep.builder()
                        .stepId(secondRequest.getId())
                        .request(secondRequest)
                        .result(secondResult)
                        .querySummaries(createQuerySummaries(secondResult, viewer, features))
                        .build()
        );
        return new ContinueScenario(secondRequest, secondResult, createQuerySummaries(secondResult, viewer, features), steps);
    }

    private List<QuerySummary> createQuerySummaries(BillingResult result, BillingResultViewer viewer, Set<TestFeature> features) {
        if (!features.contains(TestFeature.QUERY_TIME) || result == null || result.getUnits() == null || result.getUnits().isEmpty()) {
            return List.of();
        }

        LocalDateTime firstBegin = result.getUnits().get(0).getBeginTime();
        LocalDateTime calculationEnd = result.getCalculationEndTime();
        LocalDateTime middle = firstBegin.plusMinutes(
                Math.max(1, java.time.Duration.between(firstBegin, calculationEnd).toMinutes() / 2)
        );
        List<LocalDateTime> candidates = List.of(
                firstBegin.plusMinutes(1),
                middle,
                calculationEnd.minusMinutes(1).isAfter(firstBegin) ? calculationEnd.minusMinutes(1) : calculationEnd
        );

        List<QuerySummary> summaries = new ArrayList<>();
        for (LocalDateTime queryTime : candidates) {
            if (queryTime.isAfter(firstBegin) && !queryTime.isAfter(calculationEnd)) {
                summaries.add(viewer.createQuerySummary(result, queryTime));
            }
        }
        return summaries;
    }

    private BillingService createBillingService(
            DayNightConfig ruleConfig,
            List<PromotionRuleConfig> promotionConfigs,
            BConstants.BillingMode billingMode,
            Set<TestFeature> features) {
        BillingConfigResolver resolver = new StaticResolver(ruleConfig, promotionConfigs, billingMode, features);

        PromotionRuleRegistry promotionRegistry = new PromotionRuleRegistry();
        promotionRegistry.register(BConstants.PromotionRuleType.FREE_MINUTES, new FreeMinutesPromotionRule());
        promotionRegistry.register(BConstants.PromotionRuleType.START_FREE, new StartFreePromotionRule());

        BillingRuleRegistry ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(BConstants.ChargeRuleType.DAY_NIGHT, new DayNightRule());

        return new BillingService(
                new SegmentBuilder(),
                resolver,
                new PromotionEngine(resolver, new FreeTimeRangeMerger(), new FreeMinuteAllocator(), promotionRegistry),
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );
    }

    private BConstants.BillingMode selectBillingMode(Set<TestFeature> features) {
        return features.contains(TestFeature.CONTINUOUS)
                ? BConstants.BillingMode.CONTINUOUS
                : BConstants.BillingMode.UNIT_BASED;
    }

    private BillingRequest copyRequest(BillingRequest source) {
        BillingRequest copied = new BillingRequest();
        copied.setId(source.getId());
        copied.setBeginTime(source.getBeginTime());
        copied.setEndTime(source.getEndTime());
        copied.setCalcEndTime(source.getCalcEndTime());
        copied.setExternalPromotions(source.getExternalPromotions());
        copied.setSegmentCalculationMode(source.getSegmentCalculationMode());
        copied.setSchemeId(source.getSchemeId());
        copied.setSchemeChanges(source.getSchemeChanges());
        copied.setPreviousCarryOver(source.getPreviousCarryOver());
        copied.setTimeRoundingMode(source.getTimeRoundingMode());
        copied.setContext(source.getContext());
        copied.setDisableSimplification(source.getDisableSimplification());
        return copied;
    }

    private record TimeWindow(LocalDateTime begin, LocalDateTime end) {
    }

    private record ContinueScenario(
            BillingRequest finalRequest,
            BillingResult finalResult,
            List<QuerySummary> finalQuerySummaries,
            List<GeneratedContinueStep> steps) {
    }

    private static class StaticResolver implements BillingConfigResolver {
        private final DayNightConfig ruleConfig;
        private final List<PromotionRuleConfig> promotionConfigs;
        private final BConstants.BillingMode billingMode;
        private final Set<TestFeature> features;

        StaticResolver(
                DayNightConfig ruleConfig,
                List<PromotionRuleConfig> promotionConfigs,
                BConstants.BillingMode billingMode,
                Set<TestFeature> features) {
            this.ruleConfig = ruleConfig;
            this.promotionConfigs = promotionConfigs;
            this.billingMode = billingMode;
            this.features = features;
        }

        @Override
        public BConstants.BillingMode resolveBillingMode(String schemeId, Map<String, Object> context) {
            return billingMode;
        }

        @Override
        public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
            return ruleConfig;
        }

        @Override
        public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd, Map<String, Object> context) {
            return promotionConfigs;
        }

        @Override
        public int getSimplifiedCycleThreshold() {
            return features.contains(TestFeature.SIMPLIFICATION) ? 2 : 0;
        }
    }
}
