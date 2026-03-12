package cn.shang.charging;

import cn.shang.charging.charge.rules.BillingRuleRegistry;
import cn.shang.charging.charge.rules.daynight.DayNightConfig;
import cn.shang.charging.charge.rules.daynight.DayNightRule;
import cn.shang.charging.charge.util.JacksonUtils;
import cn.shang.charging.promotion.FreeMinuteAllocator;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.promotion.rules.PromotionRuleRegistry;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionConfig;
import cn.shang.charging.promotion.rules.minutes.FreeMinutesPromotionRule;
import cn.shang.charging.promotion.rules.ranges.FreeTimeRangePromotionRule;
import cn.shang.charging.settlement.ResultAssembler;
import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;

public class PromotionTest {

    /**
     * 优惠聚合测试
     */
    public static void main(String[] args) {

        var billingService = getBillingService();
        var request = new BillingRequest();
        request.setId("23");
        request.setBeginTime(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.JANUARY, 2, 12, 0, 0));
        request.setSchemeChanges(
                List.of()
        );
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("12");
        request.setExternalPromotions(getExternalPromotions());

        System.out.println("=== Request ===");
        System.out.println(JacksonUtils.toJsonString(request));
        System.out.println();

        var result = billingService.calculate(request);
        System.out.println("=== Result ===");
        System.out.println(JacksonUtils.toJsonString(result));
    }

    static BillingService getBillingService() {
        var billingConfigResolver = new BillingConfigResolver() {

            @Override
            public BConstants.BillingMode resolveBillingMode(String schemeId) {
                return BConstants.BillingMode.CONTINUOUS;
            }

            @Override
            public RuleConfig resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                return new DayNightConfig().setId("20").setBlockWeight(new BigDecimal("0.5"))
                        .setDayBeginMinute(740).setDayEndMinute(1140).setDayUnitPrice(new BigDecimal("2"))
                        .setNightUnitPrice(new BigDecimal("1")).setMaxChargeOneDay(new BigDecimal("20"))
                        .setUnitMinutes(60);
            }

            @Override
            public List<PromotionRuleConfig> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                return List.of(
                        new FreeMinutesPromotionConfig().setId("fm").setPriority(1).setMinutes(30)
                );
            }
        };

        var promotionRegistry = new PromotionRuleRegistry();
        promotionRegistry.register(BConstants.PromotionRuleType.FREE_TIME_RANGE, new FreeTimeRangePromotionRule());
        promotionRegistry.register(BConstants.PromotionRuleType.FREE_MINUTES, new FreeMinutesPromotionRule());

        var promotionEngine = new PromotionEngine(
                billingConfigResolver,
                new FreeTimeRangeMerger(),
                new FreeMinuteAllocator(),
                promotionRegistry
        );

        var ruleRegistry = new BillingRuleRegistry();
        ruleRegistry.register(BConstants.ChargeRuleType.DAY_NIGHT, new DayNightRule());

        return new BillingService(
                new SegmentBuilder(),
                billingConfigResolver,
                promotionEngine,
                new BillingCalculator(ruleRegistry),
                new ResultAssembler()
        );
    }

    static List<PromotionGrant> getExternalPromotions() {
        return new ArrayList<PromotionGrant>(List.of(
                PromotionGrant.builder().id("21").type(BConstants.PromotionType.FREE_MINUTES)
                        .priority(2).source(BConstants.PromotionSource.COUPON)
                        .freeMinutes(30)
                        .build(),
                PromotionGrant.builder().id("22").type(BConstants.PromotionType.FREE_MINUTES)
                        .priority(1).source(BConstants.PromotionSource.COUPON)
                        .freeMinutes(40)
                        .build(),

                PromotionGrant.builder().id("152").type(BConstants.PromotionType.FREE_RANGE)
                        .priority(2).source(BConstants.PromotionSource.COUPON)
                        .beginTime(LocalDateTime.of(2026, Month.JANUARY, 1, 1, 0, 0))
                        .endTime(LocalDateTime.of(2026, Month.JANUARY, 1, 4, 0, 0))
                        .build(),
                PromotionGrant.builder().id("821").type(BConstants.PromotionType.FREE_RANGE)
                        .priority(1).source(BConstants.PromotionSource.COUPON)
                        .beginTime(LocalDateTime.of(2026, Month.JANUARY, 1, 3, 0, 0))
                        .endTime(LocalDateTime.of(2026, Month.JANUARY, 1, 7, 0, 0))
                        .build()
        ));
    }
}
