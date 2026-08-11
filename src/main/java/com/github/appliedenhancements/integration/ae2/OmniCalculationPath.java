package com.github.appliedenhancements.integration.ae2;

public enum OmniCalculationPath {
    AE2_NATIVE(0),
    MAX_FAST(1),
    AE2_FALLBACK(2),
    ECOAE(3);

    private final int networkId;

    OmniCalculationPath(int networkId) {
        this.networkId = networkId;
    }

    public int networkId() {
        return networkId;
    }

    public static OmniCalculationPath fromNetworkId(int networkId) {
        return switch (networkId) {
            case 0 -> AE2_NATIVE;
            case 1 -> MAX_FAST;
            case 2 -> AE2_FALLBACK;
            case 3 -> ECOAE;
            default -> throw new IllegalArgumentException(
                    "Unknown crafting calculation path: " + networkId);
        };
    }
}
