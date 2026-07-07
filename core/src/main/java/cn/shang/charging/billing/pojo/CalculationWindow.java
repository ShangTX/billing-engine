package cn.shang.charging.billing.pojo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 计算窗口。
 * <p>
 * 表示实际参与规则计算的时间范围 [calculationBegin, calculationEnd]。
 * 由 {@code CalculationWindowFactory} 创建：单段场景直接取分段起止时间；
 * 多段场景以分段起点为 calculationBegin（统一以分段起点起算）。
 */
@Data
public class CalculationWindow {

    /** 计算窗口起点（= 分段起点） */
    LocalDateTime calculationBegin;
    /** 计算窗口终点（= 分段终点） */
    LocalDateTime calculationEnd;

}
