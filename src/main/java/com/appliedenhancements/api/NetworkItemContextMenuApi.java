package com.appliedenhancements.api;

import appeng.api.stacks.AEKey;
import com.appliedenhancements.AppliedEnhancements;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-side extension API for entries in the ME terminal item context menu.
 *
 * <p>Register providers during client setup. Providers are evaluated each time
 * the player opens the menu, so visibility can depend on the current key,
 * amount and crafting state. Actions run on the client and must use the
 * supplied context helpers or their own validated network request for any
 * server-side change.</p>
 */
public final class NetworkItemContextMenuApi {
    private static final ConcurrentHashMap<ResourceLocation, Registration> BY_ID =
            new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<Registration> REGISTRATIONS =
            new CopyOnWriteArrayList<>();

    private NetworkItemContextMenuApi() {
    }

    /** Registers a context-menu entry provider. Higher priorities appear first. */
    public static void register(
            ResourceLocation id, int priority, EntryProvider provider) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(provider, "provider");
        var registration = new Registration(id, priority, provider);
        if (BY_ID.putIfAbsent(id, registration) != null) {
            throw new IllegalArgumentException(
                    "Network item context-menu provider already registered: " + id);
        }
        REGISTRATIONS.add(registration);
        REGISTRATIONS.sort(Comparator
                .comparingInt(Registration::priority)
                .reversed()
                .thenComparing(entry -> entry.id().toString()));
    }

    /** Returns a stable snapshot of all registrations. */
    public static List<Registration> registrations() {
        return List.copyOf(REGISTRATIONS);
    }

    /** Builds all third-party entries for one opened menu. */
    public static List<Entry> entries(Context context) {
        Objects.requireNonNull(context, "context");
        var result = new ArrayList<Entry>();
        for (Registration registration : REGISTRATIONS) {
            List<Entry> provided;
            try {
                provided = registration.provider().createEntries(context);
            } catch (RuntimeException failure) {
                AppliedEnhancements.LOGGER.warn(
                        "Network item context-menu provider {} failed",
                        registration.id(), failure);
                continue;
            }
            if (provided == null || provided.isEmpty()) {
                continue;
            }
            for (Entry entry : provided) {
                if (entry != null) {
                    result.add(entry);
                }
            }
        }
        return List.copyOf(result);
    }

    /** Context and validated built-in actions for the selected terminal entry. */
    public interface Context {
        AEKey key();

        long storedAmount();

        long requestableAmount();

        boolean craftable();

        void extractOne();

        void extractStack();

        void extractAmount(long amount);

        void requestCraft();

        void copyName();

        void copyId();

        void searchSameMod();
    }

    @FunctionalInterface
    public interface EntryProvider {
        List<Entry> createEntries(Context context);
    }

    public record Entry(Component label, Consumer<Context> action) {
        public Entry {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(action, "action");
        }

        public void activate(Context context) {
            action.accept(Objects.requireNonNull(context, "context"));
        }
    }

    public record Registration(
            ResourceLocation id, int priority, EntryProvider provider) {
        public Registration {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(provider, "provider");
        }
    }
}
