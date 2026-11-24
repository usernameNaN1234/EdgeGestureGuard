package io.github.usernameNaN1234.EdgeGestureGuard.xposed;

import io.github.usernameNaN1234.EdgeGestureGuard.common.Logx;
import io.github.usernameNaN1234.EdgeGestureGuard.common.ModuleConfig;
import io.github.usernameNaN1234.EdgeGestureGuard.common.StatusReport;

/** 组装状态报告（App 的 UI 直接展示）。所有异常都被吞掉，只返回尽量完整的信息。 */
final class HookStatus {

    private HookStatus() {
    }

    static String buildJson() {
        try {
            StatusReport st = HookRuntime.status;
            ModuleConfig cfg = HookRuntime.config;
            st.configSummary = cfg == null ? "" : cfg.describe();
            st.zoneConfig = Zones.describe(cfg, Zones.screenHeight());
            st.armed = ArmController.isArmed();
            st.armedSource = ArmController.source();
            st.timestamp = System.currentTimeMillis();
            try {
                st.diag.clear();
                for (String line : HookRuntime.diagSnapshot(120)) {
                    st.diag.add(line);
                }
            } catch (Throwable ignored) {
            }
            return st.toJson();
        } catch (Throwable t) {
            Logx.w("buildJson failed", t);
            return "{}";
        }
    }
}
