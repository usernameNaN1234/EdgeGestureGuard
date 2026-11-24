package io.github.usernameNaN1234.EdgeGestureGuard.common;

import android.content.Context;
import android.content.SharedPreferences;

/** App 侧配置持久化（SharedPreferences 里存一份 JSON）。 */
public final class ConfigStore {

    private final SharedPreferences sp;

    public ConfigStore(Context context) {
        Context c = context.getApplicationContext();
        if (c == null) {
            c = context;
        }
        this.sp = c.getSharedPreferences(ModuleContract.PREFS_CONFIG, Context.MODE_PRIVATE);
    }

    public ModuleConfig load() {
        try {
            return ModuleConfig.fromJson(sp.getString(ModuleContract.PREF_CONFIG_JSON, ""));
        } catch (Throwable t) {
            Logx.w("ConfigStore.load failed", t);
            return ModuleConfig.DEFAULT;
        }
    }

    public void save(ModuleConfig config) {
        try {
            ModuleConfig value = config == null ? ModuleConfig.DEFAULT : config;
            sp.edit().putString(ModuleContract.PREF_CONFIG_JSON, value.toJson().toString()).apply();
        } catch (Throwable t) {
            Logx.w("ConfigStore.save failed", t);
        }
    }
}
