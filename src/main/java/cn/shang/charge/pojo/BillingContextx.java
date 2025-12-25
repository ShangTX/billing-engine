package cn.shang.charge.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计费上下文
 */
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
public class BillingContextx {

    LocalDateTime beginTime; // 计费开始时间

    LocalDateTime lastQueryTime; // 上次查询时间

    BigDecimal currentAmount; // 当前金额

    public int loopCount = 0; // 计算循环次数

    Set<Discount> usedDiscounts; // 已使用的优惠

    List<Discount> availableDiscounts; // 可用的优惠

    List<BillingSegment> chargingSegments; // 计费分段

    BillingSegment currentSegment; // 当前分段

    Map<String, Object> stateMap; // 计费状态

    @Data
    public static class BillingSegment {
        LocalDateTime beginTime; // 开始时间
        LocalDateTime endTime; // 结束时间
        Long schemaId; // 方案id
        Long ruleId; // 规则id
        Integer ruleVersion; // 版本号
        List<Period> periods; // 时间段
        Integer status; // 计费分段状态
    }

    @Data
    public static class Period {
        Integer periodType; // 时段类型
        LocalDateTime beginTime;
        LocalDateTime endTime;
        List<Pellet> pellets; // 颗粒
    }

    // 计费颗粒
    @Data
    public static class Pellet {
        LocalDateTime beginTime;
        LocalDateTime endTime;
        BigDecimal price;
        BigDecimal currentAmount;
        int minutes;
    }
}
