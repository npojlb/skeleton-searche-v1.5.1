package dev.yym.skeletonsearcher;

public enum RenderStyle {
    SHELL("球壳"),
    SOLID_BLOCKS("半透明方块");

    private final String chineseName;

    RenderStyle(String chineseName) {
        this.chineseName = chineseName;
    }

    public String chineseName() {
        return chineseName;
    }

    public RenderStyle next() {
        return this == SHELL ? SOLID_BLOCKS : SHELL;
    }
}
