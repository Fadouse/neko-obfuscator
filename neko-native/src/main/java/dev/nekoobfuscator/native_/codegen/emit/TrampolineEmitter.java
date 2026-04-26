package dev.nekoobfuscator.native_.codegen.emit;

/**
 * Top-level trampoline emitter. Per signature emits two naked-asm functions
 * ({@code neko_sig_N_i2i} and {@code neko_sig_N_c2i}) and a parallel pointer
 * table {@code g_neko_sig_table}.
 *
 * The arch-specific bodies live in {@link X86_64SysVTrampoline},
 * {@link X86_64WindowsTrampoline}, {@link Aarch64SysVTrampoline}.
 * Each arch backend renders its naked function inside an
 * {@code #if defined(...)} guard so that on platforms without an
 * implementation the linker fails loudly rather than producing a silently
 * misbehaving lib.
 */
public final class TrampolineEmitter {

    private final X86_64SysVTrampoline x86_64SysV = new X86_64SysVTrampoline();
    private final X86_64WindowsTrampoline x86_64Windows = new X86_64WindowsTrampoline();
    private final Aarch64SysVTrampoline aarch64SysV = new Aarch64SysVTrampoline();

    public String render(SignaturePlan plan) {
        if (plan.shapes().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("/* === Per-signature trampolines === */\n");
        for (int i = 0; i < plan.shapes().size(); i++) {
            sb.append("extern void neko_sig_").append(i).append("_i2i(void);\n");
            sb.append("extern void neko_sig_").append(i).append("_c2i(void);\n");
        }
        /* struct neko_sig_entry is forward-declared in CCodeGenerator's prelude. */
        sb.append("__attribute__((visibility(\"hidden\"))) const struct neko_sig_entry g_neko_sig_table[] = {\n");
        for (int i = 0; i < plan.shapes().size(); i++) {
            sb.append("    { (void*)&neko_sig_").append(i).append("_i2i, (void*)&neko_sig_").append(i).append("_c2i },\n");
        }
        if (plan.shapes().isEmpty()) sb.append("    { NULL, NULL }\n");
        sb.append("};\n");
        sb.append("__attribute__((visibility(\"hidden\"))) const uint32_t g_neko_sig_table_count = ")
            .append(plan.shapes().size()).append("u;\n\n");

        sb.append("#if defined(__x86_64__) && (defined(__linux__) || defined(__APPLE__))\n");
        for (int i = 0; i < plan.shapes().size(); i++) sb.append(x86_64SysV.render(i, plan.shapes().get(i)));
        sb.append("#elif defined(__x86_64__) && defined(_WIN64)\n");
        for (int i = 0; i < plan.shapes().size(); i++) sb.append(x86_64Windows.render(i, plan.shapes().get(i)));
        sb.append("#elif defined(__aarch64__)\n");
        for (int i = 0; i < plan.shapes().size(); i++) sb.append(aarch64SysV.render(i, plan.shapes().get(i)));
        sb.append("#else\n");
        sb.append("#error \"NekoObfuscator native patcher: unsupported (arch, os) combination — add a trampoline backend\"\n");
        sb.append("#endif\n\n");
        return sb.toString();
    }
}
