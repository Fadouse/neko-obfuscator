package dev.nekoobfuscator.native_.codegen;

import dev.nekoobfuscator.core.ir.l3.CFunction;
import dev.nekoobfuscator.core.ir.l3.CStatement;
import dev.nekoobfuscator.core.ir.l3.CType;
import dev.nekoobfuscator.core.ir.l3.CVariable;
import dev.nekoobfuscator.native_.codegen.emit.BootstrapEmitter;
import dev.nekoobfuscator.native_.codegen.emit.CEmissionContext;
import dev.nekoobfuscator.native_.codegen.emit.EntryPatchEmitter;
import dev.nekoobfuscator.native_.codegen.emit.ManifestEmitter;
import dev.nekoobfuscator.native_.codegen.emit.Wave1RuntimeEmitter;
import dev.nekoobfuscator.native_.codegen.emit.Wave2FieldLdcEmitter;
import dev.nekoobfuscator.native_.codegen.emit.Wave3InvokeStaticEmitter;
import dev.nekoobfuscator.native_.codegen.emit.Wave4aRuntimeApiEmitter;
import dev.nekoobfuscator.native_.translator.NativeTranslator.NativeMethodBinding;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CCodeGenerator {
    public static final int MANIFEST_FLAGS_OFFSET = 32;
    public static final int MANIFEST_SIGNATURE_ID_OFFSET = 36;
    public static final int MANIFEST_IMPL_FN_OFFSET = 40;
    public static final int MANIFEST_METHOD_STAR_OFFSET = 48;
    public static final int MANIFEST_ENTRY_SIZE = 88;

    @SuppressWarnings("unused")
    private final SymbolTableGenerator symbols;
    private final CEmissionContext ctx;
    private final ManifestEmitter manifestEmitter;
    private final Wave1RuntimeEmitter wave1RuntimeEmitter;
    private final Wave2FieldLdcEmitter wave2FieldLdcEmitter;
    private final Wave3InvokeStaticEmitter wave3InvokeStaticEmitter;
    private final Wave4aRuntimeApiEmitter wave4aRuntimeApiEmitter;
    private final EntryPatchEmitter entryPatchEmitter;
    private final BootstrapEmitter bootstrapEmitter;
    private final LinkedHashMap<String, Integer> classSlotIndex;
    private final LinkedHashMap<String, Integer> methodSlotIndex;
    private final LinkedHashMap<String, Integer> fieldSlotIndex;
    private final LinkedHashMap<String, Integer> ownerBindIndex;
    private final LinkedHashMap<String, OwnerResolution> ownerResolutions;
    private final LinkedHashMap<String, Integer> icacheMethodIndex;
    private final LinkedHashMap<String, IcacheSiteRef> icacheSites;
    private final LinkedHashMap<String, IcacheDirectStubRef> icacheDirectStubs;
    private final LinkedHashMap<String, IcacheMetaRef> icacheMetas;
    private final LinkedHashMap<String, List<ManifestInvokeSiteRef>> manifestInvokeSites;
    private final LinkedHashSet<String> manifestOwnerInternals;

    public CCodeGenerator(long masterSeed) {
        this.ctx = new CEmissionContext(masterSeed);
        this.manifestEmitter = new ManifestEmitter(this, ctx);
        this.wave1RuntimeEmitter = new Wave1RuntimeEmitter();
        this.wave2FieldLdcEmitter = new Wave2FieldLdcEmitter();
        this.wave3InvokeStaticEmitter = new Wave3InvokeStaticEmitter(this, ctx);
        this.wave4aRuntimeApiEmitter = new Wave4aRuntimeApiEmitter();
        this.entryPatchEmitter = new EntryPatchEmitter();
        this.bootstrapEmitter = new BootstrapEmitter(wave1RuntimeEmitter);
        this.symbols = ctx.symbols();
        this.classSlotIndex = ctx.classSlotIndex();
        this.methodSlotIndex = ctx.methodSlotIndex();
        this.fieldSlotIndex = ctx.fieldSlotIndex();
        this.ownerBindIndex = ctx.ownerBindIndex();
        this.ownerResolutions = ctx.ownerResolutions();
        this.icacheMethodIndex = ctx.icacheMethodIndex();
        this.icacheSites = ctx.icacheSites();
        this.icacheDirectStubs = ctx.icacheDirectStubs();
        this.icacheMetas = ctx.icacheMetas();
        this.manifestInvokeSites = ctx.manifestInvokeSites();
        this.manifestOwnerInternals = ctx.manifestOwnerInternals();
    }

    public void configureStringCacheCount(int stringCacheCount) {
        ctx.setStringCacheCount(stringCacheCount);
    }

    public int internClass(String internalName) {
        return classSlotIndex.computeIfAbsent(internalName, ignored -> classSlotIndex.size());
    }

    public int internMethod(String owner, String name, String desc, boolean isStatic) {
        String key = owner + "." + name + desc + "/" + (isStatic ? "S" : "V");
        return methodSlotIndex.computeIfAbsent(key, ignored -> methodSlotIndex.size());
    }

    public int internField(String owner, String name, String desc, boolean isStatic) {
        String key = owner + "." + name + desc + "/" + (isStatic ? "S" : "I");
        return fieldSlotIndex.computeIfAbsent(key, ignored -> fieldSlotIndex.size());
    }

    public String classSlotName(String internalName) {
        return "g_cls_" + internClass(internalName);
    }

    public String methodSlotName(String owner, String name, String desc, boolean isStatic) {
        return "g_mid_" + internMethod(owner, name, desc, isStatic);
    }

    public String fieldSlotName(String owner, String name, String desc, boolean isStatic) {
        return "g_fid_" + internField(owner, name, desc, isStatic);
    }

    public String fieldOffsetSlotName(String owner, String name, String desc, boolean isStatic) {
        return "g_off_" + internField(owner, name, desc, isStatic);
    }

    public String staticFieldOffsetSlotName(String owner, String name, String desc, boolean isStatic) {
        return "g_static_off_" + internField(owner, name, desc, isStatic);
    }

    public String staticFieldBaseSlotName(String owner, String name, String desc, boolean isStatic) {
        return "g_static_base_" + internField(owner, name, desc, isStatic);
    }

    public void registerBindingOwner(String ownerInternalName) {
        ownerBindIndex.computeIfAbsent(ownerInternalName, ignored -> ownerBindIndex.size());
        OwnerResolution resolution = ownerResolutions.computeIfAbsent(ownerInternalName, ignored -> new OwnerResolution());
        resolution.classes.add(ownerInternalName);
        manifestOwnerInternals.add(ownerInternalName);
        internClass(ownerInternalName);
    }

    public int registerManifestMethod(String bindingKey) {
        return manifestEmitter.registerManifestMethod(bindingKey);
    }

    public String reserveManifestFieldSite(String bindingKey, String bindingOwner, String owner, String name, String desc, boolean isStatic) {
        return manifestEmitter.reserveManifestFieldSite(bindingKey, bindingOwner, owner, name, desc, isStatic);
    }

    public String reserveManifestInvokeSite(String bindingKey, String bindingOwner, String owner, String name, String desc, int opcode) {
        return manifestEmitter.reserveManifestInvokeSite(bindingKey, bindingOwner, owner, name, desc, opcode);
    }

    public String reserveManifestStringLdcSite(String bindingKey, String bindingOwner, String literal) {
        return manifestEmitter.reserveManifestStringLdcSite(bindingKey, bindingOwner, literal);
    }

    public String reserveManifestClassLdcSite(String bindingKey, String bindingOwner, String descriptor) {
        return manifestEmitter.reserveManifestClassLdcSite(bindingKey, bindingOwner, descriptor);
    }

    public void registerOwnerClassReference(String bindingOwner, String classOwner) {
        registerBindingOwner(bindingOwner);
        ownerResolutions.get(bindingOwner).classes.add(classOwner);
        manifestOwnerInternals.add(classOwner);
        internClass(classOwner);
    }

    public void registerOwnerMethodReference(String bindingOwner, String owner, String name, String desc, boolean isStatic) {
        registerOwnerClassReference(bindingOwner, owner);
        ownerResolutions.get(bindingOwner).methods.add(new MethodRef(owner, name, desc, isStatic));
        internMethod(owner, name, desc, isStatic);
    }

    public void registerOwnerFieldReference(String bindingOwner, String owner, String name, String desc, boolean isStatic) {
        registerOwnerClassReference(bindingOwner, owner);
        ownerResolutions.get(bindingOwner).fields.add(new FieldRef(owner, name, desc, isStatic));
        internField(owner, name, desc, isStatic);
    }

    public void registerOwnerStringReference(String bindingOwner, String value, String cacheVar) {
        registerBindingOwner(bindingOwner);
        ownerResolutions.get(bindingOwner).strings.add(new StringRef(cacheVar, value));
    }

    public String reserveInvokeCacheSite(String bindingOwner, String methodKey, int siteIndex) {
        String cacheMethodKey = bindingOwner + '#' + methodKey;
        String siteKey = cacheMethodKey + '#' + siteIndex;
        registerBindingOwner(bindingOwner);
        return icacheSites.computeIfAbsent(siteKey, ignored -> new IcacheSiteRef(
            ownerBindIndex.get(bindingOwner),
            icacheMethodIndex.computeIfAbsent(cacheMethodKey, key -> icacheMethodIndex.size()),
            siteIndex,
            bindingOwner,
            methodKey
        )).symbol();
    }

    public String reserveInvokeCacheDirectStub(
        String bindingOwner,
        String methodKey,
        int siteIndex,
        NativeMethodBinding binding,
        Type[] args,
        Type returnType
    ) {
        String cacheMethodKey = bindingOwner + '#' + methodKey;
        String siteKey = cacheMethodKey + '#' + siteIndex;
        registerBindingOwner(bindingOwner);
        return icacheDirectStubs.computeIfAbsent(siteKey, ignored -> new IcacheDirectStubRef(
            ownerBindIndex.get(bindingOwner),
            icacheMethodIndex.computeIfAbsent(cacheMethodKey, key -> icacheMethodIndex.size()),
            siteIndex,
            binding,
            args.clone(),
            returnType
        )).symbol();
    }

    public String reserveInvokeCacheMeta(
        String bindingOwner,
        String methodKey,
        int siteIndex,
        String name,
        String desc,
        boolean isInterface,
        String translatedClassSlot,
        String translatedStubSymbol
    ) {
        String cacheMethodKey = bindingOwner + '#' + methodKey;
        String siteKey = cacheMethodKey + '#' + siteIndex;
        registerBindingOwner(bindingOwner);
        return icacheMetas.computeIfAbsent(siteKey, ignored -> new IcacheMetaRef(
            ownerBindIndex.get(bindingOwner),
            icacheMethodIndex.computeIfAbsent(cacheMethodKey, key -> icacheMethodIndex.size()),
            siteIndex,
            name,
            desc,
            isInterface,
            translatedClassSlot,
            translatedStubSymbol
        )).symbol();
    }

    public String generateHeader(List<NativeMethodBinding> bindings) {
        StringBuilder sb = new StringBuilder();
        sb.append("#ifndef NEKO_NATIVE_H\n");
        sb.append("#define NEKO_NATIVE_H\n\n");
        sb.append("#include <jni.h>\n");
        sb.append("#include <stdint.h>\n\n");
        for (NativeMethodBinding binding : bindings) {
            sb.append(renderPrototype(binding)).append(";\n");
        }
        sb.append("\n#endif\n");
        return sb.toString();
    }

    public List<GeneratedSource> generateAdditionalSources(List<NativeMethodBinding> bindings) {
        if (bindings.isEmpty()) {
            return List.of();
        }
        return List.of(new GeneratedSource("neko_stubs.S", generateAssembly(bindings)));
    }

    public List<SignatureInfo> signatureInfos(List<NativeMethodBinding> bindings) {
        SignaturePlan plan = buildSignaturePlan(bindings);
        List<SignatureInfo> infos = new ArrayList<>(plan.signatures().size());
        for (SignatureShape signature : plan.signatures()) {
            infos.add(new SignatureInfo(signature.id(), signature.key()));
        }
        return infos;
    }

    public String generateSource(List<CFunction> functions, List<NativeMethodBinding> bindings) {
        SignaturePlan signaturePlan = buildSignaturePlan(bindings);
        StringBuilder body = new StringBuilder();
        for (CFunction function : functions) {
            body.append(renderFunction(function)).append("\n");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("#if defined(__linux__)\n");
        sb.append("#define _GNU_SOURCE\n");
        sb.append("#endif\n");
        sb.append("#include \"neko_native.h\"\n");
        sb.append("#include <jvmti.h>\n");
        sb.append("#include <stdint.h>\n");
        sb.append("#include <stddef.h>\n");
        sb.append("#include <stdio.h>\n");
        sb.append("#include <stdlib.h>\n");
        sb.append("#include <string.h>\n");
        sb.append("#include <stdarg.h>\n");
        sb.append("#include <math.h>\n\n");
        sb.append("#if defined(_WIN32)\n");
        sb.append("#include <windows.h>\n");
        sb.append("#include <psapi.h>\n");
        sb.append("#else\n");
        sb.append("#include <dlfcn.h>\n");
        sb.append("#endif\n");
        sb.append("#if defined(__linux__)\n");
        sb.append("#include <link.h>\n");
        sb.append("#endif\n");
        sb.append("#if defined(__APPLE__)\n");
        sb.append("#include <mach-o/dyld.h>\n");
        sb.append("#endif\n\n");
        sb.append(wave3InvokeStaticEmitter.renderResolutionCaches());
        sb.append(wave1RuntimeEmitter.renderRuntimeSupport());
        sb.append(wave1RuntimeEmitter.renderHotSpotSupport());
        sb.append(manifestEmitter.renderManifestSupport(bindings, signaturePlan));
        sb.append(renderSignatureDispatchSupport(signaturePlan));
        sb.append(bootstrapEmitter.renderBootstrapSupport());
        sb.append(wave3InvokeStaticEmitter.renderBindOwnerFunctions());
        sb.append(wave3InvokeStaticEmitter.renderBindSupport());
        sb.append(wave2FieldLdcEmitter.renderWave2Support());
        sb.append(wave3InvokeStaticEmitter.renderWave3Support());
        sb.append(wave4aRuntimeApiEmitter.renderWave4ASupport());
        sb.append(entryPatchEmitter.renderEntryPatchSupport());
        sb.append(wave4aRuntimeApiEmitter.renderObjectReturnSupport());
        sb.append(wave3InvokeStaticEmitter.renderIcacheDirectStubs());
        sb.append(wave3InvokeStaticEmitter.renderIcacheMetas());
        sb.append(body);
        sb.append(renderJniOnLoad());
        return sb.toString();
    }

    private String renderPrototype(NativeMethodBinding binding) {
        StringBuilder sb = new StringBuilder();
        sb.append(rawFunctionReturnType(Type.getReturnType(binding.descriptor()))).append(' ')
            .append(binding.cFunctionName()).append('(');
        if (!binding.isStatic()) {
            sb.append("void* _this");
        }
        Type[] args = Type.getArgumentTypes(binding.descriptor());
        if (binding.isStatic() && args.length == 0) {
            sb.append("void");
        }
        for (int i = 0; i < args.length; i++) {
            if (i > 0 || !binding.isStatic()) {
                sb.append(", ");
            }
            sb.append(rawFunctionParamType(args[i])).append(" p").append(i);
        }
        sb.append(")");
        return sb.toString();
    }

    private String renderFunction(CFunction fn) {
        StringBuilder sb = new StringBuilder();
        sb.append("__attribute__((used, visibility(\"default\"))) ").append(rawFunctionReturnType(fn.returnType())).append(' ').append(fn.name()).append('(');
        if (fn.params().isEmpty()) {
            sb.append("void");
        } else {
            for (int i = 0; i < fn.params().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(renderParam(fn.params().get(i)));
            }
        }
        sb.append(") {\n");
        sb.append("    JNIEnv *env = neko_current_env();\n");
        sb.append("    void *thread = neko_get_current_thread();\n");
        if (requiresLocalCapacity(fn)) {
            sb.append("    if (env != NULL) neko_ensure_local_capacity(env, 8192);\n");
        }
        sb.append("    neko_slot stack[").append(fn.maxStack() + 16).append("];\n");
        sb.append("    int sp = 0;\n");
        sb.append("    neko_slot locals[").append(fn.maxLocals() + 8).append("];\n");
        sb.append("    memset(locals, 0, sizeof(locals));\n");
        for (CStatement statement : fn.body()) {
            sb.append(renderStatement(statement));
        }
        sb.append("}\n");
        return sb.toString();
    }

    private String renderStatement(CStatement statement) {
        if (statement instanceof CStatement.RawC raw) {
            return "    " + raw.code() + "\n";
        }
        if (statement instanceof CStatement.Label label) {
            return label.name() + ": ;\n";
        }
        if (statement instanceof CStatement.Goto go) {
            return "    goto " + go.label() + ";\n";
        }
        if (statement instanceof CStatement.ReturnVoid) {
            return "    return;\n";
        }
        if (statement instanceof CStatement.Return ret) {
            return "    return " + ret.value() + ";\n";
        }
        if (statement instanceof CStatement.Comment comment) {
            return "    /* " + comment.text() + " */\n";
        }
        throw new IllegalStateException("Unsupported C statement in generator: " + statement.getClass().getSimpleName());
    }

    private String renderSignatureDispatchSupport(SignaturePlan signaturePlan) {
        if (signaturePlan.signatures().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("// === Signature dispatch helpers ===\n");
        for (SignatureShape signature : signaturePlan.signatures()) {
            sb.append(renderSignatureDispatcher(signature, false));
            sb.append(renderSignatureDispatcher(signature, true));
        }
        sb.append('\n');
        return sb.toString();
    }

    private String renderSignatureDispatcher(SignatureShape signature, boolean instance) {
        StringBuilder sb = new StringBuilder();
        String returnType = rawType(signature.returnKind());
        String dispatcherName = "neko_sig_" + signature.id() + (instance ? "_dispatch_instance" : "_dispatch_static");
        String functionPointerType = "neko_sig_" + signature.id() + (instance ? "_instance_fn" : "_static_fn");
        List<String> params = new ArrayList<>();
        List<String> args = new ArrayList<>();

        params.add("const NekoManifestMethod* entry");
        if (instance) {
            params.add("void* _this");
            args.add("_this");
        }
        for (int i = 0; i < signature.argKinds().size(); i++) {
            params.add(rawType(signature.argKinds().get(i)) + " p" + i);
            args.add("p" + i);
        }

        sb.append("typedef ").append(returnType).append(" (*").append(functionPointerType).append(")(");
        if (instance) {
            sb.append("void*");
            for (int i = 0; i < signature.argKinds().size(); i++) {
                sb.append(", ").append(rawType(signature.argKinds().get(i)));
            }
        } else {
            for (int i = 0; i < signature.argKinds().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(rawType(signature.argKinds().get(i)));
            }
            if (signature.argKinds().isEmpty()) {
                sb.append("void");
            }
        }
        sb.append(");\n");

        sb.append("__attribute__((visibility(\"hidden\"))) ").append(returnType).append(' ').append(dispatcherName).append('(');
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(params.get(i));
        }
        sb.append(") {\n");
        if (signature.returnKind() == 'V') {
            sb.append("    ((").append(functionPointerType).append(")entry->impl_fn)(");
        } else {
            sb.append("    return ((").append(functionPointerType).append(")entry->impl_fn)(");
        }
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(args.get(i));
        }
        sb.append(");\n");
        if (signature.returnKind() == 'V') {
            sb.append("    return;\n");
        }
        sb.append("}\n\n");
        return sb.toString();
    }

    private String renderJniOnLoad() {
        return """
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = NULL;
    jvmtiEnv *jvmti = NULL;
    jint env_status;
    (void)reserved;
    g_neko_java_vm = vm;
    env_status = (*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6);
    if (env_status != JNI_OK || env == NULL) {
        neko_error_log("GetEnv(JNI_VERSION_1_6) failed, falling back to throw body");
        return JNI_VERSION_1_6;
    }
    env_status = (*vm)->GetEnv(vm, (void**)&jvmti, JVMTI_VERSION_1_2);
    if (env_status != JNI_OK || jvmti == NULL) {
        neko_error_log("GetEnv(JVMTI_VERSION_1_2) failed, falling back to throw body");
        return JNI_VERSION_1_6;
    }
    neko_mark_loader_loaded(env);
    g_neko_jvmti = jvmti;
    if (!neko_resolve_vm_symbols()) {
        return JNI_VERSION_1_6;
    }
    if (!neko_parse_vm_layout(env)) {
        return JNI_VERSION_1_6;
    }
    neko_log_runtime_helpers_ready();
    neko_log_wave4a_status();
    if (!neko_init_jvmti(vm, jvmti)) {
        return JNI_VERSION_1_6;
    }
    if (!neko_discover_loaded_classes(env, jvmti)) {
        return JNI_VERSION_1_6;
    }
    if (!neko_discover_manifest_owners(env, jvmti)) {
        return JNI_VERSION_1_6;
    }
    if (!neko_prewarm_ldc_sites(env)) {
        return JNI_VERSION_1_6;
    }
    neko_manifest_lock_enter();
    neko_patch_discovered_methods();
    neko_manifest_lock_exit();
    if (!neko_install_class_prepare_callback(jvmti)) {
        return JNI_VERSION_1_6;
    }
    neko_debug_log("discovery matched %u/%u manifest entries", g_neko_manifest_match_count, g_neko_manifest_method_count);
    neko_debug_log("patched %u/%u manifest entries", g_neko_manifest_patch_count, g_neko_manifest_method_count);
    neko_log_wave2_ready();
    neko_log_wave3_ready();
    return JNI_VERSION_1_6;
}
""";
    }

    private boolean requiresLocalCapacity(CFunction fn) {
        for (CStatement statement : fn.body()) {
            if (statement instanceof CStatement.RawC raw) {
                String code = raw.code();
                if (code.contains("neko_new_") || code.contains("neko_call_") || code.contains("neko_get_object_array_element")
                    || code.contains("neko_set_object_array_element") || code.contains("neko_get_object_class")
                    || code.contains("NEKO_ENSURE_STRING") || code.contains("neko_string_concat")
                    || code.contains("neko_class_for_descriptor") || code.contains("neko_resolve_indy")
                    || code.contains("neko_resolve_constant_dynamic")) {
                    return true;
                }
            }
        }
        return false;
    }

    private String generateAssembly(List<NativeMethodBinding> bindings) {
        SignaturePlan signaturePlan = buildSignaturePlan(bindings);
        StringBuilder sb = new StringBuilder();
        sb.append("#if !defined(__x86_64__) || !defined(__linux__)\n");
        sb.append("#error \"M3 stubs only support x86_64 Linux\"\n");
        sb.append("#endif\n\n");
        sb.append(".intel_syntax noprefix\n");
        sb.append(".text\n\n");
        sb.append("# Signature-stub sketch:\n");
        sb.append("#   i2i: lookup manifest entry by Method*, pop return address, unpack interpreter slots,\n");
        sb.append("#        call hidden C dispatcher, restore sender_sp (r13) and jump to continuation.\n");
        sb.append("#   c2i: lookup manifest entry by Method*, materialize any stack-overflow args for the\n");
        sb.append("#        hidden C dispatcher, put entry in rdi, call dispatcher, return to compiled caller.\n\n");
        for (SignatureShape signature : signaturePlan.signatures()) {
            appendSignatureAssembly(sb, signature);
        }
        return sb.toString();
    }

    private void appendSignatureAssembly(StringBuilder sb, SignatureShape signature) {
        String prefix = "neko_sig_" + signature.id();
        DispatchPlan i2iStatic = buildI2iDispatchPlan(buildLogicalArgKinds(signature, false));
        DispatchPlan i2iInstance = buildI2iDispatchPlan(buildLogicalArgKinds(signature, true));
        DispatchPlan c2iStatic = buildC2iDispatchPlan(buildLogicalArgKinds(signature, false));
        DispatchPlan c2iInstance = buildC2iDispatchPlan(buildLogicalArgKinds(signature, true));

        sb.append(".globl ").append(prefix).append("_i2i\n");
        sb.append(".type ").append(prefix).append("_i2i, @function\n");
        sb.append(prefix).append("_i2i:\n");
        appendManifestLookup(sb, prefix + "_i2i_found", prefix + "_i2i_fail");
        sb.append("    test WORD PTR [rax + ").append(MANIFEST_FLAGS_OFFSET).append("], 1\n");
        sb.append("    jnz ").append(prefix).append("_i2i_static\n");
        appendI2iMode(sb, signature, i2iInstance, true, prefix + "_i2i_instance");
        sb.append(prefix).append("_i2i_static:\n");
        appendI2iMode(sb, signature, i2iStatic, false, prefix + "_i2i_static");
        sb.append(prefix).append("_i2i_fail:\n");
        sb.append("    pop r10\n");
        appendZeroReturn(sb, signature.returnKind());
        sb.append("    mov rsp, r13\n");
        sb.append("    jmp r10\n\n");

        sb.append(".globl ").append(prefix).append("_c2i\n");
        sb.append(".type ").append(prefix).append("_c2i, @function\n");
        sb.append(prefix).append("_c2i:\n");
        appendManifestLookup(sb, prefix + "_c2i_found", prefix + "_c2i_fail");
        sb.append("    test WORD PTR [rax + ").append(MANIFEST_FLAGS_OFFSET).append("], 1\n");
        sb.append("    jnz ").append(prefix).append("_c2i_static\n");
        appendC2iMode(sb, signature, c2iInstance, true, prefix + "_c2i_instance");
        sb.append(prefix).append("_c2i_static:\n");
        appendC2iMode(sb, signature, c2iStatic, false, prefix + "_c2i_static");
        sb.append(prefix).append("_c2i_fail:\n");
        appendZeroReturn(sb, signature.returnKind());
        sb.append("    ret\n\n");
    }

    private void appendManifestLookup(StringBuilder sb, String foundLabel, String failLabel) {
        sb.append("    lea r10, [rip + g_neko_manifest_method_stars]\n");
        sb.append("    xor r11d, r11d\n");
        sb.append(foundLabel).append("_scan:\n");
        sb.append("    cmp r11d, DWORD PTR [rip + g_neko_manifest_method_count]\n");
        sb.append("    jae ").append(failLabel).append("\n");
        sb.append("    cmp QWORD PTR [r10 + r11 * 8], rbx\n");
        sb.append("    je ").append(foundLabel).append("\n");
        sb.append("    inc r11d\n");
        sb.append("    jmp ").append(foundLabel).append("_scan\n");
        sb.append(foundLabel).append(":\n");
        sb.append("    mov rax, r11\n");
        sb.append("    imul rax, rax, ").append(MANIFEST_ENTRY_SIZE).append("\n");
        sb.append("    lea r10, [rip + g_neko_manifest_methods]\n");
        sb.append("    add rax, r10\n");
    }

    private void appendI2iMode(StringBuilder sb, SignatureShape signature, DispatchPlan plan, boolean instance, String labelPrefix) {
        sb.append("    pop r10\n");
        sb.append("    mov r11, rsp\n");
        sb.append("    sub rsp, ").append(plan.frameBytes()).append("\n");
        sb.append("    mov QWORD PTR [rsp + ").append(plan.entrySaveOffset()).append("], rax\n");
        sb.append("    mov QWORD PTR [rsp + ").append(plan.retSaveOffset()).append("], r10\n");
        emitStackCopiesFromInterpreter(sb, plan, labelPrefix);
        emitRegisterLoadsFromInterpreter(sb, plan, labelPrefix);
        sb.append("    mov rdi, QWORD PTR [rsp + ").append(plan.entrySaveOffset()).append("]\n");
        sb.append("    call neko_sig_").append(signature.id()).append(instance ? "_dispatch_instance" : "_dispatch_static").append("\n");
        if (signature.returnKind() == 'L') {
            sb.append("    mov rdi, rax\n");
            sb.append("    call neko_encode_heap_oop_runtime\n");
        }
        sb.append("    mov r10, QWORD PTR [rsp + ").append(plan.retSaveOffset()).append("]\n");
        sb.append("    mov rsp, r13\n");
        sb.append("    jmp r10\n");
    }

    private void appendC2iMode(StringBuilder sb, SignatureShape signature, DispatchPlan plan, boolean instance, String labelPrefix) {
        sb.append("    sub rsp, ").append(plan.frameBytes()).append("\n");
        sb.append("    mov QWORD PTR [rsp + ").append(plan.entrySaveOffset()).append("], rax\n");
        for (int gpIndex = 0; gpIndex < plan.sourceLayout().gpRegisterCount(); gpIndex++) {
            sb.append("    mov QWORD PTR [rsp + ").append(plan.gpSpillBaseOffset() + (gpIndex * 8)).append("], ").append(javaGpRegister64(gpIndex)).append("\n");
        }
        emitStackCopiesFromCompiled(sb, plan, labelPrefix);
        sb.append("    mov rdi, QWORD PTR [rsp + ").append(plan.entrySaveOffset()).append("]\n");
        sb.append("    call neko_sig_").append(signature.id()).append(instance ? "_dispatch_instance" : "_dispatch_static").append("\n");
        sb.append("    add rsp, ").append(plan.frameBytes()).append("\n");
        sb.append("    ret\n");
    }

    private void emitStackCopiesFromInterpreter(StringBuilder sb, DispatchPlan plan, String labelPrefix) {
        for (int i = 0; i < plan.logicalArgKinds().size(); i++) {
            ArgLocation dest = plan.destLayout().locations().get(i);
            if (dest.kind() != ArgLocationKind.STACK) {
                continue;
            }
            ArgLocation source = plan.sourceLayout().locations().get(i);
            emitInterpreterLoadToStack(sb, plan, source, dest.index(), plan.logicalArgKinds().get(i), labelPrefix + "_stack_" + i);
        }
    }

    private void emitRegisterLoadsFromInterpreter(StringBuilder sb, DispatchPlan plan, String labelPrefix) {
        for (int i = 0; i < plan.logicalArgKinds().size(); i++) {
            ArgLocation dest = plan.destLayout().locations().get(i);
            ArgLocation source = plan.sourceLayout().locations().get(i);
            char kind = plan.logicalArgKinds().get(i);
            if (dest.kind() == ArgLocationKind.GP_REG) {
                emitInterpreterLoadToGpRegister(sb, source, dispatcherGpRegister(dest.index()), dispatcherGpRegister32(dest.index()), kind);
            } else if (dest.kind() == ArgLocationKind.FP_REG) {
                emitInterpreterLoadToFpRegister(sb, source, fpRegister(dest.index()), kind);
            }
        }
    }

    private void emitStackCopiesFromCompiled(StringBuilder sb, DispatchPlan plan, String labelPrefix) {
        for (int i = 0; i < plan.logicalArgKinds().size(); i++) {
            ArgLocation dest = plan.destLayout().locations().get(i);
            if (dest.kind() != ArgLocationKind.STACK) {
                continue;
            }
            ArgLocation source = plan.sourceLayout().locations().get(i);
            emitCompiledLoadToStack(sb, plan, source, dest.index(), plan.logicalArgKinds().get(i), labelPrefix + "_stack_" + i);
        }
    }

    private void emitInterpreterLoadToStack(StringBuilder sb, DispatchPlan plan, ArgLocation source, int destStackSlot, char kind, String labelPrefix) {
        int sourceOffset = source.index() * 8;
        int destOffset = destStackSlot * 8;
        if (kind == 'F') {
            sb.append("    movss xmm15, DWORD PTR [r11 + ").append(sourceOffset).append("]\n");
            sb.append("    movss DWORD PTR [rsp + ").append(destOffset).append("], xmm15\n");
            return;
        }
        if (kind == 'D') {
            sb.append("    movsd xmm15, QWORD PTR [r11 + ").append(sourceOffset).append("]\n");
            sb.append("    movsd QWORD PTR [rsp + ").append(destOffset).append("], xmm15\n");
            return;
        }
        if (kind == 'L' || kind == 'J') {
            sb.append("    mov rax, QWORD PTR [r11 + ").append(sourceOffset).append("]\n");
            sb.append("    mov QWORD PTR [rsp + ").append(destOffset).append("], rax\n");
            return;
        }
        sb.append("    mov eax, DWORD PTR [r11 + ").append(sourceOffset).append("]\n");
        sb.append("    mov DWORD PTR [rsp + ").append(destOffset).append("], eax\n");
    }

    private void emitInterpreterLoadToGpRegister(StringBuilder sb, ArgLocation source, String register64, String register32, char kind) {
        int sourceOffset = source.index() * 8;
        if (kind == 'L' || kind == 'J') {
            sb.append("    mov ").append(register64).append(", QWORD PTR [r11 + ").append(sourceOffset).append("]\n");
        } else {
            sb.append("    mov ").append(register32).append(", DWORD PTR [r11 + ").append(sourceOffset).append("]\n");
        }
    }

    private void emitInterpreterLoadToFpRegister(StringBuilder sb, ArgLocation source, String xmmRegister, char kind) {
        int sourceOffset = source.index() * 8;
        if (kind == 'F') {
            sb.append("    movss ").append(xmmRegister).append(", DWORD PTR [r11 + ").append(sourceOffset).append("]\n");
        } else {
            sb.append("    movsd ").append(xmmRegister).append(", QWORD PTR [r11 + ").append(sourceOffset).append("]\n");
        }
    }

    private void emitCompiledLoadToStack(StringBuilder sb, DispatchPlan plan, ArgLocation source, int destStackSlot, char kind, String labelPrefix) {
        int destOffset = destStackSlot * 8;
        switch (source.kind()) {
            case GP_REG -> {
                int spillOffset = plan.gpSpillBaseOffset() + (source.index() * 8);
                if (kind == 'L' || kind == 'J') {
                    sb.append("    mov rax, QWORD PTR [rsp + ").append(spillOffset).append("]\n");
                    sb.append("    mov QWORD PTR [rsp + ").append(destOffset).append("], rax\n");
                } else {
                    sb.append("    mov eax, DWORD PTR [rsp + ").append(spillOffset).append("]\n");
                    sb.append("    mov DWORD PTR [rsp + ").append(destOffset).append("], eax\n");
                }
            }
            case STACK -> {
                int sourceOffset = plan.frameBytes() + 8 + (source.index() * 8);
                if (kind == 'F') {
                    sb.append("    movss xmm15, DWORD PTR [rsp + ").append(sourceOffset).append("]\n");
                    sb.append("    movss DWORD PTR [rsp + ").append(destOffset).append("], xmm15\n");
                } else if (kind == 'D') {
                    sb.append("    movsd xmm15, QWORD PTR [rsp + ").append(sourceOffset).append("]\n");
                    sb.append("    movsd QWORD PTR [rsp + ").append(destOffset).append("], xmm15\n");
                } else if (kind == 'L' || kind == 'J') {
                    sb.append("    mov rax, QWORD PTR [rsp + ").append(sourceOffset).append("]\n");
                    sb.append("    mov QWORD PTR [rsp + ").append(destOffset).append("], rax\n");
                } else {
                    sb.append("    mov eax, DWORD PTR [rsp + ").append(sourceOffset).append("]\n");
                    sb.append("    mov DWORD PTR [rsp + ").append(destOffset).append("], eax\n");
                }
            }
            default -> throw new IllegalStateException("Unexpected compiled-source location: " + source.kind());
        }
    }

    private DispatchPlan buildI2iDispatchPlan(List<Character> logicalArgKinds) {
        CallingLayout sourceLayout = buildInterpreterLayout(logicalArgKinds);
        CallingLayout destLayout = buildDispatcherLayout(logicalArgKinds);
        int callStackBytes = destLayout.stackSlotCount() * 8;
        int entrySaveOffset = callStackBytes;
        int retSaveOffset = entrySaveOffset + 8;
        int desiredBytes = callStackBytes + 16;
        int frameBytes = alignForPopCallFrame(desiredBytes);
        return new DispatchPlan(logicalArgKinds, sourceLayout, destLayout, frameBytes, entrySaveOffset, retSaveOffset, -1);
    }

    private DispatchPlan buildC2iDispatchPlan(List<Character> logicalArgKinds) {
        CallingLayout sourceLayout = buildJavaLayout(logicalArgKinds);
        CallingLayout destLayout = buildDispatcherLayout(logicalArgKinds);
        int callStackBytes = destLayout.stackSlotCount() * 8;
        int entrySaveOffset = callStackBytes;
        int gpSpillBaseOffset = entrySaveOffset + 8;
        int frameBytes = alignUp(callStackBytes + 8 + (sourceLayout.gpRegisterCount() * 8), 16);
        return new DispatchPlan(logicalArgKinds, sourceLayout, destLayout, frameBytes, entrySaveOffset, -1, gpSpillBaseOffset);
    }

    private CallingLayout buildJavaLayout(List<Character> logicalArgKinds) {
        return buildAbiLayout(logicalArgKinds, 6, 8);
    }

    private CallingLayout buildDispatcherLayout(List<Character> logicalArgKinds) {
        return buildAbiLayout(logicalArgKinds, 5, 8);
    }

    private CallingLayout buildAbiLayout(List<Character> logicalArgKinds, int gpRegisterLimit, int fpRegisterLimit) {
        List<ArgLocation> locations = new ArrayList<>(logicalArgKinds.size());
        int gpUsed = 0;
        int fpUsed = 0;
        int stackUsed = 0;
        for (char kind : logicalArgKinds) {
            if (isFloatingKind(kind)) {
                if (fpUsed < fpRegisterLimit) {
                    locations.add(new ArgLocation(ArgLocationKind.FP_REG, fpUsed++));
                } else {
                    locations.add(new ArgLocation(ArgLocationKind.STACK, stackUsed++));
                }
            } else if (gpUsed < gpRegisterLimit) {
                locations.add(new ArgLocation(ArgLocationKind.GP_REG, gpUsed++));
            } else {
                locations.add(new ArgLocation(ArgLocationKind.STACK, stackUsed++));
            }
        }
        return new CallingLayout(locations, stackUsed, gpUsed);
    }

    private CallingLayout buildInterpreterLayout(List<Character> logicalArgKinds) {
        List<ArgLocation> locations = new ArrayList<>(logicalArgKinds.size());
        int totalSlots = 0;
        for (char kind : logicalArgKinds) {
            totalSlots += slotsForKind(kind);
        }
        int remainingSlots = totalSlots;
        for (char kind : logicalArgKinds) {
            int slots = slotsForKind(kind);
            locations.add(new ArgLocation(ArgLocationKind.INTERPRETER_STACK, remainingSlots - slots));
            remainingSlots -= slots;
        }
        return new CallingLayout(locations, 0, 0);
    }

    private List<Character> buildLogicalArgKinds(SignatureShape signature, boolean instance) {
        List<Character> logicalArgKinds = new ArrayList<>(signature.argKinds().size() + (instance ? 1 : 0));
        if (instance) {
            logicalArgKinds.add('L');
        }
        logicalArgKinds.addAll(signature.argKinds());
        return logicalArgKinds;
    }

    private int alignForPopCallFrame(int bytes) {
        int remainder = Math.floorMod(bytes, 16);
        if (remainder == 8) {
            return bytes;
        }
        return bytes + Math.floorMod(8 - remainder, 16);
    }

    private int alignUp(int value, int alignment) {
        if (value == 0) {
            return 0;
        }
        return ((value + alignment - 1) / alignment) * alignment;
    }

    private int slotsForKind(char kind) {
        return isWideKind(kind) ? 2 : 1;
    }

    private String javaGpRegister64(int index) {
        return switch (index) {
            case 0 -> "rsi";
            case 1 -> "rdx";
            case 2 -> "rcx";
            case 3 -> "r8";
            case 4 -> "r9";
            case 5 -> "rdi";
            default -> throw new IllegalArgumentException("Unexpected Java GP register index: " + index);
        };
    }

    private String dispatcherGpRegister(int index) {
        return switch (index) {
            case 0 -> "rsi";
            case 1 -> "rdx";
            case 2 -> "rcx";
            case 3 -> "r8";
            case 4 -> "r9";
            default -> throw new IllegalArgumentException("Unexpected dispatcher GP register index: " + index);
        };
    }

    private String dispatcherGpRegister32(int index) {
        return switch (index) {
            case 0 -> "esi";
            case 1 -> "edx";
            case 2 -> "ecx";
            case 3 -> "r8d";
            case 4 -> "r9d";
            default -> throw new IllegalArgumentException("Unexpected dispatcher GP register index: " + index);
        };
    }

    private String fpRegister(int index) {
        return "xmm" + index;
    }

    private void appendZeroReturn(StringBuilder sb, char returnKind) {
        if (returnKind == 'F' || returnKind == 'D') {
            sb.append("    pxor xmm0, xmm0\n");
        } else {
            sb.append("    xor eax, eax\n");
        }
    }

    private SignaturePlan buildSignaturePlan(List<NativeMethodBinding> bindings) {
        LinkedHashMap<String, SignatureShape> signaturesByKey = new LinkedHashMap<>();
        List<Integer> bindingSignatureIds = new ArrayList<>(bindings.size());
        int maxArgCount = 0;
        for (NativeMethodBinding binding : bindings) {
            SignatureShape signature = registerSignatureShape(signaturesByKey, binding.descriptor());
            bindingSignatureIds.add(signature.id());
            maxArgCount = Math.max(maxArgCount, signature.argKinds().size());
        }
        for (List<ManifestInvokeSiteRef> sites : manifestInvokeSites.values()) {
            for (ManifestInvokeSiteRef site : sites) {
                SignatureShape signature = registerSignatureShape(signaturesByKey, site.desc());
                maxArgCount = Math.max(maxArgCount, signature.argKinds().size());
            }
        }
        Map<String, Integer> signatureIdsByKey = new LinkedHashMap<>();
        for (SignatureShape signature : signaturesByKey.values()) {
            signatureIdsByKey.put(signature.key(), signature.id());
        }
        return new SignaturePlan(List.copyOf(signaturesByKey.values()), List.copyOf(bindingSignatureIds), maxArgCount, Map.copyOf(signatureIdsByKey));
    }

    private SignatureShape registerSignatureShape(LinkedHashMap<String, SignatureShape> signaturesByKey, String descriptor) {
        Type[] argumentTypes = Type.getArgumentTypes(descriptor);
        List<Character> argKinds = new ArrayList<>(argumentTypes.length);
        String key = signatureKey(descriptor);
        char returnKind = collapseKind(Type.getReturnType(descriptor));
        for (Type argumentType : argumentTypes) {
            argKinds.add(collapseKind(argumentType));
        }
        return signaturesByKey.computeIfAbsent(key, ignored -> new SignatureShape(signaturesByKey.size(), key, returnKind, List.copyOf(argKinds)));
    }

    private String signatureKey(String descriptor) {
        Type[] argumentTypes = Type.getArgumentTypes(descriptor);
        StringBuilder key = new StringBuilder();
        key.append('(');
        for (Type argumentType : argumentTypes) {
            key.append(collapseKind(argumentType));
        }
        return key.append(')').append(collapseKind(Type.getReturnType(descriptor))).toString();
    }

    private String rawFunctionReturnType(Type type) {
        return rawType(collapseKind(type));
    }

    private String rawFunctionReturnType(CType type) {
        return switch (type) {
            case VOID -> "void";
            case JLONG -> "int64_t";
            case JFLOAT -> "float";
            case JDOUBLE -> "double";
            case JOBJECT, JCLASS, JSTRING, JARRAY -> "void*";
            default -> "int32_t";
        };
    }

    private String rawFunctionParamType(Type type) {
        return rawType(collapseKind(type));
    }

    private String rawFunctionParamType(CVariable variable) {
        return rawFunctionReturnType(variable.type());
    }

    private String rawType(char kind) {
        return switch (kind) {
            case 'V' -> "void";
            case 'J' -> "int64_t";
            case 'F' -> "float";
            case 'D' -> "double";
            case 'L' -> "void*";
            default -> "int32_t";
        };
    }

    private char collapseKind(Type type) {
        return switch (type.getSort()) {
            case Type.VOID -> 'V';
            case Type.BOOLEAN -> 'Z';
            case Type.BYTE -> 'B';
            case Type.SHORT -> 'S';
            case Type.CHAR -> 'C';
            case Type.INT -> 'I';
            case Type.LONG -> 'J';
            case Type.FLOAT -> 'F';
            case Type.DOUBLE -> 'D';
            default -> 'L';
        };
    }

    private boolean isWideKind(char kind) {
        return kind == 'J' || kind == 'D';
    }

    private boolean isFloatingKind(char kind) {
        return kind == 'F' || kind == 'D';
    }

    private String renderParam(CVariable variable) {
        return rawFunctionParamType(variable) + " " + variable.name();
    }

    public record GeneratedSource(String fileName, String content) {}

    public record SignatureInfo(int id, String key) {}

    private enum ArgLocationKind {
        GP_REG,
        FP_REG,
        STACK,
        INTERPRETER_STACK
    }

    private record ArgLocation(ArgLocationKind kind, int index) {}

    private record CallingLayout(List<ArgLocation> locations, int stackSlotCount, int gpRegisterCount) {}

    private record DispatchPlan(
        List<Character> logicalArgKinds,
        CallingLayout sourceLayout,
        CallingLayout destLayout,
        int frameBytes,
        int entrySaveOffset,
        int retSaveOffset,
        int gpSpillBaseOffset
    ) {}

    public record SignatureShape(int id, String key, char returnKind, List<Character> argKinds) {}

    public record SignaturePlan(
        List<SignatureShape> signatures,
        List<Integer> bindingSignatureIds,
        int maxArgCount,
        Map<String, Integer> signatureIdsByKey
    ) {}

    public record MethodRef(String owner, String name, String desc, boolean isStatic) {}

    public record FieldRef(String owner, String name, String desc, boolean isStatic) {}

    public record StringRef(String cacheVar, String value) {}

    public enum LdcKind {
        STRING("NEKO_LDC_KIND_STRING"),
        CLASS("NEKO_LDC_KIND_CLASS"),
        METHOD_HANDLE("NEKO_LDC_KIND_METHOD_HANDLE"),
        METHOD_TYPE("NEKO_LDC_KIND_METHOD_TYPE");

        private final String constant;

        LdcKind(String constant) {
            this.constant = constant;
        }

        public String constant() {
            return constant;
        }
    }

    public record Utf8BlobRef(int methodId, int siteIndex, byte[] bytes) {
        public String symbol() {
            return "g_neko_utf8_" + methodId + '_' + siteIndex;
        }
    }

    public record ManifestFieldSiteRef(
        int methodId,
        int siteIndex,
        int ownerClassIndex,
        String owner,
        String name,
        String desc,
        boolean isStatic,
        boolean isReference
    ) {
        public String arrayElementExpression() {
            return "&" + "g_neko_field_sites_" + methodId + '[' + siteIndex + ']';
        }
    }

    public record ManifestInvokeSiteRef(
        int methodId,
        int siteIndex,
        String owner,
        String name,
        String desc,
        int opcode,
        String signatureKey
    ) {}

    public record ManifestLdcSiteRef(int methodId, int siteIndex, LdcKind kind, String rawConstant, Utf8BlobRef blob) {
        public String arrayElementExpression() {
            return "&" + "g_neko_ldc_sites_" + methodId + '[' + siteIndex + ']';
        }
    }

    public record IcacheSiteRef(int ownerId, int methodId, int siteIndex, String bindingOwner, String methodKey) {
        private String symbol() {
            return "neko_icache_" + ownerId + '_' + methodId + '_' + siteIndex;
        }
    }

    public record IcacheDirectStubRef(
        int ownerId,
        int methodId,
        int siteIndex,
        NativeMethodBinding binding,
        Type[] args,
        Type returnType
    ) {
        private String symbol() {
            return "neko_icache_stub_" + ownerId + '_' + methodId + '_' + siteIndex;
        }
    }

    public record IcacheMetaRef(
        int ownerId,
        int methodId,
        int siteIndex,
        String name,
        String desc,
        boolean isInterface,
        String translatedClassSlot,
        String translatedStubSymbol
    ) {
        private String symbol() {
            return "neko_icache_meta_" + ownerId + '_' + methodId + '_' + siteIndex;
        }
    }

    public static final class OwnerResolution {
        private final Set<String> classes = new LinkedHashSet<>();
        private final Set<MethodRef> methods = new LinkedHashSet<>();
        private final Set<FieldRef> fields = new LinkedHashSet<>();
        private final Set<StringRef> strings = new LinkedHashSet<>();

        public Set<String> classes() {
            return classes;
        }

        public Set<MethodRef> methods() {
            return methods;
        }

        public Set<FieldRef> fields() {
            return fields;
        }

        public Set<StringRef> strings() {
            return strings;
        }
    }
}
