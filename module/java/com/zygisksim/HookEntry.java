package com.zygisksim;

import android.app.Application;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.telephony.euicc.DownloadableSubscription;
import android.telephony.euicc.EuiccManager;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import org.json.JSONObject;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

/**
 * Entry point for ZygiskSIM.
 * Reverted to Pine hooking framework. Uses dalvik.system.VMRuntime to bypass Hidden API policy
 * and disables Pine's native hidden API bypass to prevent SIGSEGV native crashes on newer Android versions.
 */
public class HookEntry {

    private static String sLogDir = null;
    private static Application sApplication = null;

    // Config defaults
    private static String sEid = "89049032005008882600033827513789";
    private static String sSpoofModel = "Pixel 8";
    private static String sSpoofDevice = "shiba";
    private static String sSpoofManufacturer = "Google";
    private static String sSpoofBrand = "google";
    private static String sSpoofProduct = "shiba";

    public static void init(String logDir, String pineLibPath, String configJson) {
        sLogDir = logDir;
        logStatic("ZygiskSIM Java payload initializing (Pine Architecture)...");

        // 1. Parse config.json if provided
        parseConfig(configJson);

        // 2. Spoof device identity (pure reflection)
        spoofBuildFields();

        // 3. Bypass Hidden API restrictions via dalvik.system.VMRuntime in Java
        bypassHiddenApiRestrictions();

        // 4. Disable Pine's native Hidden API bypass to prevent native SIGSEGV
        disablePineNativeHiddenApiBypass();

        // 5. Load Pine library
        try {
            loadPineLibrary(pineLibPath);
        } catch (Throwable t) {
            logStatic("Pine library load FAILED — hooks will not be installed: " + t.getMessage());
            logStackTrace(t);
            return;
        }

        // 6. Install hooks immediately
        installHooks();
    }

    private static void bypassHiddenApiRestrictions() {
        try {
            Class<?> vmRuntimeClass = Class.forName("dalvik.system.VMRuntime");
            Method getRuntimeMethod = vmRuntimeClass.getDeclaredMethod("getRuntime");
            Object vmRuntime = getRuntimeMethod.invoke(null);
            Method setExemptionsMethod = vmRuntimeClass.getDeclaredMethod("setHiddenApiExemptions", String[].class);
            setExemptionsMethod.invoke(vmRuntime, new Object[]{new String[]{"L"}});
            logStatic("Bypassed hidden API restrictions via VMRuntime.");
        } catch (Throwable t) {
            logStatic("Failed to bypass hidden API restrictions via VMRuntime: " + t.getMessage());
        }
    }

    private static void disablePineNativeHiddenApiBypass() {
        try {
            Class<?> pineConfigClass = Class.forName("top.canyie.pine.PineConfig");
            
            Field f1 = pineConfigClass.getDeclaredField("disableHiddenApiPolicy");
            f1.setAccessible(true);
            f1.setBoolean(null, false);

            Field f2 = pineConfigClass.getDeclaredField("disableHiddenApiPolicyForPlatformDomain");
            f2.setAccessible(true);
            f2.setBoolean(null, false);
            logStatic("Disabled Pine's native HiddenAPI bypass.");
        } catch (Throwable t) {
            logStatic("Failed to disable Pine HiddenAPI bypass: " + t.getMessage());
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

    private static void configurePineLoader(final String loadedPath) {
        try {
            Class<?> pineConfigClass = Class.forName("top.canyie.pine.PineConfig");
            Field libLoaderField = pineConfigClass.getDeclaredField("libLoader");
            libLoaderField.setAccessible(true);

            Class<?> loaderInterface = libLoaderField.getType();

            Object customLoader = java.lang.reflect.Proxy.newProxyInstance(
                loaderInterface.getClassLoader(),
                new Class<?>[]{ loaderInterface },
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if (method.getName().equals("loadLib")) {
                            System.load(loadedPath);
                        }
                        return null;
                    }
                }
            );

            libLoaderField.set(null, customLoader);

            Pine.ensureInitialized();
            logStatic("  Pine initialized with custom lib loader");
        } catch (Throwable t) {
            logStatic("  Warning: Pine custom loader setup failed: " + t.getMessage());
        }
    }

    private static void installHooks() {
        logStatic("Installing Pine hooks...");
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
            logStatic("  EuiccManager.getEuiccInfo() not available on this API level");
        }
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
        logStatic("  Hooked EuiccManager.isEnabled()");
    }

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
        } catch (Throwable t) {
            logStatic("  EuiccManager.getEid() not available on this API level");
        }
    }

    private static void hookPackageManagerHasSystemFeature() {
        try {
            Class<?> apmClass = Class.forName("android.app.ApplicationPackageManager");
            
            // Hook 1: hasSystemFeature(String)
            try {
                Method m1 = apmClass.getDeclaredMethod("hasSystemFeature", String.class);
                Pine.hook(m1, new MethodHook() {
                    @Override
                    public void afterCall(Pine.CallFrame callFrame) {
                        String featureName = (String) callFrame.args[0];
                        if (featureName != null && featureName.startsWith("android.hardware.telephony.euicc")) {
                            callFrame.setResult(true);
                        }
                    }
                });
            } catch (Throwable t) {
                logStatic("  Failed to hook hasSystemFeature(String): " + t.getMessage());
            }

            // Hook 2: hasSystemFeature(String, int)
            try {
                Method m2 = apmClass.getDeclaredMethod("hasSystemFeature", String.class, int.class);
                Pine.hook(m2, new MethodHook() {
                    @Override
                    public void afterCall(Pine.CallFrame callFrame) {
                        String featureName = (String) callFrame.args[0];
                        if (featureName != null && featureName.startsWith("android.hardware.telephony.euicc")) {
                            callFrame.setResult(true);
                        }
                    }
                });
            } catch (Throwable t) {
                logStatic("  Failed to hook hasSystemFeature(String, int): " + t.getMessage());
            }

            logStatic("  Hooked ApplicationPackageManager.hasSystemFeature()");
        } catch (Throwable t) {
            logStatic("  Failed to hook hasSystemFeature: " + t.getMessage());
        }
    }

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

    private static void hookEuiccManagerDownloadSubscription() {
        try {
            Class<?> euiccManagerClass = Class.forName("android.telephony.euicc.EuiccManager");
            Method download = euiccManagerClass.getDeclaredMethod("downloadSubscription", 
                DownloadableSubscription.class, boolean.class, PendingIntent.class);
            
            Pine.hook(download, new MethodHook() {
                @Override
                public void beforeCall(Pine.CallFrame callFrame) {
                    logStatic("Intercepted EuiccManager.downloadSubscription()!");
                    
                    callFrame.setResult(null);

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

                    PendingIntent callbackIntent = (PendingIntent) callFrame.args[2];
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
        } catch (Throwable t) {
            logStatic("  Failed to hook downloadSubscription(): " + t.getMessage());
        }
    }

    private static Context getApplicationContext() {
        if (sApplication != null) return sApplication;
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method currentApplicationMethod = activityThreadClass.getDeclaredMethod("currentApplication");
            sApplication = (Application) currentApplicationMethod.invoke(null);
        } catch (Exception ignored) {}
        return sApplication;
    }

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

    private static void handleActivationCode(String code) {
        logStatic("========================================");
        logStatic("eSIM DOWNLOAD INTERCEPTED (Pine)");
        logStatic("  Activation Code: " + code);
        logStatic("========================================");

        Application app = getApplicationContext();
        if (app != null) {
            try {
                ClipboardManager clipboard = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
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

    private static void parseConfig(String configJson) {
        if (configJson == null || configJson.trim().isEmpty()) {
            logStatic("No config.json provided from native layer.");
            return;
        }

        try {
            JSONObject root = new JSONObject(configJson);
            if (root.has("eid")) {
                sEid = root.getString("eid");
            }
            if (root.has("device")) {
                JSONObject dev = root.getJSONObject("device");
                sSpoofDevice = dev.optString("device", sSpoofDevice);
                sSpoofModel = dev.optString("model", sSpoofModel);
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
