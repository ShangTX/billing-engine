package cn.shang.charging.billing.pojo;

import cn.shang.charging.promotion.pojo.FreeTimeRange;
import cn.shang.charging.util.TypeConversionUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 优惠结转状态
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PromotionCarryOver {

    /**
     * 剩余免费分钟数
     * key: promotionId
     * value: 剩余分钟数
     * 注意：序列化后 value 可能变成 Number 或 String，使用 getRemainingMinutesConverted() 获取转换后的值
     */
    private Map<String, Object> remainingMinutes;

    /**
     * 已使用的免费时段
     * 用于追踪免费时段的部分使用情况
     */
    private List<FreeTimeRange> usedFreeRanges;

    /**
     * 获取转换后的剩余分钟数 Map
     * 自动处理序列化后的类型转换（Number/String → Integer）
     *
     * @return Map<String, Integer>，保证 value 为 Integer 类型
     */
    public Map<String, Integer> getRemainingMinutesConverted() {
        if (remainingMinutes == null || remainingMinutes.isEmpty()) {
            return null;
        }
        Map<String, Integer> converted = new HashMap<>();
        for (Map.Entry<String, Object> entry : remainingMinutes.entrySet()) {
            Integer value = TypeConversionUtil.toInteger(entry.getValue());
            if (value != null) {
                converted.put(entry.getKey(), value);
            }
        }
        return converted.isEmpty() ? null : converted;
    }

}