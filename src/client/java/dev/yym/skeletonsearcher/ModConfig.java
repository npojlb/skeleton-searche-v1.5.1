package dev.yym.skeletonsearcher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ModConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("skeleton_searcher/config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("skeleton_searcher.json");
    private static final int MAX_SPHERES = 64;

    private static Data data = new Data();
    private static int revision = 1;

    private ModConfig() {
    }

    public static synchronized void load() {
        if (!Files.isRegularFile(CONFIG_PATH)) {
            data = new Data();
            save();
            return;
        }

        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            Data loaded = GSON.fromJson(reader, Data.class);
            data = sanitize(loaded);
            revision++;
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("无法读取 Skeleton Searcher 配置，已恢复默认设置", exception);
            data = new Data();
            revision++;
        }
    }

    public static synchronized void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(data, writer);
            }
        } catch (IOException exception) {
            LOGGER.error("无法保存 Skeleton Searcher 配置", exception);
        }
    }

    public static synchronized Snapshot snapshot() {
        List<SphereRegion> copies = data.spheres.stream().map(SphereRegion::copy).toList();
        boolean throughWalls = data.throughWalls == null || data.throughWalls;
        return new Snapshot(copies, data.opacity, data.renderDistance, data.renderStyle, throughWalls, revision);
    }

    /**
     * 每帧渲染使用的轻量状态，不复制球体列表，避免持续创建临时对象。
     */
    public static synchronized RenderSettings renderSettings() {
        boolean throughWalls = data.throughWalls == null || data.throughWalls;
        boolean hasBase = false;
        if (data.spheres.size() >= 3) {
            for (SphereRegion sphere : data.spheres) {
                if (!sphere.isExclusion()) {
                    hasBase = true;
                    break;
                }
            }
        }
        boolean shouldRender = data.spheres.size() >= 3 && hasBase;
        return new RenderSettings(data.opacity, throughWalls, shouldRender);
    }

    public static synchronized AddResult tryAddSphere(SphereRegion sphere) {
        if (sphere == null || !sphere.isValid()) {
            return AddResult.INVALID;
        }
        if (data.spheres.size() >= MAX_SPHERES) {
            return AddResult.LIMIT_REACHED;
        }

        if (sphere.isExclusion()) {
            boolean hasPreviousInDimension = data.spheres.stream()
                    .anyMatch(previous -> !previous.isExclusion()
                            && previous.dimension.equals(sphere.dimension));
            if (!hasPreviousInDimension) {
                return AddResult.REQUIRES_BASE;
            }
            // “>250”是差集约束：允许与之前结果完全不相交，也允许把结果减为空。
        } else {
            List<SphereRegion> candidate = new ArrayList<>(data.spheres);
            candidate.add(sphere);
            if (SphereMath.findResultWitness(candidate).isEmpty()) {
                return AddResult.OUT_OF_REGION;
            }
        }

        data.spheres.add(sphere.copy());
        revision++;
        save();
        return AddResult.ADDED;
    }

    public static synchronized boolean removeSphere(String id) {
        boolean removed = data.spheres.removeIf(sphere -> sphere.id.equals(id));
        if (removed) {
            revision++;
            save();
        }
        return removed;
    }

    public static synchronized int clearAll() {
        int count = data.spheres.size();
        if (count > 0) {
            data.spheres.clear();
            revision++;
            save();
        }
        return count;
    }

    public static synchronized void setOpacity(float opacity) {
        float next = Math.clamp(opacity, 0.03f, 0.65f);
        if (Math.abs(next - data.opacity) > 0.0001f) {
            data.opacity = next;
            // 透明度通过每帧颜色调制统一传给 GPU，不触发几何重建或顶点重传。
        }
    }

    public static synchronized void setRenderDistance(int renderDistance) {
        int next = Math.clamp(renderDistance, 24, 300);
        if (next != data.renderDistance) {
            data.renderDistance = next;
            revision++;
        }
    }

    public static synchronized void setThroughWalls(boolean enabled) {
        boolean current = data.throughWalls == null || data.throughWalls;
        if (current != enabled) {
            data.throughWalls = enabled;
            // 透视开关只切换渲染管线，不需要重新计算或重新上传网格。
            save();
        }
    }

    public static synchronized void setRenderStyle(RenderStyle renderStyle) {
        RenderStyle next = renderStyle == null ? RenderStyle.SHELL : renderStyle;
        if (next != data.renderStyle) {
            data.renderStyle = next;
            revision++;
            save();
        }
    }

    private static Data sanitize(Data loaded) {
        Data cleaned = new Data();
        if (loaded == null) {
            return cleaned;
        }

        cleaned.opacity = Math.clamp(loaded.opacity, 0.03f, 0.65f);
        cleaned.renderDistance = Math.clamp(loaded.renderDistance, 24, 300);
        cleaned.renderStyle = loaded.renderStyle == null ? RenderStyle.SHELL : loaded.renderStyle;
        cleaned.throughWalls = loaded.throughWalls == null ? Boolean.TRUE : loaded.throughWalls;
        cleaned.spheres.clear();

        if (loaded.spheres != null) {
            List<SphereRegion> validPrefix = new ArrayList<>();
            for (SphereRegion sphere : loaded.spheres) {
                if (sphere == null || !sphere.isValid() || validPrefix.size() >= MAX_SPHERES) {
                    continue;
                }
                if (sphere.id == null || sphere.id.isBlank()) {
                    sphere.id = UUID.randomUUID().toString();
                }
                if (sphere.isExclusion()) {
                    boolean hasPreviousInDimension = validPrefix.stream()
                            .anyMatch(previous -> !previous.isExclusion()
                            && previous.dimension.equals(sphere.dimension));
                    if (hasPreviousInDimension) {
                        validPrefix.add(sphere.copy());
                    }
                } else {
                    List<SphereRegion> candidate = new ArrayList<>(validPrefix);
                    candidate.add(sphere);
                    if (SphereMath.findResultWitness(candidate).isPresent()) {
                        validPrefix.add(sphere.copy());
                    }
                }
            }
            cleaned.spheres.addAll(validPrefix);
        }
        return cleaned;
    }

    public enum AddResult {
        ADDED,
        REQUIRES_BASE,
        OUT_OF_REGION,
        LIMIT_REACHED,
        INVALID
    }

    public record RenderSettings(float opacity, boolean throughWalls, boolean shouldRender) {
    }

    public record Snapshot(
            List<SphereRegion> spheres,
            float opacity,
            int renderDistance,
            RenderStyle renderStyle,
            boolean throughWalls,
            int revision
    ) {
        public boolean shouldRender() {
            return spheres.size() >= 3 && spheres.stream().anyMatch(sphere -> !sphere.isExclusion());
        }
    }

    private static final class Data {
        float opacity = 0.16f;
        int renderDistance = 96;
        RenderStyle renderStyle = RenderStyle.SHELL;
        Boolean throughWalls = Boolean.TRUE;
        List<SphereRegion> spheres = new ArrayList<>();
    }
}
