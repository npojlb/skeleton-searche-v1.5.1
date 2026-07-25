package dev.yym.skeletonsearcher;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 高性能世界高亮渲染器。
 *
 * <p>与旧版相比，本实现把区域拆成 16×16×16 区段，在后台线程生成网格，
 * 只保留暴露面，并按距离使用 1/2/4/8 格 LOD。生成后的顶点只在结果发生变化时
 * 上传到 GPU；正常帧只提交已有缓冲区，因此不再每帧重建数百万顶点。</p>
 */
public final class SolidBlockRenderer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("skeleton_searcher/renderer");
    private static final int SECTION_SIZE = 16;
    private static final int MAX_VISIBLE_FACES = 300_000;
    private static final float INSET = 0.018f;

    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

    /** 普通深度测试：被墙体遮挡。 */
    private static final RenderPipeline DEPTH_TESTED_PIPELINE = RenderPipelines.DEBUG_FILLED_BOX;

    /** 关闭深度测试：高亮可以隔着实体方块显示。 */
    private static final RenderPipeline THROUGH_WALLS_PIPELINE = createThroughWallsPipeline();

    /**
     * 1.21.11 中 RenderPipelines#register 为私有方法，且没有公开的
     * DEBUG_FILLED_SNIPPET。这里直接复制 DEBUG_FILLED_BOX 的公开管线参数，
     * 仅覆盖深度测试、深度写入和面剔除设置。该管线复用原版已有着色器，
     * 因此无需加入 RenderPipelines 的私有注册表。
     */
    private static RenderPipeline createThroughWallsPipeline() {
        RenderPipeline base = DEPTH_TESTED_PIPELINE;
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(Identifier.of(SkeletonSearcherClient.MOD_ID,
                        "pipeline/skeleton_searcher_through_walls"))
                .withVertexShader(base.getVertexShader())
                .withFragmentShader(base.getFragmentShader())
                .withVertexFormat(base.getVertexFormat(), base.getVertexFormatMode())
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false)
                .withCull(false)
                .withColorWrite(base.isWriteColor(), base.isWriteAlpha())
                .withPolygonMode(base.getPolygonMode())
                .withDepthBias(base.getDepthBiasScaleFactor(), base.getDepthBiasConstant());

        base.getBlendFunction().ifPresent(builder::withBlend);
        for (String sampler : base.getSamplers()) {
            builder.withSampler(sampler);
        }
        for (RenderPipeline.UniformDescription uniform : base.getUniforms()) {
            if (uniform.textureFormat() == null) {
                builder.withUniform(uniform.name(), uniform.type());
            } else {
                builder.withUniform(uniform.name(), uniform.type(), uniform.textureFormat());
            }
        }
        for (String flag : base.getShaderDefines().flags()) {
            builder.withShaderDefine(flag);
        }
        base.getShaderDefines().values().forEach((name, value) -> {
            try {
                builder.withShaderDefine(name, Integer.parseInt(value));
            } catch (NumberFormatException integerFailure) {
                try {
                    builder.withShaderDefine(name, Float.parseFloat(value));
                } catch (NumberFormatException floatFailure) {
                    LOGGER.warn("无法复制渲染管线着色器定义 {}={}", name, value);
                }
            }
        });
        return builder.build();
    }

    private final ExecutorService executor;
    private final AtomicBoolean building = new AtomicBoolean(false);
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicInteger serialSource = new AtomicInteger();

    private volatile MeshCache cache = MeshCache.empty();
    private volatile RequestKey lastSubmitted;
    private int tickCounter;

    // 下列对象只在渲染线程访问。
    private GpuBuffer uploadedVertices;
    private int uploadedSerial = -1;
    private int uploadedIndexCount;
    private int uploadedOriginX;
    private int uploadedOriginY;
    private int uploadedOriginZ;

    public SolidBlockRenderer() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "skeleton-searcher-mesh-builder");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        };
        executor = Executors.newSingleThreadExecutor(factory);
    }

    public void tick(MinecraftClient client) {
        if (++tickCounter % 10 != 0 || client.player == null || client.world == null) {
            return;
        }
        requestRebuild(client);
    }

    public void markDirty() {
        generation.incrementAndGet();
        lastSubmitted = null;
    }

    public void clearCache() {
        generation.incrementAndGet();
        cache = MeshCache.empty();
        lastSubmitted = null;
        uploadedSerial = -1;
        uploadedIndexCount = 0;
        if (uploadedVertices != null && !uploadedVertices.isClosed()) {
            uploadedVertices.close();
        }
        uploadedVertices = null;
    }

    public void requestRebuild(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            return;
        }

        ModConfig.Snapshot snapshot = ModConfig.snapshot();
        if (!snapshot.shouldRender()) {
            MeshCache current = cache;
            if (!current.sections.isEmpty() || uploadedVertices != null) {
                clearCache();
            }
            return;
        }

        BlockPos playerPos = client.player.getBlockPos();
        String dimension = client.world.getRegistryKey().getValue().toString();
        int anchorX = Math.floorDiv(playerPos.getX(), SECTION_SIZE) * SECTION_SIZE;
        int anchorY = Math.floorDiv(playerPos.getY(), SECTION_SIZE) * SECTION_SIZE;
        int anchorZ = Math.floorDiv(playerPos.getZ(), SECTION_SIZE) * SECTION_SIZE;

        RequestKey desired = new RequestKey(
                dimension,
                anchorX,
                anchorY,
                anchorZ,
                snapshot.renderDistance(),
                snapshot.renderStyle(),
                snapshot.revision());

        MeshCache current = cache;
        if (desired.equals(current.key) || desired.equals(lastSubmitted)
                || !building.compareAndSet(false, true)) {
            return;
        }

        int requestGeneration = generation.get();
        lastSubmitted = desired;
        executor.execute(() -> {
            try {
                MeshCache built = build(desired, snapshot, current);
                if (generation.get() == requestGeneration) {
                    cache = built;
                } else {
                    lastSubmitted = null;
                }
            } catch (RuntimeException exception) {
                LOGGER.error("构建 Skeleton Searcher 区段网格时发生错误", exception);
                lastSubmitted = null;
            } finally {
                building.set(false);
            }
        });
    }

    public void render(WorldRenderContext context) {
        MeshCache current = cache;
        if (current.sections.isEmpty() || current.totalVertices == 0) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return;
        }
        String dimension = client.world.getRegistryKey().getValue().toString();
        if (!Objects.equals(dimension, current.key.dimension)) {
            return;
        }

        ModConfig.RenderSettings settings = ModConfig.renderSettings();
        if (!settings.shouldRender()) {
            return;
        }

        if (uploadedSerial != current.serial) {
            upload(current);
        }
        if (uploadedVertices == null || uploadedIndexCount == 0) {
            return;
        }

        RenderPipeline pipeline = settings.throughWalls()
                ? THROUGH_WALLS_PIPELINE
                : DEPTH_TESTED_PIPELINE;
        draw(client, pipeline, settings.opacity());
    }

    /** 仅在网格版本变化时调用一次。 */
    private void upload(MeshCache mesh) {
        RenderPipeline formatSource = DEPTH_TESTED_PIPELINE;
        int estimatedBytes = Math.max(1024,
                mesh.totalVertices * formatSource.getVertexFormat().getVertexSize());

        try (BufferAllocator allocator = new BufferAllocator(estimatedBytes)) {
            BufferBuilder builder = new BufferBuilder(
                    allocator,
                    formatSource.getVertexFormatMode(),
                    formatSource.getVertexFormat());

            for (SectionMesh section : mesh.sections) {
                float[] values = section.vertices;
                for (int i = 0; i < values.length; i += 3) {
                    builder.vertex(
                                    values[i] - mesh.key.anchorX,
                                    values[i + 1] - mesh.key.anchorY,
                                    values[i + 2] - mesh.key.anchorZ)
                            .color(255, 255, 255, 255);
                }
            }

            try (BuiltBuffer built = builder.end()) {
                BuiltBuffer.DrawParameters parameters = built.getDrawParameters();
                int requiredBytes = parameters.vertexCount() * parameters.format().getVertexSize();
                if (requiredBytes <= 0) {
                    if (uploadedVertices != null && !uploadedVertices.isClosed()) {
                        uploadedVertices.close();
                    }
                    uploadedVertices = null;
                    uploadedIndexCount = 0;
                    uploadedSerial = mesh.serial;
                    return;
                }

                if (uploadedVertices == null || uploadedVertices.isClosed()
                        || uploadedVertices.size() < requiredBytes) {
                    if (uploadedVertices != null && !uploadedVertices.isClosed()) {
                        uploadedVertices.close();
                    }
                    uploadedVertices = RenderSystem.getDevice().createBuffer(
                            () -> "Skeleton Searcher persistent mesh",
                            GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                            requiredBytes);
                }

                CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
                encoder.writeToBuffer(
                        uploadedVertices.slice(0, built.getBuffer().remaining()),
                        built.getBuffer().duplicate());

                uploadedIndexCount = parameters.indexCount();
                uploadedOriginX = mesh.key.anchorX;
                uploadedOriginY = mesh.key.anchorY;
                uploadedOriginZ = mesh.key.anchorZ;
                uploadedSerial = mesh.serial;
            }
        }
    }

    private void draw(MinecraftClient client, RenderPipeline pipeline, float opacity) {
        Framebuffer framebuffer = client.getFramebuffer();
        if (framebuffer.getColorAttachmentView() == null || framebuffer.getDepthAttachmentView() == null) {
            return;
        }

        Vec3d camera = client.gameRenderer.getCamera().getCameraPos();
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix())
                .translate(
                        (float) (uploadedOriginX - camera.x),
                        (float) (uploadedOriginY - camera.y),
                        (float) (uploadedOriginZ - camera.z));
        COLOR_MODULATOR.set(1f, 1f, 1f, Math.clamp(opacity, 0.03f, 0.65f));
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .write(modelView, COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);

        RenderSystem.ShapeIndexBuffer indexBuffer = RenderSystem.getSequentialBuffer(
                pipeline.getVertexFormatMode());
        GpuBuffer indices = indexBuffer.getIndexBuffer(uploadedIndexCount);
        VertexFormat.IndexType indexType = indexBuffer.getIndexType();

        try (RenderPass pass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(
                        () -> "Skeleton Searcher cached highlight",
                        framebuffer.getColorAttachmentView(),
                        OptionalInt.empty(),
                        framebuffer.getDepthAttachmentView(),
                        OptionalDouble.empty())) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", dynamicTransforms);
            pass.setVertexBuffer(0, uploadedVertices);
            pass.setIndexBuffer(indices, indexType);
            pass.drawIndexed(0, 0, uploadedIndexCount, 1);
        }
    }

    private MeshCache build(RequestKey key, ModConfig.Snapshot snapshot, MeshCache previous) {
        List<SphereRegion> source = snapshot.spheres().stream()
                .filter(sphere -> key.dimension.equals(sphere.dimension))
                .toList();
        if (source.size() < 3) {
            return new MeshCache(key, List.of(), Map.of(), 0, 0,
                    serialSource.incrementAndGet(), false, 0);
        }

        CompiledRegion region = CompiledRegion.compile(source);
        SphereMath.Bounds sphereBounds = SphereMath.outerIntersectionBounds(source);
        if (sphereBounds == null) {
            return new MeshCache(key, List.of(), Map.of(), 0, 0,
                    serialSource.incrementAndGet(), false, 0);
        }

        int radius = key.renderDistance;
        SphereMath.Bounds local = new SphereMath.Bounds(
                key.anchorX - radius,
                key.anchorY - radius,
                key.anchorZ - radius,
                key.anchorX + radius,
                key.anchorY + radius,
                key.anchorZ + radius);
        SphereMath.Bounds scan = local.intersect(sphereBounds);
        if (scan == null) {
            return new MeshCache(key, List.of(), Map.of(), 0, 0,
                    serialSource.incrementAndGet(), false, 0);
        }

        Map<SectionKey, SectionMesh> reusable = canReuse(previous, key)
                ? previous.byKey
                : Map.of();
        Map<SectionKey, SectionMesh> nextByKey = new LinkedHashMap<>();
        List<SectionMesh> sections = new ArrayList<>();

        int minSectionX = Math.floorDiv(scan.minX(), SECTION_SIZE);
        int minSectionY = Math.floorDiv(scan.minY(), SECTION_SIZE);
        int minSectionZ = Math.floorDiv(scan.minZ(), SECTION_SIZE);
        int maxSectionX = Math.floorDiv(scan.maxX(), SECTION_SIZE);
        int maxSectionY = Math.floorDiv(scan.maxY(), SECTION_SIZE);
        int maxSectionZ = Math.floorDiv(scan.maxZ(), SECTION_SIZE);

        int faceCount = 0;
        int reusedCount = 0;
        boolean truncated = false;

        sectionLoop:
        for (int sx = minSectionX; sx <= maxSectionX; sx++) {
            for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                for (int sz = minSectionZ; sz <= maxSectionZ; sz++) {
                    int step = lodStep(sx, sy, sz, key);
                    SectionKey sectionKey = new SectionKey(sx, sy, sz, step);
                    SectionMesh section = reusable.get(sectionKey);
                    if (section == null) {
                        int boxMinX = Math.max(sx * SECTION_SIZE, scan.minX());
                        int boxMinY = Math.max(sy * SECTION_SIZE, scan.minY());
                        int boxMinZ = Math.max(sz * SECTION_SIZE, scan.minZ());
                        int boxMaxX = Math.min(sx * SECTION_SIZE + SECTION_SIZE - 1, scan.maxX());
                        int boxMaxY = Math.min(sy * SECTION_SIZE + SECTION_SIZE - 1, scan.maxY());
                        int boxMaxZ = Math.min(sz * SECTION_SIZE + SECTION_SIZE - 1, scan.maxZ());
                        if (!region.canContainBox(boxMinX, boxMinY, boxMinZ,
                                boxMaxX, boxMaxY, boxMaxZ)) {
                            continue;
                        }
                        section = buildSection(sectionKey, scan, region, key.renderStyle);
                    } else {
                        reusedCount++;
                    }
                    if (section.faceCount == 0) {
                        continue;
                    }

                    if (faceCount + section.faceCount > MAX_VISIBLE_FACES) {
                        int remaining = MAX_VISIBLE_FACES - faceCount;
                        if (remaining > 0) {
                            section = section.limitFaces(remaining);
                            sections.add(section);
                            nextByKey.put(sectionKey, section);
                            faceCount += section.faceCount;
                        }
                        truncated = true;
                        break sectionLoop;
                    }

                    sections.add(section);
                    nextByKey.put(sectionKey, section);
                    faceCount += section.faceCount;
                }
            }
        }

        int totalVertices = faceCount * 4;
        if (truncated) {
            LOGGER.warn("高亮暴露面超过 {} 个，本次已截断。可降低渲染距离。", MAX_VISIBLE_FACES);
        }
        LOGGER.debug("网格更新：{} 个区段，复用 {} 个，{} 个暴露面，{} 个顶点。",
                sections.size(), reusedCount, faceCount, totalVertices);
        return new MeshCache(
                key,
                List.copyOf(sections),
                Map.copyOf(nextByKey),
                faceCount,
                totalVertices,
                serialSource.incrementAndGet(),
                truncated,
                reusedCount);
    }

    private static boolean canReuse(MeshCache previous, RequestKey key) {
        return previous != null
                && previous.key.revision == key.revision
                && previous.key.renderStyle == key.renderStyle
                && previous.key.renderDistance == key.renderDistance
                && Objects.equals(previous.key.dimension, key.dimension);
    }

    private static int lodStep(int sx, int sy, int sz, RequestKey key) {
        double centerX = sx * SECTION_SIZE + SECTION_SIZE * 0.5;
        double centerY = sy * SECTION_SIZE + SECTION_SIZE * 0.5;
        double centerZ = sz * SECTION_SIZE + SECTION_SIZE * 0.5;
        double dx = centerX - key.anchorX;
        double dy = centerY - key.anchorY;
        double dz = centerZ - key.anchorZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance <= 80.0) return 1;
        if (distance <= 160.0) return 2;
        if (distance <= 240.0) return 4;
        return 8;
    }

    private static SectionMesh buildSection(
            SectionKey key,
            SphereMath.Bounds scan,
            CompiledRegion region,
            RenderStyle style
    ) {
        int sectionMinX = key.sx * SECTION_SIZE;
        int sectionMinY = key.sy * SECTION_SIZE;
        int sectionMinZ = key.sz * SECTION_SIZE;
        int minX = Math.max(sectionMinX, scan.minX());
        int minY = Math.max(sectionMinY, scan.minY());
        int minZ = Math.max(sectionMinZ, scan.minZ());
        int maxX = Math.min(sectionMinX + SECTION_SIZE - 1, scan.maxX());
        int maxY = Math.min(sectionMinY + SECTION_SIZE - 1, scan.maxY());
        int maxZ = Math.min(sectionMinZ + SECTION_SIZE - 1, scan.maxZ());
        int step = key.step;

        FloatBuilder vertices = new FloatBuilder(1024);
        int faces = 0;

        for (int x = alignUp(minX, sectionMinX, step); x <= maxX; x += step) {
            for (int y = alignUp(minY, sectionMinY, step); y <= maxY; y += step) {
                for (int z = alignUp(minZ, sectionMinZ, step); z <= maxZ; z += step) {
                    int testX = x + (step - 1) / 2;
                    int testY = y + (step - 1) / 2;
                    int testZ = z + (step - 1) / 2;
                    if (!region.contains(testX, testY, testZ)) {
                        continue;
                    }

                    boolean negX = !region.contains(testX - step, testY, testZ);
                    boolean posX = !region.contains(testX + step, testY, testZ);
                    boolean negY = !region.contains(testX, testY - step, testZ);
                    boolean posY = !region.contains(testX, testY + step, testZ);
                    boolean negZ = !region.contains(testX, testY, testZ - step);
                    boolean posZ = !region.contains(testX, testY, testZ + step);
                    boolean boundary = negX || posX || negY || posY || negZ || posZ;
                    boolean interiorMarker = style == RenderStyle.SOLID_BLOCKS
                            && !boundary
                            && isInteriorMarker(x, y, z, step);
                    if (!boundary && !interiorMarker) {
                        continue;
                    }

                    // 球壳模式只绘制最终结果的暴露面；半透明方块模式还会在内部
                    // 以稀疏规则保留少量完整方块，从而体现体积而不恢复海量内部面。
                    if (interiorMarker) {
                        negX = posX = negY = posY = negZ = posZ = true;
                    }
                    float x0 = x + INSET;
                    float y0 = y + INSET;
                    float z0 = z + INSET;
                    float x1 = Math.min(x + step, maxX + 1) - INSET;
                    float y1 = Math.min(y + step, maxY + 1) - INSET;
                    float z1 = Math.min(z + step, maxZ + 1) - INSET;

                    if (negY) { addBottom(vertices, x0, y0, z0, x1, z1); faces++; }
                    if (posY) { addTop(vertices, x0, y1, z0, x1, z1); faces++; }
                    if (negZ) { addNorth(vertices, x0, y0, z0, x1, y1); faces++; }
                    if (posZ) { addSouth(vertices, x0, y0, z1, x1, y1); faces++; }
                    if (negX) { addWest(vertices, x0, y0, z0, y1, z1); faces++; }
                    if (posX) { addEast(vertices, x1, y0, z0, y1, z1); faces++; }
                }
            }
        }

        return new SectionMesh(key, vertices.toArray(), faces);
    }


    private static boolean isInteriorMarker(int x, int y, int z, int step) {
        int spacing = Math.max(4, step * 4);
        return Math.floorMod(x, spacing) == 0
                && Math.floorMod(y, spacing) == 0
                && Math.floorMod(z, spacing) == 0;
    }

    private static int alignUp(int value, int origin, int step) {
        int offset = Math.floorMod(value - origin, step);
        return offset == 0 ? value : value + step - offset;
    }

    private static void addBottom(FloatBuilder b, float x0, float y, float z0, float x1, float z1) {
        quad(b, x0, y, z1, x1, y, z1, x1, y, z0, x0, y, z0);
    }

    private static void addTop(FloatBuilder b, float x0, float y, float z0, float x1, float z1) {
        quad(b, x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z1);
    }

    private static void addNorth(FloatBuilder b, float x0, float y0, float z, float x1, float y1) {
        quad(b, x1, y0, z, x1, y1, z, x0, y1, z, x0, y0, z);
    }

    private static void addSouth(FloatBuilder b, float x0, float y0, float z, float x1, float y1) {
        quad(b, x0, y0, z, x0, y1, z, x1, y1, z, x1, y0, z);
    }

    private static void addWest(FloatBuilder b, float x, float y0, float z0, float y1, float z1) {
        quad(b, x, y0, z0, x, y1, z0, x, y1, z1, x, y0, z1);
    }

    private static void addEast(FloatBuilder b, float x, float y0, float z0, float y1, float z1) {
        quad(b, x, y0, z1, x, y1, z1, x, y1, z0, x, y0, z0);
    }

    private static void quad(
            FloatBuilder b,
            float x1, float y1, float z1,
            float x2, float y2, float z2,
            float x3, float y3, float z3,
            float x4, float y4, float z4
    ) {
        b.add(x1, y1, z1);
        b.add(x2, y2, z2);
        b.add(x3, y3, z3);
        b.add(x4, y4, z4);
    }

    @Override
    public void close() {
        executor.shutdownNow();
        if (uploadedVertices != null && !uploadedVertices.isClosed()) {
            uploadedVertices.close();
        }
        uploadedVertices = null;
    }

    private record RequestKey(
            String dimension,
            int anchorX,
            int anchorY,
            int anchorZ,
            int renderDistance,
            RenderStyle renderStyle,
            int revision
    ) {
    }

    private record SectionKey(int sx, int sy, int sz, int step) {
    }

    private record SectionMesh(SectionKey key, float[] vertices, int faceCount) {
        SectionMesh limitFaces(int maxFaces) {
            int keepFaces = Math.clamp(maxFaces, 0, faceCount);
            int floatCount = keepFaces * 4 * 3;
            float[] limited = new float[floatCount];
            System.arraycopy(vertices, 0, limited, 0, floatCount);
            return new SectionMesh(key, limited, keepFaces);
        }
    }

    private record MeshCache(
            RequestKey key,
            List<SectionMesh> sections,
            Map<SectionKey, SectionMesh> byKey,
            int faceCount,
            int totalVertices,
            int serial,
            boolean truncated,
            int reusedSections
    ) {
        static MeshCache empty() {
            RequestKey key = new RequestKey("", 0, 0, 0, 0, RenderStyle.SHELL, -1);
            return new MeshCache(key, List.of(), Map.of(), 0, 0, -1, false, 0);
        }
    }

    private record CompiledSphere(int x, int y, int z, long innerSquared, long outerSquared,
                                  boolean exclusion) {
    }

    private record CompiledRegion(CompiledSphere[] spheres) {
        static CompiledRegion compile(List<SphereRegion> source) {
            CompiledSphere[] result = new CompiledSphere[source.size()];
            for (int i = 0; i < source.size(); i++) {
                SphereRegion sphere = source.get(i);
                result[i] = new CompiledSphere(
                        sphere.x,
                        sphere.y,
                        sphere.z,
                        (long) sphere.innerRadius * sphere.innerRadius,
                        (long) sphere.outerRadius * sphere.outerRadius,
                        sphere.isExclusion());
            }
            return new CompiledRegion(result);
        }

        boolean contains(int x, int y, int z) {
            boolean hasBase = false;
            for (CompiledSphere sphere : spheres) {
                long dx = (long) x - sphere.x;
                long dy = (long) y - sphere.y;
                long dz = (long) z - sphere.z;
                long distanceSquared = dx * dx + dy * dy + dz * dz;
                if (sphere.exclusion) {
                    if (distanceSquared <= sphere.outerSquared) {
                        return false;
                    }
                } else {
                    hasBase = true;
                    if (distanceSquared < sphere.innerSquared || distanceSquared > sphere.outerSquared) {
                        return false;
                    }
                }
            }
            return hasBase;
        }

        boolean canContainBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            boolean hasBase = false;
            for (CompiledSphere sphere : spheres) {
                long minDistance = minDistanceSquared(
                        sphere.x, sphere.y, sphere.z,
                        minX, minY, minZ, maxX, maxY, maxZ);
                long maxDistance = maxDistanceSquared(
                        sphere.x, sphere.y, sphere.z,
                        minX, minY, minZ, maxX, maxY, maxZ);
                if (sphere.exclusion) {
                    // 整个区段都位于排除球内部时可直接跳过。
                    if (maxDistance <= sphere.outerSquared) {
                        return false;
                    }
                } else {
                    hasBase = true;
                    // AABB 与该球带完全分离时，无需逐点扫描。
                    if (minDistance > sphere.outerSquared || maxDistance < sphere.innerSquared) {
                        return false;
                    }
                }
            }
            return hasBase;
        }

        private static long minDistanceSquared(
                int cx, int cy, int cz,
                int minX, int minY, int minZ,
                int maxX, int maxY, int maxZ
        ) {
            long dx = axisMinDistance(cx, minX, maxX);
            long dy = axisMinDistance(cy, minY, maxY);
            long dz = axisMinDistance(cz, minZ, maxZ);
            return dx * dx + dy * dy + dz * dz;
        }

        private static long maxDistanceSquared(
                int cx, int cy, int cz,
                int minX, int minY, int minZ,
                int maxX, int maxY, int maxZ
        ) {
            long dx = Math.max(Math.abs((long) minX - cx), Math.abs((long) maxX - cx));
            long dy = Math.max(Math.abs((long) minY - cy), Math.abs((long) maxY - cy));
            long dz = Math.max(Math.abs((long) minZ - cz), Math.abs((long) maxZ - cz));
            return dx * dx + dy * dy + dz * dz;
        }

        private static long axisMinDistance(int center, int min, int max) {
            if (center < min) return (long) min - center;
            if (center > max) return (long) center - max;
            return 0L;
        }
    }

    private static final class FloatBuilder {
        private float[] values;
        private int size;

        FloatBuilder(int initialCapacity) {
            values = new float[Math.max(12, initialCapacity)];
        }

        void add(float x, float y, float z) {
            ensure(3);
            values[size++] = x;
            values[size++] = y;
            values[size++] = z;
        }

        private void ensure(int extra) {
            if (size + extra <= values.length) {
                return;
            }
            int next = Math.max(values.length * 2, size + extra);
            float[] expanded = new float[next];
            System.arraycopy(values, 0, expanded, 0, size);
            values = expanded;
        }

        float[] toArray() {
            float[] result = new float[size];
            System.arraycopy(values, 0, result, 0, size);
            return result;
        }
    }
}
