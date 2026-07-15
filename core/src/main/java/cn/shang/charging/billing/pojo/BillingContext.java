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

/**
 * 计费上下文。
 * <p>
 * 引擎内部流转对象，包含单个分段计算所需的所有信息。
 * 由 {@code BillingService.resolveSegmentContext} 构建，传递给 {@code BillingCalculator} 执行计费。
 */
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Data
public class BillingContext {
    /** 计费ID（来自 BillingRequest.id） */
    private String id;

    /** 计费开始时间（来自 BillingRequest.beginTime） */
    private LocalDateTime beginTime;

    /** 计费结束时间（来自 BillingRequest.endTime） */
    private LocalDateTime endTime;

    /**
     * 计算模式：CONTINUOUS（连续时间）/ UNIT_BASED（固定单元）/ DURATION_PERIOD（周期时长）/ DURATION_GLOBAL（全局时长）
     */
    private BConstants.CalculationMode calculationMode;

    /** 当前分段（方案层，含分段起止时间和方案ID） */
    private BillingSegment segment;

    /** 计算窗口（实际参与规则计算的时间范围，分段起点截取） */
    private CalculationWindow window;

    /** 本段可用外部优惠列表（从 ExternalPromotionPool.remaining() 获取） */
    private List<PromotionGrant> externalPromotions;

    /** 本段优惠规则配置列表（由 BillingConfigResolver 解析） */
    private List<PromotionRuleConfig> promotionRules;

    /** 本段计费规则配置（由 BillingConfigResolver 解析） */
    private RuleConfig chargingRule;

    /** 计费配置解析器引用（策略侧可按需调用 getSimplifiedCycleThreshold 等） */
    private BillingConfigResolver billingConfigResolver;

    /** 是否禁用简化计算（精确查询时设为 true，保证输出完整明细） */
    private Boolean disableSimplification;

}
