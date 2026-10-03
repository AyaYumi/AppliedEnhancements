package com.github.appliedenhancements.crafting.aelis;

/** A compiled simulation result is committed only when its batch semantics are known. */
final class AelisOrderedChoiceFallback {
    private AelisOrderedChoiceFallback() {}
    static boolean mayCommitCompiledResult(boolean simulation, boolean batchCertified) {
        return !simulation || batchCertified;
    }
}
