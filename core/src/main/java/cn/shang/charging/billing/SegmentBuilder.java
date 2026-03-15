package cn.shang.charging.billing;

import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.SchemeChange;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 计费分段Builder
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
        if (!StringUtils.isEmpty(request.getSchemeId())
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
            currentBegin = segmentBegin;

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
