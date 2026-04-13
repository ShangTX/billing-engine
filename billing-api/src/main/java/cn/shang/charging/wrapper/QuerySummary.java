package cn.shang.charging.wrapper;

import cn.shang.charging.promotion.pojo.PromotionUsage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 查询结果摘要
 * 轻量级结构，用索引代替复制单元列表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuerySummary {
    /**
     * 查询单元索引（0-based）
     * -1 表示无对应单元（units 为空）
     */
    private int unitIndex;

    /**
     * 查询时间点的累计金额
     * = units[unitIndex].accumulatedAmount
     * 当 unitIndex = -1 时为 0
     */
    private BigDecimal amount;

    /**
     * 有效时间范围起点
     * = units[0].beginTime
     */
    private LocalDateTime effectiveFrom;

    /**
     * 有效时间范围终点
     * = units[unitIndex].endTime
     */
    private LocalDateTime effectiveTo;

    /**
     * 查询时间点
     */
    private LocalDateTime queryTime;

    /**
     * 截取的优惠使用记录
     */
    private List<PromotionUsage> promotionUsages;
}