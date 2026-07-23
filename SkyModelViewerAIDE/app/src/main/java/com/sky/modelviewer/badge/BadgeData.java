package com.sky.modelviewer.badge;

import android.util.Log;

/**
 * 光遇徽章数据库
 *
 * 数据来源: 官方商店 thatskyshop.cn STAR图鉴
 *
 * 包含:
 *  - Badge: 82款徽章/周边信息 (图鉴, 来源于官方商店真实数据)
 *  - PresetBadge: 已验证SK码的预设徽章 (可直接NFC写入)
 *  - GAME_CHANNELS: 10个游戏渠道 (渠道名, 包名, 启动Activity)
 *  - detectBySkCode: 根据SK码匹配徽章
 *  - detectByUrl: 根据URL匹配徽章
 *
 * AIDE兼容: 无lambda, 泛型用完整类型, Java 7语法
 */
public class BadgeData {

    private static final String TAG = "BadgeData";

    // ================================================================
    //  游戏渠道 (渠道名, 包名, 启动Activity类名)
    // ================================================================

    public static final String[][] GAME_CHANNELS = {
        {"应用宝", "com.tencent.tmgp.eyou.eygy", "com.tgc.sky.netease.GameActivity_Netease"},
        {"官服",   "com.netease.sky",              "com.tgc.sky.netease.GameActivity_Netease"},
        {"4399",   "com.netease.sky.m4399",        "com.tgc.sky.netease.GameActivity_Netease"},
        {"vivo",   "com.netease.sky.vivo",         "com.tgc.sky.netease.GameActivity_Netease"},
        {"小米",   "com.netease.sky.mi",           "com.tgc.sky.netease.GameActivity_Netease"},
        {"哔哩哔哩","com.netease.sky.bilibili",     "com.tgc.sky.netease.GameActivity_Netease"},
        {"OPPO",   "com.netease.sky.nearme.gamecenter", "com.tgc.sky.netease.GameActivity_Netease"},
        {"华为",   "com.netease.sky.huawei",       "com.tgc.sky.netease.GameActivity_Netease"},
        {"九游",   "com.netease.sky.aligames",     "com.tgc.sky.netease.GameActivity_Netease"},
        {"国际服", "com.tgc.sky.android",          "com.tgc.sky.GameActivity"}
    };

    /** 默认渠道名 */
    public static final String DEFAULT_CHANNEL = "官服";

    // ================================================================
    //  徽章信息类
    // ================================================================

    /**
     * 徽章条目 (图鉴)
     * category 取值: "徽章" / "毛绒玩具" / "钥匙扣" / "其他"
     */
    public static class Badge {
        public final String id;
        public final String name;
        public final String description;
        public final String category;

        public Badge(String id, String name, String description, String category) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.category = category;
        }
    }

    /**
     * 预设徽章 (含SK码, 可直接使用)
     */
    public static class PresetBadge {
        public final String name;
        public final String description;
        public final String skCode;

        public PresetBadge(String name, String description, String skCode) {
            this.name = name;
            this.description = description;
            this.skCode = skCode;
        }
    }

    // ================================================================
    //  82款徽章图鉴数据 (来源于 thatskyshop.cn STAR图鉴)
    // ================================================================

    public static final Badge[] BADGES = {
        // ---- 徽章 (金属别针 Pin) (52) ----
        new Badge("pin01", "鬼蝠斗篷徽章",       "穿上特别款鬼蝠斗篷并获得进入办公室区域的能力",   "徽章"),
        new Badge("pin02", "冥龙烟花徽章",       "点亮冥龙烟花，与周围的人一同欣赏",               "徽章"),
        new Badge("pin03", "圣岛太阳镜徽章",     "佩戴有独特镜片颜色的墨镜",                       "徽章"),
        new Badge("pin04", "圣域群岛徽章",       "与牵手好友一同传送至圣岛",                       "徽章"),
        new Badge("pin05", "紫藤花憩徽章",       "传送至雨林，召唤出紫藤花树",                     "徽章"),
        new Badge("pin06", "福虎面具徽章",       "佩戴福虎面具",                                   "徽章"),
        new Badge("pin07", "灯笼徽章",           "获得夏日灯笼",                                   "徽章"),
        new Badge("pin08", "爱之船徽章",         "获得爱之船道具",                                 "徽章"),
        new Badge("pin09", "预言山谷徽章",       "传送至预言山谷",                                 "徽章"),
        new Badge("pin10", "暖冬瑞雪斗篷徽章",   "穿上暖冬瑞雪斗篷",                               "徽章"),
        new Badge("pin11", "禁阁先祖徽章",       "传送至禁阁先祖寺庙",                             "徽章"),
        new Badge("pin12", "星光沙漠徽章",       "传送至星光沙漠",                                 "徽章"),
        new Badge("pin13", "花憩徽章",           "与牵手好友一同撒花",                             "徽章"),
        new Badge("pin14", "TGC徽章",            "穿上TGC斗篷",                                    "徽章"),
        new Badge("pin15", "爱之跷跷板徽章",     "获得爱之跷跷板",                                 "徽章"),
        new Badge("pin16", "昼夜徽章",           "将遇境切换为白天或黑夜",                         "徽章"),
        new Badge("pin17", "自然海龟徽章",       "传送至圣域群岛并召唤海龟",                       "徽章"),
        new Badge("pin18", "魔法术士斗篷徽章",   "穿上魔法术士斗篷",                               "徽章"),
        new Badge("pin19", "暮土先祖徽章",       "传送至暮土先祖寺庙",                             "徽章"),
        new Badge("pin20", "遗忘方舟徽章",       "传送至遗忘方舟",                                 "徽章"),
        new Badge("pin21", "哨兵号角徽章",       "获得哨兵号角",                                   "徽章"),
        new Badge("pin22", "烟花徽章套装",       "观赏新年烟花/幸运烟花",                          "徽章"),
        new Badge("pin23", "云野传说徽章",       "召唤云野传说",                                   "徽章"),
        new Badge("pin24", "音韵钢琴徽章",       "背上音韵钢琴",                                   "徽章"),
        new Badge("pin25", "隐士山谷徽章",       "传送至隐士山谷入口",                             "徽章"),
        new Badge("pin26", "感恩狐狸面具徽章",   "穿上感恩狐狸面具",                               "徽章"),
        new Badge("pin27", "霞谷先祖徽章",       "传送至霞谷先祖寺庙",                             "徽章"),
        new Badge("pin28", "梦想邮差斗篷徽章",   "穿上梦想邮差斗篷",                               "徽章"),
        new Badge("pin29", "花憩茶桌徽章",       "获得花憩茶桌",                                   "徽章"),
        new Badge("pin30", "花憩斗篷徽章",       "穿上花憩斗篷",                                   "徽章"),
        new Badge("pin31", "归属白斗篷徽章",     "穿上归属白斗篷",                                 "徽章"),
        new Badge("pin32", "雨林隐秘入口徽章",   "传送至雨林隐秘入口",                             "徽章"),
        new Badge("pin33", "追光花瓣斗篷徽章",   "穿上花瓣斗篷",                                   "徽章"),
        new Badge("pin34", "音韵导演斗篷徽章",   "穿上音韵导演斗篷",                               "徽章"),
        new Badge("pin35", "矮人面具徽章",       "变成矮人",                                       "徽章"),
        new Badge("pin36", "音韵演员面具徽章",   "戴上音韵演员面具",                               "徽章"),
        new Badge("pin37", "雨林先祖徽章",       "传送至雨林先祖寺庙",                             "徽章"),
        new Badge("pin38", "鼬鼠面具徽章",       "戴上鼬鼠面具",                                   "徽章"),
        new Badge("pin39", "倒立暗蟹徽章",       "翻转周围的暗蟹",                                 "徽章"),
        new Badge("pin40", "晨岛先祖徽章",       "传送至晨岛先祖寺庙",                             "徽章"),
        new Badge("pin41", "追光者斗篷徽章",     "穿上追光者斗篷",                                 "徽章"),
        new Badge("pin42", "和谐竖琴徽章",       "戴上和谐竖琴",                                   "徽章"),
        new Badge("pin43", "云野先祖徽章",       "传送至云野先祖寺庙",                             "徽章"),
        new Badge("pin44", "鲲鹏徽章",           "鲲鹏在空中飞行时旋转",                           "徽章"),
        new Badge("pin45", "你我成双徽章",       "传送至好友身旁",                                 "徽章"),
        new Badge("pin46", "女巫帽徽章",         "获得不含发型的女巫帽",                           "徽章"),
        new Badge("pin47", "向日葵花憩徽章",     "传送至云野神庙，欣赏向日葵",                     "徽章"),
        new Badge("pin48", "马蹄莲花憩徽章",     "传送至云野峻岭，召唤荷叶和雷声",                 "徽章"),
        new Badge("pin49", "长大成人魔法徽章",   "通过魔法一起长高",                               "徽章"),
        new Badge("pin50", "福娃面具徽章",       "佩戴福娃面具",                                   "徽章"),
        new Badge("pin51", "大只佬和小不点魔法徽章", "获得大只佬/小不点魔法",                     "徽章"),
        new Badge("pin52", "玫瑰花径徽章",       "散步时留下玫瑰花瓣痕迹",                         "徽章"),

        // ---- 钥匙扣 (13) ----
        new Badge("key01", "追忆拾光钥匙扣",         "发送\"侧抱\"邀请",                         "钥匙扣"),
        new Badge("key02", "熊抱雪人毛绒钥匙扣",     "发送\"熊抱\"邀请",                         "钥匙扣"),
        new Badge("key03", "叠叠蟹毛绒钥匙扣",       "发送\"背背\"邀请",                         "钥匙扣"),
        new Badge("key04", "年年有鱼毛绒钥匙扣",     "佩戴年年有鱼小帽",                         "钥匙扣"),
        new Badge("key05", "冥龙毛绒钥匙扣-特别款",  "获得独家颜色冬季宴会围巾",                 "钥匙扣"),
        new Badge("key06", "兔子毛绒钥匙扣挂饰",     "获得兔子头饰",                             "钥匙扣"),
        new Badge("key07", "海牛毛绒钥匙扣挂饰",     "发出独家海牛叫声",                         "钥匙扣"),
        new Badge("key08", "流星雨钥匙扣挂饰",       "传送至水母湾召唤流星雨",                   "钥匙扣"),
        new Badge("key09", "Sky x AURORA 钥匙扣",    "发出独特哼唱",                             "钥匙扣"),
        new Badge("key10", "Sky x Moomin 毛绒钥匙扣","获得mini Moomin毛绒玩具",                  "钥匙扣"),
        new Badge("key11", "光之爱钥匙扣",           "发出随机\"芜湖\"声音",                     "钥匙扣"),
        new Badge("key12", "毛绒兔子别针钥匙扣",     "获得兔子头饰",                             "钥匙扣"),
        new Badge("key13", "毛绒螃蟹别针钥匙扣",     "获得暗蟹饰品",                             "钥匙扣"),

        // ---- 毛绒玩具 (9) ----
        new Badge("plush01", "小奥毛绒玩具",                          "获得Little Oreo抱枕",            "毛绒玩具"),
        new Badge("plush02", "小奥毛绒玩具-特别款",                   "获得Little Oreo抱枕",            "毛绒玩具"),
        new Badge("plush03", "Sky小鹿毛绒玩具",                       "传送至月牙绿洲与小鹿相见",       "毛绒玩具"),
        new Badge("plush04", "鲲鹏毛绒玩具(98cm)",                    "获得大鲲鹏毛绒玩具",             "毛绒玩具"),
        new Badge("plush05", "恶作剧皮皮猫毛绒玩具",                  "获得巨无霸皮皮猫毛绒玩具",       "毛绒玩具"),
        new Badge("plush06", "海牛毛绒玩具",                          "传送至禁阁与灵体海牛玩耍",       "毛绒玩具"),
        new Badge("plush07", "熊抱雪人毛绒玩具",                      "穿上雪人发型/裤子变装",          "毛绒玩具"),
        new Badge("plush08", "Sky x Le Petit Prince 狐狸毛绒别针",    "获得巨无霸狐狸毛绒玩具",         "毛绒玩具"),
        new Badge("plush09", "Sky x AURORA 布偶套装",                 "穿上AURORA之翼",                 "毛绒玩具"),

        // ---- 其他 (8) ----
        new Badge("other01", "追光者雨伞",     "获得追光者雨伞记忆",                     "其他"),
        new Badge("other02", "冬季宴会围巾",   "获得冬季宴会围巾记忆",                   "其他"),
        new Badge("other03", "美术设定集",     "获得\"美术设定集\"道具",                 "其他"),
        new Badge("other04", "美术设定集II",   "获得\"美术设定集II\"道具",               "其他"),
        new Badge("other05", "立体绘本",       "获得\"立体绘本\"道具",                   "其他"),
        new Badge("other06", "光之子手办",     "与光之子相会",                           "其他"),
        new Badge("other07", "梦之盒",         "发送\"熊抱\"邀请",                       "其他"),
        new Badge("other08", "光之忆礼盒",     "精致礼盒珍藏难忘回忆，精选徽章附带STAR魔法", "其他")
    };

    // ================================================================
    //  预设徽章 (含已验证SK码)
    //
    //  仅收录已从官方商店确认真实SK码的徽章。
    //  注意: 官方真实SK码格式为 "SKY-..." (如 SKY-PN-ST-CAP-MB),
    //  随着更多SK码被核实, 可继续向此数组追加条目。
    // ================================================================

    public static final PresetBadge[] PRESET_BADGES = {
        new PresetBadge("鬼蝠斗篷徽章",
                "穿上特别款鬼蝠斗篷并获得进入办公室区域的能力",
                "SKY-PN-ST-CAP-MB")
    };

    // ================================================================
    //  检测方法
    // ================================================================

    /**
     * 根据SK码匹配预设徽章信息
     *
     * @param skCode SK码
     * @return 匹配的PresetBadge, 未匹配返回null
     */
    public static PresetBadge detectBySkCode(String skCode) {
        if (skCode == null || skCode.isEmpty()) return null;
        skCode = skCode.trim().toUpperCase();

        for (int i = 0; i < PRESET_BADGES.length; i++) {
            PresetBadge pb = PRESET_BADGES[i];
            if (pb.skCode != null && pb.skCode.toUpperCase().equals(skCode)) {
                return pb;
            }
        }
        return null;
    }

    /**
     * 根据URL匹配徽章信息
     * 先提取SK码, 再根据SK码匹配预设徽章
     *
     * @param url 徽章URL
     * @return 匹配的PresetBadge, 未匹配返回null
     */
    public static PresetBadge detectByUrl(String url) {
        if (url == null || url.isEmpty()) return null;

        String skCode = SkExtractor.getSkFromLink(url);
        if (skCode == null) {
            Log.w(TAG, "detectByUrl: 无法从URL提取SK码: " + url);
            return null;
        }

        return detectBySkCode(skCode);
    }

    /**
     * 根据徽章ID获取徽章信息
     *
     * @param id 徽章ID
     * @return 匹配的Badge, 未匹配返回null
     */
    public static Badge getBadgeById(String id) {
        if (id == null) return null;
        for (int i = 0; i < BADGES.length; i++) {
            if (BADGES[i].id.equals(id)) {
                return BADGES[i];
            }
        }
        return null;
    }

    /**
     * 根据渠道名获取渠道信息
     *
     * @param channelName 渠道名
     * @return String[]{渠道名, 包名, Activity类名}, 未找到返回null
     */
    public static String[] getChannelByName(String channelName) {
        if (channelName == null) return null;
        for (int i = 0; i < GAME_CHANNELS.length; i++) {
            if (GAME_CHANNELS[i][0].equals(channelName)) {
                return GAME_CHANNELS[i];
            }
        }
        return null;
    }

    /**
     * 获取所有渠道名数组
     */
    public static String[] getChannelNames() {
        String[] names = new String[GAME_CHANNELS.length];
        for (int i = 0; i < GAME_CHANNELS.length; i++) {
            names[i] = GAME_CHANNELS[i][0];
        }
        return names;
    }
}
