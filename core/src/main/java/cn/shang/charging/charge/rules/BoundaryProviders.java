package cn.shang.charging.charge.rules;

import cn.shang.charging.promotion.pojo.FreeTimeRange;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 公共边界来源工厂及最近边界查找工具。
 */
public final class BoundaryProviders {

    private BoundaryProviders() {
    }

    /**
     * 周期结束边界（从周期起点按 cycleMinutes 步进）。
     * 返回 current 之后、calcEnd 之前的第一个周期结束点。
     * <p>
     */
    public static BoundaryProvider cycleEnd(LocalDateTime cycleOriginBegin, int cycleMinutes) {
        return (current, calcEnd) -> {
            List<LocalDateTime> result = new ArrayList<>();
            // 找到第一个 > current 的周期结束点
            long offsetMinutes = Duration.between(cycleOriginBegin, current).toMinutes();
            long cycleIndex = offsetMinutes / cycleMinutes;
            LocalDateTime boundary = cycleOriginBegin.plusMinutes((cycleIndex + 1) * cycleMinutes);
            // 向前推进直到 > current
            while (!boundary.isAfter(current)) {
                boundary = boundary.plusMinutes(cycleMinutes);
            }
            if (!boundary.isAfter(calcEnd)) {
                result.add(boundary);
            }
            return result;
        };
    }

    /**
     * 免费时段起止边界。返回落在 (current, calcEnd] 内的所有免费时段起点和终点。
     * <p>
     */
    public static BoundaryProvider freeRangeEdges(List<FreeTimeRange> freeTimeRanges) {
        return (current, calcEnd) -> {
            List<LocalDateTime> result = new ArrayList<>();
            if (freeTimeRanges == null) {
                return result;
            }
            for (FreeTimeRange range : freeTimeRanges) {
                if (range.getBeginTime().isAfter(current) && !range.getBeginTime().isAfter(calcEnd)) {
                    result.add(range.getBeginTime());
                }
                if (range.getEndTime().isAfter(current) && !range.getEndTime().isAfter(calcEnd)) {
                    result.add(range.getEndTime());
                }
            }
            return result;
        };
    }

    /**
     * calcEnd 作为边界（始终包含）。
     */
    public static BoundaryProvider calcEnd(LocalDateTime calcEnd) {
        return (current, end) -> List.of(calcEnd);
    }

    /**
     * 从所有边界来源中找出严格大于 current、不大于 calcEnd 的最近边界。
     * 若无来源返回边界，返回 calcEnd。
     */
    public static LocalDateTime findNearest(LocalDateTime current,
                                            LocalDateTime calcEnd,
                                            List<BoundaryProvider> providers) {
        LocalDateTime nearest = calcEnd;
        for (BoundaryProvider provider : providers) {
            for (LocalDateTime boundary : provider.getBoundaries(current, calcEnd)) {
                if (boundary.isAfter(current) && !boundary.isAfter(nearest)) {
                    nearest = boundary;
                }
            }
        }
        return nearest;
    }
}
