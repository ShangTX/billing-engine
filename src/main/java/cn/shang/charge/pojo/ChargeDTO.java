package cn.shang.charge.pojo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ChargeDTO {
    Integer chargeMethod; // 0 仅计费 1 继续计费 2 重新计费
    // 订单id
    Long orderId;
    // 计费开始时间
    LocalDateTime beginTime;
    // 查询计费时间
    LocalDateTime queryTime;
    // 优惠集合
    List<Discount> discountList;

}
