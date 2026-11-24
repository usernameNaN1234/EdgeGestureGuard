package io.github.usernameNaN1234.EdgeGestureGuard.common;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Process;

/**
 * 配置兜底通道：SystemUI / 桌面进程可以直接 query 最新配置（广播还没到时用）。
 * 只读，且只对可信调用方开放。
 */
public final class ConfigProvider extends ContentProvider {

    public static final String COLUMN_JSON = "json";

    private static final int SHELL_UID = 2000;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        try {
            Context ctx = getContext();
            if (ctx == null) {
                return null;
            }
            int uid = Binder.getCallingUid();
            if (!isTrustedCaller(ctx, uid)) {
                Logx.w("provider: rejected caller uid=" + uid);
                return null;
            }
            String json = new ConfigStore(ctx).load().toJson().toString();
            MatrixCursor cursor = new MatrixCursor(new String[]{COLUMN_JSON}, 1);
            cursor.addRow(new Object[]{json});
            return cursor;
        } catch (Throwable t) {
            Logx.w("provider query failed", t);
            return null;
        }
    }

    private static boolean isTrustedCaller(Context ctx, int uid) {
        try {
            if (uid == Process.myUid() || uid == Process.SYSTEM_UID || uid == SHELL_UID) {
                return true;
            }
            String[] packages = ctx.getPackageManager().getPackagesForUid(uid);
            if (packages != null) {
                for (String p : packages) {
                    if (ModuleContract.MODULE_PACKAGE.equals(p)
                            || ModuleContract.SYSTEMUI_PACKAGE.equals(p)
                            || ModuleContract.isLauncherPackage(p)) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            Logx.w("provider: caller check failed", t);
        }
        return false;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd." + ModuleContract.MODULE_PACKAGE + ".config";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
