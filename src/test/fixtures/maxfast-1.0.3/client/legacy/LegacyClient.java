package legacy;

import appeng.api.stacks.KeyCounter;
import com.appliedenhancements.api.MaxFastCraftingPlanner;
import java.util.ArrayList;
import java.util.List;

/** Compiled only against the isolated 1.0.3 API declaration, never the current API. */
public final class LegacyClient {
    public static final List<String> EVENTS = new ArrayList<>();

    public static MaxFastCraftingPlanner create() throws InterruptedException {
        MaxFastCraftingPlanner.NO_PAUSE.pause();
        var none = MaxFastCraftingPlanner.ProgressListener.NONE;
        none.compilationStarted();
        none.nodeDiscovered();
        none.compilationStep();
        none.executionStarted(0);
        none.executionStep();
        return MaxFastCraftingPlanner.create(321, 123, () -> EVENTS.add("pause"),
                new MaxFastCraftingPlanner.ProgressListener() {
                    public void compilationStarted() { EVENTS.add("compile"); }
                    public void nodeDiscovered() { EVENTS.add("node"); }
                    public void compilationStep() { EVENTS.add("step"); }
                    public void executionStarted(long units) { EVENTS.add("execute:" + units); }
                    public void executionStep() { EVENTS.add("executionStep"); }
                });
    }

    public static MaxFastCraftingPlanner configured() {
        return MaxFastCraftingPlanner.createConfigured(
                MaxFastCraftingPlanner.NO_PAUSE, MaxFastCraftingPlanner.ProgressListener.NONE);
    }

    public static MaxFastCraftingPlanner.Result execute(MaxFastCraftingPlanner planner) throws InterruptedException {
        return planner.tryExecute(null, null, 17, true, new KeyCounter());
    }

    public static void readEveryResultAccessor(MaxFastCraftingPlanner.Result result,
            Object expectedFailure, Object expectedError) {
        if (result.applied() || !"legacy_test".equals(result.fallbackReason())
                || result.uniqueNodes() != 2 || result.mergedOccurrences() != 3
                || result.barrierCount() != 4 || result.logicalNodeCount() != 5
                || result.compileNanos() != 6 || result.executionNanos() != 7
                || !result.nativeNodeCount() || result.branchFailure() != expectedFailure
                || result.error() != expectedError || result.shouldFallback() != (expectedFailure == null)) {
            throw new AssertionError("Legacy Result descriptor or field conversion mismatch");
        }
        var copy = new MaxFastCraftingPlanner.Result(result.applied(), result.fallbackReason(),
                result.uniqueNodes(), result.mergedOccurrences(), result.barrierCount(),
                result.logicalNodeCount(), result.compileNanos(), result.executionNanos(),
                result.nativeNodeCount(), result.branchFailure(), result.error());
        if (!copy.equals(result) || copy.hashCode() != result.hashCode() || copy.toString().isEmpty()) {
            throw new AssertionError("Legacy Result canonical constructor or record methods changed");
        }
    }
}
