package cn.shang.charge;

import cn.shang.charge.pojo.BillingContext;

import java.time.LocalDateTime;
import java.util.List;

public interface SchemeTimelineService {

    List<BillingContext.SchemeSegment>  buildTimeline(String schemaId, LocalDateTime startTime, LocalDateTime endTime);
}
