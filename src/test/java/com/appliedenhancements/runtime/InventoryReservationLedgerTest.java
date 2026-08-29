package com.appliedenhancements.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InventoryReservationLedgerTest {
    @Test
    void acquisitionIsAllOrNothingAcrossKeys() {
        var ledger = new InventoryReservationLedger<String>();
        var first = ledger.tryAcquire(
                Map.of("iron", 100L, "gold", 20L),
                Map.of("iron", 60L, "gold", 10L));

        assertTrue(first.active());
        assertNull(ledger.tryAcquire(
                Map.of("iron", 100L, "gold", 20L),
                Map.of("iron", 20L, "gold", 11L)));
        assertEquals(Map.of("iron", 60L, "gold", 10L), ledger.snapshot());
    }

    @Test
    void ownerCanUseItsShareWhileOtherReservationsRemainProtected() {
        var ledger = new InventoryReservationLedger<String>();
        var first = ledger.tryAcquire(Map.of("iron", 100L), Map.of("iron", 60L));
        var second = ledger.tryAcquire(Map.of("iron", 100L), Map.of("iron", 30L));

        assertEquals(90, ledger.reservedForOthers("iron", null));
        assertEquals(30, ledger.reservedForOthers("iron", first));
        assertEquals(60, ledger.reservedForOthers("iron", second));
    }

    @Test
    void closeReleasesExactlyOnce() {
        var ledger = new InventoryReservationLedger<String>();
        var reservation = ledger.tryAcquire(Map.of("iron", 100L), Map.of("iron", 75L));

        reservation.close();
        reservation.close();

        assertFalse(reservation.active());
        assertTrue(ledger.isEmpty());
    }

    @Test
    void concurrentAcquisitionCannotOverbookInventory() throws Exception {
        var ledger = new InventoryReservationLedger<String>();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var acquired = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (int index = 0; index < 2; index++) {
                executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    if (ledger.tryAcquire(
                            Map.of("iron", 100L), Map.of("iron", 75L)) != null) {
                        acquired.incrementAndGet();
                    }
                    return null;
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertEquals(1, acquired.get());
        assertEquals(Map.of("iron", 75L), ledger.snapshot());
    }

    @Test
    void featureGateIsTheAutomaticPlannerSwitch() throws IOException {
        Path source = Path.of(System.getProperty("user.dir"))
                .resolve("src/main/java/com/appliedenhancements/runtime/ManualCraftingInventoryLock.java");
        String code = Files.readString(source);

        assertTrue(code.contains(
                "Config.ENABLE_AUTOMATIC_MAX_FAST_PLANNER.get()"));
        assertFalse(code.contains("enableManualCraftingInventoryLock"));
    }
}
