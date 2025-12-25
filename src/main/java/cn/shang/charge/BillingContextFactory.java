package cn.shang.charge;

import cn.shang.charge.pojo.BillingContext;
import cn.shang.charge.pojo.BillingDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BillingContextFactory {

    private final SchemeTimelineService schemeTimelineService;
    private final RuleSnapshotService ruleSnapshotService;


    /**
     * 创建计费context
     */
    public BillingContext create(BillingDTO request) {

        LocalDateTime endTime = request.getEndTime() != null
                ? request.getEndTime()
                : LocalDateTime.now();

        List<BillingContext.SchemeSegment> schemeTimeline =
                schemeTimelineService.buildTimeline(
                        request.getInitialSchemeId(),
                        request.getStartTime(),
                        endTime);
        // 计费规则
        BillingContext.RuleSnapshot ruleSnapshot = ruleSnapshotService.loadSnapshot(schemeTimeline);

        return BillingContext.builder()
                .orderId(request.getOrderId())
                .beginTime(request.getStartTime())
                .endTime(endTime)
                .schemeTimeline(schemeTimeline)
                .ruleSnapshot(ruleSnapshot)
                .externalFreeMinutes(request.getFreeMinutes())
                .externalFreeTimeSegments(request.getFreeTimeSegments())
                .billingMode(request.getBillingMode())
                .build();
    }

}
