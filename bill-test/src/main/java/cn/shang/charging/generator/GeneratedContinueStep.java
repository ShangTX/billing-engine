package cn.shang.charging.generator;

import cn.shang.charging.billing.pojo.BillingRequest;
import cn.shang.charging.billing.pojo.BillingResult;
import cn.shang.charging.wrapper.QuerySummary;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * CONTINUE 场景中的单步计算结果。
 * <p>
 * 一个完整 CONTINUE 样本通常包含第一次计算和携带 carryOver 的第二次计算。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeneratedContinueStep {

    /**
     * 分步 ID。
     */
    private String stepId;

    /**
     * 当前步骤使用的计费请求。
     */
    private BillingRequest request;

    /**
     * 当前步骤得到的计费结果。
     */
    private BillingResult result;

    /**
     * 当前步骤对应的查询摘要。
     */
    private List<QuerySummary> querySummaries;
}
