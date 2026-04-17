package dev.nekoobfuscator.runtime;

import sun.misc.Unsafe;

import java.lang.reflect.Field;

public final class NekoUnsafe {
    private static final Unsafe UNSAFE;

    static {
        Unsafe unsafe;
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            unsafe = (Unsafe) field.get(null);
        } catch (Throwable throwable) {
            unsafe = null;
        }
        UNSAFE = unsafe;
    }

    private NekoUnsafe() {
    }

    public static boolean available() {
        return UNSAFE != null;
    }

    public static long instanceFieldOffset(Class<?> owner, String fieldName) throws NoSuchFieldException {
        return UNSAFE.objectFieldOffset(owner.getDeclaredField(fieldName));
    }

    public static long staticFieldOffset(Class<?> owner, String fieldName) throws NoSuchFieldException {
        return UNSAFE.staticFieldOffset(owner.getDeclaredField(fieldName));
    }

    public static Object staticFieldBase(Class<?> owner, String fieldName) throws NoSuchFieldException {
        return UNSAFE.staticFieldBase(owner.getDeclaredField(fieldName));
    }

    public static int getInt(Object target, long offset) { return UNSAFE.getInt(target, offset); }
    public static void putInt(Object target, long offset, int value) { UNSAFE.putInt(target, offset, value); }
    public static long getLong(Object target, long offset) { return UNSAFE.getLong(target, offset); }
    public static void putLong(Object target, long offset, long value) { UNSAFE.putLong(target, offset, value); }
    public static float getFloat(Object target, long offset) { return UNSAFE.getFloat(target, offset); }
    public static void putFloat(Object target, long offset, float value) { UNSAFE.putFloat(target, offset, value); }
    public static double getDouble(Object target, long offset) { return UNSAFE.getDouble(target, offset); }
    public static void putDouble(Object target, long offset, double value) { UNSAFE.putDouble(target, offset, value); }
    public static byte getByte(Object target, long offset) { return UNSAFE.getByte(target, offset); }
    public static void putByte(Object target, long offset, byte value) { UNSAFE.putByte(target, offset, value); }
    public static short getShort(Object target, long offset) { return UNSAFE.getShort(target, offset); }
    public static void putShort(Object target, long offset, short value) { UNSAFE.putShort(target, offset, value); }
    public static char getChar(Object target, long offset) { return UNSAFE.getChar(target, offset); }
    public static void putChar(Object target, long offset, char value) { UNSAFE.putChar(target, offset, value); }
    public static boolean getBoolean(Object target, long offset) { return UNSAFE.getBoolean(target, offset); }
    public static void putBoolean(Object target, long offset, boolean value) { UNSAFE.putBoolean(target, offset, value); }
    public static Object getObject(Object target, long offset) { return UNSAFE.getObject(target, offset); }
    public static void putObject(Object target, long offset, Object value) { UNSAFE.putObject(target, offset, value); }
    public static int getIntVolatile(Object target, long offset) { return UNSAFE.getIntVolatile(target, offset); }
    public static void putIntVolatile(Object target, long offset, int value) { UNSAFE.putIntVolatile(target, offset, value); }
    public static long getLongVolatile(Object target, long offset) { return UNSAFE.getLongVolatile(target, offset); }
    public static void putLongVolatile(Object target, long offset, long value) { UNSAFE.putLongVolatile(target, offset, value); }
    public static float getFloatVolatile(Object target, long offset) { return UNSAFE.getFloatVolatile(target, offset); }
    public static void putFloatVolatile(Object target, long offset, float value) { UNSAFE.putFloatVolatile(target, offset, value); }
    public static double getDoubleVolatile(Object target, long offset) { return UNSAFE.getDoubleVolatile(target, offset); }
    public static void putDoubleVolatile(Object target, long offset, double value) { UNSAFE.putDoubleVolatile(target, offset, value); }
    public static byte getByteVolatile(Object target, long offset) { return UNSAFE.getByteVolatile(target, offset); }
    public static void putByteVolatile(Object target, long offset, byte value) { UNSAFE.putByteVolatile(target, offset, value); }
    public static short getShortVolatile(Object target, long offset) { return UNSAFE.getShortVolatile(target, offset); }
    public static void putShortVolatile(Object target, long offset, short value) { UNSAFE.putShortVolatile(target, offset, value); }
    public static char getCharVolatile(Object target, long offset) { return UNSAFE.getCharVolatile(target, offset); }
    public static void putCharVolatile(Object target, long offset, char value) { UNSAFE.putCharVolatile(target, offset, value); }
    public static boolean getBooleanVolatile(Object target, long offset) { return UNSAFE.getBooleanVolatile(target, offset); }
    public static void putBooleanVolatile(Object target, long offset, boolean value) { UNSAFE.putBooleanVolatile(target, offset, value); }
    public static Object getObjectVolatile(Object target, long offset) { return UNSAFE.getObjectVolatile(target, offset); }
    public static void putObjectVolatile(Object target, long offset, Object value) { UNSAFE.putObjectVolatile(target, offset, value); }
}
