package dev.yym.skeletonsearcher;

public enum SphereBand {
    GT_250(0, 250, ">250", 0xFFFFFF, true),
    R250_150(150, 250, "250–150", 0xFF9A9A, false),
    R150_100(100, 150, "150–100", 0x2547C7, false),
    R100_50(50, 100, "100–50", 0xFFE45C, false),
    R50_25(25, 50, "50–25", 0x55FF55, false),
    R50_0(0, 50, "50–0", 0x55CCFF, false);

    private final int innerRadius;
    private final int outerRadius;
    private final String label;
    private final int textColor;
    private final boolean exclusion;

    SphereBand(int innerRadius, int outerRadius, String label, int textColor, boolean exclusion) {
        this.innerRadius = innerRadius;
        this.outerRadius = outerRadius;
        this.label = label;
        this.textColor = textColor;
        this.exclusion = exclusion;
    }

    public int innerRadius() {
        return innerRadius;
    }

    public int outerRadius() {
        return outerRadius;
    }

    public String label() {
        return label;
    }

    public int textColor() {
        return textColor;
    }

    /**
     * “>250”不是普通球壳，而是从已有结果中排除半径 250 以内区域的差集约束。
     */
    public boolean isExclusion() {
        return exclusion;
    }

    public static SphereBand fromRadii(int inner, int outer) {
        for (SphereBand band : values()) {
            if (band.innerRadius == inner && band.outerRadius == outer) {
                return band;
            }
        }
        return null;
    }
}
