package cn.shang.charging.generator;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashSet;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestGenerationRequest {

    private String chargeRuleType;

    @Builder.Default
    private Set<TestFeature> features = new LinkedHashSet<>();

    @Builder.Default
    private int count = 1;

    @Builder.Default
    private Long seed = 1L;
}
