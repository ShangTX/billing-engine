package cn.shang.charging.promotion.pojo;

import lombok.Data;

@Data
public class FreeMinutes {

    private String id;

    private Integer minutes;

    // 优先级
    private Integer priority;

}
