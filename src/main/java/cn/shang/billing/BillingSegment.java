package cn.shang.billing;

import cn.shang.billing.pojo.SchemeChange;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

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
