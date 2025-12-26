package cn.shang.promotion.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
public class PromotionContext {

    /** 计费开始时间 */
    private LocalDateTime beginTime;

    /** 当前计费结束时间（查询时间） */
    private LocalDateTime endTime;

    /** 当前方案 ID（用于规则筛选） */
    private String schemeId;

    /** 计费周期信息（自然日 / 24h 等） */
//    private final BillingCycleType cycleType;

    /** 外部输入的优惠参数（如：赠送分钟数） */
    private Map<String, Object> externalPromotionParams;

}
