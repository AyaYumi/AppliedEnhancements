package com.appliedenhancements.client.plan;

import static org.junit.jupiter.api.Assertions.*;

import appeng.api.stacks.AEKey;
import com.appliedenhancements.plan.AelisPlanGraph;
import com.appliedenhancements.test.TestAEKey;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AelisGraphInteractionTest {
    private static AelisGraphLayout.Drawing drawing() {
        return drawing(Map.of());
    }

    private static AelisGraphLayout.Drawing drawing(Map<Integer, AelisGraphLayout.NodeSize> sizes) {
        List<AEKey> keys = List.of(new TestAEKey("root"), new TestAEKey("child"));
        return AelisGraphLayout.build(new AelisPlanGraph(keys,
                List.of(new AelisPlanGraph.Edge(0, 1, false)), 0, true), Set.of(0, 1), Set.of(), sizes);
    }

    @Test
    void dragsOnlySelectedNodeAndHitTestingFollowsItsNewPosition() {
        var drawing = drawing();
        var interaction = new AelisGraphInteraction();
        var root = drawing.boxes().getFirst();
        var child = drawing.boxes().getLast();
        assertTrue(interaction.beginDrag(drawing, root.x() + 7, root.y() + 9));
        assertTrue(interaction.dragTo(507, 409));
        interaction.release();
        assertNull(interaction.nodeAt(drawing, root.x() + 7, root.y() + 9));
        var moved = interaction.nodeAt(drawing, 507, 409);
        assertEquals(root.node(), moved.node());
        assertEquals(500, moved.x());
        assertEquals(400, moved.y());
        assertTrue(interaction.boxes(drawing).contains(child));
        assertEquals(0, root.x()); // The source layout is not modified.
    }

    @Test
    void dragKeepsGrabOffsetAtEveryZoomAndWithPannedCanvas() {
        for (double zoom : new double[] {0.25, 0.85, 1.0, 2.0}) {
            var drawing = drawing();
            var interaction = new AelisGraphInteraction();
            var viewport = new AelisGraphViewport();
            viewport.zoom(zoom / viewport.zoom(), 0, 0);
            viewport.pan(30, -20);
            var root = drawing.boxes().getFirst();
            double sx = viewport.x() + (root.x() + 7) * zoom;
            double sy = viewport.y() + (root.y() + 9) * zoom;
            assertTrue(interaction.beginDrag(drawing, viewport.worldX(sx), viewport.worldY(sy)));
            assertTrue(interaction.dragTo(viewport.worldX(sx + 60), viewport.worldY(sy - 40)));
            var moved = interaction.boxes(drawing).stream().filter(box -> box.node() == root.node())
                    .findFirst().orElseThrow();
            assertEquals(root.x() + 60 / zoom, moved.x(), 1e-9);
            assertEquals(root.y() - 40 / zoom, moved.y(), 1e-9);
            assertEquals(zoom, viewport.zoom(), 1e-9);
        }
    }

    @Test
    void releaseStopsMovementButKeepsPositionAcrossLayoutRebuilds() {
        var interaction = new AelisGraphInteraction();
        assertTrue(interaction.beginDrag(drawing(), 7, 9));
        interaction.dragTo(-293, 509);
        interaction.release();
        assertFalse(interaction.dragging());
        assertFalse(interaction.dragTo(7, 9));
        assertEquals(0, interaction.nodeAt(drawing(), -293, 509).node());
        assertTrue(interaction.beginDrag(drawing(), -293, 509));
        interaction.dragTo(-193, 409);
        assertEquals(-200, interaction.nodeAt(drawing(), -193, 409).x());
    }

    @Test
    void emptySpaceAndHalfOpenEdgesDoNotSelectANode() {
        var interaction = new AelisGraphInteraction();
        assertNull(interaction.nodeAt(drawing(), AelisGraphLayout.NODE_WIDTH, 0));
        assertNull(interaction.nodeAt(drawing(), 0, AelisGraphLayout.NODE_HEIGHT));
        assertFalse(interaction.beginDrag(drawing(), -1, -1));
        assertFalse(interaction.dragging());
    }

    @Test
    void draggedNodeWinsHitTestingWhenNodesOverlap() {
        var drawing = drawing();
        var child = drawing.boxes().getLast();
        var interaction = new AelisGraphInteraction();
        assertTrue(interaction.beginDrag(drawing, 7, 9));
        interaction.dragTo(child.x() + 7, child.y() + 9);
        assertEquals(0, interaction.boxes(drawing).getLast().node());
        assertEquals(0, interaction.nodeAt(drawing, child.x() + 7, child.y() + 9).node());
    }

    @Test
    void movedNodeRemainsOnTopAfterReleaseAndCanBeGrabbedAgain() {
        var drawing = drawing();
        var child = drawing.boxes().getLast();
        var interaction = new AelisGraphInteraction();
        assertTrue(interaction.beginDrag(drawing, 7, 9));
        interaction.dragTo(child.x() + 7, child.y() + 9);
        interaction.release();
        assertFalse(interaction.dragging());
        assertEquals(0, interaction.boxes(drawing).getLast().node());
        assertEquals(0, interaction.nodeAt(drawing, child.x() + 7, child.y() + 9).node());
        assertTrue(interaction.beginDrag(drawing, child.x() + 7, child.y() + 9));
        interaction.dragTo(507, 409);
        assertEquals(0, interaction.nodeAt(drawing, 507, 409).node());
        assertTrue(interaction.boxes(drawing).contains(child));
    }

    @Test
    void cycleBoundsFollowMovedNodesAndFoldingPreservesPositions() {
        var base = drawing();
        var group = new AelisGraphLayout.Group(0, List.of(0, 1), true, false, 0, 0, 128, 120);
        var interaction = new AelisGraphInteraction();
        assertTrue(interaction.beginDrag(base, 7, 9));
        interaction.dragTo(507, 409);
        interaction.release();
        var bounds = interaction.groupBounds(group, interaction.boxes(base));
        assertEquals(500 + AelisGraphLayout.NODE_WIDTH + 6, bounds.x() + bounds.width());
        assertEquals(400 + AelisGraphLayout.NODE_HEIGHT + 8, bounds.y() + bounds.height());
        var folded = new AelisGraphLayout.Group(0, group.nodes(), true, true, 10, 20, 128, 20);
        assertEquals(new AelisGraphInteraction.Bounds(10, 20, 128, 20),
                interaction.groupBounds(folded, List.of()));
        assertNull(interaction.nodeAt(new AelisGraphLayout.Drawing(List.of(folded), List.of(),
                new int[] {0, 0}, Set.of()), 507, 409));
        assertEquals(0, interaction.nodeAt(base, 507, 409).node());
    }

    @Test
    void nodeHitTestingUsesEachBoxDimensionsAtEveryHalfOpenEdge() {
        var interaction = new AelisGraphInteraction();
        var boxes = List.of(new AelisGraphLayout.Box(0, 20, 30, 300, 80),
                new AelisGraphLayout.Box(1, 400, 180, 60, 20));
        var drawing = new AelisGraphLayout.Drawing(List.of(), boxes, new int[] {0, 1}, Set.of(0, 1));
        for (var box : boxes) {
            assertEquals(box, interaction.nodeAt(drawing, box.x(), box.y()));
            assertEquals(box, interaction.nodeAt(drawing, box.x() + box.width() - 1, box.y()));
            assertEquals(box, interaction.nodeAt(drawing, box.x(), box.y() + box.height() - 1));
            assertEquals(box, interaction.nodeAt(drawing,
                    box.x() + box.width() - 1, box.y() + box.height() - 1));
            assertNull(interaction.nodeAt(drawing, box.x() + box.width(), box.y()));
            assertNull(interaction.nodeAt(drawing, box.x(), box.y() + box.height()));
            assertNull(interaction.nodeAt(drawing,
                    box.x() + box.width(), box.y() + box.height() - 1));
            assertNull(interaction.nodeAt(drawing,
                    box.x() + box.width() - 1, box.y() + box.height()));
            assertNull(interaction.nodeAt(drawing, box.x() - 1, box.y()));
            assertNull(interaction.nodeAt(drawing, box.x(), box.y() - 1));
        }
    }

    @Test
    void draggedPositionsKeepCurrentBoxDimensionsAfterLayoutRebuild() {
        var base = drawing(Map.of(0, new AelisGraphLayout.NodeSize(180, 72),
                1, new AelisGraphLayout.NodeSize(60, 20)));
        var interaction = new AelisGraphInteraction();
        assertTrue(interaction.beginDrag(base, 7, 9));
        assertTrue(interaction.dragTo(507, 409));
        interaction.release();
        assertEquals(new AelisGraphLayout.Box(0, 500, 400, 180, 72), interaction.boxes(base).getLast());
        assertTrue(interaction.boxes(base).contains(base.boxes().getLast()));
        for (int[] size : new int[][] {{300, 80}, {60, 20}}) {
            var rebuilt = drawing(Map.of(0, new AelisGraphLayout.NodeSize(size[0], size[1]),
                    1, new AelisGraphLayout.NodeSize(60, 20)));
            var moved = new AelisGraphLayout.Box(0, 500, 400, size[0], size[1]);
            assertEquals(moved, interaction.boxes(rebuilt).getLast());
            assertTrue(interaction.boxes(rebuilt).contains(rebuilt.boxes().getLast()));
            assertEquals(moved, interaction.nodeAt(rebuilt, 500 + size[0] - 1, 400 + size[1] - 1));
            assertNull(interaction.nodeAt(rebuilt, 500 + size[0], 400));
            assertNull(interaction.nodeAt(rebuilt, 500, 400 + size[1]));
        }
    }

    @Test
    void cycleBoundsFollowDraggedNodesAndEachCurrentBoxSize() {
        List<AEKey> keys = List.of(new TestAEKey("cycle_a"), new TestAEKey("cycle_b"));
        var graph = new AelisPlanGraph(keys, List.of(
                new AelisPlanGraph.Edge(0, 1, false),
                new AelisPlanGraph.Edge(1, 0, false)), 0, true);
        var drawing = AelisGraphLayout.build(graph, Set.of(0), Set.of(), Map.of(
                0, new AelisGraphLayout.NodeSize(300, 80),
                1, new AelisGraphLayout.NodeSize(180, 50)));
        var group = drawing.groups().getFirst();
        var interaction = new AelisGraphInteraction();
        assertEquals(new AelisGraphInteraction.Bounds(group.x(), group.y(), group.width(), group.height()),
                interaction.groupBounds(group, interaction.boxes(drawing)));
        var first = drawing.boxes().getFirst();
        assertTrue(interaction.beginDrag(drawing, first.x() + 297, first.y() + 79));
        assertTrue(interaction.dragTo(797, 479));
        interaction.release();
        assertEquals(new AelisGraphInteraction.Bounds(0, 85, 806, 403),
                interaction.groupBounds(group, interaction.boxes(drawing)));
        var rebuiltSizes = Map.of(0, new AelisGraphLayout.NodeSize(360, 96),
                1, new AelisGraphLayout.NodeSize(420, 60));
        var rebuilt = AelisGraphLayout.build(graph, Set.of(0), Set.of(), rebuiltSizes);
        assertEquals(new AelisGraphInteraction.Bounds(0, 101, 866, 403),
                interaction.groupBounds(rebuilt.groups().getFirst(), interaction.boxes(rebuilt)));
        assertEquals(new AelisGraphLayout.Box(0, 500, 400, 360, 96), interaction.boxes(rebuilt).getLast());
        var folded = AelisGraphLayout.build(graph, Set.of(0), Set.of(0), rebuiltSizes);
        assertEquals(new AelisGraphInteraction.Bounds(0, 0, 432, 20),
                interaction.groupBounds(folded.groups().getFirst(), interaction.boxes(folded)));
    }

    @Test
    void clearingForNewPlanDropsPositionsAndCancelsDrag() {
        var interaction = new AelisGraphInteraction();
        assertTrue(interaction.beginDrag(drawing(), 7, 9));
        interaction.dragTo(507, 409);
        interaction.clear();
        assertFalse(interaction.dragging());
        assertNull(interaction.nodeAt(drawing(), 507, 409));
        assertEquals(0, interaction.nodeAt(drawing(), 7, 9).node());
    }

    @Test
    void boundsExtremeMovementAndIgnoresNonFiniteCoordinates() {
        var interaction = new AelisGraphInteraction();
        assertTrue(interaction.beginDrag(drawing(), 7, 9));
        assertFalse(interaction.dragTo(Double.NaN, 0));
        assertFalse(interaction.dragTo(0, Double.POSITIVE_INFINITY));
        assertTrue(interaction.dragTo(1e12, -1e12));
        var moved = interaction.boxes(drawing()).getLast();
        assertEquals(200_000, moved.x());
        assertEquals(-200_000, moved.y());
    }
}
