package com.github.appliedenhancements.crafting.aelis;

import java.util.Objects;

/**
 * Executes an already-policy-limited prefix of ordered crafting candidates.
 *
 * <p>The attempt owns its transaction: it returns {@code true} only after an
 * applied candidate has committed its staged inventory, missing-item and
 * possible-state changes. A shortage or compatibility fallback returns
 * {@code false} without committing. The caller remains the single owner of the
 * native fallback.</p>
 */
final class AelisOrderedCandidateTrial {
    @FunctionalInterface
    interface Attempt {
        boolean tryCandidate(int candidateIndex) throws InterruptedException;
    }

    private AelisOrderedCandidateTrial() {
    }

    static boolean tryInOrder(int candidateLimit, Attempt attempt)
            throws InterruptedException {
        Objects.requireNonNull(attempt, "attempt");
        if (candidateLimit <= 0) {
            return false;
        }

        for (int candidateIndex = 0;
                candidateIndex < candidateLimit; candidateIndex++) {
            if (attempt.tryCandidate(candidateIndex)) {
                return true;
            }
        }
        return false;
    }
}
