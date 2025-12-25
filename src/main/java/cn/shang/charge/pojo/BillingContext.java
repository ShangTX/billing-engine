package cn.shang.charge.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
public class BillingContext {

    // 身份信息
    Long parkingId;
    String orderId;

    // === 时间范围 ===
    LocalDateTime beginTime;
    LocalDateTime endTime;

    // 计费方案时间轴
    // === 计费方案时间轴（关键） ===
    List<SchemeSegment> schemeTimeline;

    // === 规则 & 参数 ===
    RuleSnapshot ruleSnapshot;

    // === 计算模式 ===
    CalculationMode mode;

    // === 新增：外部优惠 ===
    List<ExternalPromotion> externalPromotions;

    // 方案分段
    @Data
    public static class SchemeSegment {
        LocalDateTime effectiveFrom;
        LocalDateTime effectiveTo;
        Long schemeId;
    }

    @Data
    public static class RuleSnapshot {

        Long pricingRuleId;
        int ruleVersion;

        // === 核心参数 ===
        Duration unitDuration;
        BigDecimal unitPrice;
        BigDecimal cycleMaxAmount;

        // === 免费相关 ===
        List<FreeTimeRule> freeTimeRules;
        int freeMinutes;

        // === 特殊规则 ===
        JsonNode irregularRuleConfig;
    }



    public enum CalculationMode {
        STATELESS,   // 完全重算
        CACHE,       // 可使用缓存
        PERSISTENCE  // 可落库
    }

    public enum PromotionType {
        FREE_MINUTES,  // 免费分钟数
        FREE_TIME_RANGE // 免费时间段
    }

    @Data
    public static class ExternalPromotion {

        String promotionId;     // 必须有，用于核销 & 审计
        PromotionType type;     // FREE_MINUTES / FREE_TIME_RANGE

        int priority;           // 优先级（解决重叠覆盖）

        // === 免费分钟数 ===
        Integer freeMinutes;    // 仅当 type == FREE_MINUTES

        // === 免费时间段 ===
        List<TimeRange> freeTimeRanges; // 仅当 type == FREE_TIME_RANGE

        // === 行为配置 ===
        boolean expireOnOveruse; // 超过即失效（你提到的那种）

    }

    // 免费时间段
    public static class FreeTimeRule {

    }

    public enum FreeSourceType {
        RULE,
        PROMOTION
    }

    /**
     * 免费时间段特性
     */
    public enum FreeTimeRangeFeature {
        COMMON,
        FULL_SKIP, // 所有计算都跳过这个免费时间段
        HOLLOW // 计费时间超过免费时长时，不再免费
    }

    // 免费时间段
    @Data
    public static class TimeRange {
        LocalDateTime beginTime;
        LocalDateTime endTime;
        String sourceId;     // ruleId / promotionId
        FreeSourceType type; // RULE / PROMOTION
        int priority;
        FreeTimeRangeFeature feature;
    }

}
