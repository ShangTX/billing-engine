package cn.shang.charging.billing.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 分段级结转状态
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SegmentCarryOver {

    /**
     * 规则状态，key 为规则类型，value 为规则自定义结构
     * 例如：
     * - "relativeTime" -> {cycleIndex, cycleAccumulated, cycleBoundary}
     * - "dayNight" -> {cycleIndex, cycleAccumulated, cycleBoundary}
     */
    private Map<String, Object> ruleState;

    /**
     * 优惠结转状态
     */
    private PromotionCarryOver promotionState;

    /**
     * 最后一个截断单元的开始时间
     * 用于 CONTINUE 模式合并计算
     * 如果上次计算的最后单元是完整的（isTruncated=false 或不存在），此值为 null
     */
    private LocalDateTime lastTruncatedUnitStartTime;

}