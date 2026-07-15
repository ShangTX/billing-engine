package cn.shang.charging.wrapper;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.PromotionEquivalentCalculator;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.billing.pojo.EquivalentAmountSpec;
import cn.shang.charging.billing.pojo.PromotionRuleConfig;
import cn.shang.charging.billing.pojo.SchemeChange;
import cn.shang.charging.billing.pojo.TimeRoundingMode;
import cn.shang.charging.charge.rules.BillingRule;
import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.flatfree.FlatFreeRule;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.rules.PromotionRule;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionRule;
import cn.shang.charging.promotion.rules.startfree.StartFreePromotionRule;
import cn.shang.charging.settlement.ResultAssembler;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight billing facade for application integration.
 * <p>
 * The core module owns billing semantics. This facade only provides convenient
 * component assembly, request normalization, and compatibility helpers.
 */
public class BillingTemplate {

    private final BillingService billingService;
    private final BillingConfigResolver configResolver;
    private final PromotionEquivalentCalculator promotionEquivalentCalculator;

    public BillingTemplate(BillingService billingService,
                           BillingConfigResolver configResolver) {
        this.billingService = billingService;
        this.configResolver = configResolver;
        this.promotionEquivalentCalculator = new PromotionEquivalentCalculator(billingService);
    }

    /**
     * Creates a builder that assembles default core components.
     */
    public static Builder builder(BillingConfigResolver configResolver) {
        return new Builder(configResolver);
    }

    /**
     * Creates a template with default core components.
     */
    public static BillingTemplate create(BillingConfigResolver configResolver) {
        return builder(configResolver).build();
    }

    /**
     * Calculates with a normalized copy of the request.
     * The original request object is not mutated.
     */
    public BillingResult calculate(BillingRequest request) {
        return calculate(request, TimeRoundingMode.CEIL_BEGIN_TRUNCATE_END);
    }

    /**
     * Calculates with a normalized copy of the request.
     * <p>
     * {@code roundingMode} is retained for source compatibility. Current facade
     * normalization always truncates seconds; business-specific rounding should
     * be applied by callers through {@link TimeRounding} before building the request.
     */
    public BillingResult calculate(BillingRequest request, TimeRoundingMode roundingMode) {
        return billingService.calculate(normalize(request, roundingMode));
    }

    /**
     * Calls core directly without normalization or request copying.
     */
    public BillingResult calculateRaw(BillingRequest request) {
        return billingService.calculate(request);
    }

    /**
     * Returns a normalized copy of the request.
     */
    public BillingRequest normalize(BillingRequest request) {
        return normalize(request, TimeRoundingMode.CEIL_BEGIN_TRUNCATE_END);
    }

    /**
     * Returns a normalized copy of the request.
     * <p>
     * {@code roundingMode} is retained for source compatibility; all timestamps
     * are currently truncated to minute precision.
     */
    public BillingRequest normalize(BillingRequest request, TimeRoundingMode roundingMode) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        BillingRequest normalized = copyRequest(request);
        if (normalized.getBeginTime() != null) {
            normalized.setBeginTime(TimeRounding.truncate(normalized.getBeginTime()));
        }
        if (normalized.getEndTime() != null) {
            normalized.setEndTime(TimeRounding.truncate(normalized.getEndTime()));
        }
        if (normalized.getCalcEndTime() != null) {
            normalized.setCalcEndTime(TimeRounding.truncate(normalized.getCalcEndTime()));
        }
        roundExternalPromotions(normalized);
        return normalized;
    }

    /**
     * Rounds external FREE_RANGE promotion timestamps.
     */
    private void roundExternalPromotions(BillingRequest request) {
        if (request.getExternalPromotions() == null) {
            return;
        }
        for (PromotionGrant grant : request.getExternalPromotions()) {
            if (grant.getType() != BConstants.PromotionType.FREE_RANGE) {
                continue;
            }
            if (grant.getBeginTime() != null) {
                grant.setBeginTime(TimeRounding.truncate(grant.getBeginTime()));
            }
            if (grant.getEndTime() != null) {
                grant.setEndTime(TimeRounding.truncate(grant.getEndTime()));
            }
        }
    }

    public BillingConfigResolver getConfigResolver() {
        return configResolver;
    }

    /**
     * Calculates promotion equivalent amounts using the core calculator.
     */
    public Map<String, BigDecimal> calculatePromotionEquivalents(BillingRequest request) {
        return promotionEquivalentCalculator.calculate(normalize(request));
    }

    private BillingRequest copyRequest(BillingRequest source) {
        BillingRequest target = new BillingRequest();
        target.setId(source.getId());
        target.setBeginTime(source.getBeginTime());
        target.setEndTime(source.getEndTime());
        target.setCalcEndTime(source.getCalcEndTime());
        target.setExternalPromotions(copyPromotionGrants(source.getExternalPromotions()));
        target.setSegmentCalculationMode(source.getSegmentCalculationMode());
        target.setSchemeId(source.getSchemeId());
        target.setSchemeChanges(copySchemeChanges(source.getSchemeChanges()));
        target.setTimeRoundingMode(source.getTimeRoundingMode());
        target.setContext(source.getContext() == null ? null : new HashMap<>(source.getContext()));
        target.setDisableSimplification(source.getDisableSimplification());
        target.setEquivalentAmountSpec(copyEquivalentAmountSpec(source.getEquivalentAmountSpec()));
        return target;
    }

    private List<PromotionGrant> copyPromotionGrants(List<PromotionGrant> source) {
        if (source == null) {
            return null;
        }
        List<PromotionGrant> copy = new ArrayList<>(source.size());
        for (PromotionGrant grant : source) {
            copy.add(PromotionGrant.builder()
                    .id(grant.getId())
                    .type(grant.getType())
                    .source(grant.getSource())
                    .beginTime(grant.getBeginTime())
                    .endTime(grant.getEndTime())
                    .freeMinutes(grant.getFreeMinutes())
                    .priority(grant.getPriority())
                    .rangeType(grant.getRangeType())
                    .activationMode(grant.getActivationMode())
                    .build());
        }
        return copy;
    }

    private List<SchemeChange> copySchemeChanges(List<SchemeChange> source) {
        if (source == null) {
            return null;
        }
        List<SchemeChange> copy = new ArrayList<>(source.size());
        for (SchemeChange change : source) {
            SchemeChange item = new SchemeChange();
            item.setLastSchemeId(change.getLastSchemeId());
            item.setNextSchemeId(change.getNextSchemeId());
            item.setChangeTime(change.getChangeTime());
            copy.add(item);
        }
        return copy;
    }

    private EquivalentAmountSpec copyEquivalentAmountSpec(EquivalentAmountSpec source) {
        if (source == null) {
            return null;
        }
        return EquivalentAmountSpec.builder()
                .promotionIds(source.getPromotionIds() == null ? null : Set.copyOf(source.getPromotionIds()))
                .types(source.getTypes() == null ? null : Set.copyOf(source.getTypes()))
                .build();
    }

    /**
     * Builder for the default integration facade.
     */
    public static class Builder {
        private final BillingConfigResolver configResolver;
        private final BillingRuleRegistry billingRuleRegistry = new BillingRuleRegistry();
        private final PromotionRuleRegistry promotionRuleRegistry = new PromotionRuleRegistry();
        private SegmentBuilder segmentBuilder = new SegmentBuilder();
        private FreeTimeRangeMerger freeTimeRangeMerger = new FreeTimeRangeMerger();
        private ResultAssembler resultAssembler = new ResultAssembler();

        private Builder(BillingConfigResolver configResolver) {
            if (configResolver == null) {
                throw new IllegalArgumentException("configResolver must not be null");
            }
            this.configResolver = configResolver;
            this.billingRuleRegistry.register(BConstants.ChargeRuleType.FLAT_FREE, new FlatFreeRule());
            this.promotionRuleRegistry.register(BConstants.PromotionRuleType.FREE_MINUTES, new FreeMinutesPromotionRule());
            this.promotionRuleRegistry.register(BConstants.PromotionRuleType.START_FREE, new StartFreePromotionRule());
        }

        public Builder registerBillingRule(String ruleType, BillingRule<?> rule) {
            this.billingRuleRegistry.register(ruleType, rule);
            return this;
        }

        public <C extends PromotionRuleConfig> Builder registerPromotionRule(String type, PromotionRule<C> rule) {
            this.promotionRuleRegistry.register(type, rule);
            return this;
        }

        public Builder segmentBuilder(SegmentBuilder segmentBuilder) {
            this.segmentBuilder = segmentBuilder;
            return this;
        }

        public Builder freeTimeRangeMerger(FreeTimeRangeMerger freeTimeRangeMerger) {
            this.freeTimeRangeMerger = freeTimeRangeMerger;
            return this;
        }

        public Builder resultAssembler(ResultAssembler resultAssembler) {
            this.resultAssembler = resultAssembler;
            return this;
        }

        public BillingTemplate build() {
            PromotionEngine promotionEngine = new PromotionEngine(
                    configResolver, freeTimeRangeMerger, promotionRuleRegistry);
            BillingService billingService = new BillingService(
                    segmentBuilder,
                    configResolver,
                    promotionEngine,
                    new BillingCalculator(billingRuleRegistry),
                    resultAssembler);
            return new BillingTemplate(billingService, configResolver);
        }
    }
}
