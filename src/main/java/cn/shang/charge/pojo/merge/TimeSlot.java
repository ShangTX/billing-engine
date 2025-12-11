package cn.shang.charge.pojo.merge;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Accessors(chain = true)
public class TimeSlot {
    private String id;            // 唯一标识符，用于追踪
    private LocalDateTime start;
    private LocalDateTime end;
    private int priority;
    private Object data;

    // 构造方法
    public TimeSlot(LocalDateTime start, LocalDateTime end, int priority) {
        this.id = UUID.randomUUID().toString();
        this.start = start;
        this.end = end;
        this.priority = priority;
    }

    public TimeSlot(String id, LocalDateTime start, LocalDateTime end, int priority) {
        this.id = id;
        this.start = start;
        this.end = end;
        this.priority = priority;
    }

    // 复制构造方法
    public TimeSlot copy() {
        TimeSlot copy = new TimeSlot().setId(id).setStart(start).setEnd(end).setPriority(priority);
        copy.data = this.data;
        return copy;
    }

    public TimeSlot copyWithNewId() {
        return new TimeSlot().setStart(this.start).setEnd(this.end).setPriority(this.priority);
    }

    // 获取时间段持续时间（分钟）
    public long getDurationMinutes() {
        return java.time.Duration.between(start, end).toMinutes();
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
    // 检查时间段是否有效（开始时间早于结束时间）
    public boolean isValid() {
        return start != null && end != null && !start.isAfter(end);
    }

    // 检查两个时间段是否有重合
    public boolean overlaps(TimeSlot other) {
        return !this.start.isAfter(other.end) && !this.end.isBefore(other.start);
    }

    // 获取重合部分
    public TimeSlot getOverlap(TimeSlot other) {
        if (!overlaps(other)) {
            return null;
        }
        LocalDateTime overlapStart = this.start.isAfter(other.start) ? this.start : other.start;
        LocalDateTime overlapEnd = this.end.isBefore(other.end) ? this.end : other.end;
        return new TimeSlot(overlapStart, overlapEnd, Math.min(this.priority, other.priority));
    }


}
