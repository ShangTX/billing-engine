# 输出结果优化 + 废弃 GLOBAL_ORIGIN + 等效金额增强 实施计划

**关联**: TODO-20260706-003
**Spec**: `docs/superpowers/specs/2026-07-06-output-optimization-design.md`

## 阶段 1: POJO 字段新增

- [ ] `PromotionUsage` 加 `source` 字段
- [ ] `FreeMinutes` 加 `source` 字段
- [ ] `EquivalentAmountSpec` 新建（core billing.pojo）
- [ ] `BillingRequest` 加 `equivalentAmountSpec` 字段
- [ ] `BillingResult` 加 `totalEquivalentAmount` 字段

## 阶段 2: source 透传

- [ ] `PromotionEngine.convertMinutesFromRule` 透传 `grant.getSource()` 到 `FreeMinutes`
- [ ] `FreeTimeRangeMerger.preprocessRanges` + 各处构造保留 `source`（processedRange / discarded）
- [ ] `PromotionAggregateUtil.buildFreeRangeUsages` 透传 `range.getSource()` 到 usage
- [ ] `FreeMinuteAllocator` FREE_MINUTES usage 透传 `currentFreeMinutes.getSource()`
- [ ] `DurationGlobalStrategy` 价格感知 FREE_MINUTES usage 透传 `minutes.getSource()`

## 阶段 3: 废弃 GLOBAL_ORIGIN

- [ ] `BConstants.SegmentCalculationMode` 删 `GLOBAL_ORIGIN`
- [ ] `CalculationWindow` 删 `clipBegin` / `clipEnd`
- [ ] `CalculationWindowFactory` 删 GLOBAL_ORIGIN 分支（SINGLE/SEGMENT_LOCAL 统一 `calculationBegin = segment.getBeginTime()`）
- [ ] `BillingService` 删 `validateGlobalOrigin` + 两处调用
- [ ] 删 `GlobalOriginGuardTest`
- [ ] `SchemeSwitchTest` 场景2 改 SEGMENT_LOCAL（或删除场景2）
- [ ] `BillingTestCaseGenerator` / `TestFeature.GLOBAL_ORIGIN` 调整（移除或映射 SEGMENT_LOCAL）

## 阶段 4: 等效金额计算器迁移 + 重放 evaluate

- [ ] `PromotionEquivalentCalculator` 从 billing-api 移到 core `cn.shang.charging.billing` 包
- [ ] `cloneAndExclude` 改源层排除（externalPromotions + promotionRules 按 id 过滤，不再用 `PromotionAggregateUtil.exclude`）
- [ ] `calculateWithContexts` 改重放 evaluate（每段重建 BillingContext externalPromotions + evaluate + writeBack）
- [ ] `extractAndSortRanges` 按 `EquivalentAmountSpec` 过滤（promotionIds + types）
- [ ] `calculate` spec==null 返回空 Map
- [ ] billing-api `BillingTemplate` import 调整 + `PromotionEquivalentCalculatorTest` import 调整

## 阶段 5: BillingService 回填

- [ ] `BillingService.calculate` 在 `equivalentAmountSpec != null` 时调用 calculator，回填 `promotionUsages.equivalentAmount` + `totalEquivalentAmount`

## 阶段 6: 测试

- [ ] 新增 `EquivalentAmountSpec` 按需/细致指定测试
- [ ] 新增 `PromotionUsage.source` 透传测试
- [ ] 新增 `totalEquivalentAmount` 测试
- [ ] 新增 等效金额多段+外部优惠测试
- [ ] `mvn -pl core compile` + `mvn -pl bill-test -am test`

## 阶段 7: 文档同步

- [ ] `segment-promotion-consistency.md` 顶部废弃标注
- [ ] `global-origin-half-finished.md` 备注标注废弃
- [ ] `billing-engine-capabilities.md` / `-zh.md` 去 GLOBAL_ORIGIN 或标注废弃
- [ ] `billing-engine-calculation-flow-zh.md` 去 GLOBAL_ORIGIN
- [ ] `docs/superpowers/archive/billing-engine-current-flow-zh.md` 去 GLOBAL_ORIGIN 守卫
- [ ] `USER_GUIDE.md` 去 GLOBAL_ORIGIN
- [ ] `DONE.md` / `TODO.md` 迁移
- [ ] 提交
