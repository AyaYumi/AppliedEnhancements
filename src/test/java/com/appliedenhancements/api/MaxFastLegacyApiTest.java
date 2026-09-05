package com.appliedenhancements.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import appeng.crafting.CraftBranchFailure;
import com.appliedenhancements.Config;
import com.appliedenhancements.test.TestAEKey;
import com.github.appliedenhancements.crafting.aelis.AelisPlanner;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("deprecation")
class MaxFastLegacyApiTest {
    @TempDir static Path temporary;
    private static URLClassLoader clientLoader;
    private static Class<?> oldClient;

    @BeforeAll
    static void compileOldClientAgainstReleaseDeclaration() throws Exception {
        Path fixtures = Path.of("src/test/fixtures/maxfast-1.0.3");
        Path declarations = Files.createDirectories(temporary.resolve("old-api"));
        Path client = Files.createDirectories(temporary.resolve("old-client"));
        String dependencies = compilationClasspath();
        compile(declarations, dependencies,
                fixtures.resolve("api/com/appliedenhancements/api/MaxFastCraftingPlanner.java"));
        compile(client, declarations + File.pathSeparator + dependencies,
                fixtures.resolve("client/legacy/LegacyClient.java"));
        // Deliberately exclude old-api at runtime: every API symbol must bind to
        // the current production classes in the parent loader.
        clientLoader = new URLClassLoader(new URL[] {client.toUri().toURL()}, MaxFastLegacyApiTest.class.getClassLoader());
        oldClient = clientLoader.loadClass("legacy.LegacyClient");
        assertSame(MaxFastCraftingPlanner.class,
                clientLoader.loadClass("com.appliedenhancements.api.MaxFastCraftingPlanner"));
    }

    @AfterAll
    static void closeClientLoader() throws Exception {
        if (clientLoader != null) clientLoader.close();
    }

    @Test
    void compiledLegacyFactoryAndAllCallbacksLinkToAelisWithoutServiceIndex() throws Exception {
        ((List<?>) oldClient.getField("EVENTS").get(null)).clear();
        var legacy = (MaxFastCraftingPlanner) oldClient.getMethod("create").invoke(null);
        Object aelis = read(legacy, "delegate");
        Object session = read(aelis, "delegate");
        assertNull(read(session, "craftingService"));
        assertEquals(321, read(session, "maxNodes"));
        assertEquals(123_000_000L, read(session, "compileBudgetNanos"));
        var pause = (AelisPlanner.PauseCheckpoint) read(session, "pauseCheckpoint");
        var progress = (AelisPlanner.ProgressSink) read(session, "progressSink");
        pause.pause();
        progress.compilationStarted();
        progress.nodeDiscovered();
        progress.compilationStep();
        progress.executionStarted(42);
        progress.executionStep();
        assertEquals(List.of("pause", "compile", "node", "step", "execute:42", "executionStep"),
                oldClient.getField("EVENTS").get(null));
    }

    @Test
    void configuredLegacyDescriptorUsesConfiguredBudgets() throws Exception {
        // A plain unit test has no loaded game config. Populate only the value
        // caches used by this factory and restore their exact previous contents.
        var cached = net.neoforged.neoforge.common.ModConfigSpec.ConfigValue.class.getDeclaredField("cachedValue");
        cached.setAccessible(true);
        Object previousNodes = cached.get(Config.AELIS_MAX_NODES);
        Object previousBudget = cached.get(Config.AELIS_COMPILE_BUDGET_MS);
        Object previousAutomatic = cached.get(Config.ENABLE_AUTOMATIC_AELIS_PLANNER);
        try {
            cached.set(Config.AELIS_MAX_NODES, 456);
            cached.set(Config.AELIS_COMPILE_BUDGET_MS, 789);
            cached.set(Config.ENABLE_AUTOMATIC_AELIS_PLANNER, false);
            var legacy = oldClient.getMethod("configured").invoke(null);
            var session = read(read(legacy, "delegate"), "delegate");
            assertEquals(456, read(session, "maxNodes"));
            assertEquals(789_000_000L, read(session, "compileBudgetNanos"));
            assertNull(read(session, "craftingService"));
        } finally {
            cached.set(Config.AELIS_MAX_NODES, previousNodes);
            cached.set(Config.AELIS_COMPILE_BUDGET_MS, previousBudget);
            cached.set(Config.ENABLE_AUTOMATIC_AELIS_PLANNER, previousAutomatic);
        }
    }

    @Test
    void oldClientExecutesAndReadsEveryOriginalResultDescriptor() throws Exception {
        var branch = new CraftBranchFailure(new TestAEKey("legacy_failure"), 1);
        var failure = new IllegalStateException("preserved error");
        for (CraftBranchFailure terminal : new CraftBranchFailure[] {null, branch}) {
            var expected = new AelisCraftingPlanner.Result(false, "legacy_test", 2, 3, 4, 5, 6, 7,
                    true, terminal, failure);
            var wrapper = new MaxFastCraftingPlannerAdapter((root, inventory, amount, simulation, missing) -> {
                assertNull(root);
                assertNull(inventory);
                assertEquals(17, amount);
                assertTrue(simulation);
                assertNotNull(missing);
                return expected;
            });
            var result = oldClient.getMethod("execute", MaxFastCraftingPlanner.class).invoke(null, wrapper);
            oldClient.getMethod("readEveryResultAccessor", MaxFastCraftingPlanner.Result.class, Object.class, Object.class)
                    .invoke(null, result, terminal, failure);
        }
    }

    @Test
    void interruptionAndBudgetValidationKeepLegacyBehavior() {
        assertThrows(IllegalArgumentException.class, () -> MaxFastCraftingPlanner.create(0, 1, null, null));
        assertThrows(IllegalArgumentException.class, () -> MaxFastCraftingPlanner.create(1, 0, null, null));
        assertNotNull(MaxFastCraftingPlanner.create(1, 1, null, null));
        var interrupted = new InterruptedException("legacy pause interrupted");
        var wrapper = new MaxFastCraftingPlannerAdapter((root, inventory, amount, simulation, missing) -> {
            throw interrupted;
        });
        var failure = assertThrows(InvocationTargetException.class,
                () -> oldClient.getMethod("execute", MaxFastCraftingPlanner.class).invoke(null, wrapper));
        assertSame(interrupted, failure.getCause());
    }

    private static void compile(Path output, String classpath, Path source) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "The legacy ABI test requires the configured Java 21 JDK");
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            var units = manager.getJavaFileObjects(source);
            assertTrue(compiler.getTask(null, manager, null,
                    List.of("-proc:none", "--release", "21", "-classpath", classpath,
                            "-d", output.toString()), null, units).call(), "Old API/client compilation failed");
        }
    }

    private static String compilationClasspath() throws Exception {
        var paths = new LinkedHashSet<String>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) paths.add(entry);
        for (ClassLoader loader = MaxFastLegacyApiTest.class.getClassLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) {
                for (URL url : urls.getURLs()) {
                    if (url.getProtocol().equals("file")) paths.add(Path.of(url.toURI()).toString());
                }
            }
        }
        return String.join(File.pathSeparator, paths);
    }

    private static Object read(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
