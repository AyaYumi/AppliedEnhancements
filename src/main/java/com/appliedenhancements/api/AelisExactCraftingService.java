package com.appliedenhancements.api;

import appeng.api.networking.crafting.*;
import appeng.api.stacks.*;
import appeng.crafting.CraftingCalculation;
import com.appliedenhancements.Config;
import com.appliedenhancements.runtime.ExactRequestScope;
import java.math.BigInteger;
import java.util.concurrent.*;
import net.minecraft.world.level.Level;

/** Public asynchronous planning entry point. No CPU whitelist or pre-submission capability check. */
public final class AelisExactCraftingService {
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, task -> {
        var thread = new Thread(task, "AELIS Exact Calculator"); thread.setDaemon(true); return thread;
    });
    private AelisExactCraftingService() {}

    /** Call on the server thread: the calculation constructor snapshots the network here. */
    public static Future<ICraftingPlan> begin(Level level, ICraftingSimulationRequester requester,
            AEKey output, BigInteger amount) {
        var request = new AelisExactRequest(amount);
        if (level.isClientSide || level.getServer() == null || !level.getServer().isSameThread())
            throw new IllegalStateException("Exact planning must begin on the server thread");
        java.util.Objects.requireNonNull(output, "output");
        if (requester == null || requester.getGridNode() == null)
            throw new IllegalArgumentException("A connected crafting requester is required");
        if (!Config.ENABLE_AELIS_BIG_INTEGER_PLANNING.get()) throw new IllegalStateException("BigInteger planning is disabled");
        var calculation = new CraftingCalculation(level, requester.getGridNode().getGrid(), requester,
                new GenericStack(output, request.projection()), CalculationStrategy.REPORT_MISSING_ITEMS);
        return EXECUTOR.submit(() -> {
            try (var scope = new ExactRequestScope(request)) {
                var plan = calculation.run();
                if (plan == null) throw new IllegalStateException("Exact calculation returned no plan");
                var copy = AelisCycleExecutionApi.copyMetadata(plan, plan);
                ((com.github.appliedenhancements.integration.ae2.AelisBigIntegerCraftAmountsCarrier) copy)
                        .appliedenhancements$setBigIntegerFinalAmount(amount);
                return copy;
            }
        });
    }
}
