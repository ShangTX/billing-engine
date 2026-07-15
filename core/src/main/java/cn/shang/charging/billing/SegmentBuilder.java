package cn.shang.charging.billing;

import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.SchemeChange;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 计费方案分段构建器。
 * <p>
 * 根据计费请求构建 {@link BillingSegment} 列表：
 * <ul>
 *   <li>单方案场景：直接生成一段 [beginTime, endTime]</li>
 *   <li>方案切换场景：按 schemeChanges 时间轴切割为多段，每段绑定对应方案ID</li>
 * </ul>
 */
public class SegmentBuilder {

    private int segmentCounter = 0;

    /**
     * 生成分段ID
     */
    private String generateSegmentId(String schemeId) {
        return schemeId + "-" + (segmentCounter++);
    }

    /**
     * 计费方案分段
     */
    List<BillingSegment> buildSegments(BillingRequest request) {
        segmentCounter = 0; // 重置计数器
        var segments = new ArrayList<BillingSegment>();

        // 单方案计费
        if (request.getSchemeId() != null && !request.getSchemeId().isEmpty()
                && !request.getSchemeId().equals("0")
                && request.getSchemeChanges().isEmpty()) {
            var segment = BillingSegment.builder()
                    .id(generateSegmentId(request.getSchemeId()))
                    .beginTime(request.getBeginTime())
                    .endTime(request.getEndTime())
                    .schemeId(request.getSchemeId())
                    .build();
            segments.add(segment);
            return segments;
        }

        // 多方案分段计费
        var currentBegin = request.getBeginTime();
        for (SchemeChange schemeChange : request.getSchemeChanges()) {
            LocalDateTime segmentBegin;
            if (currentBegin.isBefore(schemeChange.getChangeTime())) {
                segmentBegin = currentBegin;
            } else {
                segmentBegin = schemeChange.getChangeTime();
            }
            currentBegin = schemeChange.getChangeTime();

            var segment = BillingSegment.builder()
                    .id(generateSegmentId(schemeChange.getLastSchemeId()))
                    .beginTime(segmentBegin)
                    .endTime(schemeChange.getChangeTime())
                    .schemeId(schemeChange.getLastSchemeId())
                    .build();
            segments.add(segment);
        }

        var lastChange = request.getSchemeChanges().getLast();
        var lastSegment = BillingSegment.builder()
                .id(generateSegmentId(lastChange.getNextSchemeId()))
                .beginTime(currentBegin)
                .endTime(request.getEndTime())
                .schemeId(lastChange.getNextSchemeId())
                .build();
        segments.add(lastSegment);

        return segments;
    }
}
