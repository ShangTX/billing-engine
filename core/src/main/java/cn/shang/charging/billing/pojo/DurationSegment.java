package cn.shang.charging.billing.pojo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 时长计费段（Duration-Based Billing Mode 的产出）
 * <p>
 * 时长模式将时间轴视为连续的分钟流，按时段类型分组，每段记录收费分钟数。
 * 与 BillingUnit（单元模式）不同，DurationSegment 反映"按分钟计费"的语义。
 */
public record DurationSegment(
    LocalDateTime beginTime,     // 段起点
    LocalDateTime endTime,       // 段终点
    int chargedMinutes,          // 实际收费分钟数（免费段=0）
    BigDecimal unitPrice,        // 完整单元单价（用于查询投影）
    BigDecimal chargedAmount,    // = unitPrice × chargedMinutes / unitMinutes
    String freePromotionId,      // 免费段记录优惠 ID
    Object ruleData              // 规则扩展数据
) {}
