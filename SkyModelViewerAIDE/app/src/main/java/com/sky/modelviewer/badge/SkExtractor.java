package com.sky.modelviewer.badge;

import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;

/**
 * SK码 / STAR徽章URL 提取工具
 *
 * STAR徽章URL格式: https://sky.thatgamecompany.com/u?s=<Base64-URL>
 * 其中 s 参数为 Base64 编码的内部URL, 内部URL中包含 SK 码
 *
 * SK码格式: SK-XXXXX-XXXXX (字母数字)
 */
public class SkExtractor {

    private static final String TAG = "SkExtractor";

    /** STAR徽章入口URL前缀 */
    private static final String STAR_BASE = "https://sky.thatgamecompany.com/u?s=";

    /** SK码前缀（支持SK-和SKY-两种格式） */
    private static final String SK_PREFIX = "SK-";
    private static final String SKY_PREFIX = "SKY-";

    /** 内部URL模板 (用于buildLinkFromSk) */
    private static final String INNER_URL_TEMPLATE =
            "https://sky-children-of-light.thatgamecompany.com/star/%s";

    // ================================================================
    //  公开方法
    // ================================================================

    /**
     * 从链接中提取SK码
     *
     * 支持以下输入:
     *  1. STAR徽章URL: https://sky.thatgamecompany.com/u?s=<Base64>
     *  2. 直接包含SK码的URL
     *  3. 纯SK码文本
     *
     * @param link 输入链接或文本
     * @return SK码(如 SK-ABCDE-FGHIJ), 提取失败返回null
     */
    public static String getSkFromLink(String link) {
        if (link == null || link.trim().isEmpty()) {
            return null;
        }
        link = link.trim();

        // 情况1: 直接就是SK码 (支持SK-和SKY-前缀)
        if (link.toUpperCase().startsWith(SK_PREFIX) || link.toUpperCase().startsWith(SKY_PREFIX)) {
            String sk = cleanSkCode(link);
            if (sk != null) return sk;
        }

        // 情况2: STAR徽章URL, 需要Base64解码
        if (isStarBadgeUrl(link)) {
            String sParam = extractSParam(link);
            if (sParam != null && !sParam.isEmpty()) {
                // 尝试Base64解码
                String decoded = tryBase64Decode(sParam);
                if (decoded != null) {
                    // 从解码后的文本中提取SK码
                    String sk = extractSkFromText(decoded);
                    if (sk != null) return sk;

                    // 解码后可能是URL, 从URL中再提取
                    sk = extractSkFromUrl(decoded);
                    if (sk != null) return sk;

                    // 解码后可能是查询参数, 从sk=参数中提取
                    sk = extractSkFromQuery(decoded);
                    if (sk != null) return sk;
                }

                // s参数本身可能就是URL编码的查询参数
                String sk = extractSkFromQuery(sParam);
                if (sk != null) return sk;
                sk = extractSkFromText(sParam);
                if (sk != null) return sk;
            }
        }

        // 情况3: 普通URL中包含SK码
        String sk = extractSkFromUrl(link);
        if (sk != null) return sk;

        // 情况4: 文本中包含SK码
        sk = extractSkFromText(link);
        if (sk != null) return sk;

        return null;
    }

    /**
     * 根据SK码构建STAR徽章URL
     *
     * @param sk SK码(如 SK-ABCDE-FGHIJ)
     * @return 完整STAR徽章URL, 失败返回null
     */
    public static String buildLinkFromSk(String sk) {
        if (sk == null || sk.trim().isEmpty()) {
            return null;
        }
        sk = sk.trim().toUpperCase();

        // 确保SK码格式正确（支持SK-和SKY-前缀）
        if (!sk.startsWith(SKY_PREFIX) && !sk.startsWith(SK_PREFIX)) {
            sk = SKY_PREFIX + sk;
        }

        try {
            String innerUrl = String.format(INNER_URL_TEMPLATE, sk);
            String encoded = Base64.encodeToString(
                    innerUrl.getBytes("UTF-8"),
                    Base64.URL_SAFE | Base64.NO_WRAP);
            return STAR_BASE + encoded;
        } catch (UnsupportedEncodingException e) {
            Log.e(TAG, "buildLinkFromSk encoding error", e);
            return null;
        }
    }

    /**
     * 判断URL是否为STAR徽章URL
     *
     * @param url 待检测URL
     * @return true表示是STAR徽章URL
     */
    public static boolean isStarBadgeUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("sky.thatgamecompany.com/u")
                || lower.contains("sky.thatg.co")
                || lower.contains("thatgamecompany.com")
                || (lower.contains("?s=") && (lower.contains("thatg") || lower.contains("sky")));
    }

    // ================================================================
    //  内部方法
    // ================================================================

    /**
     * 从URL中提取 s 参数值
     */
    private static String extractSParam(String url) {
        try {
            Uri uri = Uri.parse(url);
            String s = uri.getQueryParameter("s");
            if (s != null && !s.isEmpty()) {
                return s;
            }
        } catch (Exception e) {
            Log.w(TAG, "Uri.parse failed, fallback to manual extraction");
        }

        // 手动提取
        int idx;
        if (url.contains("?s=")) {
            idx = url.indexOf("?s=");
        } else if (url.contains("&s=")) {
            idx = url.indexOf("&s=");
        } else {
            // 纯Base64字符串
            if (url.startsWith(STAR_BASE)) {
                return url.substring(STAR_BASE.length());
            }
            return null;
        }

        String after = url.substring(idx + 3);
        // 截断到下一个参数
        int amp = after.indexOf('&');
        if (amp >= 0) {
            after = after.substring(0, amp);
        }
        // 截断到 #
        int hash = after.indexOf('#');
        if (hash >= 0) {
            after = after.substring(0, hash);
        }

        try {
            return URLDecoder.decode(after, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return after;
        }
    }

    /**
     * 尝试Base64解码
     */
    private static String tryBase64Decode(String input) {
        if (input == null || input.isEmpty()) return null;

        // 移除可能的padding和空白
        String cleaned = input.trim().replaceAll("\\s", "");

        // 尝试URL_SAFE解码
        try {
            byte[] decoded = Base64.decode(cleaned, Base64.URL_SAFE | Base64.NO_WRAP);
            String result = new String(decoded, "UTF-8");
            if (isValidDecodedContent(result)) return result;
        } catch (Exception e) {
            // 忽略
        }

        // 尝试标准Base64解码
        try {
            byte[] decoded = Base64.decode(cleaned, Base64.NO_WRAP);
            String result = new String(decoded, "UTF-8");
            if (isValidDecodedContent(result)) return result;
        } catch (Exception e) {
            // 忽略
        }

        // 尝试DEFAULT解码(兼容带换行的情况)
        try {
            byte[] decoded = Base64.decode(cleaned, Base64.DEFAULT);
            String result = new String(decoded, "UTF-8");
            if (isValidDecodedContent(result)) return result;
        } catch (Exception e) {
            // 忽略
        }

        return null;
    }

    /** 检查解码后的内容是否包含有效的徽章数据特征 */
    private static boolean isValidDecodedContent(String result) {
        if (result == null || result.isEmpty()) return false;
        String lower = result.toLowerCase();
        return lower.contains("http")
                || lower.contains("sk-")
                || lower.contains("sky")
                || lower.contains("sk=")
                || lower.contains("thatg");
    }

    /**
     * 从查询参数字符串中提取sk=参数的值
     * 例如: "j=xxx&s=xxx&sk=SKY-PN-SU-CAP-MB&t=xxx" → "SKY-PN-SU-CAP-MB"
     */
    private static String extractSkFromQuery(String query) {
        if (query == null) return null;

        // 尝试URL解码
        try {
            query = URLDecoder.decode(query, "UTF-8");
        } catch (Exception e) {
            // 忽略, 用原始字符串
        }

        // 查找 sk= 参数
        String lower = query.toLowerCase();
        int idx = lower.indexOf("sk=");
        if (idx < 0) return null;

        // 跳过 "sk="
        int start = idx + 3;
        int end = query.indexOf('&', start);
        if (end < 0) end = query.indexOf('#', start);
        if (end < 0) end = query.length();

        String skValue = query.substring(start, end).trim();
        if (skValue.isEmpty()) return null;

        // 验证是否为有效的SK码
        return cleanSkCode(skValue);
    }

    /**
     * 从URL路径中提取SK码
     */
    private static String extractSkFromUrl(String url) {
        if (url == null) return null;

        // SK码通常在URL路径的最后一段
        int idx = url.toUpperCase().indexOf(SKY_PREFIX);
        if (idx < 0) idx = url.toUpperCase().indexOf(SK_PREFIX);
        if (idx < 0) return null;

        return extractSkFromText(url.substring(idx));
    }

    /**
     * 从文本中提取SK码
     * SK码格式: SK- 后跟若干段, 每段为字母或数字, 段间用 - 分隔
     */
    private static String extractSkFromText(String text) {
        if (text == null) return null;

        // 先找 SKY- 前缀，再找 SK- 前缀
        int idx = text.toUpperCase().indexOf(SKY_PREFIX);
        if (idx < 0) idx = text.toUpperCase().indexOf(SK_PREFIX);
        if (idx < 0) return null;

        StringBuilder sb = new StringBuilder();
        int i = idx;
        boolean started = false;
        int segmentCount = 0;

        // 检查是 SKY- 还是 SK- 前缀
        String upToCheck = text.substring(idx).toUpperCase();
        while (i < text.length()) {
            char c = text.charAt(i);

            if (!started && i == idx) {
                // 检查 SKY- 前缀
                if (upToCheck.startsWith(SKY_PREFIX) && i + 3 < text.length()
                        && Character.toUpperCase(text.charAt(i + 1)) == 'S'
                        && Character.toUpperCase(text.charAt(i + 2)) == 'K'
                        && Character.toUpperCase(text.charAt(i + 3)) == 'Y'
                        && text.charAt(i + 4) == '-') {
                    sb.append("SKY-");
                    i += 5;
                    started = true;
                    segmentCount = 1;
                    continue;
                }
                // 检查 SK- 前缀
                if (i + 2 < text.length()
                        && Character.toUpperCase(text.charAt(i + 1)) == 'K'
                        && text.charAt(i + 2) == '-') {
                    sb.append("SK-");
                    i += 3;
                    started = true;
                    segmentCount = 1;
                    continue;
                }
                break;
            }

            if (!started) break;

            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            } else if (c == '-') {
                // 检查后面是否还有字母数字(新的段)
                if (i + 1 < text.length()
                        && Character.isLetterOrDigit(text.charAt(i + 1))) {
                    sb.append('-');
                    segmentCount++;
                    // SK码通常2-4段, 防止无限匹配
                    if (segmentCount > 5) break;
                } else {
                    break;
                }
            } else {
                break;
            }

            i++;
        }

        return cleanSkCode(sb.toString());
    }

    /**
     * 清理并验证SK码
     * 最小格式: SK-X-Y (至少两段, 每段至少1字符)
     */
    private static String cleanSkCode(String sk) {
        if (sk == null) return null;
        sk = sk.trim().toUpperCase();

        // 支持 SK- 和 SKY- 前缀
        boolean isSky = sk.startsWith(SKY_PREFIX);
        boolean isSk = sk.startsWith(SK_PREFIX);
        if (!isSky && !isSk) return null;

        // 移除尾部多余字符
        while (sk.endsWith("-") || sk.endsWith(".")) {
            sk = sk.substring(0, sk.length() - 1);
        }

        // 验证: 至少 SKY-X-Y 或 SK-X-Y 格式
        String prefix = isSky ? SKY_PREFIX : SK_PREFIX;
        String body = sk.substring(prefix.length());
        if (body.isEmpty()) return null;

        String[] parts = body.split("-");
        if (parts.length < 2) return null;

        for (String part : parts) {
            if (part.isEmpty()) return null;
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (!Character.isLetterOrDigit(c)) return null;
            }
        }

        return sk;
    }
}
