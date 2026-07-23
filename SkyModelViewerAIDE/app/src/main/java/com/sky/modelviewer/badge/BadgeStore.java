package com.sky.modelviewer.badge;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 徽章存储管理 (SharedPreferences + JSON)
 *
 * 功能:
 *  - StoredBadge: 存储的徽章数据结构
 *  - CRUD: getAll / add / update / delete / getById
 *  - useBadge: 构造ACTION_NDEF_DISCOVERED intent + setClassName 启动游戏
 *
 * AIDE兼容: 无lambda, 使用org.json, 泛型用完整类型
 */
public class BadgeStore {

    private static final String TAG = "BadgeStore";
    private static final String PREFS_NAME = "badge_store";
    private static final String KEY_BADGES = "badges";

    // ================================================================
    //  存储徽章数据结构
    // ================================================================

    /**
     * 存储的徽章条目
     */
    public static class StoredBadge {
        public String id;
        public String title;
        public String link;
        public String skCode;
        public String remark;
        public String channel;
        public long timestamp;

        public StoredBadge() {
        }

        public StoredBadge(String id, String title, String link, String skCode,
                           String remark, String channel, long timestamp) {
            this.id = id;
            this.title = title;
            this.link = link;
            this.skCode = skCode;
            this.remark = remark;
            this.channel = channel;
            this.timestamp = timestamp;
        }

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", id);
                obj.put("title", title == null ? "" : title);
                obj.put("link", link == null ? "" : link);
                obj.put("skCode", skCode == null ? "" : skCode);
                obj.put("remark", remark == null ? "" : remark);
                obj.put("channel", channel == null ? "" : channel);
                obj.put("timestamp", timestamp);
            } catch (Exception e) {
                Log.e(TAG, "toJson error", e);
            }
            return obj;
        }

        public static StoredBadge fromJson(JSONObject obj) {
            if (obj == null) return null;
            StoredBadge badge = new StoredBadge();
            badge.id = obj.optString("id", "");
            badge.title = obj.optString("title", "");
            badge.link = obj.optString("link", "");
            badge.skCode = obj.optString("skCode", "");
            badge.remark = obj.optString("remark", "");
            badge.channel = obj.optString("channel", BadgeData.DEFAULT_CHANNEL);
            badge.timestamp = obj.optLong("timestamp", 0);
            return badge;
        }
    }

    // ================================================================
    //  SharedPreferences 操作
    // ================================================================

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ================================================================
    //  CRUD 方法
    // ================================================================

    /**
     * 获取所有存储的徽章
     */
    public static List<StoredBadge> getAll(Context context) {
        List<StoredBadge> list = new ArrayList<StoredBadge>();
        try {
            String json = getPrefs(context).getString(KEY_BADGES, "[]");
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                StoredBadge badge = StoredBadge.fromJson(obj);
                if (badge != null) {
                    list.add(badge);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "getAll error", e);
        }
        return list;
    }

    /**
     * 保存徽章列表 (覆盖写入)
     */
    public static void save(Context context, List<StoredBadge> badges) {
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < badges.size(); i++) {
                arr.put(badges.get(i).toJson());
            }
            getPrefs(context).edit().putString(KEY_BADGES, arr.toString()).commit();
        } catch (Exception e) {
            Log.e(TAG, "save error", e);
        }
    }

    /**
     * 添加新徽章
     *
     * @param title   标题
     * @param link    徽章链接
     * @param skCode  SK码
     * @param remark  备注
     * @param channel 渠道 (null则默认"官服")
     * @return 新创建的StoredBadge
     */
    public static StoredBadge add(Context context, String title, String link,
                                  String skCode, String remark, String channel) {
        List<StoredBadge> list = getAll(context);
        StoredBadge badge = new StoredBadge(
                UUID.randomUUID().toString(),
                title,
                link,
                skCode,
                remark,
                channel != null ? channel : BadgeData.DEFAULT_CHANNEL,
                System.currentTimeMillis()
        );
        list.add(badge);
        save(context, list);
        Log.d(TAG, "添加徽章: " + title);
        return badge;
    }

    /**
     * 更新徽章
     */
    public static void update(Context context, StoredBadge badge) {
        if (badge == null || badge.id == null) return;
        List<StoredBadge> list = getAll(context);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(badge.id)) {
                list.set(i, badge);
                break;
            }
        }
        save(context, list);
        Log.d(TAG, "更新徽章: " + badge.id);
    }

    /**
     * 删除徽章
     *
     * @param id 徽章ID
     */
    public static void delete(Context context, String id) {
        if (id == null) return;
        List<StoredBadge> list = getAll(context);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(id)) {
                list.remove(i);
                break;
            }
        }
        save(context, list);
        Log.d(TAG, "删除徽章: " + id);
    }

    /**
     * 根据ID获取徽章
     */
    public static StoredBadge getById(Context context, String id) {
        if (id == null) return null;
        List<StoredBadge> list = getAll(context);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                return list.get(i);
            }
        }
        return null;
    }

    /**
     * 获取徽章数量
     */
    public static int count(Context context) {
        return getAll(context).size();
    }

    /**
     * 清空所有徽章
     */
    public static void clearAll(Context context) {
        getPrefs(context).edit().remove(KEY_BADGES).commit();
        Log.d(TAG, "清空所有徽章");
    }

    // ================================================================
    //  使用徽章 (启动游戏)
    // ================================================================

    /**
     * 使用徽章 - 构造ACTION_NDEF_DISCOVERED intent + setClassName 启动游戏
     *
     * @param context     上下文
     * @param link        徽章链接 (STAR徽章URL)
     * @param channelName 渠道名 (null则默认"官服")
     * @return true表示启动成功
     */
    public static boolean useBadge(Context context, String link, String channelName) {
        if (link == null || link.isEmpty()) {
            Toast.makeText(context, "徽章链接为空", Toast.LENGTH_SHORT).show();
            return false;
        }

        // 查找渠道信息
        String packageName = null;
        String className = null;

        if (channelName == null || channelName.isEmpty()) {
            channelName = BadgeData.DEFAULT_CHANNEL;
        }

        String[] channel = BadgeData.getChannelByName(channelName);
        if (channel != null) {
            packageName = channel[1];
            className = channel[2];
        }

        // 如果没找到指定渠道, 回退到默认渠道
        if (packageName == null) {
            channel = BadgeData.getChannelByName(BadgeData.DEFAULT_CHANNEL);
            if (channel != null) {
                packageName = channel[1];
                className = channel[2];
                channelName = BadgeData.DEFAULT_CHANNEL;
            }
        }

        if (packageName == null || className == null) {
            Toast.makeText(context, "未找到渠道信息: " + channelName, Toast.LENGTH_SHORT).show();
            return false;
        }

        // 如果链接不是完整URL, 尝试通过SK码构建
        String finalLink = link;
        if (!link.startsWith("http") && !link.startsWith("sky")) {
            // 可能是纯SK码, 构建完整URL
            String skCode = link;
            String builtUrl = SkExtractor.buildLinkFromSk(skCode);
            if (builtUrl != null) {
                finalLink = builtUrl;
            } else {
                finalLink = "https://sky.thatgamecompany.com/u?s=" + link;
            }
        }

        try {
            Intent intent = new Intent("android.nfc.action.NDEF_DISCOVERED");
            intent.setData(Uri.parse(finalLink));
            intent.setClassName(packageName, className);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
            Log.d(TAG, "启动游戏: 渠道=" + channelName + ", 链接=" + finalLink);
            return true;
        } catch (android.content.ActivityNotFoundException e) {
            Log.e(TAG, "游戏未安装: " + packageName, e);
            Toast.makeText(context, "未安装该渠道的游戏 (" + channelName + ")\n包名: " + packageName,
                    Toast.LENGTH_LONG).show();
            return false;
        } catch (SecurityException e) {
            Log.e(TAG, "启动游戏权限不足", e);
            Toast.makeText(context, "启动游戏失败(权限不足): " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            return false;
        } catch (Exception e) {
            Log.e(TAG, "启动游戏失败", e);
            Toast.makeText(context, "启动游戏失败: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
            return false;
        }
    }
}
