package com.appliedenhancements.api;

import com.appliedenhancements.AppliedEnhancements;
import com.appliedenhancements.network.PatternBatchMovePayload;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.network.PacketDistributor;

/** Public client request and server dispatch API for atomic batch pattern moves. */
public final class PatternBatchMoveApi {
    public static final int MAX_SOURCES = 512;
    public static final int MAX_TARGETS = 128;

    private static final ConcurrentHashMap<ResourceLocation, HandlerRegistration>
            HANDLERS_BY_ID = new ConcurrentHashMap<>();
    private static final CopyOnWriteArrayList<HandlerRegistration> HANDLERS =
            new CopyOnWriteArrayList<>();

    private PatternBatchMoveApi() {
    }

    /** Sends a validated move request for the currently open client menu. */
    public static void requestMove(
            int menuId,
            Collection<PatternSlotRef> sources,
            Collection<Long> targetContainerIds,
            int preferredTargetSlot) {
        Request request = new Request(
                menuId,
                copySources(sources),
                copyTargets(targetContainerIds),
                preferredTargetSlot);
        PacketDistributor.sendToServer(new PatternBatchMovePayload(request));
    }

    /**
     * Registers a handler for a custom server menu that cannot implement
     * {@link MenuExtension} directly. Handlers run from highest priority to
     * lowest priority and must perform all slot, permission and capacity checks.
     */
    public static void registerMenuHandler(
            ResourceLocation id, int priority, MenuHandler handler) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(handler, "handler");
        var registration = new HandlerRegistration(id, priority, handler);
        if (HANDLERS_BY_ID.putIfAbsent(id, registration) != null) {
            throw new IllegalArgumentException("Pattern move handler already registered: " + id);
        }
        HANDLERS.add(registration);
        HANDLERS.sort(Comparator
                .comparingInt(HandlerRegistration::priority).reversed()
                .thenComparing(entry -> entry.id().toString()));
    }

    public static List<HandlerRegistration> registeredMenuHandlers() {
        return List.copyOf(HANDLERS);
    }

    /** Dispatches a request against the player's currently open server menu. */
    public static Result execute(ServerPlayer player, Request request) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(request, "request");
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == null || menu.containerId != request.menuId()) {
            return Result.failure(Failure.INVALID_MENU);
        }
        try {
            if (menu instanceof MenuExtension extension) {
                return Objects.requireNonNullElse(
                        extension.movePatterns(player, request),
                        Result.failure(Failure.APPLY_FAILED));
            }
            for (HandlerRegistration registration : HANDLERS) {
                if (registration.handler().supports(menu)) {
                    return Objects.requireNonNullElse(
                            registration.handler().movePatterns(player, menu, request),
                            Result.failure(Failure.APPLY_FAILED));
                }
            }
        } catch (RuntimeException failure) {
            AppliedEnhancements.LOGGER.error(
                    "Pattern batch move handler failed for menu {}",
                    menu.getClass().getName(), failure);
            return Result.failure(Failure.APPLY_FAILED);
        }
        return Result.failure(Failure.INVALID_MENU);
    }

    public interface MenuExtension {
        Result movePatterns(ServerPlayer player, Request request);
    }

    public interface MenuHandler {
        boolean supports(AbstractContainerMenu menu);

        Result movePatterns(
                ServerPlayer player, AbstractContainerMenu menu, Request request);
    }

    public record Request(
            int menuId,
            List<PatternSlotRef> sources,
            List<Long> targetContainerIds,
            int preferredTargetSlot) {
        public Request {
            if (menuId < 0 || preferredTargetSlot < -1) {
                throw new IllegalArgumentException("invalid menu or preferred target slot");
            }
            sources = copySources(sources);
            targetContainerIds = copyTargets(targetContainerIds);
        }
    }

    public enum Failure {
        NONE,
        INVALID_MENU,
        INVALID_SOURCE,
        INVALID_TARGET,
        SAME_TARGET,
        NOT_ENOUGH_SPACE,
        APPLY_FAILED
    }

    public record Result(boolean success, int moved, Failure failure) {
        public Result {
            Objects.requireNonNull(failure, "failure");
            if (moved < 0) {
                throw new IllegalArgumentException("moved must be non-negative");
            }
            if (success && failure != Failure.NONE) {
                throw new IllegalArgumentException("successful result must use NONE");
            }
            if (!success && (failure == Failure.NONE || moved != 0)) {
                throw new IllegalArgumentException(
                        "failed result must use a failure reason and move zero patterns");
            }
        }

        public static Result success(int moved) {
            return new Result(true, moved, Failure.NONE);
        }

        public static Result failure(Failure failure) {
            if (failure == Failure.NONE) {
                throw new IllegalArgumentException("failure must not be NONE");
            }
            return new Result(false, 0, failure);
        }
    }

    public record HandlerRegistration(
            ResourceLocation id, int priority, MenuHandler handler) {
        public HandlerRegistration {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(handler, "handler");
        }
    }

    public static List<PatternSlotRef> copySources(
            Collection<PatternSlotRef> sources) {
        if (sources == null || sources.isEmpty() || sources.size() > MAX_SOURCES) {
            throw new IllegalArgumentException("invalid source count");
        }
        return List.copyOf(sources);
    }

    public static List<Long> copyTargets(Collection<Long> targets) {
        if (targets == null || targets.isEmpty() || targets.size() > MAX_TARGETS) {
            throw new IllegalArgumentException("invalid target count");
        }
        return List.copyOf(targets);
    }
}
