package dev.nekoobfuscator.native_.codegen.emit;

import dev.nekoobfuscator.native_.codegen.CCodeGenerator;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.ArgLocation;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.ArgLocationKind;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.CallingLayout;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.DispatchPlan;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.ManifestInvokeSiteRef;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.SignaturePlan;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator.SignatureShape;
import dev.nekoobfuscator.native_.translator.NativeTranslator.NativeMethodBinding;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AssemblyStubEmitter {
    private static final int RT_CTX_BYTES = 64;
    private static final int RT_RETURN_SAVE_BYTES = 16;
    private static final int RT_I2I_SOURCE_SAVE_BYTES = 8;

    private final CEmissionContext ctx;

    public AssemblyStubEmitter(CEmissionContext ctx) {
        this.ctx = ctx;
    }

    public String renderPrototype(NativeMethodBinding binding) {
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

    public List<CCodeGenerator.GeneratedSource> generateAdditionalSources(List<NativeMethodBinding> bindings) {
        if (bindings.isEmpty()) {
            return List.of();
        }
        return List.of(new CCodeGenerator.GeneratedSource("neko_stubs.S", generateAssembly(bindings)));
    }

    public List<CCodeGenerator.SignatureInfo> signatureInfos(List<NativeMethodBinding> bindings) {
        SignaturePlan plan = buildSignaturePlan(bindings);
        List<CCodeGenerator.SignatureInfo> infos = new ArrayList<>(plan.signatures().size());
        for (SignatureShape signature : plan.signatures()) {
            infos.add(new CCodeGenerator.SignatureInfo(signature.id(), signature.key()));
        }
        return infos;
    }

    public SignaturePlan buildSignaturePlan(List<NativeMethodBinding> bindings) {
        LinkedHashMap<String, SignatureShape> signaturesByKey = new LinkedHashMap<>();
        List<Integer> bindingSignatureIds = new ArrayList<>(bindings.size());
        int maxArgCount = 0;
        for (NativeMethodBinding binding : bindings) {
            SignatureShape signature = registerSignatureShape(signaturesByKey, binding.descriptor());
            bindingSignatureIds.add(signature.id());
            maxArgCount = Math.max(maxArgCount, signature.argKinds().size());
        }
        for (List<ManifestInvokeSiteRef> sites : ctx.manifestInvokeSites().values()) {
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

    public String renderSignatureDispatchSupport(SignaturePlan signaturePlan) {
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
        String returnType = ctx.rawAbiType(signature.returnKind());
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
            params.add(ctx.rawAbiType(signature.argKinds().get(i)) + " p" + i);
            args.add("p" + i);
        }

        sb.append("typedef ").append(returnType).append(" (*").append(functionPointerType).append(")(");
        if (instance) {
            sb.append("void*");
            for (int i = 0; i < signature.argKinds().size(); i++) {
                sb.append(", ").append(ctx.rawAbiType(signature.argKinds().get(i)));
            }
        } else {
            for (int i = 0; i < signature.argKinds().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(ctx.rawAbiType(signature.argKinds().get(i)));
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
        sb.append("#        call hidden C dispatcher, preserve primitive/void returns, preserve decoded raw oop\n");
        sb.append("#        returns in rax, restore sender_sp (r13) and jump to continuation.\n");
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
        sb.append("    test WORD PTR [rax + ").append(CCodeGenerator.MANIFEST_FLAGS_OFFSET).append("], 1\n");
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
        sb.append("    test WORD PTR [rax + ").append(CCodeGenerator.MANIFEST_FLAGS_OFFSET).append("], 1\n");
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
        sb.append("    imul rax, rax, ").append(CCodeGenerator.MANIFEST_ENTRY_SIZE).append("\n");
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

    private void emitEnterVmFromI2i(StringBuilder sb, DispatchPlan plan) {
        int ctxOffset = i2iCtxOffset(plan);
        sb.append("    lea rdi, [rsp + ").append(ctxOffset).append("]\n");
        sb.append("    mov rsi, r15\n");
        sb.append("    mov rdx, r13\n");
        sb.append("    xor ecx, ecx\n");
        sb.append("    mov r8, QWORD PTR [rsp + ").append(plan.retSaveOffset()).append("]\n");
        sb.append("    xor r9d, r9d\n");
        sb.append("    call neko_rt_ctx_init\n");
        sb.append("    lea rdi, [rsp + ").append(ctxOffset).append("]\n");
        sb.append("    call neko_rt_enter_vm\n");
    }

    private void emitEnterVmFromC2i(StringBuilder sb, DispatchPlan plan) {
        int ctxOffset = c2iCtxOffset(plan);
        sb.append("    lea r10, [rsp + ").append(plan.frameBytes()).append("]\n");
        sb.append("    lea rdi, [rsp + ").append(ctxOffset).append("]\n");
        sb.append("    mov rsi, r15\n");
        sb.append("    mov rdx, r10\n");
        sb.append("    xor ecx, ecx\n");
        sb.append("    mov r8, QWORD PTR [r10]\n");
        sb.append("    xor r9d, r9d\n");
        sb.append("    call neko_rt_ctx_init\n");
        sb.append("    lea rdi, [rsp + ").append(ctxOffset).append("]\n");
        sb.append("    call neko_rt_enter_vm\n");
    }

    private void emitLeaveVmPreservingReturn(StringBuilder sb, char returnKind, int ctxOffset, int returnSaveOffset) {
        emitSaveReturnValue(sb, returnKind, returnSaveOffset);
        sb.append("    lea rdi, [rsp + ").append(ctxOffset).append("]\n");
        sb.append("    call neko_rt_leave_vm\n");
        emitRestoreReturnValue(sb, returnKind, returnSaveOffset);
    }

    private void emitSaveReturnValue(StringBuilder sb, char returnKind, int returnSaveOffset) {
        switch (returnKind) {
            case 'V' -> {
            }
            case 'F' -> sb.append("    movss DWORD PTR [rsp + ").append(returnSaveOffset).append("], xmm0\n");
            case 'D' -> sb.append("    movsd QWORD PTR [rsp + ").append(returnSaveOffset).append("], xmm0\n");
            default -> sb.append("    mov QWORD PTR [rsp + ").append(returnSaveOffset).append("], rax\n");
        }
    }

    private void emitRestoreReturnValue(StringBuilder sb, char returnKind, int returnSaveOffset) {
        switch (returnKind) {
            case 'V' -> {
            }
            case 'F' -> sb.append("    movss xmm0, DWORD PTR [rsp + ").append(returnSaveOffset).append("]\n");
            case 'D' -> sb.append("    movsd xmm0, QWORD PTR [rsp + ").append(returnSaveOffset).append("]\n");
            default -> sb.append("    mov rax, QWORD PTR [rsp + ").append(returnSaveOffset).append("]\n");
        }
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

    private void emitFpSpillsFromCompiled(StringBuilder sb, DispatchPlan plan) {
        int fpCount = countFpRegisters(plan.sourceLayout());
        int fpBase = c2iFpSpillBaseOffset(plan);
        for (int fpIndex = 0; fpIndex < fpCount; fpIndex++) {
            sb.append("    movdqu XMMWORD PTR [rsp + ").append(fpBase + (fpIndex * 16)).append("], ").append(fpRegister(fpIndex)).append("\n");
        }
    }

    private void emitRegisterLoadsFromCompiled(StringBuilder sb, DispatchPlan plan) {
        int fpBase = c2iFpSpillBaseOffset(plan);
        for (int i = 0; i < plan.logicalArgKinds().size(); i++) {
            ArgLocation dest = plan.destLayout().locations().get(i);
            ArgLocation source = plan.sourceLayout().locations().get(i);
            char kind = plan.logicalArgKinds().get(i);
            if (dest.kind() == ArgLocationKind.GP_REG) {
                if (source.kind() == ArgLocationKind.GP_REG) {
                    if (kind == 'L' || kind == 'J') {
                        sb.append("    mov ").append(dispatcherGpRegister(dest.index())).append(", QWORD PTR [rsp + ").append(plan.gpSpillBaseOffset() + (source.index() * 8)).append("]\n");
                    } else {
                        sb.append("    mov ").append(dispatcherGpRegister32(dest.index())).append(", DWORD PTR [rsp + ").append(plan.gpSpillBaseOffset() + (source.index() * 8)).append("]\n");
                    }
                }
            } else if (dest.kind() == ArgLocationKind.FP_REG && source.kind() == ArgLocationKind.FP_REG) {
                sb.append("    movdqu ").append(fpRegister(dest.index())).append(", XMMWORD PTR [rsp + ").append(fpBase + (source.index() * 16)).append("]\n");
            }
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

    private int countFpRegisters(CallingLayout layout) {
        int count = 0;
        for (ArgLocation location : layout.locations()) {
            if (location.kind() == ArgLocationKind.FP_REG) {
                count = Math.max(count, location.index() + 1);
            }
        }
        return count;
    }

    private int i2iCtxOffset(DispatchPlan plan) {
        return plan.retSaveOffset() + 8;
    }

    private int i2iSourceSaveOffset(DispatchPlan plan) {
        return i2iCtxOffset(plan) + RT_CTX_BYTES;
    }

    private int i2iReturnSaveOffset(DispatchPlan plan) {
        return i2iSourceSaveOffset(plan) + RT_I2I_SOURCE_SAVE_BYTES;
    }

    private int c2iFpSpillBaseOffset(DispatchPlan plan) {
        return plan.gpSpillBaseOffset() + (plan.sourceLayout().gpRegisterCount() * 8);
    }

    private int c2iCtxOffset(DispatchPlan plan) {
        return c2iFpSpillBaseOffset(plan) + (countFpRegisters(plan.sourceLayout()) * 16);
    }

    private int c2iReturnSaveOffset(DispatchPlan plan) {
        return c2iCtxOffset(plan) + RT_CTX_BYTES;
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
        // RESTORED to the pre-M5h logic after Oracle 11/12 escalation showed both
        // alignUp(bytes,16) and alignUp(bytes+8,16)-8 cause regressions on different
        // methods. The original 8-mod-16 logic was correct; the libjvm+0x52635f
        // movaps crash is a SEPARATE M5h reference-field path bug, not an
        // alignment regression. Investigate the M5h sfm/fsr path next.
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

    private SignatureShape registerSignatureShape(LinkedHashMap<String, SignatureShape> signaturesByKey, String descriptor) {
        Type[] argumentTypes = Type.getArgumentTypes(descriptor);
        List<Character> argKinds = new ArrayList<>(argumentTypes.length);
        String key = ctx.signatureKey(descriptor);
        char returnKind = ctx.returnKind(descriptor);
        for (Type argumentType : argumentTypes) {
            argKinds.add(ctx.collapseKind(argumentType));
        }
        return signaturesByKey.computeIfAbsent(key, ignored -> new SignatureShape(signaturesByKey.size(), key, returnKind, List.copyOf(argKinds)));
    }

    private String rawFunctionReturnType(Type type) {
        return ctx.rawAbiType(type);
    }

    private String rawFunctionParamType(Type type) {
        return ctx.rawAbiType(type);
    }

    private boolean isWideKind(char kind) {
        return kind == 'J' || kind == 'D';
    }

    private boolean isFloatingKind(char kind) {
        return kind == 'F' || kind == 'D';
    }
}
