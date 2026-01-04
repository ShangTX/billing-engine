package cn.shang.charging.billing;

import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.SchemeChange;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class SegmentBuilder {


    /**
     * 计费方案分段
     */
    List<BillingSegment> buildSegments(BillingRequest request) {
        var segments = new ArrayList<BillingSegment>();
        if (!StringUtils.isEmpty(request.getSchemeId())
                && request.getSchemeId().equals("0")
                && request.getSchemeChanges().isEmpty()) {
            var segment = new BillingSegment(
                    request.getBeginTime(),
                    request.getEndTime(),
                    request.getSchemeId()
            );
            segments.add(segment);
            return segments;
        }
        var currentBegin = request.getBeginTime();
        for (SchemeChange schemeChange : request.getSchemeChanges()) {
            LocalDateTime segmentBegin;
            if (currentBegin.isBefore(schemeChange.getChangeTime())) {
                segmentBegin = currentBegin;
            } else {
                segmentBegin = schemeChange.getChangeTime();
            }
            currentBegin = segmentBegin;
            var segment = new BillingSegment(segmentBegin, schemeChange.getChangeTime(), schemeChange.getLastSchemeId());
            segments.add(segment);
        }
        var lastChange = request.getSchemeChanges().getLast();
        var lastSegment = new BillingSegment(
                currentBegin,
                request.getEndTime(),
                lastChange.getNextSchemeId());
        segments.add(lastSegment);
        return segments;
    }
}
