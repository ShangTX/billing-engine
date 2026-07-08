package cn.shang.charging.promotion.pojo;

import cn.shang.charging.billing.pojo.BConstants;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 可计算的优惠授予对象。
 * <p>
 * 描述一笔具体的优惠（时间段免费、分钟数免费）。
 * 由优惠规则（PromotionRule.grant）或外部优惠（BillingRequest.externalPromotions）产出，
 * 最终被 {@code PromotionEngine} 聚合为 {@code PromotionAggregate}。
 */
@Data
@Builder
@Accessors(chain = true)
@AllArgsConstructor
@NoArgsConstructor
public class PromotionGrant {

    /** 优惠ID（唯一标识，用于追踪） */
    String id;

    /** 优惠类型：FREE_RANGE / FREE_MINUTES / SMART_FREE_MINUTES */
    BConstants.PromotionType type;

    /** 优惠来源：RULE（方案内规则）/ COUPON（外部优惠券） */
    BConstants.PromotionSource source;

    /** 优惠时间段起点（FREE_RANGE/外部优惠券适用） */
    LocalDateTime beginTime;

    /** 优惠时间段终点（FREE_RANGE/外部优惠券适用） */
    LocalDateTime endTime;

    /** 免费分钟数（FREE_MINUTES/SMART_FREE_MINUTES 适用） */
    Integer freeMinutes;

    /** 优先级（数字越小优先级越高，控制多优惠叠加时的处理顺序） */
    Integer priority;

    /**
     * 免费时间段类型：NORMAL（普通）/ BUBBLE（气泡型，延长周期边界）
     * 仅对 FREE_RANGE 类型有效
     */
    FreeTimeRangeType rangeType;

}
