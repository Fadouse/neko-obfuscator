package dev.nekoobfuscator.transforms.jvm.cff;

import static dev.nekoobfuscator.transforms.jvm.cff.ControlFlowFlatteningLr.*;
import static dev.nekoobfuscator.transforms.jvm.cff.ControlFlowFlatteningVerify.*;

import dev.nekoobfuscator.api.transform.IRLevel;
import dev.nekoobfuscator.api.transform.TransformContext;
import dev.nekoobfuscator.api.transform.TransformPass;
import dev.nekoobfuscator.api.transform.TransformPhase;
import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.ir.l1.L1Method;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import dev.nekoobfuscator.transforms.util.JvmObfuscationCoverage;
import dev.nekoobfuscator.transforms.util.TransformGuards;
import dev.nekoobfuscator.transforms.jvm.internal.JvmCodeSizeEstimator;
import dev.nekoobfuscator.transforms.jvm.internal.JvmPassBytecode;
import dev.nekoobfuscator.transforms.jvm.key.JvmKeyDispatchPass;
import dev.nekoobfuscator.transforms.jvm.strings.JvmStringObfuscationPass;
import dev.nekoobfuscator.transforms.jvm.constants.JvmConstantObfuscationPass;
import dev.nekoobfuscator.transforms.jvm.parameters.JvmMethodParameterObfuscationPass;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.objectweb.asm.Type;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


abstract class CffKeyTransferRewriter extends CffKeyStateEmitter {
    private static final String CLASS_INTEGRITY_TICKETED_ENTRY_SEEDS = "controlFlowFlattening.classIntegrityTicketedEntrySeeds";


    protected void installEntryKeyState(
        List<Block> blocks,
        DispatchPlan dispatchPlan,
        Map<LabelNode, CffBlockKeyState> keyStateByLabel,
        long methodSeed,
        boolean externalEntrySeed
    ) {
        Block entry = firstNonHandler(blocks);
        if (entry == null) return;
        for (IslandGroup group : dispatchPlan.groups()) {
            if (!group.blocks().contains(entry)) continue;
            keyStateByLabel.put(
                entry.label(),
                initialKeyState(
                    methodSeed,
                    entryInitSeed(group.salt(), externalEntrySeed, methodSeed)
                )
            );
            return;
        }
    }

    protected long entryInitSeed(
        long groupSalt,
        boolean externalEntrySeed,
        long methodSeed
    ) {
        long contextSeed = JvmPassBytecode.mix(
            groupSalt ^ 0x454E545259435458L,
            methodSeed
        );
        if (!externalEntrySeed) return nonZeroLong(contextSeed);
        long seed = JvmPassBytecode.mix(
            contextSeed ^ 0x45585445524B4559L,
            0x4B4559454E545259L
        );
        return nonZeroLong(seed);
    }

    protected Set<LabelNode> runtimeKeyLabels(
        PipelineContext pctx,
        MethodNode mn,
        List<Block> blocks,
        Map<LabelNode, LabelNode> aliases
    ) {
        Set<LabelNode> labels = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Block block : blocks) {
            for (
                AbstractInsnNode insn = block.label();
                insn != null && insn != block.endExclusive();
                insn = insn.getNext()
            ) {
                if (insn.getOpcode() < 0 || JvmKeyDispatchPass.isGeneratedNode(pctx, insn)) {
                    continue;
                }
                if (requiresRuntimeKeys(pctx, insn)) {
                    labels.add(block.label());
                    break;
                }
            }
        }
        for (Map.Entry<LabelNode, LabelNode> alias : aliases.entrySet()) {
            if (labels.contains(alias.getValue())) {
                labels.add(alias.getKey());
            }
        }
        return labels;
    }

    protected boolean requiresRuntimeKeys(PipelineContext pctx, AbstractInsnNode insn) {
        if (
            pctx.isTransformEnabledForClass(JvmConstantObfuscationPass.ID, pctx.currentL1Class()) &&
            isNumericConstantSite(insn)
        ) {
            return true;
        }
        if (!pctx.isTransformEnabledForClass(JvmStringObfuscationPass.ID, pctx.currentL1Class())) {
            return false;
        }
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String) {
            return true;
        }
        return insn instanceof InvokeDynamicInsnNode indy &&
            indy.bsm != null &&
            "java/lang/invoke/StringConcatFactory".equals(indy.bsm.getOwner()) &&
            "makeConcatWithConstants".equals(indy.bsm.getName()) &&
            indy.bsmArgs.length > 0 &&
            indy.bsmArgs[0] instanceof String &&
            Type.getReturnType(indy.desc).equals(Type.getType(String.class));
    }

    protected boolean isNumericConstantSite(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (insn instanceof IincInsnNode) return true;
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.DCONST_1) return true;
        if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) return true;
        return insn instanceof LdcInsnNode ldc && ldc.cst instanceof Number;
    }

    protected void rewriteKeyedCallTransfers(
        PipelineContext pctx,
        MethodNode mn,
        List<Block> blocks,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int keyTmpLocal,
        Map<LabelNode, CffBlockKeyState> keyStateByLabel,
        long salt
    ) {
        Map<AbstractInsnNode, Long> generatedKeyLoadSeeds = generatedKeyLoadTargetSeeds(pctx);
        Map<AbstractInsnNode, Block> blockByInstruction = instructionBlockMap(blocks);
        for (Block block : blocks) {
            for (
                AbstractInsnNode insn = block.label();
                insn != null && insn != block.endExclusive();
                insn = insn.getNext()
            ) {
                Long generatedTargetSeed = generatedKeyLoadSeeds.get(insn);
                if (generatedTargetSeed != null) {
                    InsnList replacement = new InsnList();
                    emitMaterializedDynamicBoundDecodedLong(
                        replacement,
                        pctx,
                        incomingRawForCanonical(generatedTargetSeed),
                        generatedTargetSeed,
                        requireBlockKey(block.label(), keyStateByLabel.get(block.label())),
                        keyLocal,
                        guardLocal,
                        pathKeyLocal,
                        blockKeyLocal,
                        salt ^ generatedTargetSeed ^ System.identityHashCode(insn),
                        insn,
                        keyTmpLocal
                    );
                    JvmKeyDispatchPass.markGenerated(pctx, replacement);
                    mn.instructions.insertBefore(insn, replacement);
                    mn.instructions.remove(insn);
                    continue;
                }
                Long targetSeed = keyedTargetSeed(pctx, insn);
                if (targetSeed == null) continue;
                AbstractInsnNode keyLoad = previousReal(insn.getPrevious());
                long rawSeed = incomingRawForCanonical(targetSeed);
                if (isGeneratedKeyLoad(pctx, keyLoad, keyLocal)) {
                    Block keyLoadBlock = nearbyBlock(keyLoad, blockByInstruction);
                    InsnList replacement = buildKeyTransferReplacement(
                        pctx,
                        keyLoadBlock == null ? block : keyLoadBlock,
                        keyStateByLabel,
                        rawSeed,
                        targetSeed,
                        keyLocal,
                        guardLocal,
                        pathKeyLocal,
                        blockKeyLocal,
                        keyTmpLocal,
                        salt,
                        keyLoad
                    );
                    mn.instructions.insertBefore(keyLoad, replacement);
                    mn.instructions.remove(keyLoad);
                    continue;
                }
                rewritePackedGeneratedKeyLoads(
                    pctx,
                    mn,
                    insn,
                    keyLocal,
                    blockByInstruction,
                    keyStateByLabel,
                    targetSeed,
                    keyLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    keyTmpLocal,
                    salt
                );
            }
        }
        rewriteDetachedGeneratedKeyLoads(
            pctx,
            mn,
            generatedKeyLoadSeeds,
            blockByInstruction,
            keyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            keyTmpLocal,
            keyStateByLabel,
            salt
        );
        rewriteReflectiveGeneratedKeyLoads(
            pctx,
            mn,
            generatedKeyLoadSeeds,
            blockByInstruction,
            keyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            keyTmpLocal,
            keyStateByLabel,
            salt
        );
        rewriteDetachedPackedKeyedCallTransfers(
            pctx,
            mn,
            blockByInstruction,
            keyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            keyTmpLocal,
            keyStateByLabel,
            salt
        );
    }

    protected void rewriteDetachedPackedKeyedCallTransfers(
        PipelineContext pctx,
        MethodNode mn,
        Map<AbstractInsnNode, Block> blockByInstruction,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int keyTmpLocal,
        Map<LabelNode, CffBlockKeyState> keyStateByLabel,
        long salt
    ) {
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            Long targetSeed = keyedTargetSeed(pctx, insn);
            if (targetSeed == null) continue;
            Block block = nearbyBlock(insn, blockByInstruction);
            if (block == null) continue;
            rewritePackedGeneratedKeyLoads(
                pctx,
                mn,
                insn,
                keyLocal,
                blockByInstruction,
                keyStateByLabel,
                targetSeed,
                keyLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                keyTmpLocal,
                salt
            );
        }
    }

    @SuppressWarnings("unchecked")
    protected Map<AbstractInsnNode, Long> generatedKeyLoadTargetSeeds(PipelineContext pctx) {
        Map<AbstractInsnNode, Long> map = pctx.getPassData(
            JvmMethodParameterObfuscationPass.CFF_KEY_LOAD_TARGET_SEED
        );
        return map == null ? Map.of() : map;
    }

    protected void rewritePackedGeneratedKeyLoads(
        PipelineContext pctx,
        MethodNode mn,
        AbstractInsnNode call,
        int keyLocal,
        Map<AbstractInsnNode, Block> blockByInstruction,
        Map<LabelNode, CffBlockKeyState> keyStateByLabel,
        long targetSeed,
        int methodKeyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int keyTmpLocal,
        long salt
    ) {
        int scanned = 0;
        for (
            AbstractInsnNode scan = call.getPrevious();
            scan != null && scanned++ < 160;
            scan = scan.getPrevious()
        ) {
            if (!isGeneratedKeyLoad(pctx, scan, keyLocal) && !isKeyLocalLoad(scan, keyLocal)) continue;
            AbstractInsnNode next = nextReal(scan.getNext());
            if (next instanceof VarInsnNode store &&
                store.getOpcode() == Opcodes.LSTORE &&
                rewriteStoredPackedGeneratedKeyLoad(
                    pctx,
                    mn,
                    call,
                    store.var,
                    blockByInstruction,
                    keyStateByLabel,
                    targetSeed,
                    methodKeyLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    keyTmpLocal,
                    salt
                )) {
                return;
            }
            if (!(next instanceof MethodInsnNode box) ||
                box.getOpcode() != Opcodes.INVOKESTATIC ||
                !"java/lang/Long".equals(box.owner) ||
                !"valueOf".equals(box.name) ||
                !"(J)Ljava/lang/Long;".equals(box.desc)) {
                continue;
            }
            InsnList replacement = buildKeyTransferReplacementForLoad(
                pctx,
                scan,
                blockByInstruction,
                keyStateByLabel,
                targetSeed,
                methodKeyLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                keyTmpLocal,
                salt
            );
            if (replacement == null) continue;
            mn.instructions.insertBefore(scan, replacement);
            mn.instructions.remove(scan);
            return;
        }
    }

    protected boolean rewriteStoredPackedGeneratedKeyLoad(
        PipelineContext pctx,
        MethodNode mn,
        AbstractInsnNode call,
        int storedLocal,
        Map<AbstractInsnNode, Block> blockByInstruction,
        Map<LabelNode, CffBlockKeyState> keyStateByLabel,
        long targetSeed,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int keyTmpLocal,
        long salt
    ) {
        int scanned = 0;
        for (
            AbstractInsnNode scan = call.getPrevious();
            scan != null && scanned++ < 160;
            scan = scan.getPrevious()
        ) {
            if (!(scan instanceof VarInsnNode load) ||
                load.getOpcode() != Opcodes.LLOAD ||
                load.var != storedLocal) {
                continue;
            }
            AbstractInsnNode next = nextReal(scan.getNext());
            if (!(next instanceof MethodInsnNode box) ||
                box.getOpcode() != Opcodes.INVOKESTATIC ||
                !"java/lang/Long".equals(box.owner) ||
                !"valueOf".equals(box.name) ||
                !"(J)Ljava/lang/Long;".equals(box.desc)) {
                continue;
            }
            InsnList replacement = buildKeyTransferReplacementForLoad(
                pctx,
                scan,
                blockByInstruction,
                keyStateByLabel,
                targetSeed,
                keyLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                keyTmpLocal,
                salt
            );
            if (replacement == null) continue;
            mn.instructions.insertBefore(scan, replacement);
            mn.instructions.remove(scan);
            return true;
        }
        return false;
    }

    protected InsnList buildKeyTransferReplacementForLoad(
        PipelineContext pctx,
        AbstractInsnNode keyLoad,
        Map<AbstractInsnNode, Block> blockByInstruction,
        Map<LabelNode, CffBlockKeyState> keyStateByLabel,
        long targetSeed,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int keyTmpLocal,
        long salt
    ) {
        Block block = nearbyBlock(keyLoad, blockByInstruction);
        if (block == null) return null;
        return buildKeyTransferReplacement(
            pctx,
            block,
            keyStateByLabel,
            incomingRawForCanonical(targetSeed),
            targetSeed,
            keyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            keyTmpLocal,
            salt,
            keyLoad
        );
    }

    protected InsnList buildKeyTransferReplacement(
        PipelineContext pctx,
        Block block,
        Map<LabelNode, CffBlockKeyState> keyStateByLabel,
        long value,
        long targetSeed,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int keyTmpLocal,
        long salt,
        AbstractInsnNode sourceInsn
    ) {
        InsnList replacement = new InsnList();
        emitMaterializedDynamicBoundDecodedLong(
            replacement,
            pctx,
            value,
            targetSeed,
            requireBlockKey(block.label(), keyStateByLabel.get(block.label())),
            keyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            salt ^ targetSeed ^ System.identityHashCode(sourceInsn),
            sourceInsn,
            keyTmpLocal
        );
        JvmKeyDispatchPass.markGenerated(pctx, replacement);
        return replacement;
    }

    protected Map<AbstractInsnNode, Block> instructionBlockMap(List<Block> blocks) {
        Map<AbstractInsnNode, Block> out = new IdentityHashMap<>();
        for (Block block : blocks) {
            for (
                AbstractInsnNode insn = block.label();
                insn != null && insn != block.endExclusive();
                insn = insn.getNext()
            ) {
                out.put(insn, block);
            }
        }
        return out;
    }

    protected void rewriteDetachedGeneratedKeyLoads(
        PipelineContext pctx,
        MethodNode mn,
        Map<AbstractInsnNode, Long> generatedKeyLoadSeeds,
        Map<AbstractInsnNode, Block> blockByInstruction,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int keyTmpLocal,
        Map<LabelNode, CffBlockKeyState> keyStateByLabel,
        long salt
    ) {
        if (generatedKeyLoadSeeds.isEmpty()) return;
        for (Map.Entry<AbstractInsnNode, Long> entry : new ArrayList<>(generatedKeyLoadSeeds.entrySet())) {
            AbstractInsnNode keyLoad = entry.getKey();
            if (!isLiveInstruction(mn, keyLoad)) continue;
            Block block = nearbyBlock(keyLoad, blockByInstruction);
            if (block == null) continue;
            long targetSeed = entry.getValue();
            InsnList replacement = new InsnList();
            emitMaterializedDynamicBoundDecodedLong(
                replacement,
                pctx,
                incomingRawForCanonical(targetSeed),
                targetSeed,
                requireBlockKey(block.label(), keyStateByLabel.get(block.label())),
                keyLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                salt ^ targetSeed ^ System.identityHashCode(keyLoad),
                keyLoad,
                keyTmpLocal
            );
            JvmKeyDispatchPass.markGenerated(pctx, replacement);
            mn.instructions.insertBefore(keyLoad, replacement);
            mn.instructions.remove(keyLoad);
        }
    }

    protected boolean isLiveInstruction(MethodNode mn, AbstractInsnNode insn) {
        return insn != null &&
            (insn == mn.instructions.getFirst() ||
                insn.getPrevious() != null ||
                insn.getNext() != null);
    }

    protected Block nearbyBlock(
        AbstractInsnNode insn,
        Map<AbstractInsnNode, Block> blockByInstruction
    ) {
        Block block = blockByInstruction.get(insn);
        if (block != null) return block;
        for (AbstractInsnNode next = nextReal(insn.getNext()); next != null; next = nextReal(next.getNext())) {
            block = blockByInstruction.get(next);
            if (block != null) return block;
        }
        for (AbstractInsnNode prev = previousReal(insn.getPrevious()); prev != null; prev = previousReal(prev.getPrevious())) {
            block = blockByInstruction.get(prev);
            if (block != null) return block;
        }
        return null;
    }

    protected void rewriteReflectiveGeneratedKeyLoads(
        PipelineContext pctx,
        MethodNode mn,
        Map<AbstractInsnNode, Long> generatedKeyLoadSeeds,
        Map<AbstractInsnNode, Block> blockByInstruction,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int keyTmpLocal,
        Map<LabelNode, CffBlockKeyState> keyStateByLabel,
        long salt
    ) {
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (generatedKeyLoadSeeds.containsKey(insn)) continue;
            if (!isGeneratedKeyLoad(pctx, insn, keyLocal)) continue;
            AbstractInsnNode next = nextReal(insn.getNext());
            if (!(next instanceof MethodInsnNode box) ||
                box.getOpcode() != Opcodes.INVOKESTATIC ||
                !"java/lang/Long".equals(box.owner) ||
                !"valueOf".equals(box.name) ||
                !"(J)Ljava/lang/Long;".equals(box.desc)) {
                continue;
            }
            Long targetSeed = reflectivePackedTargetSeed(pctx, insn);
            if (targetSeed == null) continue;
            Block block = nearbyBlock(insn, blockByInstruction);
            if (block == null) continue;
            InsnList replacement = new InsnList();
            emitMaterializedDynamicBoundDecodedLong(
                replacement,
                pctx,
                incomingRawForCanonical(targetSeed),
                targetSeed,
                requireBlockKey(block.label(), keyStateByLabel.get(block.label())),
                keyLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                salt ^ targetSeed ^ System.identityHashCode(insn),
                insn,
                keyTmpLocal
            );
            JvmKeyDispatchPass.markGenerated(pctx, replacement);
            mn.instructions.insertBefore(insn, replacement);
            AbstractInsnNode previous = insn.getPrevious();
            mn.instructions.remove(insn);
            insn = previous;
        }
    }

    protected Long reflectivePackedTargetSeed(PipelineContext pctx, AbstractInsnNode keyLoad) {
        MethodInsnNode invoke = nextReflectiveInvoke(keyLoad);
        if (invoke == null) return null;
        MethodInsnNode lookup = previousReflectiveLookup(invoke);
        if (lookup == null) return null;
        ReflectiveTarget target = reflectiveTarget(lookup);
        if (target == null) return null;
        L1Class clazz = pctx.classMap().get(target.owner());
        if (clazz == null) return null;
        L1Method matched = null;
        for (L1Method method : clazz.methods()) {
            if (!method.name().equals(target.name()) || !method.hasCode()) continue;
            Type[] args = Type.getArgumentTypes(method.descriptor());
            if (args.length != 1 || !Type.getType(Object[].class).equals(args[0])) continue;
            if (matched != null) return null;
            matched = method;
        }
        if (matched == null) return null;
        Long seed = JvmKeyDispatchPass.findMethodSeed(
            pctx,
            JvmKeyDispatchPass.coverageKey(clazz.name(), matched.name(), matched.descriptor())
        );
        return seed != null ? seed : JvmKeyDispatchPass.methodSeed(
            pctx,
            clazz,
            matched,
            matched.asmNode()
        );
    }

    protected MethodInsnNode nextReflectiveInvoke(AbstractInsnNode keyLoad) {
        int scanned = 0;
        for (AbstractInsnNode scan = keyLoad.getNext(); scan != null && scanned++ < 512; scan = scan.getNext()) {
            if (!(scan instanceof MethodInsnNode call)) continue;
            if ("java/lang/reflect/Method".equals(call.owner) &&
                "invoke".equals(call.name) &&
                "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;".equals(call.desc)) {
                return call;
            }
        }
        return null;
    }

    protected MethodInsnNode previousReflectiveLookup(MethodInsnNode invoke) {
        int scanned = 0;
        for (AbstractInsnNode scan = invoke.getPrevious(); scan != null && scanned++ < 1024; scan = scan.getPrevious()) {
            if (!(scan instanceof MethodInsnNode call)) continue;
            if ("java/lang/Class".equals(call.owner) &&
                ("getMethod".equals(call.name) || "getDeclaredMethod".equals(call.name)) &&
                "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;".equals(call.desc)) {
                return call;
            }
        }
        return null;
    }

    protected ReflectiveTarget reflectiveTarget(MethodInsnNode lookup) {
        String name = null;
        String owner = null;
        int scanned = 0;
        for (AbstractInsnNode scan = lookup.getPrevious(); scan != null && scanned++ < 256; scan = scan.getPrevious()) {
            if (!(scan instanceof LdcInsnNode ldc)) continue;
            if (name == null && ldc.cst instanceof String value) {
                name = value;
                continue;
            }
            if (name != null && owner == null && ldc.cst instanceof Type type && type.getSort() == Type.OBJECT) {
                owner = type.getInternalName();
            }
            if (name != null && owner != null) break;
        }
        return name != null && owner != null ? new ReflectiveTarget(owner, name) : null;
    }


    protected InsnList cloneInsnList(InsnList source) {
        InsnList out = new InsnList();
        Map<LabelNode, LabelNode> labels = new IdentityHashMap<>();
        for (AbstractInsnNode insn = source.getFirst(); insn != null; insn = insn.getNext()) {
            out.add(insn.clone(labels));
        }
        return out;
    }

    protected Long keyedTargetSeed(PipelineContext pctx, AbstractInsnNode insn) {
        if (insn instanceof MethodInsnNode call) {
            if ("<init>".equals(call.name)) return null;
            return keyedTargetSeed(pctx, call.owner, call.name, call.desc);
        }
        if (insn instanceof InvokeDynamicInsnNode indy) {
            for (Object arg : indy.bsmArgs) {
                if (!(arg instanceof Handle handle)) continue;
                Long seed = keyedTargetSeed(
                    pctx,
                    handle.getOwner(),
                    handle.getName(),
                    handle.getDesc()
                );
                if (seed != null) return seed;
            }
        }
        return null;
    }

    protected Long keyedTargetSeed(
        PipelineContext pctx,
        String owner,
        String name,
        String desc
    ) {
        Long packed = packedCallTargetSeed(pctx, owner, name, desc);
        if (packed != null) return packed;
        Long recorded = JvmKeyDispatchPass.findMethodSeed(
            pctx,
            JvmKeyDispatchPass.coverageKey(owner, name, desc)
        );
        if (recorded != null) return recorded;
        L1Class targetClass = pctx.classMap().get(owner);
        if (targetClass == null) return null;
        L1Method targetMethod = findAsmMethod(targetClass, name, desc);
        if (targetMethod == null) {
            return null;
        }
        if (!targetMethod.hasCode() || isVirtualFamilyMethod(targetClass, targetMethod)) {
            return JvmKeyDispatchPass.methodSeed(
                pctx,
                targetClass,
                targetMethod,
                targetMethod.asmNode()
            );
        }
        if (!usesExternalEntrySeed(pctx, targetClass, targetMethod)) return null;
        return JvmKeyDispatchPass.methodSeed(
            pctx,
            targetClass,
            targetMethod,
            targetMethod.asmNode()
        );
    }

    @SuppressWarnings("unchecked")
    protected Long packedCallTargetSeed(PipelineContext pctx, String owner, String name, String desc) {
        Map<String, Long> seeds = pctx.getPassData(
            JvmMethodParameterObfuscationPass.CFF_PACKED_CALL_TARGET_SEED
        );
        return seeds == null ? null : seeds.get(JvmKeyDispatchPass.coverageKey(owner, name, desc));
    }

    protected boolean isVirtualFamilyMethod(L1Class clazz, L1Method method) {
        MethodNode mn = method.asmNode();
        if (mn == null) return false;
        if ("<init>".equals(mn.name) || "<clinit>".equals(mn.name)) return false;
        if ((mn.access & Opcodes.ACC_STATIC) != 0) return false;
        return (mn.access & Opcodes.ACC_PRIVATE) == 0;
    }

    protected L1Method findAsmMethod(L1Class clazz, String name, String desc) {
        L1Method direct = clazz.findMethod(name, desc);
        if (direct != null) return direct;
        for (L1Method method : clazz.methods()) {
            MethodNode node = method.asmNode();
            if (node != null && name.equals(node.name) && desc.equals(node.desc)) {
                return method;
            }
        }
        return null;
    }

    protected boolean usesExternalEntrySeed(PipelineContext pctx, L1Class clazz, L1Method method) {
        if (JvmKeyDispatchPass.isReflectiveKeyedEntry(
            pctx,
            JvmKeyDispatchPass.coverageKey(clazz.name(), method.name(), method.descriptor())
        )) {
            return false;
        }
        int access = method.access();
        if ((access & Opcodes.ACC_STATIC) != 0) return true;
        if ((access & Opcodes.ACC_PRIVATE) != 0) return true;
        if ((access & Opcodes.ACC_FINAL) != 0) return true;
        return (clazz.asmNode().access & Opcodes.ACC_FINAL) != 0;
    }

    protected AbstractInsnNode previousReal(AbstractInsnNode start) {
        for (
            AbstractInsnNode insn = start;
            insn != null;
            insn = insn.getPrevious()
        ) {
            if (insn.getOpcode() >= 0) return insn;
        }
        return null;
    }

    protected boolean isGeneratedKeyLoad(
        PipelineContext pctx,
        AbstractInsnNode insn,
        int keyLocal
    ) {
        return insn instanceof VarInsnNode var &&
            var.getOpcode() == Opcodes.LLOAD &&
            var.var == keyLocal &&
            JvmKeyDispatchPass.isGeneratedNode(pctx, insn);
    }

    protected boolean isKeyLocalLoad(AbstractInsnNode insn, int keyLocal) {
        return insn instanceof VarInsnNode var &&
            var.getOpcode() == Opcodes.LLOAD &&
            var.var == keyLocal;
    }

    protected long incomingRawForCanonical(long targetSeed) {
        return JvmKeyDispatchPass.incomingRawForCanonical(targetSeed);
    }

    protected void emitMaterializedDynamicBoundDecodedLong(
        InsnList insns,
        PipelineContext pctx,
        long value,
        long targetSeed,
        CffBlockKeyState expectedKeys,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        long seed,
        AbstractInsnNode sourceInsn,
        int scratchLocal
    ) {
        CffClassKeyTable table = activeKeyTable;
        if (table == null) {
            throw new IllegalStateException("CFF key-transfer material helper requires a class key table");
        }
        classIntegrityTicketedEntrySeeds(pctx).add(targetSeed);
        long sourceSeed = keyTransferSourceSeed(sourceInsn);
        int runtimeSourceMode = keyTransferRuntimeSourceMode(sourceInsn);
        int highCursor = registerKeyTransferMaterialWord(
            table,
            (int) (value >>> 32),
            expectedKeys,
            seed ^ sourceSeed ^ 0x4B58464849474831L,
            KEY_TRANSFER_MATERIAL_HIGH_METHOD_SEED,
            runtimeSourceMode
        );
        int lowCursor = registerKeyTransferMaterialWord(
            table,
            (int) value,
            expectedKeys,
            seed ^ sourceSeed ^ 0x4B58464C4F5731L,
            KEY_TRANSFER_MATERIAL_HIGH_METHOD_SEED,
            runtimeSourceMode
        );
        boolean deferTicket = keyTransferDefersTicket(sourceInsn, runtimeSourceMode);
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockKeyLocal));
        insns.add(new FieldInsnNode(
            Opcodes.GETSTATIC,
            table.owner(),
            table.objectFieldName(),
            "[Ljava/lang/Object;"
        ));
        JvmPassBytecode.pushInt(
            insns,
            deferTicket ? highCursor | KEY_TRANSFER_CURSOR_TICKET_DEFER_FLAG : highCursor
        );
        JvmPassBytecode.pushInt(insns, lowCursor);
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            table.keyTransferMaterialHelperOwner(),
            table.keyTransferMaterialHelperName(),
            KEY_TRANSFER_MATERIAL_HELPER_DESC,
            table.keyTransferMaterialHelperInterfaceOwner()
        ));
    }

    protected void installClassIntegrityEntryTicketConsume(
        PipelineContext pctx,
        L1Class clazz,
        MethodNode mn,
        LabelNode protectedStart,
        int keyLocal,
        long methodSeed
    ) {
        String actualKey = JvmKeyDispatchPass.coverageKey(clazz.name(), mn.name, mn.desc);
        if (!JvmKeyDispatchPass.isActualKeyedEntry(pctx, actualKey)) {
            return;
        }
        if (!classIntegrityTicketedEntrySeeds(pctx).contains(methodSeed)) {
            return;
        }
        CffClassKeyTable table = activeKeyTable;
        if (table == null) {
            throw new IllegalStateException("CFF class-integrity ticket consume requires a class key table");
        }
        InsnList insns = new InsnList();
        JvmPassBytecode.pushInt(
            insns,
            JvmKeyDispatchPass.isReusableKeyedEntry(pctx, actualKey)
                ? CLASS_INTEGRITY_TICKET_OBSERVE_MODE
                : CLASS_INTEGRITY_TICKET_CONSUME_MODE
        );
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new LdcInsnNode(Type.getObjectType(clazz.name())));
        insns.add(new InsnNode(Opcodes.LCONST_0));
        insns.add(new InsnNode(Opcodes.LCONST_0));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            table.classIntegrityState().owner(),
            table.classIntegrityState().helperName(),
            CLASS_INTEGRITY_HELPER_DESC,
            table.classIntegrityState().interfaceOwner()
        ));
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LSTORE, keyLocal));
        JvmKeyDispatchPass.markGenerated(pctx, insns);
        AbstractInsnNode first = firstReal(mn);
        if (first == null) {
            throw new IllegalStateException("CFF class-integrity ticket consume requires a method prologue");
        }
        mn.instructions.insertBefore(first, insns);
        mn.maxStack = Math.max(mn.maxStack, 12);
    }

    @SuppressWarnings("unchecked")
    protected Set<Long> classIntegrityTicketedEntrySeeds(PipelineContext pctx) {
        Set<Long> seeds = pctx.getPassData(CLASS_INTEGRITY_TICKETED_ENTRY_SEEDS);
        if (seeds == null) {
            seeds = new java.util.LinkedHashSet<>();
            pctx.putPassData(CLASS_INTEGRITY_TICKETED_ENTRY_SEEDS, seeds);
        }
        return seeds;
    }

    protected long keyTransferSourceSeed(AbstractInsnNode insn) {
        AbstractInsnNode context = keyTransferSourceContext(insn);
        if (context instanceof InvokeDynamicInsnNode indy) {
            long source = isLambdaMetafactory(indy)
                ? 0x4B54584C414D4241L
                : 0x4B5458494E445931L;
            Type returnType = Type.getReturnType(indy.desc);
            if (returnType.getSort() == Type.OBJECT && isAsyncCarrierType(returnType.getInternalName())) {
                source ^= 0x4B54584153594E43L;
            }
            source ^= indy.name.hashCode();
            source ^= indy.desc.hashCode();
            if (indy.bsm != null) {
                source ^= indy.bsm.getOwner().hashCode();
                source ^= indy.bsm.getName().hashCode();
                source ^= indy.bsm.getDesc().hashCode();
            }
            return JvmPassBytecode.mix(source, 0x4B54585352434931L);
        }
        if (context instanceof MethodInsnNode call) {
            long source = 0x4B54584D45544831L;
            if (isReflectiveInvokeCall(call)) {
                source ^= 0x4B54585245464C31L;
            }
            if (isReflectiveLookupCall(call)) {
                source ^= 0x4B54584C4F4F4B31L;
            }
            if (isAsyncBoundaryCall(call)) {
                source ^= 0x4B54584153594E43L;
            }
            if (isStackTraceBoundaryCall(call)) {
                source ^= 0x4B5458535441434BL;
            }
            if (isExceptionBoundaryCall(call)) {
                source ^= 0x4B54584558435031L;
            }
            source ^= call.owner.hashCode();
            source ^= call.name.hashCode();
            source ^= call.desc.hashCode();
            source ^= call.getOpcode();
            return JvmPassBytecode.mix(source, 0x4B54585352434D31L);
        }
        return JvmPassBytecode.mix(0x4B54585352434E31L, System.identityHashCode(insn));
    }

    protected AbstractInsnNode keyTransferSourceContext(AbstractInsnNode insn) {
        if (insn instanceof MethodInsnNode || insn instanceof InvokeDynamicInsnNode) {
            return insn;
        }
        int scanned = 0;
        for (
            AbstractInsnNode scan = nextReal(insn == null ? null : insn.getNext());
            scan != null && scanned++ < 96;
            scan = nextReal(scan.getNext())
        ) {
            if (isLongBoxCall(scan)) continue;
            if (scan instanceof InvokeDynamicInsnNode || scan instanceof MethodInsnNode) {
                return scan;
            }
        }
        return insn;
    }

    protected boolean isLongBoxCall(AbstractInsnNode insn) {
        return insn instanceof MethodInsnNode call &&
            call.getOpcode() == Opcodes.INVOKESTATIC &&
            "java/lang/Long".equals(call.owner) &&
            "valueOf".equals(call.name) &&
            "(J)Ljava/lang/Long;".equals(call.desc);
    }

    protected boolean isLambdaMetafactory(InvokeDynamicInsnNode indy) {
        return indy.bsm != null &&
            "java/lang/invoke/LambdaMetafactory".equals(indy.bsm.getOwner()) &&
            ("metafactory".equals(indy.bsm.getName()) ||
                "altMetafactory".equals(indy.bsm.getName()));
    }

    protected boolean isReflectiveInvokeCall(MethodInsnNode call) {
        return "java/lang/reflect/Method".equals(call.owner) &&
            "invoke".equals(call.name) &&
            "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;".equals(call.desc);
    }

    protected boolean isReflectiveLookupCall(MethodInsnNode call) {
        return "java/lang/Class".equals(call.owner) &&
            ("getMethod".equals(call.name) || "getDeclaredMethod".equals(call.name)) &&
            "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;".equals(call.desc);
    }

    protected boolean isAsyncBoundaryCall(MethodInsnNode call) {
        if ("java/lang/Thread".equals(call.owner)) {
            return "start".equals(call.name) || "<init>".equals(call.name) || "ofVirtual".equals(call.name);
        }
        if ("java/util/concurrent/Executor".equals(call.owner)) {
            return "execute".equals(call.name);
        }
        if ("java/util/concurrent/ExecutorService".equals(call.owner)) {
            return "execute".equals(call.name)
                || "submit".equals(call.name)
                || "invokeAll".equals(call.name)
                || "invokeAny".equals(call.name);
        }
        if ("java/util/concurrent/ForkJoinPool".equals(call.owner)) {
            return "execute".equals(call.name)
                || "submit".equals(call.name)
                || "invoke".equals(call.name);
        }
        if ("java/util/concurrent/CompletableFuture".equals(call.owner)) {
            return call.name.endsWith("Async")
                || "runAsync".equals(call.name)
                || "supplyAsync".equals(call.name);
        }
        if ("java/util/concurrent/Executors".equals(call.owner)) {
            return call.name.startsWith("new")
                || "callable".equals(call.name)
                || "privilegedCallable".equals(call.name)
                || "privilegedCallableUsingCurrentClassLoader".equals(call.name);
        }
        return false;
    }

    protected boolean isStackTraceBoundaryCall(MethodInsnNode call) {
        if ("java/lang/Throwable".equals(call.owner)) {
            return "getStackTrace".equals(call.name)
                || "setStackTrace".equals(call.name)
                || "fillInStackTrace".equals(call.name)
                || "printStackTrace".equals(call.name);
        }
        if ("java/lang/Thread".equals(call.owner)) {
            return "getStackTrace".equals(call.name)
                || "getAllStackTraces".equals(call.name)
                || "dumpStack".equals(call.name);
        }
        return "java/lang/StackWalker".equals(call.owner)
            || call.owner.startsWith("java/lang/StackTraceElement");
    }

    protected boolean isExceptionBoundaryCall(MethodInsnNode call) {
        return "java/lang/Throwable".equals(call.owner)
            || call.owner.endsWith("Exception")
            || call.owner.endsWith("Error");
    }

    protected boolean isAsyncCarrierType(String internalName) {
        return "java/lang/Runnable".equals(internalName)
            || "java/util/concurrent/Callable".equals(internalName)
            || "java/util/concurrent/Future".equals(internalName)
            || "java/util/concurrent/CompletableFuture".equals(internalName)
            || "java/util/concurrent/CompletionStage".equals(internalName);
    }

    protected int keyTransferRuntimeSourceMode(AbstractInsnNode insn) {
        AbstractInsnNode context = keyTransferSourceContext(insn);
        int mode = KEY_TRANSFER_RUNTIME_SOURCE_NONE;
        if (context instanceof InvokeDynamicInsnNode indy) {
            Type returnType = Type.getReturnType(indy.desc);
            if (returnType.getSort() == Type.OBJECT && isAsyncCarrierType(returnType.getInternalName())) {
                mode |= KEY_TRANSFER_RUNTIME_SOURCE_THREAD;
            }
            if (isLambdaMetafactory(indy)) {
                for (Object arg : indy.bsmArgs) {
                    if (arg instanceof Type type &&
                        type.getSort() == Type.OBJECT &&
                        isAsyncCarrierType(type.getInternalName())) {
                        mode |= KEY_TRANSFER_RUNTIME_SOURCE_THREAD;
                    }
                }
            }
        } else if (context instanceof MethodInsnNode call) {
            if (isAsyncBoundaryCall(call)) {
                mode |= KEY_TRANSFER_RUNTIME_SOURCE_THREAD;
            }
            if (isStackTraceBoundaryCall(call) || isExceptionBoundaryCall(call)) {
                mode |= KEY_TRANSFER_RUNTIME_SOURCE_STACK;
            }
        }
        return mode;
    }

    protected boolean keyTransferDefersTicket(AbstractInsnNode insn, int runtimeSourceMode) {
        if ((runtimeSourceMode & KEY_TRANSFER_RUNTIME_SOURCE_THREAD) != 0) {
            return true;
        }
        AbstractInsnNode context = keyTransferSourceContext(insn);
        return context instanceof InvokeDynamicInsnNode indy && isLambdaMetafactory(indy);
    }

    protected int registerKeyTransferMaterialWord(
        CffClassKeyTable table,
        int word,
        CffBlockKeyState expectedKeys,
        long materialSeed,
        long methodSeed,
        int runtimeSourceMode
    ) {
        int bucketCount = keyTransferRuntimeSourceBucketCount(runtimeSourceMode);
        int baseCursor = -1;
        for (int bucket = 0; bucket < bucketCount; bucket++) {
            int cursor = registerKeyTransferMaterialWordBucket(
                table,
                word,
                expectedKeys,
                keyTransferRuntimeSourceBucketSeed(
                    materialSeed,
                    runtimeSourceMode,
                    bucket
                ),
                methodSeed
            );
            if (bucket == 0) {
                baseCursor = cursor;
            } else {
                int expectedCursor = baseCursor + bucket * TOKEN_MATERIAL_ROW_LONGS * 2;
                if (cursor != expectedCursor) {
                    throw new IllegalStateException(
                        "CFF key-transfer runtime source rows are not contiguous for " +
                            table.owner()
                    );
                }
            }
        }
        return encodeKeyTransferMaterialCursor(baseCursor, runtimeSourceMode);
    }

    protected int registerKeyTransferMaterialWordBucket(
        CffClassKeyTable table,
        int word,
        CffBlockKeyState expectedKeys,
        long materialSeed,
        long methodSeed
    ) {
        int materialToken = word ^ methodKeyFold(expectedKeys.methodKey(), methodSeed);
        int encrypted = materialToken ^
            classTokenMask(expectedKeys, materialSeed) ^
            classObjectTokenMask(expectedKeys, materialSeed) ^
            controlTokenMask(expectedKeys, materialSeed);
        return registerEncryptedTokenMaterial(table, encrypted, materialSeed);
    }

    protected int keyTransferRuntimeSourceBucketCount(int runtimeSourceMode) {
        return runtimeSourceMode == KEY_TRANSFER_RUNTIME_SOURCE_NONE
            ? 1
            : KEY_TRANSFER_RUNTIME_SOURCE_BUCKETS;
    }

    protected long keyTransferRuntimeSourceBucketSeed(
        long materialSeed,
        int runtimeSourceMode,
        int bucket
    ) {
        long seed = materialSeed ^
            (((long) runtimeSourceMode) << 40) ^
            (((long) bucket) << 32) ^
            0x4B58525352433131L;
        return JvmPassBytecode.mix(seed, 0x4B58525352433231L);
    }

    protected int encodeKeyTransferMaterialCursor(
        int cursor,
        int runtimeSourceMode
    ) {
        if ((cursor & ~KEY_TRANSFER_CURSOR_INDEX_MASK) != 0) {
            throw new IllegalStateException(
                "CFF key-transfer material cursor exceeds encoded range: " + cursor
            );
        }
        return cursor | (runtimeSourceMode << KEY_TRANSFER_CURSOR_MODE_SHIFT);
    }

    protected void emitDynamicBoundDecodedLong(
        InsnList insns,
        long value,
        CffBlockKeyState expectedKeys,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        long seed,
        int scratchLocal
    ) {
        emitEncryptedBoundToken(
            insns,
            (int) (value >>> 32),
            expectedKeys,
            keyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            seed ^ 0x42444849474831L,
            scratchLocal
        );
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        emitEncryptedBoundToken(
            insns,
            (int) value,
            expectedKeys,
            keyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            seed ^ 0x42444C4F5731L,
            scratchLocal
        );
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
    }

    protected void emitEncryptedBoundToken(
        InsnList insns,
        int token,
        CffBlockKeyState expectedKeys,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        long seed,
        int scratchLocal
    ) {
        int encrypted = token ^
            classTokenMask(expectedKeys, seed) ^
            controlTokenMask(expectedKeys, seed) ^
            methodKeyFold(expectedKeys.methodKey(), seed ^ 0x42444D45544831L);
        JvmPassBytecode.pushInt(insns, encrypted);
        emitClassTokenMask(insns, guardLocal, pathKeyLocal, blockKeyLocal, seed, scratchLocal);
        emitControlTokenMask(
            insns,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            seed
        );
        emitMethodKeyFold(insns, keyLocal, seed ^ 0x42444D45544831L);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    protected void emitDynamicDecodedLong(
        InsnList insns,
        long value,
        CffBlockKeyState expectedKeys,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        long seed,
        int scratchLocal
    ) {
        emitEncryptedToken(
            insns,
            (int) (value >>> 32),
            expectedKeys,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            seed ^ 0x5241574849474831L,
            scratchLocal
        );
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        emitEncryptedToken(
            insns,
            (int) value,
            expectedKeys,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            seed ^ 0x5241574C4F5731L,
            scratchLocal
        );
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
    }

    protected CffBlockKeyState syntheticHandlerSourceKey(
        long methodSeed,
        long salt,
        LabelNode handler
    ) {
        long seed = JvmPassBytecode.mix(
            methodSeed ^ salt ^ 0x48414E444C45524BL,
            System.identityHashCode(handler)
        );
        return blockKeyState(seed ^ 0x48414E444C455252L, nonZeroInt(JvmPassBytecode.mix(seed, 0x48544331L)));
    }

    protected CffBlockKeyState blockKeyState(long seed, int pcToken) {
        int guardKey = nonZeroInt(JvmPassBytecode.mix(seed, 0x47554152444B31L));
        int pathKey = nonZeroInt(JvmPassBytecode.mix(seed, 0x504154484B31L));
        int blockKey = nonZeroInt(JvmPassBytecode.mix(seed, 0x424C4F434B31L));
        long methodSalt = nonZeroLong(JvmPassBytecode.mix(seed, 0x4D4554484F444B31L));
        return new CffBlockKeyState(
            guardKey,
            pathKey,
            blockKey,
            pcToken,
            methodKeyFromBlock(guardKey, pathKey, blockKey, pcToken, methodSalt),
            methodSalt
        );
    }

    protected long methodKeyFromBlock(
        int guardKey,
        int pathKey,
        int blockKey,
        int pcToken,
        long methodSalt
    ) {
        long high = ((long) guardKey) << 32;
        long low = ((long) pathKey) & 0xFFFFFFFFL;
        long pc = ((long) pcToken) & 0xFFFFFFFFL;
        return nonZeroLong((high ^ low) + (((long) blockKey) ^ methodSalt) ^ (pc * METHOD_KEY_PC_MIX));
    }

    protected CffBlockKeyState firstIslandKeyState(
        IslandGroup group,
        int island,
        Map<LabelNode, CffBlockKeyState> keyStateByLabel
    ) {
        for (Block block : group.blocks()) {
            Integer blockIsland = group.islands().get(block.label());
            if (blockIsland != null && blockIsland == island) {
                return requireBlockKey(
                    block.label(),
                    keyStateByLabel.get(block.label())
                );
            }
        }
        throw new IllegalStateException("CFF island has no block key state");
    }

    protected LabelNode firstIslandLabel(IslandGroup group, int island) {
        for (Block block : group.blocks()) {
            Integer blockIsland = group.islands().get(block.label());
            if (blockIsland != null && blockIsland == island) {
                return block.label();
            }
        }
        throw new IllegalStateException("CFF island has no block label");
    }

    protected int domainToken(long groupSalt, int island) {
        return nonZeroInt(
            JvmPassBytecode.mix(groupSalt ^ 0x444F4D544F4B31L, island)
        );
    }

    protected int fakeDispatchToken(
        long groupSalt,
        int fakeState,
        int island,
        int fakeIndex
    ) {
        long seed = JvmPassBytecode.mix(
            groupSalt ^ 0x46414B45544F4B31L ^ island,
            fakeState
        );
        return nonZeroInt(JvmPassBytecode.mix(seed, fakeIndex));
    }

    protected long caseSelectorSeed(
        long groupSalt,
        LabelNode label,
        int state,
        int island
    ) {
        long seed = JvmPassBytecode.mix(
            groupSalt ^ 0x4341534553454C31L ^ island,
            state
        );
        seed = JvmPassBytecode.mix(seed, System.identityHashCode(label));
        return seed == 0L ? groupSalt ^ 0x53454C45435431L : seed;
    }

    protected Block firstNonHandler(List<Block> blocks) {
        for (Block block : blocks) {
            if (!block.handler()) return block;
        }
        return null;
    }

    protected int shift(long seed, int base) {
        return 1 + (int) ((seed >>> base) & 30);
    }

    protected LabelNode ensureLabelBefore(MethodNode mn, AbstractInsnNode node) {
        for (
            AbstractInsnNode previous = node.getPrevious();
            previous != null && previous.getOpcode() < 0;
            previous = previous.getPrevious()
        ) {
            if (previous instanceof LabelNode label) return label;
        }
        LabelNode label = new LabelNode();
        mn.instructions.insertBefore(node, label);
        return label;
    }

    protected LabelNode ensureLabelAfter(MethodNode mn, AbstractInsnNode node) {
        AbstractInsnNode next = node.getNext();
        if (next instanceof LabelNode label) return label;
        LabelNode label = new LabelNode();
        mn.instructions.insert(node, label);
        return label;
    }

    protected AbstractInsnNode firstReal(MethodNode mn) {
        return nextReal(mn.instructions.getFirst());
    }

    protected AbstractInsnNode nextReal(AbstractInsnNode start) {
        for (
            AbstractInsnNode insn = start;
            insn != null;
            insn = insn.getNext()
        ) {
            if (insn.getOpcode() >= 0) return insn;
        }
        return null;
    }

    protected AbstractInsnNode lastRealBefore(MethodNode mn, AbstractInsnNode endExclusive) {
        AbstractInsnNode insn = endExclusive == null
            ? mn.instructions.getLast()
            : endExclusive.getPrevious();
        if (insn == null) return null;
        for (; insn != null; insn = insn.getPrevious()) {
            if (insn.getOpcode() >= 0) return insn;
        }
        return null;
    }

    protected boolean before(AbstractInsnNode left, AbstractInsnNode right) {
        for (
            AbstractInsnNode insn = left;
            insn != null;
            insn = insn.getNext()
        ) {
            if (insn == right) return true;
        }
        return false;
    }

    protected boolean terminates(int opcode) {
        return (
            (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) ||
            opcode == Opcodes.ATHROW
        );
    }

    protected boolean isControlTransfer(AbstractInsnNode insn) {
        return (
            insn instanceof JumpInsnNode ||
            insn instanceof TableSwitchInsnNode ||
            insn instanceof LookupSwitchInsnNode ||
            terminates(insn.getOpcode())
        );
    }

    protected int invertJumpOpcode(int opcode) {
        return switch (opcode) {
            case Opcodes.IFEQ -> Opcodes.IFNE;
            case Opcodes.IFNE -> Opcodes.IFEQ;
            case Opcodes.IFLT -> Opcodes.IFGE;
            case Opcodes.IFGE -> Opcodes.IFLT;
            case Opcodes.IFGT -> Opcodes.IFLE;
            case Opcodes.IFLE -> Opcodes.IFGT;
            case Opcodes.IF_ICMPEQ -> Opcodes.IF_ICMPNE;
            case Opcodes.IF_ICMPNE -> Opcodes.IF_ICMPEQ;
            case Opcodes.IF_ICMPLT -> Opcodes.IF_ICMPGE;
            case Opcodes.IF_ICMPGE -> Opcodes.IF_ICMPLT;
            case Opcodes.IF_ICMPGT -> Opcodes.IF_ICMPLE;
            case Opcodes.IF_ICMPLE -> Opcodes.IF_ICMPGT;
            case Opcodes.IF_ACMPEQ -> Opcodes.IF_ACMPNE;
            case Opcodes.IF_ACMPNE -> Opcodes.IF_ACMPEQ;
            case Opcodes.IFNULL -> Opcodes.IFNONNULL;
            case Opcodes.IFNONNULL -> Opcodes.IFNULL;
            default -> throw new IllegalStateException(
                "Unsupported conditional opcode for inversion: " + opcode
            );
        };
    }

    protected void emitInitTransitionOut(InsnList insns, int outLocal) {
        JvmPassBytecode.pushInt(insns, 4);
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_LONG));
        insns.add(new VarInsnNode(Opcodes.ASTORE, outLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        JvmPassBytecode.pushInt(insns, 3);
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "java/lang/Thread",
            "currentThread",
            "()Ljava/lang/Thread;",
            false
        ));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "java/lang/System",
            "identityHashCode",
            "(Ljava/lang/Object;)I",
            false
        ));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new InsnNode(Opcodes.LASTORE));
    }

    protected void emitTransitionOutStores(
        InsnList insns,
        int outLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int domainLocal
    ) {
        emitTransitionOutStores(
            insns,
            outLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            pcLocal,
            domainLocal,
            true
        );
    }

    protected void emitTransitionOutStores(
        InsnList insns,
        int outLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int domainLocal,
        boolean includeDomain
    ) {
        emitTransitionOutPairStore(insns, outLocal, 0, guardLocal, pathKeyLocal);
        emitTransitionOutPairStore(insns, outLocal, 1, blockKeyLocal, pcLocal);
        if (includeDomain) {
            emitTransitionOutHighStore(insns, outLocal, 2, domainLocal);
        }
    }

    protected void emitTransitionOutStoresWithResult(
        InsnList insns,
        int outLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int domainLocal,
        int resultToken
    ) {
        emitTransitionOutPairStore(insns, outLocal, 0, guardLocal, pathKeyLocal);
        emitTransitionOutPairStore(insns, outLocal, 1, blockKeyLocal, pcLocal);
        emitTransitionOutPairStoreConstLow(insns, outLocal, 2, domainLocal, resultToken);
    }

    protected void emitTransitionOutStoresWithResultLocal(
        InsnList insns,
        int outLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int domainLocal,
        int resultLocal
    ) {
        emitTransitionOutPairStore(insns, outLocal, 0, guardLocal, pathKeyLocal);
        emitTransitionOutPairStore(insns, outLocal, 1, blockKeyLocal, pcLocal);
        emitTransitionOutPairStoreLocalLow(insns, outLocal, 2, domainLocal, resultLocal);
    }

    protected void emitTransitionOutStoresWithMaskedResult(
        InsnList insns,
        int outLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int domainLocal,
        int keyLocal,
        int resultOrdinal,
        long resultMaskSeed
    ) {
        emitTransitionOutPairStore(insns, outLocal, 0, guardLocal, pathKeyLocal);
        emitTransitionOutPairStore(insns, outLocal, 1, blockKeyLocal, pcLocal);
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        JvmPassBytecode.pushInt(insns, 2);
        insns.add(new VarInsnNode(Opcodes.ILOAD, domainLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        JvmPassBytecode.pushInt(insns, resultOrdinal);
        emitResultRouteMask(
            insns,
            keyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            pcLocal,
            domainLocal,
            resultMaskSeed
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
        insns.add(new InsnNode(Opcodes.LASTORE));
    }

    protected void emitTransitionOutStoresWithMaskedResultLocal(
        InsnList insns,
        int outLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int domainLocal,
        int keyLocal,
        int resultLocal,
        long resultMaskSeed
    ) {
        emitTransitionOutPairStore(insns, outLocal, 0, guardLocal, pathKeyLocal);
        emitTransitionOutPairStore(insns, outLocal, 1, blockKeyLocal, pcLocal);
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        JvmPassBytecode.pushInt(insns, 2);
        insns.add(new VarInsnNode(Opcodes.ILOAD, domainLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        insns.add(new VarInsnNode(Opcodes.ILOAD, resultLocal));
        emitResultRouteMask(
            insns,
            keyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            pcLocal,
            domainLocal,
            resultMaskSeed
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
        insns.add(new InsnNode(Opcodes.LASTORE));
    }

    protected void emitResultRouteMask(
        InsnList insns,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int domainLocal,
        long seed
    ) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, (int) seed);
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, 0x45D9F3B);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockKeyLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pcLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, domainLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    protected void emitTransitionOutPairStore(
        InsnList insns,
        int outLocal,
        int index,
        int highLocal,
        int lowLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        JvmPassBytecode.pushInt(insns, index);
        insns.add(new VarInsnNode(Opcodes.ILOAD, highLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        insns.add(new VarInsnNode(Opcodes.ILOAD, lowLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
        insns.add(new InsnNode(Opcodes.LASTORE));
    }

    protected void emitTransitionOutHighStore(
        InsnList insns,
        int outLocal,
        int index,
        int highLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        JvmPassBytecode.pushInt(insns, index);
        insns.add(new VarInsnNode(Opcodes.ILOAD, highLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        insns.add(new InsnNode(Opcodes.LASTORE));
    }

    protected void emitTransitionOutPairStoreConstLow(
        InsnList insns,
        int outLocal,
        int index,
        int highLocal,
        int low
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        JvmPassBytecode.pushInt(insns, index);
        insns.add(new VarInsnNode(Opcodes.ILOAD, highLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        JvmPassBytecode.pushInt(insns, low);
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
        insns.add(new InsnNode(Opcodes.LASTORE));
    }

    protected void emitTransitionOutPairStoreLocalLow(
        InsnList insns,
        int outLocal,
        int index,
        int highLocal,
        int lowLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        JvmPassBytecode.pushInt(insns, index);
        insns.add(new VarInsnNode(Opcodes.ILOAD, highLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        insns.add(new VarInsnNode(Opcodes.ILOAD, lowLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
        insns.add(new InsnNode(Opcodes.LASTORE));
    }

    protected void emitTransitionOutLoads(
        InsnList insns,
        int outLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int domainLocal
    ) {
        emitTransitionOutLoads(
            insns,
            outLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            pcLocal,
            domainLocal,
            true
        );
    }

    protected void emitTransitionOutLoads(
        InsnList insns,
        int outLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int domainLocal,
        boolean includeDomain
    ) {
        emitTransitionOutPairLoad(insns, outLocal, 0, guardLocal, pathKeyLocal);
        emitTransitionOutPairLoad(insns, outLocal, 1, blockKeyLocal, pcLocal);
        if (includeDomain) {
            emitTransitionOutHighLoad(insns, outLocal, 2, domainLocal);
        }
    }

    protected void emitTransitionOutPairLoad(
        InsnList insns,
        int outLocal,
        int index,
        int highLocal,
        int lowLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        JvmPassBytecode.pushInt(insns, index);
        insns.add(new InsnNode(Opcodes.LALOAD));
        insns.add(new InsnNode(Opcodes.DUP2));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new VarInsnNode(Opcodes.ISTORE, highLocal));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new VarInsnNode(Opcodes.ISTORE, lowLocal));
    }

    protected void emitTransitionOutHighLoad(
        InsnList insns,
        int outLocal,
        int index,
        int highLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        JvmPassBytecode.pushInt(insns, index);
        insns.add(new InsnNode(Opcodes.LALOAD));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new VarInsnNode(Opcodes.ISTORE, highLocal));
    }

    protected void emitTransitionOutLowLoad(
        InsnList insns,
        int outLocal,
        int index,
        int lowLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        JvmPassBytecode.pushInt(insns, index);
        insns.add(new InsnNode(Opcodes.LALOAD));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new VarInsnNode(Opcodes.ISTORE, lowLocal));
    }

    protected void emitPackedTransitionOutLoads(
        InsnList insns,
        int outLocal,
        int tokenLocal,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int domainLocal
    ) {
        emitPackedTransitionOutLoad(insns, outLocal, tokenLocal, 0, guardLocal);
        emitPackedTransitionOutLoad(insns, outLocal, tokenLocal, 1, pathKeyLocal);
        emitPackedTransitionOutLoad(insns, outLocal, tokenLocal, 2, blockKeyLocal);
        emitPackedTransitionOutLoad(insns, outLocal, tokenLocal, 3, pcLocal);
        emitPackedTransitionOutLoad(insns, outLocal, tokenLocal, 4, domainLocal);
        emitPackedTransitionOutValue(insns, outLocal, tokenLocal, 6);
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        emitPackedTransitionOutValue(insns, outLocal, tokenLocal, 7);
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
        insns.add(new VarInsnNode(Opcodes.LSTORE, keyLocal));
    }

    protected void emitPackedTransitionOutLoad(
        InsnList insns,
        int outLocal,
        int tokenLocal,
        int index,
        int dstLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        JvmPassBytecode.pushInt(insns, index);
        insns.add(new InsnNode(Opcodes.IALOAD));
        emitTransitionTokenMask(insns, tokenLocal, index);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, dstLocal));
    }

    protected void emitPackedTransitionOutValue(
        InsnList insns,
        int outLocal,
        int tokenLocal,
        int index
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        JvmPassBytecode.pushInt(insns, index);
        insns.add(new InsnNode(Opcodes.IALOAD));
        emitTransitionTokenMask(insns, tokenLocal, index);
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    protected void emitPackedTransitionOutStore(
        InsnList insns,
        int outLocal,
        int tokenLocal,
        int index,
        int valueLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        JvmPassBytecode.pushInt(insns, index);
        insns.add(new VarInsnNode(Opcodes.ILOAD, valueLocal));
        emitTransitionTokenMask(insns, tokenLocal, index);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IASTORE));
    }

    protected void emitPackedTransitionOutStoreConst(
        InsnList insns,
        int outLocal,
        int tokenLocal,
        int index,
        int value
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        JvmPassBytecode.pushInt(insns, index);
        JvmPassBytecode.pushInt(insns, value);
        emitTransitionTokenMask(insns, tokenLocal, index);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IASTORE));
    }

    protected void emitTransitionTokenMask(
        InsnList insns,
        int tokenLocal,
        int index
    ) {
        long seed = JvmPassBytecode.mix(0x535542444953504CL, index);
        insns.add(new VarInsnNode(Opcodes.LLOAD, tokenLocal));
        JvmPassBytecode.pushLong(insns, seed);
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.DUP2));
        JvmPassBytecode.pushInt(insns, 23 + (index & 7));
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.L2I));
    }
}
