# 输出结果优化 + 废弃 GLOBAL_ORIGIN + 等效金额增强

---
id: TODO-20260706-003
type: feature
priority: P1
status: done
source_git: 82da1af
created_at: 2026-07-06
completed_at: 2026-07-06
completed_git: <pending>
---

## 背景

规则抽象重构（TODO-20260706-002）落地后，输出层与等效金额计算仍有 6 项已确认优化未完成：

1. `PromotionUsage` 缺 `source` 字段，调用方无法区分方案内优惠（RULE）与外部优惠（COUPON 等）。
2. `PromotionUsage.equivalentAmount` 由策略侧按"免费段原价之和"填充，但 `PromotionEquivalentCalculator` 的消去法等效金额未回填到 `BillingResult.promotionUsages`，两套数值并存且语义不一致。
3. 等效金额计算的多段 + 外部优惠场景存在缺陷：`PromotionEquivalentCalculator.cloneAndExclude` 在聚合后按 ID 过滤免费段，但外部优惠跨段去重发生在 `ExternalPromotionPool.writeBack`（需计费推进），消去法迭代用的是缓存的 `SegmentContext.promotionAggregate`，未重放 `PromotionEngine.evaluate`，导致多段场景下外部优惠的跨段扣减未在每次消去迭代中重算。
4. `GLOBAL_ORIGIN`（4B 减法方案）半成品止血已 3 个版本（TODO-20260702-001 done），externalPool 跨段共享已替代其外部优惠一致性目标；减法方案（4A）无明确业务诉求，保留 `SegmentCalculationMode.GLOBAL_ORIGIN` 枚举值长期空转。
5. 等效金额目前全量计算（消去法对每个优惠重算一次），调用方无法按需（仅某几个 id / 某几个类型）计算，性能浪费。
6. `BillingResult` 缺 `totalEquivalentAmount` 汇总字段，调用方需自行求和。

## 目标

- `PromotionUsage` 透传 `source`（RULE/COUPON），从 `FreeTimeRange.source` / `FreeMinutes.source` / SMART 来源透传。
- `BillingResult.promotionUsages[i].equivalentAmount` 在请求指定等效金额计算时回填消去法结果（覆盖策略侧的"原价之和"近似值）。
- 等效金额多段 + 外部优惠修复：`calculateWithContexts` 重新 `PromotionEngine.evaluate`（externalPool reset + 重新聚合），`cloneAndExclude` 改在源层（externalPromotions / promotionRules 按 id 排除）排除。
- 废弃 `GLOBAL_ORIGIN`：删枚举值、`clipBegin/clipEnd`、工厂分支、`validateGlobalOrigin`、`GlobalOriginGuardTest`；保留 `SEGMENT_LOCAL` 作为扩展点；`segmentCalculationMode` 字段保留。
- 等效金额按需计算：新增 `EquivalentAmountSpec`（promotionIds + types，null=不限），`BillingRequest.equivalentAmountSpec`（null=不计算，默认 null）。
- `BillingResult.totalEquivalentAmount` 与 `finalAmount` 同级。

## 范围

### 包含

- `PromotionUsage` 加 `source` 字段 + 三处 usage 产出点透传（FREE_RANGE / FREE_MINUTES / SMART_FREE_MINUTES）。
- `FreeMinutes` 加 `source` 字段（透传 RULE / COUPON）。
- `PromotionEngine.convertMinutesFromRule` 透传 `source`。
- `EquivalentAmountSpec` 新建 POJO + `BillingRequest.equivalentAmountSpec` 字段。
- `BillingResult.totalEquivalentAmount` 字段。
- `PromotionEquivalentCalculator.calculate` 读 spec 过滤 + 返回按 spec 过滤后的 Map；`extractAndSortRanges` 按 spec 过滤。
- `BillingService.calculate` 在 `equivalentAmountSpec != null` 时调用 calculator，回填 `promotionUsages.equivalentAmount` + `totalEquivalentAmount`。
- `PromotionEquivalentCalculator.cloneAndExclude` 改在源层排除（externalPromotions / promotionRules 按 id），`calculateWithContexts` 重新 `evaluate`。
- 废弃 `GLOBAL_ORIGIN`：删 `BConstants.SegmentCalculationMode.GLOBAL_ORIGIN`、`CalculationWindow.clipBegin/clipEnd`、`CalculationWindowFactory` GLOBAL_ORIGIN 分支、`BillingService.validateGlobalOrigin` + 调用、`GlobalOriginGuardTest`；`SchemeSwitchTest` 场景2 改 SEGMENT_LOCAL 或删除；`BillingTestCaseGenerator` / `TestFeature.GLOBAL_ORIGIN` 调整。
- 测试：新增 `EquivalentAmountSpec` 按需/细致指定、`PromotionUsage.source` 透传、`totalEquivalentAmount`、等效金额多段+外部优惠；调整 `GlobalOriginGuardTest`（删除，因枚举值已不存在）。
- 文档同步：capabilities 中英 / USER_GUIDE / calculation-flow-zh / current-flow-zh / AGENTS.md / WORK_HANDOFF。

### 不包含

- 4A 减法方案实现（无业务诉求，`segment-promotion-consistency.md` 保留作设计参考）。
- AMOUNT/DISCOUNT 的等效金额（消去法天然支持，但 spec.types 过滤时如指定 AMOUNT/DISCOUNT，按 usage 无 usedFrom/usedTo 跳过；本次不专门补全非免费类优惠的等效金额语义）。

## 验收标准

- `PromotionUsage.getSource()` 对方案内优惠返回 `RULE`，外部优惠返回 `COUPON`（或 grant 指定的 source）。
- `BillingRequest.equivalentAmountSpec == null` 时不计算等效金额，`promotionUsages.equivalentAmount` 保持策略侧原价之和近似值，`totalEquivalentAmount == null`。
- `equivalentAmountSpec != null` 时按 spec 过滤计算，回填 `promotionUsages.equivalentAmount`（仅 spec 命中的），`totalEquivalentAmount` = 所有命中等效金额之和。
- 等效金额多段 + 外部优惠场景：每次消去迭代重放 `evaluate`，跨段扣减重算，结果正确。
- `GLOBAL_ORIGIN` 枚举值删除后，所有引用编译通过；`GlobalOriginGuardTest` 删除；其余 89+ 测试全绿。
- 现有 90 测试除 GlobalOriginGuardTest（删除）外全绿。

## 相关文件

- `core/src/main/java/cn/shang/charging/promotion/pojo/PromotionUsage.java`
- `core/src/main/java/cn/shang/charging/promotion/pojo/FreeMinutes.java`
- `core/src/main/java/cn/shang/charging/promotion/pojo/PromotionGrant.java`
- `core/src/main/java/cn/shang/charging/promotion/PromotionEngine.java`
- `core/src/main/java/cn/shang/charging/promotion/PromotionAggregateUtil.java`
- `core/src/main/java/cn/shang/charging/promotion/FreeMinuteAllocator.java`
- `core/src/main/java/cn/shang/charging/charge/rules/DurationGlobalStrategy.java`
- `core/src/main/java/cn/shang/charging/billing/BillingService.java`
- `core/src/main/java/cn/shang/charging/billing/pojo/BillingRequest.java`
- `core/src/main/java/cn/shang/charging/billing/pojo/BillingResult.java`
- `core/src/main/java/cn/shang/charging/billing/pojo/BConstants.java`
- `core/src/main/java/cn/shang/charging/billing/pojo/CalculationWindow.java`
- `core/src/main/java/cn/shang/charging/billing/CalculationWindowFactory.java`
- `core/src/main/java/cn/shang/charging/billing/pojo/EquivalentAmountSpec.java`（新建）
- `billing-api/src/main/java/cn/shang/charging/wrapper/PromotionEquivalentCalculator.java`
- `bill-test/src/test/java/cn/shang/charging/GlobalOriginGuardTest.java`（删除）
- `bill-test/src/test/java/cn/shang/charging/SchemeSwitchTest.java`
- `bill-test/src/test/java/cn/shang/charging/generator/BillingTestCaseGenerator.java`
- `bill-test/src/test/java/cn/shang/charging/generator/TestFeature.java`
- `docs/designs/segment-promotion-consistency.md`
- `docs/tracking/items/global-origin-half-finished.md`
- `docs/billing-engine-capabilities.md` / `docs/billing-engine-capabilities-zh.md`
- `docs/billing-engine-calculation-flow-zh.md`
- `docs/billing-engine-current-flow-zh.md`
- `docs/USER_GUIDE.md`

## 备注

- 设计决策见 `docs/superpowers/specs/2026-07-06-output-optimization-design.md`。
- `segment-promotion-consistency.md` 保留作 4A 设计参考，顶部加废弃标注。
- `global-origin-half-finished.md` 已 done，本次更新备注标注 GLOBAL_ORIGIN 废弃。

## 验证

```bash
mvn -pl core compile
mvn -pl bill-test -am test
# Tests run: 95, Failures: 0, Errors: 0, Skipped: 0
# 新增 OutputOptimizationAndEquivalentAmountTest 8 测试
```
