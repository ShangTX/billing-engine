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
     * 分段唯一标识（用于 CONTINUE 模式状态匹配）
     */
    private String id;

    private LocalDateTime beginTime;
    private LocalDateTime endTime;
    private String schemeId;

}
