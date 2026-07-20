package com.sky.modelviewer.model;

import java.util.List;

public class MeshData {
    public final String name;
    public final String sourcePath;
    public final List<float[]> vertices;
    public final List<byte[]> packedVertexAttrs;
    public final List<float[]> uv0;
    public final List<int[]> indices;
    public final List<List<BoneWeight>> boneWeights;
    public final List<SkeletonBone> embeddedSkeleton;
    public final int version;
    public final boolean isAnimated;
    // Multi-UV support (ported from HTML parseModern: 16-byte UV stride contains uv0/uv1/uv3)
    // uv1 = baked lighting/AO map coordinates (u_lightTex)
    // uv3 = second layer color coordinates (u_diffuse2Tex)
    public final List<float[]> uv1;
    public final List<float[]> uv3;

    public MeshData(String name, String sourcePath, List<float[]> vertices,
                    List<byte[]> packedVertexAttrs, List<float[]> uv0,
                    List<int[]> indices, List<List<BoneWeight>> boneWeights,
                    List<SkeletonBone> embeddedSkeleton, int version, boolean isAnimated) {
        this(name, sourcePath, vertices, packedVertexAttrs, uv0, indices,
             boneWeights, embeddedSkeleton, version, isAnimated, null, null);
    }

    public MeshData(String name, String sourcePath, List<float[]> vertices,
                    List<byte[]> packedVertexAttrs, List<float[]> uv0,
                    List<int[]> indices, List<List<BoneWeight>> boneWeights,
                    List<SkeletonBone> embeddedSkeleton, int version, boolean isAnimated,
                    List<float[]> uv1, List<float[]> uv3) {
        this.name = name;
        this.sourcePath = sourcePath;
        this.vertices = vertices;
        this.packedVertexAttrs = packedVertexAttrs;
        this.uv0 = uv0;
        this.indices = indices;
        this.boneWeights = boneWeights;
        this.embeddedSkeleton = embeddedSkeleton;
        this.version = version;
        this.isAnimated = isAnimated;
        this.uv1 = uv1;
        this.uv3 = uv3;
    }
}
