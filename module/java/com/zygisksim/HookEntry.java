package com.zygisksim;

import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.IInterface;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
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
import java.util.Map;

/**
 * Entry point for ZygiskSIM.
 * No longer uses Pine. Uses pure Java reflection and dynamic proxies to mock system services
 * because native inline hooking causes fatal SIGSEGV crashes on Android 15 ART.
 */
public class HookEntry {

    // Config defaults
    private static String sLogDir = null;
    private static String sEid = "89049032005008882600033827513789";
    private static String sSpoofModel = "Pixel 8";
    private static String sSpoofDevice = "shiba";
    private static String sSpoofManufacturer = "Google";
    private static String sSpoofBrand = "google";
    private static String sSpoofProduct = "shiba";

    public static void init(String logDir, String pineLibPath, String configJson) {
        sLogDir = logDir;
        logStatic("ZygiskSIM Java payload initializing (Pure Reflection Mode)...");

        // Parse config.json if provided
        parseConfig(configJson);
        buildSpoofedProps();

        // 1. Spoof device identity
        spoofBuildFields();

        // 2. Mock IPackageManager in ActivityThread and disable PropertyInvalidatedCache
        mockPackageManager();

        // 3. Mock IEuiccController in ServiceManager
        mockEuiccService();

        logStatic("ZygiskSIM pure reflection hooks installed successfully!");
    }

    // =========================================================================
    // Mock PackageManager (to spoof hasSystemFeature)
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
            Field sPackageManagerField = activityThreadClass.getDeclaredField("sPackageManager");
            sPackageManagerField.setAccessible(true);
            Object originalPm = sPackageManagerField.get(null);

            if (originalPm == null) {
                Method getPm = activityThreadClass.getDeclaredMethod("getPackageManager");
                getPm.setAccessible(true);
                originalPm = getPm.invoke(null);
            }

            final Object targetPm = originalPm;
            Class<?> iPackageManagerClass = Class.forName("android.content.pm.IPackageManager");
            Object mockPm = Proxy.newProxyInstance(
                    iPackageManagerClass.getClassLoader(),
                    new Class<?>[]{iPackageManagerClass},
                    new InvocationHandler() {
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
                            return method.invoke(targetPm, args);
                        }
                    }
            );

            sPackageManagerField.set(null, mockPm);
            logStatic("Injected mock IPackageManager into ActivityThread.");
        } catch (Throwable t) {
            logStatic("Failed to mock IPackageManager: " + t.getMessage());
            logStackTrace(t);
        }
    }

    // =========================================================================
    // Mock EuiccService (to spoof EID, download, and EuiccInfo)
    // =========================================================================

    private static Object createMockController(final ClassLoader classLoader) {
        try {
            Class<?> iEuiccControllerClass = Class.forName("android.telephony.euicc.IEuiccController", true, classLoader);
            return Proxy.newProxyInstance(
                    classLoader,
                    new Class<?>[]{iEuiccControllerClass},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            String name = method.getName();
                            
                            if ("getEid".equals(name)) {
                                return sEid;
                            }
                            
                            if ("getEuiccInfo".equals(name)) {
                                Class<?> euiccInfoClass = Class.forName("android.telephony.euicc.EuiccInfo", true, classLoader);
                                Constructor<?> constructor = euiccInfoClass.getDeclaredConstructor(String.class);
                                constructor.setAccessible(true);
                                return constructor.newInstance("1.0");
                            }
                            
                            if ("downloadSubscription".equals(name)) {
                                logStatic("Intercepted downloadSubscription via IPC mock!");
                                
                                // 1. Extract activation code
                                if (args != null && args.length > 0) {
                                    Object sub = null;
                                    for (Object arg : args) {
                                        if (arg != null && arg.getClass().getName().equals("android.telephony.euicc.DownloadableSubscription")) {
                                            sub = arg;
                                            break;
                                        }
                                    }
                                    if (sub != null) {
                                        try {
                                            Method getCode = sub.getClass().getDeclaredMethod("getEncodedActivationCode");
                                            getCode.setAccessible(true);
                                            String code = (String) getCode.invoke(sub);
                                            if (code != null) {
                                                handleActivationCode(code);
                                            }
                                        } catch (Throwable t) {
                                            logStatic("Failed to parse activation code: " + t.getMessage());
                                        }
                                    }
                                }

                                // 2. Trigger PendingIntent callback for success
                                if (args != null) {
                                    android.app.PendingIntent callback = null;
                                    for (Object arg : args) {
                                        if (arg instanceof android.app.PendingIntent) {
                                            callback = (android.app.PendingIntent) arg;
                                        }
                                    }
                                    if (callback != null) {
                                        Application app = getApplication();
                                        if (app != null) {
                                            logStatic("Triggering download success callback...");
                                            Intent resultIntent = new Intent();
                                            callback.send(app, 0, resultIntent);
                                        } else {
                                            logStatic("Cannot trigger callback: Application context is null.");
                                        }
                                    }
                                }
                                return null; // method returns void
                            }
                            
                            // Default return values for other primitive methods to avoid NPE
                            Class<?> retType = method.getReturnType();
                            if (retType == boolean.class) return false;
                            if (retType == int.class) return 0;
                            if (retType == long.class) return 0L;
                            return null;
                        }
                    }
            );
        } catch (Throwable t) {
            logStatic("Failed to create lazy mock IEuiccController: " + t.getMessage());
            logStackTrace(t);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static void mockEuiccService() {
        try {
            Class<?> iBinderClass = Class.forName("android.os.IBinder");
            IBinder mockBinder = (IBinder) Proxy.newProxyInstance(
                    iBinderClass.getClassLoader(),
                    new Class<?>[]{iBinderClass},
                    new InvocationHandler() {
                        private Object mMockController = null;

                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            String name = method.getName();
                            if ("queryLocalInterface".equals(name)) {
                                if (mMockController == null) {
                                    ClassLoader classLoader = null;
                                    Application app = getApplication();
                                    if (app != null) {
                                        classLoader = app.getClassLoader();
                                    }
                                    if (classLoader == null) {
                                        classLoader = Thread.currentThread().getContextClassLoader();
                                    }
                                    if (classLoader == null) {
                                        classLoader = ClassLoader.getSystemClassLoader();
                                    }
                                    mMockController = createMockController(classLoader);
                                }
                                return mMockController;
                            }
                            if ("pingBinder".equals(name) || "isBinderAlive".equals(name)) {
                                return true;
                            }
                            if ("getInterfaceDescriptor".equals(name)) {
                                return "android.telephony.euicc.IEuiccController";
                            }
                            
                            Class<?> retType = method.getReturnType();
                            if (retType == boolean.class) return false;
                            if (retType == int.class) return 0;
                            if (retType == long.class) return 0L;
                            return null;
                        }
                    }
            );

            Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
            Field sCacheField = serviceManagerClass.getDeclaredField("sCache");
            sCacheField.setAccessible(true);
            Map<String, IBinder> cache = (Map<String, IBinder>) sCacheField.get(null);
            
            if (cache != null) {
                cache.put("econtroller", mockBinder);
                logStatic("Injected mock IEuiccController binder into ServiceManager cache.");
            } else {
                logStatic("ServiceManager.sCache is null! Cannot inject econtroller.");
            }
        } catch (Throwable t) {
            logStatic("Failed to mock IEuiccController binder: " + t.getMessage());
            logStackTrace(t);
        }
    }

    // =========================================================================
    // Activation code handler — copy to clipboard
    // =========================================================================

    private static void handleActivationCode(String code) {
        logStatic("========================================");
        logStatic("eSIM DOWNLOAD INTERCEPTED (Pure Reflection)");
        logStatic("  Activation Code: " + code);
        logStatic("========================================");

        Application app = getApplication();
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

    // =========================================================================
    // Utilities
    // =========================================================================

    private static Application getApplication() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method currentApplicationMethod = activityThreadClass.getDeclaredMethod("currentApplication");
            return (Application) currentApplicationMethod.invoke(null);
        } catch (Exception ignored) {
            return null;
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
                sSpoofDevice = root.getString("device");
            }
            if (root.has("model")) {
                sSpoofModel = root.getString("model");
            }

            logStatic("  Parsed config.json successfully.");
            logStatic("  Config EID: " + sEid);
            logStatic("  Config Device: " + sSpoofModel + " (" + sSpoofDevice + ")");
        } catch (Throwable t) {
            logStatic("  Failed to parse config.json, using defaults. Error: " + t.getMessage());
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
