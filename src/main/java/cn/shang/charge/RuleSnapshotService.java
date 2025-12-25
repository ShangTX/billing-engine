package cn.shang.charge;

import cn.shang.charge.pojo.BillingContext;

import java.time.LocalDateTime;
import java.util.List;

public interface RuleSnapshotService {

    BillingContext.RuleSnapshot loadSnapshot(List<BillingContext.SchemeSegment> schemeTimeline);

}
