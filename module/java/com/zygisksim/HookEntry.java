package com.zygisksim;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * ZygiskSIM Hook Entry Point (Binder Proxy Architecture)
 *
 * Injects a custom IBinder into android.os.ServiceManager.sCache for the "euicc" service.
 * When EuiccManager calls ServiceManager.getService("euicc"), it gets our IBinder.
 * Our IBinder returns a dynamic proxy for IEuiccController in queryLocalInterface.
 * This proxy intercepts downloadSubscription, and automatically makes isEnabled() true.
 */
public class HookEntry {

    private static String sLogDir;

    public static void init(String logDir) {
        sLogDir = logDir;
        logStatic("ZygiskSIM Java payload initializing...");

        try {
            bypassHiddenApi();
            logStatic("Hidden API bypassed.");

            injectBinderProxy();
            logStatic("Binder proxy successfully injected for 'euicc' service.");

            injectPackageManagerProxy();
            logStatic("IPackageManager proxy successfully injected.");

        } catch (Throwable t) {
            logStatic("Hook initialization failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /**
     * Standard double-reflection technique to bypass Android 9+ hidden API restrictions.
     */
    private static void bypassHiddenApi() {
        try {
            Method getDeclaredMethod = Class.class.getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);
            Class<?> vmRuntimeClass = Class.forName("dalvik.system.VMRuntime");
            Method getRuntimeMethod = (Method) getDeclaredMethod.invoke(vmRuntimeClass, "getRuntime", new Class[0]);
            Object vmRuntime = getRuntimeMethod.invoke(null);
            Method setHiddenApiExemptions = (Method) getDeclaredMethod.invoke(vmRuntimeClass, "setHiddenApiExemptions", new Class[]{String[].class});
            
            // Exempt all APIs (prefix "L")
            setHiddenApiExemptions.invoke(vmRuntime, new Object[]{new String[]{"L"}});
        } catch (Throwable t) {
            logStatic("Hidden API bypass warning: " + t.getMessage());
        }
    }

    /**
     * Injects a proxy IBinder into ServiceManager.sCache
     */
    @SuppressWarnings("unchecked")
    private static void injectBinderProxy() throws Exception {
        ClassLoader cl = ClassLoader.getSystemClassLoader();

        // 1. Create a dynamic proxy for IEuiccController
        Class<?> iEuiccControllerClass = Class.forName("com.android.internal.telephony.euicc.IEuiccController");
        
        Object euiccControllerProxy = Proxy.newProxyInstance(
                cl,
                new Class<?>[]{iEuiccControllerClass},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        String methodName = method.getName();
                        
                        // EuiccManager.isEnabled() checks if getIEuiccController() != null.
                        // Our proxy exists, so it will return true. We don't need to hook isEnabled.

                        if ("downloadSubscription".equals(methodName)) {
                            handleDownloadSubscription(args);
                            // It returns void or an int status code depending on API.
                            // We return 0 (success) or null for void.
                            return method.getReturnType() == int.class ? 0 : null;
                        }

                        // For all other IEuiccController methods, return default values to prevent crashes
                        Class<?> retType = method.getReturnType();
                        if (retType == boolean.class) return false;
                        if (retType == int.class) return 0;
                        return null;
                    }
                }
        );

        // 2. Create a proxy for IBinder that returns our IEuiccController proxy
        Class<?> iBinderClass = Class.forName("android.os.IBinder");
        
        Object binderProxy = Proxy.newProxyInstance(
                cl,
                new Class<?>[]{iBinderClass},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if ("queryLocalInterface".equals(method.getName())) {
                            return euiccControllerProxy;
                        }
                        // Default IBinder behavior for other methods
                        if ("toString".equals(method.getName())) {
                            return "ZygiskSIM-IBinderProxy";
                        }
                        Class<?> retType = method.getReturnType();
                        if (retType == boolean.class) return false;
                        if (retType == int.class) return 0;
                        return null;
                    }
                }
        );

        // 3. Inject into ServiceManager.sCache
        Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
        Field cacheField = serviceManagerClass.getDeclaredField("sCache");
        cacheField.setAccessible(true);
        
        Map<String, Object> cache = (Map<String, Object>) cacheField.get(null);
        cache.put("euicc", binderProxy);
    }

    /**
     * Injects a proxy for IPackageManager to spoof the eSIM system feature.
     * KernelSU ignores system overlays without metamodules, so we spoof it in Java.
     */
    private static void injectPackageManagerProxy() throws Exception {
        ClassLoader cl = ClassLoader.getSystemClassLoader();
        Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        
        // ActivityThread.sPackageManager is a static field holding the IPackageManager singleton
        Field sPackageManagerField = activityThreadClass.getDeclaredField("sPackageManager");
        sPackageManagerField.setAccessible(true);
        Object originalPackageManager = sPackageManagerField.get(null);

        if (originalPackageManager == null) {
            // If it's null, we have to fetch it first so we can wrap it
            Method getPackageManagerMethod = activityThreadClass.getDeclaredMethod("getPackageManager");
            getPackageManagerMethod.setAccessible(true);
            originalPackageManager = getPackageManagerMethod.invoke(null);
        }

        if (originalPackageManager == null) {
            logStatic("Could not get original IPackageManager.");
            return;
        }

        Class<?> iPackageManagerClass = Class.forName("android.content.pm.IPackageManager");

        final Object finalOriginalPm = originalPackageManager;
        Object pmProxy = Proxy.newProxyInstance(
                cl,
                new Class<?>[]{iPackageManagerClass},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if ("hasSystemFeature".equals(method.getName())) {
                            if (args != null && args.length > 0 && "android.hardware.telephony.euicc".equals(args[0])) {
                                logStatic("Spoofed hasSystemFeature for android.hardware.telephony.euicc");
                                return true;
                            }
                        }
                        try {
                            return method.invoke(finalOriginalPm, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getTargetException(); // Unwrap exception to prevent crashes
                        }
                    }
                }
        );

        sPackageManagerField.set(null, pmProxy);
    }

    /**
     * Intercepts downloadSubscription and logs the activation code.
     */
    private static void handleDownloadSubscription(Object[] args) {
        String activationCode = "<unknown>";

        if (args != null && args.length > 0 && args[0] != null) {
            Object subscription = args[0];
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
        }

        logStatic("========================================");
        logStatic("eSIM DOWNLOAD INTERCEPTED (Binder Proxy)");
        logStatic("  Activation Code: " + activationCode);
        logStatic("========================================");
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
