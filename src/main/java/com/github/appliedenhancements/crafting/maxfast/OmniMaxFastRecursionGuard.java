package com.github.appliedenhancements.crafting.maxfast;

/**
 * Per-thread depth guard for the contextual transactional executor.
 *
 * <p>The transactional method has a relatively large Java frame, so use a
 * deliberately conservative limit instead of relying on the VM to throw an
 * unrecoverable {@link StackOverflowError}.</p>
 */
final class OmniMaxFastRecursionGuard {
    static final int MAX_TRANSACTIONAL_DEPTH = 256;

    private static final ThreadLocal<Integer> TRANSACTIONAL_DEPTH =
            ThreadLocal.withInitial(() -> 0);

    private OmniMaxFastRecursionGuard() {
    }

    static boolean tryEnterTransactional() {
        int depth = TRANSACTIONAL_DEPTH.get();
        if (depth >= MAX_TRANSACTIONAL_DEPTH) {
            return false;
        }
        TRANSACTIONAL_DEPTH.set(depth + 1);
        return true;
    }

    static void exitTransactional() {
        int depth = TRANSACTIONAL_DEPTH.get();
        if (depth <= 1) {
            TRANSACTIONAL_DEPTH.remove();
        } else {
            TRANSACTIONAL_DEPTH.set(depth - 1);
        }
    }

    static int currentTransactionalDepth() {
        return TRANSACTIONAL_DEPTH.get();
    }
}
