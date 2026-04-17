package dev.nekoobfuscator.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Loads platform-specific native libraries from the JAR at runtime.
 * Extracts to a temporary file and loads via System.load().
 */
public final class NekoNativeLoader {
    private static volatile boolean loaded = false;
    private static final Object LOCK = new Object();

    private NekoNativeLoader() {}

    public static void load() {
        if (loaded) {
            return;
        }
        synchronized (LOCK) {
            if (loaded) {
                return;
            }
            try {
                String os = System.getProperty("os.name", "").toLowerCase();
                String arch = System.getProperty("os.arch", "").toLowerCase();

                String platform;
                String ext;
                if (os.contains("win")) {
                    platform = "windows";
                    ext = ".dll";
                } else if (os.contains("mac") || os.contains("darwin")) {
                    platform = "macos";
                    ext = ".dylib";
                } else {
                    platform = "linux";
                    ext = ".so";
                }

                String archSuffix;
                if (arch.contains("aarch64") || arch.contains("arm64")) {
                    archSuffix = "aarch64";
                } else {
                    archSuffix = "x64";
                }

                String libName = "libneko_" + platform + "_" + archSuffix + ext;
                String resourcePath = "/neko/native/" + libName;

                Path tmp = Files.createTempFile("libneko_", ext);
                tmp.toFile().deleteOnExit();
                try (InputStream is = NekoNativeLoader.class.getResourceAsStream(resourcePath)) {
                    if (is == null) {
                        throw new UnsatisfiedLinkError("Native library not found: " + resourcePath);
                    }
                    Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING);
                }

                System.load(tmp.toAbsolutePath().toString());
                loaded = true;
            } catch (IOException e) {
                throw new UnsatisfiedLinkError("Failed to load native library: " + e.getMessage());
            }
        }
    }

    public static void bindClass(Class<?> self, String internalOwnerName) {
        load();
        nekoBindClass(self, internalOwnerName);
    }

    static String nekoVmOption(String name) {
        try {
            Class<?> managementFactoryClass = Class.forName("java.lang.management.ManagementFactory");
            Class<?> hotspotMxBeanClass = Class.forName("com.sun.management.HotSpotDiagnosticMXBean");
            Method getPlatformMxBean = managementFactoryClass.getMethod("getPlatformMXBean", Class.class);
            Object mxBean = getPlatformMxBean.invoke(null, hotspotMxBeanClass);
            if (mxBean == null) {
                return null;
            }
            Method getVmOption = hotspotMxBeanClass.getMethod("getVMOption", String.class);
            Object vmOption = getVmOption.invoke(mxBean, name);
            if (vmOption == null) {
                return null;
            }
            Method getValue = vmOption.getClass().getMethod("getValue");
            Object value = getValue.invoke(vmOption);
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static int nekoAddressSize() {
        try {
            Object unsafe = UnsafeAccess.UNSAFE;
            Method addressSize = UnsafeAccess.ADDRESS_SIZE;
            if (unsafe == null || addressSize == null) {
                return 0;
            }
            Object value = addressSize.invoke(unsafe);
            return value instanceof Number ? ((Number) value).intValue() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    static int nekoArrayBaseOffset(String primitiveClassName) {
        try {
            Object unsafe = UnsafeAccess.UNSAFE;
            Method arrayBaseOffset = UnsafeAccess.ARRAY_BASE_OFFSET;
            Class<?> arrayClass = primitiveArrayClass(primitiveClassName);
            if (unsafe == null || arrayBaseOffset == null || arrayClass == null) {
                return -1;
            }
            Object value = arrayBaseOffset.invoke(unsafe, arrayClass);
            return value instanceof Number ? ((Number) value).intValue() : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static int nekoArrayIndexScale(String primitiveClassName) {
        try {
            Object unsafe = UnsafeAccess.UNSAFE;
            Method arrayIndexScale = UnsafeAccess.ARRAY_INDEX_SCALE;
            Class<?> arrayClass = primitiveArrayClass(primitiveClassName);
            if (unsafe == null || arrayIndexScale == null || arrayClass == null) {
                return 0;
            }
            Object value = arrayIndexScale.invoke(unsafe, arrayClass);
            return value instanceof Number ? ((Number) value).intValue() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    static long nekoInstanceFieldOffset(Class<?> owner, String fieldName) {
        try {
            Object unsafe = UnsafeAccess.UNSAFE;
            Method objectFieldOffset = UnsafeAccess.OBJECT_FIELD_OFFSET;
            Field field = declaredField(owner, fieldName);
            if (unsafe == null || objectFieldOffset == null || field == null) {
                return -1L;
            }
            Object value = objectFieldOffset.invoke(unsafe, field);
            return value instanceof Number ? ((Number) value).longValue() : -1L;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    static long nekoStaticFieldOffset(Class<?> owner, String fieldName) {
        try {
            Object unsafe = UnsafeAccess.UNSAFE;
            Method staticFieldOffset = UnsafeAccess.STATIC_FIELD_OFFSET;
            Field field = declaredField(owner, fieldName);
            if (unsafe == null || staticFieldOffset == null || field == null) {
                return -1L;
            }
            Object value = staticFieldOffset.invoke(unsafe, field);
            return value instanceof Number ? ((Number) value).longValue() : -1L;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    static Object nekoStaticFieldBase(Class<?> owner, String fieldName) {
        try {
            Object unsafe = UnsafeAccess.UNSAFE;
            Method staticFieldBase = UnsafeAccess.STATIC_FIELD_BASE;
            Field field = declaredField(owner, fieldName);
            if (unsafe == null || staticFieldBase == null || field == null) {
                return null;
            }
            return staticFieldBase.invoke(unsafe, field);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field declaredField(Class<?> owner, String fieldName) {
        if (owner == null || fieldName == null) {
            return null;
        }
        try {
            Field field = owner.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Class<?> primitiveArrayClass(String primitiveClassName) {
        if (primitiveClassName == null) {
            return null;
        }
        switch (primitiveClassName) {
            case "boolean":
                return boolean[].class;
            case "byte":
                return byte[].class;
            case "char":
                return char[].class;
            case "short":
                return short[].class;
            case "int":
                return int[].class;
            case "long":
                return long[].class;
            case "float":
                return float[].class;
            case "double":
                return double[].class;
            default:
                return null;
        }
    }

    private static final class UnsafeAccess {
        private static final Object UNSAFE = loadUnsafe();
        private static final Method ADDRESS_SIZE = method("addressSize");
        private static final Method ARRAY_BASE_OFFSET = method("arrayBaseOffset", Class.class);
        private static final Method ARRAY_INDEX_SCALE = method("arrayIndexScale", Class.class);
        private static final Method OBJECT_FIELD_OFFSET = method("objectFieldOffset", Field.class);
        private static final Method STATIC_FIELD_OFFSET = method("staticFieldOffset", Field.class);
        private static final Method STATIC_FIELD_BASE = method("staticFieldBase", Field.class);

        private static Object loadUnsafe() {
            Object unsafe = unsafeSingleton("sun.misc.Unsafe");
            if (unsafe != null) {
                return unsafe;
            }
            return unsafeSingleton("jdk.internal.misc.Unsafe");
        }

        private static Object unsafeSingleton(String className) {
            try {
                Class<?> unsafeClass = Class.forName(className);
                Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
                theUnsafe.setAccessible(true);
                return theUnsafe.get(null);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static Method method(String name, Class<?>... parameterTypes) {
            if (UNSAFE == null) {
                return null;
            }
            try {
                Method method = UNSAFE.getClass().getMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (Throwable ignored) {
                try {
                    Method method = UNSAFE.getClass().getDeclaredMethod(name, parameterTypes);
                    method.setAccessible(true);
                    return method;
                } catch (Throwable ignoredAgain) {
                    return null;
                }
            }
        }
    }

    private static native void nekoBindClass(Class<?> self, String internalOwnerName);
}
