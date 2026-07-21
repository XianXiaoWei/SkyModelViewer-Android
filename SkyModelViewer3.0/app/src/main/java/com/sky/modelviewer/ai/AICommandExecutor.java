package com.sky.modelviewer.ai;

import android.util.Log;

import org.json.JSONObject;

/**
 * AI Command Executor - parses and executes commands from AI responses.
 *
 * AI can embed commands in its replies using this format:
 *   <<CMD:command_name|param=value|param=value>>
 *
 * Supported commands:
 *   switch_mode       | mode=model|texture|map|wardrobe|editor|audio
 *   set_view_mode     | mode=texture|solid|wire
 *   play_animation    |
 *   pause_animation   |
 *   toggle_animation  |
 *   set_anim_speed    | speed=1.0
 *   reset_camera      |
 *   frame_camera      |
 *   zoom              | factor=1.5
 *   rotate_camera     | dx=30|dy=15
 *   clear_wardrobe    |
 *   search_mesh       | keyword=xxx
 *   load_first_mesh   |
 *   show_anim_dialog  |
 *   export_glb        |
 *   export_obj        |
 *   take_screenshot   |
 *   set_bg_color      | color=#1a1a2e
 *   toggle_grid       |
 *   show_model_info   |
 */
public class AICommandExecutor {

    private static final String TAG = "AICommandExecutor";
    private static final String CMD_PREFIX = "<<CMD:";
    private static final String CMD_SUFFIX = ">>";

    public interface CommandCallback {
        /**
         * Execute a command and return result message.
         * @param command command name
         * @param params JSON object with parameters
         * @return result message (success or error description)
         */
        String onCommand(String command, JSONObject params);
    }

    private CommandCallback callback;

    public AICommandExecutor(CommandCallback callback) {
        this.callback = callback;
    }

    /**
     * Parse AI response, extract and execute commands.
     * Returns the response text with commands removed, and commands executed.
     */
    public String processResponse(String aiReply) {
        if (aiReply == null || aiReply.isEmpty()) return aiReply;

        StringBuilder cleanText = new StringBuilder();
        int index = 0;

        while (index < aiReply.length()) {
            int cmdStart = aiReply.indexOf(CMD_PREFIX, index);

            if (cmdStart == -1) {
                // No more commands, append rest
                cleanText.append(aiReply.substring(index));
                break;
            }

            // Append text before command
            cleanText.append(aiReply.substring(index, cmdStart));

            // Find command end
            int cmdEnd = aiReply.indexOf(CMD_SUFFIX, cmdStart);
            if (cmdEnd == -1) {
                // Malformed, append rest as text
                cleanText.append(aiReply.substring(cmdStart));
                break;
            }

            // Extract and execute command
            String cmdContent = aiReply.substring(cmdStart + CMD_PREFIX.length(), cmdEnd);
            String result = executeCommand(cmdContent);
            if (result != null && !result.isEmpty()) {
                cleanText.append("\n[执行结果: ").append(result).append("]\n");
            }

            index = cmdEnd + CMD_SUFFIX.length();
        }

        return cleanText.toString().trim();
    }

    private String executeCommand(String cmdContent) {
        try {
            // Parse: command_name|param=value|param=value
            String[] parts = cmdContent.split("\\|");
            String command = parts[0].trim().toLowerCase();

            JSONObject params = new JSONObject();
            for (int i = 1; i < parts.length; i++) {
                String[] kv = parts[i].split("=", 2);
                if (kv.length == 2) {
                    params.put(kv[0].trim(), kv[1].trim());
                }
            }

            Log.i(TAG, "Executing command: " + command + " params: " + params.toString());

            if (callback != null) {
                return callback.onCommand(command, params);
            }
            return "无命令处理器";
        } catch (Exception e) {
            Log.e(TAG, "Command execution error: " + cmdContent, e);
            return "命令执行错误: " + e.getMessage();
        }
    }

    /**
     * Get the system prompt section describing available commands.
     */
    public static String getCommandInstructions() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n你可以通过在回复中嵌入命令来控制应用。命令格式：\n");
        sb.append("<<CMD:命令名|参数1=值1|参数2=值2>>\n\n");
        sb.append("可用命令：\n");
        sb.append("• <<CMD:switch_mode|mode=model>> - 切换到模型模式\n");
        sb.append("• <<CMD:switch_mode|mode=texture>> - 切换到贴图模式\n");
        sb.append("• <<CMD:switch_mode|mode=map>> - 切换到地图模式\n");
        sb.append("• <<CMD:switch_mode|mode=wardrobe>> - 切换到换装模式\n");
        sb.append("• <<CMD:set_view_mode|mode=texture>> - 贴图模式\n");
        sb.append("• <<CMD:set_view_mode|mode=solid>> - 实体模式\n");
        sb.append("• <<CMD:set_view_mode|mode=wire>> - 线框模式\n");
        sb.append("• <<CMD:play_animation>> - 播放动画\n");
        sb.append("• <<CMD:pause_animation>> - 暂停动画\n");
        sb.append("• <<CMD:toggle_animation>> - 切换播放/暂停\n");
        sb.append("• <<CMD:set_anim_speed|speed=1.5>> - 设置动画速度\n");
        sb.append("• <<CMD:reset_camera>> - 重置相机视角\n");
        sb.append("• <<CMD:frame_camera>> - 自动取景（聚焦模型）\n");
        sb.append("• <<CMD:zoom|factor=1.5>> - 缩放（>1放大，<1缩小）\n");
        sb.append("• <<CMD:rotate_camera|dx=30|dy=15>> - 旋转相机\n");
        sb.append("• <<CMD:clear_wardrobe>> - 清除所有装扮\n");
        sb.append("• <<CMD:search_mesh|keyword=xxx>> - 搜索模型\n");
        sb.append("• <<CMD:load_first_mesh>> - 加载第一个模型\n");
        sb.append("• <<CMD:show_anim_dialog>> - 显示动画选择对话框\n");
        sb.append("• <<CMD:show_model_info>> - 显示模型详细信息\n");
        sb.append("• <<CMD:export_glb>> - 导出GLB格式\n");
        sb.append("• <<CMD:export_obj>> - 导出OBJ格式\n");
        sb.append("• <<CMD:take_screenshot>> - 截图\n\n");
        sb.append("底层渲染控制命令：\n");
        sb.append("• <<CMD:set_bg_color|color=#1a1a2e>> - 设置背景色\n");
        sb.append("• <<CMD:set_ambient|r=100|g=100|b=100>> - 环境光颜色\n");
        sb.append("• <<CMD:set_key_light|r=255|g=255|b=255>> - 主光源颜色\n");
        sb.append("• <<CMD:set_fill_light|r=150|g=160|b=176>> - 补光颜色\n");
        sb.append("• <<CMD:set_light_dir|yaw=-2.0|pitch=-0.5>> - 光源方向\n");
        sb.append("• <<CMD:set_fov|fov=55>> - 视场角(10-120度)\n");
        sb.append("• <<CMD:color_tint|h=180|s=0.8|v=1.0>> - 模型染色(H:0-360,S:0-1,V:0-1)\n");
        sb.append("• <<CMD:clear_tint>> - 清除染色\n");
        sb.append("• <<CMD:hide_mesh|name=body|hide=true>> - 隐藏/显示mesh\n");
        sb.append("• <<CMD:list_meshes>> - 列出所有mesh\n");
        sb.append("• <<CMD:wire_overlay|enable=true>> - 线框叠加模式\n");
        sb.append("• <<CMD:set_camera_dist|dist=5.0>> - 相机距离\n\n");
        sb.append("预设效果：\n");
        sb.append("• <<CMD:preset_lighting|preset=warm>> - 暖色调灯光\n");
        sb.append("• <<CMD:preset_lighting|preset=cool>> - 冷色调灯光\n");
        sb.append("• <<CMD:preset_lighting|preset=studio>> - 工作室灯光\n");
        sb.append("• <<CMD:preset_lighting|preset=sunset>> - 日落氛围\n");
        sb.append("• <<CMD:preset_lighting|preset=night>> - 夜晚氛围\n\n");
        sb.append("地图模式命令：\n");
        sb.append("• <<CMD:toggle_layer|layer=effect>> - 切换地图图层可见性(effect/marker/prop/water/scenery)\n");
        sb.append("• <<CMD:show_layer|layer=effect|visible=true>> - 设置图层显隐\n");
        sb.append("• <<CMD:search_levels|keyword=dawn>> - 搜索关卡\n");
        sb.append("• <<CMD:load_level|name=Dawn>> - 加载指定关卡\n");
        sb.append("• <<CMD:show_level_info>> - 显示关卡信息(传送/任务/音乐)\n");
        sb.append("• <<CMD:show_events>> - 显示事件逻辑\n");
        sb.append("• <<CMD:list_levels>> - 列出所有可用关卡\n\n");
        sb.append("换装命令：\n");
        sb.append("• <<CMD:equip_item|category=Hair|name=xxx>> - 穿戴指定物品\n");
        sb.append("• <<CMD:search_wardrobe|keyword=hair>> - 搜索装扮\n");
        sb.append("• <<CMD:list_wardrobe|category=Hair>> - 列出分类物品\n");
        sb.append("• <<CMD:list_wardrobe_categories>> - 列出所有装扮分类\n");
        sb.append("• <<CMD:unequip_item|category=Hair>> - 脱下指定分类物品\n\n");
        sb.append("场景命令：\n");
        sb.append("• <<CMD:set_camera_target|x=0|y=0|z=0>> - 设置相机目标点\n");
        sb.append("• <<CMD:pan_camera|dx=1.0|dy=0.5>> - 平移相机\n");
        sb.append("• <<CMD:save_screenshot>> - 保存截图到相册\n");
        sb.append("• <<CMD:get_camera_info>> - 获取当前相机参数\n");
        sb.append("• <<CMD:set_render_quality|quality=high>> - 渲染质量(low/medium/high)\n\n");
        sb.append("规则：\n");
        sb.append("1. 命令必须独占一行或在回复末尾\n");
        sb.append("2. 可以在一条回复中包含多个命令，会按顺序执行\n");
        sb.append("3. 命令执行后会显示结果\n");
        sb.append("4. 先用文字解释你要做什么，再发命令\n");
        return sb.toString();
    }
}
