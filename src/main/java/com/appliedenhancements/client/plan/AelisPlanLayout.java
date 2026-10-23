package com.appliedenhancements.client.plan;

/** All dimensions are scaled GUI pixels; the material table uses the full panel width. */
public record AelisPlanLayout(int width, int height) {
    public static final int ROW_HEIGHT = 22;
    public static final int ROW_TOP = 108;
    public static final int FOOTER_HEIGHT = 56;
    public static final int FOOTER_BUTTON_HEIGHT = 19;

    public static AelisPlanLayout fit(int screenWidth, int screenHeight) {
        return new AelisPlanLayout(Math.min(360, Math.max(0, screenWidth - 12)),
                Math.min(330, Math.max(0, screenHeight - 12)));
    }

    public int tableRight() { return width - 10; }
    public int footerTop() { return height - 64; }
    public int footerBottom() { return footerTop() + FOOTER_HEIGHT; }
    public int cpuButtonTop() { return footerTop() + 7; }
    public int actionButtonTop() { return cpuButtonTop() + FOOTER_BUTTON_HEIGHT + 5; }
    public int visibleRows() { return Math.max(1, (footerTop() - 5 - ROW_TOP) / ROW_HEIGHT); }
    public int rowsBottom() { return ROW_TOP + visibleRows() * ROW_HEIGHT; }
    public boolean usable() { return width >= 300 && height >= 220; }

    public GraphArea graphArea(int top, int hintLines, int lineHeight) {
        int hintTop = footerTop() - 8 - hintLines * (lineHeight + 1);
        boolean inlineHint = hintTop - 5 - top >= 48;
        int bottom = inlineHint ? hintTop - 5 : footerTop() - 4;
        return new GraphArea(top, Math.max(top, bottom), hintTop, inlineHint);
    }

    public record GraphArea(int top, int bottom, int hintTop, boolean inlineHint) {}

    public Columns columns(boolean cyclic) { return new Columns(tableRight() - 14, cyclic); }

    public static int centeredTextTop(int top, int height, int lineHeight) {
        return top + (height - lineHeight) / 2;
    }

    public static int centeredTextLeft(int center, int textWidth) {
        return center - textWidth / 2;
    }

    /** Headers and values share column centers; the material names remain left-aligned. */
    public record Columns(int right, boolean cyclic) {
        public int craftRight() { return right - (cyclic ? 64 : 0); }
        public int storedRight() { return craftRight() - 48; }
        public int demandRight() { return storedRight() - 48; }
        public int nameWidth() { return demandRight() - 50 - 40; }
        public int demandCenter() { return demandRight() - 22; }
        public int storedCenter() { return storedRight() - 22; }
        public int craftCenter() { return craftRight() - 22; }
        public int cyclicCenter() { return right - 30; }
    }
}
