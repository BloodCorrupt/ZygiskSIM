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
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

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

    public static void init(String logDir) {
        sLogDir = logDir;
        logStatic("ZygiskSIM Java payload initializing (Pine)...");

        try {
            loadPineLibrary();

            hookApplicationOnCreate();
            hookEuiccManagerIsEnabled();
            hookEuiccManagerGetEid();
            hookPackageManagerHasSystemFeature();
            hookForActivationCode();

            logStatic("All Pine hooks successfully installed!");
        } catch (Throwable t) {
            logStatic("Hook initialization failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            logStackTrace(t);
        }
    }

    private static void loadPineLibrary() throws Exception {
        // Try multiple loading strategies for compatibility with NeoZygisk / different mount namespaces
        try {
            System.loadLibrary("pine");
            logStatic("Successfully loaded libpine.so via System.loadLibrary");
            return;
        } catch (UnsatisfiedLinkError e) {
            logStatic("System.loadLibrary(\"pine\") failed: " + e.getMessage());
        }

        // Fallback: try loading from explicit system paths
        String[] fallbackPaths = {
            "/system/lib64/libpine.so",
            "/system/lib/libpine.so",
        };
        for (String path : fallbackPaths) {
            try {
                System.load(path);
                logStatic("Successfully loaded Pine from fallback path: " + path);
                return;
            } catch (UnsatisfiedLinkError e) {
                logStatic("Fallback load failed for " + path + ": " + e.getMessage());
            }
        }

        throw new Exception("Failed to load libpine.so from any path");
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
