package cn.shang.billing.pojo;

import cn.shang.billing.RuleResolver;
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
    private BConstants.BillingMode mode;

    /**
     * 方案变更时间轴（只在方案切换时产生）
     */
    private List<SchemeChange> schemeChanges;

    /**
     * 规则解析器（核心）
     */
    private RuleResolver ruleResolver;

    /**
     * 已计算进度（仅缓存 / 持久化模式使用）
     */
    private BillingProgress progress;

    /**
     * 外部优惠
     */
    private List<PromotionRule> promotionRules;

    /**
     * 优惠使用统计
     */
    private PromotionUsageTracker promotionTracker;

}
