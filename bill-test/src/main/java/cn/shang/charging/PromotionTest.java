package cn.shang.charging;

import cn.shang.charging.billing.BillingCalculator;
import cn.shang.charging.billing.BillingService;
import cn.shang.charging.billing.RuleResolver;
import cn.shang.charging.billing.SegmentBuilder;
import cn.shang.charging.billing.pojo.BConstants;
import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.PromotionRuleSnapshot;
import cn.shang.charging.billing.pojo.RuleSnapshot;
import cn.shang.charging.charge.util.JacksonUtils;
import cn.shang.charging.promotion.FreeMinuteAllocator;
import cn.shang.charging.promotion.FreeTimeRangeMerger;
import cn.shang.charging.promotion.PromotionEngine;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import cn.shang.charging.settlement.ResultAssembler;
import org.springframework.cglib.core.Local;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Month;
import java.util.ArrayList;
import java.util.List;

public class PromotionTest {

    /**
     * 优惠聚合测试
     */
    static void main() {

        var billingService = getBillingService();
        var request = new BillingRequest();
        request.setId("23");
        request.setBillingMode(BConstants.BillingMode.STATELESS);
        request.setBeginTime(LocalDateTime.of(2026, Month.JANUARY, 1, 0, 0, 0));
        request.setEndTime(LocalDateTime.of(2026, Month.JANUARY, 2, 12, 0, 0));
        request.setSchemeChanges(
                List.of()
        );
        request.setSegmentCalculationMode(BConstants.SegmentCalculationMode.SEGMENT_LOCAL);
        request.setSchemeId("12");
        request.setExternalPromotions(getExternalPromotions());

        var result = billingService.calculate(request);
        System.out.println(JacksonUtils.toJsonString(result));
    }

    static BillingService getBillingService() {
        var ruleResolver = new RuleResolver() {

            @Override
            public RuleSnapshot resolveChargingRule(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                return null;
            }

            @Override
            public List<PromotionRuleSnapshot> resolvePromotionRules(String schemeId, LocalDateTime segmentStart, LocalDateTime segmentEnd) {
                return List.of();
            }
        };

        var promotionEngine = new PromotionEngine(
                ruleResolver,
                new FreeTimeRangeMerger(),
                new FreeMinuteAllocator()
        );

        return new BillingService(
                new SegmentBuilder(),
                ruleResolver,
                promotionEngine,
                new BillingCalculator(),
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
