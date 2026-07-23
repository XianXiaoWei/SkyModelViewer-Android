package com.sky.modelviewer.badge;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.os.Build;
import android.os.Parcelable;
import android.util.Log;

import java.io.UnsupportedEncodingException;
import java.util.Arrays;

/**
 * NFC标签读取辅助类
 *
 * 功能:
 *  - 前台调度 (enableForegroundDispatch / disableForegroundDispatch)
 *  - NDEF消息解析 (parseNdefFromIntent)
 *  - Tag直接读取
 *
 * AIDE兼容注意事项:
 *  - 不使用 Build.VERSION_CODES.S (用数字31代替)
 *  - 不使用 PendingIntent.FLAG_MUTABLE (用0x02000000代替)
 *  - 无lambda, 使用匿名内部类
 */
public class NfcHelper {

    private static final String TAG = "NfcHelper";

    private NfcAdapter nfcAdapter;
    private PendingIntent pendingIntent;
    private IntentFilter[] intentFilters;
    private String[][] techLists;

    /**
     * 初始化NFC适配器
     *
     * @param context 上下文 (通常为Activity)
     * @return true表示设备支持NFC且初始化成功
     */
    public boolean init(Context context) {
        nfcAdapter = NfcAdapter.getDefaultAdapter(context);
        if (nfcAdapter == null) {
            Log.w(TAG, "设备不支持NFC");
            return false;
        }

        // 构建PendingIntent
        // 注意: AIDE旧版SDK不支持 PendingIntent.FLAG_MUTABLE, 用0x02000000代替
        // Android 12 (API 31) 要求指定mutability标志
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) { // 31 = Build.VERSION_CODES.S
            flags = PendingIntent.FLAG_UPDATE_CURRENT | 0x02000000; // 0x02000000 = FLAG_MUTABLE
        }

        Intent intent = new Intent(context, context.getClass());
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        pendingIntent = PendingIntent.getActivity(context, 0, intent, flags);

        // 意图过滤器
        intentFilters = new IntentFilter[] {
            new IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED),
            new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED),
            new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
        };

        // 技术列表
        techLists = new String[][] {
            new String[] { Ndef.class.getName() },
            new String[] { NdefFormatable.class.getName() }
        };

        return true;
    }

    /**
     * 启用前台调度
     * 应在Activity的onResume()中调用
     */
    public void enableForegroundDispatch(Activity activity) {
        if (nfcAdapter == null) {
            Log.w(TAG, "NFC适配器未初始化");
            return;
        }
        if (!nfcAdapter.isEnabled()) {
            Log.w(TAG, "NFC未开启");
            return;
        }
        try {
            nfcAdapter.enableForegroundDispatch(activity, pendingIntent, intentFilters, techLists);
        } catch (Exception e) {
            Log.e(TAG, "enableForegroundDispatch失败", e);
        }
    }

    /**
     * 禁用前台调度
     * 应在Activity的onPause()中调用
     */
    public void disableForegroundDispatch(Activity activity) {
        if (nfcAdapter == null) return;
        try {
            nfcAdapter.disableForegroundDispatch(activity);
        } catch (Exception e) {
            Log.e(TAG, "disableForegroundDispatch失败", e);
        }
    }

    /**
     * 设备是否支持NFC
     */
    public boolean isNfcSupported() {
        return nfcAdapter != null;
    }

    /**
     * NFC是否已开启
     */
    public boolean isNfcEnabled() {
        return nfcAdapter != null && nfcAdapter.isEnabled();
    }

    // ================================================================
    //  NDEF解析 (静态方法)
    // ================================================================

    /**
     * 从Intent中解析NDEF数据
     *
     * 识别NFC标签发现Intent (ACTION_NDEF_DISCOVERED / ACTION_TECH_DISCOVERED / ACTION_TAG_DISCOVERED)
     * 提取其中的URI或文本内容
     *
     * @param intent Activity onNewIntent收到的Intent
     * @return 解析出的内容(URI或文本), 无法解析返回null
     */
    public static String parseNdefFromIntent(Intent intent) {
        if (intent == null) return null;

        String action = intent.getAction();
        if (action == null) return null;

        if (!NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)
                && !NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)
                && !NfcAdapter.ACTION_TAG_DISCOVERED.equals(action)) {
            return null;
        }

        // 方式1: 从EXTRA_NDEF_MESSAGES中提取
        Parcelable[] rawMsgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
        if (rawMsgs != null && rawMsgs.length > 0) {
            for (int i = 0; i < rawMsgs.length; i++) {
                NdefMessage msg = (NdefMessage) rawMsgs[i];
                String result = parseNdefMessage(msg);
                if (result != null) return result;
            }
        }

        // 方式2: 直接读取Tag
        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (tag != null) {
            String result = readTag(tag);
            if (result != null) return result;
        }

        return null;
    }

    /**
     * 解析NdefMessage中的所有Record
     */
    private static String parseNdefMessage(NdefMessage msg) {
        if (msg == null) return null;
        NdefRecord[] records = msg.getRecords();
        if (records == null) return null;

        for (int i = 0; i < records.length; i++) {
            String result = parseNdefRecord(records[i]);
            if (result != null) return result;
        }
        return null;
    }

    /**
     * 解析单个NdefRecord
     * 支持URI记录、文本记录、绝对URI记录
     */
    private static String parseNdefRecord(NdefRecord record) {
        if (record == null) return null;

        short tnf = record.getTnf();
        byte[] type = record.getType();
        byte[] payload = record.getPayload();

        if (payload == null || payload.length == 0) return null;

        // TNF_WELL_KNOWN + RTD_URI
        if (tnf == NdefRecord.TNF_WELL_KNOWN
                && Arrays.equals(type, NdefRecord.RTD_URI)) {
            return parseUriRecord(payload);
        }

        // TNF_WELL_KNOWN + RTD_TEXT
        if (tnf == NdefRecord.TNF_WELL_KNOWN
                && Arrays.equals(type, NdefRecord.RTD_TEXT)) {
            return parseTextRecord(payload);
        }

        // TNF_ABSOLUTE_URI
        if (tnf == NdefRecord.TNF_ABSOLUTE_URI) {
            try {
                String uri = new String(payload, "UTF-8");
                return uri;
            } catch (UnsupportedEncodingException e) {
                return new String(payload);
            }
        }

        // TNF_EXTERNAL_TYPE(0x04) / TNF_UNKNOWN(0x05): 尝试当作文本/URL
        if (tnf == 0x04 || tnf == 0x05) {
            String text = safeString(payload);
            if (text != null && (text.contains("http") || text.contains("SK-") || text.contains("sky"))) {
                return text;
            }
        }

        // Smart Poster (包含URI+文本)
        if (tnf == NdefRecord.TNF_WELL_KNOWN
                && Arrays.equals(type, NdefRecord.RTD_SMART_POSTER)) {
            try {
                NdefMessage smartPoster = new NdefMessage(payload);
                return parseNdefMessage(smartPoster);
            } catch (Exception e) {
                // 忽略
            }
        }

        return null;
    }

    /**
     * 解析URI Record
     * payload[0] = URI前缀代码, payload[1:] = URI主体
     */
    private static String parseUriRecord(byte[] payload) {
        if (payload == null || payload.length < 1) return null;

        int prefixCode = payload[0] & 0xFF;
        String prefix = getUriPrefix(prefixCode);
        String body;
        try {
            body = new String(payload, 1, payload.length - 1, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            body = new String(payload, 1, payload.length - 1);
        }
        return prefix + body;
    }

    /**
     * 解析Text Record
     * payload[0] = 状态字节(含编码标志), payload[1] = 语言码长度, payload[2+langLen:] = 文本
     */
    private static String parseTextRecord(byte[] payload) {
        if (payload == null || payload.length < 2) return null;

        int status = payload[0] & 0xFF;
        int langLen = status & 0x3F;
        // bit 6: 0=UTF-8, 1=UTF-16
        boolean utf16 = (status & 0x40) != 0;

        int textStart = 1 + langLen;
        if (textStart >= payload.length) return null;

        String charset = utf16 ? "UTF-16" : "UTF-8";
        try {
            return new String(payload, textStart, payload.length - textStart, charset);
        } catch (UnsupportedEncodingException e) {
            return new String(payload, textStart, payload.length - textStart);
        }
    }

    /**
     * URI前缀代码表 (NFC Forum URI Record Type Definition)
     */
    private static String getUriPrefix(int code) {
        switch (code) {
            case 0:  return "";
            case 1:  return "http://www.";
            case 2:  return "https://www.";
            case 3:  return "http://";
            case 4:  return "https://";
            case 5:  return "tel:";
            case 6:  return "mailto:";
            case 7:  return "ftp://anonymous:anonymous@";
            case 8:  return "ftp://ftp.";
            case 9:  return "ftps://";
            case 10: return "sftp://";
            case 11: return "smb://";
            case 12: return "nfs://";
            case 13: return "ftp://";
            case 14: return "dav://";
            case 15: return "news:";
            case 16: return "telnet://";
            case 17: return "imap:";
            case 18: return "rtsp://";
            case 19: return "urn:";
            case 20: return "pop:";
            case 21: return "sip:";
            case 22: return "sips:";
            case 23: return "tftp:";
            case 24: return "btspp://";
            case 25: return "btl2cap://";
            case 26: return "btgoep://";
            case 27: return "tcpobex://";
            case 28: return "irdaobex://";
            case 29: return "file://";
            case 30: return "urn:epc:id:";
            case 31: return "urn:epc:tag:";
            case 32: return "urn:epc:pat:";
            case 33: return "urn:epc:raw:";
            case 34: return "urn:epc:";
            case 35: return "urn:nfc:";
            default: return "";
        }
    }

    /**
     * 直接从Tag读取NDEF数据
     */
    private static String readTag(Tag tag) {
        if (tag == null) return null;

        // 尝试Ndef
        Ndef ndef = Ndef.get(tag);
        if (ndef != null) {
            try {
                ndef.connect();
                NdefMessage msg = ndef.getNdefMessage();
                ndef.close();
                if (msg != null) {
                    return parseNdefMessage(msg);
                }
            } catch (Exception e) {
                Log.e(TAG, "readTag (Ndef) 失败", e);
                try {
                    ndef.close();
                } catch (Exception ex) {
                    // 忽略
                }
            }
        }

        // NdefFormatable标签是未格式化的NFC标签, 不包含NDEF消息, 无法读取
        // 如果需要写入, 可在此处添加格式化逻辑

        return null;
    }

    /**
     * 安全地将byte[]转为String
     */
    private static String safeString(byte[] data) {
        if (data == null) return null;
        try {
            String s = new String(data, "UTF-8");
            // 过滤不可打印字符
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c >= 32 && c < 127 || c == '\n' || c == '\r' || c == '\t'
                        || (c >= 0x4E00 && c <= 0x9FFF)) { // 允许中文
                    sb.append(c);
                }
            }
            String result = sb.toString().trim();
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            return null;
        }
    }
}
