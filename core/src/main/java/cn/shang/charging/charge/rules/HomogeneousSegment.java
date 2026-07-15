package cn.shang.charging.charge.rules;

import cn.shang.charging.promotion.pojo.FreeTimeRangeType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 同质计费段：一次边界驱动的最小产出。
 * <p>
 * 表示从 {@code beginTime}（含）到 {@code endTime}（不含）的一段时间，
 * 在此区间内所有计费参数（单价、免费状态、规则特化数据）保持一致。
 * 子类可扩展特化字段。
 */
public class HomogeneousSegment {

    /** 段起点（含） */
    private final LocalDateTime beginTime;
    /** 段终点（不含） */
    private final LocalDateTime endTime;
    /** 单元单价（免费段为 0） */
    private final BigDecimal unitPrice;
    /** 单元素始金额（按规则原价，封顶前；CONTINUOUS 免费段设 0，收费段 = unitPrice） */
    private final BigDecimal originalAmount;
    /** 是否免费段 */
    private final boolean free;
    /** 免费段对应的 FreeTimeRange.id（非免费段为 null） */
    private final String freePromotionId;
    /** 免费时段类型：NORMAL（普通，占用周期）/ BUBBLE（气泡型，不占用周期）；仅免费段有意义，收费段始终 NORMAL */
    private final FreeTimeRangeType rangeType;
    /** 规则特化数据（透传，不参与计费；可由子类或策略扩展使用） */
    private final Object ruleData;

    public HomogeneousSegment(LocalDateTime beginTime,
                              LocalDateTime endTime,
                              BigDecimal unitPrice,
                              BigDecimal originalAmount,
                              boolean free,
                              String freePromotionId,
                              Object ruleData) {
        this(beginTime, endTime, unitPrice, originalAmount, free, freePromotionId, FreeTimeRangeType.NORMAL, ruleData);
    }

    public HomogeneousSegment(LocalDateTime beginTime,
                              LocalDateTime endTime,
                              BigDecimal unitPrice,
                              BigDecimal originalAmount,
                              boolean free,
                              String freePromotionId,
                              FreeTimeRangeType rangeType,
                              Object ruleData) {
        this.beginTime = beginTime;
        this.endTime = endTime;
        this.unitPrice = unitPrice;
        this.originalAmount = originalAmount;
        this.free = free;
        this.freePromotionId = freePromotionId;
        this.rangeType = rangeType != null ? rangeType : FreeTimeRangeType.NORMAL;
        this.ruleData = ruleData;
    }

    public LocalDateTime getBeginTime() {
        return beginTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getOriginalAmount() {
        return originalAmount;
    }

    public boolean isFree() {
        return free;
    }

    public String getFreePromotionId() {
        return freePromotionId;
    }

    public FreeTimeRangeType getRangeType() {
        return rangeType;
    }

    /** 是否为 bubble 免费段（不占用周期时长）。 */
    public boolean isBubble() {
        return free && rangeType == FreeTimeRangeType.BUBBLE;
    }

    public Object getRuleData() {
        return ruleData;
    }

    public int durationMinutes() {
        return (int) java.time.Duration.between(beginTime, endTime).toMinutes();
    }

    /**
     * 同质合并判定：除时间窗外所有字段（单价、原始金额、免费状态、freePromotionId、rangeType、ruleData）一致才可合并。
     * 子类可覆盖以加入特化字段。
     */
    public boolean canMergeWith(HomogeneousSegment other) {
        if (other == null) return false;
        if (free != other.free) return false;
        if (!Objects.equals(freePromotionId, other.freePromotionId)) return false;
        if (!Objects.equals(unitPrice, other.unitPrice)) return false;
        if (!Objects.equals(originalAmount, other.originalAmount)) return false;
        if (rangeType != other.rangeType) return false;
        if (!Objects.equals(ruleData, other.ruleData)) return false;
        // 时间连续：other.beginTime == this.endTime
        return other.beginTime.equals(this.endTime);
    }
}
