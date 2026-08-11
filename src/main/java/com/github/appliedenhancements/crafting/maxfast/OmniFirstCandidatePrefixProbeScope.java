package com.github.appliedenhancements.crafting.maxfast;

/**
 * Keeps a real-capacity probe on the recursively certified candidate-zero
 * graph. Nested ordered nodes must not capacity-mix later candidates while the
 * outer probe is proving a candidate-zero prefix.
 */
final class OmniFirstCandidatePrefixProbeScope {
    private static final ThreadLocal<Integer> DEPTH =
            ThreadLocal.withInitial(() -> 0);

    private OmniFirstCandidatePrefixProbeScope() {
    }

    static Scope enter() {
        DEPTH.set(DEPTH.get() + 1);
        return new Scope();
    }

    static boolean active() {
        return DEPTH.get() > 0;
    }

    static final class Scope implements AutoCloseable {
        private boolean closed;

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            int next = DEPTH.get() - 1;
            if (next <= 0) {
                DEPTH.remove();
            } else {
                DEPTH.set(next);
            }
        }
    }
}
