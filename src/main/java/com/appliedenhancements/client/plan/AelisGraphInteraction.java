package com.appliedenhancements.client.plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Client-local node positions, in graph coordinates rather than scaled screen pixels. */
public final class AelisGraphInteraction {
    public record Bounds(double x, double y, double width, double height) {
        public boolean contains(double px, double py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }
    }

    private final Map<Integer, AelisGraphLayout.Box> positions = new HashMap<>();
    private int draggedNode = -1;
    private int foregroundNode = -1;
    private double offsetX;
    private double offsetY;

    public List<AelisGraphLayout.Box> boxes(AelisGraphLayout.Drawing drawing) {
        var result = new ArrayList<AelisGraphLayout.Box>(drawing.boxes().size());
        AelisGraphLayout.Box foreground = null;
        for (var base : drawing.boxes()) {
            var position = positions.get(base.node());
            var box = position == null ? base : new AelisGraphLayout.Box(base.node(),
                    position.x(), position.y(), base.width(), base.height());
            if (box.node() == foregroundNode) foreground = box;
            else result.add(box);
        }
        if (foreground != null) result.add(foreground);
        return result;
    }

    public AelisGraphLayout.Box nodeAt(AelisGraphLayout.Drawing drawing, double x, double y) {
        var boxes = boxes(drawing);
        for (int i = boxes.size() - 1; i >= 0; i--) {
            var box = boxes.get(i);
            if (new Bounds(box.x(), box.y(), box.width(), box.height()).contains(x, y)) return box;
        }
        return null;
    }

    public boolean beginDrag(AelisGraphLayout.Drawing drawing, double x, double y) {
        release();
        var box = nodeAt(drawing, x, y);
        if (box == null) return false;
        draggedNode = box.node();
        foregroundNode = draggedNode;
        offsetX = x - box.x();
        offsetY = y - box.y();
        return true;
    }

    public boolean dragTo(double x, double y) {
        if (draggedNode < 0 || !Double.isFinite(x) || !Double.isFinite(y)) return false;
        positions.put(draggedNode, new AelisGraphLayout.Box(draggedNode,
                Math.max(-200_000, Math.min(200_000, x - offsetX)),
                Math.max(-200_000, Math.min(200_000, y - offsetY))));
        return true;
    }

    public boolean dragging() { return draggedNode >= 0; }
    public void release() { draggedNode = -1; }
    public void clear() { release(); foregroundNode = -1; positions.clear(); }

    public Bounds groupBounds(AelisGraphLayout.Group group, List<AelisGraphLayout.Box> boxes) {
        if (group.collapsed()) return new Bounds(group.x(), group.y(), group.width(), group.height());
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (var box : boxes) {
            if (!group.nodes().contains(box.node())) continue;
            minX = Math.min(minX, box.x());
            minY = Math.min(minY, box.y());
            maxX = Math.max(maxX, box.x() + box.width());
            maxY = Math.max(maxY, box.y() + box.height());
        }
        if (!Double.isFinite(minX)) return new Bounds(group.x(), group.y(), group.width(), group.height());
        return new Bounds(minX - 6, minY - 19, maxX - minX + 12, maxY - minY + 27);
    }
}
