package com.appliedenhancements.mixin;

import static org.junit.jupiter.api.Assertions.*;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import com.appliedenhancements.Config;
import com.appliedenhancements.CraftingOrderMode;
import com.appliedenhancements.api.AelisCraftingRequest;
import com.appliedenhancements.api.AelisExactRequest;
import com.appliedenhancements.runtime.CraftingPlannerIntervention;
import com.appliedenhancements.runtime.CraftingSubmissionScope;
import com.appliedenhancements.runtime.ExactRequestScope;
import com.appliedenhancements.test.TestAEKey;
import com.github.appliedenhancements.integration.ae2.CraftingCalculationProgressHandle;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.MethodRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.ClassNode;

/** Execute the actual Mixin handlers against small host fixtures without starting Minecraft. */
public class CraftingRequestRoutingTest {
    private static final AEKey OUTPUT = new TestAEKey("submitted_output");
    private static final CalculationStrategy STRATEGY = CalculationStrategy.REPORT_MISSING_ITEMS;
    private Field cached;
    private Object oldMode, oldProgress, oldAutomatic;

    @BeforeEach
    void configureInputWithoutEnablingAelis() throws Exception {
        cached = ModConfigSpec.ConfigValue.class.getDeclaredField("cachedValue");
        cached.setAccessible(true);
        oldMode = cached.get(Config.MAX_CRAFTING_ORDER_AMOUNT);
        oldProgress = cached.get(Config.ENABLE_PROGRESS_DISPLAY);
        oldAutomatic = cached.get(Config.ENABLE_AUTOMATIC_AELIS_PLANNER);
        cached.set(Config.MAX_CRAFTING_ORDER_AMOUNT, CraftingOrderMode.BIG_INTEGER);
        cached.set(Config.ENABLE_PROGRESS_DISPLAY, true);
        cached.set(Config.ENABLE_AUTOMATIC_AELIS_PLANNER, false);
    }

    @AfterEach
    void restoreConfiguration() throws Exception {
        cached.set(Config.MAX_CRAFTING_ORDER_AMOUNT, oldMode);
        cached.set(Config.ENABLE_PROGRESS_DISPLAY, oldProgress);
        cached.set(Config.ENABLE_AUTOMATIC_AELIS_PLANNER, oldAutomatic);
        assertNull(CraftingSubmissionScope.request());
        assertNull(CraftingSubmissionScope.progress());
        assertNull(ExactRequestScope.current());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1000", "2147483648", "9223372036854775807", "9223372036854775808",
            "100000000000000000000000000000000000000000000000000"})
    void everyQuantityUsesHostConfirmationWithoutSelectingAPlanner(String decimal) throws Throwable {
        var menu = fixture(CraftAmountMenuMixin.class, AmountMenu.class,
                "appliedenhancements$confirmExact", "appeng/menu/me/crafting/CraftAmountMenu");
        var amount = new BigInteger(decimal);
        invoke(menu, "appliedenhancements$confirmExact", amount, false, true);
        assertEquals(1, menu.confirmations);
        assertEquals(amount.min(BigInteger.valueOf(Integer.MAX_VALUE)).intValueExact(), menu.amount);
        assertTrue(menu.autoStart);
        assertFalse(menu.missing);
        if (amount.bitLength() <= 31) assertNull(menu.submission);
        else assertEquals(amount, menu.submission.amount());
        assertFalse(CraftingPlannerIntervention.enabled());
    }

    @Test
    void disabledAndLongModesRetainTheirInputLimits() throws Throwable {
        var menu = fixture(CraftAmountMenuMixin.class, AmountMenu.class,
                "appliedenhancements$confirmExact", "appeng/menu/me/crafting/CraftAmountMenu");
        cached.set(Config.MAX_CRAFTING_ORDER_AMOUNT, CraftingOrderMode.DISABLED);
        invoke(menu, "appliedenhancements$confirmExact", BigInteger.valueOf(Integer.MAX_VALUE).add(BigInteger.ONE), false, false);
        assertEquals(0, menu.confirmations);
        invoke(menu, "appliedenhancements$confirmExact", BigInteger.valueOf(1000), true, false);
        assertEquals(1, menu.confirmations);
        assertTrue(menu.missing);
        cached.set(Config.MAX_CRAFTING_ORDER_AMOUNT, CraftingOrderMode.LONG_MAX);
        invoke(menu, "appliedenhancements$confirmExact", BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE), false, false);
        assertEquals(1, menu.confirmations);
        invoke(menu, "appliedenhancements$confirmExact", BigInteger.valueOf(Long.MAX_VALUE), false, false);
        assertEquals(2, menu.confirmations);
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE), menu.submission.amount());
    }

    @Test
    void missingSubmissionSubtractsLongStockBeforeHandoff() throws Throwable {
        var menu = fixture(CraftAmountMenuMixin.class, AmountMenu.class,
                "appliedenhancements$confirmExact", "appeng/menu/me/crafting/CraftAmountMenu");
        var stock = new KeyCounter();
        stock.set(OUTPUT, 3_000_000_000L);
        var storage = proxy(IStorageService.class, "getCachedInventory", stock);
        var grid = proxy(IGrid.class, "getStorageService", storage);
        var node = proxy(IGridNode.class, "getGrid", grid);
        menu.target = proxy(IActionHost.class, "getActionableNode", node);
        invoke(menu, "appliedenhancements$confirmExact", BigInteger.TEN.pow(30), true, false);
        assertEquals(BigInteger.TEN.pow(30).subtract(BigInteger.valueOf(3_000_000_000L)), menu.submission.amount());
        assertFalse(menu.missing, "The host must not subtract the stock a second time");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1000", "2147483648", "9223372036854775807", "9223372036854775808"})
    void hostServiceKeepsRequesterAndFutureIdentityForEveryRange(String decimal) throws Throwable {
        var menu = fixture(CraftConfirmMenuMixin.class, ConfirmationMenu.class,
                "appliedenhancements$trackCalculationProgress", null);
        var amount = new BigInteger(decimal);
        if (amount.bitLength() > 31) menu.appliedenhancements$submittedQuantity = AelisCraftingRequest.of(OUTPUT, amount);
        menu.progress = new CraftingCalculationProgressHandle(1);
        var requester = (ICraftingSimulationRequester) () -> null;
        var future = new CompletableFuture<ICraftingPlan>();
        var calls = new int[1];
        Operation<Future<ICraftingPlan>> host = arguments -> {
            calls[0]++;
            assertSame(requester, arguments[2]);
            assertSame(OUTPUT, arguments[3]);
            assertEquals(new AelisExactRequest(amount).projection(), arguments[4]);
            assertSame(STRATEGY, arguments[5]);
            assertSame(menu.progress, CraftingSubmissionScope.progress());
            assertSame(menu.appliedenhancements$submittedQuantity, CraftingSubmissionScope.request());
            assertNull(ExactRequestScope.current());
            assertFalse(CraftingPlannerIntervention.enabled());
            return future;
        };
        assertSame(future, invoke(menu, "appliedenhancements$trackCalculationProgress",
                null, null, requester, OUTPUT,
                amount.min(BigInteger.valueOf(Integer.MAX_VALUE)).longValueExact(), STRATEGY, host));
        assertEquals(1, calls[0]);
    }

    @Test
    void bigIntegerQuantityDoesNotEnableAelisDuringHostExecution() throws Throwable {
        var calculation = fixture(AelisCraftingCalculationMixin.class, Calculation.class,
                "molecularmanipulator$trackAelisCalculation", null);
        calculation.appliedenhancements$submittedExactAmount = new AelisExactRequest(BigInteger.TEN.pow(50));
        Operation<ICraftingPlan> host = arguments -> {
            assertNull(ExactRequestScope.current());
            assertFalse(CraftingPlannerIntervention.enabled());
            return null;
        };
        assertNull(invoke(calculation, "molecularmanipulator$trackAelisCalculation", host));
    }

    @Test
    void replanAndBackPassTheQuantityWithoutReplacingHostBehavior() throws Throwable {
        var menu = fixture(CraftConfirmMenuMixin.class, ConfirmationMenu.class,
                "appliedenhancements$keepSubmittedQuantity", null);
        menu.appliedenhancements$submittedQuantity = AelisCraftingRequest.of(OUTPUT, BigInteger.TEN.pow(40));
        var failure = new IllegalStateException("host failure");
        Operation<Void> host = arguments -> {
            assertSame(menu.appliedenhancements$submittedQuantity, CraftingSubmissionScope.request());
            assertFalse(CraftingPlannerIntervention.enabled());
            throw failure;
        };
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> invoke(menu, "appliedenhancements$keepSubmittedQuantity", host)));
    }

    public static class AmountMenu {
        public AEKey whatToCraft = OUTPUT;
        public Object target;
        public int confirmations, amount;
        public boolean missing, autoStart;
        public AelisCraftingRequest submission;
        public boolean isClientSide() { return false; }
        public boolean stillValid(Player player) { return true; }
        public Player getPlayer() { return null; }
        public Object getTarget() { return target; }
        public void confirm(int amount, boolean missing, boolean autoStart) {
            confirmations++;
            this.amount = amount;
            this.missing = missing;
            this.autoStart = autoStart;
            submission = CraftingSubmissionScope.request();
            assertNull(ExactRequestScope.current());
            assertFalse(CraftingPlannerIntervention.enabled());
        }
    }

    public static class ConfirmationMenu {
        public AelisCraftingRequest appliedenhancements$submittedQuantity;
        public CraftingCalculationProgressHandle progress;
        public CraftingCalculationProgressHandle appliedenhancements$startProgressTask(boolean enabled) {
            assertTrue(enabled);
            return progress;
        }
    }

    public static class Calculation {
        public boolean molecularmanipulator$aelisEnabled;
        public CraftingCalculationProgressHandle molecularmanipulator$calculationProgress;
        public AelisExactRequest appliedenhancements$submittedExactAmount;
    }

    private static Object invoke(Object fixture, String name, Object... arguments) throws Throwable {
        var method = java.util.Arrays.stream(fixture.getClass().getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name)).findFirst().orElseThrow();
        try { return method.invoke(fixture, arguments); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }

    private static <T> T proxy(Class<T> type, String method, Object result) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, called, arguments) -> {
                    if (called.getName().equals(method)) return result;
                    throw new AssertionError("Unexpected call: " + called);
                }));
    }

    private static <T> T fixture(Class<?> mixin, Class<T> base, String method, String host) throws Exception {
        var source = new ClassNode();
        try (var input = mixin.getResourceAsStream("/" + Type.getInternalName(mixin) + ".class")) {
            assertNotNull(input);
            new ClassReader(input).accept(source, 0);
        }
        String name = Type.getInternalName(base) + "$Harness";
        var names = new java.util.HashMap<String, String>();
        names.put(source.name, name);
        if (host != null) names.put(host, name);
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, Type.getInternalName(base), null);
        var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, Type.getInternalName(base), "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        var handler = source.methods.stream().filter(candidate -> candidate.name.equals(method)).findFirst().orElseThrow();
        handler.accept(new MethodRemapper(writer.visitMethod(Opcodes.ACC_PUBLIC, handler.name,
                handler.desc, null, handler.exceptions.toArray(String[]::new)), new SimpleRemapper(names)));
        writer.visitEnd();
        var loader = new ClassLoader(base.getClassLoader()) {
            Class<?> define() { return defineClass(name.replace('/', '.'), writer.toByteArray(), 0, writer.toByteArray().length); }
        };
        return base.cast(loader.define().getConstructor().newInstance());
    }
}
