package dev.nekoobfuscator.runtime;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ConcurrentHashMap;

public final class NekoIndyDispatch {
    private static final ConcurrentHashMap<Long, MethodHandle> CACHE = new ConcurrentHashMap<>();
    private static final MethodHandles.Lookup LOOKUP;

    static {
        MethodHandles.Lookup l;
        try {
            java.lang.reflect.Field f = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            f.setAccessible(true);
            l = (MethodHandles.Lookup) f.get(null);
        } catch (Throwable t) {
            l = MethodHandles.lookup();
        }
        LOOKUP = l;
    }

    private NekoIndyDispatch() {
    }

    public static Object invoke(long siteId, String bsmOwner, String bsmName, String bsmDesc,
                                String name, String typeDesc, Object[] bootstrapArgs, Object[] invokeArgs) throws Throwable {
        MethodHandle target = CACHE.get(siteId);
        if (target == null) {
            Class<?> ownerCls = Class.forName(bsmOwner.replace('/', '.'));
            MethodType bsmMt = MethodType.fromMethodDescriptorString(bsmDesc, ownerCls.getClassLoader());
            MethodHandle bsmHandle = LOOKUP.findStatic(ownerCls, bsmName, bsmMt);
            MethodType siteType = MethodType.fromMethodDescriptorString(typeDesc, ownerCls.getClassLoader());
            Object[] bsmFullArgs = new Object[3 + bootstrapArgs.length];
            bsmFullArgs[0] = LOOKUP;
            bsmFullArgs[1] = name;
            bsmFullArgs[2] = siteType;
            for (int i = 0; i < bootstrapArgs.length; i++) {
                bsmFullArgs[i + 3] = decodeBootstrapArg(bootstrapArgs[i], ownerCls.getClassLoader());
            }
            CallSite cs = (CallSite) bsmHandle.invokeWithArguments(bsmFullArgs);
            target = cs.dynamicInvoker();
            CACHE.put(siteId, target);
        }
        return target.invokeWithArguments(invokeArgs);
    }

    private static Object decodeBootstrapArg(Object arg, ClassLoader loader) throws Throwable {
        if (!(arg instanceof String s) || !s.startsWith("\u0001NEKO_")) {
            return arg;
        }
        if (s.startsWith("\u0001NEKO_MT:")) {
            return MethodType.fromMethodDescriptorString(s.substring("\u0001NEKO_MT:".length()), loader);
        }
        if (s.startsWith("\u0001NEKO_CT:")) {
            return classForDescriptor(s.substring("\u0001NEKO_CT:".length()), loader);
        }
        if (s.startsWith("\u0001NEKO_H:")) {
            return handleForEncoding(s.substring("\u0001NEKO_H:".length()), loader);
        }
        return arg;
    }

    private static Class<?> classForDescriptor(String desc, ClassLoader loader) throws ClassNotFoundException {
        return switch (desc) {
            case "Z" -> boolean.class;
            case "B" -> byte.class;
            case "C" -> char.class;
            case "S" -> short.class;
            case "I" -> int.class;
            case "J" -> long.class;
            case "F" -> float.class;
            case "D" -> double.class;
            case "V" -> void.class;
            default -> Class.forName(desc.startsWith("[") ? desc : desc.substring(1, desc.length() - 1).replace('/', '.'), false, loader);
        };
    }

    private static MethodHandle handleForEncoding(String payload, ClassLoader loader) throws Throwable {
        String[] parts = payload.split("\\|", 5);
        int tag = Integer.parseInt(parts[0]);
        Class<?> owner = Class.forName(parts[1].replace('/', '.'), false, loader);
        String name = parts[2];
        String desc = parts[3];
        boolean itf = "1".equals(parts[4]);
        return switch (tag) {
            case 1 -> LOOKUP.findGetter(owner, name, classForDescriptor(desc, loader));
            case 2 -> LOOKUP.findStaticGetter(owner, name, classForDescriptor(desc, loader));
            case 3 -> LOOKUP.findSetter(owner, name, classForDescriptor(desc, loader));
            case 4 -> LOOKUP.findStaticSetter(owner, name, classForDescriptor(desc, loader));
            case 5, 9 -> LOOKUP.findVirtual(owner, name, MethodType.fromMethodDescriptorString(desc, loader));
            case 6 -> LOOKUP.findStatic(owner, name, MethodType.fromMethodDescriptorString(desc, loader));
            case 7 -> LOOKUP.findSpecial(owner, name, MethodType.fromMethodDescriptorString(desc, loader), owner);
            case 8 -> LOOKUP.findConstructor(owner, MethodType.fromMethodDescriptorString(desc, loader));
            default -> throw new IllegalArgumentException("Unsupported handle tag: " + tag + ", interface=" + itf);
        };
    }
}
