package dev.nekoobfuscator.native_.codegen;

import dev.nekoobfuscator.core.ir.l3.CFunction;
import dev.nekoobfuscator.native_.codegen.emit.BootstrapEmitter;
import dev.nekoobfuscator.native_.codegen.emit.CEmissionContext;
import dev.nekoobfuscator.native_.codegen.emit.AssemblyStubEmitter;
import dev.nekoobfuscator.native_.codegen.emit.EntryPatchEmitter;
import dev.nekoobfuscator.native_.codegen.emit.ImplBodyEmitter;
import dev.nekoobfuscator.native_.codegen.emit.JniOnLoadEmitter;
import dev.nekoobfuscator.native_.codegen.emit.ManifestEmitter;
import dev.nekoobfuscator.native_.codegen.emit.Wave1RuntimeEmitter;
import dev.nekoobfuscator.native_.codegen.emit.Wave2FieldLdcEmitter;
import dev.nekoobfuscator.native_.codegen.emit.Wave3InvokeStaticEmitter;
import dev.nekoobfuscator.native_.codegen.emit.Wave4aRuntimeApiEmitter;
import dev.nekoobfuscator.native_.translator.NativeTranslator.NativeMethodBinding;
import org.objectweb.asm.Type;

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
    private final AssemblyStubEmitter assemblyStubEmitter;
    private final ImplBodyEmitter implBodyEmitter;
    private final JniOnLoadEmitter jniOnLoadEmitter;
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
    private final LinkedHashSet<String> manifestOwnerInternals;

    public CCodeGenerator(long masterSeed) {
        this.ctx = new CEmissionContext(masterSeed);
        this.manifestEmitter = new ManifestEmitter(this, ctx);
        this.wave1RuntimeEmitter = new Wave1RuntimeEmitter();
        this.wave2FieldLdcEmitter = new Wave2FieldLdcEmitter();
        this.wave3InvokeStaticEmitter = new Wave3InvokeStaticEmitter(this, ctx);
        this.wave4aRuntimeApiEmitter = new Wave4aRuntimeApiEmitter();
        this.entryPatchEmitter = new EntryPatchEmitter();
        this.assemblyStubEmitter = new AssemblyStubEmitter(ctx);
        this.implBodyEmitter = new ImplBodyEmitter();
        this.jniOnLoadEmitter = new JniOnLoadEmitter();
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
            sb.append(assemblyStubEmitter.renderPrototype(binding)).append(";\n");
        }
        sb.append("\n#endif\n");
        return sb.toString();
    }

    public List<GeneratedSource> generateAdditionalSources(List<NativeMethodBinding> bindings) {
        return assemblyStubEmitter.generateAdditionalSources(bindings);
    }

    public List<SignatureInfo> signatureInfos(List<NativeMethodBinding> bindings) {
        return assemblyStubEmitter.signatureInfos(bindings);
    }

    public String generateSource(List<CFunction> functions, List<NativeMethodBinding> bindings) {
        SignaturePlan signaturePlan = assemblyStubEmitter.buildSignaturePlan(bindings);
        StringBuilder body = new StringBuilder();
        for (CFunction function : functions) {
            body.append(implBodyEmitter.renderFunction(function)).append("\n");
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
        sb.append(assemblyStubEmitter.renderSignatureDispatchSupport(signaturePlan));
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
        sb.append(jniOnLoadEmitter.renderJniOnLoad());
        return sb.toString();
    }

    public record GeneratedSource(String fileName, String content) {}

    public record SignatureInfo(int id, String key) {}

    public enum ArgLocationKind {
        GP_REG,
        FP_REG,
        STACK,
        INTERPRETER_STACK
    }

    public record ArgLocation(ArgLocationKind kind, int index) {}

    public record CallingLayout(List<ArgLocation> locations, int stackSlotCount, int gpRegisterCount) {}

    public record DispatchPlan(
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
