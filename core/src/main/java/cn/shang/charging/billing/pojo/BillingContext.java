package cn.shang.charging.billing.pojo;

import cn.shang.charging.billing.BillingSegment;
import cn.shang.charging.promotion.pojo.PromotionGrant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

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
     * 继续模式：是否从上次结果继续计算
     */
    private BConstants.ContinueMode continueMode;

    /**
     * 计费模式：计费单位如何划分
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
     * 外部优惠
     */
    private List<PromotionGrant> externalPromotions;

    /**
     * 从 carryOver 恢复的规则状态
     * key: 规则类型（如 "relativeTime", "dayNight"）
     * value: 规则自定义的状态结构
     */
    private Map<String, Object> ruleState;

    /**
     * 优惠规则
     */
    private List<PromotionRuleConfig> promotionRules;

    /**
     * 计费规则
     */
    private RuleConfig chargingRule;

}
