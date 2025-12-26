package cn.shang.billing.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
public class BillingProgress {

    @Builder.Default
    Integer calIndex = 0; // 已计算的位置索引


    Map<String, Object> state; // 状态Map，各规则根据需要存取

    public static BillingProgress create() {
        return BillingProgress.builder().build();
    }

}
