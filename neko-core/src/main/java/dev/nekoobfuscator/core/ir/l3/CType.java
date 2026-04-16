package dev.nekoobfuscator.core.ir.l3;

public enum CType {
    JINT("jint", "int32_t", 4),
    JLONG("jlong", "int64_t", 8),
    JFLOAT("jfloat", "float", 4),
    JDOUBLE("jdouble", "double", 8),
    JBYTE("jbyte", "int8_t", 1),
    JSHORT("jshort", "int16_t", 2),
    JCHAR("jchar", "uint16_t", 2),
    JBOOLEAN("jboolean", "uint8_t", 1),
    JOBJECT("jobject", "void*", 8),
    JCLASS("jclass", "void*", 8),
    JSTRING("jstring", "void*", 8),
    JARRAY("jarray", "void*", 8),
    VOID("void", "void", 0);

    private final String jniName;
    private final String cName;
    private final int size;

    CType(String jniName, String cName, int size) {
        this.jniName = jniName;
        this.cName = cName;
        this.size = size;
    }

    public String jniName() { return jniName; }
    public String cName() { return cName; }
    public int size() { return size; }

    public boolean isWide() { return this == JLONG || this == JDOUBLE; }
    public boolean isObject() { return this == JOBJECT || this == JCLASS || this == JSTRING || this == JARRAY; }

    public static CType fromJvmType(char type) {
        return switch (type) {
            case 'I' -> JINT;
            case 'J' -> JLONG;
            case 'F' -> JFLOAT;
            case 'D' -> JDOUBLE;
            case 'B' -> JBYTE;
            case 'S' -> JSHORT;
            case 'C' -> JCHAR;
            case 'Z' -> JBOOLEAN;
            case 'V' -> VOID;
            case 'L', '[' -> JOBJECT;
            default -> JOBJECT;
        };
    }
}
