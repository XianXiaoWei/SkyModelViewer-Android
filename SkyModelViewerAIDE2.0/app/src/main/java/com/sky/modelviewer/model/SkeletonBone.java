package com.sky.modelviewer.model;

public class SkeletonBone {
    public final String name;
    public final int parentIndex;
    public final float[] inverseBindMatrix;

    public SkeletonBone(String name, int parentIndex, float[] inverseBindMatrix) {
        this.name = name;
        this.parentIndex = parentIndex;
        this.inverseBindMatrix = inverseBindMatrix;
    }
}
