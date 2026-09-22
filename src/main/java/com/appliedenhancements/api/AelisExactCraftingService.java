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
        return begin(level, requester, new AelisCraftingRequest(
                output, amount, CalculationStrategy.REPORT_MISSING_ITEMS));
    }

    /** Begins an exact calculation from the shared validated request object. */
    public static Future<ICraftingPlan> begin(Level level, ICraftingSimulationRequester requester,
            AelisCraftingRequest request) {
        java.util.Objects.requireNonNull(request, "request");
        java.util.Objects.requireNonNull(level, "level");
        var exactRequest = new AelisExactRequest(request.amount());
        if (level.isClientSide || level.getServer() == null || !level.getServer().isSameThread())
            throw new IllegalStateException("Exact planning must begin on the server thread");
        if (requester == null || requester.getGridNode() == null)
            throw new IllegalArgumentException("A connected crafting requester is required");
        if (!Config.ENABLE_AELIS_BIG_INTEGER_PLANNING.get()) throw new IllegalStateException("BigInteger planning is disabled");
        CraftingCalculation calculation;
        try (var scope = new ExactRequestScope(exactRequest)) {
            calculation = new CraftingCalculation(level, requester.getGridNode().getGrid(), requester,
                    new GenericStack(request.output(), exactRequest.projection()), request.strategy());
        }
        return EXECUTOR.submit(() -> {
            try (var scope = new ExactRequestScope(exactRequest)) {
                var plan = calculation.run();
                if (plan == null) throw new IllegalStateException("Exact calculation returned no plan");
                var copy = AelisCycleExecutionApi.copyMetadata(plan, plan);
                ((com.github.appliedenhancements.integration.ae2.AelisBigIntegerCraftAmountsCarrier) copy)
                        .appliedenhancements$setBigIntegerFinalAmount(exactRequest.amount());
                return copy;
            }
        });
    }
}
