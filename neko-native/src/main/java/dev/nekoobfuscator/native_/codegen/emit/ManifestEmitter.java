package dev.nekoobfuscator.native_.codegen.emit;

import dev.nekoobfuscator.native_.codegen.CCodeGenerator;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.LdcKind;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.ManifestFieldSiteRef;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.ManifestInvokeSiteRef;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.ManifestLdcSiteRef;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.SignaturePlan;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.SignatureShape;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.Utf8BlobRef;
import dev.nekoobfuscator.native_.translator.NativeTranslator.NativeMethodBinding;
import org.objectweb.asm.Opcodes;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ManifestEmitter {
    private final CCodeGenerator generator;
    private final CEmissionContext ctx;

    public ManifestEmitter(CCodeGenerator generator, CEmissionContext ctx) {
        this.generator = generator;
        this.ctx = ctx;
    }

    public int registerManifestMethod(String bindingKey) {
        return ctx.manifestMethodIndex().computeIfAbsent(bindingKey, ignored -> ctx.manifestMethodIndex().size());
    }

    public String reserveManifestFieldSite(String bindingKey, String bindingOwner, String owner, String name, String desc, boolean isStatic) {
        int methodId = registerManifestMethod(bindingKey);
        generator.registerOwnerClassReference(bindingOwner, owner);
        List<ManifestFieldSiteRef> sites = ctx.manifestFieldSites().computeIfAbsent(bindingKey, ignored -> new ArrayList<>());
        int siteIndex = sites.size();
        ManifestFieldSiteRef site = new ManifestFieldSiteRef(
            methodId,
            siteIndex,
            ctx.classSlotIndex().get(owner),
            owner,
            name,
            desc,
            isStatic,
            !isPrimitiveFieldDescriptor(desc)
        );
        sites.add(site);
        return site.arrayElementExpression();
    }

    public String reserveManifestInvokeSite(String bindingKey, String bindingOwner, String owner, String name, String desc, int opcode) {
        int methodId = registerManifestMethod(bindingKey);
        generator.registerOwnerClassReference(bindingOwner, owner);
        List<ManifestInvokeSiteRef> sites = ctx.manifestInvokeSites().computeIfAbsent(bindingKey, ignored -> new ArrayList<>());
        int siteIndex = sites.size();
        sites.add(new ManifestInvokeSiteRef(
            methodId,
            siteIndex,
            owner,
            name,
            desc,
            opcode,
            ctx.signatureKey(desc)
        ));
        return "&" + manifestInvokeSiteArrayName(methodId) + '[' + siteIndex + ']';
    }

    public String reserveManifestStringLdcSite(String bindingKey, String bindingOwner, String literal) {
        int methodId = registerManifestMethod(bindingKey);
        generator.registerBindingOwner(bindingOwner);
        List<ManifestLdcSiteRef> sites = ctx.manifestLdcSites().computeIfAbsent(bindingKey, ignored -> new ArrayList<>());
        int siteIndex = sites.size();
        ManifestLdcSiteRef site = new ManifestLdcSiteRef(
            methodId,
            siteIndex,
            -1,
            null,
            LdcKind.STRING,
            literal,
            new Utf8BlobRef(methodId, siteIndex, modifiedUtf8Bytes(literal))
        );
        sites.add(site);
        return site.arrayElementExpression();
    }

    public String reserveManifestClassLdcSite(String bindingKey, String bindingOwner, String descriptor) {
        int methodId = registerManifestMethod(bindingKey);
        generator.registerBindingOwner(bindingOwner);
        List<ManifestLdcSiteRef> sites = ctx.manifestLdcSites().computeIfAbsent(bindingKey, ignored -> new ArrayList<>());
        int siteIndex = sites.size();
        ManifestLdcSiteRef site = new ManifestLdcSiteRef(
            methodId,
            siteIndex,
            ctx.classSlotIndex().get(bindingOwner),
            bindingOwner,
            LdcKind.CLASS,
            descriptor,
            new Utf8BlobRef(methodId, siteIndex, descriptor.getBytes(StandardCharsets.UTF_8))
        );
        sites.add(site);
        return site.arrayElementExpression();
    }

    public String renderManifestSupport(List<NativeMethodBinding> bindings, SignaturePlan signaturePlan) {
        StringBuilder sb = new StringBuilder();
        int stringInternSlotCount = totalLdcKindCount(LdcKind.STRING);
        int stringInternBucketCount = Math.max(1, stringInternSlotCount * 2);
        sb.append("#define NEKO_MANIFEST_FLAG_STATIC 0x01u\n");
        sb.append("#define NEKO_MANIFEST_FLAG_LEAF_ONLY 0x02u\n");
        sb.append("#define NEKO_PATCH_STATE_NONE 0u\n");
        sb.append("#define NEKO_PATCH_STATE_APPLIED 1u\n");
        sb.append("#define NEKO_PATCH_STATE_FAILED 2u\n");
        sb.append("#define NEKO_FIELD_SITE_UNRESOLVED ((ptrdiff_t)-1)\n");
        sb.append("#define NEKO_FIELD_SITE_FAILED ((ptrdiff_t)-2)\n");
        sb.append("#define NEKO_LDC_KIND_STRING 1u\n");
        sb.append("#define NEKO_LDC_KIND_CLASS 2u\n");
        sb.append("#define NEKO_LDC_KIND_METHOD_HANDLE 3u\n");
        sb.append("#define NEKO_LDC_KIND_METHOD_TYPE 4u\n");
        sb.append("#define NEKO_MANIFEST_STORAGE_COUNT ").append(Math.max(bindings.size(), 1)).append("u\n\n");
        sb.append("#define NEKO_SIGNATURE_STORAGE_COUNT ").append(Math.max(signaturePlan.signatures().size(), 1)).append("u\n");
        sb.append("#define NEKO_SIGNATURE_MAX_ARGS ").append(Math.max(signaturePlan.maxArgCount(), 1)).append("u\n\n");
        sb.append("typedef struct {\n");
        sb.append("    uint8_t return_kind;\n");
        sb.append("    uint8_t arg_count;\n");
        sb.append("    uint8_t arg_kinds[NEKO_SIGNATURE_MAX_ARGS];\n");
        sb.append("} NekoSignatureDescriptor;\n\n");
        sb.append("typedef struct NekoManifestFieldSite {\n");
        sb.append("    uint32_t owner_class_index;\n");
        sb.append("    const char* owner_internal;\n");
        sb.append("    const char* field_name;\n");
        sb.append("    const char* field_desc;\n");
        sb.append("    jclass *owner_class_slot;\n");
        sb.append("    uint8_t is_static;\n");
        sb.append("    uint8_t is_reference;\n");
        sb.append("    uint8_t is_volatile;\n");
        sb.append("    uint8_t _pad0;\n");
        sb.append("    void* cached_klass;\n");
        sb.append("    jobject static_base_global_ref;\n");
        sb.append("    void *volatile *static_base_slot;\n");
        sb.append("    ptrdiff_t field_offset_cookie;\n");
        sb.append("    ptrdiff_t resolved_offset;\n");
        sb.append("} NekoManifestFieldSite;\n\n");
        sb.append("typedef struct NekoManifestInvokeSite {\n");
        sb.append("    const char* owner_internal;\n");
        sb.append("    const char* method_name;\n");
        sb.append("    const char* method_desc;\n");
        sb.append("    uint8_t opcode;\n");
        sb.append("    uint8_t is_interface;\n");
        sb.append("    uint16_t signature_id;\n");
        sb.append("    void* resolved_method;\n");
        sb.append("} NekoManifestInvokeSite;\n\n");
        sb.append("typedef struct NekoManifestLdcSite {\n");
        sb.append("    uint32_t site_id;\n");
        sb.append("    uint32_t owner_class_index;\n");
        sb.append("    uint8_t kind;\n");
        sb.append("    uint8_t _pad0;\n");
        sb.append("    uint16_t _pad1;\n");
        sb.append("    const uint8_t* raw_constant_utf8;\n");
        sb.append("    size_t raw_constant_utf8_len;\n");
        sb.append("    jclass *owner_class_slot;\n");
        sb.append("    void* cached_klass;\n");
        sb.append("    void* resolved_cache_handle;\n");
        sb.append("} NekoManifestLdcSite;\n\n");
        sb.append("typedef struct {\n");
        sb.append("    const char* owner_internal;\n");
        sb.append("    const char* method_name;\n");
        sb.append("    const char* method_desc;\n");
        sb.append("    uint32_t owner_hash;\n");
        sb.append("    uint32_t name_desc_hash;\n");
        sb.append("    uint16_t flags;\n");
        sb.append("    uint16_t reserved;\n");
        sb.append("    uint32_t signature_id;\n");
        sb.append("    void* impl_fn;\n");
        sb.append("    void* method_star;\n");
        sb.append("    NekoManifestFieldSite* field_sites;\n");
        sb.append("    uint32_t field_site_count;\n");
        sb.append("    uint32_t _pad_field_sites;\n");
        sb.append("    NekoManifestLdcSite* ldc_sites;\n");
        sb.append("    uint32_t ldc_site_count;\n");
        sb.append("    uint32_t _pad_ldc_sites;\n");
        sb.append("} NekoManifestMethod;\n\n");

        sb.append("_Static_assert(sizeof(NekoManifestMethod) == ").append(CCodeGenerator.MANIFEST_ENTRY_SIZE).append(", \"unexpected NekoManifestMethod size\");\n");
        sb.append("_Static_assert(offsetof(NekoManifestMethod, flags) == ").append(CCodeGenerator.MANIFEST_FLAGS_OFFSET).append(", \"unexpected NekoManifestMethod::flags offset\");\n");
        sb.append("_Static_assert(offsetof(NekoManifestMethod, signature_id) == ").append(CCodeGenerator.MANIFEST_SIGNATURE_ID_OFFSET).append(", \"unexpected NekoManifestMethod::signature_id offset\");\n");
        sb.append("_Static_assert(offsetof(NekoManifestMethod, impl_fn) == ").append(CCodeGenerator.MANIFEST_IMPL_FN_OFFSET).append(", \"unexpected NekoManifestMethod::impl_fn offset\");\n");
        sb.append("_Static_assert(offsetof(NekoManifestMethod, method_star) == ").append(CCodeGenerator.MANIFEST_METHOD_STAR_OFFSET).append(", \"unexpected NekoManifestMethod::method_star offset\");\n\n");

        sb.append("static const NekoSignatureDescriptor g_neko_signature_descriptors[NEKO_SIGNATURE_STORAGE_COUNT] = {\n");
        if (signaturePlan.signatures().isEmpty()) {
            sb.append("    { 'V', 0u, { 0u } }\n");
        } else {
            for (int i = 0; i < signaturePlan.signatures().size(); i++) {
                SignatureShape signature = signaturePlan.signatures().get(i);
                sb.append("    { '").append(signature.returnKind()).append("', ").append(signature.argKinds().size()).append("u, {");
                for (int argIndex = 0; argIndex < Math.max(signaturePlan.maxArgCount(), 1); argIndex++) {
                    if (argIndex > 0) {
                        sb.append(", ");
                    }
                    if (argIndex < signature.argKinds().size()) {
                        sb.append('\'').append(signature.argKinds().get(argIndex)).append("'");
                    } else {
                        sb.append("0u");
                    }
                }
                sb.append(" } }");
                sb.append(i + 1 == signaturePlan.signatures().size() ? '\n' : ',').append(i + 1 == signaturePlan.signatures().size() ? "" : "\n");
            }
        }
        sb.append("};\n");
        sb.append("const uint32_t g_neko_signature_descriptor_count = ").append(signaturePlan.signatures().size()).append("u;\n\n");

        for (SignatureShape signature : signaturePlan.signatures()) {
            sb.append("extern void neko_sig_").append(signature.id()).append("_i2i(void);\n");
            sb.append("extern void neko_sig_").append(signature.id()).append("_c2i(void);\n");
        }
        sb.append("static int neko_patch_method(const NekoManifestMethod *entry, void *method_star);\n");
        sb.append("static void neko_patch_discovered_methods(void);\n");
        sb.append("static void neko_resolve_discovered_invoke_sites(const char *owner_internal, const char *name, const char *desc, void *method_star);\n");
        if (!signaturePlan.signatures().isEmpty()) {
            sb.append('\n');
        }

        for (NativeMethodBinding binding : bindings) {
            String bindingKey = bindingKey(binding.ownerInternalName(), binding.methodName(), binding.descriptor());
            sb.append(renderManifestFieldSiteArray(bindingKey));
            sb.append(renderManifestInvokeSiteArray(bindingKey, signaturePlan));
            sb.append(renderManifestLdcSiteArray(bindingKey));
        }
        if (!bindings.isEmpty()) {
            sb.append('\n');
        }

        sb.append(renderManifestOwnerArray());
        sb.append(renderManifestInvokeSiteIndexArray());

        sb.append("__attribute__((visibility(\"hidden\"))) const NekoManifestMethod g_neko_manifest_methods[NEKO_MANIFEST_STORAGE_COUNT] = {\n");
        if (bindings.isEmpty()) {
            sb.append("    { NULL, NULL, NULL, 0u, 0u, 0u, 0u, 0u, NULL, NULL, NULL, 0u, 0u, NULL, 0u, 0u }\n");
        } else {
            for (int i = 0; i < bindings.size(); i++) {
                NativeMethodBinding binding = bindings.get(i);
                int signatureId = signaturePlan.bindingSignatureIds().get(i);
                String bindingKey = bindingKey(binding.ownerInternalName(), binding.methodName(), binding.descriptor());
                List<ManifestFieldSiteRef> fieldSites = ctx.manifestFieldSites().getOrDefault(bindingKey, List.of());
                List<ManifestLdcSiteRef> ldcSites = ctx.manifestLdcSites().getOrDefault(bindingKey, List.of());
                String fieldSitesExpr = fieldSites.isEmpty() ? "NULL" : manifestFieldSiteArrayName(registerManifestMethod(bindingKey));
                String ldcSitesExpr = ldcSites.isEmpty() ? "NULL" : manifestLdcSiteArrayName(registerManifestMethod(bindingKey));
                sb.append("    { \"").append(c(binding.ownerInternalName())).append("\", \"")
                    .append(c(binding.methodName())).append("\", \"")
                    .append(c(binding.descriptor())).append("\", ")
                    .append(renderUint32Literal(manifestHash(binding.ownerInternalName()))).append(", ")
                    .append(renderUint32Literal(manifestHash(binding.methodName(), binding.descriptor()))).append(", ")
                    .append(renderUint16Literal(manifestFlags(binding))).append(", 0u, ").append(signatureId).append("u, (void*)&")
                    .append(binding.cFunctionName()).append(", NULL, ")
                    .append(fieldSitesExpr).append(", ").append(fieldSites.size()).append("u, 0u, ")
                    .append(ldcSitesExpr).append(", ").append(ldcSites.size()).append("u, 0u }");
                sb.append(i + 1 == bindings.size() ? '\n' : ',').append(i + 1 == bindings.size() ? "" : "\n");
            }
        }
        sb.append("};\n");
        sb.append("__attribute__((visibility(\"hidden\"))) const uint32_t g_neko_manifest_method_count = ").append(bindings.size()).append("u;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) void* g_neko_manifest_method_stars[NEKO_MANIFEST_STORAGE_COUNT] = { NULL };\n\n");
        sb.append("static uint8_t g_neko_manifest_patch_states[NEKO_MANIFEST_STORAGE_COUNT] = { 0u };\n");
        sb.append("static uint32_t g_neko_manifest_patch_count = 0u;\n\n");
        sb.append("static const uint32_t g_neko_manifest_owner_count = ").append(ctx.manifestOwnerInternals().size()).append("u;\n");
        sb.append("static const uint32_t g_neko_manifest_field_site_count = ").append(totalFieldSiteCount()).append("u;\n");
        sb.append("static const uint32_t g_neko_manifest_invoke_site_count = ").append(totalInvokeSiteCount()).append("u;\n");
        sb.append("static const uint32_t g_neko_manifest_ldc_string_site_count = ").append(totalLdcKindCount(LdcKind.STRING)).append("u;\n");
        sb.append("static const uint32_t g_neko_manifest_ldc_class_site_count = ").append(totalLdcKindCount(LdcKind.CLASS)).append("u;\n\n");

        sb.append("static void* const g_neko_signature_i2i_stubs[NEKO_SIGNATURE_STORAGE_COUNT] = {\n");
        if (signaturePlan.signatures().isEmpty()) {
            sb.append("    NULL\n");
        } else {
            for (int i = 0; i < signaturePlan.signatures().size(); i++) {
                SignatureShape signature = signaturePlan.signatures().get(i);
                sb.append("    (void*)&neko_sig_").append(signature.id()).append("_i2i");
                sb.append(i + 1 == signaturePlan.signatures().size() ? '\n' : ',').append(i + 1 == signaturePlan.signatures().size() ? "" : "\n");
            }
        }
        sb.append("};\n");
        sb.append("static void* const g_neko_signature_c2i_stubs[NEKO_SIGNATURE_STORAGE_COUNT] = {\n");
        if (signaturePlan.signatures().isEmpty()) {
            sb.append("    NULL\n");
        } else {
            for (int i = 0; i < signaturePlan.signatures().size(); i++) {
                SignatureShape signature = signaturePlan.signatures().get(i);
                sb.append("    (void*)&neko_sig_").append(signature.id()).append("_c2i");
                sb.append(i + 1 == signaturePlan.signatures().size() ? '\n' : ',').append(i + 1 == signaturePlan.signatures().size() ? "" : "\n");
            }
        }
        sb.append("};\n\n");
        sb.append("#define NEKO_STRING_INTERN_SLOT_COUNT ").append(stringInternSlotCount).append("u\n");
        sb.append("#define NEKO_STRING_INTERN_BUCKET_COUNT ").append(stringInternBucketCount).append("u\n\n");
        sb.append("typedef struct NekoStringInternEntry {\n");
        sb.append("    uint32_t coder;            /* 0 LATIN1 / 1 UTF16 (JDK 9+); 1 synthetic UTF16-BE for JDK 8 */\n");
        sb.append("    uint32_t char_length;      /* logical Java char count */\n");
        sb.append("    uint32_t payload_length;   /* bytes backing the key */\n");
        sb.append("    uint32_t slot_index;       /* index into root Object[] */\n");
        sb.append("    const uint8_t* payload;    /* pointer to MUTF-8-derived key bytes or String byte[] contents */\n");
        sb.append("    struct NekoStringInternEntry* next;\n");
        sb.append("} NekoStringInternEntry;\n\n");
        sb.append("static NekoStringInternEntry* g_neko_string_intern_buckets[NEKO_STRING_INTERN_BUCKET_COUNT];\n");
        sb.append("static NekoStringInternEntry g_neko_string_intern_entries[NEKO_STRING_INTERN_SLOT_COUNT];\n");
        sb.append("static uint32_t g_neko_string_intern_filled = 0;\n\n");
        return sb.toString();
    }

    private String renderManifestFieldSiteArray(String bindingKey) {
        List<ManifestFieldSiteRef> sites = ctx.manifestFieldSites().getOrDefault(bindingKey, List.of());
        if (sites.isEmpty()) {
            return "";
        }
        int methodId = registerManifestMethod(bindingKey);
        StringBuilder sb = new StringBuilder();
        sb.append("static NekoManifestFieldSite ").append(manifestFieldSiteArrayName(methodId)).append('[').append(sites.size()).append("] = {\n");
        for (int i = 0; i < sites.size(); i++) {
            ManifestFieldSiteRef site = sites.get(i);
            sb.append("    { ")
                .append(site.ownerClassIndex()).append("u, \"").append(c(site.owner())).append("\", \"")
                .append(c(site.name())).append("\", \"").append(c(site.desc())).append("\", &")
                .append(generator.classSlotName(site.owner())).append(", ")
                .append(site.isStatic() ? "1u" : "0u").append(", ")
                .append(site.isReference() ? "1u" : "0u").append(", 0u, 0u, NULL, NULL, NULL, -1, NEKO_FIELD_SITE_UNRESOLVED }");
            sb.append(i + 1 == sites.size() ? '\n' : ',').append(i + 1 == sites.size() ? "" : "\n");
        }
        sb.append("};\n");
        return sb.toString();
    }

    private String renderManifestInvokeSiteArray(String bindingKey, SignaturePlan signaturePlan) {
        List<ManifestInvokeSiteRef> sites = ctx.manifestInvokeSites().getOrDefault(bindingKey, List.of());
        if (sites.isEmpty()) {
            return "";
        }
        int methodId = registerManifestMethod(bindingKey);
        StringBuilder sb = new StringBuilder();
        sb.append("static NekoManifestInvokeSite ").append(manifestInvokeSiteArrayName(methodId)).append('[').append(sites.size()).append("] = {\n");
        for (int i = 0; i < sites.size(); i++) {
            ManifestInvokeSiteRef site = sites.get(i);
            int signatureId = signaturePlan.signatureIdsByKey().get(site.signatureKey());
            sb.append("    { \"").append(c(site.owner())).append("\", \"")
                .append(c(site.name())).append("\", \"").append(c(site.desc())).append("\", ")
                .append(site.opcode()).append("u, ")
                .append(site.opcode() == Opcodes.INVOKEINTERFACE ? "1u" : "0u").append(", ")
                .append(signatureId).append("u, NULL }");
            sb.append(i + 1 == sites.size() ? '\n' : ',').append(i + 1 == sites.size() ? "" : "\n");
        }
        sb.append("};\n");
        return sb.toString();
    }

    private String renderManifestLdcSiteArray(String bindingKey) {
        List<ManifestLdcSiteRef> sites = ctx.manifestLdcSites().getOrDefault(bindingKey, List.of());
        if (sites.isEmpty()) {
            return "";
        }
        int methodId = registerManifestMethod(bindingKey);
        StringBuilder sb = new StringBuilder();
        for (ManifestLdcSiteRef site : sites) {
            sb.append(renderUtf8Blob(site.blob()));
        }
        sb.append("static NekoManifestLdcSite ").append(manifestLdcSiteArrayName(methodId)).append('[').append(sites.size()).append("] = {\n");
        for (int i = 0; i < sites.size(); i++) {
            ManifestLdcSiteRef site = sites.get(i);
            String ownerSlotExpr = site.kind() == LdcKind.CLASS
                ? "&" + generator.classSlotName(site.ownerInternal())
                : "NULL";
            sb.append("    { ").append(site.siteIndex()).append("u, ")
                .append(site.ownerClassIndex() >= 0 ? site.ownerClassIndex() + "u" : "0u")
                .append(", ").append(site.kind().constant()).append(", 0u, 0u, ")
                .append(site.blob().symbol()).append(", ").append(site.blob().bytes().length).append("u, ")
                .append(ownerSlotExpr).append(", NULL, NULL }");
            sb.append(i + 1 == sites.size() ? '\n' : ',').append(i + 1 == sites.size() ? "" : "\n");
        }
        sb.append("};\n");
        return sb.toString();
    }

    private String renderUtf8Blob(Utf8BlobRef blob) {
        StringBuilder sb = new StringBuilder();
        sb.append("static const uint8_t ").append(blob.symbol()).append("[] = {");
        byte[] bytes = blob.bytes();
        if (bytes.length == 0) {
            sb.append("0x00");
        } else {
            for (int i = 0; i < bytes.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(String.format("0x%02X", bytes[i] & 0xFF));
            }
        }
        sb.append("};\n");
        return sb.toString();
    }

    private String manifestFieldSiteArrayName(int methodId) {
        return "g_neko_field_sites_" + methodId;
    }

    private String manifestInvokeSiteArrayName(int methodId) {
        return "g_neko_invoke_sites_" + methodId;
    }

    private String manifestLdcSiteArrayName(int methodId) {
        return "g_neko_ldc_sites_" + methodId;
    }

    private String renderManifestOwnerArray() {
        StringBuilder sb = new StringBuilder();
        int count = Math.max(ctx.manifestOwnerInternals().size(), 1);
        sb.append("static const char* const g_neko_manifest_owners[").append(count).append("] = {\n");
        if (ctx.manifestOwnerInternals().isEmpty()) {
            sb.append("    NULL\n");
        } else {
            int index = 0;
            for (String owner : ctx.manifestOwnerInternals()) {
                sb.append("    \"").append(c(owner)).append("\"");
                sb.append(++index == ctx.manifestOwnerInternals().size() ? '\n' : ',').append(index == ctx.manifestOwnerInternals().size() ? "" : "\n");
            }
        }
        sb.append("};\n");
        return sb.toString();
    }

    private String renderManifestInvokeSiteIndexArray() {
        int totalSiteCount = totalInvokeSiteCount();
        StringBuilder sb = new StringBuilder();
        sb.append("static NekoManifestInvokeSite* const g_neko_manifest_invoke_sites[")
            .append(Math.max(totalSiteCount, 1))
            .append("] = {\n");
        if (totalSiteCount == 0) {
            sb.append("    NULL\n");
        } else {
            int emitted = 0;
            for (List<ManifestInvokeSiteRef> sites : ctx.manifestInvokeSites().values()) {
                for (ManifestInvokeSiteRef site : sites) {
                    sb.append("    &").append(manifestInvokeSiteArrayName(site.methodId())).append('[').append(site.siteIndex()).append(']');
                    sb.append(++emitted == totalSiteCount ? '\n' : ',').append(emitted == totalSiteCount ? "" : "\n");
                }
            }
        }
        sb.append("};\n\n");
        return sb.toString();
    }

    private int totalFieldSiteCount() {
        return ctx.manifestFieldSites().values().stream().mapToInt(List::size).sum();
    }

    private int totalInvokeSiteCount() {
        return ctx.manifestInvokeSites().values().stream().mapToInt(List::size).sum();
    }

    private int totalLdcKindCount(LdcKind kind) {
        return ctx.manifestLdcSites().values().stream()
            .flatMap(List::stream)
            .mapToInt(site -> site.kind() == kind ? 1 : 0)
            .sum();
    }

    private byte[] modifiedUtf8Bytes(String value) {
        if (value == null || value.isEmpty()) {
            return new byte[0];
        }
        byte[] buffer = new byte[value.length() * 3];
        int index = 0;
        for (int i = 0; i < value.length(); i++) {
            int ch = value.charAt(i);
            if (ch >= 0x0001 && ch <= 0x007F) {
                buffer[index++] = (byte) ch;
            } else if (ch <= 0x07FF) {
                buffer[index++] = (byte) (0xC0 | ((ch >> 6) & 0x1F));
                buffer[index++] = (byte) (0x80 | (ch & 0x3F));
            } else {
                buffer[index++] = (byte) (0xE0 | ((ch >> 12) & 0x0F));
                buffer[index++] = (byte) (0x80 | ((ch >> 6) & 0x3F));
                buffer[index++] = (byte) (0x80 | (ch & 0x3F));
            }
        }
        byte[] out = new byte[index];
        System.arraycopy(buffer, 0, out, 0, index);
        return out;
    }

    private int manifestFlags(NativeMethodBinding binding) {
        int flags = 0;
        if (binding.isStatic()) {
            flags |= 0x01;
        }
        if (binding.directCallSafe()) {
            flags |= 0x02;
        }
        return flags;
    }

    private int manifestHash(String value) {
        long hash = 0x811C9DC5L;
        hash = manifestHashUpdate(hash, value);
        return (int) hash;
    }

    private int manifestHash(String first, String second) {
        long hash = 0x811C9DC5L;
        hash = manifestHashUpdate(hash, first);
        hash = manifestHashByte(hash, 0);
        hash = manifestHashUpdate(hash, second);
        return (int) hash;
    }

    private long manifestHashUpdate(long hash, String value) {
        if (value == null) {
            return hash;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (byte current : bytes) {
            hash = manifestHashByte(hash, current & 0xFF);
        }
        return hash;
    }

    private long manifestHashByte(long hash, int value) {
        hash ^= value & 0xFFL;
        return (hash * 0x01000193L) & 0xFFFF_FFFFL;
    }

    private String renderUint32Literal(int value) {
        return String.format("0x%08Xu", Integer.toUnsignedLong(value));
    }

    private String renderUint16Literal(int value) {
        return String.format("0x%04Xu", value & 0xFFFF);
    }

    private boolean isPrimitiveFieldDescriptor(String desc) {
        return desc != null && desc.length() == 1 && "ZBCSIJFD".indexOf(desc.charAt(0)) >= 0;
    }

    private String bindingKey(String owner, String name, String desc) {
        return owner + '#' + name + desc;
    }

    private String c(String value) {
        return value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("\b", "\\b")
            .replace("\f", "\\f");
    }
}
