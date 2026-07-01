package com.zygisksim;

import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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
 * Uses the Pine hooking framework to perfectly mimic the behavior of the SureSIM Xposed module.
 * - Hooks EuiccManager.isEnabled() to always return true.
 * - Hooks DownloadableSubscription.forActivationCode() to copy the code to the clipboard.
 * - Hooks Application.onCreate() to grab the Context for the ClipboardManager.
 */
public class HookEntry {

    private static String sLogDir;
    private static Application sApplication;

    public static void init(String logDir) {
        sLogDir = logDir;
        logStatic("ZygiskSIM Java payload initializing (Pine)...");

        try {
            loadPineLibrary();
            
            // Pine.ensureInitialized() should be called early
            // But loading the library is usually enough before the first hook
            
            hookApplicationOnCreate();
            hookEuiccManagerIsEnabled();
            hookForActivationCode();

            logStatic("Pine hooks successfully installed!");
        } catch (Throwable t) {
            logStatic("Hook initialization failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static void loadPineLibrary() throws Exception {
        // Determine the current process ABI
        String abi;
        if (android.os.Process.is64Bit()) {
            abi = android.os.Build.SUPPORTED_64_BIT_ABIS[0];
        } else {
            abi = android.os.Build.SUPPORTED_32_BIT_ABIS[0];
        }
        
        // Path where Magisk/KernelSU installed our pine library
        String soPath = "/data/adb/modules/zygisksim/pine/" + abi + "/libpine.so";
        
        File soFile = new File(soPath);
        if (!soFile.exists()) {
            throw new Exception("libpine.so not found at " + soPath);
        }
        
        System.load(soPath);
        logStatic("Loaded libpine.so for " + abi);
    }

    private static void hookApplicationOnCreate() throws Exception {
        Method onCreate = Application.class.getDeclaredMethod("onCreate");
        Pine.hook(onCreate, new MethodHook() {
            @Override
            public void beforeCall(Pine.CallFrame callFrame) {
                if (sApplication == null) {
                    sApplication = (Application) callFrame.thisObject;
                    logStatic("Application context captured.");
                }
            }
        });
    }

    private static void hookEuiccManagerIsEnabled() throws Exception {
        Method isEnabled = EuiccManager.class.getDeclaredMethod("isEnabled");
        Pine.hook(isEnabled, new MethodHook() {
            @Override
            public void beforeCall(Pine.CallFrame callFrame) {
                logStatic("Spoofed EuiccManager.isEnabled() -> true");
                callFrame.setResult(true);
            }
        });
    }

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
    }

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
}
