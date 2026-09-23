package com.appliedenhancements.runtime;

import com.appliedenhancements.AppliedEnhancements;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.OptionalLong;

/** Optional bridge to Data Energistics' long-sized crafting menu state. */
public final class DataEnergisticsMenuCompat {
    private static final String CONFIRM_METHOD = "data_energistics$confirm";
    private static final String INITIAL_AMOUNT_METHOD =
            "data_energistics$setInitialAmount";
    private static final String QUANTITY_MODE_GETTER =
            "data_energistics$quantityMode";
    private static final String QUANTITY_MODE_SETTER =
            "data_energistics$setQuantityMode";
    private static final String REQUESTED_AMOUNT_FIELD =
            "dataEnergistics$requestedAmount";

    private static final ClassValue<Optional<Method>> CONFIRM = methods(
            CONFIRM_METHOD, long.class, boolean.class, boolean.class);
    private static final ClassValue<Optional<Method>> SET_INITIAL_AMOUNT = methods(
            INITIAL_AMOUNT_METHOD, long.class);
    private static final ClassValue<Optional<Method>> GET_QUANTITY_MODE = methods(
            QUANTITY_MODE_GETTER);
    private static final ClassValue<Optional<Field>> REQUESTED_AMOUNT =
            new ClassValue<>() {
                @Override
                protected Optional<Field> computeValue(Class<?> type) {
                    try {
                        Field field = type.getDeclaredField(REQUESTED_AMOUNT_FIELD);
                        field.setAccessible(true);
                        return Optional.of(field);
                    } catch (ReflectiveOperationException | RuntimeException absent) {
                        return Optional.empty();
                    }
                }
            };

    private DataEnergisticsMenuCompat() {
    }

    /**
     * Lets Data Energistics retain its quantity mode and BigInteger planner
     * context when it is installed. Returns false when its menu API is absent.
     */
    public static boolean confirmLongIfAvailable(
            Object amountMenu, long amount,
            boolean craftMissingAmount, boolean autoStart) {
        Method method = CONFIRM.get(amountMenu.getClass()).orElse(null);
        if (method == null) {
            return false;
        }
        invoke(method, amountMenu, amount, craftMissingAmount, autoStart);
        return true;
    }

    /** Reads the exact request kept by Data Energistics' confirmation menu. */
    public static OptionalLong requestedAmount(Object confirmMenu) {
        Field field = REQUESTED_AMOUNT.get(confirmMenu.getClass()).orElse(null);
        if (field == null) {
            return OptionalLong.empty();
        }
        try {
            long amount = field.getLong(confirmMenu);
            return amount > 0 ? OptionalLong.of(amount) : OptionalLong.empty();
        } catch (IllegalAccessException | RuntimeException unavailable) {
            AppliedEnhancements.LOGGER.debug(
                    "Could not read optional Data Energistics crafting amount",
                    unavailable);
            return OptionalLong.empty();
        }
    }

    /** Restores Data Energistics' long amount and quantity mode after Back. */
    public static void restoreAmountScreen(
            Object confirmMenu, Object amountMenu, long amount) {
        SET_INITIAL_AMOUNT.get(amountMenu.getClass())
                .ifPresent(method -> invoke(method, amountMenu, amount));

        Method getter = GET_QUANTITY_MODE.get(confirmMenu.getClass()).orElse(null);
        if (getter == null) {
            return;
        }
        Object quantityMode = invoke(getter, confirmMenu);
        if (quantityMode == null) {
            return;
        }
        findCompatibleMethod(
                amountMenu.getClass(), QUANTITY_MODE_SETTER, quantityMode.getClass())
                .ifPresent(method -> invoke(method, amountMenu, quantityMode));
    }

    private static ClassValue<Optional<Method>> methods(
            String name, Class<?>... parameterTypes) {
        return new ClassValue<>() {
            @Override
            protected Optional<Method> computeValue(Class<?> type) {
                try {
                    return Optional.of(type.getMethod(name, parameterTypes));
                } catch (ReflectiveOperationException | RuntimeException absent) {
                    return Optional.empty();
                }
            }
        };
    }

    private static Optional<Method> findCompatibleMethod(
            Class<?> type, String name, Class<?> parameterType) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name)
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isAssignableFrom(parameterType)) {
                return Optional.of(method);
            }
        }
        return Optional.empty();
    }

    private static Object invoke(Method method, Object receiver, Object... arguments) {
        try {
            return method.invoke(receiver, arguments);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException(
                    "Could not invoke optional Data Energistics menu API", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(
                    "Optional Data Energistics menu API failed", cause);
        }
    }
}
