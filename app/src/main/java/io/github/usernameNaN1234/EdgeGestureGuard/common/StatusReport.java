package io.github.usernameNaN1234.EdgeGestureGuard.common;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Hook 进程 -> App 的状态报告（UI 直接展示）。 */
public final class StatusReport {

    public static final String BOTTOM_ATTACHED = "attached";
    public static final String BOTTOM_UNSUPPORTED = "unsupported";
    public static final String BOTTOM_UNKNOWN = "unknown";

    public static final String SHADE_ATTACHED = "attached";
    public static final String SHADE_UNSUPPORTED = "unsupported";
    public static final String SHADE_UNKNOWN = "unknown";

    public boolean edgeHooked;
    public String edgeTarget = "";
    public String edgeMetrics = "";
    public String bottomState = BOTTOM_UNKNOWN;
    public String bottomTarget = "";
    public String shadeState = SHADE_UNKNOWN;
    public String shadeTarget = "";
    /** 几何摘要：屏幕高度 / 顶部区 / 底部区像素 */
    public String zoneConfig = "";
    /** 当前生效的全局手势配置摘要 */
    public String configSummary = "";
    /** 游戏模式是否开启 */
    public boolean armed;
    /** 谁打开的（tile / notification / app / …） */
    public String armedSource = "";
    public long timestamp;
    public String moduleVersion = "1.9.1";
    public final List<String> notes = new ArrayList<>();
    /** Hook 进程的诊断日志尾部 */
    public final List<String> diag = new ArrayList<>();

    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("edgeHooked", edgeHooked);
            o.put("edgeTarget", safe(edgeTarget));
            o.put("edgeMetrics", safe(edgeMetrics));
            o.put("bottomState", bottomState == null ? BOTTOM_UNKNOWN : bottomState);
            o.put("bottomTarget", safe(bottomTarget));
            o.put("shadeState", shadeState == null ? SHADE_UNKNOWN : shadeState);
            o.put("shadeTarget", safe(shadeTarget));
            o.put("zoneConfig", safe(zoneConfig));
            o.put("configSummary", safe(configSummary));
            o.put("armed", armed);
            o.put("armedSource", safe(armedSource));
            o.put("timestamp", timestamp);
            o.put("moduleVersion", moduleVersion == null ? "1.9.1" : moduleVersion);
            JSONArray arr = new JSONArray();
            for (String n : notes) {
                arr.put(n);
            }
            o.put("notes", arr);
            JSONArray diagArr = new JSONArray();
            for (String d : diag) {
                diagArr.put(d);
            }
            o.put("diag", diagArr);
            return o.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    public static StatusReport fromJson(String json) {
        StatusReport r = new StatusReport();
        if (json == null || json.length() == 0) {
            return r;
        }
        try {
            JSONObject o = new JSONObject(json);
            r.edgeHooked = o.optBoolean("edgeHooked", false);
            r.edgeTarget = o.optString("edgeTarget", "");
            r.edgeMetrics = o.optString("edgeMetrics", "");
            r.bottomState = o.optString("bottomState", BOTTOM_UNKNOWN);
            r.bottomTarget = o.optString("bottomTarget", "");
            r.shadeState = o.optString("shadeState", SHADE_UNKNOWN);
            r.shadeTarget = o.optString("shadeTarget", "");
            r.zoneConfig = o.optString("zoneConfig", "");
            r.configSummary = o.optString("configSummary", "");
            r.armed = o.optBoolean("armed", false);
            r.armedSource = o.optString("armedSource", "");
            r.timestamp = o.optLong("timestamp", 0L);
            r.moduleVersion = o.optString("moduleVersion", "1.9.1");
            JSONArray arr = o.optJSONArray("notes");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String n = arr.optString(i, "");
                    if (n != null && n.length() > 0) {
                        r.notes.add(n);
                    }
                }
            }
            JSONArray diagArr = o.optJSONArray("diag");
            if (diagArr != null) {
                for (int i = 0; i < diagArr.length(); i++) {
                    String d = diagArr.optString(i, "");
                    if (d != null && d.length() > 0) {
                        r.diag.add(d);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return r;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public boolean bottomUsable() {
        return BOTTOM_ATTACHED.equals(bottomState);
    }

    public boolean shadeUsable() {
        return SHADE_ATTACHED.equals(shadeState);
    }
}
