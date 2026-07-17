package com.sky.modelviewer.model;

public class MeshCatalogEntry {
    public final String name;
    public final String fullPath;
    public final String relativePath;
    public final String category;
    public final String fileType; // "mesh" or "level"

    public MeshCatalogEntry(String name, String fullPath, String relativePath, String category, String fileType) {
        this.name = name;
        this.fullPath = fullPath;
        this.relativePath = relativePath;
        this.category = category;
        this.fileType = fileType;
    }

    public MeshCatalogEntry(String name, String fullPath, String relativePath, String category) {
        this(name, fullPath, relativePath, category, "mesh");
    }
}
