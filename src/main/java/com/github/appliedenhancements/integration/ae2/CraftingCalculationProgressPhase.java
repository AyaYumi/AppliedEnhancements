package com.github.appliedenhancements.integration.ae2;

public enum CraftingCalculationProgressPhase {
    IDLE(0, true),
    QUEUED(1, false),
    WAITING_SLOT(2, false),
    PREPARING(3, false),
    MAX_FAST_COMPILING(4, false),
    MAX_FAST_EXECUTING(5, false),
    AE2_CALCULATING(6, false),
    BUILDING_PLAN(7, false),
    COMPLETED(8, true),
    CANCELLED(9, true),
    FAILED(10, true);

    private final int networkId;
    private final boolean terminal;

    CraftingCalculationProgressPhase(int networkId, boolean terminal) {
        this.networkId = networkId;
        this.terminal = terminal;
    }

    public int networkId() {
        return networkId;
    }

    public boolean terminal() {
        return terminal;
    }

    public static CraftingCalculationProgressPhase fromNetworkId(int networkId) {
        return switch (networkId) {
            case 0 -> IDLE;
            case 1 -> QUEUED;
            case 2 -> WAITING_SLOT;
            case 3 -> PREPARING;
            case 4 -> MAX_FAST_COMPILING;
            case 5 -> MAX_FAST_EXECUTING;
            case 6 -> AE2_CALCULATING;
            case 7 -> BUILDING_PLAN;
            case 8 -> COMPLETED;
            case 9 -> CANCELLED;
            case 10 -> FAILED;
            default -> throw new IllegalArgumentException(
                    "Unknown crafting calculation progress phase: " + networkId);
        };
    }
}
