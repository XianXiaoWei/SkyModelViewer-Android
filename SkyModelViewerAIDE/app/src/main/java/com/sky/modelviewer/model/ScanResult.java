package com.sky.modelviewer.model;

import java.util.List;

public class ScanResult {
    public final String apkPath;
    public final List<MeshCatalogEntry> meshes;

    public ScanResult(String apkPath, List<MeshCatalogEntry> meshes) {
        this.apkPath = apkPath;
        this.meshes = meshes;
    }
}
