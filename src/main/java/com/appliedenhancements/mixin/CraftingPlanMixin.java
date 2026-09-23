package com.appliedenhancements.mixin;

import appeng.crafting.CraftingPlan;
import appeng.api.stacks.AEKey;
import appeng.api.crafting.IPatternDetails;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPath;
import com.github.appliedenhancements.integration.ae2.AelisCalculationPathCarrier;
import com.github.appliedenhancements.integration.ae2.AelisBigIntegerCraftAmountsCarrier;
import com.github.appliedenhancements.integration.ae2.AelisCyclicCraftAmountsCarrier;
import com.github.appliedenhancements.integration.ae2.AelisCycleExecutionPlanCarrier;
import com.appliedenhancements.api.AelisCycleExecutionPlan;
import java.util.Map;
import java.math.BigInteger;
import java.util.Objects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = CraftingPlan.class, remap = false)
public abstract class CraftingPlanMixin implements AelisCalculationPathCarrier,
        AelisCyclicCraftAmountsCarrier, AelisCycleExecutionPlanCarrier,
        AelisBigIntegerCraftAmountsCarrier {
    @Unique
    private AelisCalculationPath appliedenhancements$calculationPath =
            AelisCalculationPath.AE2_NATIVE;
    @Unique
    private Map<AEKey, Long> appliedenhancements$cyclicCraftAmounts = Map.of();
    @Unique
    private Map<AEKey, BigInteger> appliedenhancements$bigIntegerCraftAmounts = Map.of();
    @Unique private Map<AEKey, BigInteger> appliedenhancements$bigIntegerMissingAmounts = Map.of();
    @Unique private Map<AEKey, BigInteger> appliedenhancements$bigIntegerStoredAmounts = Map.of();
    @Override public Map<AEKey, BigInteger> appliedenhancements$getBigIntegerStoredAmounts() {
        return appliedenhancements$bigIntegerStoredAmounts;
    }
    @Override public void appliedenhancements$setBigIntegerStoredAmounts(Map<AEKey, BigInteger> amounts) {
        appliedenhancements$bigIntegerStoredAmounts = Map.copyOf(amounts);
    }
    @Unique private Map<AEKey, BigInteger> appliedenhancements$bigIntegerInfiniteAmounts = Map.of();
    @Override public Map<AEKey, BigInteger> appliedenhancements$getBigIntegerInfiniteAmounts() {
        return appliedenhancements$bigIntegerInfiniteAmounts;
    }
    @Override public void appliedenhancements$setBigIntegerInfiniteAmounts(Map<AEKey, BigInteger> amounts) {
        appliedenhancements$bigIntegerInfiniteAmounts = Map.copyOf(amounts);
    }
    @Unique private boolean appliedenhancements$previewOnly;
    @Unique private BigInteger appliedenhancements$exactFinalAmount;
    @Override public BigInteger appliedenhancements$getBigIntegerFinalAmount() { return appliedenhancements$exactFinalAmount; }
    @Override public void appliedenhancements$setBigIntegerFinalAmount(BigInteger amount) { appliedenhancements$exactFinalAmount = amount; }
    @Unique private BigInteger appliedenhancements$bigIntegerBytes;
    @Override public BigInteger appliedenhancements$getBigIntegerBytes() { return appliedenhancements$bigIntegerBytes; }
    @Override public void appliedenhancements$setBigIntegerBytes(BigInteger bytes) { appliedenhancements$bigIntegerBytes = bytes; }
    @Unique private Map<IPatternDetails, BigInteger> appliedenhancements$bigIntegerPatternTimes = Map.of();
    @Override public Map<IPatternDetails, BigInteger> appliedenhancements$getBigIntegerPatternTimes() {
        return appliedenhancements$bigIntegerPatternTimes;
    }
    @Override public void appliedenhancements$setBigIntegerPatternTimes(Map<IPatternDetails, BigInteger> times) {
        appliedenhancements$bigIntegerPatternTimes = Map.copyOf(times);
    }
    @Override public boolean appliedenhancements$isPreviewOnly() { return appliedenhancements$previewOnly; }
    @Override public void appliedenhancements$setPreviewOnly(boolean value) { appliedenhancements$previewOnly = value; }

    @Override public Map<AEKey, BigInteger> appliedenhancements$getBigIntegerMissingAmounts() {
        return appliedenhancements$bigIntegerMissingAmounts;
    }

    @Override public void appliedenhancements$setBigIntegerMissingAmounts(Map<AEKey, BigInteger> amounts) {
        appliedenhancements$bigIntegerMissingAmounts = Map.copyOf(amounts);
    }
    @Unique
    private AelisCycleExecutionPlan appliedenhancements$cycleExecutionPlan;

    @Override
    public AelisCalculationPath molecularmanipulator$getCalculationPath() {
        return appliedenhancements$calculationPath;
    }

    @Override
    public void molecularmanipulator$setCalculationPath(AelisCalculationPath path) {
        appliedenhancements$calculationPath = Objects.requireNonNull(path, "path");
    }

    @Override
    public Map<AEKey, Long> appliedenhancements$getCyclicCraftAmounts() {
        return appliedenhancements$cyclicCraftAmounts;
    }

    @Override
    public void appliedenhancements$setCyclicCraftAmounts(Map<AEKey, Long> amounts) {
        appliedenhancements$cyclicCraftAmounts = Map.copyOf(
                Objects.requireNonNull(amounts, "amounts"));
    }

    @Override
    public Map<AEKey, BigInteger> appliedenhancements$getBigIntegerCraftAmounts() {
        return appliedenhancements$bigIntegerCraftAmounts;
    }

    @Override
    public void appliedenhancements$setBigIntegerCraftAmounts(
            Map<AEKey, BigInteger> amounts) {
        appliedenhancements$bigIntegerCraftAmounts = Map.copyOf(
                Objects.requireNonNull(amounts, "amounts"));
    }

    @Override
    public AelisCycleExecutionPlan appliedenhancements$getCycleExecutionPlan() {
        return appliedenhancements$cycleExecutionPlan;
    }

    @Override
    public void appliedenhancements$setCycleExecutionPlan(
            AelisCycleExecutionPlan plan) {
        appliedenhancements$cycleExecutionPlan = plan;
    }
}
