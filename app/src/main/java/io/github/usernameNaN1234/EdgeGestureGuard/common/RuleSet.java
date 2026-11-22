package io.github.usernameNaN1234.EdgeGestureGuard.common;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 不可变规则表 + 全局配置（整体替换，便于在触摸回调里无锁读取）。
 * Hook 进程只读取，App 进程只整体写入。
 *
 * 线上格式（version 2）：
 * <pre>
 * {"version":2,
 *  "config":{"top":0.12,"bottom":0.12,"verify":false,"launcher":false},
 *  "rules":[{"package":"com.x","left":true,"right":true,"bottom":false,"top":true}]}
 * </pre>
 * version 1（没有 config）照常解析：config 取默认值。
 * 未知字段一律忽略，因此新老 App / Hook 混装也不会崩。
 */
public final class RuleSet {

    public static final int FORMAT_VERSION = 2;

    /** 屏蔽位掩码：一次 hot-path 判定只需要一个 int，不产生任何对象。 */
    public static final int MASK_LEFT = 1;
    public static final int MASK_RIGHT = 2;
    public static final int MASK_BOTTOM = 4;
    public static final int MASK_SHADE = 8;
    public static final RuleSet EMPTY = new RuleSet(Collections.<String, Rule>emptyMap(),
            ModuleConfig.DEFAULT);

    private final Map<String, Rule> byKey;
    private final ModuleConfig config;

    private RuleSet(Map<String, Rule> byKey, ModuleConfig config) {
        this.byKey = byKey;
        this.config = config == null ? ModuleConfig.DEFAULT : config;
    }

    public static RuleSet of(List<Rule> rules) {
        return of(rules, ModuleConfig.DEFAULT);
    }

    public static RuleSet of(List<Rule> rules, ModuleConfig config) {
        Map<String, Rule> map = new LinkedHashMap<>();
        if (rules != null) {
            for (Rule r : rules) {
                if (r == null || r.packageName.length() == 0 || !r.blocksAnything()) {
                    continue;
                }
                map.put(r.key(), r);
            }
        }
        return new RuleSet(Collections.unmodifiableMap(map), config);
    }

    public ModuleConfig config() {
        return config;
    }

    /** 保留全部规则，只换配置（App 侧改阈值/验证模式用）。 */
    public RuleSet withConfig(ModuleConfig newConfig) {
        return new RuleSet(byKey, newConfig);
    }

    public int size() {
        return byKey.size();
    }

    public List<Rule> all() {
        return new ArrayList<>(byKey.values());
    }

    public List<String> packageNames() {
        List<String> out = new ArrayList<>();
        for (Rule r : byKey.values()) {
            if (!out.contains(r.packageName)) {
                out.add(r.packageName);
            }
        }
        return out;
    }

    public Rule find(String packageName, String activityName) {
        if (packageName == null || packageName.length() == 0) {
            return null;
        }
        if (activityName != null) {
            Rule exact = byKey.get(Rule.key(packageName, activityName));
            if (exact != null) {
                return exact;
            }
        }
        return byKey.get(Rule.key(packageName, null));
    }

    /**
     * 决策入口：Activity 级规则优先，其次包级规则，都没有 -> 0（不屏蔽，安全默认）。
     *
     * 返回 int 位掩码而不是对象：前台变化时才调用一次，结果缓存在 HookRuntime 里，
     * 触摸热路径只读一个 volatile int。
     */
    public int maskFor(String packageName, String activityName) {
        Rule r = find(packageName, activityName);
        if (r == null) {
            return 0;
        }
        int mask = 0;
        if (r.blockLeft) {
            mask |= MASK_LEFT;
        }
        if (r.blockRight) {
            mask |= MASK_RIGHT;
        }
        if (r.blockBottom) {
            mask |= MASK_BOTTOM;
        }
        if (r.blockTopShade) {
            mask |= MASK_SHADE;
        }
        return mask;
    }

    public String toJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("version", FORMAT_VERSION);
            config.writeTo(root);
            JSONArray arr = new JSONArray();
            for (Rule r : byKey.values()) {
                try {
                    arr.put(r.toJson());
                } catch (JSONException ignored) {
                    // 单条失败不影响整表
                }
            }
            root.put("rules", arr);
            return root.toString();
        } catch (Throwable t) {
            Logx.w("RuleSet.toJson failed", t);
            return "{}";
        }
    }

    /** 容错解析：任何异常都返回 EMPTY（= 完全不屏蔽）。 */
    public static RuleSet fromJson(String json) {
        if (json == null || json.length() == 0) {
            return EMPTY;
        }
        try {
            JSONObject root = new JSONObject(json);
            ModuleConfig config = ModuleConfig.fromJson(root.optJSONObject("config"));
            JSONArray arr = root.optJSONArray("rules");
            if (arr == null) {
                return new RuleSet(Collections.<String, Rule>emptyMap(), config);
            }
            List<Rule> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                Rule r = Rule.fromJson(arr.optJSONObject(i));
                if (r != null) {
                    list.add(r);
                }
            }
            return of(list, config);
        } catch (Throwable t) {
            Logx.w("RuleSet.fromJson failed", t);
            return EMPTY;
        }
    }

    @Override
    public String toString() {
        return "RuleSet(" + byKey.size() + ", " + config.describe() + ")";
    }
}
