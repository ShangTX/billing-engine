package cn.shang.promotion;

import cn.shang.billing.pojo.BillingContext;
import cn.shang.promotion.pojo.PromotionAggregate;
import cn.shang.promotion.pojo.PromotionContext;
import cn.shang.promotion.pojo.PromotionContribution;
import cn.shang.promotion.pojo.PromotionRule;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 优惠计算engine
 */
@Service
public class PromotionEngine {


    public PromotionAggregate evaluate(BillingContext context) {

        PromotionContext promotionContext = buildPromotionContext(context);

        // 1. 从方案中解析出【规则内优惠】
        List<PromotionContribution> ruleContributions =
                loadPromotionRules(promotionContext);

        // 2. 从外部输入中解析【外部优惠】
        List<PromotionContribution> externalContributions =
                loadExternalPromotions(promotionContext);

        List<PromotionContribution> contributions = new ArrayList<>();

        // 外部优惠
        for (PromotionRule rule : context.getPromotionRules()) {
            contributions.add(rule.contribute(context));
        }
        // 规则优惠
        // 3. 合并所有优惠（优先级 + 截断 + 去重）
        PromotionAggregate aggregate =
                PromotionAggregator.aggregate(
                        ruleContributions,
                        externalContributions,
                        promotionContext
                );

        return aggregate;
    }

    private PromotionContext buildPromotionContext(BillingContext ctx) {

        return new PromotionContext(
                ctx.getEntryTime(),
                ctx.getExitTime(),
                ctx.getCurrentSchemeId(),
                ctx.getBillingCycleType(),
                ctx.getExternalPromotionParams()
        );
        return PromotionContext.builder()
                .beginTime(ctx.getBeginTime())
                .endTime(ctx.getEndTime())
                .schemeId(ctx.getSC)
                .build();
    }

}
