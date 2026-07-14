package com.sky.modelviewer.model;

public class MaterialInfo {
    public String name = "";
    public String shader = "";
    public String diffuseTex = "";
    public String diffuse2Tex = "";
    public String normTex = "";
    public String maskTex = "";
    public String attribTex = "";
    public float emissionScale = 0f;
    public float normalScale = 1f;
    public String source = null;

    public MaterialInfo() {}

    public MaterialInfo(String name, String shader, String diffuseTex, String diffuse2Tex,
                        String normTex, String maskTex, String attribTex,
                        float emissionScale, float normalScale, String source) {
        this.name = name;
        this.shader = shader;
        this.diffuseTex = diffuseTex;
        this.diffuse2Tex = diffuse2Tex;
        this.normTex = normTex;
        this.maskTex = maskTex;
        this.attribTex = attribTex;
        this.emissionScale = emissionScale;
        this.normalScale = normalScale;
        this.source = source;
    }
}
