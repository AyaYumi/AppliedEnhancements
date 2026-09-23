package com.appliedenhancements.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DataEnergisticsMenuCompatTest {
    @Test
    void delegatesLongConfirmationWhenOptionalMenuApiExists() {
        var menu = new CompatibleAmountMenu();

        assertTrue(DataEnergisticsMenuCompat.confirmLongIfAvailable(
                menu, 10_000_000_000L, true, false));
        assertEquals(10_000_000_000L, menu.confirmedAmount);
        assertTrue(menu.craftMissing);
        assertFalse(menu.autoStart);
    }

    @Test
    void remainsStandaloneWhenOptionalMenuApiIsAbsent() {
        assertFalse(DataEnergisticsMenuCompat.confirmLongIfAvailable(
                new Object(), 10_000_000_000L, false, false));
    }

    @Test
    void restoresExactAmountAndQuantityMode() {
        var confirm = new CompatibleConfirmMenu(10_000_000_000L, QuantityMode.FINAL_TOTAL);
        var amount = new CompatibleAmountMenu();

        assertEquals(10_000_000_000L,
                DataEnergisticsMenuCompat.requestedAmount(confirm).orElseThrow());
        DataEnergisticsMenuCompat.restoreAmountScreen(
                confirm, amount, 10_000_000_000L);

        assertEquals(10_000_000_000L, amount.initialAmount);
        assertEquals(QuantityMode.FINAL_TOTAL, amount.quantityMode);
    }

    public enum QuantityMode {
        NET_NEW,
        FINAL_TOTAL
    }

    public static final class CompatibleAmountMenu {
        long confirmedAmount;
        boolean craftMissing;
        boolean autoStart;
        long initialAmount = 1;
        QuantityMode quantityMode = QuantityMode.NET_NEW;

        public void data_energistics$confirm(
                long amount, boolean craftMissingAmount, boolean shiftStart) {
            confirmedAmount = amount;
            craftMissing = craftMissingAmount;
            autoStart = shiftStart;
        }

        public void data_energistics$setInitialAmount(long amount) {
            initialAmount = amount;
        }

        public void data_energistics$setQuantityMode(QuantityMode mode) {
            quantityMode = mode;
        }
    }

    public static final class CompatibleConfirmMenu {
        private final long dataEnergistics$requestedAmount;
        private final QuantityMode quantityMode;

        CompatibleConfirmMenu(long requestedAmount, QuantityMode quantityMode) {
            this.dataEnergistics$requestedAmount = requestedAmount;
            this.quantityMode = quantityMode;
        }

        public QuantityMode data_energistics$quantityMode() {
            return quantityMode;
        }
    }
}
