package cn.shang.charging.billing.pojo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 计算窗口
 */
@Data
public class CalculationWindow {

    // 实际用于规则计算的时间范围
    LocalDateTime calculationBegin;
    LocalDateTime calculationEnd;

    // 最终要从计算结果中“截取”的时间范围（可为空）
    LocalDateTime clipBegin;
    LocalDateTime clipEnd;

}
