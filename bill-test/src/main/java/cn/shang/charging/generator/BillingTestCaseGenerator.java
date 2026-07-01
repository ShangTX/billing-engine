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
import cn.shang.charging.charge.rules.compositetime.CompositePeriod;
import cn.shang.charging.charge.rules.compositetime.CompositeTimeConfig;
import cn.shang.charging.charge.rules.compositetime.CompositeTimeRule;
import cn.shang.charging.charge.rules.compositetime.CrossPeriodMode;
import cn.shang.charging.charge.rules.compositetime.NaturalPeriod;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.charge.rules.daynight.DayNightRule;
import cn.shang.charging.charge.rules.flatfree.FlatFreeConfig;
import cn.shang.charging.charge.rules.flatfree.FlatFreeRule;
import cn.shang.charging.charge.rules.naturaltime.NaturalTimeConfig;
import cn.shang.charging.charge.rules.naturaltime.NaturalTimeRule;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeConfig;
import cn.shang.charging.charge.rules.relativetime.RelativeTimePeriod;
import cn.shang.charging.charge.rules.relativetime.RelativeTimeRule;
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
 * 计费结果样本生成器。
 * <p>
 * 该工具面向人工校验：它负责按规则类型和功能点组合生成计费请求、执行计费并输出结果，
 * 但不会生成预期金额。第一版先支持日夜计费规则，并通过真实场景时间模板加少量确定性扰动
 * 生成更接近业务现场的开始/结束时间。
 */
public class BillingTestCaseGenerator {

    private static final String DEFAULT_SCHEME_ID = "generated-scheme";
    private static final LocalDateTime BASE_DAY = LocalDateTime.of(2026, 4, 20, 0, 0);

    /**
     * 根据生成请求批量生成计费结果样本。
     *
     * @param generationRequest 生成参数，包含规则类型、功能点、数量和随机种子
     * @return 可直接序列化为 JSON 的计费结果样本
     */
    public List<GeneratedBillingCase> generate(TestGenerationRequest generationRequest) {
        validate(generationRequest);

        Random random = new Random(generationRequest.getSeed());
        List<GeneratedBillingCase> cases = new ArrayList<>();
        for (int i = 0; i < generationRequest.getCount(); i++) {
            cases.add(generateCase(generationRequest, i, random));
        }
        return cases;
    }

    /**
     * 生成单个样本：先规范化功能点，再创建规则、优惠、请求和计费服务。
     */
    private GeneratedBillingCase generateCase(TestGenerationRequest generationRequest, int index, Random random) {
        Set<TestFeature> features = normalizeFeatures(generationRequest.getFeatures());
        String ruleType = generationRequest.getChargeRuleType();
        RuleConfig ruleConfig = createRuleConfig(ruleType, features, index);
        List<PromotionRuleConfig> promotionConfigs = createPromotionConfigs(features, index);
        BConstants.BillingMode billingMode = selectBillingMode(features);
        BillingService billingService = createBillingService(ruleType, ruleConfig, promotionConfigs, billingMode, features);
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
                .chargeRuleType(ruleType)
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

    /**
     * 校验生成器参数。
     */
    private void validate(TestGenerationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("generation request must not be null");
        }
        String ruleType = request.getChargeRuleType();
        if (!BConstants.ChargeRuleType.DAY_NIGHT.equals(ruleType)
                && !BConstants.ChargeRuleType.RELATIVE_TIME.equals(ruleType)
                && !BConstants.ChargeRuleType.NATURAL_TIME.equals(ruleType)
                && !BConstants.ChargeRuleType.COMPOSITE_TIME.equals(ruleType)
                && !BConstants.ChargeRuleType.FLAT_FREE.equals(ruleType)) {
            throw new IllegalArgumentException("unsupported rule type: " + ruleType);
        }
        if (request.getCount() <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
    }

    /**
     * 根据用户传入的功能点补齐必要的派生功能点，减少使用者配置负担。
     */
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

    /**
     * 未显式指定计费模式时，根据功能点选择更自然的默认模式。
     */
    private TestFeature selectDefaultModeFeature(Set<TestFeature> features) {
        if (features.contains(TestFeature.BUBBLE_FREE_RANGE)
                || features.contains(TestFeature.SIMPLIFICATION)
                || features.contains(TestFeature.COMPACT)) {
            return TestFeature.CONTINUOUS;
        }
        return TestFeature.UNIT_BASED;
    }

    /**
     * 根据规则类型创建规则配置。
     */
    private RuleConfig createRuleConfig(String ruleType, Set<TestFeature> features, int index) {
        if (BConstants.ChargeRuleType.DAY_NIGHT.equals(ruleType)) {
            return createDayNightConfig(features, index);
        }
        if (BConstants.ChargeRuleType.RELATIVE_TIME.equals(ruleType)) {
            return createRelativeTimeConfig(features, index);
        }
        if (BConstants.ChargeRuleType.NATURAL_TIME.equals(ruleType)) {
            return createNaturalTimeConfig(features, index);
        }
        if (BConstants.ChargeRuleType.COMPOSITE_TIME.equals(ruleType)) {
            return createCompositeTimeConfig(features, index);
        }
        if (BConstants.ChargeRuleType.FLAT_FREE.equals(ruleType)) {
            return createFlatFreeConfig(features, index);
        }
        throw new IllegalArgumentException("unsupported rule type: " + ruleType);
    }

    /**
     * 构造日夜计费规则配置。
     * <p>
     * 当前使用固定的白天/夜间边界和价格，功能点只影响封顶、简化计算等关键开关。
     */
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
                .crossPeriodMode(CrossPeriodMode.BLOCK_WEIGHT)
                .dayUnitPrice(new BigDecimal("2.00"))
                .nightUnitPrice(new BigDecimal("1.00"))
                .maxChargeOneDay(maxChargeOneDay)
                .simplifiedSupported(features.contains(TestFeature.SIMPLIFICATION) ? Boolean.TRUE : null)
                .build();
    }

    /**
     * 构造相对时间计费规则配置。
     */
    private RelativeTimeConfig createRelativeTimeConfig(Set<TestFeature> features, int index) {
        BigDecimal maxChargeOneCycle = features.contains(TestFeature.RELATIVE_CYCLE_CAP)
                ? new BigDecimal("10.00")
                : new BigDecimal("100.00");
        return RelativeTimeConfig.builder()
                .id("generated-relative-time-" + (index + 1))
                .periods(List.of(
                        RelativeTimePeriod.builder()
                                .beginMinute(0)
                                .endMinute(120)
                                .unitMinutes(30)
                                .unitPrice(new BigDecimal("2.00"))
                                .build(),
                        RelativeTimePeriod.builder()
                                .beginMinute(120)
                                .endMinute(480)
                                .unitMinutes(60)
                                .unitPrice(new BigDecimal("3.00"))
                                .build(),
                        RelativeTimePeriod.builder()
                                .beginMinute(480)
                                .endMinute(1440)
                                .unitMinutes(60)
                                .unitPrice(new BigDecimal("1.50"))
                                .build()
                ))
                .maxChargeOneCycle(maxChargeOneCycle)
                .simplifiedSupported(features.contains(TestFeature.SIMPLIFICATION) ? Boolean.TRUE : null)
                .build();
    }

    /**
     * 构造自然时间计费规则配置。
     */
    private NaturalTimeConfig createNaturalTimeConfig(Set<TestFeature> features, int index) {
        return NaturalTimeConfig.builder()
                .id("generated-natural-time-" + (index + 1))
                .unitMinutes(60)
                .crossPeriodMode(features.contains(TestFeature.COMPOSITE_CROSS_PERIOD_MODE)
                        ? CrossPeriodMode.HIGHER_PRICE : CrossPeriodMode.BEGIN_TIME_TRUNCATE)
                .periods(List.of(
                        NaturalPeriod.builder().beginMinute(0).endMinute(360).unitPrice(new BigDecimal("1.00")).build(),
                        NaturalPeriod.builder().beginMinute(360).endMinute(720).unitPrice(new BigDecimal("2.00")).build(),
                        NaturalPeriod.builder().beginMinute(720).endMinute(1080).unitPrice(new BigDecimal("1.50")).build(),
                        NaturalPeriod.builder().beginMinute(1080).endMinute(1440).unitPrice(new BigDecimal("1.00")).build()
                ))
                .maxChargeOneDay(new BigDecimal("20.00"))
                .simplifiedSupported(features.contains(TestFeature.SIMPLIFICATION) ? Boolean.TRUE : null)
                .build();
    }

    /**
     * 构造组合时间计费规则配置。
     */
    private CompositeTimeConfig createCompositeTimeConfig(Set<TestFeature> features, int index) {
        return CompositeTimeConfig.builder()
                .id("generated-composite-time-" + (index + 1))
                .maxChargeOneCycle(new BigDecimal("30.00"))
                .periods(List.of(
                        CompositePeriod.builder()
                                .beginMinute(0)
                                .endMinute(1440)
                                .unitMinutes(60)
                                .crossPeriodMode(features.contains(TestFeature.COMPOSITE_CROSS_PERIOD_MODE)
                                        ? CrossPeriodMode.HIGHER_PRICE : CrossPeriodMode.BEGIN_TIME_TRUNCATE)
                                .naturalPeriods(List.of(
                                        NaturalPeriod.builder().beginMinute(0).endMinute(480).unitPrice(new BigDecimal("1.00")).build(),
                                        NaturalPeriod.builder().beginMinute(480).endMinute(1200).unitPrice(new BigDecimal("2.00")).build(),
                                        NaturalPeriod.builder().beginMinute(1200).endMinute(1440).unitPrice(new BigDecimal("1.00")).build()
                                ))
                                .build()
                ))
                .simplifiedSupported(features.contains(TestFeature.SIMPLIFICATION) ? Boolean.TRUE : null)
                .build();
    }

    /**
     * 构造统一免费计费规则配置。
     */
    private FlatFreeConfig createFlatFreeConfig(Set<TestFeature> features, int index) {
        return FlatFreeConfig.builder()
                .id("generated-flat-free-" + (index + 1))
                .build();
    }

    /**
     * 构造规则型优惠配置，例如免费分钟数和起始免费。
     */
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

    /**
     * 构造计费请求，并把外部优惠、分段模式和时间取整功能点写入请求。
     */
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

    /**
     * 根据功能点选择真实场景时间窗口。
     * <p>
     * 优先满足更强约束的功能点，例如简化计算、多天长停、跨日夜边界和气泡免费时段。
     */
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
        if (features.contains(TestFeature.COMPACT)) {
            // 纯白天长窗口（8:00-16:00）：DayNight 60min 单元同价产出 1 个 count=8 的 compact；
            // RelativeTime 单时段产出 1 个 compact；NaturalTime 跨两个自然时段产出 2 个 compact。
            return new TimeWindow(day.withHour(8).withMinute(0), day.withHour(16).withMinute(0));
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

    /**
     * 构造外部优惠。
     * <p>
     * 气泡免费时段会贴近真实计费窗口中部，便于观察它对周期边界和 CONTINUE 状态的影响。
     */
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

    /**
     * 执行 CONTINUE 场景。
     * <p>
     * 第一步计算到中间时间并产出 carryOver，第二步携带 carryOver 继续算到原始结束时间。
     */
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

    /**
     * 根据计费结果生成查询摘要。
     * <p>
     * 查询点覆盖开头附近、中间位置和计算结束前，便于人工观察单元内 valueSpec 的即时值。
     */
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

    /**
     * 为样本装配一套纯内存 BillingService。
     */
    private BillingService createBillingService(
            String ruleType,
            RuleConfig ruleConfig,
            List<PromotionRuleConfig> promotionConfigs,
            BConstants.BillingMode billingMode,
            Set<TestFeature> features) {
        BillingConfigResolver resolver = new StaticResolver(ruleConfig, promotionConfigs, billingMode, features);

        PromotionRuleRegistry promotionRegistry = new PromotionRuleRegistry();
        promotionRegistry.register(BConstants.PromotionRuleType.FREE_MINUTES, new FreeMinutesPromotionRule());
        promotionRegistry.register(BConstants.PromotionRuleType.START_FREE, new StartFreePromotionRule());

        BillingRuleRegistry ruleRegistry = new BillingRuleRegistry();

        return new BillingService(
                new SegmentBuilder(),
                resolver,
                new PromotionEngine(resolver, new FreeTimeRangeMerger(), new FreeMinuteAllocator(), promotionRegistry),
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );
    }

    /**
     * 从功能点选择实际计费模式。
     * UNIT_BASED 已降级为独立规则类型，普通规则只支持 CONTINUOUS；
     * 生成器统一产出 CONTINUOUS 用例（TestFeature.UNIT_BASED 保留为兼容标记，按 CONTINUOUS 生成）。
     */
    private BConstants.BillingMode selectBillingMode(Set<TestFeature> features) {
        return BConstants.BillingMode.CONTINUOUS;
    }

    /**
     * 复制可变请求对象，避免 CONTINUE 两步计算互相污染。
     */
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

    /**
     * 真实业务时间窗口。
     */
    private record TimeWindow(LocalDateTime begin, LocalDateTime end) {
    }

    /**
     * CONTINUE 场景的中间结果。
     */
    private record ContinueScenario(
            BillingRequest finalRequest,
            BillingResult finalResult,
            List<QuerySummary> finalQuerySummaries,
            List<GeneratedContinueStep> steps) {
    }

    /**
     * 静态配置解析器。
     * <p>
     * 生成器不接入数据库或外部服务，所有规则和优惠都由当前样本直接提供。
     */
    private static class StaticResolver implements BillingConfigResolver {
        private final RuleConfig ruleConfig;
        private final List<PromotionRuleConfig> promotionConfigs;
        private final BConstants.BillingMode billingMode;
        private final Set<TestFeature> features;

        StaticResolver(
                RuleConfig ruleConfig,
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
