package com.appliedenhancements.api;

/**
 * Marker interface for AE2 {@code StorageCell} implementations whose visible
 * contents should be treated as unbounded by Applied Enhancements.
 *
 * <p>Implement this interface on the runtime storage inventory, not only on the
 * item class. Item-only integrations can instead add their cell item to
 * {@link InfiniteStorageCells#ITEM_TAG}.</p>
 */
public interface InfiniteStorageCellMarker {
}
