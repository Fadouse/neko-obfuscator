package dev.nekoobfuscator.transforms.jvm.key;

import dev.nekoobfuscator.api.transform.IRLevel;
import dev.nekoobfuscator.api.transform.TransformContext;
import dev.nekoobfuscator.api.transform.TransformPass;
import dev.nekoobfuscator.api.transform.TransformPhase;
import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.ir.l1.L1Method;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import dev.nekoobfuscator.transforms.util.JvmObfuscationCoverage;
import dev.nekoobfuscator.transforms.util.TransformGuards;
import dev.nekoobfuscator.transforms.jvm.internal.JvmPassBytecode;
import dev.nekoobfuscator.transforms.jvm.parameters.JvmMethodParameterObfuscationPass;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Establishes hidden key material for JVM obfuscation passes.
 *
 * <p>Application methods whose descriptor can be changed receive a real trailing
 * {@code long} key parameter. JVM ABI entry points that cannot be changed keep
 * their original descriptor, create a local key in the original method body, and
 * pass that key onward to keyed application calls. No runtime carrier or bridge
 * method is generated.</p>
 */
public final class JvmKeyDispatchPass implements TransformPass {
    public static final String ID = "keyDispatch";
    public static final String CONTEXT_CENSUS_STATS = "keyDispatch.contextCensusStats";
    private static final Logger log = LoggerFactory.getLogger(JvmKeyDispatchPass.class);
    public static final String LOCAL_BY_METHOD = "keyDispatch.localByMethod";
    public static final String SEED_BY_METHOD = "keyDispatch.seedByMethod";
    public static final String CFF_LOCAL_BY_METHOD = "controlFlowFlattening.flowKeyLocalByMethod";
    public static final String GENERATED_NODES = "jvm.generatedNodes";
    public static final long INCOMING_KEY_MIX_MASK = 0x4E4B4F4A564D4B31L;
    private static final String PREPARED = "keyDispatch.preparedInPlaceSignatures";
    private static final String KEYED_DESC_BY_METHOD = "keyDispatch.keyedDescByMethod";
    private static final String KEY_INDEX_BY_METHOD = "keyDispatch.keyIndexByMethod";
    private static final String REFLECTIVE_KEYED_ENTRIES = "keyDispatch.reflectiveKeyedEntries";
    private static final String ACTUAL_KEYED_ENTRIES = "keyDispatch.actualKeyedEntries";
    private static final String REUSABLE_KEYED_ENTRIES = "keyDispatch.reusableKeyedEntries";
    private static final String INDY_SAM_TARGETS = "keyDispatch.indySamTargets";
    private static final String UNKEYED_INBOUND_TARGETS = "keyDispatch.unkeyedInboundTargets";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "JVM Key Dispatch";
    }

    @Override
    public TransformPhase phase() {
        return TransformPhase.PRE_TRANSFORM;
    }

    @Override
    public IRLevel requiredLevel() {
        return IRLevel.L1;
    }

    @Override
    public void transformClass(TransformContext ctx) {
        if (Boolean.TRUE.equals(ctx.getPassData(PREPARED))) return;
        if (ctx instanceof PipelineContext pctx) {
            prepareKeyedDescriptors(pctx);
        }
        ctx.putPassData(PREPARED, Boolean.TRUE);
    }

    @Override
    public void transformMethod(TransformContext ctx) {
        if (!(ctx instanceof PipelineContext pctx)) return;
        L1Class clazz = pctx.currentL1Class();
        L1Method method = pctx.currentL1Method();
        if (clazz == null || method == null || !method.hasCode()) return;

        if (!isKeyCandidate(pctx, clazz, method)) {
            return;
        }

        MethodNode mn = method.asmNode();
        String originalDesc = mn.desc;
        String originalMethodKey = coverageKey(clazz.name(), mn.name, originalDesc);
        boolean keyedDescriptor = keyedDescMap(ctx).containsKey(originalMethodKey);
        int incomingKeyLocal = -1;
        if (keyedDescriptor) {
            int keyIndex = keyIndexMap(ctx).getOrDefault(originalMethodKey, Type.getArgumentTypes(originalDesc).length);
            incomingKeyLocal = addLongParameter(clazz, mn, keyIndex);
            actualKeyedEntries(ctx).add(coverageKey(clazz.name(), mn.name, mn.desc));
            if (lambdaKeyIndexes(pctx).containsKey(originalMethodKey)) {
                reusableKeyedEntries(ctx).add(coverageKey(clazz.name(), mn.name, mn.desc));
            }
        }

        String methodKey = coverageKey(clazz, method);
        String actualMethodKey = coverageKey(clazz.name(), mn.name, mn.desc);
        Map<String, Integer> locals = localMap(ctx, LOCAL_BY_METHOD);
        Integer existing = locals.get(methodKey);
        if (existing == null && !actualMethodKey.equals(methodKey)) {
            existing = locals.get(actualMethodKey);
        }
        if (existing != null) {
            publishControlFlowLocal(ctx, methodKey, existing);
            if (!actualMethodKey.equals(methodKey)) {
                publishControlFlowLocal(ctx, actualMethodKey, existing);
            }
            JvmObfuscationCoverage.get(ctx).safe(id(), clazz.name(), method.name(),
                method.descriptor(), "key-local-already-present");
            return;
        }

        int keyLocal = incomingKeyLocal >= 0 ? incomingKeyLocal : mn.maxLocals;
        long seed = methodSeed(pctx, clazz, method, mn);
        InsnList prologue = new InsnList();
        if (incomingKeyLocal >= 0) {
            emitIncomingKeyMix(prologue, keyLocal, seed, INCOMING_KEY_MIX_MASK);
        } else {
            emitKeyInit(prologue, keyLocal, seed, 0x4E4B4F4A564D4B31L);
        }

        AbstractInsnNode first = firstRealInstruction(mn);
        if (first == null) return;
        markGenerated(ctx, prologue);
        mn.instructions.insertBefore(prologueInsertionPoint(first), prologue);
        if (incomingKeyLocal < 0) {
            mn.maxLocals = Math.max(mn.maxLocals, keyLocal + 2);
        }
        mn.maxStack = Math.max(mn.maxStack, 8);
        int transfers = instrumentKeyTransfers(pctx, methodKey, mn, keyLocal);
        clazz.markDirty();
        pctx.invalidate(method);

        recordMethodKeyLocal(ctx, methodKey, keyLocal, seed);
        if (!actualMethodKey.equals(methodKey)) {
            recordMethodKeyLocal(ctx, actualMethodKey, keyLocal, seed);
        }
        logContextCensusMethodStats(pctx, methodKey);
        JvmObfuscationCoverage.get(ctx).full(id(), clazz.name(), method.name(),
            method.descriptor(), transfers == 0
                ? "method-key-local"
                : "method-key-local+long-key-callsite-transfer");
    }

    public static boolean isKeyCandidate(PipelineContext pctx, L1Class clazz, L1Method method) {
        if (!pctx.isTransformEnabledForClass(ID, clazz)) return false;
        if (TransformGuards.isRuntimeClass(clazz) || TransformGuards.isGeneratedMethod(method)) return false;
        if (method.isAbstract() || method.isNative()) return false;
        return true;
    }

    public static int ensureMethodKeyLocal(PipelineContext pctx, L1Class clazz, L1Method method) {
        String key = coverageKey(clazz, method);
        Map<String, Integer> locals = localMap(pctx, LOCAL_BY_METHOD);
        Integer existing = locals.get(key);
        if (existing != null) {
            publishControlFlowLocal(pctx, key, existing);
            return existing;
        }

        MethodNode mn = method.asmNode();
        int keyLocal = mn.maxLocals;
        long seed = methodSeed(pctx, clazz, method, mn);
        InsnList prologue = new InsnList();
        emitKeyInit(prologue, keyLocal, seed, 0x6A766D4B65794C31L);

        AbstractInsnNode first = firstRealInstruction(mn);
        if (first == null) return -1;
        markGenerated(pctx, prologue);
        mn.instructions.insertBefore(prologueInsertionPoint(first), prologue);
        mn.maxLocals = Math.max(mn.maxLocals, keyLocal + 2);
        mn.maxStack = Math.max(mn.maxStack, 6);
        instrumentKeyTransfers(pctx, key, mn, keyLocal);
        clazz.markDirty();
        pctx.invalidate(method);

        recordMethodKeyLocal(pctx, key, keyLocal, seed);
        logContextCensusMethodStats(pctx, key);
        return keyLocal;
    }

    public static Integer findMethodKeyLocal(TransformContext ctx, String methodKey) {
        return localMap(ctx, LOCAL_BY_METHOD).get(methodKey);
    }

    public static Long findMethodSeed(TransformContext ctx, String methodKey) {
        return seedMap(ctx).get(methodKey);
    }

    public static void recordMethodKeyLocal(TransformContext ctx, String methodKey, int keyLocal, long seed) {
        localMap(ctx, LOCAL_BY_METHOD).put(methodKey, keyLocal);
        seedMap(ctx).put(methodKey, seed);
        publishControlFlowLocal(ctx, methodKey, keyLocal);
    }

    public static void recordMethodSeed(TransformContext ctx, String methodKey, long seed) {
        seedMap(ctx).put(methodKey, seed);
    }

    public static void emitKeyInit(InsnList insns, int keyLocal, long seed, long mask) {
        JvmPassBytecode.pushLong(insns, incomingRawFor(seed, seed, mask));
        emitIncomingKeyMixFromStack(insns, keyLocal, seed, mask);
    }

    public static String coverageKey(L1Class clazz, L1Method method) {
        return coverageKey(clazz.name(), method.name(), method.descriptor());
    }

    public static long methodSeed(long masterSeed, L1Class clazz, L1Method method, MethodNode mn) {
        long h = masterSeed ^ 0x9E3779B97F4A7C15L;
        h = JvmPassBytecode.mix(h, clazz.name().hashCode());
        h = JvmPassBytecode.mix(h, method.name().hashCode());
        h = JvmPassBytecode.mix(h, method.descriptor().hashCode());
        h = JvmPassBytecode.mix(h, mn.instructions == null ? 0 : mn.instructions.size());
        h = JvmPassBytecode.mix(h, mn.maxLocals);
        return h == 0L ? 0x5DEECE66DL : h;
    }

    public static long methodSeed(PipelineContext pctx, L1Class clazz, L1Method method, MethodNode mn) {
        if (!usesVirtualFamilySeed(mn)) {
            return methodSeed(pctx.masterSeed(), clazz, method, mn);
        }
        String owner = virtualFamilyOwner(pctx, clazz, mn.name, mn.desc);
        long h = pctx.masterSeed() ^ 0x9E3779B97F4A7C15L;
        h = JvmPassBytecode.mix(h, owner.hashCode());
        h = JvmPassBytecode.mix(h, mn.name.hashCode());
        h = JvmPassBytecode.mix(h, mn.desc.hashCode());
        h = JvmPassBytecode.mix(h, 0x5649525453454544L);
        return h == 0L ? 0x5DEECE66DL : h;
    }

    private static boolean usesVirtualFamilySeed(MethodNode mn) {
        if ("<init>".equals(mn.name) || "<clinit>".equals(mn.name)) return false;
        return (mn.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0;
    }

    private static String virtualFamilyOwner(PipelineContext pctx, L1Class clazz, String name, String desc) {
        String best = null;
        best = betterVirtualRoot(best, virtualFamilyOwnerIn(pctx, clazz.superName(), name, desc));
        for (String iface : clazz.interfaces()) {
            best = betterVirtualRoot(best, virtualFamilyOwnerIn(pctx, iface, name, desc));
        }
        return best == null ? clazz.name() : best;
    }

    private static String virtualFamilyOwnerIn(PipelineContext pctx, String owner, String name, String desc) {
        if (owner == null) return null;
        L1Class clazz = pctx.classMap().get(owner);
        if (clazz == null) return null;
        String best = declaresAsmMethod(clazz, name, desc) ? clazz.name() : null;
        best = betterVirtualRoot(best, virtualFamilyOwnerIn(pctx, clazz.superName(), name, desc));
        for (String iface : clazz.interfaces()) {
            best = betterVirtualRoot(best, virtualFamilyOwnerIn(pctx, iface, name, desc));
        }
        return best;
    }

    private static boolean declaresAsmMethod(L1Class clazz, String name, String desc) {
        if (clazz.findMethod(name, desc) != null) return true;
        for (L1Method method : clazz.methods()) {
            MethodNode mn = method.asmNode();
            if (mn != null && name.equals(mn.name) && desc.equals(mn.desc)) return true;
        }
        return false;
    }

    private static L1Method findAsmMethod(L1Class clazz, String name, String desc) {
        L1Method direct = clazz.findMethod(name, desc);
        if (direct != null) return direct;
        for (L1Method method : clazz.methods()) {
            MethodNode mn = method.asmNode();
            if (mn != null && name.equals(mn.name) && desc.equals(mn.desc)) return method;
        }
        return null;
    }

    private static long pendingKeyedMethodSeed(PipelineContext pctx, L1Class clazz, L1Method method, String keyedDesc) {
        MethodNode mn = method.asmNode();
        if (usesVirtualFamilySeed(mn)) {
            String owner = virtualFamilyOwner(pctx, clazz, mn.name, keyedDesc);
            long h = pctx.masterSeed() ^ 0x9E3779B97F4A7C15L;
            h = JvmPassBytecode.mix(h, owner.hashCode());
            h = JvmPassBytecode.mix(h, mn.name.hashCode());
            h = JvmPassBytecode.mix(h, keyedDesc.hashCode());
            h = JvmPassBytecode.mix(h, 0x5649525453454544L);
            return h == 0L ? 0x5DEECE66DL : h;
        }
        long h = pctx.masterSeed() ^ 0x9E3779B97F4A7C15L;
        h = JvmPassBytecode.mix(h, clazz.name().hashCode());
        h = JvmPassBytecode.mix(h, method.name().hashCode());
        h = JvmPassBytecode.mix(h, keyedDesc.hashCode());
        h = JvmPassBytecode.mix(h, mn.instructions == null ? 0 : mn.instructions.size());
        h = JvmPassBytecode.mix(h, mn.maxLocals + 2);
        return h == 0L ? 0x5DEECE66DL : h;
    }

    private static String betterVirtualRoot(String current, String candidate) {
        if (candidate == null) return current;
        if (current == null) return candidate;
        return candidate.compareTo(current) < 0 ? candidate : current;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> localMap(TransformContext ctx, String key) {
        Map<String, Integer> map = ctx.getPassData(key);
        if (map == null) {
            map = new LinkedHashMap<>();
            ctx.putPassData(key, map);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> seedMap(TransformContext ctx) {
        Map<String, Long> map = ctx.getPassData(SEED_BY_METHOD);
        if (map == null) {
            map = new LinkedHashMap<>();
            ctx.putPassData(SEED_BY_METHOD, map);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> keyedDescMap(TransformContext ctx) {
        Map<String, String> map = ctx.getPassData(KEYED_DESC_BY_METHOD);
        if (map == null) {
            map = new LinkedHashMap<>();
            ctx.putPassData(KEYED_DESC_BY_METHOD, map);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> keyIndexMap(TransformContext ctx) {
        Map<String, Integer> map = ctx.getPassData(KEY_INDEX_BY_METHOD);
        if (map == null) {
            map = new LinkedHashMap<>();
            ctx.putPassData(KEY_INDEX_BY_METHOD, map);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> reflectiveKeyedEntries(TransformContext ctx) {
        Set<String> entries = ctx.getPassData(REFLECTIVE_KEYED_ENTRIES);
        if (entries == null) {
            entries = new java.util.LinkedHashSet<>();
            ctx.putPassData(REFLECTIVE_KEYED_ENTRIES, entries);
        }
        return entries;
    }

    public static boolean isReflectiveKeyedEntry(TransformContext ctx, String methodKey) {
        Set<String> entries = ctx.getPassData(REFLECTIVE_KEYED_ENTRIES);
        return entries != null && entries.contains(methodKey);
    }

    public static boolean isActualKeyedEntry(TransformContext ctx, String methodKey) {
        Set<String> entries = ctx.getPassData(ACTUAL_KEYED_ENTRIES);
        return entries != null && entries.contains(methodKey);
    }

    public static boolean isReusableKeyedEntry(TransformContext ctx, String methodKey) {
        Set<String> entries = ctx.getPassData(REUSABLE_KEYED_ENTRIES);
        return entries != null && entries.contains(methodKey);
    }

    public static void migrateReflectiveKeyedEntry(TransformContext ctx, String oldKey, String newKey) {
        Set<String> entries = ctx.getPassData(REFLECTIVE_KEYED_ENTRIES);
        if (entries != null && entries.remove(oldKey)) {
            entries.add(newKey);
        }
    }

    private static void publishControlFlowLocal(TransformContext ctx, String methodKey, int keyLocal) {
        localMap(ctx, CFF_LOCAL_BY_METHOD).put(methodKey, keyLocal);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> actualKeyedEntries(TransformContext ctx) {
        Set<String> entries = ctx.getPassData(ACTUAL_KEYED_ENTRIES);
        if (entries == null) {
            entries = new java.util.LinkedHashSet<>();
            ctx.putPassData(ACTUAL_KEYED_ENTRIES, entries);
        }
        return entries;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> reusableKeyedEntries(TransformContext ctx) {
        Set<String> entries = ctx.getPassData(REUSABLE_KEYED_ENTRIES);
        if (entries == null) {
            entries = new java.util.LinkedHashSet<>();
            ctx.putPassData(REUSABLE_KEYED_ENTRIES, entries);
        }
        return entries;
    }

    public static Set<AbstractInsnNode> generatedNodes(TransformContext ctx) {
        Set<AbstractInsnNode> nodes = ctx.getPassData(GENERATED_NODES);
        if (nodes == null) {
            nodes = Collections.newSetFromMap(new IdentityHashMap<>());
            ctx.putPassData(GENERATED_NODES, nodes);
        }
        return nodes;
    }

    public static void markGenerated(TransformContext ctx, InsnList insns) {
        Set<AbstractInsnNode> nodes = generatedNodes(ctx);
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            nodes.add(insn);
        }
    }

    public static boolean isGeneratedNode(TransformContext ctx, AbstractInsnNode insn) {
        Set<AbstractInsnNode> nodes = ctx.getPassData(GENERATED_NODES);
        return nodes != null && nodes.contains(insn);
    }

    private static AbstractInsnNode firstRealInstruction(MethodNode mn) {
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() >= 0) return insn;
        }
        return mn.instructions.getFirst();
    }

    private static AbstractInsnNode prologueInsertionPoint(AbstractInsnNode firstReal) {
        AbstractInsnNode anchor = firstReal;
        while (anchor.getPrevious() != null && anchor.getPrevious().getOpcode() < 0) {
            anchor = anchor.getPrevious();
        }
        return anchor;
    }

    private static void prepareKeyedDescriptors(PipelineContext pctx) {
        Map<String, String> keyed = keyedDescMap(pctx);
        Map<String, Integer> keyIndexes = keyIndexMap(pctx);
        Set<String> indySamTargets = indySamTargets(pctx);
        Map<String, Integer> lambdaIndexes = lambdaKeyIndexes(pctx);
        Set<String> unkeyedInboundTargets = unkeyedInboundTargets(pctx);
        for (L1Class clazz : pctx.classMap().values()) {
            if (!pctx.isTransformEnabledForClass(ID, clazz)) continue;
            for (L1Method method : clazz.methods()) {
                if (TransformGuards.isRuntimeClass(clazz) || TransformGuards.isGeneratedMethod(method)) continue;
                if (clazz.isAnnotation() || !canReceiveLongKey(method) || method.isNative()) continue;
                if (overridesExternalMethod(pctx, clazz, method.asmNode(), method.descriptor())) continue;
                if (indySamTargets.contains(coverageKey(clazz.name(), method.name(), method.descriptor()))) continue;
                String original = method.descriptor();
                String methodKey = coverageKey(clazz.name(), method.name(), original);
                if (unkeyedInboundTargets.contains(methodKey)) continue;
                int keyIndex = lambdaIndexes.getOrDefault(methodKey, Type.getArgumentTypes(original).length);
                String keyedDesc = insertLongParameter(original, keyIndex);
                keyed.put(methodKey, keyedDesc);
                keyIndexes.put(methodKey, keyIndex);
                if (!method.hasCode()) {
                    method.asmNode().desc = keyedDesc;
                    clazz.markDirty();
                }
            }
        }
    }

    private static Set<String> indySamTargets(PipelineContext pctx) {
        Set<String> targets = pctx.getPassData(INDY_SAM_TARGETS);
        if (targets != null) return targets;
        targets = new java.util.LinkedHashSet<>();
        for (L1Class clazz : pctx.classMap().values()) {
            if (!pctx.isTransformEnabledForClass(ID, clazz)) continue;
            for (L1Method method : clazz.methods()) {
                if (!method.hasCode()) continue;
                for (AbstractInsnNode insn = method.instructions().getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof InvokeDynamicInsnNode indy)) continue;
                    if (!isLambdaMetafactory(indy) || indy.bsmArgs.length == 0 || !(indy.bsmArgs[0] instanceof Type samType)) {
                        continue;
                    }
                    Type owner = Type.getReturnType(indy.desc);
                    if (owner.getSort() == Type.OBJECT) {
                        targets.add(coverageKey(owner.getInternalName(), indy.name, samType.getDescriptor()));
                    }
                    if (indy.bsmArgs.length > 1 &&
                        indy.bsmArgs[1] instanceof Handle handle &&
                        pctx.classMap().containsKey(handle.getOwner()) &&
                        isUnboundLambdaMethodReference(indy, handle)) {
                        addVirtualFamilyTargets(pctx, targets, handle.getOwner(), handle.getName(), handle.getDesc());
                    }
                }
            }
        }
        pctx.putPassData(INDY_SAM_TARGETS, targets);
        return targets;
    }

    private static boolean isUnboundLambdaMethodReference(InvokeDynamicInsnNode indy, Handle handle) {
        return handle.getTag() != Opcodes.H_INVOKESTATIC &&
            Type.getArgumentTypes(indy.desc).length == 0;
    }

    private static void addVirtualFamilyTargets(
        PipelineContext pctx,
        Set<String> targets,
        String owner,
        String name,
        String desc
    ) {
        for (L1Class clazz : pctx.classMap().values()) {
            if (!pctx.isTransformEnabledForClass(ID, clazz)) continue;
            if (!isSubtypeOf(pctx, clazz, owner)) continue;
            for (L1Method method : clazz.methods()) {
                if (method.name().equals(name) && method.descriptor().equals(desc)) {
                    targets.add(coverageKey(clazz.name(), method.name(), method.descriptor()));
                }
            }
        }
    }

    private static boolean isSubtypeOf(PipelineContext pctx, L1Class clazz, String targetOwner) {
        if (clazz == null) return false;
        if (clazz.name().equals(targetOwner)) return true;
        if (clazz.interfaces().contains(targetOwner)) return true;
        for (String iface : clazz.interfaces()) {
            if (isSubtypeOf(pctx, pctx.classMap().get(iface), targetOwner)) return true;
        }
        return isSubtypeOf(pctx, pctx.classMap().get(clazz.superName()), targetOwner);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> lambdaKeyIndexes(PipelineContext pctx) {
        Map<String, Integer> indexes = pctx.getPassData("keyDispatch.lambdaKeyIndexes");
        if (indexes != null) return indexes;
        indexes = new LinkedHashMap<>();
        for (L1Class clazz : pctx.classMap().values()) {
            if (!pctx.isTransformEnabledForClass(ID, clazz)) continue;
            for (L1Method method : clazz.methods()) {
                if (!method.hasCode()) continue;
                for (AbstractInsnNode insn = method.instructions().getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof InvokeDynamicInsnNode indy)) continue;
                    if (!isLambdaMetafactory(indy) || indy.bsmArgs.length <= 1 || !(indy.bsmArgs[1] instanceof Handle handle)) {
                        continue;
                    }
                    int captured = Type.getArgumentTypes(indy.desc).length;
                    int keyIndex = handle.getTag() == Opcodes.H_INVOKESTATIC ? captured : Math.max(0, captured - 1);
                    String key = coverageKey(handle.getOwner(), handle.getName(), handle.getDesc());
                    Integer previous = indexes.putIfAbsent(key, keyIndex);
                    if (previous != null && previous != keyIndex) {
                        throw new IllegalStateException(
                            "Conflicting LambdaMetafactory key insertion index for " +
                                handle.getOwner() + "." + handle.getName() + handle.getDesc()
                        );
                    }
                }
            }
        }
        pctx.putPassData("keyDispatch.lambdaKeyIndexes", indexes);
        return indexes;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> unkeyedInboundTargets(PipelineContext pctx) {
        Set<String> targets = pctx.getPassData(UNKEYED_INBOUND_TARGETS);
        if (targets != null) return targets;
        targets = new java.util.LinkedHashSet<>();
        for (L1Class clazz : pctx.classMap().values()) {
            if (pctx.isTransformEnabledForClass(ID, clazz)) continue;
            for (L1Method method : clazz.methods()) {
                if (!method.hasCode()) continue;
                for (AbstractInsnNode insn = method.instructions().getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof MethodInsnNode call) {
                        addUnkeyedInboundTarget(pctx, targets, call.owner, call.name, call.desc, call.getOpcode());
                        continue;
                    }
                    if (insn instanceof InvokeDynamicInsnNode indy) {
                        addUnkeyedInboundHandle(pctx, targets, indy.bsm);
                        for (Object arg : indy.bsmArgs) {
                            if (arg instanceof Handle handle) {
                                addUnkeyedInboundHandle(pctx, targets, handle);
                            }
                        }
                    }
                }
            }
        }
        pctx.putPassData(UNKEYED_INBOUND_TARGETS, targets);
        return targets;
    }

    private static void addUnkeyedInboundHandle(PipelineContext pctx, Set<String> targets, Handle handle) {
        addUnkeyedInboundTarget(
            pctx,
            targets,
            handle.getOwner(),
            handle.getName(),
            handle.getDesc(),
            opcodeForHandle(handle.getTag())
        );
    }

    private static void addUnkeyedInboundTarget(
        PipelineContext pctx,
        Set<String> targets,
        String owner,
        String name,
        String desc,
        int opcode
    ) {
        if (!pctx.classMap().containsKey(owner)) return;
        targets.add(coverageKey(owner, name, desc));
        if (opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE) {
            addVirtualFamilyTargets(pctx, targets, owner, name, desc);
        }
    }

    private static int opcodeForHandle(int tag) {
        return switch (tag) {
            case Opcodes.H_INVOKEVIRTUAL -> Opcodes.INVOKEVIRTUAL;
            case Opcodes.H_INVOKESTATIC -> Opcodes.INVOKESTATIC;
            case Opcodes.H_INVOKESPECIAL,
                 Opcodes.H_NEWINVOKESPECIAL -> Opcodes.INVOKESPECIAL;
            case Opcodes.H_INVOKEINTERFACE -> Opcodes.INVOKEINTERFACE;
            default -> -1;
        };
    }

    private static boolean canReceiveLongKey(L1Method method) {
        if (method.isConstructor() || method.isClassInit()) return false;
        if ("main".equals(method.name()) && "([Ljava/lang/String;)V".equals(method.descriptor())
            && (method.access() & Opcodes.ACC_STATIC) != 0) {
            return false;
        }
        return true;
    }

    private static boolean overridesExternalMethod(PipelineContext pctx, L1Class clazz, MethodNode mn,
            String originalDesc) {
        return overridesExternalIn(pctx, clazz.superName(), mn.name, originalDesc)
            || overridesExternalInInterfaces(pctx, clazz.interfaces(), mn.name, originalDesc);
    }

    private static boolean overridesExternalIn(PipelineContext pctx, String owner, String name, String desc) {
        if (owner == null) return false;
        L1Class appClass = pctx.classMap().get(owner);
        if (appClass != null) {
            if (appClass.findMethod(name, desc) != null) return false;
            return overridesExternalIn(pctx, appClass.superName(), name, desc)
                || overridesExternalInInterfaces(pctx, appClass.interfaces(), name, desc);
        }
        return externalClassDeclares(owner, name, desc);
    }

    private static boolean overridesExternalInInterfaces(PipelineContext pctx, List<String> interfaces,
            String name, String desc) {
        for (String iface : interfaces) {
            L1Class appInterface = pctx.classMap().get(iface);
            if (appInterface != null) {
                if (appInterface.findMethod(name, desc) != null) return false;
                if (overridesExternalInInterfaces(pctx, appInterface.interfaces(), name, desc)) return true;
                continue;
            }
            if (externalClassDeclares(iface, name, desc)) return true;
        }
        return false;
    }

    private static boolean externalClassDeclares(String owner, String name, String desc) {
        try {
            Class<?> type = Class.forName(owner.replace('/', '.'), false, JvmKeyDispatchPass.class.getClassLoader());
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name) && Type.getMethodDescriptor(method).equals(desc)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int addLongParameter(L1Class clazz, MethodNode mn, int keyIndex) {
        int insertLocal = argumentLocalOffset(mn.access, mn.desc, keyIndex);
        shiftLocals(mn, insertLocal, 2);
        mn.desc = insertLongParameter(mn.desc, keyIndex);
        mn.maxLocals = Math.max(mn.maxLocals + 2, insertLocal + 2);
        clazz.markDirty();
        return insertLocal;
    }

    private static int argumentLocalOffset(int access, String desc, int endExclusive) {
        int size = (access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        Type[] args = Type.getArgumentTypes(desc);
        if (endExclusive < 0 || endExclusive > args.length) {
            throw new IllegalStateException("Invalid key parameter index " + endExclusive + " for " + desc);
        }
        for (int i = 0; i < endExclusive; i++) {
            Type arg = args[i];
            size += arg.getSize();
        }
        return size;
    }

    private static void shiftLocals(MethodNode mn, int from, int amount) {
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof VarInsnNode var && var.var >= from) {
                var.var += amount;
            } else if (insn instanceof IincInsnNode iinc && iinc.var >= from) {
                iinc.var += amount;
            }
        }
        if (mn.localVariables != null) {
            for (LocalVariableNode local : mn.localVariables) {
                if (local.index >= from) {
                    local.index += amount;
                }
            }
        }
    }

    private static int instrumentKeyTransfers(PipelineContext pctx, String methodKey, MethodNode mn, int keyLocal) {
        int transfers = 0;
        KeyDispatchContextCensusStats census = contextCensusStats(pctx);
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof InvokeDynamicInsnNode indy) {
                boolean lambdaMetafactory = isLambdaMetafactory(indy);
                census.recordInvokeDynamic(methodKey, lambdaMetafactory);
                IndyKeyRewrite rewrite = rewriteInvokeDynamic(pctx, indy);
                if (rewrite.changed()) {
                    census.recordInvokeDynamicRewrite(methodKey, lambdaMetafactory);
                    InsnList before = new InsnList();
                    if (rewrite.canonicalSeed() == null) {
                        before.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
                    } else {
                        LdcInsnNode rawKey = new LdcInsnNode(incomingRawForCanonical(rewrite.canonicalSeed()));
                        before.add(rawKey);
                        cffKeyLoadTargetSeeds(pctx).put(rawKey, rewrite.canonicalSeed());
                    }
                    markGenerated(pctx, before);
                    mn.instructions.insertBefore(indy, before);
                    transfers++;
                }
                continue;
            }
            if (!(insn instanceof MethodInsnNode call)) continue;
            census.recordBoundaryCall(methodKey, call);
            if (isMethodLookup(call)) {
                rewriteMethodLookup(pctx, mn, call);
                census.recordMethodLookupRewrite(methodKey);
                transfers++;
                continue;
            }
            if (isMethodInvoke(call)) {
                rewriteMethodInvoke(pctx, mn, call, keyLocal);
                census.recordReflectiveInvokeRewrite(methodKey);
                transfers++;
                continue;
            }
            if ("<init>".equals(call.name)) continue;
            String keyedDesc = resolveKeyedDescriptor(pctx, call.owner, call.name, call.desc);
            if (keyedDesc == null) continue;
            int keyIndex = keyIndexMap(pctx).getOrDefault(
                coverageKey(call.owner, call.name, call.desc),
                Type.getArgumentTypes(call.desc).length
            );
            call.desc = keyedDesc;
            insertKeyArgumentBeforeCall(pctx, mn, call, keyLocal, keyIndex);
            census.recordNormalKeyedCallRewrite(methodKey);
            transfers++;
        }
        if (transfers > 0) {
            mn.maxStack = Math.max(mn.maxStack, 8);
        }
        return transfers;
    }

    private static KeyDispatchContextCensusStats contextCensusStats(PipelineContext pctx) {
        KeyDispatchContextCensusStats stats = pctx.getPassData(CONTEXT_CENSUS_STATS);
        if (stats == null) {
            stats = new KeyDispatchContextCensusStats();
            pctx.putPassData(CONTEXT_CENSUS_STATS, stats);
        }
        return stats;
    }

    private static void logContextCensusMethodStats(PipelineContext pctx, String methodKey) {
        KeyDispatchContextCensusStats stats = pctx.getPassData(CONTEXT_CENSUS_STATS);
        if (stats == null) return;
        KeyDispatchContextCensusMethodStats methodStats = stats.methods().get(methodKey);
        if (methodStats == null || !methodStats.hasRelevantBoundary()) return;
        log.info(
            "Key-dispatch context census: method={} indySites={} lambdaMetaSites={} indyRewrites={} lambdaMetaRewrites={} normalKeyedCalls={} reflectiveLookups={} reflectiveInvokes={} asyncCalls={} stackTraceCalls={} exceptionCalls={}",
            methodKey,
            methodStats.invokeDynamicSites(),
            methodStats.lambdaMetafactorySites(),
            methodStats.invokeDynamicRewrites(),
            methodStats.lambdaMetafactoryRewrites(),
            methodStats.normalKeyedCallRewrites(),
            methodStats.methodLookupRewrites(),
            methodStats.reflectiveInvokeRewrites(),
            methodStats.asyncBoundaryCalls(),
            methodStats.stackTraceCalls(),
            methodStats.exceptionBoundaryCalls()
        );
    }

    private static boolean isAsyncBoundaryCall(MethodInsnNode call) {
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

    private static boolean isStackTraceCall(MethodInsnNode call) {
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

    private static boolean isExceptionBoundaryCall(MethodInsnNode call) {
        return "java/lang/Throwable".equals(call.owner)
            || call.owner.endsWith("Exception")
            || call.owner.endsWith("Error");
    }

    private static IndyKeyRewrite rewriteInvokeDynamic(PipelineContext pctx, InvokeDynamicInsnNode indy) {
        if (isLambdaMetafactory(indy)
            && indy.bsmArgs.length > 1
            && indy.bsmArgs[1] instanceof Handle lambdaHandle) {
            String keyedDesc = keyedDescMap(pctx).get(
                coverageKey(lambdaHandle.getOwner(), lambdaHandle.getName(), lambdaHandle.getDesc())
            );
            if (keyedDesc != null) {
                indy.bsmArgs[1] = new Handle(
                    lambdaHandle.getTag(),
                    lambdaHandle.getOwner(),
                    lambdaHandle.getName(),
                    keyedDesc,
                    lambdaHandle.isInterface()
                );
                indy.desc = appendLongParameter(indy.desc);
                Long targetSeed = findMethodSeed(
                    pctx,
                    coverageKey(lambdaHandle.getOwner(), lambdaHandle.getName(), keyedDesc)
                );
                if (targetSeed == null) {
                    L1Class targetClass = pctx.classMap().get(lambdaHandle.getOwner());
                    L1Method targetMethod = targetClass == null
                        ? null
                        : findAsmMethod(targetClass, lambdaHandle.getName(), keyedDesc);
                    if (targetClass != null && targetMethod != null && targetMethod.hasCode()) {
                        targetSeed = methodSeed(pctx, targetClass, targetMethod, targetMethod.asmNode());
                    } else if (targetClass != null) {
                        targetMethod = findAsmMethod(targetClass, lambdaHandle.getName(), lambdaHandle.getDesc());
                        if (targetMethod != null && targetMethod.hasCode()) {
                            targetSeed = pendingKeyedMethodSeed(pctx, targetClass, targetMethod, keyedDesc);
                        }
                    }
                }
                return new IndyKeyRewrite(true, targetSeed);
            }
        }
        boolean changed = false;
        for (int i = 0; i < indy.bsmArgs.length; i++) {
            Object arg = indy.bsmArgs[i];
            if (!(arg instanceof Handle handle)) continue;
            String keyedDesc = keyedDescMap(pctx).get(coverageKey(handle.getOwner(), handle.getName(), handle.getDesc()));
            if (keyedDesc == null) continue;
            indy.bsmArgs[i] = new Handle(handle.getTag(), handle.getOwner(), handle.getName(),
                keyedDesc, handle.isInterface());
            changed = true;
        }
        if (changed) {
            indy.desc = appendLongParameter(indy.desc);
        }
        return new IndyKeyRewrite(changed, null);
    }

    public static long incomingRawForCanonical(long targetSeed) {
        return incomingRawFor(targetSeed, targetSeed, INCOMING_KEY_MIX_MASK);
    }

    public static long incomingRawFor(long targetValue, long seed, long mask) {
        return ((targetValue ^ incomingMixXorB(seed, mask)) - incomingMixAddend(seed, mask)) ^
            incomingMixXorA(seed, mask);
    }

    private static long incomingMixXorA(long seed, long mask) {
        return JvmPassBytecode.mix(seed ^ 0x474B4D49584131L, Long.rotateLeft(mask, 17));
    }

    private static long incomingMixAddend(long seed, long mask) {
        long value = JvmPassBytecode.mix(seed + 0x474B4D41444431L, mask ^ 0x4B4D4144444D534BL);
        return value == 0L ? 0x4B4D4144444D534BL : value;
    }

    private static long incomingMixXorB(long seed, long mask) {
        return JvmPassBytecode.mix(seed ^ Long.rotateLeft(mask, 41), 0x474B4D49584231L);
    }

    private static boolean isLambdaMetafactory(InvokeDynamicInsnNode indy) {
        return indy.bsm != null
            && "java/lang/invoke/LambdaMetafactory".equals(indy.bsm.getOwner())
            && ("metafactory".equals(indy.bsm.getName()) || "altMetafactory".equals(indy.bsm.getName()))
            && indy.bsmArgs.length > 0
            && indy.bsmArgs[0] instanceof Type;
    }

    private static String resolveKeyedDescriptor(PipelineContext pctx, String owner, String name, String desc) {
        String direct = keyedDescMap(pctx).get(coverageKey(owner, name, desc));
        if (direct != null) return direct;
        L1Class clazz = pctx.classMap().get(owner);
        if (clazz == null) return null;
        String fromSuper = resolveKeyedDescriptorInSuper(pctx, clazz.superName(), name, desc);
        if (fromSuper != null) return fromSuper;
        return resolveKeyedDescriptorInInterfaces(pctx, clazz.interfaces(), name, desc);
    }

    private static String resolveKeyedDescriptorInSuper(PipelineContext pctx, String owner, String name, String desc) {
        if (owner == null) return null;
        String direct = keyedDescMap(pctx).get(coverageKey(owner, name, desc));
        if (direct != null) return direct;
        L1Class clazz = pctx.classMap().get(owner);
        if (clazz == null) return null;
        String fromSuper = resolveKeyedDescriptorInSuper(pctx, clazz.superName(), name, desc);
        if (fromSuper != null) return fromSuper;
        return resolveKeyedDescriptorInInterfaces(pctx, clazz.interfaces(), name, desc);
    }

    private static String resolveKeyedDescriptorInInterfaces(PipelineContext pctx, List<String> interfaces,
            String name, String desc) {
        for (String iface : interfaces) {
            String direct = keyedDescMap(pctx).get(coverageKey(iface, name, desc));
            if (direct != null) return direct;
            L1Class clazz = pctx.classMap().get(iface);
            if (clazz == null) continue;
            String nested = resolveKeyedDescriptorInInterfaces(pctx, clazz.interfaces(), name, desc);
            if (nested != null) return nested;
        }
        return null;
    }

    private static boolean isMethodLookup(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKEVIRTUAL
            && "java/lang/Class".equals(call.owner)
            && ("getMethod".equals(call.name) || "getDeclaredMethod".equals(call.name))
            && "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;".equals(call.desc);
    }

    private static boolean isMethodInvoke(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKEVIRTUAL
            && "java/lang/reflect/Method".equals(call.owner)
            && "invoke".equals(call.name)
            && "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;".equals(call.desc);
    }

    private static void rewriteMethodLookup(PipelineContext pctx, MethodNode mn, MethodInsnNode call) {
        recordLiteralReflectiveTargets(pctx, call);

        boolean literalNullParameters = hasLiteralNullMethodLookupParameters(call);
        int paramsLocal = allocateLocal(mn, Type.getType(Class[].class));
        int nameLocal = allocateLocal(mn, Type.getType(String.class));
        int classLocal = allocateLocal(mn, Type.getType(Class.class));
        int newParamsLocal = allocateLocal(mn, Type.getType(Class[].class));
        LabelNode copyExisting = new LabelNode();
        LabelNode storeLongType = new LabelNode();
        InsnList before = new InsnList();
        before.add(new VarInsnNode(Opcodes.ASTORE, paramsLocal));
        before.add(new VarInsnNode(Opcodes.ASTORE, nameLocal));
        before.add(new VarInsnNode(Opcodes.ASTORE, classLocal));

        if (literalNullParameters) {
            JvmPassBytecode.pushInt(before, 1);
            before.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Class"));
            before.add(new VarInsnNode(Opcodes.ASTORE, newParamsLocal));
        } else {
            before.add(new VarInsnNode(Opcodes.ALOAD, paramsLocal));
            before.add(new JumpInsnNode(Opcodes.IFNONNULL, copyExisting));
            JvmPassBytecode.pushInt(before, 1);
            before.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Class"));
            before.add(new VarInsnNode(Opcodes.ASTORE, newParamsLocal));
            before.add(new JumpInsnNode(Opcodes.GOTO, storeLongType));

            before.add(copyExisting);
            before.add(new VarInsnNode(Opcodes.ALOAD, paramsLocal));
            before.add(new InsnNode(Opcodes.ARRAYLENGTH));
            JvmPassBytecode.pushInt(before, 1);
            before.add(new InsnNode(Opcodes.IADD));
            before.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Class"));
            before.add(new VarInsnNode(Opcodes.ASTORE, newParamsLocal));
            before.add(new VarInsnNode(Opcodes.ALOAD, paramsLocal));
            JvmPassBytecode.pushInt(before, 0);
            before.add(new VarInsnNode(Opcodes.ALOAD, newParamsLocal));
            JvmPassBytecode.pushInt(before, 0);
            before.add(new VarInsnNode(Opcodes.ALOAD, paramsLocal));
            before.add(new InsnNode(Opcodes.ARRAYLENGTH));
            before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "arraycopy",
                "(Ljava/lang/Object;ILjava/lang/Object;II)V", false));
        }

        before.add(storeLongType);
        before.add(new VarInsnNode(Opcodes.ALOAD, newParamsLocal));
        before.add(new VarInsnNode(Opcodes.ALOAD, newParamsLocal));
        before.add(new InsnNode(Opcodes.ARRAYLENGTH));
        JvmPassBytecode.pushInt(before, 1);
        before.add(new InsnNode(Opcodes.ISUB));
        before.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/Long", "TYPE", "Ljava/lang/Class;"));
        before.add(new InsnNode(Opcodes.AASTORE));

        before.add(new VarInsnNode(Opcodes.ALOAD, classLocal));
        before.add(new VarInsnNode(Opcodes.ALOAD, nameLocal));
        before.add(new VarInsnNode(Opcodes.ALOAD, newParamsLocal));
        markGenerated(pctx, before);
        mn.instructions.insertBefore(call, before);
    }

    private static boolean hasLiteralNullMethodLookupParameters(MethodInsnNode call) {
        AbstractInsnNode params = previousReal(call.getPrevious());
        if (params instanceof TypeInsnNode cast
                && params.getOpcode() == Opcodes.CHECKCAST
                && "[Ljava/lang/Class;".equals(cast.desc)) {
            params = previousReal(params.getPrevious());
        }
        return params != null && params.getOpcode() == Opcodes.ACONST_NULL;
    }

    private static void recordLiteralReflectiveTargets(PipelineContext pctx, MethodInsnNode call) {
        ReflectiveLookup lookup = literalNoArgMethodLookup(call);
        if (lookup == null) return;
        L1Class clazz = pctx.classMap().get(lookup.owner());
        if (clazz == null) return;
        for (L1Method method : clazz.methods()) {
            if (!method.name().equals(lookup.name())) continue;
            Type[] args = Type.getArgumentTypes(method.descriptor());
            if (args.length == 1 && Type.LONG_TYPE.equals(args[0])) {
                reflectiveKeyedEntries(pctx).add(
                    coverageKey(clazz.name(), method.name(), method.descriptor())
                );
                continue;
            }
            if (args.length != 0) continue;
            String keyedDesc = keyedDescMap(pctx).get(
                coverageKey(clazz.name(), method.name(), method.descriptor())
            );
            if (keyedDesc != null) {
                reflectiveKeyedEntries(pctx).add(coverageKey(clazz.name(), method.name(), keyedDesc));
            }
        }
    }

    private static ReflectiveLookup literalNoArgMethodLookup(MethodInsnNode call) {
        AbstractInsnNode params = previousReal(call.getPrevious());
        if (params instanceof TypeInsnNode cast
                && params.getOpcode() == Opcodes.CHECKCAST
                && "[Ljava/lang/Class;".equals(cast.desc)) {
            params = previousReal(params.getPrevious());
        }
        if (params == null || params.getOpcode() != Opcodes.ACONST_NULL) return null;
        AbstractInsnNode nameInsn = previousReal(params.getPrevious());
        if (!(nameInsn instanceof LdcInsnNode nameLdc) || !(nameLdc.cst instanceof String name)) {
            return null;
        }
        AbstractInsnNode classInsn = previousReal(nameInsn.getPrevious());
        if (!(classInsn instanceof LdcInsnNode classLdc) || !(classLdc.cst instanceof Type type)) {
            return null;
        }
        if (type.getSort() != Type.OBJECT) return null;
        return new ReflectiveLookup(type.getInternalName(), name);
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode start) {
        for (AbstractInsnNode insn = start; insn != null; insn = insn.getPrevious()) {
            if (insn.getOpcode() >= 0) return insn;
        }
        return null;
    }

    private record ReflectiveLookup(String owner, String name) {
    }

    private static void rewriteMethodInvoke(PipelineContext pctx, MethodNode mn, MethodInsnNode call, int keyLocal) {
        int argsLocal = allocateLocal(mn, Type.getType(Object[].class));
        int targetLocal = allocateLocal(mn, Type.getType(Object.class));
        int methodLocal = allocateLocal(mn, Type.getType(Object.class));
        int newArgsLocal = allocateLocal(mn, Type.getType(Object[].class));
        Long targetSeed = reflectiveInvokeTargetSeed(pctx, call);
        LabelNode copyExisting = new LabelNode();
        LabelNode storeKey = new LabelNode();
        InsnList before = new InsnList();
        before.add(new VarInsnNode(Opcodes.ASTORE, argsLocal));
        before.add(new VarInsnNode(Opcodes.ASTORE, targetLocal));
        before.add(new VarInsnNode(Opcodes.ASTORE, methodLocal));

        before.add(new VarInsnNode(Opcodes.ALOAD, argsLocal));
        before.add(new JumpInsnNode(Opcodes.IFNONNULL, copyExisting));
        JvmPassBytecode.pushInt(before, 1);
        before.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        before.add(new VarInsnNode(Opcodes.ASTORE, newArgsLocal));
        before.add(new JumpInsnNode(Opcodes.GOTO, storeKey));

        before.add(copyExisting);
        before.add(new VarInsnNode(Opcodes.ALOAD, argsLocal));
        before.add(new InsnNode(Opcodes.ARRAYLENGTH));
        JvmPassBytecode.pushInt(before, 1);
        before.add(new InsnNode(Opcodes.IADD));
        before.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        before.add(new VarInsnNode(Opcodes.ASTORE, newArgsLocal));
        before.add(new VarInsnNode(Opcodes.ALOAD, argsLocal));
        JvmPassBytecode.pushInt(before, 0);
        before.add(new VarInsnNode(Opcodes.ALOAD, newArgsLocal));
        JvmPassBytecode.pushInt(before, 0);
        before.add(new VarInsnNode(Opcodes.ALOAD, argsLocal));
        before.add(new InsnNode(Opcodes.ARRAYLENGTH));
        before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "arraycopy",
            "(Ljava/lang/Object;ILjava/lang/Object;II)V", false));

        before.add(storeKey);
        before.add(new VarInsnNode(Opcodes.ALOAD, newArgsLocal));
        before.add(new VarInsnNode(Opcodes.ALOAD, newArgsLocal));
        before.add(new InsnNode(Opcodes.ARRAYLENGTH));
        JvmPassBytecode.pushInt(before, 1);
        before.add(new InsnNode(Opcodes.ISUB));
        VarInsnNode keyLoad = new VarInsnNode(Opcodes.LLOAD, keyLocal);
        before.add(keyLoad);
        if (targetSeed != null) {
            cffKeyLoadTargetSeeds(pctx).put(keyLoad, targetSeed);
        }
        before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf",
            "(J)Ljava/lang/Long;", false));
        before.add(new InsnNode(Opcodes.AASTORE));

        before.add(new VarInsnNode(Opcodes.ALOAD, methodLocal));
        before.add(new VarInsnNode(Opcodes.ALOAD, targetLocal));
        before.add(new VarInsnNode(Opcodes.ALOAD, newArgsLocal));
        markGenerated(pctx, before);
        mn.instructions.insertBefore(call, before);
    }

    private static Long reflectiveInvokeTargetSeed(PipelineContext pctx, MethodInsnNode invokeCall) {
        MethodInsnNode lookupCall = previousReflectiveLookup(invokeCall);
        if (lookupCall == null) return null;
        ReflectiveLookup lookup = reflectiveLookupTarget(lookupCall);
        if (lookup == null) return null;
        L1Class clazz = pctx.classMap().get(lookup.owner());
        if (clazz == null) return null;
        L1Method matched = null;
        for (L1Method method : clazz.methods()) {
            if (!method.name().equals(lookup.name()) || !method.hasCode()) continue;
            Type[] args = Type.getArgumentTypes(method.descriptor());
            if (args.length == 0 || !Type.LONG_TYPE.equals(args[args.length - 1])) continue;
            if (matched == null || method.descriptor().compareTo(matched.descriptor()) < 0) {
                matched = method;
            }
        }
        if (matched == null) return null;
        Long seed = findMethodSeed(pctx, coverageKey(clazz.name(), matched.name(), matched.descriptor()));
        return seed != null ? seed : methodSeed(pctx, clazz, matched, matched.asmNode());
    }

    private static MethodInsnNode previousReflectiveLookup(MethodInsnNode invokeCall) {
        int scanned = 0;
        for (AbstractInsnNode scan = invokeCall.getPrevious(); scan != null && scanned++ < 256; scan = scan.getPrevious()) {
            if (!(scan instanceof MethodInsnNode call)) continue;
            if ("java/lang/Class".equals(call.owner)
                && ("getMethod".equals(call.name) || "getDeclaredMethod".equals(call.name))
                && "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;".equals(call.desc)) {
                return call;
            }
        }
        return null;
    }

    private static ReflectiveLookup reflectiveLookupTarget(MethodInsnNode lookupCall) {
        String name = null;
        String owner = null;
        int scanned = 0;
        for (AbstractInsnNode scan = lookupCall.getPrevious(); scan != null && scanned++ < 96; scan = scan.getPrevious()) {
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
        return name != null && owner != null ? new ReflectiveLookup(owner, name) : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<AbstractInsnNode, Long> cffKeyLoadTargetSeeds(PipelineContext pctx) {
        Map<AbstractInsnNode, Long> map = pctx.getPassData(JvmMethodParameterObfuscationPass.CFF_KEY_LOAD_TARGET_SEED);
        if (map == null) {
            map = new IdentityHashMap<>();
            pctx.putPassData(JvmMethodParameterObfuscationPass.CFF_KEY_LOAD_TARGET_SEED, map);
        }
        return map;
    }

    private static void insertKeyArgumentBeforeCall(
        PipelineContext pctx,
        MethodNode mn,
        MethodInsnNode call,
        int keyLocal,
        int keyIndex
    ) {
        Type[] keyedArgs = Type.getArgumentTypes(call.desc);
        Type[] originalArgs = new Type[keyedArgs.length - 1];
        for (int i = 0, j = 0; i < keyedArgs.length; i++) {
            if (i == keyIndex) continue;
            originalArgs[j++] = keyedArgs[i];
        }
        int[] locals = new int[originalArgs.length];
        for (int i = originalArgs.length - 1; i >= 0; i--) {
            locals[i] = allocateLocal(mn, originalArgs[i]);
        }
        int receiverLocal = -1;
        boolean isStatic = call.getOpcode() == Opcodes.INVOKESTATIC;
        if (!isStatic) {
            receiverLocal = allocateLocal(mn, Type.getType(Object.class));
        }
        InsnList before = new InsnList();
        for (int i = originalArgs.length - 1; i >= 0; i--) {
            before.add(new VarInsnNode(originalArgs[i].getOpcode(Opcodes.ISTORE), locals[i]));
        }
        if (!isStatic) {
            before.add(new VarInsnNode(Opcodes.ASTORE, receiverLocal));
            before.add(new VarInsnNode(Opcodes.ALOAD, receiverLocal));
        }
        for (int i = 0; i < keyedArgs.length; i++) {
            if (i == keyIndex) {
                before.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
            } else {
                int originalIndex = i < keyIndex ? i : i - 1;
                before.add(new VarInsnNode(originalArgs[originalIndex].getOpcode(Opcodes.ILOAD), locals[originalIndex]));
            }
        }
        markGenerated(pctx, before);
        mn.instructions.insertBefore(call, before);
    }

    private static int allocateLocal(MethodNode mn, Type type) {
        int local = mn.maxLocals;
        mn.maxLocals += type.getSize();
        return local;
    }

    public static void emitIncomingKeyMix(InsnList insns, int keyLocal, long seed, long mask) {
        emitIncomingKeyMix(insns, keyLocal, keyLocal, seed, mask);
    }

    public static void emitIncomingKeyMix(InsnList insns, int sourceLocal, int targetLocal, long seed, long mask) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, sourceLocal));
        emitIncomingKeyMixFromStack(insns, targetLocal, seed, mask);
    }

    private static void emitIncomingKeyMixFromStack(InsnList insns, int targetLocal, long seed, long mask) {
        JvmPassBytecode.pushLong(insns, incomingMixXorA(seed, mask));
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, incomingMixAddend(seed, mask));
        insns.add(new InsnNode(Opcodes.LADD));
        JvmPassBytecode.pushLong(insns, incomingMixXorB(seed, mask));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LSTORE, targetLocal));
    }

    private static String appendLongParameter(String desc) {
        return insertLongParameter(desc, Type.getArgumentTypes(desc).length);
    }

    private static String insertLongParameter(String desc, int index) {
        Type returnType = Type.getReturnType(desc);
        Type[] args = Type.getArgumentTypes(desc);
        if (index < 0 || index > args.length) {
            throw new IllegalStateException("Invalid key parameter index " + index + " for " + desc);
        }
        Type[] keyed = new Type[args.length + 1];
        System.arraycopy(args, 0, keyed, 0, index);
        keyed[index] = Type.LONG_TYPE;
        System.arraycopy(args, index, keyed, index + 1, args.length - index);
        return Type.getMethodDescriptor(returnType, keyed);
    }

    public static String coverageKey(String owner, String name, String desc) {
        return owner + "." + name + desc;
    }

    public static final class KeyDispatchContextCensusStats {
        private final Map<String, KeyDispatchContextCensusMethodStats> methods = new LinkedHashMap<>();
        private int invokeDynamicSites;
        private int lambdaMetafactorySites;
        private int invokeDynamicRewrites;
        private int lambdaMetafactoryRewrites;
        private int normalKeyedCallRewrites;
        private int methodLookupRewrites;
        private int reflectiveInvokeRewrites;
        private int asyncBoundaryCalls;
        private int stackTraceCalls;
        private int exceptionBoundaryCalls;

        private void recordInvokeDynamic(String methodKey, boolean lambdaMetafactory) {
            invokeDynamicSites++;
            if (lambdaMetafactory) lambdaMetafactorySites++;
            method(methodKey).recordInvokeDynamic(lambdaMetafactory);
        }

        private void recordInvokeDynamicRewrite(String methodKey, boolean lambdaMetafactory) {
            invokeDynamicRewrites++;
            if (lambdaMetafactory) lambdaMetafactoryRewrites++;
            method(methodKey).recordInvokeDynamicRewrite(lambdaMetafactory);
        }

        private void recordBoundaryCall(String methodKey, MethodInsnNode call) {
            boolean async = isAsyncBoundaryCall(call);
            boolean stackTrace = isStackTraceCall(call);
            boolean exception = isExceptionBoundaryCall(call);
            if (async) asyncBoundaryCalls++;
            if (stackTrace) stackTraceCalls++;
            if (exception) exceptionBoundaryCalls++;
            method(methodKey).recordBoundaryCall(async, stackTrace, exception);
        }

        private void recordNormalKeyedCallRewrite(String methodKey) {
            normalKeyedCallRewrites++;
            method(methodKey).recordNormalKeyedCallRewrite();
        }

        private void recordMethodLookupRewrite(String methodKey) {
            methodLookupRewrites++;
            method(methodKey).recordMethodLookupRewrite();
        }

        private void recordReflectiveInvokeRewrite(String methodKey) {
            reflectiveInvokeRewrites++;
            method(methodKey).recordReflectiveInvokeRewrite();
        }

        private KeyDispatchContextCensusMethodStats method(String methodKey) {
            return methods.computeIfAbsent(methodKey, KeyDispatchContextCensusMethodStats::new);
        }

        public Map<String, KeyDispatchContextCensusMethodStats> methods() {
            return methods;
        }

        public int invokeDynamicSites() {
            return invokeDynamicSites;
        }

        public int lambdaMetafactorySites() {
            return lambdaMetafactorySites;
        }

        public int invokeDynamicRewrites() {
            return invokeDynamicRewrites;
        }

        public int lambdaMetafactoryRewrites() {
            return lambdaMetafactoryRewrites;
        }

        public int normalKeyedCallRewrites() {
            return normalKeyedCallRewrites;
        }

        public int methodLookupRewrites() {
            return methodLookupRewrites;
        }

        public int reflectiveInvokeRewrites() {
            return reflectiveInvokeRewrites;
        }

        public int asyncBoundaryCalls() {
            return asyncBoundaryCalls;
        }

        public int stackTraceCalls() {
            return stackTraceCalls;
        }

        public int exceptionBoundaryCalls() {
            return exceptionBoundaryCalls;
        }
    }

    public static final class KeyDispatchContextCensusMethodStats {
        private final String methodKey;
        private int invokeDynamicSites;
        private int lambdaMetafactorySites;
        private int invokeDynamicRewrites;
        private int lambdaMetafactoryRewrites;
        private int normalKeyedCallRewrites;
        private int methodLookupRewrites;
        private int reflectiveInvokeRewrites;
        private int asyncBoundaryCalls;
        private int stackTraceCalls;
        private int exceptionBoundaryCalls;

        private KeyDispatchContextCensusMethodStats(String methodKey) {
            this.methodKey = methodKey;
        }

        private void recordInvokeDynamic(boolean lambdaMetafactory) {
            invokeDynamicSites++;
            if (lambdaMetafactory) lambdaMetafactorySites++;
        }

        private void recordInvokeDynamicRewrite(boolean lambdaMetafactory) {
            invokeDynamicRewrites++;
            if (lambdaMetafactory) lambdaMetafactoryRewrites++;
        }

        private void recordBoundaryCall(boolean async, boolean stackTrace, boolean exception) {
            if (async) asyncBoundaryCalls++;
            if (stackTrace) stackTraceCalls++;
            if (exception) exceptionBoundaryCalls++;
        }

        private void recordNormalKeyedCallRewrite() {
            normalKeyedCallRewrites++;
        }

        private void recordMethodLookupRewrite() {
            methodLookupRewrites++;
        }

        private void recordReflectiveInvokeRewrite() {
            reflectiveInvokeRewrites++;
        }

        private boolean hasRelevantBoundary() {
            return invokeDynamicSites > 0
                || normalKeyedCallRewrites > 0
                || methodLookupRewrites > 0
                || reflectiveInvokeRewrites > 0
                || asyncBoundaryCalls > 0
                || stackTraceCalls > 0
                || exceptionBoundaryCalls > 0;
        }

        public String methodKey() {
            return methodKey;
        }

        public int invokeDynamicSites() {
            return invokeDynamicSites;
        }

        public int lambdaMetafactorySites() {
            return lambdaMetafactorySites;
        }

        public int invokeDynamicRewrites() {
            return invokeDynamicRewrites;
        }

        public int lambdaMetafactoryRewrites() {
            return lambdaMetafactoryRewrites;
        }

        public int normalKeyedCallRewrites() {
            return normalKeyedCallRewrites;
        }

        public int methodLookupRewrites() {
            return methodLookupRewrites;
        }

        public int reflectiveInvokeRewrites() {
            return reflectiveInvokeRewrites;
        }

        public int asyncBoundaryCalls() {
            return asyncBoundaryCalls;
        }

        public int stackTraceCalls() {
            return stackTraceCalls;
        }

        public int exceptionBoundaryCalls() {
            return exceptionBoundaryCalls;
        }
    }

    private record IndyKeyRewrite(boolean changed, Long canonicalSeed) {}

}
