package com.sky.modelviewer.parsing;

import com.sky.modelviewer.parsing.TgclParser.TgclFile;
import com.sky.modelviewer.parsing.TgclParser.BstNode;
import com.sky.modelviewer.parsing.TgclParser.Field;

import java.util.*;

/**
 * 从 Objects.level.bin 提取关卡物件实例列表。
 * EXACT port of HTML extractLevelMeshes (line 35425-35489) + buildLevelMaterialIndex (line 35401-35418).
 *
 * Uses new TgclParser which mirrors HTML TGCL class exactly:
 *   - Fields are List<Field> with {kind, name, strValue/bytes/clumpValue/elems}
 *   - shaderTex/shaderVec use Field[] traversal matching HTML
 */
public class LevelMeshExtractor {

    /** 单个关卡物件实例 */
    public static class LevelMeshInstance {
        public String name;           // 节点名
        public String resourceName;   // mesh资源名
        public String shaderName;     // shader名
        public String diffuseTex;     // u_diffuse1Tex 或 u_diffuseTex
        public String normTex;        // u_normTex
        public String diffuse2Tex;    // u_diffuse2Tex
        public String lightTex;       // u_lightTex
        public float[] diffuse2Offset; // u_diffuse2TexOffset [x,y,z,w] 或 null
        public float[] diffuse1Scale;  // u_diffuse1TexScale 或 null
        public float[] diffuseColor;   // u_diffuseColor [r,g,b,a] 或 null
        public float[] matBaseColor;   // LevelMaterial.baseColor 或 null
        public float[] matrix;         // 16元素变换矩阵(行主序，对齐THREE.Matrix4.set)
        public float[] rawTransform;   // 16元素变换矩阵(列主序，原始TGCL格式)
        public String typeName;        // 类名
    }

    /** LevelMaterial 索引条目 */
    private static class MaterialEntry {
        String shaderName;
        String diffuse1Tex;
        String diffuse2Tex;
        String normTex;
        String lightTex;
        float[] diffuse2Offset;
        float[] baseColor;
    }

    /**
     * 从TGCL文件提取所有mesh实例。
     * EXACT port of HTML extractLevelMeshes (line 35425-35489).
     */
    public static List<LevelMeshInstance> extract(TgclFile tgclFile) {
        List<LevelMeshInstance> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 1. 建立LevelMaterial索引 (HTML buildLevelMaterialIndex line 35401-35418)
        Map<Integer, MaterialEntry> matIndex = buildMaterialIndex(tgclFile);

        // Debug stats
        int totalNodes = tgclFile.nodes.size();
        int withResourceName = 0;
        int withTransform = 0;
        int withDiffuseTex = 0;
        int withMaterialFallback = 0;

        // 2. 遍历所有节点 (HTML line 35431-35488)
        for (BstNode nd : tgclFile.nodes) {
            // nodeResourceName (HTML line 35432)
            String resName = TgclParser.nodeResourceName(nd);
            if (resName == null || resName.isEmpty()) continue;
            withResourceName++;

            // nodeTransform (HTML line 35434)
            float[] rawTf = TgclParser.nodeTransform(nd);
            if (rawTf == null) continue; // 必须有64字节transform
            withTransform++;

            // 去重键 = resourceName + transform (HTML line 35437-35439)
            String key = resName + "|" + Arrays.toString(rawTf);
            if (seen.contains(key)) continue;
            seen.add(key);

            LevelMeshInstance inst = new LevelMeshInstance();
            inst.name = nd.name;
            inst.resourceName = resName;
            inst.typeName = nd.type < tgclFile.typeNames.size() ? tgclFile.typeNames.get(nd.type) : "";

            // shaderName (HTML line 35440, 35456)
            inst.shaderName = TgclParser.getStringProp(nd, "shaderName");

            // 从shaderParams提取贴图 (HTML line 35444-35452)
            // diffuseTex: u_diffuse1Tex || u_diffuseTex
            inst.diffuseTex = TgclParser.shaderTex(nd, "u_diffuse1Tex");
            if (inst.diffuseTex == null || inst.diffuseTex.isEmpty()) {
                inst.diffuseTex = TgclParser.shaderTex(nd, "u_diffuseTex");
            }
            inst.normTex = TgclParser.shaderTex(nd, "u_normTex");
            inst.diffuse2Tex = TgclParser.shaderTex(nd, "u_diffuse2Tex");
            inst.lightTex = TgclParser.shaderTex(nd, "u_lightTex");
            inst.diffuse2Offset = TgclParser.shaderVec(nd, "u_diffuse2TexOffset");
            inst.diffuse1Scale = TgclParser.shaderVec(nd, "u_diffuse1TexScale");
            inst.diffuseColor = TgclParser.shaderVec(nd, "u_diffuseColor");

            // materialBstGuid 回退 (HTML line 35462-35475)
            inst.matBaseColor = null;
            if (inst.diffuseTex == null || inst.diffuseTex.isEmpty()) {
                int mg = TgclParser.nodePodU32(nd, "materialBstGuid");
                if (mg != 0 && matIndex.containsKey(mg)) {
                    withMaterialFallback++;
                    MaterialEntry lm = matIndex.get(mg);
                    if (lm.diffuse1Tex != null && !lm.diffuse1Tex.isEmpty()) inst.diffuseTex = lm.diffuse1Tex;
                    if ((inst.normTex == null || inst.normTex.isEmpty()) && lm.normTex != null) inst.normTex = lm.normTex;
                    if ((inst.diffuse2Tex == null || inst.diffuse2Tex.isEmpty()) && lm.diffuse2Tex != null) inst.diffuse2Tex = lm.diffuse2Tex;
                    if ((inst.lightTex == null || inst.lightTex.isEmpty()) && lm.lightTex != null) inst.lightTex = lm.lightTex;
                    if (inst.diffuse2Offset == null && lm.diffuse2Offset != null) inst.diffuse2Offset = lm.diffuse2Offset;
                    if ((inst.shaderName == null || inst.shaderName.isEmpty()) && lm.shaderName != null) inst.shaderName = lm.shaderName;
                    inst.matBaseColor = lm.baseColor;
                }
            }

            // 矩阵重排：列主序→行主序 (HTML line 35479-35485)
            float[] b = rawTf;
            inst.rawTransform = b;
            inst.matrix = new float[] {
                b[0], b[4], b[8],  b[12],  // 行0
                b[1], b[5], b[9],  b[13],  // 行1
                b[2], b[6], b[10], b[14],  // 行2
                0,    0,    0,     1
            };

            out.add(inst);
            if (inst.diffuseTex != null && !inst.diffuseTex.isEmpty()) withDiffuseTex++;
        }

        // Debug: log extraction stats
        android.util.Log.d("LevelMeshExtractor", "extract: totalNodes=" + totalNodes +
            " withResourceName=" + withResourceName +
            " withTransform=" + withTransform +
            " instances=" + out.size() +
            " withDiffuseTex=" + withDiffuseTex +
            " withMaterialFallback=" + withMaterialFallback +
            " materialIndexSize=" + matIndex.size());
        // Log first 10 instances WITH diffuseTex for verification
        int logged = 0;
        for (LevelMeshInstance i : out) {
            if (i.diffuseTex != null && !i.diffuseTex.isEmpty() && logged < 10) {
                android.util.Log.d("LevelMeshExtractor", "  WITH_TEX: resourceName='" + i.resourceName +
                    "' diffuseTex='" + i.diffuseTex + "' shaderName='" + i.shaderName + "'" +
                    " normTex='" + i.normTex + "' diffuse2Tex='" + i.diffuse2Tex + "'");
                logged++;
            }
        }
        // Log first 10 instances WITHOUT diffuseTex (to diagnose why)
        int noTexLogged = 0;
        for (LevelMeshInstance i : out) {
            if ((i.diffuseTex == null || i.diffuseTex.isEmpty()) && noTexLogged < 10) {
                android.util.Log.d("LevelMeshExtractor", "  NO_TEX: resourceName='" + i.resourceName +
                    "' typeName='" + i.typeName + "'" +
                    " matBaseColor=" + (i.matBaseColor != null ? java.util.Arrays.toString(i.matBaseColor) : "null") +
                    " shaderName='" + i.shaderName + "'");
                noTexLogged++;
            }
        }
        // Log first 5 LevelMaterial entries from matIndex
        int matLogged = 0;
        for (Map.Entry<Integer, MaterialEntry> e : matIndex.entrySet()) {
            if (matLogged >= 5) break;
            MaterialEntry lm = e.getValue();
            android.util.Log.d("LevelMeshExtractor", "  MAT[" + matLogged + "] guid=" + e.getKey() +
                " diffuse1Tex='" + lm.diffuse1Tex + "'" +
                " normTex='" + lm.normTex + "'" +
                " shaderName='" + lm.shaderName + "'" +
                " baseColor=" + (lm.baseColor != null ? java.util.Arrays.toString(lm.baseColor) : "null"));
            matLogged++;
        }
        return out;
    }

    /**
     * 建立LevelMaterial索引 (HTML buildLevelMaterialIndex line 35401-35418).
     * Scans all nodes for LevelMaterial type, indexes by bstGuid.
     */
    private static Map<Integer, MaterialEntry> buildMaterialIndex(TgclFile tgclFile) {
        Map<Integer, MaterialEntry> idx = new HashMap<>();
        for (BstNode nd : tgclFile.nodes) {
            String typeName = nd.type < tgclFile.typeNames.size() ? tgclFile.typeNames.get(nd.type) : "";
            if (!"LevelMaterial".equals(typeName)) continue;

            int guid = TgclParser.nodePodU32(nd, "bstGuid");
            if (guid == 0) continue;

            MaterialEntry entry = new MaterialEntry();
            entry.shaderName = TgclParser.getStringProp(nd, "shaderName");
            // CRITICAL: LevelMaterial's shaderParams use field names 'name'/'tex'/'vec'
            // (NOT 'uniformName'/'texValue'/'vecValue' used by LevelMesh instances).
            // Must use matParamTex/matParamVec here, not shaderTex/shaderVec.
            // Ported from HTML buildLevelMaterialIndex (line 36027-36044) + matParamTex (line 35996).
            entry.diffuse1Tex = TgclParser.matParamTex(nd, "u_diffuse1Tex");
            if (entry.diffuse1Tex == null || entry.diffuse1Tex.isEmpty()) {
                entry.diffuse1Tex = TgclParser.matParamTex(nd, "u_diffuseTex");
            }
            entry.diffuse2Tex = TgclParser.matParamTex(nd, "u_diffuse2Tex");
            entry.normTex = TgclParser.matParamTex(nd, "u_normTex");
            entry.lightTex = TgclParser.matParamTex(nd, "u_lightTex");
            entry.diffuse2Offset = TgclParser.matParamVec(nd, "u_diffuse2TexOffset");
            entry.baseColor = TgclParser.nodePodVec4(nd, "baseColor");
            idx.put(guid, entry);
        }
        return idx;
    }
}
