package com.appliedenhancements.api;

import com.appliedenhancements.AppliedEnhancements;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.resources.ResourceLocation;

/**
 * Registry for pattern-terminal screens that use an AE2-compatible row model.
 *
 * <p>A registered screen automatically receives Applied Enhancements' duplicate
 * filter and quick-move controls when it inherits the corresponding supported
 * base screen and keeps that family's slot/header layout. Completely custom
 * menus can still use {@link PatternDuplicateApi} and
 * {@link PatternBatchMoveApi#registerMenuHandler} directly.</p>
 */
public final class PatternTerminalIntegrationApi {
    private static final ConcurrentHashMap<ResourceLocation, Registration>
            BY_ID = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<Registration> REGISTRATIONS =
            new CopyOnWriteArrayList<>();

    static {
        registerBuiltin("ae2_pattern_access", Family.AE2_PATTERN_ACCESS,
                "appeng.client.gui.me.patternaccess.PatternAccessTermScreen");
        registerBuiltin("ae2wtlib_wireless_pattern_access", Family.AE2_PATTERN_ACCESS,
                "de.mari_023.ae2wtlib.wat.WATScreen");
        registerBuiltin("extendedae_pattern_access", Family.EXTENDEDAE_PATTERN_ACCESS,
                "com.glodblock.github.extendedae.client.gui.GuiExPatternTerminal");
        registerBuiltin("extendedae_wireless_pattern_access", Family.EXTENDEDAE_PATTERN_ACCESS,
                "com.glodblock.github.extendedae.xmod.wt.GuiWirelessExPAT");
    }

    private PatternTerminalIntegrationApi() {
    }

    /** Registers one exact client screen class. Call during client setup. */
    public static void register(
            ResourceLocation id, Family family, String screenClassName) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(family, "family");
        if (screenClassName == null || screenClassName.isBlank()) {
            throw new IllegalArgumentException("screenClassName must not be blank");
        }
        var registration = new Registration(id, family, screenClassName);
        if (BY_ID.putIfAbsent(id, registration) != null) {
            throw new IllegalArgumentException("Pattern terminal already registered: " + id);
        }
        REGISTRATIONS.add(registration);
        REGISTRATIONS.sort(Comparator.comparing(entry -> entry.id().toString()));
    }

    public static List<Registration> registrations() {
        return List.copyOf(REGISTRATIONS);
    }

    public static boolean supports(Object screen, Family family) {
        return screen != null && supports(screen.getClass().getName(), family);
    }

    public static boolean supports(String screenClassName, Family family) {
        for (Registration registration : REGISTRATIONS) {
            if (registration.family() == family
                    && registration.screenClassName().equals(screenClassName)) {
                return true;
            }
        }
        return false;
    }

    private static void registerBuiltin(String path, Family family, String className) {
        register(AppliedEnhancements.id(path), family, className);
    }

    public enum Family {
        AE2_PATTERN_ACCESS,
        EXTENDEDAE_PATTERN_ACCESS
    }

    public record Registration(
            ResourceLocation id, Family family, String screenClassName) {
        public Registration {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(family, "family");
            if (screenClassName == null || screenClassName.isBlank()) {
                throw new IllegalArgumentException("screenClassName must not be blank");
            }
        }
    }
}
