package com.appliedenhancements.mixin;

import static org.junit.jupiter.api.Assertions.*;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import com.appliedenhancements.test.TestAEKey;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.MethodRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

class NetworkCraftingPatternCacheTest {
    private static final String PROVIDERS = "appeng.me.service.helpers.NetworkCraftingProviders";
    private static final String PATTERNS = PROVIDERS + "$PatternsForKey";
    private static final String MIXIN = NetworkCraftingPatternCacheMixin.class.getName();

    @Test
    void duplicateProviderCopiesAreSortedOnceUntilTheNetworkChanges() throws Exception {
        var registry = new Registry();
        var output = new TestAEKey("duplicate_output");
        var pattern = new Pattern("same_recipe", output);
        var providers = new HashSet<Provider>();
        for (int index = 0; index < 256; index++) {
            var provider = new Provider(index, pattern);
            providers.add(provider);
            registry.add(provider);
        }
        pattern.hashCalls = 0;

        List<IPatternDetails> first = registry.patterns(output);
        assertEquals(List.of(pattern), first);
        int firstSortHashCalls = pattern.hashCalls;
        assertTrue(firstSortHashCalls >= providers.size());
        for (int lookup = 0; lookup < 1_000; lookup++) {
            assertSame(first, registry.patterns(output));
        }
        assertEquals(firstSortHashCalls, pattern.hashCalls,
                "Unchanged lookups must not hash all duplicate provider copies again");
        assertEquals(providers, registry.providers(pattern),
                "Deduplicating planning candidates must retain every execution provider");
        assertThrows(UnsupportedOperationException.class, first::clear);
    }

    @Test
    void addingAndRemovingAnAlternativeInvalidatesTheCachedCandidates() throws Exception {
        var registry = new Registry();
        var output = new TestAEKey("alternative_output");
        var firstPattern = new Pattern("first_recipe", output);
        var secondPattern = new Pattern("different_recipe", output);
        var firstProvider = new Provider(10, firstPattern);
        var secondProvider = new Provider(100, secondPattern);
        registry.add(firstProvider);
        List<IPatternDetails> original = registry.patterns(output);

        registry.add(secondProvider);
        List<IPatternDetails> expanded = registry.patterns(output);
        assertEquals(List.of(secondPattern, firstPattern), expanded);
        assertNotSame(original, expanded);
        assertEquals(List.of(firstPattern), original);
        assertSame(expanded, registry.patterns(output));

        registry.remove(secondProvider);
        List<IPatternDetails> reduced = registry.patterns(output);
        assertEquals(List.of(firstPattern), reduced);
        assertNotSame(expanded, reduced);
        assertSame(reduced, registry.patterns(output));
        assertEquals(Set.of(firstProvider), registry.providers(firstPattern));

        registry.remove(firstProvider);
        assertTrue(registry.patterns(output).isEmpty());
        registry.add(secondProvider);
        assertEquals(List.of(secondPattern), registry.patterns(output));
    }

    @Test
    void duplicateRecipesKeepTheirHighestPriorityRepresentativeAndAllMachines() throws Exception {
        var registry = new Registry();
        var output = new TestAEKey("priority_output");
        var lowPattern = new Pattern("shared_recipe", output);
        var highPattern = new Pattern("shared_recipe", output);
        var alternative = new Pattern("alternative_recipe", output);
        var low = new Provider(1, lowPattern);
        var high = new Provider(100, highPattern);
        var middle = new Provider(10, alternative);
        registry.add(low);
        registry.add(high);
        registry.add(middle);

        List<IPatternDetails> patterns = registry.patterns(output);
        assertEquals(2, patterns.size());
        assertSame(highPattern, patterns.getFirst());
        assertSame(alternative, patterns.get(1));
        assertEquals(Set.of(low, high), registry.providers(highPattern));

        registry.remove(high);
        List<IPatternDetails> remaining = registry.patterns(output);
        assertSame(alternative, remaining.getFirst());
        assertSame(lowPattern, remaining.get(1));
        assertEquals(Set.of(low), registry.providers(lowPattern));
    }

    @Test
    void refreshingAProviderReordersTheCacheAtItsNewPriority() throws Exception {
        var registry = new Registry();
        var output = new TestAEKey("refresh_output");
        var first = new Provider(10, new Pattern("recipe_a", output));
        var second = new Provider(20, new Pattern("recipe_b", output));
        registry.add(first);
        registry.add(second);
        List<IPatternDetails> before = registry.patterns(output);
        assertSame(second.pattern, before.getFirst());

        registry.remove(first);
        first.priority = 30;
        registry.add(first);
        List<IPatternDetails> after = registry.patterns(output);
        assertSame(first.pattern, after.getFirst());
        assertSame(after, registry.patterns(output));
        assertSame(second.pattern, before.getFirst());
    }

    @Test
    void changingOneOutputDoesNotInvalidateAnotherOutputsCache() throws Exception {
        var registry = new Registry();
        var firstOutput = new TestAEKey("first_output");
        var secondOutput = new TestAEKey("second_output");
        var first = new Pattern("first", firstOutput);
        var second = new Pattern("second", secondOutput);
        registry.add(new Provider(0, first));
        registry.add(new Provider(0, second));
        List<IPatternDetails> firstSnapshot = registry.patterns(firstOutput);
        List<IPatternDetails> secondSnapshot = registry.patterns(secondOutput);

        registry.add(new Provider(1, new Pattern("new_first", firstOutput)));
        assertNotSame(firstSnapshot, registry.patterns(firstOutput));
        assertSame(secondSnapshot, registry.patterns(secondOutput));
    }

    private static final class Pattern implements IPatternDetails {
        private final String recipe;
        private final AEKey output;
        private int hashCalls;

        private Pattern(String recipe, AEKey output) {
            this.recipe = recipe;
            this.output = output;
        }

        @Override public AEItemKey getDefinition() { return null; }
        @Override public IInput[] getInputs() { return new IInput[0]; }
        @Override public List<GenericStack> getOutputs() { return List.of(new GenericStack(output, 1)); }
        @Override public int hashCode() { hashCalls++; return recipe.hashCode(); }
        @Override public boolean equals(Object other) {
            return other instanceof Pattern pattern && recipe.equals(pattern.recipe);
        }
    }

    private static final class Provider implements ICraftingProvider {
        private int priority;
        private final Pattern pattern;

        private Provider(int priority, Pattern pattern) {
            this.priority = priority;
            this.pattern = pattern;
        }

        @Override public List<IPatternDetails> getAvailablePatterns() { return List.of(pattern); }
        @Override public int getPatternPriority() { return priority; }
        @Override public boolean pushPattern(IPatternDetails details, KeyCounter[] inputs) { return false; }
        @Override public boolean isBusy() { return false; }
    }

    private static final class Registry {
        private final Class<?> type = new PatchedProvidersLoader().loadClass(PROVIDERS);
        private final Object delegate = type.getConstructor().newInstance();

        private Registry() throws ReflectiveOperationException {
        }

        private void add(ICraftingProvider provider) throws ReflectiveOperationException {
            type.getMethod("addProvider", ICraftingProvider.class).invoke(delegate, provider);
        }

        private void remove(ICraftingProvider provider) throws ReflectiveOperationException {
            type.getMethod("removeProvider", ICraftingProvider.class).invoke(delegate, provider);
        }

        @SuppressWarnings("unchecked")
        private List<IPatternDetails> patterns(AEKey output) throws ReflectiveOperationException {
            return (List<IPatternDetails>) type.getMethod("getCraftingFor", AEKey.class).invoke(delegate, output);
        }

        @SuppressWarnings("unchecked")
        private Set<ICraftingProvider> providers(IPatternDetails pattern) throws ReflectiveOperationException {
            var result = new HashSet<ICraftingProvider>();
            var providers = (Iterable<ICraftingProvider>) type.getMethod("getMediums", IPatternDetails.class)
                    .invoke(delegate, pattern);
            providers.forEach(result::add);
            return result;
        }
    }

    /**
     * JUnit does not launch Mixin. Copy the production RETURN callback into the
     * real AE2 index so these tests exercise its sorting, invalidation and routing,
     * rather than a reimplementation of AE2's cache in a test fixture.
     */
    private static final class PatchedProvidersLoader extends ClassLoader {
        private PatchedProvidersLoader() {
            super(NetworkCraftingPatternCacheTest.class.getClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(PROVIDERS) && !name.startsWith(PROVIDERS + "$")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try {
                        ClassNode node = readClass(name);
                        if (name.equals(PATTERNS)) {
                            applyCacheHook(node);
                        }
                        var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                        node.accept(writer);
                        byte[] bytes = writer.toByteArray();
                        loaded = defineClass(name, bytes, 0, bytes.length);
                    } catch (IOException exception) {
                        throw new ClassNotFoundException(name, exception);
                    }
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
    }

    private static void applyCacheHook(ClassNode target) throws IOException {
        ClassNode mixin = readClass(MIXIN);
        assertEquals(List.of(PATTERNS), annotationValue(
                annotation(mixin.visibleAnnotations, mixin.invisibleAnnotations,
                        "Lorg/spongepowered/asm/mixin/Mixin;"), "targets"));
        MethodNode hook = mixin.methods.stream()
                .filter(method -> method.name.equals("appliedenhancements$cacheSortedPatterns"))
                .findFirst().orElseThrow();
        AnnotationNode injection = annotation(hook.visibleAnnotations, hook.invisibleAnnotations,
                "Lorg/spongepowered/asm/mixin/injection/Inject;");
        assertEquals(List.of("sortPatterns"), annotationValue(injection, "method"));
        @SuppressWarnings("unchecked")
        var points = (List<AnnotationNode>) annotationValue(injection, "at");
        assertEquals(1, points.size());
        assertEquals("RETURN", annotationValue(points.getFirst(), "value"));

        var merged = new MethodNode(Opcodes.ASM9, hook.access, hook.name, hook.desc, hook.signature, null);
        hook.accept(new MethodRemapper(merged, new SimpleRemapper(mixin.name, target.name)));
        target.methods.add(merged);
        MethodNode sort = target.methods.stream().filter(method -> method.name.equals("sortPatterns"))
                .findFirst().orElseThrow();
        for (var instruction : sort.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                var callback = new InsnList();
                callback.add(new VarInsnNode(Opcodes.ALOAD, 0));
                callback.add(new InsnNode(Opcodes.ACONST_NULL));
                callback.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, target.name,
                        hook.name, hook.desc, false));
                sort.instructions.insertBefore(instruction, callback);
            }
        }
    }

    private static ClassNode readClass(String name) throws IOException {
        try (var input = NetworkCraftingPatternCacheTest.class.getClassLoader()
                .getResourceAsStream(name.replace('.', '/') + ".class")) {
            assertNotNull(input, name);
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }

    private static AnnotationNode annotation(List<AnnotationNode> visible,
            List<AnnotationNode> invisible, String descriptor) {
        var annotations = new ArrayList<AnnotationNode>();
        if (visible != null) annotations.addAll(visible);
        if (invisible != null) annotations.addAll(invisible);
        return annotations.stream().filter(annotation -> annotation.desc.equals(descriptor))
                .findFirst().orElseThrow();
    }

    private static Object annotationValue(AnnotationNode annotation, String key) {
        for (int index = 0; index < annotation.values.size(); index += 2) {
            if (key.equals(annotation.values.get(index))) return annotation.values.get(index + 1);
        }
        throw new AssertionError("Missing annotation value: " + key);
    }
}
