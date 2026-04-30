package cn.shang.charging.generator;

import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.wrapper.QuerySummary;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeneratedContinueStep {

    private String stepId;
    private BillingRequest request;
    private BillingResult result;
    private List<QuerySummary> querySummaries;
}
