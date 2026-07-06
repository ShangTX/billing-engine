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

}
