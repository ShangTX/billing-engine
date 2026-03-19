package cn.shang.charging.charge.rules.compositetime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 相对时间段配置
 * <p>
 * 以计费起点为基准的相对时间段，每个时间段内可有不同的单元长度和自然时段价格
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompositePeriod {

    /**
     * 相对开始分钟（相对于计费起点，0-1440）
     */
    private int beginMinute;

    /**
     * 相对结束分钟
     */
    private int endMinute;

    /**
     * 计费单元长度（分钟）
     */
    private int unitMinutes;

    /**
     * 时间段独立封顶（可选）
     */
    private BigDecimal maxCharge;

    /**
     * 跨自然时段处理模式
     */
    private CrossPeriodMode crossPeriodMode;

    /**
     * 自然时段价格列表
     * 必须覆盖全天（0-1440分钟）
     */
    private List<NaturalPeriod> naturalPeriods;
}