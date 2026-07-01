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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

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

    /**
     * Fake EID (Embedded Identity Document) - 32-digit hex string.
     * This follows the standard EID format (89 prefix for eUICC).
     * It won't enable real eSIM downloads, but makes detection checks pass.
     */
    private static final String FAKE_EID = "89049032000000000000000000000001";

    /**
     * Spoofed Build fields — Google Pixel 8 (known eSIM-capable device).
     * Many eSIM apps check Build.MODEL against a whitelist of supported devices.
     * These are only applied inside the target app's process, not system-wide.
     */
    private static final String SPOOF_MODEL = "Pixel 8";
    private static final String SPOOF_DEVICE = "shiba";
    private static final String SPOOF_MANUFACTURER = "Google";
    private static final String SPOOF_BRAND = "google";
    private static final String SPOOF_PRODUCT = "shiba";

    /** Map of system property names to spoofed values */
    private static final Map<String, String> SPOOFED_PROPS = new HashMap<>();
    static {
        SPOOFED_PROPS.put("ro.product.model", SPOOF_MODEL);
        SPOOFED_PROPS.put("ro.product.device", SPOOF_DEVICE);
        SPOOFED_PROPS.put("ro.product.manufacturer", SPOOF_MANUFACTURER);
        SPOOFED_PROPS.put("ro.product.brand", SPOOF_BRAND);
        SPOOFED_PROPS.put("ro.product.name", SPOOF_PRODUCT);
        // Vendor-specific overrides (some apps read these variants)
        SPOOFED_PROPS.put("ro.product.vendor.model", SPOOF_MODEL);
        SPOOFED_PROPS.put("ro.product.vendor.device", SPOOF_DEVICE);
        SPOOFED_PROPS.put("ro.product.vendor.manufacturer", SPOOF_MANUFACTURER);
        SPOOFED_PROPS.put("ro.product.vendor.brand", SPOOF_BRAND);
        SPOOFED_PROPS.put("ro.product.vendor.name", SPOOF_PRODUCT);
        SPOOFED_PROPS.put("ro.product.system.model", SPOOF_MODEL);
        SPOOFED_PROPS.put("ro.product.system.device", SPOOF_DEVICE);
        SPOOFED_PROPS.put("ro.product.system.manufacturer", SPOOF_MANUFACTURER);
        SPOOFED_PROPS.put("ro.product.system.brand", SPOOF_BRAND);
    }

    public static void init(String logDir, String pineLibPath) {
        sLogDir = logDir;
        logStatic("ZygiskSIM Java payload initializing (Pine)...");
        logStatic("  Pine library path from native: " + pineLibPath);

        try {
            loadPineLibrary(pineLibPath);

            // Spoof device identity FIRST (before any app code reads Build fields)
            spoofBuildFields();

            hookApplicationOnCreate();
            hookEuiccManagerIsEnabled();
            hookEuiccManagerGetEid();
            hookPackageManagerHasSystemFeature();
            hookSystemPropertiesGet();
            hookForActivationCode();

            logStatic("All Pine hooks successfully installed!");
        } catch (Throwable t) {
            logStatic("Hook initialization failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            logStackTrace(t);
        }
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
    // Hook: Application.onCreate() — capture application context
    // =========================================================================

    private static void hookApplicationOnCreate() throws Exception {
        Method onCreate = Application.class.getDeclaredMethod("onCreate");
        Pine.hook(onCreate, new MethodHook() {
            @Override
            public void beforeCall(Pine.CallFrame callFrame) {
                if (sApplication == null) {
                    sApplication = (Application) callFrame.thisObject;
                    logStatic("Application context captured: " + sApplication.getPackageName());
                }
            }
        });
        logStatic("  Hooked Application.onCreate()");
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
                    logStatic("Spoofed EuiccManager.getEid() -> " + FAKE_EID);
                    callFrame.setResult(FAKE_EID);
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
            Method hasSystemFeature = PackageManager.class.getDeclaredMethod("hasSystemFeature", String.class);
            Pine.hook(hasSystemFeature, new MethodHook() {
                @Override
                public void afterCall(Pine.CallFrame callFrame) {
                    String featureName = (String) callFrame.args[0];
                    if (featureName != null && featureName.startsWith("android.hardware.telephony.euicc")) {
                        Boolean originalResult = (Boolean) callFrame.getResult();
                        if (originalResult == null || !originalResult) {
                            logStatic("Spoofed hasSystemFeature(\"" + featureName + "\") -> true (was " + originalResult + ")");
                            callFrame.setResult(true);
                        }
                    }
                }
            });
            logStatic("  Hooked PackageManager.hasSystemFeature()");
        } catch (Throwable t) {
            logStatic("  Failed to hook PackageManager.hasSystemFeature(): " + t.getMessage());
        }
    }

    // =========================================================================
    // Hook: DownloadableSubscription.forActivationCode() → clipboard copy
    // =========================================================================

    private static void hookForActivationCode() throws Exception {
        Method forActivationCode = DownloadableSubscription.class.getDeclaredMethod("forActivationCode", String.class);
        Pine.hook(forActivationCode, new MethodHook() {
            @Override
            public void afterCall(Pine.CallFrame callFrame) {
                Object result = callFrame.getResult();
                if (result instanceof DownloadableSubscription) {
                    String code = (String) callFrame.args[0];
                    handleActivationCode(code);
                }
            }
        });
        logStatic("  Hooked DownloadableSubscription.forActivationCode()");
    }

    // =========================================================================
    // Spoof: android.os.Build static fields — make device look like Pixel 8
    // This runs via reflection to modify the final static fields in-process.
    // Only affects THIS app process, not the system.
    // =========================================================================

    private static void spoofBuildFields() {
        try {
            Class<?> buildClass = android.os.Build.class;
            setStaticField(buildClass, "MODEL", SPOOF_MODEL);
            setStaticField(buildClass, "DEVICE", SPOOF_DEVICE);
            setStaticField(buildClass, "MANUFACTURER", SPOOF_MANUFACTURER);
            setStaticField(buildClass, "BRAND", SPOOF_BRAND);
            setStaticField(buildClass, "PRODUCT", SPOOF_PRODUCT);
            logStatic("  Spoofed Build fields: MODEL=" + SPOOF_MODEL + ", DEVICE=" + SPOOF_DEVICE
                    + ", MANUFACTURER=" + SPOOF_MANUFACTURER + ", BRAND=" + SPOOF_BRAND);
        } catch (Throwable t) {
            logStatic("  Failed to spoof Build fields: " + t.getMessage());
        }
    }

    private static void setStaticField(Class<?> clazz, String fieldName, Object value) {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);

            // Remove 'final' modifier via Field's own modifiers field
            // On Android, the field holding modifiers is called "accessFlags"
            try {
                Field modifiersField = Field.class.getDeclaredField("accessFlags");
                modifiersField.setAccessible(true);
                modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
            } catch (NoSuchFieldException e) {
                // Try the standard Java name
                try {
                    Field modifiersField = Field.class.getDeclaredField("modifiers");
                    modifiersField.setAccessible(true);
                    modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);
                } catch (NoSuchFieldException ignored) {
                    // On some Android versions, final removal isn't needed for set()
                }
            }

            field.set(null, value);
        } catch (Throwable t) {
            logStatic("    Failed to set Build." + fieldName + ": " + t.getMessage());
        }
    }

    // =========================================================================
    // Hook: SystemProperties.get() — intercept ro.product.* system properties
    // Some apps read device info via SystemProperties instead of Build fields.
    // =========================================================================

    private static void hookSystemPropertiesGet() {
        try {
            Class<?> sysPropClass = Class.forName("android.os.SystemProperties");

            // Hook get(String key)
            try {
                Method get1 = sysPropClass.getDeclaredMethod("get", String.class);
                Pine.hook(get1, new MethodHook() {
                    @Override
                    public void afterCall(Pine.CallFrame callFrame) {
                        String key = (String) callFrame.args[0];
                        if (key != null && SPOOFED_PROPS.containsKey(key)) {
                            String spoofed = SPOOFED_PROPS.get(key);
                            logStatic("Spoofed SystemProperties.get(\"" + key + "\") -> " + spoofed);
                            callFrame.setResult(spoofed);
                        }
                    }
                });
            } catch (Throwable ignored) {}

            // Hook get(String key, String def)
            try {
                Method get2 = sysPropClass.getDeclaredMethod("get", String.class, String.class);
                Pine.hook(get2, new MethodHook() {
                    @Override
                    public void afterCall(Pine.CallFrame callFrame) {
                        String key = (String) callFrame.args[0];
                        if (key != null && SPOOFED_PROPS.containsKey(key)) {
                            String spoofed = SPOOFED_PROPS.get(key);
                            logStatic("Spoofed SystemProperties.get(\"" + key + "\", ...) -> " + spoofed);
                            callFrame.setResult(spoofed);
                        }
                    }
                });
            } catch (Throwable ignored) {}

            logStatic("  Hooked SystemProperties.get() for device spoofing");
        } catch (Throwable t) {
            logStatic("  Failed to hook SystemProperties: " + t.getMessage());
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
