package cn.shang.charging.charge.rules.compositetime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 自然时段配置
 * <p>
 * 表示一天内的自然时间段，支持跨天表示
 * 例如：beginMinute=1200(20:00), endMinute=480(08:00) 表示 20:00 到次日 08:00
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NaturalPeriod {

    /**
     * 自然时段开始分钟（一天内的分钟数，0-1440）
     * 小于 endMinute 时表示不跨天
     * 大于 endMinute 时表示跨天
     */
    private int beginMinute;

    /**
     * 自然时段结束分钟
     * 小于 beginMinute 表示跨天
     */
    private int endMinute;

    /**
     * 单元价格
     */
    private BigDecimal unitPrice;
}