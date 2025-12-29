package cn.shang.promotion.pojo;

import cn.shang.billing.pojo.BConstants;
import cn.shang.charge.pojo.merge.TimeSlot;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 免费时间段
 */
@Accessors(chain = true)
@Data
public class FreeTimeRange {

    private String id;            // 唯一标识符，用于追踪
    private LocalDateTime beginTime;
    private LocalDateTime endTime;
    private int priority;

    private BConstants.PromotionType promotionType;

    private Object data; // 其他数据

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
        return !this.beginTime.isAfter(other.endTime) && !this.endTime.isBefore(other.beginTime);
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
        FreeTimeRange copy = new FreeTimeRange().setId(id).setBeginTime(beginTime).setEndTime(endTime).setPriority(priority);
        copy.data = this.data;
        return copy;
    }

    public FreeTimeRange copyWithNewId() {
        return new FreeTimeRange().setBeginTime(this.beginTime).setEndTime(this.endTime).setPriority(this.priority);
    }
}
