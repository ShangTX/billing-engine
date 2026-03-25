package cn.shang.charging.billing.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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

}