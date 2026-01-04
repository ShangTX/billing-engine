package cn.shang.charging.billing;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 计费分段
 */
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Data
public class BillingSegment {

    LocalDateTime beginTime;
    LocalDateTime endTime;
    String schemeId;

}
