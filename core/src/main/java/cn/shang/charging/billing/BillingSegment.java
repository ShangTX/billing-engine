package cn.shang.charging.billing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 计费分段
 */
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
public class BillingSegment {

    /**
     * 分段唯一标识
     */
    private String id;

    private LocalDateTime beginTime;
    private LocalDateTime endTime;
    private String schemeId;

}
