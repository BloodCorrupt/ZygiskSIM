package com.zygisksim;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * ZygiskSIM Hook Entry Point
 *
 * This class is loaded via InMemoryDexClassLoader from the Zygisk native module.
 * It hooks EuiccManager methods using ArtMethod swap (memcpy) provided by the
 * native nativeHookMethod() function.
 *
 * Hooks:
 *   1. EuiccManager.isEnabled()          -> always returns true
 *   2. EuiccManager.downloadSubscription -> logs activation code + no-op
 */
public class HookEntry {

    private static String sLogDir;

    // =====================================================================
    // Native method provided by the Zygisk C++ module (registered at runtime)
    // =====================================================================

    /**
     * Swap ArtMethod data: after calling this, invocations of targetMethod
     * will execute hookMethod's bytecode instead.
     */
    private static native void nativeHookMethod(Method target, Method hook);

    // =====================================================================
    // ArtMethod size helpers - MUST be adjacent in DEX method ordering
    // The native module uses the address difference between these two methods
    // to determine the size of an ArtMethod struct at runtime.
    //
    // Using names that sort adjacent alphabetically: "sizeHelper1" < "sizeHelper2"
    // Both must be static with identical signatures for reliable ordering.
    // =====================================================================

    public static void sizeHelper1() { /* do not remove */ }
    public static void sizeHelper2() { /* do not remove */ }

    // =====================================================================
    // Hook replacement methods
    //
    // After ArtMethod swap, 'this' will be the original object (EuiccManager),
    // NOT a HookEntry instance. These methods must NOT access any HookEntry fields
    // via 'this'. Parameters are received exactly as the original method declares.
    // =====================================================================

    /**
     * Replacement for EuiccManager.isEnabled()
     * Signature must match: instance method, no params, returns boolean
     *
     * After swap, 'this' = EuiccManager instance (ignored)
     */
    public boolean hookIsEnabled() {
        logStatic("isEnabled() called → returning true (eSIM spoofed)");
        return true;
    }

    /**
     * Replacement for EuiccManager.downloadSubscription(DownloadableSubscription, boolean, PendingIntent)
     * Using Object types for parameter compatibility (same register layout as real types)
     *
     * After swap, 'this' = EuiccManager instance (ignored)
     * Parameters: subscription (DownloadableSubscription), switchAfterDownload (boolean), callbackIntent (PendingIntent)
     */
    public void hookDownloadSubscription(Object subscription, boolean switchAfterDownload, Object callbackIntent) {
        String activationCode = "<unknown>";

        try {
            // Extract activation code via reflection on DownloadableSubscription
            Field codeField = subscription.getClass().getDeclaredField("encodedActivationCode");
            codeField.setAccessible(true);
            Object codeObj = codeField.get(subscription);
            if (codeObj != null) {
                activationCode = codeObj.toString();
            }
        } catch (Exception e) {
            logStatic("Could not extract activation code: " + e.getMessage());
        }

        logStatic("========================================");
        logStatic("eSIM DOWNLOAD INTERCEPTED (no-op)");
        logStatic("  Activation Code: " + activationCode);
        logStatic("  Switch After Download: " + switchAfterDownload);
        logStatic("  Intent: " + (callbackIntent != null ? callbackIntent.toString() : "null"));
        logStatic("========================================");

        // Do NOT call the original method.
        // The device doesn't have real eSIM hardware, so the download would fail anyway.
        // The activation code is logged for the user to use elsewhere.
    }

    // =====================================================================
    // Initialization - called from native postAppSpecialize
    // =====================================================================

    /**
     * Main entry point called by the Zygisk native module after loading this DEX.
     * Sets up all hooks via ArtMethod swapping.
     *
     * @param logDir Path to the module's log directory (e.g. /data/adb/modules/zygisksim/logs)
     */
    public static void init(String logDir) {
        sLogDir = logDir;

        logStatic("ZygiskSIM HookEntry initializing...");

        try {
            // Resolve framework classes via reflection (avoids hard compile-time dependency)
            Class<?> euiccManagerClass = Class.forName("android.telephony.euicc.EuiccManager");
            Class<?> downloadableSubClass = Class.forName("android.telephony.euicc.DownloadableSubscription");
            Class<?> pendingIntentClass = Class.forName("android.app.PendingIntent");

            logStatic("Framework classes resolved successfully");

            // --- Hook 1: EuiccManager.isEnabled() -> always true ---
            hookIsEnabledMethod(euiccManagerClass);

            // --- Hook 2: EuiccManager.downloadSubscription() -> log + no-op ---
            hookDownloadSubscriptionMethod(euiccManagerClass, downloadableSubClass, pendingIntentClass);

        } catch (ClassNotFoundException e) {
            logStatic("EuiccManager not available on this device (requires API 28+). Skipping hooks.");
        } catch (Exception e) {
            logStatic("Hook initialization failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // =====================================================================
    // Hook setup methods
    // =====================================================================

    private static void hookIsEnabledMethod(Class<?> euiccManagerClass) {
        try {
            Method target = euiccManagerClass.getDeclaredMethod("isEnabled");
            Method hook = HookEntry.class.getDeclaredMethod("hookIsEnabled");

            logStatic("Hooking EuiccManager.isEnabled()...");
            nativeHookMethod(target, hook);
            logStatic("✓ EuiccManager.isEnabled() hooked successfully");

        } catch (NoSuchMethodException e) {
            logStatic("✗ isEnabled() method not found: " + e.getMessage());
        } catch (Exception e) {
            logStatic("✗ Failed to hook isEnabled(): " + e.getMessage());
        }
    }

    private static void hookDownloadSubscriptionMethod(
            Class<?> euiccManagerClass,
            Class<?> downloadableSubClass,
            Class<?> pendingIntentClass) {
        try {
            Method target = euiccManagerClass.getDeclaredMethod(
                    "downloadSubscription",
                    downloadableSubClass,
                    boolean.class,
                    pendingIntentClass);

            Method hook = HookEntry.class.getDeclaredMethod(
                    "hookDownloadSubscription",
                    Object.class,
                    boolean.class,
                    Object.class);

            logStatic("Hooking EuiccManager.downloadSubscription()...");
            nativeHookMethod(target, hook);
            logStatic("✓ EuiccManager.downloadSubscription() hooked successfully");

        } catch (NoSuchMethodException e) {
            logStatic("✗ downloadSubscription() method not found: " + e.getMessage());
        } catch (Exception e) {
            logStatic("✗ Failed to hook downloadSubscription(): " + e.getMessage());
        }
    }

    // =====================================================================
    // Logging
    // =====================================================================

    /**
     * Log to both Android logcat and the module's log file.
     * Uses static access only (safe to call from any context including swapped methods).
     */
    private static void logStatic(String message) {
        // Always log to logcat
        try {
            android.util.Log.i("ZygiskSIM", message);
        } catch (Exception ignored) {}

        // Also write to log file if log directory is set
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
            // If file write fails, at least logcat has it
            try {
                android.util.Log.w("ZygiskSIM", "Log file write failed: " + e.getMessage());
            } catch (Exception ignored) {}
        }
    }
}
