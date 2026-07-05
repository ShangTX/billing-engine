package cn.shang.charging.billing.pojo;

import cn.shang.charging.billing.BillingConfigResolver;
import cn.shang.charging.billing.BillingSegment;
import cn.shang.charging.promotion.pojo.PromotionGrant;
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
     * 计费模式：计费单位如何划分
     */
    private BConstants.BillingMode billingMode;

    /**
     * 时长计费模式：是否按分钟流计费
     */
    private BConstants.DurationMode durationMode;

    /**
     * 分段
     */
    private BillingSegment segment;

    /**
     * 计算窗口
     */
    private CalculationWindow window;

    /**
     * 外部优惠
     */
    private List<PromotionGrant> externalPromotions;

    /**
     * 优惠规则
     */
    private List<PromotionRuleConfig> promotionRules;

    /**
     * 计费规则
     */
    private RuleConfig chargingRule;

    /**
     * 计费配置解析器
     */
    private BillingConfigResolver billingConfigResolver;

    /**
     * 是否在精确查询重算时禁用 simplification
     */
    private Boolean disableSimplification;

}
