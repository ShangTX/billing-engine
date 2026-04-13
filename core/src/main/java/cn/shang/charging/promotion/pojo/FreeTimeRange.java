package cn.shang.charging.promotion.pojo;

import cn.shang.charging.billing.pojo.BConstants;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 免费时间段
 */
@Accessors(chain = true)
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Data
public class FreeTimeRange {

    private String id;            // 唯一标识符，用于追踪
    private LocalDateTime beginTime;
    private LocalDateTime endTime;
    private int priority;

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

    private Object data; // 其他数据

    // TODO 免费时间段特性

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    // 检查时间段是否有效（开始时间早于结束时间）
    public boolean isValid() {
        return beginTime != null && endTime != null && !beginTime.isAfter(endTime);
    }

    // 检查两个时间段是否有重合
    public boolean overlaps(FreeTimeRange other) {
        return this.beginTime.isBefore(other.endTime) && this.endTime.isAfter(other.beginTime);
    }

    // 获取重合部分
    public FreeTimeRange getOverlap(FreeTimeRange other) {
        if (!overlaps(other)) {
            return null;
        }
        LocalDateTime overlapBegin = this.beginTime.isAfter(other.beginTime) ? this.beginTime : other.beginTime;
        LocalDateTime overlapEnd = this.endTime.isBefore(other.endTime) ? this.endTime : other.endTime;
        return new FreeTimeRange().setBeginTime(overlapBegin).setEndTime(overlapEnd)
                .setPriority(Math.min(this.priority, other.priority));
    }

    // 复制构造方法
    public FreeTimeRange copy() {
        FreeTimeRange copy = new FreeTimeRange()
                .setId(id)
                .setBeginTime(beginTime)
                .setEndTime(endTime)
                .setPriority(priority)
                .setPromotionType(promotionType)
                .setRangeType(rangeType)
                .setSource(source);
        copy.data = this.data;
        return copy;
    }

    public FreeTimeRange copyWithNewId() {
        return new FreeTimeRange()
                .setBeginTime(this.beginTime)
                .setEndTime(this.endTime)
                .setPriority(this.priority)
                .setPromotionType(this.promotionType)
                .setRangeType(this.rangeType)
                .setSource(this.source);
    }
}
