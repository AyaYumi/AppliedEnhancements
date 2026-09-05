package com.appliedenhancements.runtime;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Optional;

/** Bridges safe Useless processing patterns to Omni's optional batch provider API. */
public final class AelisSmartCycleBatchProvider {
    private static final String USELESS_AE =
            "com.sorrowmist.useless.content.machines.advanced_alloy_furnace.ae.";
    private static final ClassValue<Optional<BridgeTypes>> TYPES = new ClassValue<>() {
        @Override
        protected Optional<BridgeTypes> computeValue(Class<?> providerClass) {
            try {
                ClassLoader loader = providerClass.getClassLoader();
                Class<?> smart = Class.forName(
                        "com.sorrowmist.useless.api.crafting.SmartDoublingCraftingProvider", false, loader);
                Class<?> molecular = Class.forName(
                        "com.atir.molecularmanipulator.integration.ae2.MolecularBatchCraftingProvider", false, loader);
                Class<?> patterns = Class.forName(USELESS_AE + "SmartDoublingPatterns", false, loader);
                Class<?> dynamic = Class.forName(USELESS_AE + "DynamicPatternExecution", false, loader);
                return Optional.of(new BridgeTypes(smart, molecular,
                        patterns.getMethod("maximumSafeMultiplier", IPatternDetails.class),
                        patterns.getMethod("scale", IPatternDetails.class, long.class),
                        dynamic.getMethod("resolve", IPatternDetails.class)));
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                return Optional.empty();
            }
        }
    };

    private AelisSmartCycleBatchProvider() {
    }

    /** The caller must limit this view to providers for an active CPU cycle dispatch. */
    public static ICraftingProvider wrap(ICraftingProvider provider, IPatternDetails pattern) {
        if (provider == null || pattern == null) {
            return provider;
        }
        BridgeTypes types = TYPES.get(provider.getClass()).orElse(null);
        if (types == null || !types.smartProvider().isInstance(provider)
                || types.molecularProvider().isInstance(provider)) {
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
            if (inputs == null || limit <= 1) {
                return provider;
            }
            return (ICraftingProvider) Proxy.newProxyInstance(provider.getClass().getClassLoader(),
                    new Class<?>[] {ICraftingProvider.class, types.molecularProvider()},
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
            Method maximumMultiplier, Method scale, Method dynamicResolve) {
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
                    default -> throw new UnsupportedOperationException(method.getName());
                };
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
}
