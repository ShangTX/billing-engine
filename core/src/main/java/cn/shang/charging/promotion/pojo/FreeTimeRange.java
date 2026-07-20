package cn.shang.charging.promotion.pojo;

import cn.shang.charging.billing.pojo.BConstants;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 免费时间段。
 * <p>
 * 表示一段免费时间，由优惠规则（FREE_RANGE）或免费分钟数时段化（FREE_MINUTES）产出。
 * 多个免费时间段可由 {@code FreeTimeRangeMerger} 合并为互斥段，
 * 参与边界驱动循环和计费明细构建。
 */
@Accessors(chain = true)
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Data
public class FreeTimeRange {

    /** 唯一标识符（对应优惠ID，用于追踪和等效金额计算） */
    private String id;
    /** 免费时段起点（含） */
    private LocalDateTime beginTime;
    /** 免费时段终点（含） */
    private LocalDateTime endTime;
    /** 优先级（数字越小优先级越高，高优先级覆盖低优先级） */
    private int priority;

    /** 优惠类型（FREE_RANGE / FREE_MINUTES） */
    private BConstants.PromotionType promotionType;

    /**
     * 免费时间段类型：NORMAL（普通）/ BUBBLE（气泡型，延长周期边界）
     * 默认为 NORMAL
     */
    @Builder.Default
    private FreeTimeRangeType rangeType = FreeTimeRangeType.NORMAL;

    /**
     * 优惠来源：RULE（规则）/ COUPON（优惠券）
     */
    private BConstants.PromotionSource source;

    /** 生效模式，默认总是生效。 */
    @Builder.Default
    private PromotionActivationMode activationMode = PromotionActivationMode.ALWAYS;

    /** 扩展数据（业务侧自定义，不参与计费，仅透传） */
    private Object data;

    /** 检查时间段是否有效（开始时间早于结束时间，且非 null） */
    public boolean isValid() {
        return beginTime != null && endTime != null && !beginTime.isAfter(endTime);
    }

    /** 检查两个时间段是否有时间重叠 */
    public boolean overlaps(FreeTimeRange other) {
        return this.beginTime.isBefore(other.endTime) && this.endTime.isAfter(other.beginTime);
    }

    /** 获取两个时间段的重叠部分（无重叠返回 null，优先级取两者较小值） */
    public FreeTimeRange getOverlap(FreeTimeRange other) {
        if (!overlaps(other)) {
            return null;
        }
        LocalDateTime overlapBegin = this.beginTime.isAfter(other.beginTime) ? this.beginTime : other.beginTime;
        LocalDateTime overlapEnd = this.endTime.isBefore(other.endTime) ? this.endTime : other.endTime;
        return new FreeTimeRange().setBeginTime(overlapBegin).setEndTime(overlapEnd)
                .setPriority(Math.min(this.priority, other.priority));
    }

    /** 深拷贝（含 id 和 data） */
    public FreeTimeRange copy() {
        FreeTimeRange copy = new FreeTimeRange()
                .setId(id)
                .setBeginTime(beginTime)
                .setEndTime(endTime)
                .setPriority(priority)
                .setPromotionType(promotionType)
                .setRangeType(rangeType)
                .setSource(source)
                .setActivationMode(activationMode);
        copy.data = this.data;
        return copy;
    }

    /** 复制但不复制 id（用于合并时生成新段） */
    public FreeTimeRange copyWithNewId() {
        return new FreeTimeRange()
                .setBeginTime(this.beginTime)
                .setEndTime(this.endTime)
                .setPriority(this.priority)
                .setPromotionType(this.promotionType)
                .setRangeType(this.rangeType)
                .setSource(this.source)
                .setActivationMode(this.activationMode);
    }
}
