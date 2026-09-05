package com.github.appliedenhancements.integration.ae2;

public enum AelisCalculationPath {
    AE2_NATIVE(0),
    AELIS(4),
    AE2_FALLBACK(2),
    EXTERNAL(3);

    private final int networkId;

    AelisCalculationPath(int networkId) {
        this.networkId = networkId;
    }

    public int networkId() {
        return networkId;
    }

    public static AelisCalculationPath fromNetworkId(int networkId) {
        return switch (networkId) {
            case 0 -> AE2_NATIVE;
            case 4 -> AELIS;
            case 2 -> AE2_FALLBACK;
            case 3 -> EXTERNAL;
            default -> throw new IllegalArgumentException(
                    "Unknown crafting calculation path: " + networkId);
        };
    }
}
