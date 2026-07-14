package cn.shang.charging.charge.rules;

import cn.shang.charging.promotion.pojo.FreeTimeRange;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 公共边界来源工厂及最近边界查找工具。
 */
public final class BoundaryProviders {

    private BoundaryProviders() {
    }

    /**
     * 周期结束边界：从周期起点按 cycleMinutes 步进，返回 current 之后、calcEnd 之前的第一个周期结束点。
     */
    public static BoundaryProvider cycleEnd(LocalDateTime cycleOriginBegin, int cycleMinutes) {
        return (current, calcEnd) -> {
            long offsetMinutes = Duration.between(cycleOriginBegin, current).toMinutes();
            long cycleIndex = offsetMinutes / cycleMinutes;
            LocalDateTime boundary = cycleOriginBegin.plusMinutes((cycleIndex + 1) * cycleMinutes);
            while (!boundary.isAfter(current)) {
                boundary = boundary.plusMinutes(cycleMinutes);
            }
            return boundary.isAfter(calcEnd) ? null : boundary;
        };
    }

    /**
     * 免费时段起止边界。输入由优惠合并链路按 beginTime 排序并合并，返回 (current, calcEnd] 内最近的起点或终点。
     */
    public static BoundaryProvider freeRangeEdges(List<FreeTimeRange> freeTimeRanges) {
        return (current, calcEnd) -> {
            if (freeTimeRanges == null) {
                return null;
            }
            for (FreeTimeRange range : freeTimeRanges) {
                if (range == null || range.getBeginTime() == null || range.getEndTime() == null) {
                    continue;
                }
                if (!range.getEndTime().isAfter(current)) {
                    continue;
                }
                if (range.getBeginTime().isAfter(calcEnd)) {
                    return null;
                }
                if (range.getBeginTime().isAfter(current)) {
                    return range.getBeginTime();
                }
                if (!range.getEndTime().isAfter(calcEnd)) {
                    return range.getEndTime();
                }
            }
            return null;
        };
    }

    /**
     * calcEnd 作为兜底边界。
     */
    public static BoundaryProvider calcEnd(LocalDateTime calcEnd) {
        return (current, end) -> calcEnd;
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
            LocalDateTime boundary = provider.nextBoundary(current, calcEnd);
            if (boundary != null && boundary.isAfter(current) && !boundary.isAfter(nearest)) {
                nearest = boundary;
            }
        }
        return nearest;
    }
}
