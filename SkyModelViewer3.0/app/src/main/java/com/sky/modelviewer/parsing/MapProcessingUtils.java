package com.sky.modelviewer.parsing;

import android.util.Log;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Map processing utilities ported from the HTML enhanced SkyMeshViewer.
 *
 * Provides:
 *  - Object classification (13 categories based on resource name + shader)
 *  - Surface quality parameters per category (specStrength / shininess / fresnel)
 *  - Layer metadata (label, default visibility)
 *  - Camera auto-framing helpers (bounds calculation, distance estimation)
 *  - Mesh distortion / skinning fallback (zero-weight identity matrix)
 *
 * Reference: SkyMeshViewer.single.(增强版).html lines 38133-38360, 35044-35061
 */
public final class MapProcessingUtils {

    private static final String TAG = "MapProcessingUtils";

    private MapProcessingUtils() {}

    // ==================== Layer Categories ====================

    public static final int LAYER_MARKER = 0;
    public static final int LAYER_TERRAIN = 1;
    public static final int LAYER_ROCK = 2;
    public static final int LAYER_BUILDING = 3;
    public static final int LAYER_STRUCTURE = 4;
    public static final int LAYER_ART = 5;
    public static final int LAYER_LIGHT = 6;
    public static final int LAYER_VEGETATION = 7;
    public static final int LAYER_PROP = 8;
    public static final int LAYER_MISC = 9;
    public static final int LAYER_WATER = 10;
    public static final int LAYER_CLOUD = 11;
    public static final int LAYER_EFFECT = 12;

    public static final int LAYER_COUNT = 13;

    public static final String[] LAYER_KEYS = {
        "marker", "terrain", "rock", "building", "structure", "art",
        "light", "vegetation", "prop", "misc", "water", "cloud", "effect"
    };

    public static final String[] LAYER_LABELS = {
        "点位标记", "地形", "岩石/地貌", "建筑", "门/机关/结构", "壁画/雕像/星座",
        "灯烛/火/光源", "植被", "道具/家具", "其他物件", "水面", "云", "雾/风/特效"
    };

    public static final int[] LAYER_COLORS = {
        0xFFFF4040, 0xFF8B7355, 0xFF6B6B6B, 0xFFB8860B, 0xFFCD853F, 0xFFDA70D6,
        0xFFFFD700, 0xFF228B22, 0xFFD2691E, 0xFFA9A9A9, 0xFF4169E1, 0xFFB0C4DE,
        0xFF9370DB
    };

    /** Layers that are hidden by default (effect/marker) */
    public static final Set<Integer> LAYERS_DEFAULT_OFF = new HashSet<>();
    static {
        LAYERS_DEFAULT_OFF.add(LAYER_EFFECT);
        LAYERS_DEFAULT_OFF.add(LAYER_MARKER);
    }

    // ==================== Object Classification ====================

    // Pre-compiled regex patterns for classification (ported from HTML line 38137-38151)
    private static final Pattern PAT_LIGHT = Pattern.compile(
        "Candle|Fire|Sconce|Brazier|Bonfire|Lantern|Lighthorn|GodLight|LightShaft|Bell|Chime|Torch|Flame|Lamp",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_ART = Pattern.compile(
        "Mural|Painting|Statue|Ancestor|Constellation|Shrine|Symbol|Motif|Mote|Alter|Altar|Totem|Tablet|Relic|Rune|Glyph",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_STRUCTURE = Pattern.compile(
        "Door|Gate|Puzzle|Platform|Bridge|Wall|Steps|Stair|Frame|Elevator|Grate|Portal|Launch|Skyway|Trigger|Elevator",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_BUILDING = Pattern.compile(
        "Temple|Stupa|Tower|Shop|House|Canopy|Boat|Ship|Pillar|Column|Roof|Building|Hut|Tent|Aviary|Colosseum|Engine",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_VEGETATION = Pattern.compile(
        "Flower|Plant|Tree|Bush|Grass|Canopy|Darkshroom|Foliage|Leaf|Vine|Mushroom|Seaweed|Coral|Kelp",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_VEG_SHADER = Pattern.compile(
        "Foliage|Grass|Leaf", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_ROCK = Pattern.compile(
        "Rock|Cliff|Hill|Dune|Sand|Mountain|Stone|Boulder|Cave|Crag|Reef|Ice|Snow",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_PROP = Pattern.compile(
        "Prop|Jar|Crate|Table|Bench|Desk|Backpack|Scroll|Bucket|Pillow|Rug|Box|Barrel|Basket|Pot|Vase|Book|Debris|Flag|Rope|Cloth|Fabric|Paper|Map",
        Pattern.CASE_INSENSITIVE);

    // Water/atmosphere detection patterns (HTML line 37947-37959)
    private static final Pattern PAT_WATER = Pattern.compile(
        "ocean|water", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_WATER_EXCLUDE = Pattern.compile(
        "waterfall|floor|veil|gauge|bucket|cham|obelisk|hat", Pattern.CASE_INSENSITIVE);
    // Atmosphere effects — exact match from HTML line 37952
    private static final Pattern PAT_ATMO = Pattern.compile(
        "Fog|Cloud|Aurora|Rainbow|StormCard|StormWind|StormEnd|LightShaft|LightBeam|GodLight|Lightning|Glow|Bloom|Ripple|Shaft|Halo|Constellation|SingleStar|StarStreak|StarField|StarBroken|SunFlower",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_WATER_SHADER = Pattern.compile(
        "Ocean", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_WATER_SHADER_EXCLUDE = Pattern.compile(
        "Caustics|Cham", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_CUTOUT = Pattern.compile(
        "Alpha|Sdf|Cham|Foliage|Leaf|Bush|Tree|Grass|Flower", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAT_UNLIT = Pattern.compile(
        "Unlit", Pattern.CASE_INSENSITIVE);

    /**
     * Classify a map object into a layer category (0-12).
     * Ported from HTML classifyMapObject() line 38133-38152.
     * Note: water/effect detection is handled separately in classifyMapObjectFull.
     */
    public static int classifyMapObject(String name, String shader) {
        String n = name != null ? name : "";
        String s = shader != null ? shader : "";

        // Light sources
        if (PAT_LIGHT.matcher(n).find()) return LAYER_LIGHT;
        // Art / collectibles
        if (PAT_ART.matcher(n).find()) return LAYER_ART;
        // Structures / mechanisms
        if (PAT_STRUCTURE.matcher(n).find()) return LAYER_STRUCTURE;
        // Buildings
        if (PAT_BUILDING.matcher(n).find()) return LAYER_BUILDING;
        // Vegetation (name or shader)
        if (PAT_VEGETATION.matcher(n).find() || PAT_VEG_SHADER.matcher(s).find()) return LAYER_VEGETATION;
        // Rocks / terrain features
        if (PAT_ROCK.matcher(n).find()) return LAYER_ROCK;
        // Props / furniture
        if (PAT_PROP.matcher(n).find()) return LAYER_PROP;

        return LAYER_MISC;
    }

    /**
     * Full classification including water/atmosphere detection.
     * Ported from HTML loadMapLevel line 37947-37997.
     * Priority: isWater → isAtmo → classifyMapObject
     *
     * @param name         resource name
     * @param shader       shader name
     * @param diffuseAlpha diffuse color alpha (for isFadeSprite detection), or null
     * @return category string key ("water", "effect", "light", "art", etc.)
     */
    public static String classifyMapObjectFull(String name, String shader, Float diffuseAlpha) {
        String n = name != null ? name : "";
        String sh = shader != null ? shader : "";

        // Water detection (HTML line 37947-37949)
        boolean isWater = (PAT_WATER.matcher(n).find()
            && !PAT_WATER_EXCLUDE.matcher(n).find() && n.length() >= 5)
            || (PAT_WATER_SHADER.matcher(sh).find() && !PAT_WATER_SHADER_EXCLUDE.matcher(sh).find());
        if (isWater) return LAYER_KEYS[LAYER_WATER];

        // Atmosphere effects (HTML line 37952)
        boolean isAtmo = !isWater && PAT_ATMO.matcher(n).find();
        if (isAtmo) return LAYER_KEYS[LAYER_EFFECT];

        // Fall through to general classification
        int cat = classifyMapObject(n, sh);
        return LAYER_KEYS[cat];
    }

    /**
     * Check if object is water (for render order / depth write decisions).
     */
    public static boolean isWater(String name, String shader) {
        String n = name != null ? name : "";
        String sh = shader != null ? shader : "";
        return (PAT_WATER.matcher(n).find()
            && !PAT_WATER_EXCLUDE.matcher(n).find() && n.length() >= 5)
            || (PAT_WATER_SHADER.matcher(sh).find() && !PAT_WATER_SHADER_EXCLUDE.matcher(sh).find());
    }

    /**
     * Check if object is atmosphere effect (for transparent/depthWrite decisions).
     */
    public static boolean isAtmosphere(String name) {
        String n = name != null ? name : "";
        return PAT_ATMO.matcher(n).find();
    }

    /**
     * Check if object uses alpha cutout (vegetation/foliage).
     */
    public static boolean isCutout(String name, String shader) {
        String n = name != null ? name : "";
        String sh = shader != null ? shader : "";
        return PAT_CUTOUT.matcher(sh + " " + n).find();
    }

    /**
     * Check if object is a fade sprite (Unlit + low alpha).
     */
    public static boolean isFadeSprite(String name, String shader, Float diffuseAlpha) {
        String n = name != null ? name : "";
        String sh = shader != null ? shader : "";
        if (isWater(n, sh) || isAtmosphere(n)) return false;
        return PAT_UNLIT.matcher(sh).find() && diffuseAlpha != null && diffuseAlpha < 0.1f;
    }

    // ==================== Surface Parameters ====================

    /** Surface quality parameters for a mesh category */
    public static class SurfaceParams {
        public final float specStrength;
        public final float shininess;
        public final float fresnel;

        public SurfaceParams(float spec, float shin, float fres) {
            this.specStrength = spec;
            this.shininess = shin;
            this.fresnel = fres;
        }
    }

    // Metal/crystal/glass shader pattern
    private static final Pattern PAT_METAL_SHADER = Pattern.compile(
        "metal|gold|crystal|gem|glass|mirror", Pattern.CASE_INSENSITIVE);

    /**
     * Get surface quality parameters for a category + shader.
     * Ported from HTML surfaceParamsFor() line 38156-38171.
     */
    public static SurfaceParams getSurfaceParams(int category, String shader) {
        String s = shader != null ? shader.toLowerCase() : "";

        // Metal / crystal / gem: strong concentrated specular
        if (PAT_METAL_SHADER.matcher(s).find()) {
            return new SurfaceParams(0.35f, 64, 0.18f);
        }

        switch (category) {
            case LAYER_ROCK:       return new SurfaceParams(0.04f, 12, 0.06f);
            case LAYER_BUILDING:   return new SurfaceParams(0.08f, 24, 0.07f);
            case LAYER_STRUCTURE:  return new SurfaceParams(0.10f, 28, 0.07f);
            case LAYER_ART:        return new SurfaceParams(0.12f, 32, 0.08f);
            case LAYER_LIGHT:      return new SurfaceParams(0.18f, 40, 0.12f);
            case LAYER_VEGETATION: return new SurfaceParams(0.03f, 12, 0.10f);
            case LAYER_PROP:       return new SurfaceParams(0.09f, 24, 0.07f);
            case LAYER_TERRAIN:    return new SurfaceParams(0.03f, 10, 0.05f);
            default:               return new SurfaceParams(0.06f, 16, 0.06f);
        }
    }

    // ==================== Camera Auto-Framing ====================

    /**
     * Calculate camera distance to frame a bounding box.
     * Ported from HTML setView() line 36690-36711.
     *
     * @param boundsMin  bounding box min [x,y,z]
     * @param boundsMax  bounding box max [x,y,z]
     * @param fovDegrees camera field of view in degrees
     * @return distance to fill the viewport with 30% margin
     */
    public static float calculateCameraDistance(float[] boundsMin, float[] boundsMax, float fovDegrees) {
        float sizeX = boundsMax[0] - boundsMin[0];
        float sizeY = boundsMax[1] - boundsMin[1];
        float sizeZ = boundsMax[2] - boundsMin[2];
        float maxDim = Math.max(sizeX, Math.max(sizeY, sizeZ));
        if (maxDim < 0.001f) maxDim = 1.0f;

        double fovRad = Math.toRadians(fovDegrees);
        double dist = (maxDim / 2.0) / Math.tan(fovRad / 2.0) * 1.3; // 30% margin
        return (float) dist;
    }

    /**
     * Calculate the center of a bounding box.
     */
    public static float[] calculateBoundsCenter(float[] boundsMin, float[] boundsMax) {
        return new float[] {
            (boundsMin[0] + boundsMax[0]) * 0.5f,
            (boundsMin[1] + boundsMax[1]) * 0.5f,
            (boundsMin[2] + boundsMax[2]) * 0.5f
        };
    }

    /**
     * Calculate adaptive near/far planes based on camera distance.
     * Ported from HTML updateClip() line 36714-36725.
     */
    public static float[] calculateNearFar(float cameraDistance) {
        float near = Math.max(cameraDistance / 1000.0f, 0.0001f);
        float far = Math.max(cameraDistance * 1000.0f, 1000.0f);
        return new float[]{near, far};
    }

    /**
     * Calculate a camera position for a given view mode.
     * Ported from HTML setView() line 36698-36705.
     *
     * @param center   bounds center [x,y,z]
     * @param distance camera distance
     * @param mode     "reset", "front", "top", "side"
     * @return camera position [x,y,z]
     */
    public static float[] calculateCameraPosition(float[] center, float distance, String mode) {
        if ("front".equals(mode)) {
            return new float[]{center[0], center[1], center[2] + distance};
        } else if ("top".equals(mode)) {
            return new float[]{center[0], center[1] + distance, center[2] + 0.001f};
        } else if ("side".equals(mode)) {
            return new float[]{center[0] + distance, center[1], center[2]};
        } else {
            // Default isometric view
            return new float[]{
                center[0] + distance * 0.7f,
                center[1] + distance * 0.5f,
                center[2] + distance
            };
        }
    }

    // ==================== Mesh Distortion / Skinning Fallback ====================

    /**
     * Check if bone weights are all zero (degenerate vertex).
     * When total weight < 0.0001, use identity matrix to avoid vertex collapse.
     * Ported from HTML SKY_VS_BODY line 35993.
     */
    public static boolean isZeroWeight(int[] boneIndices, float[] weights) {
        float total = 0;
        for (int i = 0; i < weights.length && i < 4; i++) {
            if (boneIndices == null || i >= boneIndices.length || boneIndices[i] >= 0) {
                total += Math.abs(weights[i]);
            }
        }
        return total < 0.0001f;
    }

    /**
     * Compute the skinning matrix for a vertex (4-bone weighted blend).
     * If total weight is near zero, returns identity (distortion fallback).
     * Ported from HTML SKY_VS_BODY line 35983-35995.
     *
     * @param boneMatrices array of 16-float matrices (boneCount * 16)
     * @param boneIndices  4 bone indices for this vertex
     * @param weights      4 bone weights for this vertex
     * @return 16-element 4x4 matrix (column-major)
     */
    public static float[] computeSkinningMatrix(float[] boneMatrices, int[] boneIndices, float[] weights) {
        float[] result = new float[16];
        float totalWeight = 0;

        for (int i = 0; i < 4; i++) {
            if (i >= boneIndices.length || i >= weights.length) break;
            int bi = boneIndices[i];
            float w = weights[i];
            if (bi < 0 || bi * 16 + 15 >= boneMatrices.length || Math.abs(w) < 0.0001f) continue;
            totalWeight += Math.abs(w);
            // Add boneMatrices[bi] * w to result
            int base = bi * 16;
            for (int j = 0; j < 16; j++) {
                result[j] += boneMatrices[base + j] * w;
            }
        }

        // Zero-weight fallback: identity matrix (prevents vertex collapse to origin)
        if (totalWeight < 0.0001f) {
            result[0] = 1; result[5] = 1; result[10] = 1; result[15] = 1;
        }

        return result;
    }

    // ==================== Wardrobe Bone Name Matching ====================

    /**
     * Check if a mesh bone name matches an animation bone name.
     * Strips "Rig:" prefix and does suffix-contains matching.
     * Ported from HTML animBoneNamesMatch() line 34316-34329.
     */
    public static boolean boneNamesMatch(String meshBoneName, String animBoneName) {
        if (meshBoneName == null || animBoneName == null) return false;
        String mn = meshBoneName;
        String an = animBoneName;
        // Strip "Rig:" prefix
        if (mn.startsWith("Rig:")) mn = mn.substring(4);
        if (an.startsWith("Rig:")) an = an.substring(4);
        // Exact match
        if (mn.equals(an)) return true;
        // Suffix-contains: one ends with the other
        if (mn.endsWith(an) || an.endsWith(mn)) return true;
        // Try matching last segment after '_'
        int mnLast = mn.lastIndexOf('_');
        int anLast = an.lastIndexOf('_');
        if (mnLast >= 0 && anLast >= 0) {
            String mnSeg = mn.substring(mnLast + 1);
            String anSeg = an.substring(anLast + 1);
            if (mnSeg.equals(anSeg)) return true;
        }
        return false;
    }

    /**
     * Build a bone mapping from mesh skeleton to animation pack.
     * Returns an array where map[meshBoneIdx] = animBoneIdx, or -1 if no match.
     * Ported from HTML buildBoneMapping() line 37245-37254.
     */
    public static int[] buildBoneMapping(String[] meshBoneNames, String[] animBoneNames) {
        int[] map = new int[meshBoneNames.length];
        for (int mi = 0; mi < meshBoneNames.length; mi++) {
            map[mi] = -1;
            for (int ai = 0; ai < animBoneNames.length; ai++) {
                if (boneNamesMatch(meshBoneNames[mi], animBoneNames[ai])) {
                    map[mi] = ai;
                    break;
                }
            }
        }
        return map;
    }

    /**
     * Check if bone mapping hit rate is acceptable (>50%).
     * Ported from HTML loadAnimation() line 37288-37302.
     */
    public static boolean isBoneMappingValid(int[] boneMapping) {
        int mapped = 0;
        for (int idx : boneMapping) {
            if (idx >= 0) mapped++;
        }
        float hitRate = (float) mapped / boneMapping.length;
        Log.i(TAG, "Bone mapping hit rate: " + mapped + "/" + boneMapping.length
              + " = " + (hitRate * 100) + "%");
        return hitRate >= 0.5f;
    }

    // ==================== Wardrobe Lighting ====================

    /** Warm ambient light for character rendering (HTML line 37115) */
    public static final float[] CHAR_AMBIENT = {0.50f, 0.46f, 0.40f};
    /** Warm key light for character rendering (HTML line 37116) */
    public static final float[] CHAR_KEY = {0.98f, 0.95f, 0.88f};
    /** Warm fill light for character rendering (HTML line 37117) */
    public static final float[] CHAR_FILL = {0.42f, 0.39f, 0.34f};

    // ==================== kMaterial Color Table ====================

    /**
     * Get the RGB color for a kMaterial index.
     * Ported from HTML MAP_MATERIAL_COLORS line 35044-35061.
     */
    public static float[] getMaterialColor(int materialIndex) {
        if (materialIndex < 0 || materialIndex >= 256) {
            return new float[]{0.7f, 0.7f, 0.7f};
        }
        return MATERIAL_COLOR_TABLE[materialIndex];
    }

    /**
     * Blend up to 4 material colors by weight.
     * Ported from HTML parseGeo0() line 35134-35146.
     */
    public static float[] blendMaterialColors(int[] materialIds, float[] weights) {
        float[] result = new float[]{0, 0, 0};
        float totalWeight = 0;

        for (int i = 0; i < 4 && i < materialIds.length && i < weights.length; i++) {
            float w = weights[i];
            if (Math.abs(w) < 0.001f) continue;
            float[] c = getMaterialColor(materialIds[i]);
            result[0] += c[0] * w;
            result[1] += c[1] * w;
            result[2] += c[2] * w;
            totalWeight += w;
        }

        if (totalWeight < 0.001f) {
            // Fallback: use first material color
            return getMaterialColor(materialIds.length > 0 ? materialIds[0] : 0);
        }

        result[0] /= totalWeight;
        result[1] /= totalWeight;
        result[2] /= totalWeight;
        return result;
    }

    // 256-entry material color table (same as LevelMeshesReader but accessible here)
    private static final float[][] MATERIAL_COLOR_TABLE = new float[256][];
    static {
        for (int i = 0; i < 256; i++) {
            MATERIAL_COLOR_TABLE[i] = new float[]{0.7f, 0.7f, 0.7f};
        }
        MATERIAL_COLOR_TABLE[0] = new float[]{0.5f, 0.5f, 0.5f};
        MATERIAL_COLOR_TABLE[2] = new float[]{0.6f, 0.7f, 0.8f};
        MATERIAL_COLOR_TABLE[3] = new float[]{0.2f, 0.2f, 0.2f};
        MATERIAL_COLOR_TABLE[4] = new float[]{0.9f, 0.8f, 0.6f};
        MATERIAL_COLOR_TABLE[5] = new float[]{0.6f, 0.45f, 0.3f};
        MATERIAL_COLOR_TABLE[6] = new float[]{0.3f, 0.3f, 0.3f};
        MATERIAL_COLOR_TABLE[7] = new float[]{0.65f, 0.5f, 0.35f};
        MATERIAL_COLOR_TABLE[16] = new float[]{0.55f, 0.5f, 0.45f};
        MATERIAL_COLOR_TABLE[17] = new float[]{0.6f, 0.5f, 0.35f};
        MATERIAL_COLOR_TABLE[18] = new float[]{0.65f, 0.6f, 0.5f};
        MATERIAL_COLOR_TABLE[19] = new float[]{0.4f, 0.35f, 0.3f};
        MATERIAL_COLOR_TABLE[20] = new float[]{0.5f, 0.5f, 0.55f};
        MATERIAL_COLOR_TABLE[21] = new float[]{0.9f, 0.8f, 0.3f};
        MATERIAL_COLOR_TABLE[22] = new float[]{0.7f, 0.85f, 0.95f};
        MATERIAL_COLOR_TABLE[23] = new float[]{0.8f, 0.8f, 0.85f};
        MATERIAL_COLOR_TABLE[24] = new float[]{0.75f, 0.75f, 0.8f};
        MATERIAL_COLOR_TABLE[25] = new float[]{0.7f, 0.7f, 0.75f};
        MATERIAL_COLOR_TABLE[26] = new float[]{0.6f, 0.45f, 0.35f};
        MATERIAL_COLOR_TABLE[27] = new float[]{0.4f, 0.35f, 0.25f};
        MATERIAL_COLOR_TABLE[28] = new float[]{0.35f, 0.35f, 0.35f};
        MATERIAL_COLOR_TABLE[29] = new float[]{0.85f, 0.85f, 0.8f};
        MATERIAL_COLOR_TABLE[30] = new float[]{0.6f, 0.45f, 0.3f};
        MATERIAL_COLOR_TABLE[31] = new float[]{0.8f, 0.7f, 0.6f};
        MATERIAL_COLOR_TABLE[32] = new float[]{0.85f, 0.78f, 0.55f};
        MATERIAL_COLOR_TABLE[33] = new float[]{0.7f, 0.65f, 0.45f};
        MATERIAL_COLOR_TABLE[34] = new float[]{0.9f, 0.85f, 0.65f};
        MATERIAL_COLOR_TABLE[35] = new float[]{0.95f, 0.95f, 0.98f};
        MATERIAL_COLOR_TABLE[36] = new float[]{0.75f, 0.68f, 0.45f};
        MATERIAL_COLOR_TABLE[37] = new float[]{0.45f, 0.4f, 0.3f};
        MATERIAL_COLOR_TABLE[48] = new float[]{0.4f, 0.6f, 0.3f};
        MATERIAL_COLOR_TABLE[49] = new float[]{0.3f, 0.5f, 0.25f};
        MATERIAL_COLOR_TABLE[50] = new float[]{0.5f, 0.7f, 0.35f};
        MATERIAL_COLOR_TABLE[51] = new float[]{0.35f, 0.55f, 0.3f};
        MATERIAL_COLOR_TABLE[52] = new float[]{0.7f, 0.5f, 0.5f};
        MATERIAL_COLOR_TABLE[80] = new float[]{0.9f, 0.9f, 1.0f};
    }

    // ==================== Wardrobe Slots ====================

    /** 10 wardrobe equipment slots (HTML line 34810-34821) */
    public static final String[] WARDROBE_SLOTS = {
        "body", "mask", "hair", "hat", "horn",
        "face", "neck", "wing", "feet", "prop"
    };

    public static final String[] WARDROBE_SLOT_LABELS = {
        "身体", "面具", "发型", "帽子", "角饰",
        "脸部", "颈部", "背饰", "足部", "手持"
    };

    /** Default idle animation candidates (HTML line 37257) */
    public static final String[] DEFAULT_ANIM_CANDIDATES = {
        "CharKidAnimGroundState",
        "CharKidAnimGroundNav",
        "CharKidAnimPlayerAct"
    };
}
