package com.sky.modelviewer.ai;

/**
 * Builds context information about the current model/state for AI queries.
 * This information is sent as a system prompt so the AI knows what the user is looking at.
 */
public class AIContextBuilder {

    /**
     * Build a system prompt that describes the app and current model state.
     * Enhanced version with full app context (mode, camera, map, wardrobe).
     */
    public static String buildSystemPrompt(String modelInfo) {
        return buildSystemPrompt(modelInfo, null);
    }

    /**
     * Build a system prompt with extended app context.
     * @param modelInfo current model/mesh info (may be null)
     * @param appContext extended context (mode, camera, map layers, wardrobe, etc.)
     */
    public static String buildSystemPrompt(String modelInfo, String appContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是「光遇模型查看器」的AI助手。这是一个Android应用，用于查看《光·遇》(Sky: Children of the Light)游戏的3D模型资产。\n\n");
        sb.append("应用功能包括：\n");
        sb.append("- 3D模型预览（OpenGL ES 3.0渲染）\n");
        sb.append("- 骨架动画播放\n");
        sb.append("- 换装系统（多mesh叠加）\n");
        sb.append("- 地图模式（关卡地形+物件+标记点+事件逻辑+关卡信息）\n");
        sb.append("- 场景编辑器（TGCL节点编辑）\n");
        sb.append("- 音频浏览（FMOD音频库）\n");
        sb.append("- 多格式导出（GLB/OBJ/FBX）\n\n");

        if (modelInfo != null && !modelInfo.isEmpty()) {
            sb.append("当前加载的模型信息：\n");
            sb.append(modelInfo);
            sb.append("\n\n");
        }

        if (appContext != null && !appContext.isEmpty()) {
            sb.append("当前应用状态：\n");
            sb.append(appContext);
            sb.append("\n\n");
        }

        sb.append("请用简洁的中文回答用户关于模型、游戏资产、3D渲染、光遇游戏等方面的问题。");
        sb.append("如果用户询问模型详情，请基于上面的模型信息回答。");
        sb.append("如果用户询问换装建议，请推荐适合的装扮组合。");
        sb.append("如果用户在地图模式下，可以帮用户切换图层、搜索关卡、查看事件逻辑和关卡信息。\n\n");

        sb.append("你具备以下增强能力（用户可通过按钮开启）：\n");
        sb.append("- 联网搜索：当用户开启「联网搜索」时，系统会自动搜索网络并将结果注入对话。");
        sb.append("你会看到标注为「网络搜索结果」的内容，请基于这些结果回答用户问题。");
        sb.append("如果搜索结果为空或无关，请说明未找到相关信息并基于自身知识回答。\n");
        sb.append("- 深度思考：当用户开启「深度思考」时，请逐步分析问题，给出详细推理过程。\n");
        sb.append("- 图片解析：用户可发送图片，你能看到并分析图片内容（需vision模型支持）。\n");
        sb.append("- 文件解析：用户可发送文本文件，文件内容会以代码块形式包含在消息中，请基于文件内容回答。\n\n");
        sb.append("重要：当对话中包含「网络搜索结果」时，说明联网搜索已生效，请基于搜索结果回答，不要否认联网能力。\n\n");

        // Add command capabilities
        sb.append(AICommandExecutor.getCommandInstructions());

        return sb.toString();
    }

    /**
     * Build model info string from mesh data.
     */
    public static String buildModelInfo(String meshName, int vertexCount, int faceCount,
                                         int boneCount, int materialCount, String materialNames,
                                         boolean hasUV, boolean hasVertexColors,
                                         boolean hasAnimation, String animName,
                                         float scale, String[] wardrobeItems) {
        StringBuilder sb = new StringBuilder();
        sb.append("模型名称: ").append(meshName != null ? meshName : "未知").append("\n");
        sb.append("顶点数: ").append(vertexCount).append("\n");
        sb.append("面数: ").append(faceCount).append("\n");
        sb.append("骨骼数: ").append(boneCount).append("\n");
        sb.append("材质数: ").append(materialCount).append("\n");
        if (materialNames != null && !materialNames.isEmpty()) {
            sb.append("材质名称: ").append(materialNames).append("\n");
        }
        sb.append("有UV坐标: ").append(hasUV ? "是" : "否").append("\n");
        sb.append("有顶点色: ").append(hasVertexColors ? "是" : "否").append("\n");
        sb.append("有动画: ").append(hasAnimation ? "是" : "否").append("\n");
        if (hasAnimation && animName != null) {
            sb.append("当前动画: ").append(animName).append("\n");
        }
        sb.append("模型缩放: ").append(scale).append("\n");
        if (wardrobeItems != null && wardrobeItems.length > 0) {
            sb.append("已穿戴装扮: ");
            for (int i = 0; i < wardrobeItems.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(wardrobeItems[i]);
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Suggested quick prompts for the user.
     * Expanded to cover map mode, wardrobe, and common tasks.
     */
    public static final String[] QUICK_PROMPTS = {
        "分析当前模型的特点",
        "切换到线框模式",
        "播放动画",
        "给模型染成蓝色",
        "设置日落氛围灯光",
        "切换到地图模式",
        "显示关卡信息",
        "显示事件逻辑",
        "切换到换装模式",
        "自动取景并重置视角",
        "列出所有mesh",
        "把背景改成黑色"
    };
}
