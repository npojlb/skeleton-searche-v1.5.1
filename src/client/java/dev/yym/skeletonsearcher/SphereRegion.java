package dev.yym.skeletonsearcher;

import java.util.Objects;
import java.util.UUID;

public final class SphereRegion {
    public String id;
    public int x;
    public int y;
    public int z;
    public int innerRadius;
    public int outerRadius;
    public String dimension;

    @SuppressWarnings("unused")
    public SphereRegion() {
        // Gson 反序列化使用。
    }

    public SphereRegion(int x, int y, int z, SphereBand band, String dimension) {
        this.id = UUID.randomUUID().toString();
        this.x = x;
        this.y = y;
        this.z = z;
        this.innerRadius = band.innerRadius();
        this.outerRadius = band.outerRadius();
        this.dimension = Objects.requireNonNull(dimension, "dimension");
    }

    public SphereRegion copy() {
        SphereRegion copy = new SphereRegion();
        copy.id = id;
        copy.x = x;
        copy.y = y;
        copy.z = z;
        copy.innerRadius = innerRadius;
        copy.outerRadius = outerRadius;
        copy.dimension = dimension;
        return copy;
    }

    public SphereBand band() {
        return SphereBand.fromRadii(innerRadius, outerRadius);
    }

    public boolean isExclusion() {
        SphereBand band = band();
        return band != null && band.isExclusion();
    }

    public boolean isValid() {
        return id != null && !id.isBlank()
                && dimension != null && !dimension.isBlank()
                && SphereBand.fromRadii(innerRadius, outerRadius) != null;
    }

    public String shortDimension() {
        int colon = dimension.indexOf(':');
        return colon >= 0 ? dimension.substring(colon + 1) : dimension;
    }
}
