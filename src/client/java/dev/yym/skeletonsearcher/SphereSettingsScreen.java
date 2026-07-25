package dev.yym.skeletonsearcher;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Locale;

public final class SphereSettingsScreen extends Screen {
    private static final int COLOR_OVERLAY = 0xB0120F18;
    private static final int COLOR_PANEL = 0xE0201829;
    private static final int COLOR_PANEL_2 = 0xE02C2036;
    private static final int COLOR_ROW = 0xD0754A6A;
    private static final int COLOR_ROW_ALT = 0xD05C3C61;
    private static final int COLOR_ORANGE = 0xFFFFB248;
    private static final int COLOR_PURPLE = 0xFFB56AA0;
    private static final int COLOR_TEXT = 0xFFF5F2F6;
    private static final int COLOR_MUTED = 0xFFC8BBCD;
    private static final int COLOR_OK = 0xFF79E38C;
    private static final int COLOR_ERROR = 0xFFFF7777;

    private final Screen parent;
    private Page page = Page.SPHERES;
    private SphereBand selectedBand = SphereBand.R250_150;

    private String xText = "0";
    private String yText = "64";
    private String zText = "0";
    private boolean coordinatesInitialized;
    private TextFieldWidget xField;
    private TextFieldWidget yField;
    private TextFieldWidget zField;

    private int listPage;
    private int visibleRows = 4;
    private String status = "";
    private boolean statusError;

    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int contentX;
    private int contentWidth;
    private int listStartY;

    public SphereSettingsScreen(Screen parent) {
        super(Text.literal("Skeleton Searcher 设置"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(width - 16, 760);
        panelHeight = Math.min(height - 12, 430);
        panelX = (width - panelWidth) / 2;
        panelY = Math.max(6, (height - panelHeight) / 2);
        contentX = panelX + 14;
        contentWidth = panelWidth - 28;
        listStartY = panelY + 126;
        initializePlayerCoordinates();
        visibleRows = Math.clamp((panelY + panelHeight - 50 - listStartY) / 24, 2, 7);
        rebuildWidgets();
    }

    private void rebuildWidgets() {
        preserveCoordinateText();
        clearChildren();

        int tabsY = panelY + 32;
        int tabWidth = Math.min(116, (contentWidth - 12) / 3);
        addTabButton("球体管理", Page.SPHERES, contentX, tabsY, tabWidth);
        addTabButton("显示设置", Page.DISPLAY, contentX + tabWidth + 6, tabsY, tabWidth);
        addTabButton("使用说明", Page.HELP, contentX + (tabWidth + 6) * 2, tabsY, tabWidth);

        switch (page) {
            case SPHERES -> buildSphereWidgets();
            case DISPLAY -> buildDisplayWidgets();
            case HELP -> buildHelpWidgets();
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("完成"), button -> close())
                .dimensions(panelX + panelWidth - 96, panelY + panelHeight - 28, 82, 20)
                .build());
    }

    private void addTabButton(String label, Page target, int x, int y, int width) {
        String prefix = page == target ? "◆ " : "";
        addDrawableChild(ButtonWidget.builder(Text.literal(prefix + label), button -> {
                    page = target;
                    status = "";
                    rebuildWidgets();
                })
                .dimensions(x, y, width, 20)
                .build());
    }

    private void buildSphereWidgets() {
        int inputY = panelY + 67;
        int labelGap = 16;
        int fieldWidth = Math.max(54, Math.min(92, (contentWidth - 300) / 3));
        int cursor = contentX;

        xField = createCoordinateField(cursor + labelGap, inputY, fieldWidth, "X", xText);
        cursor += labelGap + fieldWidth + 10;
        yField = createCoordinateField(cursor + labelGap, inputY, fieldWidth, "Y", yText);
        cursor += labelGap + fieldWidth + 10;
        zField = createCoordinateField(cursor + labelGap, inputY, fieldWidth, "Z", zText);

        int actionY = panelY + 94;
        int actionWidth = Math.max(58, Math.min(126, (contentWidth - 18) / 4));
        addDrawableChild(ButtonWidget.builder(Text.literal("使用玩家坐标"), button -> usePlayerPosition())
                .dimensions(contentX, actionY, actionWidth, 20)
                .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("条件：" + selectedBand.label())
                        .styled(style -> style.withColor(selectedBand.textColor())), button -> {
                    selectedBand = nextBand(selectedBand);
                    rebuildWidgets();
                })
                .dimensions(contentX + actionWidth + 6, actionY, actionWidth, 20)
                .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("生成球体"), button -> addSphere())
                .dimensions(contentX + (actionWidth + 6) * 2, actionY, actionWidth, 20)
                .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("清除全部"), button -> clearAll())
                .dimensions(contentX + (actionWidth + 6) * 3, actionY, actionWidth, 20)
                .build());

        ModConfig.Snapshot snapshot = ModConfig.snapshot();
        List<SphereRegion> spheres = snapshot.spheres();
        int maxPage = maxListPage(spheres.size());
        listPage = Math.clamp(listPage, 0, maxPage);
        int first = listPage * visibleRows;
        int last = Math.min(spheres.size(), first + visibleRows);
        int deleteWidth = 50;

        for (int index = first; index < last; index++) {
            SphereRegion sphere = spheres.get(index);
            int row = index - first;
            int y = listStartY + row * 24 + 2;
            addDrawableChild(ButtonWidget.builder(Text.literal("删除"), button -> {
                        if (ModConfig.removeSphere(sphere.id)) {
                            SkeletonSearcherClient.RENDERER.markDirty();
                            setStatus("已删除该球体", false);
                            rebuildWidgets();
                        }
                    })
                    .dimensions(contentX + contentWidth - deleteWidth - 3, y, deleteWidth, 18)
                    .build());
        }

        int pageY = listStartY + visibleRows * 24 + 2;
        addDrawableChild(ButtonWidget.builder(Text.literal("上一页"), button -> {
                    listPage = Math.max(0, listPage - 1);
                    rebuildWidgets();
                })
                .dimensions(contentX, pageY, 72, 18)
                .build());
        addDrawableChild(ButtonWidget.builder(Text.literal("下一页"), button -> {
                    listPage = Math.min(maxListPage(ModConfig.snapshot().spheres().size()), listPage + 1);
                    rebuildWidgets();
                })
                .dimensions(contentX + 78, pageY, 72, 18)
                .build());
    }

    private TextFieldWidget createCoordinateField(int x, int y, int width, String name, String value) {
        TextFieldWidget field = new TextFieldWidget(textRenderer, x, y, width, 20, Text.literal(name + " 坐标"));
        field.setMaxLength(11);
        field.setTextPredicate(text -> text.matches("-?\\d{0,10}"));
        field.setPlaceholder(Text.literal(name));
        field.setText(value);
        field.setChangedListener(text -> {
            switch (name) {
                case "X" -> xText = text;
                case "Y" -> yText = text;
                case "Z" -> zText = text;
                default -> {
                }
            }
        });
        addDrawableChild(field);
        return field;
    }

    private void buildDisplayWidgets() {
        ModConfig.Snapshot snapshot = ModConfig.snapshot();
        int y = panelY + 72;
        addDrawableChild(new OpacitySlider(contentX, y, contentWidth, 20, snapshot.opacity()));
        y += 30;
        addDrawableChild(new RenderDistanceSlider(contentX, y, contentWidth, 20, snapshot.renderDistance()));
        y += 30;

        // 两个显示开关并排放置；在 480×257 的最小测试尺寸下仍有足够间距。
        int gap = 6;
        int halfWidth = Math.max(110, (contentWidth - gap) / 2);
        addDrawableChild(ButtonWidget.builder(
                        Text.literal("渲染样式：" + snapshot.renderStyle().chineseName()),
                        button -> toggleRenderStyle())
                .dimensions(contentX, y, halfWidth, 20)
                .build());
        addDrawableChild(ButtonWidget.builder(
                        Text.literal("隔墙透视：" + (snapshot.throughWalls() ? "开启" : "关闭")),
                        button -> toggleThroughWalls())
                .dimensions(contentX + halfWidth + gap, y, contentWidth - halfWidth - gap, 20)
                .build());
        y += 30;

        int buttonWidth = Math.min(280, contentWidth);
        addDrawableChild(ButtonWidget.builder(Text.literal("清除所有已生成球体"), button -> clearAll())
                .dimensions(contentX, y, buttonWidth, 20)
                .build());
    }

    private void buildHelpWidgets() {
        // 说明文字直接在 render 中绘制，避免低分辨率下产生过多控件。
    }

    private void initializePlayerCoordinates() {
        if (coordinatesInitialized) {
            return;
        }
        coordinatesInitialized = true;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }
        BlockPos pos = client.player.getBlockPos();
        xText = Integer.toString(pos.getX());
        yText = Integer.toString(pos.getY());
        zText = Integer.toString(pos.getZ());
    }

    private void usePlayerPosition() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            setStatus("请先进入一个世界", true);
            return;
        }
        BlockPos pos = client.player.getBlockPos();
        xText = Integer.toString(pos.getX());
        yText = Integer.toString(pos.getY());
        zText = Integer.toString(pos.getZ());
        if (xField != null) xField.setText(xText);
        if (yField != null) yField.setText(yText);
        if (zField != null) zField.setText(zText);
        setStatus("已填入玩家所在方块坐标", false);
    }

    private void addSphere() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            setStatus("请先进入一个世界", true);
            return;
        }

        try {
            int x = Integer.parseInt(xText);
            int y = Integer.parseInt(yText);
            int z = Integer.parseInt(zText);
            String dimension = client.world.getRegistryKey().getValue().toString();
            SphereRegion sphere = new SphereRegion(x, y, z, selectedBand, dimension);
            ModConfig.AddResult result = ModConfig.tryAddSphere(sphere);
            switch (result) {
                case ADDED -> {
                    SkeletonSearcherClient.RENDERER.markDirty();
                    ModConfig.Snapshot updated = ModConfig.snapshot();
                    if (updated.spheres().size() < 3) {
                        String action = selectedBand.isExclusion()
                                ? "差集条件已记录"
                                : "球体已记录并参与计算";
                        setStatus(action + "；达到 3 个后开始渲染（当前 "
                                + updated.spheres().size() + "/3）", false);
                    } else if (selectedBand.isExclusion()) {
                        setStatus("已排除该坐标半径 250 以内区域，正在更新结果", false);
                    } else {
                        setStatus("球体已生成，正在更新共同交集", false);
                    }
                    rebuildWidgets();
                }
                case REQUIRES_BASE -> setStatus("“>250”需要先在当前维度生成至少一个普通球体", true);
                case OUT_OF_REGION -> setStatus("已超出区域", true);
                case LIMIT_REACHED -> setStatus("球体数量已达到上限（64 个）", true);
                case INVALID -> setStatus("球体参数无效", true);
            }
        } catch (NumberFormatException exception) {
            setStatus("坐标格式不正确", true);
        }
    }

    private void clearAll() {
        int count = ModConfig.clearAll();
        SkeletonSearcherClient.RENDERER.clearCache();
        setStatus(count == 0 ? "当前没有已生成的球体" : "已清除全部球体（" + count + " 个）", false);
        listPage = 0;
        rebuildWidgets();
    }

    private void toggleRenderStyle() {
        RenderStyle next = ModConfig.snapshot().renderStyle().next();
        ModConfig.setRenderStyle(next);
        SkeletonSearcherClient.RENDERER.markDirty();
        setStatus("渲染样式已切换为：" + next.chineseName(), false);
        rebuildWidgets();
    }

    private void toggleThroughWalls() {
        boolean next = !ModConfig.snapshot().throughWalls();
        ModConfig.setThroughWalls(next);
        // 网格内容不变，只在下一帧切换有/无深度测试的渲染管线。
        setStatus("隔墙透视已" + (next ? "开启" : "关闭"), false);
        rebuildWidgets();
    }

    private void preserveCoordinateText() {
        if (xField != null) xText = xField.getText();
        if (yField != null) yText = yField.getText();
        if (zField != null) zText = zField.getText();
    }

    private int maxListPage(int count) {
        return Math.max(0, (count - 1) / visibleRows);
    }

    private static SphereBand nextBand(SphereBand current) {
        SphereBand[] values = SphereBand.values();
        return values[(current.ordinal() + 1) % values.length];
    }

    private void setStatus(String message, boolean error) {
        status = message;
        statusError = error;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        context.fill(0, 0, width, height, COLOR_OVERLAY);
        fillBorder(context, panelX, panelY, panelWidth, panelHeight, COLOR_ORANGE, COLOR_PANEL);
        context.fill(panelX + 5, panelY + 5, panelX + panelWidth - 5, panelY + 27, COLOR_PANEL_2);
        context.drawTextWithShadow(textRenderer, title, contentX, panelY + 11, COLOR_TEXT);
        context.drawTextWithShadow(textRenderer, "Fabric 1.21.11 · 客户端", panelX + panelWidth - 154, panelY + 11, COLOR_ORANGE);

        if (page == Page.SPHERES) {
            renderSpherePage(context);
        } else if (page == Page.DISPLAY) {
            renderDisplayPage(context);
        } else {
            renderHelpPage(context);
        }

        if (!status.isBlank()) {
            int color = statusError ? COLOR_ERROR : COLOR_OK;
            context.drawTextWithShadow(textRenderer, trimToWidth(status, contentWidth - 96),
                    contentX, panelY + panelHeight - 23, color);
        }
        super.render(context, mouseX, mouseY, deltaTicks);
    }

    private void renderSpherePage(DrawContext context) {
        int inputY = panelY + 73;
        int fieldWidth = Math.max(54, Math.min(92, (contentWidth - 300) / 3));
        int cursor = contentX;
        context.drawTextWithShadow(textRenderer, "X", cursor, inputY, COLOR_TEXT);
        cursor += 16 + fieldWidth + 10;
        context.drawTextWithShadow(textRenderer, "Y", cursor, inputY, COLOR_TEXT);
        cursor += 16 + fieldWidth + 10;
        context.drawTextWithShadow(textRenderer, "Z", cursor, inputY, COLOR_TEXT);

        ModConfig.Snapshot snapshot = ModConfig.snapshot();
        List<SphereRegion> spheres = snapshot.spheres();
        int first = listPage * visibleRows;
        int last = Math.min(spheres.size(), first + visibleRows);

        context.drawTextWithShadow(textRenderer,
                trimToWidth("已生成条件：" + spheres.size() + "   第 3 个起显示交集减去 >250 排除区", contentWidth),
                contentX, listStartY - 12, COLOR_MUTED);

        for (int row = 0; row < visibleRows; row++) {
            int y = listStartY + row * 24;
            int background = row % 2 == 0 ? COLOR_ROW : COLOR_ROW_ALT;
            fillBorder(context, contentX, y, contentWidth, 22, COLOR_ORANGE, background);
            int index = first + row;
            if (index >= last) {
                context.drawTextWithShadow(textRenderer, "— 空 —", contentX + 8, y + 7, COLOR_MUTED);
                continue;
            }
            SphereRegion sphere = spheres.get(index);
            SphereBand band = sphere.band();
            String line = String.format(Locale.ROOT, "#%d  (%d, %d, %d)  条件 %s  %s",
                    index + 1,
                    sphere.x,
                    sphere.y,
                    sphere.z,
                    band == null ? sphere.outerRadius + "–" + sphere.innerRadius : band.label(),
                    sphere.shortDimension());
            int lineColor = band == null ? COLOR_TEXT : 0xFF000000 | band.textColor();
            context.drawTextWithShadow(textRenderer, trimToWidth(line, contentWidth - 70),
                    contentX + 7, y + 7, lineColor);
        }

        int pageCount = maxListPage(spheres.size()) + 1;
        int pageY = listStartY + visibleRows * 24 + 8;
        context.drawTextWithShadow(textRenderer, "第 " + (listPage + 1) + " / " + pageCount + " 页",
                contentX + 160, pageY, COLOR_MUTED);
    }

    private void renderDisplayPage(DrawContext context) {
        context.drawTextWithShadow(textRenderer, "高亮显示", contentX, panelY + 60, COLOR_ORANGE);
        int y = panelY + 190;
        context.drawTextWithShadow(textRenderer,
                trimToWidth("隔墙透视开启时，高亮可穿过实体方块显示；关闭时遵守正常遮挡。", contentWidth),
                contentX, y, COLOR_MUTED);
        y += 14;
        context.drawTextWithShadow(textRenderer,
                trimToWidth("区段缓存、暴露面剔除和距离 LOD 会自动降低大范围渲染压力。", contentWidth),
                contentX, y, COLOR_MUTED);
    }

    private void renderHelpPage(DrawContext context) {
        // 使用紧凑排版，保证在 480×257 等较低 GUI 尺寸下不与“完成”按钮重叠。
        int y = panelY + 66;
        drawHelpLine(context, "默认快捷键", y, COLOR_ORANGE);
        y += 14;
        drawHelpLine(context, "O：打开设置   Delete：清除全部条件", y, COLOR_TEXT);
        y += 16;
        drawHelpLine(context, "条件档位", y, COLOR_ORANGE);
        y += 14;
        drawColoredBandLegend(context, y);
        y += 16;
        drawHelpLine(context, "运算与显示", y, COLOR_ORANGE);
        y += 14;
        drawHelpLine(context, "前两个条件只计算；生成第 3 个后显示最终结果。", y, COLOR_TEXT);
        y += 12;
        drawHelpLine(context, "普通档位取交集；>250 保留距离该坐标严格大于 250 格的部分。", y, COLOR_TEXT);
        y += 12;
        drawHelpLine(context, ">250 不能作为第一个条件，添加时不检查是否与旧结果重合。", y, COLOR_TEXT);
        y += 12;
        drawHelpLine(context, "显示设置可切换球壳/半透明方块，以及隔墙透视开关。", y, COLOR_TEXT);
        y += 12;
        drawHelpLine(context, "所有高亮仅为客户端显示，不会修改世界。", y, COLOR_TEXT);
        y += 12;
        drawHelpLine(context, "配置：config/skeleton_searcher.json", y, COLOR_MUTED);
    }


    private void drawColoredBandLegend(DrawContext context, int y) {
        int x = contentX;
        SphereBand[] bands = SphereBand.values();
        for (int i = 0; i < bands.length; i++) {
            SphereBand band = bands[i];
            String label = band.label();
            int color = 0xFF000000 | band.textColor();
            context.drawTextWithShadow(textRenderer, label, x, y, color);
            x += textRenderer.getWidth(label);
            if (i < bands.length - 1) {
                String separator = "、";
                context.drawTextWithShadow(textRenderer, separator, x, y, COLOR_MUTED);
                x += textRenderer.getWidth(separator);
            }
        }
    }

    private void drawHelpLine(DrawContext context, String text, int y, int color) {
        context.drawTextWithShadow(textRenderer, trimToWidth(text, contentWidth), contentX, y, color);
    }

    private String trimToWidth(String value, int maxWidth) {
        return textRenderer.trimToWidth(value, maxWidth);
    }

    private static void fillBorder(DrawContext context, int x, int y, int width, int height, int border, int fill) {
        context.fill(x, y, x + width, y + height, border);
        context.fill(x + 2, y + 2, x + width - 2, y + height - 2, fill);
    }

    @Override
    public void close() {
        preserveCoordinateText();
        ModConfig.save();
        MinecraftClient.getInstance().setScreen(parent);
    }

    @Override
    public void removed() {
        ModConfig.save();
        super.removed();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private enum Page {
        SPHERES,
        DISPLAY,
        HELP
    }

    private static final class OpacitySlider extends SliderWidget {
        private OpacitySlider(int x, int y, int width, int height, float opacity) {
            super(x, y, width, height, Text.empty(), normalize(opacity));
            updateMessage();
        }

        private static double normalize(float opacity) {
            return (Math.clamp(opacity, 0.03f, 0.65f) - 0.03) / 0.62;
        }

        @Override
        protected void updateMessage() {
            float opacity = (float) (0.03 + value * 0.62);
            setMessage(Text.literal("高亮透明度：" + Math.round(opacity * 100.0f) + "%"));
        }

        @Override
        protected void applyValue() {
            float opacity = (float) (0.03 + value * 0.62);
            ModConfig.setOpacity(opacity);
            // 仅更新缓存网格的顶点颜色，不重新计算几何。
        }
    }

    private static final class RenderDistanceSlider extends SliderWidget {
        private RenderDistanceSlider(int x, int y, int width, int height, int distance) {
            super(x, y, width, height, Text.empty(), normalize(distance));
            updateMessage();
        }

        private static double normalize(int distance) {
            return (Math.clamp(distance, 24, 300) - 24) / 276.0;
        }

        private int distance() {
            return 24 + (int) Math.round(value * 276.0);
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.literal("本地渲染范围：" + distance() + " 格"));
        }

        @Override
        protected void applyValue() {
            ModConfig.setRenderDistance(distance());
            SkeletonSearcherClient.RENDERER.markDirty();
        }
    }
}
