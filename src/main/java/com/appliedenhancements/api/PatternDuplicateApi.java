package com.appliedenhancements.api;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEKey;
import com.appliedenhancements.AppliedEnhancements;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Public duplicate-output pattern detection and resolver registry. */
public final class PatternDuplicateApi {
    private static final ConcurrentHashMap<ResourceLocation, ResolverRegistration>
            RESOLVERS_BY_ID = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<ResolverRegistration> RESOLVERS =
            new CopyOnWriteArrayList<>();

    private PatternDuplicateApi() {
    }

    /**
     * Registers a custom encoded-pattern resolver.
     *
     * <p>Resolvers run from highest priority to lowest priority before AE2's
     * native decoder. Registration is expected during mod setup. An id can be
     * registered only once.</p>
     */
    public static void registerOutputResolver(
            ResourceLocation id, int priority, PatternOutputResolver resolver) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(resolver, "resolver");
        var registration = new ResolverRegistration(id, priority, resolver);
        if (RESOLVERS_BY_ID.putIfAbsent(id, registration) != null) {
            throw new IllegalArgumentException("Pattern output resolver already registered: " + id);
        }
        RESOLVERS.add(registration);
        RESOLVERS.sort(Comparator
                .comparingInt(ResolverRegistration::priority).reversed()
                .thenComparing(entry -> entry.id().toString()));
    }

    /** Returns a snapshot of custom resolver registrations in invocation order. */
    public static List<ResolverRegistration> registeredOutputResolvers() {
        return List.copyOf(RESOLVERS);
    }

    /** Resolves all output keys. Custom resolvers take precedence over AE2 decoding. */
    public static List<AEKey> resolveOutputs(ItemStack patternStack, Level level) {
        if (patternStack == null || patternStack.isEmpty() || level == null) {
            return List.of();
        }
        for (ResolverRegistration registration : RESOLVERS) {
            try {
                List<AEKey> outputs = sanitize(
                        registration.resolver().resolveOutputs(patternStack, level));
                if (!outputs.isEmpty()) {
                    return outputs;
                }
            } catch (RuntimeException failure) {
                AppliedEnhancements.LOGGER.warn(
                        "Pattern output resolver {} failed for {}",
                        registration.id(), patternStack, failure);
            }
        }
        try {
            var details = PatternDetailsHelper.decodePattern(patternStack, level);
            if (details == null) {
                return List.of();
            }
            var outputs = new ArrayList<AEKey>();
            for (var output : details.getOutputs()) {
                if (output != null && output.what() != null) {
                    outputs.add(output.what());
                }
            }
            return List.copyOf(outputs);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    /** Returns the first output key used for duplicate grouping. */
    public static Optional<AEKey> primaryOutput(ItemStack patternStack, Level level) {
        List<AEKey> outputs = resolveOutputs(patternStack, level);
        return outputs.isEmpty() ? Optional.empty() : Optional.of(outputs.getFirst());
    }

    /** Tests the display names of every resolved output against a lowercase filter. */
    public static boolean outputMatchesSearch(
            ItemStack patternStack, Level level, String lowercaseFilter) {
        if (lowercaseFilter == null || lowercaseFilter.isEmpty()) {
            return true;
        }
        String normalized = lowercaseFilter.toLowerCase(Locale.ROOT);
        for (AEKey output : resolveOutputs(patternStack, level)) {
            if (output.getDisplayName().getString()
                    .toLowerCase(Locale.ROOT)
                    .contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    /** Builds a slot-to-primary-output index for arbitrary terminal entries. */
    public static Map<PatternSlotRef, AEKey> indexPrimaryOutputs(
            Collection<PatternEntry> patterns, Level level) {
        Objects.requireNonNull(patterns, "patterns");
        var outputs = new HashMap<PatternSlotRef, AEKey>();
        for (PatternEntry pattern : patterns) {
            if (pattern == null) {
                continue;
            }
            primaryOutput(pattern.stack(), level)
                    .ifPresent(output -> outputs.put(pattern.slot(), output));
        }
        return Map.copyOf(outputs);
    }

    /** Finds every slot whose primary output occurs at least twice. */
    public static Set<PatternSlotRef> findDuplicateSlots(
            Collection<PatternEntry> patterns, Level level) {
        return findDuplicateSlots(indexPrimaryOutputs(patterns, level));
    }

    /**
     * Finds duplicate values in a prebuilt slot index. This generic overload is
     * useful when an integration already has its own stable grouping key.
     */
    public static <K> Set<PatternSlotRef> findDuplicateSlots(
            Map<PatternSlotRef, K> outputs) {
        Objects.requireNonNull(outputs, "outputs");
        var slotsByOutput = new HashMap<K, ArrayList<PatternSlotRef>>();
        for (var entry : outputs.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                slotsByOutput.computeIfAbsent(
                        entry.getValue(), ignored -> new ArrayList<>())
                        .add(entry.getKey());
            }
        }
        var duplicates = new LinkedHashSet<PatternSlotRef>();
        for (var slots : slotsByOutput.values()) {
            if (slots.size() > 1) {
                duplicates.addAll(slots);
            }
        }
        return Set.copyOf(duplicates);
    }

    private static List<AEKey> sanitize(List<AEKey> outputs) {
        if (outputs == null || outputs.isEmpty()) {
            return List.of();
        }
        return outputs.stream().filter(Objects::nonNull).toList();
    }

    public record PatternEntry(PatternSlotRef slot, ItemStack stack) {
        public PatternEntry {
            Objects.requireNonNull(slot, "slot");
            Objects.requireNonNull(stack, "stack");
        }
    }

    public record ResolverRegistration(
            ResourceLocation id, int priority, PatternOutputResolver resolver) {
        public ResolverRegistration {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(resolver, "resolver");
        }
    }
}
