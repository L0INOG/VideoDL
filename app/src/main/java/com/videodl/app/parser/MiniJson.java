package com.videodl.app.parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 【解析·小红书】极简 JSON 解析器（随 xiaohongshu-parser 参考实现引入）。
 *
 * 功能: 解析小红书 SSR 页里的 window.__INITIAL_STATE__——它是 JS 字面量，
 *       除 undefined 外还可能残存未清洗干净的非法 JSON 值，org.json 会直接解析失败，
 *       因此这一份手写解析器做容错（解析失败返回 null 而不是抛异常）。
 * 依赖: 仅 JDK 基础类，无 android 引用，可桌面自测。
 * 说明: 仅 XhsParser 使用；其他平台的 JSON 仍是规整的，继续走 org.json。
 */
public class MiniJson {

    private final Object value;

    private MiniJson(Object v) {
        this.value = v;
    }

    @SuppressWarnings("unchecked")
    public Map<String, MiniJson> asObject() {
        return value instanceof Map ? (Map<String, MiniJson>) value : null;
    }

    @SuppressWarnings("unchecked")
    public List<MiniJson> asArray() {
        return value instanceof List ? (List<MiniJson>) value : null;
    }

    public boolean isArray() {
        return value instanceof List;
    }

    public boolean isObject() {
        return value instanceof Map;
    }

    public int size() {
        List<MiniJson> arr = asArray();
        return arr == null ? 0 : arr.size();
    }

    public MiniJson get(String key) {
        Map<String, MiniJson> obj = asObject();
        if (obj == null) {
            return null;
        }
        return obj.get(key);
    }

    public MiniJson at(int index) {
        List<MiniJson> arr = asArray();
        if (arr == null || index < 0 || index >= arr.size()) {
            return null;
        }
        return arr.get(index);
    }

    public String asString() {
        return value == null ? null : String.valueOf(value);
    }

    public String asString(String key, String def) {
        MiniJson v = get(key);
        if (v == null || v.value == null) {
            return def;
        }
        return String.valueOf(v.value);
    }

    public int asInt(String key, int def) {
        MiniJson v = get(key);
        if (v == null || v.value == null) {
            return def;
        }
        try {
            if (v.value instanceof Number) {
                return ((Number) v.value).intValue();
            }
            return (int) Double.parseDouble(String.valueOf(v.value));
        } catch (Exception e) {
            return def;
        }
    }

    public List<String> asStringList(String key) {
        List<String> out = new ArrayList<>();
        MiniJson node = get(key);
        List<MiniJson> arr = node == null ? null : node.asArray();
        if (arr == null) {
            return out;
        }
        for (MiniJson item : arr) {
            if (item != null && item.value != null) {
                out.add(String.valueOf(item.value));
            }
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════════
    //  [解析·小红书] 手写递归下降解析
    // ═══════════════════════════════════════════════════════════════

    /** 解析 JSON 文本；任何错误都返回 null（容错优先，调用方自行判空）。 */
    public static MiniJson parse(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim();
        if (!s.startsWith("{") && !s.startsWith("[")) {
            return null;
        }
        int[] pos = {0};
        try {
            Object v = readValue(s, pos);
            return new MiniJson(v);
        } catch (Exception e) {
            return null;
        }
    }

    private static Object readValue(String s, int[] p) {
        skipWs(s, p);
        char c = s.charAt(p[0]);
        switch (c) {
            case '{': return readObject(s, p);
            case '[': return readArray(s, p);
            case '"': return readString(s, p);
            case 't':
                expect(s, p, "true");
                return Boolean.TRUE;
            case 'f':
                expect(s, p, "false");
                return Boolean.FALSE;
            case 'n':
                expect(s, p, "null");
                return null;
            default: return readNumber(s, p);
        }
    }

    private static Map<String, MiniJson> readObject(String s, int[] p) {
        Map<String, MiniJson> map = new HashMap<>();
        p[0]++; // {
        skipWs(s, p);
        if (s.charAt(p[0]) == '}') {
            p[0]++;
            return map;
        }
        while (p[0] < s.length()) {
            skipWs(s, p);
            String key = readString(s, p);
            skipWs(s, p);
            if (s.charAt(p[0]) == ':') {
                p[0]++;
            }
            skipWs(s, p);
            map.put(key, new MiniJson(readValue(s, p)));
            skipWs(s, p);
            char ch = s.charAt(p[0]);
            if (ch == ',') {
                p[0]++;
                continue;
            }
            if (ch == '}') {
                p[0]++;
                break;
            }
            p[0]++;
        }
        return map;
    }

    private static List<MiniJson> readArray(String s, int[] p) {
        List<MiniJson> list = new ArrayList<>();
        p[0]++; // [
        skipWs(s, p);
        if (p[0] < s.length() && s.charAt(p[0]) == ']') {
            p[0]++;
            return list;
        }
        while (p[0] < s.length()) {
            skipWs(s, p);
            list.add(new MiniJson(readValue(s, p)));
            skipWs(s, p);
            char ch = s.charAt(p[0]);
            if (ch == ',') {
                p[0]++;
                continue;
            }
            if (ch == ']') {
                p[0]++;
                break;
            }
            p[0]++;
        }
        return list;
    }

    private static String readString(String s, int[] p) {
        StringBuilder sb = new StringBuilder();
        p[0]++; // 开引号
        while (p[0] < s.length()) {
            char c = s.charAt(p[0]++);
            if (c == '"') {
                break;
            }
            if (c == '\\') {
                char n = s.charAt(p[0]++);
                switch (n) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (p[0] + 4 <= s.length()) {
                            sb.append((char) Integer.parseInt(s.substring(p[0], p[0] + 4), 16));
                            p[0] += 4;
                        }
                        break;
                    default: sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static Object readNumber(String s, int[] p) {
        int start = p[0];
        while (p[0] < s.length() && "+-0123456789.eE".indexOf(s.charAt(p[0])) >= 0) {
            p[0]++;
        }
        String num = s.substring(start, p[0]);
        try {
            if (num.contains(".") || num.contains("e") || num.contains("E")) {
                return Double.valueOf(num);
            }
            long l = Long.parseLong(num);
            if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                return (int) l;
            }
            return l;
        } catch (Exception e) {
            return num;
        }
    }

    /** 只推进游标（容错：非法字面量当 null 处理，不抛异常）。 */
    private static void expect(String s, int[] p, String expect) {
        p[0] += expect.length();
    }

    private static void skipWs(String s, int[] p) {
        while (p[0] < s.length()) {
            char c = s.charAt(p[0]);
            if (c == ' ' || c == '\n' || c == '\t' || c == '\r') {
                p[0]++;
            } else {
                break;
            }
        }
    }
}
