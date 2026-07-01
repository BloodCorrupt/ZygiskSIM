package com.zygisksim;

import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.telephony.euicc.DownloadableSubscription;
import android.telephony.euicc.EuiccManager;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import android.content.Intent;
import android.app.PendingIntent;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

/**
 * ZygiskSIM Hook Entry Point (Pine Architecture)
 *
 * Uses the Pine hooking framework to spoof eSIM support for apps like Eskimo.
 * Hooks all the standard Android eSIM detection methods:
 * - EuiccManager.isEnabled() → true
 * - EuiccManager.getEid() → fake EID string
 * - PackageManager.hasSystemFeature(String) → true for euicc features
 * - DownloadableSubscription.forActivationCode() → clipboard copy
 * - Application.onCreate() → context capture
 */
public class HookEntry {

    private static String sLogDir;
    private static Application sApplication;

    // Config-driven values (populated from config.json or defaults)
    private static String sEid = "89049032000000000000000000000001";
    private static String sSpoofModel = "Pixel 8";
    private static String sSpoofDevice = "shiba";
    private static String sSpoofManufacturer = "Google";
    private static String sSpoofBrand = "google";
    private static String sSpoofProduct = "shiba";

    /** Map of system property names to spoofed values (rebuilt from config) */
    private static final Map<String, String> SPOOFED_PROPS = new HashMap<>();

    private static void buildSpoofedProps() {
        SPOOFED_PROPS.clear();
        String[][] prefixes = {
            {"ro.product.", ""},
            {"ro.product.vendor.", ""},
            {"ro.product.system.", ""},
        };
        for (String[] p : prefixes) {
            String pfx = p[0];
            SPOOFED_PROPS.put(pfx + "model", sSpoofModel);
            SPOOFED_PROPS.put(pfx + "device", sSpoofDevice);
            SPOOFED_PROPS.put(pfx + "manufacturer", sSpoofManufacturer);
            SPOOFED_PROPS.put(pfx + "brand", sSpoofBrand);
            SPOOFED_PROPS.put(pfx + "name", sSpoofProduct);
        }
    }

    private static void parseConfig(String jsonStr) {
        if (jsonStr == null || jsonStr.isEmpty()) {
            return;
        }
        try {
            JSONObject config = new JSONObject(jsonStr);
            if (config.has("eid")) {
                sEid = config.getString("eid");
            }
            if (config.has("device")) {
                JSONObject dev = config.getJSONObject("device");
                sSpoofModel = dev.optString("model", sSpoofModel);
                sSpoofDevice = dev.optString("device", sSpoofDevice);
                sSpoofManufacturer = dev.optString("manufacturer", sSpoofManufacturer);
                sSpoofBrand = dev.optString("brand", sSpoofBrand);
                sSpoofProduct = dev.optString("product", sSpoofProduct);
            }
            logStatic("  Parsed config.json successfully.");
            logStatic("  Config EID: " + sEid);
            logStatic("  Config Device: " + sSpoofModel + " (" + sSpoofDevice + ")");
        } catch (Throwable t) {
            logStatic("  Failed to parse config.json, using defaults. Error: " + t.getMessage());
        }
    }

    public static void init(String logDir, String pineLibPath, String configJson) {
        sLogDir = logDir;
        logStatic("ZygiskSIM Java payload initializing (Pine)...");
        logStatic("  Pine library path from native: " + pineLibPath);

        // Parse config.json if provided
        parseConfig(configJson);
        buildSpoofedProps();

        // Spoof device identity immediately (no Pine needed, pure reflection)
        spoofBuildFields();

        try {
            loadPineLibrary(pineLibPath);
        } catch (Throwable t) {
            logStatic("Pine library load FAILED — hooks will not be installed: " + t.getMessage());
            logStackTrace(t);
            return;
        }

        // Install hooks immediately. Deferring to Application.onCreate using
        // Pine causes native SIGSEGV because of ART state transitions.
        // The original native crash was caused by the SystemProperties infinite
        // recursion (which we fixed), so immediate hooking is safe again.
        installHooks();
    }

    /**
     * Install all spoofing/interception hooks.
     */
    private static void installHooks() {
        logStatic("Installing hooks...");
        try { hookEuiccManagerIsEnabled(); } catch (Throwable t) {
            logStatic("  WARN: hookEuiccManagerIsEnabled failed: " + t.getMessage());
        }
        try { hookEuiccManagerGetEid(); } catch (Throwable t) {
            logStatic("  WARN: hookEuiccManagerGetEid failed: " + t.getMessage());
        }
        try { hookEuiccManagerGetEuiccInfo(); } catch (Throwable t) {
            logStatic("  WARN: hookEuiccManagerGetEuiccInfo failed: " + t.getMessage());
        }
        try { hookPackageManagerHasSystemFeature(); } catch (Throwable t) {
            logStatic("  WARN: hookPackageManagerHasSystemFeature failed: " + t.getMessage());
        }
        try { hookForActivationCode(); } catch (Throwable t) {
            logStatic("  WARN: hookForActivationCode failed: " + t.getMessage());
        }
        try { hookEuiccManagerDownloadSubscription(); } catch (Throwable t) {
            logStatic("  WARN: hookEuiccManagerDownloadSubscription failed: " + t.getMessage());
        }
        logStatic("Hook installation complete.");
    }

    private static void loadPineLibrary(final String pineLibPath) throws Exception {
        // Strategy 1: Load from the path provided by native companion (most reliable)
        if (pineLibPath != null && !pineLibPath.isEmpty()) {
            try {
                System.load(pineLibPath);
                logStatic("Successfully loaded libpine.so from companion path: " + pineLibPath);
                configurePineLoader(pineLibPath);
                return;
            } catch (UnsatisfiedLinkError e) {
                logStatic("Companion path load failed: " + e.getMessage());
            }
        }

        // Strategy 2: Try System.loadLibrary (works on Magisk with system overlay)
        try {
            System.loadLibrary("pine");
            logStatic("Successfully loaded libpine.so via System.loadLibrary");
            return;
        } catch (UnsatisfiedLinkError e) {
            logStatic("System.loadLibrary(\"pine\") failed: " + e.getMessage());
        }

        // Strategy 3: Try explicit system paths
        String[] fallbackPaths = {
            "/system/lib64/libpine.so",
            "/system/lib/libpine.so",
        };
        for (String path : fallbackPaths) {
            try {
                System.load(path);
                logStatic("Successfully loaded Pine from fallback: " + path);
                configurePineLoader(path);
                return;
            } catch (UnsatisfiedLinkError e) {
                logStatic("Fallback load failed for " + path + ": " + e.getMessage());
            }
        }

        throw new Exception("Failed to load libpine.so from any path");
    }

    /**
     * Override Pine's internal library loader so it doesn't try
     * System.loadLibrary("pine") again (which fails under NeoZygisk).
     * Uses reflection + dynamic Proxy since PineConfig.LibLoader
     * may not be publicly accessible in all Pine versions.
     */
    private static void configurePineLoader(final String loadedPath) {
        try {
            // Find the libLoader field on PineConfig
            Class<?> pineConfigClass = Class.forName("top.canyie.pine.PineConfig");
            Field libLoaderField = pineConfigClass.getDeclaredField("libLoader");
            libLoaderField.setAccessible(true);

            // Get the interface type of the field (e.g., PineConfig$LibLoader)
            Class<?> loaderInterface = libLoaderField.getType();

            // Create a dynamic proxy that implements the loader interface
            Object customLoader = java.lang.reflect.Proxy.newProxyInstance(
                loaderInterface.getClassLoader(),
                new Class<?>[]{ loaderInterface },
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        // The loadLib() method — re-load from our path (no-op if already loaded)
                        if (method.getName().equals("loadLib")) {
                            System.load(loadedPath);
                        }
                        return null;
                    }
                }
            );

            libLoaderField.set(null, customLoader);

            // Force Pine to initialize NOW with our custom loader
            Pine.ensureInitialized();
            logStatic("  Pine initialized with custom lib loader");
        } catch (Throwable t) {
            logStatic("  Warning: Pine custom loader setup failed: " + t.getMessage());
        }
    }

    // =========================================================================
    // Hook: EuiccManager.getEuiccInfo() → mock object
    // =========================================================================

    private static void hookEuiccManagerGetEuiccInfo() {
        try {
            Method getEuiccInfo = EuiccManager.class.getDeclaredMethod("getEuiccInfo");
            Pine.hook(getEuiccInfo, new MethodHook() {
                @Override
                public void beforeCall(Pine.CallFrame callFrame) {
                    try {
                        Class<?> euiccInfoClass = Class.forName("android.telephony.euicc.EuiccInfo");
                        Constructor<?> constructor = euiccInfoClass.getDeclaredConstructor(String.class);
                        constructor.setAccessible(true);
                        Object mockInfo = constructor.newInstance("1.0");
                        callFrame.setResult(mockInfo);
                    } catch (Exception e) {
                        callFrame.setResult(null);
                    }
                }
            });
            logStatic("  Hooked EuiccManager.getEuiccInfo()");
        } catch (Throwable t) {
            // Method might not exist on older API levels
            logStatic("  EuiccManager.getEuiccInfo() not available on this API level");
        }
    }

    // =========================================================================
    // Hook: EuiccManager.isEnabled() → always return true
    // =========================================================================

    private static void hookEuiccManagerIsEnabled() throws Exception {
        Method isEnabled = EuiccManager.class.getDeclaredMethod("isEnabled");
        Pine.hook(isEnabled, new MethodHook() {
            @Override
            public void beforeCall(Pine.CallFrame callFrame) {
                logStatic("Spoofed EuiccManager.isEnabled() -> true");
                callFrame.setResult(true);
            }
        });
        logStatic("  Hooked EuiccManager.isEnabled()");
    }

    // =========================================================================
    // Hook: EuiccManager.getEid() → return fake EID instead of null
    // This is the CRITICAL hook that most eSIM apps use to verify hardware.
    // =========================================================================

    private static void hookEuiccManagerGetEid() {
        try {
            Method getEid = EuiccManager.class.getDeclaredMethod("getEid");
            Pine.hook(getEid, new MethodHook() {
                @Override
                public void beforeCall(Pine.CallFrame callFrame) {
                    logStatic("Spoofed EuiccManager.getEid() -> " + sEid);
                    callFrame.setResult(sEid);
                }
            });
            logStatic("  Hooked EuiccManager.getEid()");
        } catch (NoSuchMethodException e) {
            // getEid() was added in API 29 (Android 10), may not exist on older devices
            logStatic("  EuiccManager.getEid() not available on this API level (requires API 29+)");
        } catch (Throwable t) {
            logStatic("  Failed to hook EuiccManager.getEid(): " + t.getMessage());
        }
    }

    // =========================================================================
    // Hook: PackageManager.hasSystemFeature(String) → true for euicc features
    // This catches apps that check the feature flag programmatically rather
    // than relying on the XML overlay (which may not work on all ROMs).
    // =========================================================================

    private static void hookPackageManagerHasSystemFeature() {
        try {
            // PackageManager is abstract — hook the concrete ApplicationPackageManager instead
            Class<?> apmClass = Class.forName("android.app.ApplicationPackageManager");
            Method hasSystemFeature = apmClass.getDeclaredMethod("hasSystemFeature", String.class);
            Pine.hook(hasSystemFeature, new MethodHook() {
                @Override
                public void afterCall(Pine.CallFrame callFrame) {
                    String featureName = (String) callFrame.args[0];
                    if (featureName != null && featureName.startsWith("android.hardware.telephony.euicc")) {
                        Boolean originalResult = (Boolean) callFrame.getResult();
                        if (originalResult == null || !originalResult) {
                            callFrame.setResult(true);
                        }
                    }
                }
            });
            logStatic("  Hooked ApplicationPackageManager.hasSystemFeature()");
        } catch (Throwable t) {
            logStatic("  Failed to hook hasSystemFeature(): " + t.getMessage());
        }
    }

    // =========================================================================
    // Hook: DownloadableSubscription.forActivationCode() → clipboard copy
    // =========================================================================

    private static void hookForActivationCode() throws Exception {
        Method forActivationCode = DownloadableSubscription.class.getDeclaredMethod("forActivationCode", String.class);
        Pine.hook(forActivationCode, new MethodHook() {
            @Override
            public void beforeCall(Pine.CallFrame callFrame) {
                String code = (String) callFrame.args[0];
                handleActivationCode(code);
            }
        });
        logStatic("  Hooked DownloadableSubscription.forActivationCode()");
    }

    // =========================================================================
    // Hook: EuiccManager.downloadSubscription() → Bypass and trigger success
    // =========================================================================

    private static void hookEuiccManagerDownloadSubscription() {
        try {
            Class<?> euiccManagerClass = Class.forName("android.telephony.euicc.EuiccManager");
            Method download = euiccManagerClass.getDeclaredMethod("downloadSubscription", 
                DownloadableSubscription.class, boolean.class, PendingIntent.class);
            
            Pine.hook(download, new MethodHook() {
                @Override
                public void beforeCall(Pine.CallFrame callFrame) {
                    logStatic("Intercepted EuiccManager.downloadSubscription()!");
                    
                    // Prevent original method from running (it would crash on unsupported devices)
                    callFrame.setResult(null);

                    // Try to extract the activation code if we didn't catch it earlier
                    try {
                        DownloadableSubscription sub = (DownloadableSubscription) callFrame.args[0];
                        if (sub != null) {
                            String code = sub.getEncodedActivationCode();
                            if (code != null) {
                                handleActivationCode(code);
                            }
                        }
                    } catch (Throwable t) {
                        logStatic("Failed to extract code from subscription: " + t.getMessage());
                    }

                    // Trigger the app's callback so it thinks the download succeeded!
                    PendingIntent callbackIntent = (PendingIntent) callFrame.args[2];
                    if (callbackIntent != null) {
                        try {
                            // EuiccManager.EMBEDDED_SUBSCRIPTION_RESULT_OK == 0
                            Intent resultIntent = new Intent();
                            callbackIntent.send(getApplicationContext(), 0, resultIntent);
                            logStatic("Triggered success callback to app.");
                        } catch (Exception e) {
                            logStatic("Failed to send callback intent: " + e.getMessage());
                        }
                    }
                }
            });
            logStatic("  Hooked EuiccManager.downloadSubscription()");
        } catch (Throwable t) {
            logStatic("  Failed to hook downloadSubscription(): " + t.getMessage());
        }
    }

    /** Helper to get context now that Application.onCreate hook is removed */
    private static Context getApplicationContext() {
        if (sApplication != null) return sApplication;
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method currentApplicationMethod = activityThreadClass.getDeclaredMethod("currentApplication");
            sApplication = (Application) currentApplicationMethod.invoke(null);
        } catch (Exception ignored) {}
        return sApplication;
    }

    // =========================================================================
    // Spoof: android.os.Build static fields — make device look like Pixel 8
    // This runs via reflection to modify the final static fields in-process.
    // Only affects THIS app process, not the system.
    // =========================================================================

    private static void spoofBuildFields() {
        try {
            Class<?> buildClass = android.os.Build.class;
            setStaticField(buildClass, "MODEL", sSpoofModel);
            setStaticField(buildClass, "DEVICE", sSpoofDevice);
            setStaticField(buildClass, "MANUFACTURER", sSpoofManufacturer);
            setStaticField(buildClass, "BRAND", sSpoofBrand);
            setStaticField(buildClass, "PRODUCT", sSpoofProduct);
            logStatic("  Spoofed Build fields: MODEL=" + sSpoofModel + ", DEVICE=" + sSpoofDevice
                    + ", MANUFACTURER=" + sSpoofManufacturer + ", BRAND=" + sSpoofBrand);
        } catch (Throwable t) {
            logStatic("  Failed to spoof Build fields: " + t.getMessage());
        }
    }

    private static void setStaticField(Class<?> clazz, String fieldName, Object value) {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(null, value);
        } catch (Throwable t) {
            logStatic("    Failed to set Build." + fieldName + ": " + t.getMessage());
        }
    }

    // =========================================================================
    // Activation code handler — copy to clipboard
    // =========================================================================

    private static void handleActivationCode(String code) {
        logStatic("========================================");
        logStatic("eSIM DOWNLOAD INTERCEPTED (Pine)");
        logStatic("  Activation Code: " + code);
        logStatic("========================================");

        if (sApplication != null) {
            try {
                ClipboardManager clipboard = (ClipboardManager) sApplication.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    ClipData clip = ClipData.newPlainText("Encoded eSIM activation code", code);
                    clipboard.setPrimaryClip(clip);
                    logStatic("Code successfully copied to clipboard.");
                }
            } catch (Exception e) {
                logStatic("Failed to copy to clipboard: " + e.getMessage());
            }
        } else {
            logStatic("Cannot copy to clipboard: Application context is null.");
        }
    }

    // =========================================================================
    // Logging utilities
    // =========================================================================

    private static void logStatic(String message) {
        try {
            android.util.Log.i("ZygiskSIM", message);
        } catch (Exception ignored) {}

        if (sLogDir == null) return;

        try {
            File dir = new File(sLogDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            File logFile = new File(dir, "esim_log.txt");
            PrintWriter pw = new PrintWriter(new FileWriter(logFile, true));

            String timestamp = new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            pw.println("[" + timestamp + "] " + message);
            pw.flush();
            pw.close();

        } catch (Exception e) {
            try {
                android.util.Log.w("ZygiskSIM", "Log file write failed: " + e.getMessage());
            } catch (Exception ignored) {}
        }
    }

    private static void logStackTrace(Throwable t) {
        try {
            android.util.Log.e("ZygiskSIM", "Stack trace:", t);
        } catch (Exception ignored) {}

        if (sLogDir == null) return;

        try {
            File dir = new File(sLogDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            File logFile = new File(dir, "esim_log.txt");
            PrintWriter pw = new PrintWriter(new FileWriter(logFile, true));

            String timestamp = new SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            pw.print("[" + timestamp + "] ");
            t.printStackTrace(pw);
            pw.flush();
            pw.close();

        } catch (Exception ignored) {}
    }
}
