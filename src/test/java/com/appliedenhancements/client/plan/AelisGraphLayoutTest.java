package com.appliedenhancements.client.plan;

import static org.junit.jupiter.api.Assertions.*;

import appeng.api.stacks.AEKey;
import com.appliedenhancements.plan.AelisPlanGraph;
import com.appliedenhancements.test.TestAEKey;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AelisGraphLayoutTest {
    private static AelisPlanGraph graph() {
        List<AEKey> keys = List.of(new TestAEKey("root"), new TestAEKey("cycle_a"),
                new TestAEKey("cycle_b"), new TestAEKey("leaf"));
        return new AelisPlanGraph(keys, List.of(
                new AelisPlanGraph.Edge(0, 1, false),
                new AelisPlanGraph.Edge(1, 2, false),
                new AelisPlanGraph.Edge(2, 1, false),
                new AelisPlanGraph.Edge(2, 3, false)), 0, true);
    }

    @Test
    void groupsCyclesAndRetainsTheirUpstreamContextWhenFiltering() {
        var drawing = AelisGraphLayout.build(graph(), Set.of(3), Set.of());
        assertEquals(3, drawing.groups().size());
        assertTrue(drawing.groups().stream().anyMatch(group -> group.cyclic()
                && group.nodes().equals(List.of(1, 2))));
        assertTrue(drawing.boxes().stream().map(AelisGraphLayout.Box::node).toList()
                .containsAll(List.of(0, 1, 2, 3)));
    }

    @Test
    void alternativesDoNotCreateFalseCycleAndGroupsCanFold() {
        var base = graph();
        var edges = new java.util.ArrayList<>(base.edges());
        edges.add(new AelisPlanGraph.Edge(3, 3, true));
        var withAlternative = new AelisPlanGraph(base.keys(), edges, base.root(), true);
        var drawing = AelisGraphLayout.build(withAlternative, Set.of(3), Set.of(1));
        assertFalse(drawing.groups().stream().anyMatch(group -> group.nodes().contains(3) && group.cyclic()));
        var cycle = drawing.groups().stream().filter(AelisGraphLayout.Group::cyclic).findFirst().orElseThrow();
        assertTrue(cycle.collapsed());
        assertFalse(drawing.boxes().stream().anyMatch(box -> box.node() == 1 || box.node() == 2));
    }

    @Test
    void defaultBuildAndLegacyBoxConstructorKeepOriginalDimensions() {
        var drawing = AelisGraphLayout.build(graph(), Set.of(0), Set.of());
        var explicit = AelisGraphLayout.build(graph(), Set.of(0), Set.of(), Map.of());
        assertEquals(drawing.boxes(), explicit.boxes());
        assertEquals(drawing.groups(), explicit.groups());
        for (var box : drawing.boxes()) {
            assertEquals(116, box.width());
            assertEquals(44, box.height());
        }
        assertEquals(new AelisGraphLayout.Box(7, 12, 18, 116, 44),
                new AelisGraphLayout.Box(7, 12, 18));
    }

    @Test
    void nodeSizeRejectsNonPositiveDimensions() {
        for (int[] size : new int[][] {{0, 80}, {-1, 80}, {300, 0}, {300, -1}}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new AelisGraphLayout.NodeSize(size[0], size[1]));
        }
        assertDoesNotThrow(() -> new AelisGraphLayout.NodeSize(1, 1));
    }

    @Test
    void mixedNodeSizesDoNotOverlapOrEnlargeOrdinaryPeers() {
        var keys = new java.util.ArrayList<>(graph().keys());
        keys.add(new TestAEKey("long_text"));
        keys.add(new TestAEKey("short"));
        var branching = new AelisPlanGraph(keys, List.of(
                new AelisPlanGraph.Edge(0, 1, false),
                new AelisPlanGraph.Edge(0, 4, false),
                new AelisPlanGraph.Edge(0, 5, false),
                new AelisPlanGraph.Edge(1, 2, false),
                new AelisPlanGraph.Edge(2, 1, false),
                new AelisPlanGraph.Edge(2, 3, false)), 0, true);
        var drawing = AelisGraphLayout.build(branching, Set.of(0), Set.of(), Map.of(
                1, new AelisGraphLayout.NodeSize(180, 92),
                2, new AelisGraphLayout.NodeSize(300, 64),
                4, new AelisGraphLayout.NodeSize(360, 32),
                5, new AelisGraphLayout.NodeSize(68, 20)));
        assertEquals(6, drawing.boxes().size());
        var root = drawing.boxes().getFirst();
        var cycle = drawing.groups().stream().filter(AelisGraphLayout.Group::cyclic)
                .findFirst().orElseThrow();
        var longNode = drawing.boxes().stream().filter(box -> box.node() == 4).findFirst().orElseThrow();
        var shortNode = drawing.boxes().stream().filter(box -> box.node() == 5).findFirst().orElseThrow();
        var leaf = drawing.boxes().stream().filter(box -> box.node() == 3).findFirst().orElseThrow();
        assertEquals(116, root.width());
        assertEquals(44, root.height());
        assertEquals(360, longNode.width());
        assertEquals(32, longNode.height());
        assertEquals(68, shortNode.width());
        assertEquals(20, shortNode.height());
        for (var box : List.of(longNode, shortNode)) {
            var group = drawing.groups().stream().filter(candidate -> candidate.id() == box.node())
                    .findFirst().orElseThrow();
            assertFalse(group.cyclic());
            assertEquals(box.width(), group.width());
            assertEquals(box.height(), group.height());
            assertEquals(cycle.y(), box.y());
        }
        assertEquals(cycle.x() + cycle.width() + 18, longNode.x());
        assertEquals(longNode.x() + longNode.width() + 18, shortNode.x());
        assertEquals(root.y() + root.height() + 24, cycle.y());
        assertEquals(cycle.y() + cycle.height() + 24, leaf.y());
        assertEquals(116, leaf.width());
        assertEquals(44, leaf.height());
        for (int i = 0; i < drawing.boxes().size(); i++) {
            for (int j = i + 1; j < drawing.boxes().size(); j++) {
                var a = drawing.boxes().get(i);
                var b = drawing.boxes().get(j);
                assertTrue(a.x() + a.width() <= b.x()
                        || b.x() + b.width() <= a.x()
                        || a.y() + a.height() <= b.y()
                        || b.y() + b.height() <= a.y(),
                        "Nodes " + a.node() + " and " + b.node() + " overlap");
            }
        }
    }

    @Test
    void cycleGroupEnclosesDifferentNodeSizesAndLeavesRoomForNextLayer() {
        var drawing = AelisGraphLayout.build(graph(), Set.of(0), Set.of(), Map.of(
                1, new AelisGraphLayout.NodeSize(180, 92),
                2, new AelisGraphLayout.NodeSize(300, 64),
                3, new AelisGraphLayout.NodeSize(72, 28)));
        var cycle = drawing.groups().stream().filter(AelisGraphLayout.Group::cyclic)
                .findFirst().orElseThrow();
        assertEquals(312, cycle.width());
        assertEquals(188, cycle.height());
        var members = drawing.boxes().stream().filter(box -> cycle.nodes().contains(box.node())).toList();
        assertEquals(2, members.size());
        assertEquals(180, members.getFirst().width());
        assertEquals(92, members.getFirst().height());
        assertEquals(300, members.getLast().width());
        assertEquals(64, members.getLast().height());
        for (var box : members) {
            assertEquals(cycle.x() + 6, box.x());
            assertTrue(box.y() >= cycle.y());
            assertTrue(box.x() + box.width() <= cycle.x() + cycle.width());
            assertTrue(box.y() + box.height() <= cycle.y() + cycle.height());
        }
        assertEquals(cycle.y() + 19, members.getFirst().y());
        assertEquals(members.getFirst().y() + members.getFirst().height() + 5, members.getLast().y());
        var root = drawing.boxes().stream().filter(box -> box.node() == 0).findFirst().orElseThrow();
        var leaf = drawing.boxes().stream().filter(box -> box.node() == 3).findFirst().orElseThrow();
        assertEquals(cycle.y(), root.y() + root.height() + 24);
        assertEquals(cycle.y() + cycle.height() + 24, leaf.y());
        assertEquals(72, leaf.width());
        assertEquals(28, leaf.height());
    }

    @Test
    void collapsedCycleKeepsWidestMemberWidthAndHidesMemberBoxes() {
        var drawing = AelisGraphLayout.build(graph(), Set.of(0), Set.of(1), Map.of(
                1, new AelisGraphLayout.NodeSize(180, 92),
                2, new AelisGraphLayout.NodeSize(300, 64)));
        var cycle = drawing.groups().stream().filter(AelisGraphLayout.Group::cyclic)
                .findFirst().orElseThrow();
        assertTrue(cycle.collapsed());
        assertEquals(312, cycle.width());
        assertEquals(20, cycle.height());
        assertEquals(List.of(0, 3), drawing.boxes().stream().map(AelisGraphLayout.Box::node).toList());
        assertEquals(cycle.y() + cycle.height() + 24, drawing.boxes().getLast().y());
    }

    @Test
    void initialAndResetViewportKeepRootInsideCanvasLeftEdge() {
        var viewport = new AelisGraphViewport();
        assertTrue(viewport.x() > 14, "The canvas starts at x=14; leave room for the node border");
        double x = viewport.x(), y = viewport.y(), zoom = viewport.zoom();
        viewport.pan(-40, 25);
        viewport.zoom(1.5, 100, 50);
        viewport.reset();
        assertEquals(x, viewport.x());
        assertEquals(y, viewport.y());
        assertEquals(zoom, viewport.zoom());
    }

    @Test
    void viewportZoomKeepsCursorAnchorAndClamps() {
        var viewport = new AelisGraphViewport();
        double beforeX = viewport.worldX(170), beforeY = viewport.worldY(90);
        viewport.zoom(1.25, 170, 90);
        assertEquals(beforeX, viewport.worldX(170), 1e-9);
        assertEquals(beforeY, viewport.worldY(90), 1e-9);
        for (int i = 0; i < 100; i++) viewport.zoom(2, 0, 0);
        assertEquals(2.0, viewport.zoom());
        for (int i = 0; i < 100; i++) viewport.zoom(0.1, 0, 0);
        assertEquals(0.25, viewport.zoom());
    }
}
