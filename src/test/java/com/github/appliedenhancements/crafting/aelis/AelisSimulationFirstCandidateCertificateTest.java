package com.github.appliedenhancements.crafting.aelis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

class AelisSimulationFirstCandidateCertificateTest {
    @Test
    void acceptsSafeExactDeepFirstCandidateGraph() {
        var nodes = List.of(
                craft("root", input(1, "a", only("a"))),
                craft("a", input(2, "ore", only("ore"))),
                terminal("ore"));

        var result = AelisSimulationFirstCandidateCertificate.evaluate(nodes, 0);

        assertTrue(result.safe(), result.reason());
        assertEquals(Set.of("root", "a"), result.produced());
        assertEquals(Set.of("root", "a", "ore"), result.consumed());
    }

    @Test
    void rejectsSiblingThatConsumesAnotherSiblingsProducedKey() {
        var nodes = List.of(
                craft("root",
                        input(1, "b", only("b")),
                        input(3, "a", only("a"))),
                new AelisSimulationFirstCandidateCertificate.Node<>(
                        "b", 1, false, false, true, true, 2,
                        List.of(input(2, "ore", only("ore")))),
                terminal("ore"),
                craft("a", input(4, "b", only("b"))),
                terminal("b"));

        var result = AelisSimulationFirstCandidateCertificate.evaluate(nodes, 0);

        assertFalse(result.safe());
        assertEquals("sibling_crossfeed", result.reason());
    }

    @Test
    void acceptsCrossBranchRequestsServedByOneUnitNode() {
        var nodes = List.of(
                craft("root",
                        input(1, "b", 1, 256_000, only("b")),
                        input(3, "a", only("a"))),
                new AelisSimulationFirstCandidateCertificate.Node<>(
                        "b", 1, false, false, true, true, 2,
                        List.of(input(2, "ore", only("ore")))),
                terminal("ore"),
                craft("a", input(
                        1, "b", 1, 1_024_000, only("b"))));

        var result = AelisSimulationFirstCandidateCertificate.evaluate(nodes, 0);

        assertTrue(result.safe(), result.reason());
        assertTrue(result.repeatComposable());
    }

    @Test
    void acceptsContextSplitCopiesOfSameCanonicalService() {
        var nodes = List.of(
                craft("root",
                        input(1, "b", 1, 2_047_936, only("b")),
                        input(3, "a", only("a"))),
                craftWithService("b", 10,
                        input(2, "ore", only("ore"))),
                terminalWithService("ore", 11),
                craft("a", input(
                        4, "b", 1, 1_024_000, only("b"))),
                craftWithService("b", 10,
                        input(5, "ore", only("ore"))),
                terminalWithService("ore", 11));

        var result = AelisSimulationFirstCandidateCertificate.evaluate(nodes, 0);

        assertTrue(result.safe(), result.reason());
        assertTrue(result.repeatComposable());
    }

    @Test
    void rejectsCanonicalServiceWhoseContextSelectsDifferentChildService() {
        var nodes = List.of(
                craft("root",
                        input(1, "b", only("b")),
                        input(3, "a", only("a"))),
                craftWithService("b", 10,
                        input(2, "ore", only("ore"))),
                terminalWithService("ore", 11),
                craft("a", input(4, "b", only("b"))),
                craftWithService("b", 10,
                        input(5, "ore", only("ore"))),
                terminalWithService("ore", 12));

        var result = AelisSimulationFirstCandidateCertificate.evaluate(nodes, 0);

        assertFalse(result.safe());
        assertEquals("sibling_crossfeed", result.reason());
    }

    @Test
    void ignoresUnrelatedSameKeyContextWhenPairSharesOneOwner() {
        var nodes = List.of(
                craft("root",
                        input(1, "b", only("b")),
                        input(3, "a", only("a"))),
                craft("b", input(2, "ore", only("ore"))),
                terminal("ore"),
                craft("a", input(1, "b", only("b"))),
                terminal("b"));

        var result = AelisSimulationFirstCandidateCertificate.evaluate(nodes, 0);

        assertTrue(result.safe(), result.reason());
        assertEquals(1, result.touchedOwners().get("b"));
    }

    @Test
    void rejectsCrossBranchSameKeyServedByDifferentOwners() {
        var nodes = List.of(
                craft("root",
                        input(1, "stock", only("stock")),
                        input(2, "a", only("a"))),
                terminal("stock"),
                craft("a", input(3, "stock", only("stock"))),
                terminal("stock"));

        var result = AelisSimulationFirstCandidateCertificate.evaluate(nodes, 0);

        assertFalse(result.safe());
        assertEquals("sibling_crossfeed", result.reason());
    }

    @Test
    void rejectsSameKeySplitBetweenCraftableAndTerminalContexts() {
        var nodes = List.of(
                craft("root",
                        input(1, "b", only("b")),
                        input(3, "a", only("a"))),
                new AelisSimulationFirstCandidateCertificate.Node<>(
                        "b", 1, false, false, true, true, 2,
                        List.of(input(2, "ore", only("ore")))),
                terminal("ore"),
                craft("a", input(4, "b", only("b"))),
                terminal("b"));

        var result = AelisSimulationFirstCandidateCertificate.evaluate(nodes, 0);

        assertFalse(result.safe());
        assertEquals("sibling_crossfeed", result.reason());
    }

    @Test
    void rejectsNonUnitSharedCraftingService() {
        var nodes = List.of(
                craft("root",
                        input(1, "b", 2, only("b")),
                        input(3, "a", only("a"))),
                new AelisSimulationFirstCandidateCertificate.Node<>(
                        "b", 2, false, false, true, true, 3,
                        List.of(input(2, "ore", only("ore")))),
                terminal("ore"),
                craft("a", input(1, "b", 2, only("b"))));

        var result = AelisSimulationFirstCandidateCertificate.evaluate(nodes, 0);

        assertFalse(result.safe());
        assertEquals("sibling_crossfeed", result.reason());
    }

    @Test
    void acceptsRepeatedConsumptionThroughOneTerminalService() {
        var nodes = List.of(
                craft("root",
                        input(1, "stock", only("stock")),
                        input(1, "stock", only("stock"))),
                terminal("stock"));

        var result = AelisSimulationFirstCandidateCertificate.evaluate(nodes, 0);

        assertTrue(result.safe(), result.reason());
    }

    @Test
    void acceptsSharedStockWithDifferentRequestUnits() {
        var nodes = List.of(
                craft("root",
                        input(1, "stock", 1, only("stock")),
                        input(2, "stock", 2, only("stock"))),
                terminalWithService("stock", 7),
                new AelisSimulationFirstCandidateCertificate.Node<>(
                        "stock", 2, true, false, true, true, 0,
                        7, List.of()));

        var result = AelisSimulationFirstCandidateCertificate.evaluate(nodes, 0);

        assertTrue(result.safe(), result.reason());
    }

    @Test
    void acceptsDeepSiblingConvergenceWithDifferentTerminalRequestUnits() {
        var nodes = List.of(
                craft("root",
                        input(1, "left", only("left")),
                        input(5, "right", only("right"))),
                craft("left",
                        input(2, "shared", only("shared")),
                        input(3, "water", 1_000, only("water"))),
                craftWithService("shared", 10,
                        input(4, "quartz", only("quartz"))),
                terminalWithAmountAndService("water", 1_000, 7),
                terminalWithService("quartz", 11),
                craft("right",
                        input(6, "shared", only("shared")),
                        input(7, "water", 1, only("water"))),
                craftWithService("shared", 10,
                        input(8, "quartz", only("quartz"))),
                terminalWithAmountAndService("water", 1, 7),
                terminalWithService("quartz", 11));

        var result = AelisSimulationFirstCandidateCertificate.evaluate(nodes, 0);

        assertTrue(result.safe(),
                () -> result.reason() + ": " + result.detail());
    }

    @Test
    void rejectsProducedKeyAcceptedOnlyAfterItAppears() {
        Predicate<String> acceptsAOrRoot = key -> key.equals("a") || key.equals("root");
        var nodes = List.of(
                craft("root", input(1, "a", acceptsAOrRoot)),
                craft("a", input(2, "ore", only("ore"))),
                terminal("ore"));

        var result = AelisSimulationFirstCandidateCertificate.evaluate(nodes, 0);

        assertFalse(result.safe());
        assertEquals("latent_fuzzy_crossfeed", result.reason());
    }

    @Test
    void acceptsSubstituteWhenOnlySelectedTemplatesAreLive() {
        var nodes = List.of(
                craft("root", substitutableInput(
                        1, "a", List.of(
                                template("a"), template("a")),
                        key -> key.equals("a") || key.equals("alternative"))),
                terminal("a"));

        var result = AelisSimulationFirstCandidateCertificate.evaluate(nodes, 0);

        assertTrue(result.safe(), result.reason());
        assertEquals(Set.of("root", "a"), result.consumed());
    }

    @Test
    void acceptsInventoryIsolatedSubstituteTemplate() {
        var nodes = List.of(
                craft("root", substitutableInput(
                        1, "a", List.of(template("alternative")),
                        key -> key.equals("a") || key.equals("alternative"))),
                terminal("a"));

        var result = AelisSimulationFirstCandidateCertificate.evaluate(nodes, 0);

        assertTrue(result.safe(), result.reason());
        assertEquals(Set.of("root", "a", "alternative"), result.consumed());
    }

    @Test
    void rejectsSubstituteTemplateTouchedBySelectedDescendant() {
        var nodes = List.of(
                craft("root", substitutableInput(
                        1, "a", List.of(template("alternative")),
                        key -> key.equals("a") || key.equals("alternative"))),
                craft("a", input(2, "alternative", only("alternative"))),
                terminal("alternative"));

        var result = AelisSimulationFirstCandidateCertificate.evaluate(nodes, 0);

        assertFalse(result.safe());
        assertEquals("substitute_descendant_crossfeed", result.reason());
    }

    @Test
    void rejectsSiblingInputsCompetingForOneSubstituteTemplate() {
        var nodes = List.of(
                craft("root",
                        substitutableInput(
                                1, "a", List.of(template("shared")),
                                key -> key.equals("a") || key.equals("shared")),
                        substitutableInput(
                                2, "b", List.of(template("shared")),
                                key -> key.equals("b") || key.equals("shared"))),
                terminal("a"),
                terminal("b"));

        var result = AelisSimulationFirstCandidateCertificate.evaluate(nodes, 0);

        assertFalse(result.safe());
        assertEquals("sibling_crossfeed", result.reason());
    }

    @Test
    void rejectsSubstituteWhoseLiveTemplatesWereNotValidated() {
        var nodes = List.of(
                craft("root", new AelisSimulationFirstCandidateCertificate.Input<>(
                        1, "a", 1, 1, false, false,
                        List.of(template("alternative")),
                        key -> key.equals("a") || key.equals("alternative"))),
                terminal("a"));

        var result = AelisSimulationFirstCandidateCertificate.evaluate(nodes, 0);

        assertFalse(result.safe());
        assertEquals("inexact_input", result.reason());
    }

    @Test
    void rejectsStaleFirstCandidate() {
        var nodes = List.of(
                new AelisSimulationFirstCandidateCertificate.Node<>(
                        "root", 1, false, false, true, false, 1,
                        List.of(input(1, "ore", only("ore")))),
                terminal("ore"));

        var result = AelisSimulationFirstCandidateCertificate.evaluate(nodes, 0);

        assertFalse(result.safe());
        assertEquals("stale_candidate0", result.reason());
    }

    @Test
    void keepsDirectStockCandidateCertified() {
        var nodes = List.of(
                craft("root", input(1, "stock", only("stock"))),
                terminal("stock"));

        assertTrue(AelisSimulationFirstCandidateCertificate
                .evaluate(nodes, 0).safe());
    }

    private static AelisSimulationFirstCandidateCertificate.Node<String> craft(
            String key,
            AelisSimulationFirstCandidateCertificate.Input<String>... inputs) {
        return new AelisSimulationFirstCandidateCertificate.Node<>(
                key, 1, false, false, true, true, 1, List.of(inputs));
    }

    private static AelisSimulationFirstCandidateCertificate.Node<String>
            craftWithService(String key, int serviceId,
                    AelisSimulationFirstCandidateCertificate.Input<String>...
                            inputs) {
        return new AelisSimulationFirstCandidateCertificate.Node<>(
                key, 1, false, false, true, true, 1,
                serviceId, List.of(inputs));
    }

    private static AelisSimulationFirstCandidateCertificate.Node<String> terminal(
            String key) {
        return new AelisSimulationFirstCandidateCertificate.Node<>(
                key, 1, true, false, true, true, 0, List.of());
    }

    private static AelisSimulationFirstCandidateCertificate.Node<String>
            terminalWithService(String key, int serviceId) {
        return terminalWithAmountAndService(key, 1, serviceId);
    }

    private static AelisSimulationFirstCandidateCertificate.Node<String>
            terminalWithAmountAndService(
                    String key, long amount, int serviceId) {
        return new AelisSimulationFirstCandidateCertificate.Node<>(
                key, amount, true, false, true, true, 0,
                serviceId, List.of());
    }

    private static AelisSimulationFirstCandidateCertificate.Input<String> input(
            int childIndex, String key, Predicate<String> accepts) {
        return input(childIndex, key, 1, accepts);
    }

    private static AelisSimulationFirstCandidateCertificate.Input<String> input(
            int childIndex, String key, long amount,
            Predicate<String> accepts) {
        return input(childIndex, key, amount, 1, accepts);
    }

    private static AelisSimulationFirstCandidateCertificate.Input<String> input(
            int childIndex, String key, long amount, long multiplier,
            Predicate<String> accepts) {
        return new AelisSimulationFirstCandidateCertificate.Input<>(
                childIndex, key, amount, multiplier, true, true, accepts);
    }

    private static AelisSimulationFirstCandidateCertificate.Input<String>
            substitutableInput(int childIndex, String key,
                    List<AelisSimulationFirstCandidateCertificate.InventoryTemplate<String>>
                            templates,
                    Predicate<String> accepts) {
        return new AelisSimulationFirstCandidateCertificate.Input<>(
                childIndex, key, 1, 1, false, true, templates, accepts);
    }

    private static AelisSimulationFirstCandidateCertificate.InventoryTemplate<String>
            template(String key) {
        return new AelisSimulationFirstCandidateCertificate.InventoryTemplate<>(
                key, 1);
    }

    private static Predicate<String> only(String expected) {
        return expected::equals;
    }
}
