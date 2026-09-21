package com.appliedenhancements.runtime;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Bridges safe Useless processing patterns to Omni's optional batch provider API. */
public final class AelisSmartCycleBatchProvider {
    private static final String BIG_INTEGER_ADAPTER_ID =
            "appliedenhancements:useless_multiblock_alloy_furnace";
    private static final String POST_ACCOUNTING_ADAPTER_ID =
            "appliedenhancements:useless_advanced_alloy_furnace";
    private static final String USELESS_AE =
            "com.sorrowmist.useless.content.machines.advanced_alloy_furnace.ae.";
    private static final String USELESS_MULTIBLOCK_PROVIDER =
            "com.sorrowmist.useless.content.blockentities.multiblock.MePatternAssemblyBlockEntity";
    private static final int USEFUL_COIL_TIER = 10;
    /** Keep one native Useless submission comfortably below an AE2 tick. */
    private static final long ADAPTIVE_TARGET_NANOS = 8_000_000L;
    private static volatile long lastDiagnosticNanos;
    private static final AdaptiveSegmentController ADAPTIVE_SEGMENTS =
            new AdaptiveSegmentController();

    private static void logDiagnostic(
            String outcome, ICraftingProvider provider,
            BigInteger requested, BigInteger admitted,
            Integer coilTier, Integer threads) {
        long now = System.nanoTime();
        if (now - lastDiagnosticNanos < 2_000_000_000L) {
            return;
        }
        lastDiagnosticNanos = now;
        com.appliedenhancements.AppliedEnhancements.LOGGER.info(
                "Useless alloy-furnace bigint adapter: outcome={}, provider={}, requested={}, admitted={}, coilTier={}, freeThreads={}",
                outcome,
                provider == null ? "null" : provider.getClass().getName(),
                requested, admitted, coilTier, threads);
    }
    private static final ClassValue<Optional<ImmediateOutputFlush>> IMMEDIATE_OUTPUT_FLUSH =
            new ClassValue<>() {
                @Override
                protected Optional<ImmediateOutputFlush> computeValue(Class<?> providerClass) {
                    try {
                        Method controller = null;
                        Class<?> managerOwner = providerClass;
                        if (USELESS_MULTIBLOCK_PROVIDER.equals(providerClass.getName())) {
                            controller = providerClass.getMethod("getController");
                            managerOwner = controller.getReturnType();
                        }
                        Field manager = findField(managerOwner, "aeManager");
                        if (manager == null || !manager.trySetAccessible()) {
                            return Optional.empty();
                        }
                        Method flush = manager.getType().getDeclaredMethod(
                                "flushQueuedCraftingOutputs", boolean.class);
                        if (!flush.trySetAccessible()) {
                            return Optional.empty();
                        }
                        return Optional.of(new ImmediateOutputFlush(
                                controller, manager, flush));
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                        return Optional.empty();
                    }
                }
            };
    private static final ClassValue<Optional<BridgeTypes>> TYPES = new ClassValue<>() {
        @Override
        protected Optional<BridgeTypes> computeValue(Class<?> providerClass) {
            try {
                ClassLoader loader = providerClass.getClassLoader();
                Class<?> smart = Class.forName(
                        "com.sorrowmist.useless.api.crafting.SmartDoublingCraftingProvider", false, loader);
                Class<?> molecular = Class.forName(
                        "com.atir.molecularmanipulator.integration.ae2.MolecularBatchCraftingProvider", false, loader);
                Class<?> bigInteger = Class.forName(
                        "com.atir.molecularmanipulator.api.crafting.OmniBigIntegerCraftingProvider", false, loader);
                Class<?> patterns = Class.forName(USELESS_AE + "SmartDoublingPatterns", false, loader);
                Class<?> dynamic = Class.forName(USELESS_AE + "DynamicPatternExecution", false, loader);
                BigIntegerAccess bigIntegerAccess = createBigIntegerAccess(providerClass);
                return Optional.of(new BridgeTypes(smart, molecular,
                        bigInteger, bigIntegerAccess,
                        patterns.getMethod("maximumSafeMultiplier", IPatternDetails.class),
                        patterns.getMethod("operationsPerPush", IPatternDetails.class),
                        patterns.getMethod("scale", IPatternDetails.class, long.class),
                        dynamic.getMethod("resolve", IPatternDetails.class)));
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                return Optional.empty();
            }
        }
    };

    private AelisSmartCycleBatchProvider() {
    }

    /** Registers all optional Useless alloy-furnace bridges with Omni. */
    public static void registerOmniAdapters() {
        registerBigIntegerProviderAdapter();
        registerPostAccountingOutputAdapter();
    }

    private static void registerBigIntegerProviderAdapter() {
        try {
            ClassLoader loader = AelisSmartCycleBatchProvider.class.getClassLoader();
            Class<?> registry = Class.forName(
                    "com.atir.molecularmanipulator.api.crafting."
                            + "OmniBigIntegerProviderAdapterRegistry",
                    false, loader);
            Method register = registry.getMethod("register", String.class,
                    int.class, BiPredicate.class, BiFunction.class);
            BiPredicate<ICraftingProvider, IPatternDetails> supports =
                    AelisSmartCycleBatchProvider::supportsBigInteger;
            BiFunction<ICraftingProvider, IPatternDetails, Object> factory =
                    AelisSmartCycleBatchProvider::adaptBigInteger;
            register.invoke(null, BIG_INTEGER_ADAPTER_ID, 100,
                    supports, factory);
        } catch (ClassNotFoundException unavailable) {
            // OmniSequence is optional for AppliedEnhancements.
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            com.appliedenhancements.AppliedEnhancements.LOGGER.warn(
                    "Could not register the Useless alloy-furnace bigint adapter",
                    failure);
        }
    }

    private static Object adaptBigInteger(
            ICraftingProvider provider, IPatternDetails pattern) {
        if (!supportsBigInteger(provider, pattern)) {
            return null;
        }
        BridgeTypes types = TYPES.get(provider.getClass()).orElse(null);
        if (types == null) {
            return null;
        }
        try {
            ExactInput[] inputs = exactInputs(pattern);
            return Proxy.newProxyInstance(provider.getClass().getClassLoader(),
                    new Class<?>[] {
                            ICraftingProvider.class,
                            types.bigIntegerProvider()
                    }, new BatchHandler(provider, pattern,
                            inputs,
                            (long) types.maximumMultiplier().invoke(null, pattern),
                            types));
        } catch (ReflectiveOperationException | RuntimeException
                | LinkageError unsupported) {
            return null;
        }
    }

    private static boolean supportsBigInteger(
            ICraftingProvider provider, IPatternDetails pattern) {
        if (provider == null || pattern == null) {
            return false;
        }
        BridgeTypes types = TYPES.get(provider.getClass()).orElse(null);
        if (types == null || types.bigIntegerAccess() == null
                || !types.smartProvider().isInstance(provider)
                || !USELESS_MULTIBLOCK_PROVIDER.equals(
                        provider.getClass().getName())) {
            return false;
        }
        try {
            return types.bigIntegerAccess().isEligible(provider)
                    && types.dynamicResolve().invoke(null, pattern) == null
                    && exactInputs(pattern) != null;
        } catch (ReflectiveOperationException | RuntimeException
                | LinkageError unsupported) {
            return false;
        }
    }

    /** Registers the optional Useless alloy-furnace output bridge with Omni. */
    public static void registerPostAccountingOutputAdapter() {
        try {
            ClassLoader loader = AelisSmartCycleBatchProvider.class.getClassLoader();
            Class<?> registry = Class.forName(
                    "com.atir.molecularmanipulator.api.crafting."
                            + "OmniPostAccountingOutputAdapterRegistry",
                    false, loader);
            Method register = registry.getMethod("register", String.class, int.class,
                    Predicate.class, Consumer.class);
            Predicate<ICraftingProvider> supports =
                    AelisSmartCycleBatchProvider::supportsImmediateOutputFlush;
            Consumer<ICraftingProvider> flush =
                    AelisSmartCycleBatchProvider::flushOutputsAfterCpuAccounting;
            register.invoke(null, POST_ACCOUNTING_ADAPTER_ID, 100, supports, flush);
        } catch (ClassNotFoundException unavailable) {
            // OmniSequence is optional for AppliedEnhancements.
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            com.appliedenhancements.AppliedEnhancements.LOGGER.warn(
                    "Could not register the Useless alloy-furnace immediate-output adapter",
                    failure);
        }
    }

    /** The caller must limit this view to providers for an active CPU cycle dispatch. */
    public static ICraftingProvider wrap(ICraftingProvider provider, IPatternDetails pattern) {
        return wrap(provider, pattern, true);
    }

    /**
     * Exposes only the exact-count capability. Omni still requires an active
     * exact job whose real inputs are all backed by marked infinite sources.
     */
    public static ICraftingProvider wrapBigInteger(
            ICraftingProvider provider, IPatternDetails pattern) {
        return wrap(provider, pattern, false);
    }

    private static ICraftingProvider wrap(
            ICraftingProvider provider, IPatternDetails pattern,
            boolean includeLongBatching) {
        if (provider == null || pattern == null) {
            return provider;
        }
        BridgeTypes types = TYPES.get(provider.getClass()).orElse(null);
        if (types == null || !types.smartProvider().isInstance(provider)
                || types.molecularProvider().isInstance(provider)
                || types.bigIntegerProvider().isInstance(provider)) {
            return provider;
        }
        try {
            // Useless registers dynamic output claims outside provider.pushPattern using
            // the original pattern's multiplier. Scaling inside this proxy would undercount
            // those claims, so leave dynamic patterns on their original dispatch path.
            if (types.dynamicResolve().invoke(null, pattern) != null) {
                return provider;
            }
            ExactInput[] inputs = exactInputs(pattern);
            long limit = (long) types.maximumMultiplier().invoke(null, pattern);
            if (inputs == null
                    || (includeLongBatching && limit <= 1
                            && types.bigIntegerAccess() == null)
                    || (!includeLongBatching
                            && types.bigIntegerAccess() == null)) {
                return provider;
            }
            Class<?>[] interfaces = includeLongBatching
                    && types.bigIntegerAccess() == null
                    ? new Class<?>[] {ICraftingProvider.class, types.molecularProvider()}
                    : includeLongBatching
                            ? new Class<?>[] {ICraftingProvider.class,
                                    types.molecularProvider(),
                                    types.bigIntegerProvider()}
                            : new Class<?>[] {ICraftingProvider.class,
                                    types.bigIntegerProvider()};
            return (ICraftingProvider) Proxy.newProxyInstance(provider.getClass().getClassLoader(),
                    interfaces,
                    new BatchHandler(provider, pattern, inputs, limit, types));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unsupported) {
            return provider;
        }
    }

    private static ExactInput[] exactInputs(IPatternDetails pattern) {
        var inputs = pattern.getInputs();
        if (inputs == null || inputs.length == 0) {
            return null;
        }
        var result = new ExactInput[inputs.length];
        for (int index = 0; index < inputs.length; index++) {
            var input = inputs[index];
            if (input == null || input.getMultiplier() <= 0) {
                return null;
            }
            var choices = input.getPossibleInputs();
            if (choices == null || choices.length != 1 || choices[0] == null
                    || choices[0].what() == null || choices[0].amount() <= 0
                    || input.getRemainingKey(choices[0].what()) != null) {
                return null;
            }
            result[index] = new ExactInput(choices[0].what(),
                    Math.multiplyExact(choices[0].amount(), input.getMultiplier()));
        }
        return result;
    }

    private record ExactInput(AEKey key, long amount) {
    }

    private record BridgeTypes(Class<?> smartProvider, Class<?> molecularProvider,
            Class<?> bigIntegerProvider, BigIntegerAccess bigIntegerAccess,
            Method maximumMultiplier, Method operationsPerPush,
            Method scale, Method dynamicResolve) {
    }

    private record BatchHandler(ICraftingProvider provider, IPatternDetails pattern,
            ExactInput[] inputs, long limit, BridgeTypes types) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == arguments[0]
                            || arguments[0] != null && Proxy.isProxyClass(arguments[0].getClass())
                            && Proxy.getInvocationHandler(arguments[0]) instanceof BatchHandler other
                            && provider == other.provider
                            && pattern.getDefinition().equals(other.pattern.getDefinition());
                    case "hashCode" -> 31 * System.identityHashCode(provider) + pattern.getDefinition().hashCode();
                    case "toString" -> "AelisSmartCycleBatchProvider[" + provider + "]";
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }
            if (method.getDeclaringClass() == types.molecularProvider()) {
                return switch (method.getName()) {
                    case "molecularmanipulator$supportsBatching" -> matches((IPatternDetails) arguments[0]);
                    case "molecularmanipulator$getBatchLimit" -> matches((IPatternDetails) arguments[0]) ? limit : 0L;
                    case "molecularmanipulator$supportsReusableBatching" -> false;
                    case "molecularmanipulator$flushOutputsAfterCpuAccounting" -> {
                        flushOutputsAfterCpuAccounting(provider);
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }
            if (method.getDeclaringClass() == types.bigIntegerProvider()) {
                if (!matches((IPatternDetails) arguments[0])
                        || types.bigIntegerAccess() == null) {
                    return method.getReturnType() == boolean.class
                            ? false : BigInteger.ZERO;
                }
                return switch (method.getName()) {
                    case "getMaximumBigIntegerCrafts" ->
                            types.bigIntegerAccess().maximumCrafts(
                                    provider, pattern,
                                    (KeyCounter[]) arguments[1],
                                    (BigInteger) arguments[2]);
                    case "pushBigIntegerCraftingPattern" ->
                            types.bigIntegerAccess().push(
                                    provider, pattern,
                                    (BigInteger) arguments[1],
                                    (KeyCounter[]) arguments[2]);
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }
            if (method.getName().equals("flushOutputsAfterCpuAccounting")
                    && method.getParameterCount() == 0) {
                flushOutputsAfterCpuAccounting(provider);
                return null;
            }
            if (method.getName().equals("pushPattern")) {
                if (!matches((IPatternDetails) arguments[0])) {
                    return false;
                }
                var holders = (KeyCounter[]) arguments[1];
                long crafts = actualCrafts(holders);
                if (crafts <= 0 || crafts > limit) {
                    return false;
                }
                IPatternDetails dispatched = pattern;
                if (crafts > 1) {
                    try {
                        dispatched = (IPatternDetails) types.scale().invoke(null, pattern, crafts);
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                        return false;
                    }
                }
                return provider.pushPattern(dispatched, holders);
            }
            try {
                return method.invoke(provider, arguments);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        }

        private boolean matches(IPatternDetails candidate) {
            return candidate != null && pattern.getDefinition().equals(candidate.getDefinition());
        }

        private long actualCrafts(KeyCounter[] holders) {
            if (holders == null || holders.length != inputs.length) {
                return 0;
            }
            long crafts = 0;
            for (int index = 0; index < inputs.length; index++) {
                if (holders[index] == null) {
                    return 0;
                }
                var entries = holders[index].iterator();
                if (!entries.hasNext()) {
                    return 0;
                }
                var entry = entries.next();
                var input = inputs[index];
                long amount = entry.getLongValue();
                if (entries.hasNext() || !input.key().equals(entry.getKey()) || amount <= 0
                        || amount % input.amount() != 0) {
                    return 0;
                }
                long count = amount / input.amount();
                if (crafts != 0 && crafts != count) {
                    return 0;
                }
                crafts = count;
            }
            return crafts;
        }
    }

    private static boolean supportsImmediateOutputFlush(ICraftingProvider wrapped) {
        BatchHandler handler = batchHandler(wrapped);
        ICraftingProvider provider = handler == null ? wrapped : handler.provider();
        return provider != null
                && USELESS_MULTIBLOCK_PROVIDER.equals(provider.getClass().getName())
                && IMMEDIATE_OUTPUT_FLUSH.get(provider.getClass()).isPresent();
    }

    private static void flushOutputsAfterCpuAccounting(ICraftingProvider wrapped) {
        BatchHandler handler = batchHandler(wrapped);
        ICraftingProvider provider = handler == null ? wrapped : handler.provider();
        var access = IMMEDIATE_OUTPUT_FLUSH.get(provider.getClass()).orElseThrow(
                () -> new IllegalStateException(
                        "No immediate output flush is available for " + provider.getClass().getName()));
        access.flush(provider);
    }

    private static BatchHandler batchHandler(ICraftingProvider provider) {
        if (provider == null || !Proxy.isProxyClass(provider.getClass())) {
            return null;
        }
        try {
            return Proxy.getInvocationHandler(provider) instanceof BatchHandler handler
                    ? handler : null;
        } catch (IllegalArgumentException unavailable) {
            return null;
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private record ImmediateOutputFlush(
            Method controller, Field manager, Method flush) {
        private void flush(ICraftingProvider provider) {
            try {
                Object owner = controller == null
                        ? provider : controller.invoke(provider);
                if (owner == null) {
                    throw new IllegalStateException(
                            "Alloy-furnace controller is unavailable");
                }
                Object target = manager.get(owner);
                if (target == null) {
                    throw new IllegalStateException("Alloy-furnace AE manager is unavailable");
                }
                flush.invoke(target, true);
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException("Alloy-furnace output flush failed", cause);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Alloy-furnace output flush failed", failure);
            }
        }
    }

    private static BigIntegerAccess createBigIntegerAccess(Class<?> providerClass)
            throws ReflectiveOperationException {
        if (!USELESS_MULTIBLOCK_PROVIDER.equals(providerClass.getName())) {
            return null;
        }
        Method getController = providerClass.getMethod("getController");
        Class<?> controllerClass = getController.getReturnType();
        Method remainingThreads;
        try {
            remainingThreads = controllerClass.getMethod(
                    "getRemainingAETaskCount", boolean.class);
        } catch (NoSuchMethodException unavailable) {
            remainingThreads = null;
        }
        Class<?> patterns = Class.forName(
                USELESS_AE + "SmartDoublingPatterns", false,
                providerClass.getClassLoader());
        Class<?> tickBudget = Class.forName(
                USELESS_AE + "AlloyFurnaceTickBudget", false,
                providerClass.getClassLoader());
        return new BigIntegerAccess(
                getController,
                controllerClass.getMethod("isFormed"),
                controllerClass.getMethod("getCoilTier"),
                controllerClass.getMethod("getMaxAETaskCount"),
                remainingThreads,
                patterns.getMethod("operationsPerPush", IPatternDetails.class),
                tickBudget.getMethod("applyScale", BigInteger.class),
                providerClass.getMethod("pushBigIntegerCraftingPattern",
                        IPatternDetails.class, BigInteger.class,
                        KeyCounter[].class));
    }

    private record BigIntegerAccess(
            Method getController,
            Method isFormed,
            Method getCoilTier,
            Method getMaxAETaskCount,
            Method getRemainingAETaskCount,
            Method operationsPerPush,
            Method applyTickBudgetScale,
            Method pushBigInteger) {
        private boolean isEligible(ICraftingProvider provider) {
            try {
                Object controller = getController.invoke(provider);
                return controller != null
                        && (boolean) isFormed.invoke(controller)
                        && (int) getCoilTier.invoke(controller)
                                == USEFUL_COIL_TIER;
            } catch (ReflectiveOperationException | RuntimeException failure) {
                return false;
            }
        }

        private BigInteger maximumCrafts(
                ICraftingProvider provider, IPatternDetails pattern,
                KeyCounter[] unitPrototype, BigInteger requested) {
            if (requested == null || requested.signum() <= 0) {
                return BigInteger.ZERO;
            }
            try {
                Object controller = getController.invoke(provider);
                boolean formed = controller != null
                        && (boolean) isFormed.invoke(controller);
                int coil = controller == null
                        ? 0 : (int) getCoilTier.invoke(controller);
                if (!formed || coil != USEFUL_COIL_TIER) {
                    logDiagnostic("ineligible-machine", provider,
                            requested, BigInteger.ZERO, coil, 0);
                    return BigInteger.ZERO;
                }
                int remainingThreads = Math.max(0, getRemainingAETaskCount == null
                        ? (int) getMaxAETaskCount.invoke(controller)
                        : (int) getRemainingAETaskCount.invoke(
                                controller, true));
                int machineThreads = Math.max(1,
                        (int) getMaxAETaskCount.invoke(controller));
                long operations = (long) operationsPerPush.invoke(null, pattern);
                if (operations <= 0) {
                    return BigInteger.ZERO;
                }
                BigInteger targetSegments = ADAPTIVE_SEGMENTS.target(
                        provider, pattern, machineThreads);
                BigInteger admitted = maximumWindowedCrafts(
                        pattern, unitPrototype, requested,
                        remainingThreads, machineThreads, applyTickBudgetScale,
                        operations, targetSegments);
                logDiagnostic("capacity", provider, requested,
                        admitted, coil, remainingThreads);
                return admitted;
            } catch (ReflectiveOperationException | RuntimeException failure) {
                logDiagnostic("capacity-error", provider, requested,
                        BigInteger.ZERO, null, null);
                return BigInteger.ZERO;
            }
        }

        private boolean push(
                ICraftingProvider provider, IPatternDetails pattern,
                BigInteger count, KeyCounter[] unitPrototype) throws Throwable {
            if (count == null || count.signum() <= 0
                    || maximumCrafts(provider, pattern, unitPrototype, count)
                            .compareTo(count) < 0) {
                return false;
            }
            long started = System.nanoTime();
            try {
                long scale = (long) operationsPerPush.invoke(null, pattern);
                if (scale <= 0) {
                    return false;
                }
                // Omni's exact ledger counts one smart-doubling pattern as one
                // logical craft. Useless' native BigInteger entry point counts
                // the underlying physical operations, so preserve that scale
                // when handing the batch back to the machine.
                BigInteger physicalCrafts = count.multiply(
                        BigInteger.valueOf(scale));
                boolean accepted = (boolean) pushBigInteger.invoke(
                        provider, pattern, physicalCrafts, unitPrototype);
                ADAPTIVE_SEGMENTS.record(provider, pattern, machineThreads(provider),
                        accepted, System.nanoTime() - started);
                logDiagnostic(accepted ? "push-accepted" : "push-rejected",
                        provider, count, accepted ? count : BigInteger.ZERO,
                        null, null);
                return accepted;
            } catch (InvocationTargetException failure) {
                ADAPTIVE_SEGMENTS.record(provider, pattern, machineThreads(provider),
                        false, System.nanoTime() - started);
                throw failure.getCause();
            } catch (RuntimeException | LinkageError failure) {
                ADAPTIVE_SEGMENTS.record(provider, pattern, machineThreads(provider),
                        false, System.nanoTime() - started);
                throw failure;
            }
        }

        private int machineThreads(ICraftingProvider provider) {
            try {
                Object controller = getController.invoke(provider);
                return Math.max(1, (int) getMaxAETaskCount.invoke(controller));
            } catch (ReflectiveOperationException | RuntimeException failure) {
                return 1;
            }
        }

    }

    static BigInteger maximumWindowedCrafts(
            IPatternDetails pattern, KeyCounter[] unitPrototype,
            BigInteger requested, int threads) {
        return maximumWindowedCrafts(pattern, unitPrototype, requested,
                threads, threads, null);
    }

    static BigInteger maximumWindowedCrafts(
            IPatternDetails pattern, KeyCounter[] unitPrototype,
            BigInteger requested, int remainingThreads, int machineThreads,
            Method applyTickBudgetScale, long operationsPerPush,
            BigInteger targetPhysicalSegments) {
        return maximumWindowedCrafts(pattern, unitPrototype, requested,
                remainingThreads, machineThreads, applyTickBudgetScale,
                operationsPerPush, targetPhysicalSegments, true);
    }

    private static BigInteger maximumWindowedCrafts(
            IPatternDetails pattern, KeyCounter[] unitPrototype,
            BigInteger requested, int remainingThreads, int machineThreads,
            Method applyTickBudgetScale) {
        return maximumWindowedCrafts(pattern, unitPrototype, requested,
                remainingThreads, machineThreads, applyTickBudgetScale,
                1L, null, false);
    }

    private static BigInteger maximumWindowedCrafts(
            IPatternDetails pattern, KeyCounter[] unitPrototype,
            BigInteger requested, int remainingThreads, int machineThreads,
            Method applyTickBudgetScale, long operationsPerPush,
            BigInteger targetPhysicalSegments, boolean adaptive) {
        if (pattern == null || unitPrototype == null
                || requested == null || requested.signum() <= 0
                || remainingThreads <= 0 || machineThreads <= 0
                || operationsPerPush <= 0) {
            return BigInteger.ZERO;
        }
        BigInteger operationScale = BigInteger.valueOf(operationsPerPush);
        BigInteger inputLimit = null;
        for (var counter : unitPrototype) {
            if (counter == null) {
                return BigInteger.ZERO;
            }
            for (var entry : counter) {
                if (entry.getKey() == null || entry.getLongValue() <= 0) {
                    return BigInteger.ZERO;
                }
                BigInteger candidate = BigInteger.valueOf(Long.MAX_VALUE)
                        .multiply(BigInteger.valueOf(Math.max(1, machineThreads)))
                        .divide(BigInteger.valueOf(entry.getLongValue())
                                .multiply(operationScale));
                inputLimit = inputLimit == null
                        ? candidate : inputLimit.min(candidate);
            }
        }
        if (inputLimit == null) {
            return BigInteger.ZERO;
        }

        BigInteger limit = requested.min(inputLimit);

        BigInteger segmentedWindow = BigInteger.valueOf(Long.MAX_VALUE)
                .multiply(BigInteger.valueOf(Math.max(1, machineThreads)));
        if (adaptive && targetPhysicalSegments != null
                && targetPhysicalSegments.signum() > 0) {
            BigInteger segmentCount = targetPhysicalSegments.min(
                    BigInteger.valueOf(Math.max(1, machineThreads)));
            segmentedWindow = BigInteger.valueOf(Long.MAX_VALUE)
                    .multiply(segmentCount);
        }
        for (var output : pattern.getOutputs()) {
            if (output == null || output.what() == null
                    || output.amount() <= 0) {
                return BigInteger.ZERO;
            }
            limit = limit.min(segmentedWindow.divide(BigInteger.valueOf(
                    output.amount()).multiply(operationScale)));
        }
        if (applyTickBudgetScale != null) {
            try {
                limit = ((BigInteger) applyTickBudgetScale.invoke(null, limit))
                        .max(BigInteger.ONE);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Useless' native push still performs its own validation.
            }
        }
        return limit.max(BigInteger.ZERO);
    }

    /** Per-provider/pattern feedback controller; weak keys avoid retaining worlds or jobs. */
    static final class AdaptiveSegmentController {
        private final Map<ICraftingProvider, Map<IPatternDetails, State>> states =
                new WeakHashMap<>();

        synchronized BigInteger target(ICraftingProvider provider,
                IPatternDetails pattern, int machineThreads) {
            State state = state(provider, pattern);
            BigInteger maximum = BigInteger.valueOf(Math.max(1, machineThreads));
            return state.target.min(maximum).max(BigInteger.ONE);
        }

        synchronized void record(ICraftingProvider provider, IPatternDetails pattern,
                int machineThreads, boolean accepted, long elapsedNanos) {
            State state = state(provider, pattern);
            if (!accepted) {
                state.target = state.target.max(BigInteger.ONE).shiftRight(1).max(BigInteger.ONE);
                return;
            }
            long elapsed = Math.max(1L, elapsedNanos);
            state.ewmaNanos = state.ewmaNanos == 0
                    ? elapsed
                    : (state.ewmaNanos * 3L + elapsed) / 4L;
            state.target = nextTarget(state.target, state.ewmaNanos)
                    .min(BigInteger.valueOf(Math.max(1, machineThreads)))
                    .max(BigInteger.ONE);
        }

        synchronized void clear() {
            states.clear();
        }

        private State state(ICraftingProvider provider, IPatternDetails pattern) {
            Map<IPatternDetails, State> byPattern = states.computeIfAbsent(
                    provider, ignored -> new WeakHashMap<>());
            return byPattern.computeIfAbsent(pattern, ignored -> new State());
        }

        static BigInteger nextTarget(BigInteger current, long elapsedNanos) {
            BigInteger target = current == null || current.signum() <= 0
                    ? BigInteger.ONE : current;
            long elapsed = Math.max(1L, elapsedNanos);
            if (elapsed < ADAPTIVE_TARGET_NANOS / 2L) {
                return target.shiftLeft(1);
            }
            if (elapsed > ADAPTIVE_TARGET_NANOS) {
                BigInteger scaled = target.multiply(BigInteger.valueOf(
                        ADAPTIVE_TARGET_NANOS)).divide(BigInteger.valueOf(elapsed));
                return scaled.max(BigInteger.ONE).min(target);
            }
            return target;
        }

        private static final class State {
            private BigInteger target = BigInteger.ONE;
            private long ewmaNanos;
        }
    }
}
