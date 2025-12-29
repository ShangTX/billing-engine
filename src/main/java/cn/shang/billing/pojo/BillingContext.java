package cn.shang.billing.pojo;

import cn.shang.billing.BillingSegment;
import cn.shang.billing.RuleResolver;
import cn.shang.promotion.pojo.ExternalPromotionInput;
import cn.shang.promotion.pojo.PromotionContribution;
import cn.shang.promotion.pojo.PromotionRule;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
public class BillingContext {
    // 计费id
    private String id;

    // 开始结束时间
    private LocalDateTime beginTime;
    private LocalDateTime endTime;

    /**
     * 三种模式：STATELESS / CACHE / PERSIST
     */
    private BConstants.BillingMode billingMode;

    /**
     * 分段
     */
    private BillingSegment segment;

    /**
     * 计算窗口
     */
    private CalculationWindow window;


    /**
     * 已计算进度（仅缓存 / 持久化模式使用）
     */
    private BillingProgress progress;

    /**
     * 优惠规则
     */
    private List<PromotionRuleSnapshot> promotionRules;

    /**
     * 计费规则
     */
    private RuleSnapshot chargingRule;

    /**
     * 外部优惠
     */
    private List<PromotionContribution> externalPromotions;




}
