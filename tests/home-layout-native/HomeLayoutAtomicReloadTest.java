/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.libhook.rules.home.dock;

import android.content.ContentResolver;
import com.sevtinge.hyperceiler.common.utils.PrefsBridge;
import com.sevtinge.hyperceiler.libhook.base.BaseHook;
import java.lang.reflect.*;
import java.util.*;
import com.sevtinge.hyperceiler.libhook.provider.HomeRecentsHapticPrefs;
import com.sevtinge.hyperceiler.libhook.provider.HomeLayoutPrefsSnapshot;

/** Actual endpoint regression: generation changes, update outages and successful-but-torn reads. */
public final class HomeLayoutAtomicReloadTest {
    private static final String ENABLE = "home_layout_indicator_margin_bottom_enable";
    private static final String MARGIN = "home_layout_indicator_margin_bottom";
    private static Field cache;
    private static int passed, failed;
    private static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }
    private static void test(String name, Throwing body) {
        try { reset(); body.run(); passed++; System.out.println("PASS " + name); }
        catch (Throwable error) { failed++; System.out.println("FAIL " + name + ": " + error); }
    }
    private interface Throwing { void run() throws Exception; }
    private static void reset() throws Exception {
        try { HomeLayoutNativeEndpointOS4.class.getMethod("resumeAfterRejectedHotReload").invoke(null); }
        catch (NoSuchMethodException baseline) { }
        cache.set(null, null); BaseHook.resetTestState(); PrefsBridge.setTestValues(Map.of());
        ContentResolver.setRows(Map.of());
        ContentResolver.recentsHapticExtras = false;
        android.os.SystemClock.source = System::nanoTime;
        Field worker = HomeLayoutNativeEndpointOS4.class.getDeclaredField("refresher");
        worker.setAccessible(true); worker.set(null, new Thread());
    }
    private static void establish(int margin) {
        ContentResolver.setRows(Map.of("prefs_key_" + ENABLE, 1, "prefs_key_" + MARGIN, margin));
        check(HomeLayoutNativeEndpointOS4.refreshFromProvider(), "provider read");
    }
    private static HomeLayoutNativeEndpointOS4.Snapshot ready() throws Exception {
        Method method = HomeLayoutNativeEndpointOS4.class.getDeclaredMethod("readReadyPreferences");
        method.setAccessible(true); return (HomeLayoutNativeEndpointOS4.Snapshot) method.invoke(null);
    }
    public static void main(String[] args) throws Exception {
        cache = HomeLayoutNativeEndpointOS4.class.getDeclaredField("cachedValues"); cache.setAccessible(true);
        Field specs = HomeLayoutNativeEndpointOS4.class.getDeclaredField("PREF_KEYS"); specs.setAccessible(true);
        ContentResolver.setSpecs((String[][]) specs.get(null));
        test("successful refresh never mixes two UI revisions", () -> {
            establish(184);
            ContentResolver.afterRead = key -> {
                if (key.equals("prefs_key_" + ENABLE)) ContentResolver.putRow("prefs_key_" + MARGIN, 70);
            };
            check(HomeLayoutNativeEndpointOS4.refreshFromProvider(), "complete read");
            check(HomeLayoutNativeEndpointOS4.readPreferences().knobDeltaDp()[5] == 114, "height reverted within one successful refresh");
            check(ContentResolver.queryCount() == 2, "must use one IPC per snapshot");
        });
        test("cold endpoint refuses stale startup defaults", () -> {
            PrefsBridge.setTestValues(Map.of(ENABLE, 0, MARGIN, 70));
            check(ready() == null, "startup zero snapshot overwrote launcher's last-good native cache");
        });
        test("hot reload retains complete capsule height before provider recovers", () -> {
            establish(184); cache.set(null, null);
            ContentResolver.setRows(Map.of()); ContentResolver.failAt(0, "null");
            var snapshot = ready();
            check(snapshot != null && snapshot.knobEnabled()[5] == 1 && snapshot.knobDeltaDp()[5] == 114,
                "new generation lost position during package replacement");
        });
        test("old provider protocol never means reset", () -> {
            establish(184); ContentResolver.emptyBatch(true);
            check(!HomeLayoutNativeEndpointOS4.refreshFromProvider(), "empty response accepted as settings deletion");
            check(HomeLayoutNativeEndpointOS4.readPreferences().knobDeltaDp()[5] == 114, "position reset by old provider");
        });
        test("wrong schema preserves last-good offset", () -> {
            establish(184); ContentResolver.wrongSchema(true);
            check(!HomeLayoutNativeEndpointOS4.refreshFromProvider(), "unrecognized schema accepted");
            check(HomeLayoutNativeEndpointOS4.readPreferences().knobDeltaDp()[5] == 114, "schema mismatch reset height");
        });
        test("authoritative reset and new height still take effect", () -> {
            establish(184); ContentResolver.setRows(Map.of());
            check(HomeLayoutNativeEndpointOS4.refreshFromProvider(), "complete reset row");
            check(HomeLayoutNativeEndpointOS4.readPreferences().knobEnabled()[5] == 0, "real reset ignored");
            establish(300); check(ready().knobDeltaDp()[5] == 230, "new height stuck behind cached revision");
        });
        test("provider reads physical file exactly once and keeps one revision", () -> {
            Class<?> helper = Class.forName("com.sevtinge.hyperceiler.libhook.provider.HomeLayoutPrefsSnapshot");
            Method build = helper.getMethod("cursor", android.content.SharedPreferences.class);
            Map<String, Object> rows = new HashMap<>();
            rows.put("prefs_key_" + ENABLE, true); rows.put("prefs_key_" + MARGIN, 184);
            rows.put("prefs_key_home_other_seek_points", "2");
            int[] reads = {0};
            android.content.SharedPreferences prefs = () -> { reads[0]++; return new HashMap<>(rows); };
            try (var cursor = (android.database.Cursor) build.invoke(null, prefs)) {
                rows.put("prefs_key_" + MARGIN, 70);
                check(reads[0] == 1 && cursor.moveToFirst()
                    && cursor.getColumnCount() == ((String[][]) specs.get(null)).length + 1,
                    "snapshot shape / getAll count");
                for (int i = 1; i < cursor.getColumnCount(); i++) {
                    String key = cursor.getColumnName(i);
                    if (key.equals(MARGIN)) check(cursor.getInt(i) == 184, "provider cursor changed after creation");
                    if (key.equals(ENABLE)) check(cursor.getInt(i) == 1, "boolean serialization");
                    if (key.equals("home_other_seek_points")) check(cursor.getInt(i) == 2, "string mode serialization");
                }
            }
            rows.put("prefs_key_" + MARGIN, "184");
            check(build.invoke(null, prefs) == null, "wrong stored type must not publish a partial reset");
            check(build.invoke(null, new Object[]{null}) == null, "uninitialized preferences are unavailable");
        });
        test("retiring generation cannot publish an in-flight response", () -> {
            Method pause = HomeLayoutNativeEndpointOS4.class.getMethod("pauseForHotReload");
            establish(184); ContentResolver.setRows(Map.of("prefs_key_" + ENABLE, 1, "prefs_key_" + MARGIN, 70));
            ContentResolver.afterRead = key -> {
                if (key.equals("prefs_key_" + ENABLE)) {
                    try { check((Boolean) pause.invoke(null), "retirement rejected"); }
                    catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                }
            };
            check(!HomeLayoutNativeEndpointOS4.refreshFromProvider(), "retired query published");
            check(HomeLayoutNativeEndpointOS4.readPreferences().knobDeltaDp()[5] == 114, "retired query moved capsule");
        });
        test("3600 provider-unavailable cycles after reload preserve height", () -> {
            establish(300); cache.set(null, null); check(ready().knobDeltaDp()[5] == 230, "handoff");
            for (int i = 0; i < 3600; i++) {
                ContentResolver.setRows(Map.of()); ContentResolver.failAt(0, "null");
                check(!HomeLayoutNativeEndpointOS4.refreshFromProvider(), "unavailable accepted");
                check(ready().knobDeltaDp()[5] == 230, "height drift at cycle " + i);
            }
        });
        test("real refresher thread terminates before accepting hot reload", () -> {
            establish(184); Field worker = HomeLayoutNativeEndpointOS4.class.getDeclaredField("refresher");
            worker.setAccessible(true); worker.set(null, null); check(ready() != null, "worker startup");
            Thread live = (Thread) worker.get(null);
            check(live != null && live.isAlive(), "refresher thread was not started");
            check((Boolean) HomeLayoutNativeEndpointOS4.class.getMethod("pauseForHotReload").invoke(null), "refresher failed to terminate");
            check(!live.isAlive() && worker.get(null) == null, "old thread survived reload boundary");
        });
        test("haptic profile reuses one layout provider query", () -> {
            ContentResolver.setRows(Map.of(
                "prefs_key_home_recent_card_haptic", 1,
                "prefs_key_home_recent_card_close_haptic", 1,
                "prefs_key_home_recent_card_haptic_type", 2,
                "prefs_key_home_recent_card_haptic_strength", 70));
            ContentResolver.recentsHapticExtras = true;
            check(HomeLayoutNativeEndpointOS4.refreshFromProvider(), "layout snapshot unavailable");
            int count = ContentResolver.queryCount();
            var state = HomeLayoutNativeEndpointOS4.recentsHapticPreference();
            check(state.available() == 1 && state.enabled() == 1 && state.close() == 1
                && state.type() == 2 && state.strength() == 70, "haptic fields lost");
            for (int i = 0; i < 30; ++i)
                check(HomeLayoutNativeEndpointOS4.recentsHapticPreference().equals(state), "haptic profile drift");
            check(ContentResolver.queryCount() == count && count == 1,
                "haptic profile triggered additional provider Binder queries");
        });
        test("missing or invalid haptic extras fail closed without harming layout", () -> {
            establish(184);
            check(HomeLayoutNativeEndpointOS4.recentsHapticPreference().available() == 0,
                "old provider accidentally enabled haptic feedback");
            ContentResolver.setRows(Map.of("prefs_key_home_recent_card_haptic_strength", 101));
            ContentResolver.recentsHapticExtras = true;
            check(HomeLayoutNativeEndpointOS4.refreshFromProvider(), "invalid optional extras broke layout");
            check(HomeLayoutNativeEndpointOS4.recentsHapticPreference().available() == 0,
                "out-of-range haptic extension accepted");
        });
        test("expired lease disables haptic without a synchronous provider query", () -> {
            java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(10_000_000_000L);
            android.os.SystemClock.source = clock::get;
            ContentResolver.setRows(Map.of("prefs_key_home_recent_card_haptic", 1));
            ContentResolver.recentsHapticExtras = true;
            check(HomeLayoutNativeEndpointOS4.refreshFromProvider(), "initial fresh lease");
            check(HomeLayoutNativeEndpointOS4.recentsHapticPreference().available() == 1, "fresh lease denied");
            int count = ContentResolver.queryCount();
            clock.addAndGet(5_000_000_001L);
            check(HomeLayoutNativeEndpointOS4.recentsHapticPreference().available() == 0, "expired lease still allowed");
            check(ContentResolver.queryCount() == count, "lease expiry synchronously queried provider");
        });
        test("haptic settings writes and framework revocation invalidate publication", () -> {
            Method filter = HomeLayoutNativeEndpointOS4.class.getDeclaredMethod("isLayoutChange", android.net.Uri.class);
            filter.setAccessible(true);
            for (String key : new String[]{"home_recent_card_haptic", "home_recent_card_close_haptic",
                "home_recent_card_haptic_type", "home_recent_card_haptic_strength",
                "framework_api_allow_hook"}) {
                String path = "content://com.sevtinge.hyperceiler.provider.sharedprefs/pref/boolean/prefs_key_" + key;
                check((Boolean)filter.invoke(null, android.net.Uri.parse(path)), "ignored update " + key);
            }
            check(!(Boolean)filter.invoke(null, android.net.Uri.parse(
                "content://com.sevtinge.hyperceiler.provider.sharedprefs/pref/boolean/prefs_key_unrelated")),
                "unrelated setting triggered full haptic refresh");
        });
        test("haptic extras share one physical getAll revision with layout", () -> {
            Map<String, Object> data = new HashMap<>();
            data.put("prefs_key_home_recent_card_haptic", true);
            data.put("prefs_key_home_recent_card_close_haptic", true);
            data.put("prefs_key_home_recent_card_haptic_type", "3");
            data.put("prefs_key_home_recent_card_haptic_strength", "75");
            int[] reads = {0};
            android.content.SharedPreferences prefs = () -> { reads[0]++; return new HashMap<>(data); };
            try (var cursor = HomeLayoutPrefsSnapshot.cursor(prefs)) {
                check(cursor != null, "layout cursor lost with valid haptic extension");
                data.put("prefs_key_home_recent_card_haptic", false);
                var extras = cursor.getExtras();
                check(reads[0] == 1 && extras.getInt(HomeRecentsHapticPrefs.SNAPSHOT_VERSION, 0) == 1
                    && extras.getInt(HomeRecentsHapticPrefs.ENABLED, -1) == 1
                    && extras.getInt(HomeRecentsHapticPrefs.CLOSE, -1) == 1
                    && extras.getInt(HomeRecentsHapticPrefs.TYPE, -1) == 3
                    && extras.getInt(HomeRecentsHapticPrefs.STRENGTH, -1) == 75,
                    "haptic extension came from a different prefs revision");
            }
            data.put("prefs_key_framework_api_allow_hook", false);
            var denied = HomeRecentsHapticPrefs.snapshot(data);
            check(denied != null && denied.enabled() == 0 && denied.close() == 0,
                "original framework revocation gate bypassed");
            data.put("prefs_key_home_recent_card_haptic_strength", "bad");
            try (var cursor = HomeLayoutPrefsSnapshot.cursor(prefs)) {
                check(cursor != null && cursor.getExtras().getInt(HomeRecentsHapticPrefs.SNAPSHOT_VERSION, 0) == 0,
                    "malformed optional haptic setting broke unrelated layout");
            }
        });
        System.out.println("ATOMIC_RELOAD_TEST: " + passed + " passed; " + failed + " failed");
        if (failed != 0) System.exit(1);
    }
}
