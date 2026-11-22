package io.github.usernameNaN1234.EdgeGestureGuard.app;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;

import io.github.usernameNaN1234.EdgeGestureGuard.R;
import io.github.usernameNaN1234.EdgeGestureGuard.common.Logx;
import io.github.usernameNaN1234.EdgeGestureGuard.common.Rule;
import io.github.usernameNaN1234.EdgeGestureGuard.common.RuleStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 应用列表：为每个应用选择要屏蔽哪些手势（左/右返回、顶部下拉、底部上滑）。
 *
 * 视觉：白色圆角卡片行（图标 + 应用名 + 包名 + 规则摘要徽标），点击展开 2x2 手势开关。
 * 语义：这些规则**只在游戏模式开启时生效**；关着时 Hook 端不读规则表。
 */
public final class AppListAdapter extends BaseAdapter {

    public interface Listener {
        void onRulesChanged(String packageName, boolean blockLeft, boolean blockRight,
                            boolean blockBottom, boolean blockTopShade);
    }

    private final Context context;
    private final RuleStore store;
    private final Listener listener;
    private final LayoutInflater inflater;

    private final List<AppEntry> all = new ArrayList<>();
    private final List<AppEntry> visible = new ArrayList<>();
    private final Set<String> expanded = new HashSet<>();

    private String query = "";

    public AppListAdapter(Context context, RuleStore store, Listener listener) {
        Context app = context.getApplicationContext();
        this.context = app == null ? context : app;
        this.store = store;
        this.listener = listener;
        this.inflater = LayoutInflater.from(this.context);
    }

    public void setData(List<AppEntry> data) {
        all.clear();
        if (data != null) {
            all.addAll(data);
        }
        applyFilter();
    }

    public void setQuery(String newQuery) {
        query = newQuery == null ? "" : newQuery.trim().toLowerCase(Locale.ROOT);
        applyFilter();
    }

    private void applyFilter() {
        visible.clear();
        for (AppEntry entry : all) {
            if (query.length() == 0 || entry.searchKey().contains(query)) {
                visible.add(entry);
            }
        }
        notifyDataSetChanged();
    }

    /** 展开 / 收起某一行的 2x2 手势开关。 */
    public void toggle(int position) {
        if (position < 0 || position >= visible.size()) {
            return;
        }
        String pkg = visible.get(position).packageName;
        if (!expanded.remove(pkg)) {
            expanded.add(pkg);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getCount() {
        return visible.size();
    }

    @Override
    public Object getItem(int position) {
        return visible.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        View view = convertView;
        if (view == null) {
            view = inflater.inflate(R.layout.item_app, parent, false);
            holder = new ViewHolder(view);
            view.setTag(holder);
        } else {
            holder = (ViewHolder) view.getTag();
        }

        final AppEntry item = visible.get(position);
        holder.label.setText(item.label);
        holder.packageName.setText(item.packageName);
        holder.icon.setImageDrawable(item.icon);

        Rule rule = store.getPackageRule(item.packageName);
        bindSummary(holder, rule);
        holder.expand.setVisibility(expanded.contains(item.packageName)
                ? View.VISIBLE : View.GONE);

        // 先摘掉监听再 setChecked，避免复用行时触发误写
        holder.left.setOnCheckedChangeListener(null);
        holder.right.setOnCheckedChangeListener(null);
        holder.bottom.setOnCheckedChangeListener(null);
        holder.topShade.setOnCheckedChangeListener(null);

        holder.left.setChecked(rule != null && rule.blockLeft);
        holder.right.setChecked(rule != null && rule.blockRight);
        holder.bottom.setChecked(rule != null && rule.blockBottom);
        holder.topShade.setChecked(rule != null && rule.blockTopShade);

        CompoundButton.OnCheckedChangeListener changeListener =
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                        try {
                            apply(item, holder);
                        } catch (Throwable t) {
                            Logx.w("apply rule failed", t);
                        }
                    }
                };
        holder.left.setOnCheckedChangeListener(changeListener);
        holder.right.setOnCheckedChangeListener(changeListener);
        holder.bottom.setOnCheckedChangeListener(changeListener);
        holder.topShade.setOnCheckedChangeListener(changeListener);

        return view;
    }

    /** 勾选即写盘（四项全不勾 = 删除该应用规则），并通知外层广播。 */
    private void apply(AppEntry item, ViewHolder holder) {
        boolean left = holder.left.isChecked();
        boolean right = holder.right.isChecked();
        boolean bottom = holder.bottom.isChecked();
        boolean top = holder.topShade.isChecked();

        store.setPackageRule(item.packageName, left, right, bottom, top);
        Rule rule = store.getPackageRule(item.packageName);
        bindSummary(holder, rule);

        if (!left && !right && !bottom && !top) {
            expanded.remove(item.packageName);
            holder.expand.setVisibility(View.GONE);
        }
        if (listener != null) {
            listener.onRulesChanged(item.packageName, left, right, bottom, top);
        }
    }

    /** 规则摘要：如「左 右 顶」；没有任何规则时显示「未屏蔽」并切到灰色徽标。 */
    private void bindSummary(ViewHolder holder, Rule rule) {
        boolean configured = rule != null && rule.blocksAnything();
        holder.summary.setText(summary(rule));
        holder.summary.setBackgroundResource(configured
                ? R.drawable.bg_badge_on : R.drawable.bg_badge_off);
        holder.summary.setTextColor(context.getColor(configured
                ? R.color.badge_on_fg : R.color.badge_off_fg));
    }

    private String summary(Rule rule) {
        if (rule == null) {
            return context.getString(R.string.summary_none);
        }
        StringBuilder sb = new StringBuilder();
        if (rule.blockLeft) {
            sb.append("左 ");
        }
        if (rule.blockRight) {
            sb.append("右 ");
        }
        if (rule.blockTopShade) {
            sb.append("顶 ");
        }
        if (rule.blockBottom) {
            sb.append("底 ");
        }
        if (sb.length() == 0) {
            return context.getString(R.string.summary_none);
        }
        return sb.toString().trim();
    }

    private static final class ViewHolder {

        final ImageView icon;
        final TextView label;
        final TextView packageName;
        final TextView summary;
        final View expand;
        final CheckBox left;
        final CheckBox right;
        final CheckBox bottom;
        final CheckBox topShade;

        ViewHolder(View root) {
            icon = root.findViewById(R.id.app_icon);
            label = root.findViewById(R.id.app_label);
            packageName = root.findViewById(R.id.app_package);
            summary = root.findViewById(R.id.app_summary);
            expand = root.findViewById(R.id.app_expand);
            left = root.findViewById(R.id.block_left);
            right = root.findViewById(R.id.block_right);
            bottom = root.findViewById(R.id.block_bottom);
            topShade = root.findViewById(R.id.block_top_shade);
        }
    }
}
