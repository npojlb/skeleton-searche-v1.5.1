package dev.yym.skeletonsearcher;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.SplittableRandom;

public final class SphereMath {
    private static final int BEST_COUNT = 96;

    private SphereMath() {
    }

    /**
     * 判断一个坐标是否满足单个约束。普通档位要求坐标落在球壳内；“>250”
     * 要求坐标严格位于半径 250 的实心球之外。
     */
    public static boolean contains(SphereRegion sphere, int x, int y, int z) {
        long dx = (long) x - sphere.x;
        long dy = (long) y - sphere.y;
        long dz = (long) z - sphere.z;
        long distanceSquared = dx * dx + dy * dy + dz * dz;
        long innerSquared = (long) sphere.innerRadius * sphere.innerRadius;
        long outerSquared = (long) sphere.outerRadius * sphere.outerRadius;
        if (sphere.isExclusion()) {
            return distanceSquared > outerSquared;
        }
        return distanceSquared >= innerSquared && distanceSquared <= outerSquared;
    }

    /**
     * 最终结果 = 所有普通球壳的共同交集 − 所有“>250”实心球的并集。
     */
    public static boolean containsResult(List<SphereRegion> spheres, int x, int y, int z) {
        if (spheres.isEmpty()) {
            return false;
        }
        boolean hasBaseRegion = false;
        for (SphereRegion sphere : spheres) {
            if (!sphere.isExclusion()) {
                hasBaseRegion = true;
            }
            if (!contains(sphere, x, y, z)) {
                return false;
            }
        }
        return hasBaseRegion;
    }

    /**
     * 在方块中心点的整数网格上寻找所有空心球体的共同点。
     * 球壳最薄也有 25 格，因此先粗采样、再逐级细化能兼顾速度与可靠性。
     */
    public static Optional<IntPoint> findIntersectionWitness(List<SphereRegion> spheres) {
        if (spheres.isEmpty()) {
            return Optional.empty();
        }
        boolean hasBaseRegion = spheres.stream().anyMatch(sphere -> !sphere.isExclusion());
        if (!hasBaseRegion) {
            return Optional.empty();
        }
        if (spheres.size() == 1) {
            SphereRegion s = spheres.getFirst();
            return Optional.of(new IntPoint(s.x + s.innerRadius, s.y, s.z));
        }

        String dimension = spheres.getFirst().dimension;
        for (SphereRegion sphere : spheres) {
            if (!dimension.equals(sphere.dimension)) {
                return Optional.empty();
            }
        }

        Bounds bounds = outerIntersectionBounds(spheres);
        if (bounds == null) {
            return Optional.empty();
        }

        PriorityQueue<ScoredPoint> best = new PriorityQueue<>(
                Comparator.comparingDouble(ScoredPoint::score).reversed());
        Set<IntPoint> seeded = new HashSet<>();

        List<IntPoint> seeds = createSeeds(spheres, bounds);
        for (IntPoint point : seeds) {
            IntPoint clamped = bounds.clamp(point);
            if (seeded.add(clamped)) {
                if (insideAll(spheres, clamped)) {
                    return Optional.of(clamped);
                }
                offer(best, new ScoredPoint(clamped, violationScore(spheres, clamped)));
            }
        }

        int maxSpan = Math.max(bounds.maxX - bounds.minX,
                Math.max(bounds.maxY - bounds.minY, bounds.maxZ - bounds.minZ));
        int step = Math.max(4, (int) Math.ceil((maxSpan + 1) / 64.0));

        for (int x = bounds.minX; x <= bounds.maxX; x += step) {
            for (int y = bounds.minY; y <= bounds.maxY; y += step) {
                for (int z = bounds.minZ; z <= bounds.maxZ; z += step) {
                    IntPoint point = new IntPoint(x, y, z);
                    if (insideAll(spheres, point)) {
                        return Optional.of(point);
                    }
                    offer(best, new ScoredPoint(point, violationScore(spheres, point)));
                }
            }
        }

        int refineStep = Math.max(1, step / 2);
        while (true) {
            List<ScoredPoint> current = new ArrayList<>(best);
            PriorityQueue<ScoredPoint> refined = new PriorityQueue<>(
                    Comparator.comparingDouble(ScoredPoint::score).reversed());
            Set<IntPoint> visited = new HashSet<>();

            for (ScoredPoint candidate : current) {
                IntPoint p = candidate.point;
                for (int ox = -2; ox <= 2; ox++) {
                    for (int oy = -2; oy <= 2; oy++) {
                        for (int oz = -2; oz <= 2; oz++) {
                            IntPoint next = bounds.clamp(new IntPoint(
                                    p.x + ox * refineStep,
                                    p.y + oy * refineStep,
                                    p.z + oz * refineStep));
                            if (!visited.add(next)) {
                                continue;
                            }
                            if (insideAll(spheres, next)) {
                                return Optional.of(next);
                            }
                            offer(refined, new ScoredPoint(next, violationScore(spheres, next)));
                        }
                    }
                }
            }
            best = refined;
            if (refineStep == 1) {
                break;
            }
            refineStep = Math.max(1, refineStep / 2);
        }

        long seed = 0x6A09E667F3BCC909L;
        for (SphereRegion sphere : spheres) {
            seed = seed * 31 + sphere.x;
            seed = seed * 31 + sphere.y;
            seed = seed * 31 + sphere.z;
            seed = seed * 31 + sphere.innerRadius;
            seed = seed * 31 + sphere.outerRadius;
        }
        SplittableRandom random = new SplittableRandom(seed);
        int randomSamples = 50_000;
        for (int i = 0; i < randomSamples; i++) {
            IntPoint point = new IntPoint(
                    random.nextInt(bounds.minX, bounds.maxX + 1),
                    random.nextInt(bounds.minY, bounds.maxY + 1),
                    random.nextInt(bounds.minZ, bounds.maxZ + 1));
            if (insideAll(spheres, point)) {
                return Optional.of(point);
            }
        }

        // 随机与局部细化没有找到时，以整数包围盒分支剪枝做确定性兜底。
        // 该阶段会证明“确实不存在共同方块”，避免把很薄的真实交集误判为越界。
        return findIntersectionWitnessExact(spheres, bounds);
    }

    public static Optional<IntPoint> findResultWitness(List<SphereRegion> spheres) {
        return findIntersectionWitness(spheres);
    }

    private static Optional<IntPoint> findIntersectionWitnessExact(List<SphereRegion> spheres, Bounds initial) {
        ArrayDeque<Bounds> stack = new ArrayDeque<>();
        stack.push(initial);

        while (!stack.isEmpty()) {
            Bounds box = stack.pop();
            if (!boxCanContainIntersection(spheres, box)) {
                continue;
            }

            int spanX = box.maxX - box.minX;
            int spanY = box.maxY - box.minY;
            int spanZ = box.maxZ - box.minZ;
            if (spanX == 0 && spanY == 0 && spanZ == 0) {
                IntPoint point = new IntPoint(box.minX, box.minY, box.minZ);
                if (insideAll(spheres, point)) {
                    return Optional.of(point);
                }
                continue;
            }

            if (spanX >= spanY && spanX >= spanZ) {
                int middle = midpoint(box.minX, box.maxX);
                stack.push(new Bounds(middle + 1, box.minY, box.minZ,
                        box.maxX, box.maxY, box.maxZ));
                stack.push(new Bounds(box.minX, box.minY, box.minZ,
                        middle, box.maxY, box.maxZ));
            } else if (spanY >= spanZ) {
                int middle = midpoint(box.minY, box.maxY);
                stack.push(new Bounds(box.minX, middle + 1, box.minZ,
                        box.maxX, box.maxY, box.maxZ));
                stack.push(new Bounds(box.minX, box.minY, box.minZ,
                        box.maxX, middle, box.maxZ));
            } else {
                int middle = midpoint(box.minZ, box.maxZ);
                stack.push(new Bounds(box.minX, box.minY, middle + 1,
                        box.maxX, box.maxY, box.maxZ));
                stack.push(new Bounds(box.minX, box.minY, box.minZ,
                        box.maxX, box.maxY, middle));
            }
        }
        return Optional.empty();
    }

    private static boolean boxCanContainIntersection(List<SphereRegion> spheres, Bounds box) {
        for (SphereRegion sphere : spheres) {
            long minDistanceSquared = minDistanceSquared(sphere, box);
            long maxDistanceSquared = maxDistanceSquared(sphere, box);
            long innerSquared = (long) sphere.innerRadius * sphere.innerRadius;
            long outerSquared = (long) sphere.outerRadius * sphere.outerRadius;
            if (sphere.isExclusion()) {
                // 盒内所有整数点都位于排除球内（含边界）时，不可能留下“>250”结果。
                if (maxDistanceSquared <= outerSquared) {
                    return false;
                }
            } else if (minDistanceSquared > outerSquared || maxDistanceSquared < innerSquared) {
                return false;
            }
        }
        return true;
    }

    private static long minDistanceSquared(SphereRegion sphere, Bounds box) {
        long dx = axisMinDistance(sphere.x, box.minX, box.maxX);
        long dy = axisMinDistance(sphere.y, box.minY, box.maxY);
        long dz = axisMinDistance(sphere.z, box.minZ, box.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    private static long maxDistanceSquared(SphereRegion sphere, Bounds box) {
        long dx = Math.max(Math.abs((long) box.minX - sphere.x), Math.abs((long) box.maxX - sphere.x));
        long dy = Math.max(Math.abs((long) box.minY - sphere.y), Math.abs((long) box.maxY - sphere.y));
        long dz = Math.max(Math.abs((long) box.minZ - sphere.z), Math.abs((long) box.maxZ - sphere.z));
        return dx * dx + dy * dy + dz * dz;
    }

    private static long axisMinDistance(int center, int min, int max) {
        if (center < min) {
            return (long) min - center;
        }
        if (center > max) {
            return (long) center - max;
        }
        return 0L;
    }

    public static Bounds outerIntersectionBounds(List<SphereRegion> spheres) {
        int minX = Integer.MIN_VALUE;
        int minY = Integer.MIN_VALUE;
        int minZ = Integer.MIN_VALUE;
        int maxX = Integer.MAX_VALUE;
        int maxY = Integer.MAX_VALUE;
        int maxZ = Integer.MAX_VALUE;

        boolean hasBaseRegion = false;
        for (SphereRegion sphere : spheres) {
            if (sphere.isExclusion()) {
                continue;
            }
            hasBaseRegion = true;
            minX = Math.max(minX, sphere.x - sphere.outerRadius);
            minY = Math.max(minY, sphere.y - sphere.outerRadius);
            minZ = Math.max(minZ, sphere.z - sphere.outerRadius);
            maxX = Math.min(maxX, sphere.x + sphere.outerRadius);
            maxY = Math.min(maxY, sphere.y + sphere.outerRadius);
            maxZ = Math.min(maxZ, sphere.z + sphere.outerRadius);
        }

        if (!hasBaseRegion) {
            return null;
        }

        if (minX > maxX || minY > maxY || minZ > maxZ) {
            return null;
        }
        return new Bounds(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public static Bounds outerUnionBounds(List<SphereRegion> spheres) {
        if (spheres.isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        boolean hasBaseRegion = false;
        for (SphereRegion sphere : spheres) {
            if (sphere.isExclusion()) {
                continue;
            }
            hasBaseRegion = true;
            minX = Math.min(minX, sphere.x - sphere.outerRadius);
            minY = Math.min(minY, sphere.y - sphere.outerRadius);
            minZ = Math.min(minZ, sphere.z - sphere.outerRadius);
            maxX = Math.max(maxX, sphere.x + sphere.outerRadius);
            maxY = Math.max(maxY, sphere.y + sphere.outerRadius);
            maxZ = Math.max(maxZ, sphere.z + sphere.outerRadius);
        }
        return hasBaseRegion ? new Bounds(minX, minY, minZ, maxX, maxY, maxZ) : null;
    }

    private static List<IntPoint> createSeeds(List<SphereRegion> spheres, Bounds bounds) {
        List<IntPoint> seeds = new ArrayList<>();
        seeds.add(new IntPoint(
                midpoint(bounds.minX, bounds.maxX),
                midpoint(bounds.minY, bounds.maxY),
                midpoint(bounds.minZ, bounds.maxZ)));

        for (int x : new int[]{bounds.minX, bounds.maxX}) {
            for (int y : new int[]{bounds.minY, bounds.maxY}) {
                for (int z : new int[]{bounds.minZ, bounds.maxZ}) {
                    seeds.add(new IntPoint(x, y, z));
                }
            }
        }

        for (SphereRegion sphere : spheres) {
            seeds.add(new IntPoint(sphere.x, sphere.y, sphere.z));
            int middle = (sphere.innerRadius + sphere.outerRadius) / 2;
            int[] radii = sphere.isExclusion()
                    ? new int[]{sphere.outerRadius + 1}
                    : new int[]{sphere.innerRadius, middle, sphere.outerRadius};
            for (int r : radii) {
                seeds.add(new IntPoint(sphere.x + r, sphere.y, sphere.z));
                seeds.add(new IntPoint(sphere.x - r, sphere.y, sphere.z));
                seeds.add(new IntPoint(sphere.x, sphere.y + r, sphere.z));
                seeds.add(new IntPoint(sphere.x, sphere.y - r, sphere.z));
                seeds.add(new IntPoint(sphere.x, sphere.y, sphere.z + r));
                seeds.add(new IntPoint(sphere.x, sphere.y, sphere.z - r));
            }
        }

        for (int i = 0; i < spheres.size(); i++) {
            for (int j = i + 1; j < spheres.size(); j++) {
                SphereRegion a = spheres.get(i);
                SphereRegion b = spheres.get(j);
                seeds.add(new IntPoint(
                        midpoint(a.x, b.x),
                        midpoint(a.y, b.y),
                        midpoint(a.z, b.z)));
                addRaySeeds(seeds, a, b);
                addRaySeeds(seeds, b, a);
            }
        }
        return seeds;
    }

    private static void addRaySeeds(List<IntPoint> seeds, SphereRegion from, SphereRegion toward) {
        double dx = toward.x - from.x;
        double dy = toward.y - from.y;
        double dz = toward.z - from.z;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 1.0e-6) {
            return;
        }
        int middle = (from.innerRadius + from.outerRadius) / 2;
        int[] radii = from.isExclusion()
                ? new int[]{from.outerRadius + 1}
                : new int[]{from.innerRadius, middle, from.outerRadius};
        for (int radius : radii) {
            seeds.add(new IntPoint(
                    (int) Math.round(from.x + dx / length * radius),
                    (int) Math.round(from.y + dy / length * radius),
                    (int) Math.round(from.z + dz / length * radius)));
        }
    }

    private static boolean insideAll(List<SphereRegion> spheres, IntPoint point) {
        for (SphereRegion sphere : spheres) {
            if (!contains(sphere, point.x, point.y, point.z)) {
                return false;
            }
        }
        return true;
    }

    private static double violationScore(List<SphereRegion> spheres, IntPoint point) {
        double score = 0.0;
        for (SphereRegion sphere : spheres) {
            double dx = point.x - sphere.x;
            double dy = point.y - sphere.y;
            double dz = point.z - sphere.z;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double violation = 0.0;
            if (sphere.isExclusion()) {
                if (distance <= sphere.outerRadius) {
                    // 整数网格需要严格大于 250，因此把边界也视为 1 格违约。
                    violation = sphere.outerRadius + 1.0 - distance;
                }
            } else if (distance < sphere.innerRadius) {
                violation = sphere.innerRadius - distance;
            } else if (distance > sphere.outerRadius) {
                violation = distance - sphere.outerRadius;
            }
            score += violation * violation;
        }
        return score;
    }

    private static void offer(PriorityQueue<ScoredPoint> best, ScoredPoint point) {
        if (best.size() < BEST_COUNT) {
            best.offer(point);
        } else if (point.score < best.peek().score) {
            best.poll();
            best.offer(point);
        }
    }

    private static int midpoint(int a, int b) {
        // floorDiv 可正确拆分跨越负坐标的区间；普通除法会让 [-1, 0]
        // 的中点变成 0，导致精确搜索不断生成同一个区间。
        return (int) Math.floorDiv((long) a + b, 2L);
    }

    public record IntPoint(int x, int y, int z) {
    }

    private record ScoredPoint(IntPoint point, double score) {
    }

    public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public Bounds intersect(Bounds other) {
            int nextMinX = Math.max(minX, other.minX);
            int nextMinY = Math.max(minY, other.minY);
            int nextMinZ = Math.max(minZ, other.minZ);
            int nextMaxX = Math.min(maxX, other.maxX);
            int nextMaxY = Math.min(maxY, other.maxY);
            int nextMaxZ = Math.min(maxZ, other.maxZ);
            if (nextMinX > nextMaxX || nextMinY > nextMaxY || nextMinZ > nextMaxZ) {
                return null;
            }
            return new Bounds(nextMinX, nextMinY, nextMinZ, nextMaxX, nextMaxY, nextMaxZ);
        }

        public IntPoint clamp(IntPoint point) {
            return new IntPoint(
                    Math.clamp(point.x, minX, maxX),
                    Math.clamp(point.y, minY, maxY),
                    Math.clamp(point.z, minZ, maxZ));
        }
    }
}
