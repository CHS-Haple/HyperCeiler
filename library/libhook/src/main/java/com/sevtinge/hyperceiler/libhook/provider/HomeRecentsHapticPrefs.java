/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.provider;

import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import java.util.Map;

/** One authoritative physical preference read, also usable by dex-less Home. */
public final class HomeRecentsHapticPrefs {
    public static final String SNAPSHOT_VERSION = "hc_recents_haptic_version";
    public static final String ENABLED = "hc_recents_haptic_enabled";
    public static final String CLOSE = "hc_recents_haptic_close";
    public static final String TYPE = "hc_recents_haptic_type";
    public static final String STRENGTH = "hc_recents_haptic_strength";

    /** Immutable values from one physical preferences revision; null is unavailable. */
    public record Snapshot(int enabled, int close, int type, int strength) {}

    private HomeRecentsHapticPrefs() {}

    public static boolean isPreferenceKey(String key) {
        return "home_recent_card_haptic".equals(key)
            || "home_recent_card_close_haptic".equals(key)
            || "home_recent_card_haptic_type".equals(key)
            || "home_recent_card_haptic_strength".equals(key);
    }

    public static Snapshot snapshot(Map<String, ?> values) {
        if (values == null) return null;
        boolean allowed = !Boolean.FALSE.equals(values.get("prefs_key_framework_api_allow_hook"));
        boolean enabled = allowed && Boolean.TRUE.equals(values.get("prefs_key_home_recent_card_haptic"));
        boolean close = allowed && Boolean.TRUE.equals(values.get("prefs_key_home_recent_card_close_haptic"));
        int type = 0, strength = 50;
        Object mode = values.get("prefs_key_home_recent_card_haptic_type");
        Object level = values.get("prefs_key_home_recent_card_haptic_strength");
        try {
            if (mode != null) type = Integer.parseInt(mode.toString());
            if (level != null) strength = Integer.parseInt(level.toString());
        } catch (NumberFormatException malformed) { return null; }
        if (type < 0 || type > 3 || strength < 10 || strength > 100) return null;
        return new Snapshot(enabled ? 1 : 0, close ? 1 : 0, type, strength);
    }

    public static Cursor cursor(SharedPreferences prefs) {
        if (prefs == null) return null;
        Snapshot value = snapshot(prefs.getAll());
        if (value == null) return null;
        var cursor = new MatrixCursor(new String[]{"schema","enabled","type","strength"});
        cursor.addRow(new Object[]{1, value.enabled(), value.type(), value.strength()});
        var extras = new android.os.Bundle();
        extras.putInt("close_version", 1);
        extras.putInt("close_enabled", value.close());
        cursor.setExtras(extras);
        return cursor;
    }
}
