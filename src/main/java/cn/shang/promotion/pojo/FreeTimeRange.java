package cn.shang.promotion.pojo;

import cn.shang.billing.pojo.BConstants;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 免费时间段
 */
@Data
public class FreeTimeRange {

    private LocalDateTime beginTime;
    private LocalDateTime endTime;

    private BConstants.PromotionType promotionType;

}
