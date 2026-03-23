package cn.shang.charging.wrapper;

import cn.shang.charging.billing.pojo.BillingResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 计算结果与查询结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CalculationWithQueryResult {
    /**
     * 完整计算结果
     * - 用于 CONTINUE 进度存储
     * - 用于费用稳定窗口判断
     */
    private BillingResult calculationResult;

    /**
     * 查询时间点的结果
     * - 用于展示给用户
     */
    private BillingResult queryResult;
}