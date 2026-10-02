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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.json.JSONObject;

import top.canyie.pine.Pine;
import top.canyie.pine.callback.MethodHook;

/**
 * Entry point for ZygiskSIM.
 * Supports Pine ART hooking on ARM/ARM64 and ServiceManager / Dynamic Proxy mocks on x86/x86_64 emulators.
 */
public class HookEntry {

    private static String sLogDir = null;
    private static Application sApplication = null;

    // Config defaults
    private static String sEngine = "auto";
    private static String sArch = "unknown";
    private static String sEid = "89049032005008882600033827513789";
    private static String sSpoofModel = "Pixel 8";
    private static String sSpoofDevice = "shiba";
    private static String sSpoofManufacturer = "Google";
    private static String sSpoofBrand = "google";
    private static String sSpoofProduct = "shiba";

    public static void init(String logDir, String pineLibPath, String configJson) {
        sLogDir = logDir;
        logStatic("ZygiskSIM Java payload initializing...");

        // 1. Parse config.json if provided (contains detected architecture and engine)
        parseConfig(configJson);

        logStatic("Active Engine: " + sEngine + " (Arch: " + sArch + ")");

        // 2. Spoof device identity (pure reflection, works on all architectures)
        spoofBuildFields();

        // 3. Bypass Hidden API restrictions via dalvik.system.VMRuntime in Java
        bypassHiddenApiRestrictions();

        // 4. Install IPackageManager dynamic proxy (works across all architectures)
        installPackageManagerProxy();

        // 5. Install IEuiccController & ServiceManager dynamic mock (works across all architectures)
        installEuiccServiceMock();

        // 6. If Pine engine is selected (or auto on ARM) and library path is provided:
        if (!"dobby".equalsIgnoreCase(sEngine) && pineLibPath != null && !pineLibPath.trim().isEmpty()) {
            try {
                disablePineNativeHiddenApiBypass();
                loadPineLibrary(pineLibPath);
                installPineHooks();
                logStatic("Pine ART hooks installed successfully (Pine Mode).");
            } catch (Throwable t) {
                logStatic("Pine library load failed: " + t.getMessage());
                logStatic("Continuing with ServiceManager & Dynamic Proxy mocks.");
            }
        } else {
            logStatic("Running in Dobby / x86_64 mode (Pine omitted). Java dynamic proxies & ServiceManager mocks active.");
        }
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
            f1.setBoolean(null, true);

            Field f2 = pineConfigClass.getDeclaredField("disableHiddenApiPolicyForPlatformDomain");
            f2.setAccessible(true);
            f2.setBoolean(null, true);
            logStatic("Disabled Pine's native HiddenAPI bypass.");
        } catch (Throwable t) {
            logStatic("Failed to disable Pine HiddenAPI bypass: " + t.getMessage());
        }
    }

    private static void loadPineLibrary(final String pineLibPath) throws Exception {
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

        try {
            System.loadLibrary("pine");
            logStatic("Successfully loaded libpine.so via System.loadLibrary");
            return;
        } catch (UnsatisfiedLinkError e) {
            logStatic("System.loadLibrary(\"pine\") failed: " + e.getMessage());
        }

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

    // =====================================================================
    // ServiceManager & IEuiccController Mock Injection (All Architectures)
    // =====================================================================

    private static void installEuiccServiceMock() {
        try {
            final Object mockController = createEuiccControllerProxy();
            if (mockController == null) {
                logStatic("Failed to create EuiccController proxy.");
                return;
            }

            // 1. Inject into ServiceManager.sCache under all common eSIM service names
            try {
                Class<?> smClass = Class.forName("android.os.ServiceManager");
                Field sCacheField = smClass.getDeclaredField("sCache");
                sCacheField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, Object> sCache = (Map<String, Object>) sCacheField.get(null);
                if (sCache != null) {
                    sCache.put("econtroller", mockController);
                    sCache.put("euicc_service", mockController);
                    sCache.put("euicc_controller", mockController);
                    sCache.put("euicc", mockController);
                    logStatic("Successfully injected mock IEuiccController into ServiceManager.sCache.");
                }
            } catch (Throwable t) {
                logStatic("ServiceManager.sCache injection note: " + t.getMessage());
            }

            // 2. Inject into TelephonyFrameworkInitializer (Android 10+)
            try {
                Class<?> tfiClass = Class.forName("android.telephony.TelephonyFrameworkInitializer");
                Method getTsm = tfiClass.getDeclaredMethod("getTelephonyServiceManager");
                Object tsm = getTsm.invoke(null);
                if (tsm != null) {
                    Method getRegisterer = tsm.getClass().getDeclaredMethod("getEuiccControllerServiceRegisterer");
                    Object registerer = getRegisterer.invoke(tsm);
                    if (registerer != null) {
                        try {
                            Field serviceField = registerer.getClass().getDeclaredField("mService");
                            serviceField.setAccessible(true);
                            serviceField.set(registerer, mockController);
                        } catch (Throwable ignored) {}
                        try {
                            Method registerMethod = registerer.getClass().getDeclaredMethod("register", Class.forName("android.os.IBinder"));
                            registerMethod.invoke(registerer, mockController);
                        } catch (Throwable ignored) {}
                        logStatic("Successfully registered mock IEuiccController in TelephonyServiceManager.");
                    }
                }
            } catch (Throwable t) {
                logStatic("TelephonyServiceManager injection note: " + t.getMessage());
            }

            // 3. Periodic refresh to ensure cache remains populated if cleared
            new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < 30; i++) {
                        try {
                            Thread.sleep(500);
                            Class<?> smClass = Class.forName("android.os.ServiceManager");
                            Field sCacheField = smClass.getDeclaredField("sCache");
                            sCacheField.setAccessible(true);
                            @SuppressWarnings("unchecked")
                            Map<String, Object> sCache = (Map<String, Object>) sCacheField.get(null);
                            if (sCache != null) {
                                if (sCache.get("econtroller") != mockController) {
                                    sCache.put("econtroller", mockController);
                                    sCache.put("euicc_service", mockController);
                                    sCache.put("euicc_controller", mockController);
                                    sCache.put("euicc", mockController);
                                }
                            }
                            hookApplicationPackageManager();
                        } catch (Throwable ignored) {}
                    }
                }
            }).start();

        } catch (Throwable t) {
            logStatic("installEuiccServiceMock error: " + t.getMessage());
            logStackTrace(t);
        }
    }

    private static Object createEuiccControllerProxy() {
        try {
            Class<?> iBinderClass = Class.forName("android.os.IBinder");
            Class<?> iInterfaceClass = Class.forName("android.os.IInterface");
            Class<?> iEuiccControllerClass = null;
            try {
                iEuiccControllerClass = Class.forName("com.android.internal.telephony.euicc.IEuiccController");
            } catch (Throwable t) {
                try {
                    iEuiccControllerClass = Class.forName("android.telephony.euicc.IEuiccController");
                } catch (Throwable ignored) {}
            }

            List<Class<?>> ifaceList = new ArrayList<>();
            ifaceList.add(iBinderClass);
            ifaceList.add(iInterfaceClass);
            if (iEuiccControllerClass != null) {
                ifaceList.add(iEuiccControllerClass);
            }

            Class<?>[] interfaces = ifaceList.toArray(new Class<?>[0]);

            return Proxy.newProxyInstance(
                HookEntry.class.getClassLoader(),
                interfaces,
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        String name = method.getName();

                        // --- IBinder & IInterface methods ---
                        if ("queryLocalInterface".equals(name)) {
                            return proxy;
                        }
                        if ("getInterfaceDescriptor".equals(name)) {
                            return "com.android.internal.telephony.euicc.IEuiccController";
                        }
                        if ("asBinder".equals(name)) {
                            return proxy;
                        }
                        if ("pingBinder".equals(name) || "isBinderAlive".equals(name)) {
                            return Boolean.TRUE;
                        }
                        if ("transact".equals(name)) {
                            return Boolean.TRUE;
                        }
                        if ("linkToDeath".equals(name) || "unlinkToDeath".equals(name)) {
                            return null;
                        }

                        // --- IEuiccController methods ---
                        if ("isEnabled".equals(name)) {
                            logStatic("Spoofed IEuiccController.isEnabled() -> true");
                            return Boolean.TRUE;
                        }
                        if ("getEid".equals(name)) {
                            logStatic("Spoofed IEuiccController.getEid() -> " + sEid);
                            return sEid;
                        }
                        if ("getEuiccInfo".equals(name)) {
                            logStatic("Spoofed IEuiccController.getEuiccInfo()");
                            try {
                                Class<?> infoClass = Class.forName("android.telephony.euicc.EuiccInfo");
                                Constructor<?> ctor = infoClass.getDeclaredConstructor(String.class);
                                ctor.setAccessible(true);
                                return ctor.newInstance("1.0");
                            } catch (Throwable t) {
                                return null;
                            }
                        }
                        if ("getOtaStatus".equals(name)) {
                            return Integer.valueOf(0);
                        }
                        if ("downloadSubscription".equals(name)) {
                            logStatic("Intercepted IEuiccController.downloadSubscription()!");
                            if (args != null) {
                                PendingIntent callbackIntent = null;
                                for (Object arg : args) {
                                    if (arg instanceof DownloadableSubscription) {
                                        DownloadableSubscription sub = (DownloadableSubscription) arg;
                                        String code = sub.getEncodedActivationCode();
                                        if (code != null) {
                                            handleActivationCode(code);
                                        }
                                    } else if (arg instanceof PendingIntent) {
                                        callbackIntent = (PendingIntent) arg;
                                    }
                                }
                                if (callbackIntent != null) {
                                    try {
                                        Intent resultIntent = new Intent();
                                        callbackIntent.send(getApplicationContext(), 0, resultIntent);
                                        logStatic("Triggered success callback intent for subscription download.");
                                    } catch (Throwable t) {
                                        logStatic("Failed to send callback intent: " + t.getMessage());
                                    }
                                }
                            }
                            return null;
                        }
                        if ("getDefaultDownloadableSubscriptionList".equals(name) ||
                            "getDownloadableSubscriptionMetadata".equals(name)) {
                            if (args != null) {
                                for (Object arg : args) {
                                    if (arg instanceof PendingIntent) {
                                        try {
                                            ((PendingIntent) arg).send(getApplicationContext(), 0, new Intent());
                                        } catch (Throwable ignored) {}
                                    }
                                }
                            }
                            return null;
                        }

                        // --- Object methods ---
                        if ("toString".equals(name)) {
                            return "MockIEuiccControllerProxy";
                        }
                        if ("hashCode".equals(name)) {
                            return Integer.valueOf(System.identityHashCode(proxy));
                        }
                        if ("equals".equals(name)) {
                            return Boolean.valueOf(args != null && args.length > 0 && proxy == args[0]);
                        }

                        // Default primitive return types
                        Class<?> returnType = method.getReturnType();
                        if (returnType == boolean.class || returnType == Boolean.class) {
                            return Boolean.TRUE;
                        }
                        if (returnType == int.class || returnType == Integer.class) {
                            return Integer.valueOf(0);
                        }
                        return null;
                    }
                }
            );
        } catch (Throwable t) {
            logStatic("createEuiccControllerProxy error: " + t.getMessage());
            logStackTrace(t);
            return null;
        }
    }

    // =====================================================================
    // PackageManager Dynamic Proxy (All Architectures)
    // =====================================================================

    private static void installPackageManagerProxy() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Field sPackageManagerField = activityThreadClass.getDeclaredField("sPackageManager");
            sPackageManagerField.setAccessible(true);
            Object currentPm = sPackageManagerField.get(null);
            if (currentPm != null && !Proxy.isProxyClass(currentPm.getClass())) {
                Object mock = createPackageManagerProxy(currentPm);
                if (mock != null) {
                    sPackageManagerField.set(null, mock);
                    logStatic("Immediately replaced ActivityThread.sPackageManager with mock proxy.");
                }
            }
        } catch (Throwable t) {
            logStatic("Immediate sPackageManager replacement: " + t.getMessage());
        }

        pollAndMockPackageManagerField();
    }

    private static void hookApplicationPackageManager() {
        try {
            Context ctx = getApplicationContext();
            if (ctx != null) {
                Object pm = ctx.getPackageManager();
                if (pm != null) {
                    try {
                        Field mPmField = pm.getClass().getDeclaredField("mPM");
                        mPmField.setAccessible(true);
                        Object originalPM = mPmField.get(pm);
                        if (originalPM != null && !Proxy.isProxyClass(originalPM.getClass())) {
                            mPmField.set(pm, createPackageManagerProxy(originalPM));
                            logStatic("Replaced ApplicationPackageManager.mPM with mock proxy.");
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    private static Object createPackageManagerProxy(final Object originalPm) {
        try {
            Class<?> iPackageManagerClass = Class.forName("android.content.pm.IPackageManager");
            return Proxy.newProxyInstance(
                HookEntry.class.getClassLoader(),
                new Class<?>[]{ iPackageManagerClass },
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if ("hasSystemFeature".equals(method.getName())) {
                            if (args != null && args.length > 0) {
                                String feature = (String) args[0];
                                if ("android.hardware.telephony.euicc".equals(feature)) {
                                    logStatic("Spoofed IPackageManager.hasSystemFeature(" + feature + ") -> true");
                                    return Boolean.TRUE;
                                }
                            }
                        }
                        try {
                            return method.invoke(originalPm, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    }
                }
            );
        } catch (Throwable t) {
            logStatic("Failed to create IPackageManager proxy: " + t.getMessage());
            return originalPm;
        }
    }

    private static void pollAndMockPackageManagerField() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
                    Field sPackageManagerField = activityThreadClass.getDeclaredField("sPackageManager");
                    sPackageManagerField.setAccessible(true);

                    Object originalPm = null;
                    for (int i = 0; i < 100; i++) {
                        originalPm = sPackageManagerField.get(null);
                        if (originalPm != null) {
                            break;
                        }
                        Thread.sleep(100);
                    }

                    if (originalPm == null) {
                        logStatic("sPackageManager is still null after 10 seconds.");
                        return;
                    }

                    if (Proxy.isProxyClass(originalPm.getClass())) {
                        return;
                    }

                    Object mockPm = createPackageManagerProxy(originalPm);
                    sPackageManagerField.set(null, mockPm);
                    logStatic("Successfully replaced ActivityThread.sPackageManager field with mock proxy.");
                } catch (Throwable t) {
                    logStatic("Background sPackageManager field override failed: " + t.getMessage());
                    logStackTrace(t);
                }
            }
        }).start();
    }

    // =====================================================================
    // Pine Hooks (ARM / ARM64)
    // =====================================================================

    private static void installPineHooks() {
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
        try { hookActivityThreadGetPackageManager(); } catch (Throwable t) {
            logStatic("  WARN: Pine ActivityThread hook failed: " + t.getMessage());
        }
        try { hookForActivationCode(); } catch (Throwable t) {
            logStatic("  WARN: hookForActivationCode failed: " + t.getMessage());
        }
        try { hookEuiccManagerDownloadSubscription(); } catch (Throwable t) {
            logStatic("  WARN: hookEuiccManagerDownloadSubscription failed: " + t.getMessage());
        }
        logStatic("Pine hooks installation finished.");
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

    private static void hookActivityThreadGetPackageManager() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method getPackageManager = activityThreadClass.getDeclaredMethod("getPackageManager");
            Pine.hook(getPackageManager, new MethodHook() {
                private Object mMockPm = null;

                @Override
                public void afterCall(Pine.CallFrame callFrame) throws Throwable {
                    Object originalPm = callFrame.getResult();
                    if (originalPm == null) return;

                    if (mMockPm != null) {
                        callFrame.setResult(mMockPm);
                        return;
                    }

                    mMockPm = createPackageManagerProxy(originalPm);
                    callFrame.setResult(mMockPm);
                }
            });
            logStatic("Successfully hooked ActivityThread.getPackageManager() via Pine.");
        } catch (Throwable t) {
            logStatic("Failed to hook ActivityThread.getPackageManager(): " + t.getMessage());
            logStackTrace(t);
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

    // =====================================================================
    // General Helpers
    // =====================================================================

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
        logStatic("eSIM DOWNLOAD INTERCEPTED");
        logStatic("  Activation Code: " + code);
        logStatic("========================================");

        Context app = getApplicationContext();
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
            if (root.has("engine")) {
                sEngine = root.optString("engine", sEngine);
            }
            if (root.has("arch")) {
                sArch = root.optString("arch", sArch);
            }
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
            logStatic("  Engine: " + sEngine + ", Arch: " + sArch);
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
