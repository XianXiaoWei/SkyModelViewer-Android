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

    public MeshData(String name, String sourcePath, List<float[]> vertices,
                    List<byte[]> packedVertexAttrs, List<float[]> uv0,
                    List<int[]> indices, List<List<BoneWeight>> boneWeights,
                    List<SkeletonBone> embeddedSkeleton, int version, boolean isAnimated) {
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
    }
}
