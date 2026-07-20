package cn.shang.charging.promotion.pojo;

import cn.shang.charging.billing.pojo.BConstants;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 优惠聚合结果（中间形式）。
 * <p>
 * 由 {@code PromotionEngine.evaluate()} 产出，是免费时段、免费分钟数的组合快照。
 * 引擎以本对象为输入，策略侧按需消费（CONTINUOUS/UNIT_BASED 时段化 FREE_MINUTES，
 * DURATION_GLOBAL 额外支持价格感知的 FREE_MINUTES 分配模式）。
 * <p>
 * 本对象为规范中间形式：FREE_RANGE 已合并，FREE_MINUTES 未时段化（策略侧负责）。
 * <p>
 * AMOUNT/DISCOUNT（金额减免/折扣）已移出引擎，由业务系统在最终金额上自行结算。
 */
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
public class PromotionAggregate {

    /** 已合并的免费时间段列表（仅 FREE_RANGE，FREE_MINUTES 不在此处时段化） */
    List<FreeTimeRange> freeTimeRanges;
    /** 总免费分钟数（= freeMinutesList 求和，供简化计算快速判定用） */
    long freeMinutes;
    /** 未时段化的 FREE_MINUTES 列表（策略侧调用 FreeMinuteAllocator 时段化） */
    List<FreeMinutes> freeMinutesList;

    /** 等效金额（仅统计，不参与计费计算；可由策略侧按需设置） */
    BigDecimal equivalentAmount;

    // ==================== 优惠类型判断方法 ====================

    /**
     * 是否为空（无优惠）
     */
    public boolean isEmpty() {
        return (freeTimeRanges == null || freeTimeRanges.isEmpty())
                && freeMinutes <= 0
                && (freeMinutesList == null || freeMinutesList.isEmpty());
    }

    /**
     * 是否有多种优惠类型
     */
    public boolean hasMultiplePromotionTypes() {
        Set<BConstants.PromotionType> types = new HashSet<>();
        if (freeTimeRanges != null && !freeTimeRanges.isEmpty()) {
            for (FreeTimeRange range : freeTimeRanges) {
                if (range.getPromotionType() != null) {
                    types.add(range.getPromotionType());
                }
            }
        }
        if (freeMinutes > 0) {
            types.add(BConstants.PromotionType.FREE_MINUTES);
        }
        return types.size() > 1;
    }

    /**
     * 是否为单一优惠类型
     */
    public boolean hasSinglePromotionType() {
        if (isEmpty()) {
            return false;
        }
        Set<BConstants.PromotionType> types = new HashSet<>();
        if (freeTimeRanges != null) {
            for (FreeTimeRange range : freeTimeRanges) {
                if (range.getPromotionType() != null) {
                    types.add(range.getPromotionType());
                }
            }
        }
        if (freeMinutes > 0) {
            types.add(BConstants.PromotionType.FREE_MINUTES);
        }
        return types.size() == 1;
    }
}
