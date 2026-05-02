package dev.nekoobfuscator.native_.codegen.emit;

/**
 * Shared layout constants between the emitted C struct {@code NekoManifestMethod}
 * and the trampoline assembly that walks it. A {@code _Static_assert} in the C
 * source pins the size to {@link #MANIFEST_METHOD_SIZE}.
 */
public final class PatcherLayoutConstants {
    private PatcherLayoutConstants() {}

    public static final int MANIFEST_METHOD_SIZE = 48;

    public static final int OFF_OWNER_INTERNAL = 0;
    public static final int OFF_METHOD_NAME = 8;
    public static final int OFF_METHOD_DESC = 16;
    public static final int OFF_IMPL_FN = 24;
    public static final int OFF_SIGNATURE_ID = 32;
    public static final int OFF_IS_STATIC = 36;
    public static final int OFF_PATCH_STATE = 37;
    /** Byte offset of {@code owner_class_global_ref} within {@code NekoManifestMethod}.
     *  Set once at load time so the per-call dispatcher can hand a stable
     *  owner mirror to the impl_fn without crossing the JNI function table. */
    public static final int OFF_OWNER_CLASS_GLOBAL = 40;
}
