package cn.shang.charging.charge.rules.relativetime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 相对时间段定义
 * <p>
 * 以计费起点为基准的相对时间段，用于 RelativeTimeRule
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelativeTimePeriod {

    /**
     * 相对开始分钟 (0-1439)
     * 从计费周期开始时刻起算的分钟数
     */
    private int beginMinute;

    /**
     * 相对结束分钟 (1-1440)
     * 不包含在内，即 [beginMinute, endMinute)
     */
    private int endMinute;

    /**
     * 计费单元长度（分钟）
     */
    private int unitMinutes;

    /**
     * 单价（每 unitMinutes 的价格）
     */
    private BigDecimal unitPrice;
}