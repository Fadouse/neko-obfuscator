package dev.nekoobfuscator.runtime;

import java.lang.invoke.*;

/**
 * InvokeDynamic bootstrap methods for runtime decryption.
 * This class is embedded into obfuscated output JARs.
 * Compiled to Java 8 bytecode for maximum compatibility.
 */
public final class NekoBootstrap {

    private NekoBootstrap() {}

    /**
     * Bootstrap method for string decryption.
     */
    /**
     * Bootstrap for string decryption with full multi-layer key derivation.
     * Key chain: classKey -> methodKey (via nameHash + descHash) -> insnKey (via fieldIdx + salt)
     */
    public static CallSite bsmString(MethodHandles.Lookup lookup, String name,
            MethodType type, int fieldIdx, int methodNameHash, int methodDescHash,
            int insnSalt, int flowMode) throws Throwable {
        // Layer 1: class key from class structure
        long classKey = NekoKeyDerivation.classKey(lookup.lookupClass());
        // Layer 2: method key from method identity (name + descriptor hashes)
        long methodKey = NekoKeyDerivation.mix(
            NekoKeyDerivation.mix(classKey, methodNameHash), methodDescHash);
        // Layer 3: instruction key from position + salt
        long insnKey = NekoKeyDerivation.mix(
            NekoKeyDerivation.mix(methodKey, fieldIdx), insnSalt);
        if (flowMode != 0) {
            insnKey = NekoKeyDerivation.mix(insnKey, NekoContext.flowKey());
        }

        byte[] enc = NekoKeyDerivation.getEncField(lookup.lookupClass(), fieldIdx);
        String result = NekoStringDecryptor.decrypt(enc, insnKey);
        return new ConstantCallSite(MethodHandles.constant(String.class, result));
    }

    /**
     * Bootstrap method for method invocation indirection.
     * Resolves the actual target method from encrypted bootstrap args.
     */
    public static CallSite bsmInvoke(MethodHandles.Lookup lookup, String name,
            MethodType type, int siteId, int methodNameHash, int methodDescHash,
            int siteSalt, int invokeType, int targetId, int flowMode) throws Throwable {
        Class<?> callerClass = lookup.lookupClass();
        long classKey = NekoKeyDerivation.classKey(callerClass);
        long methodKey = NekoKeyDerivation.mix(
            NekoKeyDerivation.mix(classKey, methodNameHash), methodDescHash);
        long siteKey = NekoKeyDerivation.mix(methodKey, siteSalt);
        siteKey = NekoKeyDerivation.mix(siteKey, targetId);
        siteKey = NekoKeyDerivation.mix(siteKey, invokeType);
        if (flowMode != 0) {
            siteKey = NekoKeyDerivation.mix(siteKey, NekoContext.flowKey());
        }

        String owner = decryptInvokeMetadata(callerClass, siteId, 'o', siteKey, 1);
        String methodName = decryptInvokeMetadata(callerClass, siteId, 'n', siteKey, 2);
        String methodDesc = decryptInvokeMetadata(callerClass, siteId, 'd', siteKey, 3);
        Class<?> ownerClass = Class.forName(owner.replace('/', '.'), true,
            callerClass.getClassLoader());
        MethodType targetType = MethodType.fromMethodDescriptorString(methodDesc, ownerClass.getClassLoader());

        // Use privateLookupIn for cross-class access
        MethodHandles.Lookup targetLookup;
        try {
            targetLookup = MethodHandles.privateLookupIn(ownerClass, lookup);
        } catch (IllegalAccessException e) {
            targetLookup = lookup;
        }

        MethodHandle mh;
        switch (invokeType) {
            case 184: // INVOKESTATIC
                mh = targetLookup.findStatic(ownerClass, methodName, targetType);
                break;
            case 182: // INVOKEVIRTUAL
            case 185: // INVOKEINTERFACE
                mh = targetLookup.findVirtual(ownerClass, methodName, targetType);
                break;
            default:
                mh = targetLookup.findVirtual(ownerClass, methodName, targetType);
                break;
        }
        MethodHandle packedInvoker = MethodHandles.lookup().findStatic(
            NekoBootstrap.class,
            "invokePacked",
            MethodType.methodType(Object.class, MethodHandle.class, Object[].class)
        );
        packedInvoker = MethodHandles.insertArguments(packedInvoker, 0, mh);
        return new ConstantCallSite(packedInvoker.asType(type));
    }

    /**
     * Bootstrap method for number decryption.
     */
    public static CallSite bsmNumber(MethodHandles.Lookup lookup, String name,
            MethodType type, long encValue, int contextKey) throws Throwable {
        long key = NekoKeyDerivation.classKey(lookup.lookupClass()) ^ (long) contextKey;
        long decrypted = encValue ^ key;

        Class<?> rt = type.returnType();
        Object value;
        if (rt == int.class) value = (int) decrypted;
        else if (rt == long.class) value = decrypted;
        else if (rt == float.class) value = Float.intBitsToFloat((int) decrypted);
        else if (rt == double.class) value = Double.longBitsToDouble(decrypted);
        else value = (int) decrypted;

        return new ConstantCallSite(MethodHandles.constant(rt, value));
    }

    private static String decryptInvokeMetadata(Class<?> callerClass, int siteId, char component,
            long siteKey, int componentId) {
        String fieldName = "__i" + siteId + component;
        byte[] enc = NekoKeyDerivation.getBytesField(callerClass, fieldName);
        long key = NekoKeyDerivation.finalize_(NekoKeyDerivation.mix(siteKey, componentId));
        return NekoStringDecryptor.decrypt(enc, key);
    }

    @SuppressWarnings("unused")
    private static Object invokePacked(MethodHandle handle, Object[] args) throws Throwable {
        return handle.invokeWithArguments(args);
    }
}
