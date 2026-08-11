package com.appliedenhancements.runtime;

import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressHandle;
import org.jetbrains.annotations.ApiStatus;

/**
 * Keeps a progress handle attached to exactly one crafting-planning task.
 *
 * <p>A task still receives a binding when progress display is disabled; its
 * handle is simply {@code null}. This ensures that starting an untracked task
 * detaches the previous task's terminal progress instead of exposing it again
 * if progress display is enabled while the new task is running.
 */
@ApiStatus.Internal
public final class CraftingProgressTaskBinding {
    public static final class Task {
        private final CraftingCalculationProgressHandle progress;

        private Task(CraftingCalculationProgressHandle progress) {
            this.progress = progress;
        }

        public CraftingCalculationProgressHandle progress() {
            return progress;
        }
    }

    private Task current;

    public CraftingProgressTaskBinding() {
    }

    public Task begin(CraftingCalculationProgressHandle progress) {
        clear();
        current = new Task(progress);
        return current;
    }

    public boolean isCurrent(Task task) {
        return current == task;
    }

    public CraftingCalculationProgressHandle currentProgress() {
        return current == null ? null : current.progress();
    }

    public void clear() {
        Task previous = current;
        current = null;
        if (previous != null && previous.progress() != null) {
            previous.progress().cancel();
        }
    }
}
