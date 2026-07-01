package com.zygisksim;

import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.app.PendingIntent;
import android.os.IBinder;
import android.telephony.euicc.DownloadableSubscription;
import android.telephony.euicc.EuiccInfo;

import org.json.JSONObject;

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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

/**
 * Entry point for ZygiskSIM.
 * Hybrid mode:
 * - Uses dynamic proxy & disabled cache for PackageManager.hasSystemFeature (prevents startup crashes).
 * - Uses Pine for EuiccManager hooks (which works perfectly for eSIM spoofing).
 * - Disables Pine HiddenAPI bypass & removes SystemProperties hook (fixes SIGSEGV/resolution crashes).
 */
public class HookEntry {

    private static String sLogDir = null;
    private static String sEid = "89049032005008882600033827513789";
    private static String sSpoofModel = "Pixel 8";
    private static String sSpoofDevice = "shiba";
    private static String sSpoofManufacturer = "Google";
    private static String sSpoofBrand = "google";
    private static String sSpoofProduct = "shiba";

    private static Application sApplication = null;

    public static void init(String logDir, String pineLibPath, String configJson) {
        sLogDir = logDir;
        logStatic("ZygiskSIM Java payload initializing (Hybrid Pine/Reflection)...");
        logStatic("  Pine library path: " + pineLibPath);

        // Parse config.json
        parseConfig(configJson);
        buildSpoofedProps();

        // 1. Spoof device identity
        spoofBuildFields();

        // 2. Mock IPackageManager & disable PropertyInvalidatedCache (safely bypasses Pine for hasSystemFeature)
        mockPackageManager();

        // 3. Disable Pine's dangerous native Hidden API bypass (avoids reflection SIGSEGVs)
        try {
            Class<?> pineConfig = Class.forName("top.canyie.pine.PineConfig");
            
            Field f1 = pineConfig.getDeclaredField("disableHiddenApiPolicy");
            f1.setAccessible(true);
            f1.setBoolean(null, false); // false = do NOT bypass natively

            Field f2 = pineConfig.getDeclaredField("disableHiddenApiPolicyForPlatformDomain");
            f2.setAccessible(true);
            f2.setBoolean(null, false); // false = do NOT bypass natively
            
            logStatic("Disabled Pine's native HiddenAPI bypass.");
        } catch (Throwable t) {
            logStatic("Could not disable Pine HiddenAPI bypass: " + t.getMessage());
        }

        // 4. Load Pine and hook EuiccManager
        try {
            System.load(pineLibPath);
            logStatic("Pine library loaded successfully.");
            installEuiccHooks();
        } catch (Throwable t) {
            logStatic("Pine library load FAILED — hooks will not be installed: " + t.getMessage());
            logStackTrace(t);
        }
    }

    // =========================================================================
    // Safe mock of PackageManager (Dynamic Proxy)
    // =========================================================================

    private static void mockPackageManager() {
        try {
            // Disable PropertyInvalidatedCache so that hasSystemFeature hits our proxy instead of the local cache
            try {
                Class<?> cacheClass = Class.forName("android.app.PropertyInvalidatedCache");
                try {
                    Method disable = cacheClass.getDeclaredMethod("disableForTestMode");
                    disable.setAccessible(true);
                    disable.invoke(null);
                    logStatic("Disabled PropertyInvalidatedCache (test mode).");
                } catch (Exception e) {}
                try {
                    Method disable = cacheClass.getDeclaredMethod("disableForCurrentProcess");
                    disable.setAccessible(true);
                    disable.invoke(null);
                    logStatic("Disabled PropertyInvalidatedCache (current process).");
                } catch (Exception e) {}
            } catch (Throwable t) {
                logStatic("PropertyInvalidatedCache disable failed: " + t.getMessage());
            }

            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            final Field sPackageManagerField = activityThreadClass.getDeclaredField("sPackageManager");
            sPackageManagerField.setAccessible(true);

            Class<?> iPackageManagerClass = Class.forName("android.content.pm.IPackageManager");
            Object mockPm = Proxy.newProxyInstance(
                    iPackageManagerClass.getClassLoader(),
                    new Class<?>[]{iPackageManagerClass},
                    new InvocationHandler() {
                        private Object mRealPm = null;

                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            if ("hasSystemFeature".equals(method.getName())) {
                                if (args != null && args.length > 0) {
                                    String feature = (String) args[0];
                                    if ("android.hardware.telephony.euicc".equals(feature)) {
                                        return true; // Fake eSIM hardware support
                                    }
                                }
                            }
                            
                            if (mRealPm == null) {
                                try {
                                    Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
                                    Method getService = serviceManagerClass.getDeclaredMethod("getService", String.class);
                                    IBinder pmBinder = (IBinder) getService.invoke(null, "package");
                                    Class<?> iPmStubClass = Class.forName("android.content.pm.IPackageManager$Stub");
                                    Method asInterface = iPmStubClass.getDeclaredMethod("asInterface", IBinder.class);
                                    mRealPm = asInterface.invoke(null, pmBinder);
                                } catch (Throwable t) {
                                    logStatic("Failed to lazily resolve real IPackageManager: " + t.getMessage());
                                }
                            }
                            
                            if (mRealPm == null) {
                                Class<?> retType = method.getReturnType();
                                if (retType == boolean.class) return false;
                                if (retType == int.class) return 0;
                                if (retType == long.class) return 0L;
                                return null;
                            }
                            
                            return method.invoke(mRealPm, args);
                        }
                    }
            );

            sPackageManagerField.set(null, mockPm);
            logStatic("Injected lazy mock IPackageManager proxy into ActivityThread.");
        } catch (Throwable t) {
            logStatic("Failed to mock IPackageManager: " + t.getMessage());
            logStackTrace(t);
        }
    }

    // =========================================================================
    // Stable Pine Hooks for EuiccManager
    // =========================================================================

    private static void installEuiccHooks() {
        hookEuiccManagerIsEnabled();
        hookEuiccManagerGetEid();
        hookEuiccManagerGetEuiccInfo();
        hookForActivationCode();
        hookEuiccManagerDownloadSubscription();
    }

    private static void hookEuiccManagerIsEnabled() {
        try {
            Class<?> euiccManagerClass = Class.forName("android.telephony.euicc.EuiccManager");
            Method isEnabled = euiccManagerClass.getDeclaredMethod("isEnabled");
            Pine.hook(isEnabled, new MethodHook() {
                @Override
                public void beforeCall(Pine.CallFrame callFrame) {
                    callFrame.setResult(true);
                }
            });
            logStatic("  Hooked EuiccManager.isEnabled()");
        } catch (Throwable t) {
            logStatic("  Failed to hook isEnabled(): " + t.getMessage());
        }
    }

    private static void hookEuiccManagerGetEid() {
        try {
            Class<?> euiccManagerClass = Class.forName("android.telephony.euicc.EuiccManager");
            Method getEid = euiccManagerClass.getDeclaredMethod("getEid");
            Pine.hook(getEid, new MethodHook() {
                @Override
                public void beforeCall(Pine.CallFrame callFrame) {
                    callFrame.setResult(sEid);
                }
            });
            logStatic("  Hooked EuiccManager.getEid()");
        } catch (Throwable t) {
            logStatic("  Failed to hook getEid(): " + t.getMessage());
        }
    }

    private static void hookEuiccManagerGetEuiccInfo() {
        try {
            Class<?> euiccManagerClass = Class.forName("android.telephony.euicc.EuiccManager");
            Method getEuiccInfo = euiccManagerClass.getDeclaredMethod("getEuiccInfo");
            Pine.hook(getEuiccInfo, new MethodHook() {
                @Override
                public void beforeCall(Pine.CallFrame callFrame) {
                    try {
                        Class<?> euiccInfoClass = Class.forName("android.telephony.euicc.EuiccInfo");
                        Constructor<?> constructor = euiccInfoClass.getDeclaredConstructor(String.class);
                        constructor.setAccessible(true);
                        Object mockInfo = constructor.newInstance("1.0");
                        callFrame.setResult(mockInfo);
                    } catch (Throwable t) {
                        logStatic("    Failed to construct mock EuiccInfo: " + t.getMessage());
                    }
                }
            });
            logStatic("  Hooked EuiccManager.getEuiccInfo()");
        } catch (Throwable t) {
            logStatic("  Failed to hook getEuiccInfo(): " + t.getMessage());
        }
    }

    private static void hookForActivationCode() {
        try {
            Class<?> downloadableSubscriptionClass = Class.forName("android.telephony.euicc.DownloadableSubscription");
            Method forActivationCode = downloadableSubscriptionClass.getDeclaredMethod("forActivationCode", String.class);
            Pine.hook(forActivationCode, new MethodHook() {
                @Override
                public void beforeCall(Pine.CallFrame callFrame) {
                    String code = (String) callFrame.args[0];
                    handleActivationCode(code);
                }
            });
            logStatic("  Hooked DownloadableSubscription.forActivationCode()");
        } catch (Throwable t) {
            logStatic("  Failed to hook forActivationCode(): " + t.getMessage());
        }
    }

    private static void hookEuiccManagerDownloadSubscription() {
        try {
            Class<?> euiccManagerClass = Class.forName("android.telephony.euicc.EuiccManager");
            Method download = null;
            // Iterate over methods to support different signatures on older/newer Android versions
            for (Method m : euiccManagerClass.getDeclaredMethods()) {
                if ("downloadSubscription".equals(m.getName())) {
                    download = m;
                    break;
                }
            }
            if (download != null) {
                Pine.hook(download, new MethodHook() {
                    @Override
                    public void beforeCall(Pine.CallFrame callFrame) {
                        logStatic("Intercepted EuiccManager.downloadSubscription()!");
                        callFrame.setResult(null);

                        // Extract activation code from arguments
                        try {
                            for (Object arg : callFrame.args) {
                                if (arg instanceof DownloadableSubscription) {
                                    String code = ((DownloadableSubscription) arg).getEncodedActivationCode();
                                    if (code != null) {
                                        handleActivationCode(code);
                                    }
                                    break;
                                }
                            }
                        } catch (Throwable t) {
                            logStatic("Failed to extract code from subscription: " + t.getMessage());
                        }

                        // Trigger the callback intent
                        PendingIntent callbackIntent = null;
                        for (Object arg : callFrame.args) {
                            if (arg instanceof PendingIntent) {
                                callbackIntent = (PendingIntent) arg;
                                break;
                            }
                        }
                        if (callbackIntent != null) {
                            try {
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
            }
        } catch (Throwable t) {
            logStatic("  Failed to hook downloadSubscription(): " + t.getMessage());
        }
    }

    // =========================================================================
    // Utilities
    // =========================================================================

    private static Context getApplicationContext() {
        if (sApplication != null) return sApplication;
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method currentApplicationMethod = activityThreadClass.getDeclaredMethod("currentApplication");
            sApplication = (Application) currentApplicationMethod.invoke(null);
        } catch (Exception ignored) {}
        return sApplication;
    }

    private static void parseConfig(String configJson) {
        if (configJson == null || configJson.trim().isEmpty()) {
            logStatic("No config.json provided.");
            return;
        }

        try {
            JSONObject root = new JSONObject(configJson);
            if (root.has("eid")) {
                sEid = root.getString("eid");
            }
            if (root.has("device")) {
                sSpoofDevice = root.getString("device");
            }
            if (root.has("model")) {
                sSpoofModel = root.getString("model");
            }
            logStatic("  Parsed config.json successfully. EID=" + sEid + ", Model=" + sSpoofModel);
        } catch (Throwable t) {
            logStatic("  Failed to parse config.json, using defaults: " + t.getMessage());
        }
    }

    private static void buildSpoofedProps() {
        switch (sSpoofDevice) {
            case "shiba":
                sSpoofManufacturer = "Google";
                sSpoofBrand = "google";
                sSpoofProduct = "shiba";
                break;
            case "husky":
                sSpoofManufacturer = "Google";
                sSpoofBrand = "google";
                sSpoofProduct = "husky";
                break;
            case "felix":
                sSpoofManufacturer = "Google";
                sSpoofBrand = "google";
                sSpoofProduct = "felix";
                break;
            default:
                break;
        }
    }

    private static void spoofBuildFields() {
        try {
            Class<?> buildClass = android.os.Build.class;
            setStaticField(buildClass, "MODEL", sSpoofModel);
            setStaticField(buildClass, "DEVICE", sSpoofDevice);
            setStaticField(buildClass, "MANUFACTURER", sSpoofManufacturer);
            setStaticField(buildClass, "BRAND", sSpoofBrand);
            setStaticField(buildClass, "PRODUCT", sSpoofProduct);
            logStatic("  Spoofed Build fields: MODEL=" + sSpoofModel + ", DEVICE=" + sSpoofDevice);
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

    private static void handleActivationCode(String code) {
        logStatic("========================================");
        logStatic("eSIM DOWNLOAD INTERCEPTED (Hybrid)");
        logStatic("  Activation Code: " + code);
        logStatic("========================================");

        Context ctx = getApplicationContext();
        if (ctx != null) {
            try {
                ClipboardManager clipboard = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    ClipData clip = ClipData.newPlainText("Encoded eSIM activation code", code);
                    clipboard.setPrimaryClip(clip);
                    logStatic("Code copied to clipboard successfully.");
                }
            } catch (Exception e) {
                logStatic("Failed to copy to clipboard: " + e.getMessage());
            }
        }
    }

    private static void logStatic(String message) {
        try {
            android.util.Log.i("ZygiskSIM", message);
        } catch (Exception ignored) {}

        if (sLogDir == null) return;
        try {
            File dir = new File(sLogDir);
            if (!dir.exists()) dir.mkdirs();
            File logFile = new File(dir, "esim_log.txt");
            PrintWriter pw = new PrintWriter(new FileWriter(logFile, true));
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            pw.println("[" + timestamp + "] " + message);
            pw.flush();
            pw.close();
        } catch (Exception e) {}
    }

    private static void logStackTrace(Throwable t) {
        try {
            android.util.Log.e("ZygiskSIM", "Stack trace:", t);
        } catch (Exception ignored) {}

        if (sLogDir == null) return;
        try {
            File dir = new File(sLogDir);
            if (!dir.exists()) dir.mkdirs();
            File logFile = new File(dir, "esim_log.txt");
            PrintWriter pw = new PrintWriter(new FileWriter(logFile, true));
            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            pw.print("[" + timestamp + "] ");
            t.printStackTrace(pw);
            pw.flush();
            pw.close();
        } catch (Exception ignored) {}
    }
}
