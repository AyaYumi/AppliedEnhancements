package com.appliedenhancements.client.plan;

/** Local canvas coordinates. Zoom keeps the point under the cursor stationary. */
public final class AelisGraphViewport {
    private double zoom = 0.85;
    private double x = 22, y = 12;
    public double zoom() { return zoom; }
    public double x() { return x; }
    public double y() { return y; }
    public double worldX(double screen) { return (screen - x) / zoom; }
    public double worldY(double screen) { return (screen - y) / zoom; }
    public void pan(double dx, double dy) {
        x = Math.max(-200_000, Math.min(200_000, x + dx));
        y = Math.max(-200_000, Math.min(200_000, y + dy));
    }
    public void zoom(double factor, double anchorX, double anchorY) {
        double wx = worldX(anchorX), wy = worldY(anchorY);
        zoom = Math.max(0.25, Math.min(2.0, zoom * factor));
        x = anchorX - wx * zoom;
        y = anchorY - wy * zoom;
    }
    public void reset() { zoom = 0.85; x = 22; y = 12; }
}
