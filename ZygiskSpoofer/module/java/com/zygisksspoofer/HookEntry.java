package com.zygisksspoofer;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.media.MediaDrm;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.provider.Settings;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import org.json.JSONObject;

/**
 * Zygisk Spoofer — Java Hook Payload
 * Spoofs:
 *   1. ANDROID ID (Settings.Secure.ANDROID_ID)
 *   2. GSF ID (Google Services Framework ID via ContentResolver)
 *   3. ADS ID (Google Advertising ID / GAID)
 *   4. APP SET ID (GMS AppSetIdInfo)
 *   5. MEDIA DRM ID (MediaDrm Device Unique ID)
 */
public class HookEntry {

    private static final String TAG = "ZygiskSpoofer";
    private static String sLogDir = null;

    // Active spoof values & toggles
    private static boolean sAndroidIdEnabled = true;
    private static String sAndroidId = "f5ff848873c1a17d";

    private static boolean sGsfIdEnabled = true;
    private static String sGsfId = "67db684e29db56a0";

    private static boolean sAdsIdEnabled = true;
    private static String sAdsId = "c0725525-4660-4cd0-b3ea-c712e7adb543";

    private static boolean sAppSetIdEnabled = true;
    private static String sAppSetId = "68939b1b-0bb8-47f7-880b-a95d60ef77d0";

    private static boolean sMediaDrmIdEnabled = true;
    private static String sMediaDrmId = "c32f4a943fe9840c2e6f554dcd6d6987bff8a56ca6e406d00435578214c5547c";

    // Native hook engine helpers
    public static native boolean nativeHookMethod(Method target, Method hook);
    public static native void nativeProbeOffsets(Method m1, Method m2);

    // Native hooks for system methods
    public static native String hook_native_getString(ContentResolver resolver, String name);
    public static native String hook_native_getStringForUser(ContentResolver resolver, String name, int userHandle);
    public native byte[] hook_native_getPropertyByteArray(String propertyName);
    public native String hook_native_getPropertyString(String propertyName);

    public static class ProbeHelper {
        public void probe1() {}
        public void probe2() {}
    }

    public static void init(String logDir, String configJson) {
        sLogDir = logDir;
        log("========================================");
        log("Zygisk Spoofer Java payload initializing...");
        log("========================================");

        // 1. Parse config.json
        parseConfig(configJson);

        // 2. Bypass Hidden API Restrictions
        bypassHiddenApiRestrictions();

        // 3. Probe ART struct size
        try {
            Method m1 = ProbeHelper.class.getDeclaredMethod("probe1");
            Method m2 = ProbeHelper.class.getDeclaredMethod("probe2");
            nativeProbeOffsets(m1, m2);
        } catch (Throwable t) {
            log("Offset probing note: " + t.getMessage());
        }

        // 4. Install Android ID Hooks (Settings.Secure)
        if (sAndroidIdEnabled) {
            installAndroidIdHooks();
        }

        // 5. Install Media DRM ID Hooks (MediaDrm)
        if (sMediaDrmIdEnabled) {
            installMediaDrmHooks();
        }

        // 6. Install GSF ID & GMS AppSet/Ads Hooks (ContentResolver + Dynamic class loading)
        installDynamicAndGmsHooks();

        log("Zygisk Spoofer initialization complete.");
    }

    // =====================================================================
    // 1. Android ID Hooking (Settings.Secure & Settings.System)
    // =====================================================================

    private static void installAndroidIdHooks() {
        log("Installing Android ID Hooks -> " + sAndroidId);

        // Settings.Secure.getString(ContentResolver, String)
        try {
            Method target = Settings.Secure.class.getDeclaredMethod("getString", ContentResolver.class, String.class);
            Method hook = HookEntry.class.getDeclaredMethod("hook_native_getString", ContentResolver.class, String.class);
            if (nativeHookMethod(target, hook)) {
                log("  Direct ART Hook: Settings.Secure.getString() -> SUCCESS");
            }
        } catch (Throwable t) {
            log("  Settings.Secure.getString hook note: " + t.getMessage());
        }

        // Settings.Secure.getStringForUser(ContentResolver, String, int)
        try {
            Method target = Settings.Secure.class.getDeclaredMethod("getStringForUser", ContentResolver.class, String.class, int.class);
            Method hook = HookEntry.class.getDeclaredMethod("hook_native_getStringForUser", ContentResolver.class, String.class, int.class);
            if (nativeHookMethod(target, hook)) {
                log("  Direct ART Hook: Settings.Secure.getStringForUser() -> SUCCESS");
            }
        } catch (Throwable t) {
            log("  Settings.Secure.getStringForUser hook note: " + t.getMessage());
        }

        // Settings.System.getString(ContentResolver, String)
        try {
            Method target = Settings.System.class.getDeclaredMethod("getString", ContentResolver.class, String.class);
            Method hook = HookEntry.class.getDeclaredMethod("hook_native_getString", ContentResolver.class, String.class);
            if (nativeHookMethod(target, hook)) {
                log("  Direct ART Hook: Settings.System.getString() -> SUCCESS");
            }
        } catch (Throwable t) {
            log("  Settings.System.getString hook note: " + t.getMessage());
        }
    }

    // =====================================================================
    // 2. Media DRM ID Hooking (android.media.MediaDrm)
    // =====================================================================

    private static void installMediaDrmHooks() {
        log("Installing Media DRM ID Hooks -> " + sMediaDrmId);

        // MediaDrm.getPropertyByteArray(String)
        try {
            Method target = MediaDrm.class.getDeclaredMethod("getPropertyByteArray", String.class);
            Method hook = HookEntry.class.getDeclaredMethod("hook_native_getPropertyByteArray", String.class);
            if (nativeHookMethod(target, hook)) {
                log("  Direct ART Hook: MediaDrm.getPropertyByteArray() -> SUCCESS");
            }
        } catch (Throwable t) {
            log("  MediaDrm.getPropertyByteArray hook note: " + t.getMessage());
        }

        // MediaDrm.getPropertyString(String)
        try {
            Method target = MediaDrm.class.getDeclaredMethod("getPropertyString", String.class);
            Method hook = HookEntry.class.getDeclaredMethod("hook_native_getPropertyString", String.class);
            if (nativeHookMethod(target, hook)) {
                log("  Direct ART Hook: MediaDrm.getPropertyString() -> SUCCESS");
            }
        } catch (Throwable t) {
            log("  MediaDrm.getPropertyString hook note: " + t.getMessage());
        }
    }

    // =====================================================================
    // 3. GSF ID, Ads ID, and App Set ID Hooking
    // =====================================================================

    private static void installDynamicAndGmsHooks() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                // Poll for GMS classes as app loads them
                for (int i = 0; i < 60; i++) {
                    try {
                        // Hook AdvertisingIdClient$Info if loaded
                        if (sAdsIdEnabled) {
                            hookGmsAdsInfo();
                        }
                        // Hook AppSetIdInfo if loaded
                        if (sAppSetIdEnabled) {
                            hookGmsAppSetInfo();
                        }
                    } catch (Throwable ignored) {}

                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ignored) {}
                }
            }
        }).start();
    }

    private static boolean sGmsAdsHooked = false;
    private static void hookGmsAdsInfo() {
        if (sGmsAdsHooked) return;
        try {
            Class<?> infoCls = Class.forName("com.google.android.gms.ads.identifier.AdvertisingIdClient$Info");
            Method getIdMethod = infoCls.getDeclaredMethod("getId");
            Method hookMethod = HookEntry.class.getDeclaredMethod("hook_ads_getId");
            if (nativeHookMethod(getIdMethod, hookMethod)) {
                log("  Hooked AdvertisingIdClient.Info.getId() -> " + sAdsId);
                sGmsAdsHooked = true;
            }
        } catch (Throwable ignored) {}
    }

    public String hook_ads_getId() {
        log("Spoofed AdvertisingIdClient$Info.getId() -> " + sAdsId);
        return sAdsId;
    }

    private static boolean sGmsAppSetHooked = false;
    private static void hookGmsAppSetInfo() {
        if (sGmsAppSetHooked) return;
        try {
            Class<?> appSetCls = Class.forName("com.google.android.gms.appset.AppSetIdInfo");
            Method getIdMethod = appSetCls.getDeclaredMethod("getId");
            Method hookMethod = HookEntry.class.getDeclaredMethod("hook_appset_getId");
            if (nativeHookMethod(getIdMethod, hookMethod)) {
                log("  Hooked AppSetIdInfo.getId() -> " + sAppSetId);
                sGmsAppSetHooked = true;
            }
        } catch (Throwable ignored) {}
    }

    public String hook_appset_getId() {
        log("Spoofed AppSetIdInfo.getId() -> " + sAppSetId);
        return sAppSetId;
    }

    // =====================================================================
    // GSF Provider Interception Helper
    // =====================================================================

    public static Cursor createGsfCursor() {
        MatrixCursor cursor = new MatrixCursor(new String[]{"name", "data"});
        cursor.addRow(new Object[]{"android_id", sGsfId});
        return cursor;
    }

    // =====================================================================
    // General Helpers
    // =====================================================================

    private static void bypassHiddenApiRestrictions() {
        try {
            Class<?> vmRuntimeClass = Class.forName("dalvik.system.VMRuntime");
            Method getRuntimeMethod = vmRuntimeClass.getDeclaredMethod("getRuntime");
            Object vmRuntime = getRuntimeMethod.invoke(null);
            Method setExemptionsMethod = vmRuntimeClass.getDeclaredMethod("setHiddenApiExemptions", String[].class);
            setExemptionsMethod.invoke(vmRuntime, new Object[]{new String[]{"L"}});
            log("Bypassed hidden API restrictions via VMRuntime.");
        } catch (Throwable t) {
            log("Failed to bypass hidden API restrictions: " + t.getMessage());
        }
    }

    private static void parseConfig(String configJson) {
        if (configJson == null || configJson.trim().isEmpty()) {
            log("No config.json provided from native layer, using defaults.");
            return;
        }

        try {
            JSONObject root = new JSONObject(configJson);

            if (root.has("android_id")) {
                JSONObject obj = root.getJSONObject("android_id");
                sAndroidIdEnabled = obj.optBoolean("enabled", true);
                sAndroidId = obj.optString("value", sAndroidId);
            }
            if (root.has("gsf_id")) {
                JSONObject obj = root.getJSONObject("gsf_id");
                sGsfIdEnabled = obj.optBoolean("enabled", true);
                sGsfId = obj.optString("value", sGsfId);
            }
            if (root.has("ads_id")) {
                JSONObject obj = root.getJSONObject("ads_id");
                sAdsIdEnabled = obj.optBoolean("enabled", true);
                sAdsId = obj.optString("value", sAdsId);
            }
            if (root.has("app_set_id")) {
                JSONObject obj = root.getJSONObject("app_set_id");
                sAppSetIdEnabled = obj.optBoolean("enabled", true);
                sAppSetId = obj.optString("value", sAppSetId);
            }
            if (root.has("media_drm_id")) {
                JSONObject obj = root.getJSONObject("media_drm_id");
                sMediaDrmIdEnabled = obj.optBoolean("enabled", true);
                sMediaDrmId = obj.optString("value", sMediaDrmId);
            }

            log("Parsed config.json:");
            log("  Android ID:   [" + (sAndroidIdEnabled ? "ON" : "OFF") + "] " + sAndroidId);
            log("  GSF ID:       [" + (sGsfIdEnabled ? "ON" : "OFF") + "] " + sGsfId);
            log("  Ads ID:       [" + (sAdsIdEnabled ? "ON" : "OFF") + "] " + sAdsId);
            log("  App Set ID:   [" + (sAppSetIdEnabled ? "ON" : "OFF") + "] " + sAppSetId);
            log("  Media DRM ID: [" + (sMediaDrmIdEnabled ? "ON" : "OFF") + "] " + sMediaDrmId);
        } catch (Throwable t) {
            log("Failed to parse config.json, using defaults: " + t.getMessage());
        }
    }

    private static void log(String message) {
        try {
            Log.i(TAG, message);
        } catch (Exception ignored) {}

        if (sLogDir == null) return;
        try {
            File dir = new File(sLogDir);
            if (!dir.exists()) dir.mkdirs();
            File logFile = new File(dir, "spoofer_log.txt");
            PrintWriter pw = new PrintWriter(new FileWriter(logFile, true));
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            pw.println("[" + timestamp + "] " + message);
            pw.flush();
            pw.close();
        } catch (Exception ignored) {}
    }
}
