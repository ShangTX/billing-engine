package cn.shang.charging.generator;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 测试结果生成请求。
 * <p>
 * 该对象只描述“生成什么样的计费结果样本”，不承载任何预期金额。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestGenerationRequest {

    /**
     * 计费规则类型，例如 {@code BConstants.ChargeRuleType.DAY_NIGHT}。
     * <p>
     * 第一版生成器只支持 {@code dayNight}。
     */
    private String chargeRuleType;

    /**
     * 需要组合覆盖的功能点。
     * <p>
     * 生成器会根据功能点自动补充必要的派生功能点，例如 MULTI_PROMOTION 会补充 FREE_MINUTES。
     */
    @Builder.Default
    private Set<TestFeature> features = new LinkedHashSet<>();

    /**
     * 需要生成的测试结果数量。
     */
    @Builder.Default
    private int count = 1;

    /**
     * 确定性扰动种子。
     * <p>
     * 相同 seed 和相同请求会生成相同的时间扰动，便于复现人工判断过的样本。
     */
    @Builder.Default
    private Long seed = 1L;
}
