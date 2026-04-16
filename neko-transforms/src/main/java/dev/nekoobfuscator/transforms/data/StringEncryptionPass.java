package dev.nekoobfuscator.transforms.data;

import dev.nekoobfuscator.api.transform.*;
import dev.nekoobfuscator.core.ir.l1.*;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

public final class StringEncryptionPass implements TransformPass {
    private static final String FLOW_KEY_VALUES_KEY = "controlFlowFlattening.flowKeys";

    private static final String BOOTSTRAP_CLASS = "dev/nekoobfuscator/runtime/NekoBootstrap";
    private static final String BOOTSTRAP_METHOD = "bsmString";
    // BSM args: fieldIdx, methodNameHash, methodDescHash, insnSalt, flowMode
    private static final String BOOTSTRAP_DESC =
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;IIIII)Ljava/lang/invoke/CallSite;";
    private static final Handle BSM_HANDLE = new Handle(
        Opcodes.H_INVOKESTATIC, BOOTSTRAP_CLASS, BOOTSTRAP_METHOD, BOOTSTRAP_DESC, false);

    // Per-class state
    private int encFieldCounter;
    private DynamicKeyDerivationEngine keyEngine;
    private long classKey;

    private static final int CLASS_METADATA_ACCESS =
        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
    private static final int INTERFACE_METADATA_ACCESS =
        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;

    @Override public String id() { return "stringEncryption"; }
    @Override public String name() { return "String Encryption"; }
    @Override public TransformPhase phase() { return TransformPhase.TRANSFORM; }
    @Override public IRLevel requiredLevel() { return IRLevel.L1; }

    @Override
    public void transformClass(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        L1Class clazz = pctx.currentL1Class();

        // Initialize key engine if needed
        keyEngine = pctx.getPassData("keyEngine");
        if (keyEngine == null) {
            keyEngine = new DynamicKeyDerivationEngine(pctx.masterSeed());
            pctx.putPassData("keyEngine", keyEngine);
        }

        classKey = keyEngine.deriveClassKey(clazz);
        encFieldCounter = countExistingEncFields(clazz);
    }

    @Override
    public void transformMethod(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        L1Method method = pctx.currentL1Method();
        L1Class clazz = pctx.currentL1Class();
        IdentityHashMap<AbstractInsnNode, Long> flowKeyValues = pctx.getPassData(FLOW_KEY_VALUES_KEY);

        if (method.isAbstract() || method.isNative() || !method.hasCode()) return;

        InsnList insns = method.instructions();
        int methodNameHash = method.name().hashCode();
        int methodDescHash = method.descriptor().hashCode();
        // Full multi-layer: methodKey = mix(mix(classKey, nameHash), descHash)
        long methodKey = DynamicKeyDerivationEngine.mix(
            DynamicKeyDerivationEngine.mix(classKey, methodNameHash), methodDescHash);

        // Collect string LDC instructions
        List<AbstractInsnNode> stringLdcs = new ArrayList<>();
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String) {
                stringLdcs.add(insn);
            }
        }

        if (stringLdcs.isEmpty()) return;

        // Process each string
        for (AbstractInsnNode insn : stringLdcs) {
            LdcInsnNode ldc = (LdcInsnNode) insn;
            String original = (String) ldc.cst;

            // Allocate field index first (needed for key derivation)
            int fieldIdx = encFieldCounter++;
            int insnSalt = pctx.random().nextInt();
            boolean useFlowKey = flowKeyValues != null && flowKeyValues.containsKey(insn);
            long flowKey = useFlowKey ? flowKeyValues.get(insn) : 0L;

            // Derive instruction-level key using fieldIdx (matches runtime bootstrap)
            long insnKey = keyEngine.deriveInsnKey(methodKey, fieldIdx, insnSalt);
            if (useFlowKey) {
                insnKey = DynamicKeyDerivationEngine.mix(insnKey, flowKey);
            }

            // Encrypt the string
            byte[] plainBytes = original.getBytes(StandardCharsets.UTF_8);
            byte[] encrypted = DynamicKeyDerivationEngine.encrypt(plainBytes, insnKey);
            String fieldName = "__e" + fieldIdx;
            FieldNode fn = new FieldNode(
                metadataAccess(clazz.asmNode()),
                fieldName, "Ljava/lang/String;", null,
                new String(encrypted, StandardCharsets.ISO_8859_1));
            clazz.asmNode().fields.add(fn);

            // Create invokedynamic instruction with full key components
            InvokeDynamicInsnNode indy = new InvokeDynamicInsnNode(
                "decrypt",                    // name (arbitrary)
                "()Ljava/lang/String;",       // descriptor: no args, returns String
                BSM_HANDLE,                   // bootstrap method
                fieldIdx,                     // bsm arg 0: encrypted field index
                methodNameHash,               // bsm arg 1: method name hash
                methodDescHash,               // bsm arg 2: method descriptor hash
                insnSalt,                     // bsm arg 3: per-instruction salt
                useFlowKey ? 1 : 0            // bsm arg 4: consume dynamic flow key
            );

            // Replace LDC with invokedynamic
            insns.set(insn, indy);
        }

        clazz.markDirty();
    }

    private int countExistingEncFields(L1Class clazz) {
        int count = 0;
        for (FieldNode fn : clazz.asmNode().fields) {
            if (fn.name.startsWith("__e")
                    && ("[B".equals(fn.desc) || "Ljava/lang/String;".equals(fn.desc))) {
                count++;
            }
        }
        return count;
    }

    private int metadataAccess(ClassNode classNode) {
        return (classNode.access & Opcodes.ACC_INTERFACE) != 0
            ? INTERFACE_METADATA_ACCESS
            : CLASS_METADATA_ACCESS;
    }
}
