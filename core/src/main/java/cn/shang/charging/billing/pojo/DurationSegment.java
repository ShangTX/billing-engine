package cn.shang.charging.billing.pojo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 时长计费段（Duration-Based Billing Mode 的产出）
 * <p>
 * 时长模式将时间轴视为连续的分钟流，按时段类型分组，每段记录收费分钟数。
 * 与 BillingUnit（单元模式）不同，DurationSegment 反映"按分钟计费"的语义。
 * <p>
 * 封顶落盘策略：
 * <ul>
 *   <li>时段封顶落盘到 {@link #chargedAmount}（该段在时段封顶后的应收）</li>
 *   <li>周期封顶不落盘（只影响 BillingSegmentResult.chargedAmount）</li>
 * </ul>
 * 免费段用 {@code chargedMinutes=0} / {@code chargedAmount=0} 表达，免费原因走 PromotionUsage 汇总。
 *
 * @param beginTime      段起点
 * @param endTime        段终点
 * @param periodLabel    period 性质（"day"/"night"/"period-1"，规则自定义）
 * @param chargedMinutes 收费分钟数（免费段=0）
 * @param unitPrice      单价
 * @param chargedAmount  应收（时段封顶后，周期封顶前）
 * @param periodCap      该时段封顶金额（null=无封顶）
 */
public record DurationSegment(
        LocalDateTime beginTime,
        LocalDateTime endTime,
        String periodLabel,
        int chargedMinutes,
        BigDecimal unitPrice,
        BigDecimal chargedAmount,
        BigDecimal periodCap
) {}
