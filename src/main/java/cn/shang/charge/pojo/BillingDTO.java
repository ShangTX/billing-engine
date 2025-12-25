package cn.shang.charge.pojo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 计费DTO
 */
@Data
public class BillingDTO {

    private String orderId;

    private LocalDateTime startTime;
    private LocalDateTime endTime;
    // 计费方案
    private final String initialSchemeId;

    // 外部优惠
    private final List<FreeTimeSegment> freeTimeSegments;

    // 计费模式
    private final BillingMode billingMode;
}
