package dev.nekoobfuscator.transforms.flow;

import dev.nekoobfuscator.api.config.TransformConfig;
import dev.nekoobfuscator.api.transform.*;
import dev.nekoobfuscator.core.ir.l1.*;
import dev.nekoobfuscator.core.ir.l2.*;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import dev.nekoobfuscator.core.util.AsmUtil;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.*;

/**
 * Control Flow Flattening: converts method CFG into a state-machine dispatcher.
 * Each basic block becomes a case in a switch statement.
 */
public final class ControlFlowFlatteningPass implements TransformPass {
    private static final String FLATTENED_METHODS_KEY = "controlFlowFlattening.methods";
    private static final String FLOW_KEY_VALUES_KEY = "controlFlowFlattening.flowKeys";
    private static final String INTEGER_OWNER = "java/lang/Integer";
    private static final String CONTEXT_OWNER = "dev/nekoobfuscator/runtime/NekoContext";
    private static final String ZKM_STYLE_OPTION = "zkmStyle";
    private static final String TAIL_CHAIN_INTENSITY_OPTION = "tailChainIntensity";
    private static final String TRY_CATCH_TAIL_CHAIN_MULTIPLIER_OPTION = "tryCatchTailChainMultiplier";
    private static final String ALLOW_TRY_CATCH_METHODS_OPTION = "allowTryCatchMethods";
    private static final String TRY_CATCH_MAIN_ONLY_OPTION = "tryCatchMainOnly";
    private static final String MAX_TRY_CATCH_BLOCKS_OPTION = "maxTryCatchBlocks";
    private static final String TRY_CATCH_BRANCH_BONUS_OPTION = "tryCatchBranchBonus";
    private static final String TRY_CATCH_INSTRUCTION_BONUS_OPTION = "tryCatchInstructionBonus";
    private static final String ENTRYPOINT_TAIL_CHAIN_MULTIPLIER_OPTION = "entrypointTailChainMultiplier";
    private static final String ENTRYPOINT_MAX_TRY_CATCH_BLOCKS_OPTION = "entrypointMaxTryCatchBlocks";
    private static final String ENTRYPOINT_BRANCH_BONUS_OPTION = "entrypointBranchBonus";
    private static final String ENTRYPOINT_INSTRUCTION_BONUS_OPTION = "entrypointInstructionBonus";
    private static final String ALLOW_SWITCH_METHODS_OPTION = "allowSwitchMethods";
    private static final String ALLOW_MONITOR_METHODS_OPTION = "allowMonitorMethods";
    private static final String MAX_INSTRUCTION_COUNT_OPTION = "maxApplicableInstructionCount";
    private static final String MAX_BACKWARD_BRANCHES_OPTION = "maxBackwardBranches";
    private static final String MAX_BRANCHES_OPTION = "maxBranchCount";
    private static final InsnNode AFTER_HANDLER_SYNC_ANCHOR = new InsnNode(Opcodes.NOP);

    @Override public String id() { return "controlFlowFlattening"; }
    @Override public String name() { return "Control Flow Flattening"; }
    @Override public TransformPhase phase() { return TransformPhase.TRANSFORM; }
    @Override public IRLevel requiredLevel() { return IRLevel.L1; }

    private dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine keyEngine;

    @Override
    public void transformClass(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        keyEngine = pctx.getPassData("keyEngine");
        if (keyEngine == null) {
            keyEngine = new dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine(pctx.masterSeed());
            pctx.putPassData("keyEngine", keyEngine);
        }
    }

    @Override
    public boolean isApplicable(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        L1Method method = pctx.currentL1Method();
        if (method == null) return true;
        return method.hasCode()
            && method.instructionCount() > 10
            && !method.isConstructor()
            && !method.isClassInit()
            && isStructureSafe(method, pctx);
    }

    @Override
    public void transformMethod(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        L1Method method = pctx.currentL1Method();
        L1Class clazz = pctx.currentL1Class();

        if (!isApplicable(ctx)) return;

        double intensity = pctx.config().getTransformIntensity("controlFlowFlattening");
        if (pctx.random().nextDouble() > intensity) return;

        ControlFlowGraph cfg = pctx.getCFG(method);
        List<BasicBlock> blocks = cfg.blocks();
        List<BasicBlock> dispatchBlocks = new ArrayList<>();
        List<BasicBlock> handlerBlocks = new ArrayList<>();
        partitionBlocks(blocks, dispatchBlocks, handlerBlocks);
        if (dispatchBlocks.size() < 3) return;

        long classKey = keyEngine.deriveClassKey(clazz);
        long methodKey = keyEngine.deriveMethodKey(method, classKey);
        long methodFlowSeed = deriveMethodFlowSeed(methodKey);
        int stateMask = foldMethodKey(methodKey ^ 0x4E454B4F4C4FL);
        int stateDelta = foldMethodKey(Long.rotateLeft(methodKey, 19) ^ 0xC0DEC0DE5EEDL);
        int stateRotate = 5 + Math.floorMod((int) (methodKey >>> 11), 19);
        boolean zkmStyle = isZkmStyleEnabled(pctx);
        double tailChainIntensity = tailChainIntensity(pctx, method);

        // Assign random state numbers
        Map<BasicBlock, Integer> stateMap = new HashMap<>();
        Set<Integer> usedStates = new HashSet<>();
        for (BasicBlock block : dispatchBlocks) {
            int state = pctx.random().nextIntExcluding(usedStates);
            stateMap.put(block, state);
            usedStates.add(state);
        }

        Map<BasicBlock, Long> flowKeyMap = new HashMap<>();
        for (BasicBlock block : blocks) {
            Integer state = stateMap.get(block);
            long flowSeed = state != null
                ? deriveBlockFlowKey(methodFlowSeed, state)
                : dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.finalize_(
                    dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.mix(methodFlowSeed,
                        0x4E454B4F00000000L ^ block.id()));
            flowKeyMap.put(block, flowSeed);
        }

        int initialState = stateMap.get(cfg.entryBlock());
        long initialFlowKey = flowKeyMap.getOrDefault(cfg.entryBlock(), 0L);

        MethodNode mn = method.asmNode();
        IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> analyzedFrames = analyzeFrames(clazz.name(), mn);
        IdentityHashMap<AbstractInsnNode, Integer> stackHeights = extractStackHeights(analyzedFrames);
        Map<BasicBlock, List<StackSlotKind>> blockEntryStacks = analyzeBlockEntryStacks(dispatchBlocks, analyzedFrames);
        Map<BasicBlock, List<LocalSlotState>> blockEntryLocals = analyzeBlockEntryLocals(blocks, dispatchBlocks, analyzedFrames, originalMaxLocals(method));
        InsnList newInsns = new InsnList();
        int originalMaxLocals = mn.maxLocals;
        int nextLocal = mn.maxLocals;
        int flowKeyVar = nextLocal;
        nextLocal += 2;
        int flowMixVar = nextLocal++;
        int encodedStateVar = nextLocal++;
        int dispatchStateVar = nextLocal++;
        int stateMaskVar = nextLocal++;
        int stateDeltaVar = nextLocal++;
        int tailSeedVar = nextLocal++;
        int tailFlagVar = nextLocal++;
        Map<BasicBlock, Integer> blockSpillBases = new IdentityHashMap<>();
        nextLocal = allocateSpillLocals(dispatchBlocks, blockEntryStacks, blockSpillBases, nextLocal);
        Map<BasicBlock, Integer> blockLocalSpillBases = new IdentityHashMap<>();
        nextLocal = allocateLocalSpillLocals(dispatchBlocks, blockEntryLocals, blockLocalSpillBases, nextLocal);
        mn.maxLocals = nextLocal;

        LabelNode loopStart = new LabelNode();
        LabelNode loopEnd = new LabelNode();

        // Label remap for internal jumps within blocks
        Map<LabelNode, LabelNode> labelRemap = new HashMap<>();
        for (BasicBlock block : blocks) {
            for (AbstractInsnNode insn : block.instructions()) {
                if (insn instanceof LabelNode origLabel) {
                    labelRemap.put(origLabel, new LabelNode());
                }
            }
        }

        emitOriginalLocalInitialization(newInsns, method, originalMaxLocals);
        initializeSyntheticSpillLocals(newInsns, blockEntryStacks, blockSpillBases, blockEntryLocals, blockLocalSpillBases);
        spillLocalsForTarget(newInsns, cfg.entryBlock(), blockEntryLocals, blockLocalSpillBases);

        newInsns.add(AsmUtil.pushIntAny(stateMask));
        newInsns.add(new VarInsnNode(Opcodes.ISTORE, stateMaskVar));
        newInsns.add(AsmUtil.pushIntAny(stateDelta));
        newInsns.add(new VarInsnNode(Opcodes.ISTORE, stateDeltaVar));
        newInsns.add(AsmUtil.pushIntAny(foldMethodKey(Long.rotateRight(methodKey, 27) ^ 0x5A4B4D7E1F2DL)));
        newInsns.add(new VarInsnNode(Opcodes.ISTORE, tailSeedVar));
        newInsns.add(new InsnNode(Opcodes.ICONST_0));
        newInsns.add(new VarInsnNode(Opcodes.ISTORE, tailFlagVar));
        emitFlowKeyStore(newInsns, initialFlowKey, flowKeyVar, flowMixVar);
        emitEncodedStateStore(newInsns, initialState, encodedStateVar, stateMaskVar, stateDeltaVar,
            flowMixVar, stateRotate, 0);

        newInsns.add(loopStart);
        emitRuntimeFlowContextSync(newInsns, flowKeyVar);
        emitStateDecode(newInsns, encodedStateVar, dispatchStateVar, stateMaskVar, stateDeltaVar,
            flowMixVar, stateRotate);
        newInsns.add(new VarInsnNode(Opcodes.ILOAD, dispatchStateVar));

        // Build sorted lookupswitch
        int[] keys = new int[dispatchBlocks.size()];
        LabelNode[] switchLabels = new LabelNode[dispatchBlocks.size()];
        Map<BasicBlock, LabelNode> blockCaseLabels = new HashMap<>();

        List<Map.Entry<BasicBlock, Integer>> entries = new ArrayList<>(stateMap.entrySet());
        entries.sort(Comparator.comparingInt(Map.Entry::getValue));

        for (int i = 0; i < entries.size(); i++) {
            keys[i] = entries.get(i).getValue();
            LabelNode label = new LabelNode();
            switchLabels[i] = label;
            blockCaseLabels.put(entries.get(i).getKey(), label);
        }

        LabelNode dispatcherDefault = blockCaseLabels.getOrDefault(cfg.entryBlock(), switchLabels[0]);
        newInsns.add(new LookupSwitchInsnNode(dispatcherDefault, keys, switchLabels));

        List<TailChain> tailChains = new ArrayList<>();
        if (!handlerBlocks.isEmpty()) {
            Set<BasicBlock> handlerBlockSet = new HashSet<>(handlerBlocks);
            boolean previousWasDispatch = false;
            for (BasicBlock block : blocks) {
                if (handlerBlockSet.contains(block)) {
                    if (previousWasDispatch) {
                        newInsns.add(new JumpInsnNode(Opcodes.GOTO, loopEnd));
                    }
                    emitHandlerBlock(newInsns, block, labelRemap, pctx, flowKeyMap,
                        flowKeyVar, flowMixVar, stateMap, encodedStateVar, stateMaskVar, stateDeltaVar,
                        stateRotate, tailSeedVar, tailFlagVar, zkmStyle, tailChainIntensity,
                        tailChains, loopStart, loopEnd, stackHeights, blockEntryStacks, blockSpillBases,
                        blockEntryLocals, blockLocalSpillBases);
                    previousWasDispatch = false;
                } else {
                    emitDispatchBlock(newInsns, block, blockCaseLabels, labelRemap, pctx, flowKeyMap,
                        flowKeyVar, flowMixVar, stateMap, encodedStateVar, stateMaskVar, stateDeltaVar,
                        stateRotate, tailSeedVar, tailFlagVar, zkmStyle, tailChainIntensity,
                        tailChains, loopStart, loopEnd, blockEntryStacks, blockSpillBases,
                        blockEntryLocals, blockLocalSpillBases);
                    previousWasDispatch = true;
                }
            }
        } else {
            int[] emissionOrder = blockEmissionOrder(pctx, dispatchBlocks.size(), false);
            for (int index : emissionOrder) {
                BasicBlock block = dispatchBlocks.get(index);
                emitDispatchBlock(newInsns, block, blockCaseLabels, labelRemap, pctx, flowKeyMap,
                    flowKeyVar, flowMixVar, stateMap, encodedStateVar, stateMaskVar, stateDeltaVar,
                    stateRotate, tailSeedVar, tailFlagVar, zkmStyle, tailChainIntensity,
                    tailChains, loopStart, loopEnd, blockEntryStacks, blockSpillBases,
                    blockEntryLocals, blockLocalSpillBases);
            }
        }

        if (!tailChains.isEmpty()) {
            int[] tailOrder = pctx.random().randomPermutation(tailChains.size());
            for (int index : tailOrder) {
                TailChain chain = tailChains.get(index);
                newInsns.add(chain.entry());
                newInsns.add(chain.body());
            }
        }

        newInsns.add(loopEnd);
        emitSafetyReturn(newInsns, method.returnType());

        // Preserve try-catch blocks with remapped labels
        if (mn.tryCatchBlocks != null && !mn.tryCatchBlocks.isEmpty()) {
            IdentityHashMap<AbstractInsnNode, Integer> originalInstructionPositions = codePositions(mn.instructions);
            IdentityHashMap<LabelNode, Integer> emittedLabelPositions = labelCodePositions(newInsns);
            List<TryCatchBlockNode> remappedTryCatch = new ArrayList<>();
            for (TryCatchBlockNode tcb : mn.tryCatchBlocks) {
                List<RemappedTryCatchRange> remappedRanges = remapTryCatchRanges(tcb, labelRemap,
                    originalInstructionPositions, emittedLabelPositions, newInsns);
                for (RemappedTryCatchRange remapped : remappedRanges) {
                    remappedTryCatch.add(new TryCatchBlockNode(remapped.start(), remapped.end(), remapped.handler(), tcb.type));
                }
            }
            mn.tryCatchBlocks = remappedTryCatch;
        } else {
            mn.tryCatchBlocks = new ArrayList<>();
        }

        mn.instructions = newInsns;
        mn.localVariables = null;
        mn.maxStack = Math.max(mn.maxStack, 8);

        clazz.markDirty();
        flattenedMethods(pctx).add(methodKey(method));
        pctx.invalidate(method);
    }

    private boolean isTerminator(AbstractInsnNode insn, BasicBlock block) {
        AbstractInsnNode last = block.lastInsn();
        if (insn != last) return false;
        int opcode = insn.getOpcode();
        return opcode == Opcodes.GOTO || opcode == 200
            || AsmUtil.isConditionalJump(opcode)
            || insn instanceof TableSwitchInsnNode
            || insn instanceof LookupSwitchInsnNode;
    }

    private void emitDispatchBlock(InsnList insns, BasicBlock block,
            Map<BasicBlock, LabelNode> blockCaseLabels, Map<LabelNode, LabelNode> labelRemap,
            PipelineContext pctx, Map<BasicBlock, Long> flowKeyMap,
            int flowKeyVar, int flowMixVar, Map<BasicBlock, Integer> stateMap,
            int encodedStateVar, int stateMaskVar, int stateDeltaVar, int stateRotate,
            int tailSeedVar, int tailFlagVar, boolean zkmStyle, double tailChainIntensity,
            List<TailChain> tailChains, LabelNode loopStart, LabelNode loopEnd,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks, Map<BasicBlock, Integer> blockSpillBases,
            Map<BasicBlock, List<LocalSlotState>> blockEntryLocals, Map<BasicBlock, Integer> blockLocalSpillBases) {
        LabelNode caseLabel = blockCaseLabels.get(block);
        insns.add(caseLabel);
        insns.add(new InsnNode(Opcodes.NOP));
        restoreBlockEntryLocals(insns, block, blockEntryLocals, blockLocalSpillBases);
        restoreBlockEntryStack(insns, block, blockEntryStacks, blockSpillBases);

        for (AbstractInsnNode insn : block.instructions()) {
            if (insn instanceof FrameNode) continue;
            if (insn instanceof LineNumberNode) continue;
            if (isTerminator(insn, block)) continue;

            if (insn instanceof LabelNode origLabel) {
                LabelNode remapped = labelRemap.get(origLabel);
                if (remapped != null) insns.add(remapped);
                continue;
            }

            AbstractInsnNode clone = insn.clone(labelRemap);
            insns.add(clone);
            recordInstructionFlowKey(pctx, clone, flowKeyMap.getOrDefault(block, 0L));
            if (requiresFlowKeyResync(clone)) {
                emitRuntimeFlowContextSync(insns, flowKeyVar);
            }
        }

        emitStateTransition(insns, block, stateMap,
            flowKeyMap, flowKeyVar, flowMixVar, encodedStateVar, stateMaskVar, stateDeltaVar, stateRotate,
            tailSeedVar, tailFlagVar, zkmStyle, tailChainIntensity,
            tailChains, loopStart, loopEnd, blockEntryStacks, blockSpillBases, blockEntryLocals, blockLocalSpillBases);
    }

    private void emitStateTransition(InsnList insns, BasicBlock block,
            Map<BasicBlock, Integer> stateMap,
            Map<BasicBlock, Long> flowKeyMap, int flowKeyVar, int flowMixVar,
            int encodedStateVar, int stateMaskVar, int stateDeltaVar, int stateRotate,
            int tailSeedVar, int tailFlagVar, boolean zkmStyle, double tailChainIntensity,
            List<TailChain> tailChains, LabelNode loopStart, LabelNode loopEnd,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks, Map<BasicBlock, Integer> blockSpillBases,
            Map<BasicBlock, List<LocalSlotState>> blockEntryLocals, Map<BasicBlock, Integer> blockLocalSpillBases) {

        List<CFGEdge> outEdges = normalOutEdges(block);
        if (outEdges.isEmpty()) return;

        AbstractInsnNode lastReal = findLastRealInsn(block);
        if (lastReal == null) {
                emitUnconditionalTransition(insns, outEdges.get(0).target(), stateMap,
                    flowKeyMap, flowKeyVar, flowMixVar, encodedStateVar, stateMaskVar, stateDeltaVar, stateRotate,
                    tailSeedVar, tailFlagVar, zkmStyle, tailChainIntensity, tailChains,
                    loopStart, 0, blockEntryStacks, blockSpillBases, blockEntryLocals, blockLocalSpillBases);
            return;
        }

        int opcode = lastReal.getOpcode();

        if (AsmUtil.isReturn(opcode) || opcode == Opcodes.ATHROW) return;

        if (AsmUtil.isConditionalJump(opcode)) {
            CFGEdge trueEdge = null, falseEdge = null;
            for (CFGEdge edge : outEdges) {
                if (edge.type() == CFGEdge.Type.CONDITIONAL_TRUE) trueEdge = edge;
                else if (edge.type() == CFGEdge.Type.CONDITIONAL_FALSE) falseEdge = edge;
                else if (trueEdge == null) trueEdge = edge;
                else if (falseEdge == null) falseEdge = edge;
            }
            if (trueEdge == null || falseEdge == null) {
                emitUnconditionalTransition(insns, outEdges.get(0).target(), stateMap,
                    flowKeyMap, flowKeyVar, flowMixVar, encodedStateVar, stateMaskVar, stateDeltaVar, stateRotate,
                    tailSeedVar, tailFlagVar, zkmStyle, tailChainIntensity, tailChains,
                    loopStart, block.id(), blockEntryStacks, blockSpillBases, blockEntryLocals, blockLocalSpillBases);
                return;
            }

            int trueState = requiredState(stateMap, trueEdge.target());
            int falseState = requiredState(stateMap, falseEdge.target());

            LabelNode trueLabel = new LabelNode();
            LabelNode joinLabel = new LabelNode();
            insns.add(new JumpInsnNode(opcode, trueLabel));

            spillStackForTarget(insns, falseEdge.target(), blockEntryStacks, blockSpillBases);
            spillLocalsForTarget(insns, falseEdge.target(), blockEntryLocals, blockLocalSpillBases);
            emitFlowKeyStore(insns, flowKeyMap.getOrDefault(falseEdge.target(), 0L), flowKeyVar, flowMixVar);
            emitEncodedStateStore(insns, falseState, encodedStateVar, stateMaskVar, stateDeltaVar,
                flowMixVar, stateRotate, block.id() + 1);
            insns.add(new JumpInsnNode(Opcodes.GOTO, joinLabel));

            insns.add(trueLabel);
            spillStackForTarget(insns, trueEdge.target(), blockEntryStacks, blockSpillBases);
            spillLocalsForTarget(insns, trueEdge.target(), blockEntryLocals, blockLocalSpillBases);
            emitFlowKeyStore(insns, flowKeyMap.getOrDefault(trueEdge.target(), 0L), flowKeyVar, flowMixVar);
            emitEncodedStateStore(insns, trueState, encodedStateVar, stateMaskVar, stateDeltaVar,
                flowMixVar, stateRotate, block.id());
            insns.add(joinLabel);
            emitLoopReentry(insns, tailSeedVar, tailFlagVar, zkmStyle, tailChainIntensity,
                tailChains, loopStart, block.id());
            return;
        }

        if (lastReal instanceof TableSwitchInsnNode tableSwitch) {
            emitTableSwitchTransition(insns, outEdges, stateMap, flowKeyMap, flowKeyVar, flowMixVar,
                encodedStateVar, stateMaskVar, stateDeltaVar, stateRotate, tailSeedVar, tailFlagVar,
                zkmStyle, tailChainIntensity, tailChains, loopStart, tableSwitch, block.id(),
                blockEntryStacks, blockSpillBases, blockEntryLocals, blockLocalSpillBases);
            return;
        }

        if (lastReal instanceof LookupSwitchInsnNode lookupSwitch) {
            emitLookupSwitchTransition(insns, outEdges, stateMap, flowKeyMap, flowKeyVar, flowMixVar,
                encodedStateVar, stateMaskVar, stateDeltaVar, stateRotate, tailSeedVar, tailFlagVar,
                zkmStyle, tailChainIntensity, tailChains, loopStart, lookupSwitch, block.id(),
                blockEntryStacks, blockSpillBases, blockEntryLocals, blockLocalSpillBases);
            return;
        }

        for (CFGEdge edge : outEdges) {
            emitUnconditionalTransition(insns, edge.target(), stateMap,
                flowKeyMap, flowKeyVar, flowMixVar, encodedStateVar, stateMaskVar, stateDeltaVar, stateRotate,
                tailSeedVar, tailFlagVar, zkmStyle, tailChainIntensity, tailChains,
                loopStart, block.id(), blockEntryStacks, blockSpillBases, blockEntryLocals, blockLocalSpillBases);
            return;
        }
    }

    private void emitUnconditionalTransition(InsnList insns, BasicBlock target,
            Map<BasicBlock, Integer> stateMap, Map<BasicBlock, Long> flowKeyMap,
            int flowKeyVar, int flowMixVar, int encodedStateVar, int stateMaskVar,
            int stateDeltaVar, int stateRotate, int tailSeedVar, int tailFlagVar,
            boolean zkmStyle, double tailChainIntensity, List<TailChain> tailChains,
            LabelNode loopStart, int variantSeed,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks, Map<BasicBlock, Integer> blockSpillBases,
            Map<BasicBlock, List<LocalSlotState>> blockEntryLocals, Map<BasicBlock, Integer> blockLocalSpillBases) {
        int nextState = requiredState(stateMap, target);
        spillStackForTarget(insns, target, blockEntryStacks, blockSpillBases);
        spillLocalsForTarget(insns, target, blockEntryLocals, blockLocalSpillBases);
        emitFlowKeyStore(insns, flowKeyMap.getOrDefault(target, 0L), flowKeyVar, flowMixVar);
        emitEncodedStateStore(insns, nextState, encodedStateVar, stateMaskVar, stateDeltaVar,
            flowMixVar, stateRotate, variantSeed);
        emitLoopReentry(insns, tailSeedVar, tailFlagVar, zkmStyle, tailChainIntensity,
            tailChains, loopStart, variantSeed);
    }

    private void emitTableSwitchTransition(InsnList insns, List<CFGEdge> outEdges,
            Map<BasicBlock, Integer> stateMap, Map<BasicBlock, Long> flowKeyMap,
            int flowKeyVar, int flowMixVar, int encodedStateVar, int stateMaskVar,
            int stateDeltaVar, int stateRotate, int tailSeedVar, int tailFlagVar,
            boolean zkmStyle, double tailChainIntensity, List<TailChain> tailChains,
            LabelNode loopStart,
            TableSwitchInsnNode tableSwitch, int variantSeed,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks, Map<BasicBlock, Integer> blockSpillBases,
            Map<BasicBlock, List<LocalSlotState>> blockEntryLocals, Map<BasicBlock, Integer> blockLocalSpillBases) {
        BasicBlock defaultTarget = null;
        Map<Integer, BasicBlock> targets = new TreeMap<>();
        for (CFGEdge edge : outEdges) {
            if (edge.type() == CFGEdge.Type.SWITCH_DEFAULT) {
                defaultTarget = edge.target();
            } else if (edge.type() == CFGEdge.Type.SWITCH_CASE) {
                targets.put(edge.switchKey(), edge.target());
            }
        }
        if (defaultTarget == null && !outEdges.isEmpty()) {
            defaultTarget = outEdges.get(0).target();
        }
        if (defaultTarget == null) return;

        LabelNode defaultLabel = new LabelNode();
        LabelNode[] labels = new LabelNode[tableSwitch.max - tableSwitch.min + 1];
        List<Map.Entry<Integer, LabelNode>> caseLabels = new ArrayList<>();
        for (int key = tableSwitch.min; key <= tableSwitch.max; key++) {
            if (targets.containsKey(key)) {
                LabelNode label = new LabelNode();
                labels[key - tableSwitch.min] = label;
                caseLabels.add(Map.entry(key, label));
            } else {
                labels[key - tableSwitch.min] = defaultLabel;
            }
        }
        insns.add(new TableSwitchInsnNode(tableSwitch.min, tableSwitch.max, defaultLabel, labels));
        for (Map.Entry<Integer, LabelNode> entry : caseLabels) {
            insns.add(entry.getValue());
            emitUnconditionalTransition(insns, targets.get(entry.getKey()), stateMap, flowKeyMap,
                flowKeyVar, flowMixVar, encodedStateVar,
                stateMaskVar, stateDeltaVar, stateRotate, tailSeedVar, tailFlagVar,
                zkmStyle, tailChainIntensity, tailChains, loopStart, variantSeed + entry.getKey(),
                blockEntryStacks, blockSpillBases, blockEntryLocals, blockLocalSpillBases);
        }
        insns.add(defaultLabel);
        emitUnconditionalTransition(insns, defaultTarget, stateMap, flowKeyMap,
            flowKeyVar, flowMixVar, encodedStateVar,
            stateMaskVar, stateDeltaVar, stateRotate, tailSeedVar, tailFlagVar,
            zkmStyle, tailChainIntensity, tailChains, loopStart, variantSeed + 31,
            blockEntryStacks, blockSpillBases, blockEntryLocals, blockLocalSpillBases);
    }

    private void emitLookupSwitchTransition(InsnList insns, List<CFGEdge> outEdges,
            Map<BasicBlock, Integer> stateMap, Map<BasicBlock, Long> flowKeyMap,
            int flowKeyVar, int flowMixVar, int encodedStateVar, int stateMaskVar,
            int stateDeltaVar, int stateRotate, int tailSeedVar, int tailFlagVar,
            boolean zkmStyle, double tailChainIntensity, List<TailChain> tailChains,
            LabelNode loopStart,
            LookupSwitchInsnNode lookupSwitch, int variantSeed,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks, Map<BasicBlock, Integer> blockSpillBases,
            Map<BasicBlock, List<LocalSlotState>> blockEntryLocals, Map<BasicBlock, Integer> blockLocalSpillBases) {
        BasicBlock defaultTarget = null;
        Map<Integer, BasicBlock> targets = new TreeMap<>();
        for (CFGEdge edge : outEdges) {
            if (edge.type() == CFGEdge.Type.SWITCH_DEFAULT) {
                defaultTarget = edge.target();
            } else if (edge.type() == CFGEdge.Type.SWITCH_CASE) {
                targets.put(edge.switchKey(), edge.target());
            }
        }
        if (defaultTarget == null && !outEdges.isEmpty()) {
            defaultTarget = outEdges.get(0).target();
        }
        if (defaultTarget == null) return;

        LabelNode defaultLabel = new LabelNode();
        List<Integer> sortedKeys = new ArrayList<>(targets.keySet());
        Collections.sort(sortedKeys);
        int[] keys = new int[sortedKeys.size()];
        LabelNode[] labels = new LabelNode[sortedKeys.size()];
        for (int i = 0; i < sortedKeys.size(); i++) {
            keys[i] = sortedKeys.get(i);
            labels[i] = new LabelNode();
        }
        insns.add(new LookupSwitchInsnNode(defaultLabel, keys, labels));
        for (int i = 0; i < sortedKeys.size(); i++) {
            insns.add(labels[i]);
            emitUnconditionalTransition(insns, targets.get(sortedKeys.get(i)), stateMap, flowKeyMap,
                flowKeyVar, flowMixVar, encodedStateVar,
                stateMaskVar, stateDeltaVar, stateRotate, tailSeedVar, tailFlagVar,
                zkmStyle, tailChainIntensity, tailChains, loopStart, variantSeed + i,
                blockEntryStacks, blockSpillBases, blockEntryLocals, blockLocalSpillBases);
        }
        insns.add(defaultLabel);
        emitUnconditionalTransition(insns, defaultTarget, stateMap, flowKeyMap,
            flowKeyVar, flowMixVar, encodedStateVar,
            stateMaskVar, stateDeltaVar, stateRotate, tailSeedVar, tailFlagVar,
            zkmStyle, tailChainIntensity, tailChains, loopStart, variantSeed + 29,
            blockEntryStacks, blockSpillBases, blockEntryLocals, blockLocalSpillBases);
    }

    private int requiredState(Map<BasicBlock, Integer> stateMap, BasicBlock target) {
        Integer state = stateMap.get(target);
        if (state == null) {
            throw new IllegalStateException("Missing dispatch state for block " + target.id());
        }
        return state;
    }

    private void emitLoopReentry(InsnList insns, int tailSeedVar, int tailFlagVar,
            boolean zkmStyle, double tailChainIntensity, List<TailChain> tailChains,
            LabelNode loopStart, int variantSeed) {
        if (!shouldUseTailChain(zkmStyle, tailChainIntensity, variantSeed)) {
            insns.add(new JumpInsnNode(Opcodes.GOTO, loopStart));
            return;
        }

        LabelNode tailEntry = new LabelNode();
        insns.add(new JumpInsnNode(Opcodes.GOTO, tailEntry));
        tailChains.add(new TailChain(tailEntry,
            buildTailChain(loopStart, tailSeedVar, tailFlagVar, variantSeed)));
    }

    private InsnList buildTailChain(LabelNode loopStart, int tailSeedVar, int tailFlagVar, int variantSeed) {
        InsnList tail = new InsnList();
        LabelNode fallback = new LabelNode();
        tail.add(new IincInsnNode(tailSeedVar, 1 + Math.floorMod(variantSeed, 7)));
        tail.add(new VarInsnNode(Opcodes.ILOAD, tailSeedVar));
        tail.add(AsmUtil.pushIntAny(foldMethodKey(0x5F3759DFL ^ (variantSeed * 0x45D9F3B))));
        tail.add(new InsnNode(Opcodes.IXOR));
        tail.add(new InsnNode(Opcodes.ICONST_1));
        tail.add(new InsnNode(Opcodes.IOR));
        tail.add(new InsnNode(Opcodes.DUP));
        tail.add(new VarInsnNode(Opcodes.ISTORE, tailFlagVar));
        tail.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
        tail.add(new JumpInsnNode(Opcodes.GOTO, loopStart));
        tail.add(fallback);
        tail.add(new JumpInsnNode(Opcodes.GOTO, loopStart));
        return tail;
    }

    private void emitStateDecode(InsnList insns, int encodedStateVar, int dispatchStateVar,
            int stateMaskVar, int stateDeltaVar, int flowMixVar, int stateRotate) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, encodedStateVar));
        insns.add(new VarInsnNode(Opcodes.ILOAD, stateDeltaVar));
        insns.add(new InsnNode(Opcodes.ISUB));
        insns.add(AsmUtil.pushIntAny(stateRotate));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTEGER_OWNER,
            "rotateRight", "(II)I", false));
        insns.add(new VarInsnNode(Opcodes.ILOAD, stateMaskVar));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, flowMixVar));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, dispatchStateVar));
    }

    private void emitEncodedStateStore(InsnList insns, int decodedState, int encodedStateVar,
            int stateMaskVar, int stateDeltaVar, int flowMixVar, int stateRotate, int variantSeed) {
        emitEncodedStateValue(insns, decodedState, stateMaskVar, stateDeltaVar, flowMixVar, stateRotate, variantSeed);
        insns.add(new VarInsnNode(Opcodes.ISTORE, encodedStateVar));
    }

    private void emitEncodedStateValue(InsnList insns, int decodedState, int stateMaskVar,
            int stateDeltaVar, int flowMixVar, int stateRotate, int variantSeed) {
        int variant = Math.floorMod(variantSeed, 3);
        if (variant == 1) {
            insns.add(new VarInsnNode(Opcodes.ILOAD, stateMaskVar));
            insns.add(AsmUtil.pushIntAny(decodedState));
        } else {
            insns.add(AsmUtil.pushIntAny(decodedState));
            insns.add(new VarInsnNode(Opcodes.ILOAD, stateMaskVar));
        }
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, flowMixVar));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(AsmUtil.pushIntAny(stateRotate));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTEGER_OWNER,
            "rotateLeft", "(II)I", false));
        insns.add(new VarInsnNode(Opcodes.ILOAD, stateDeltaVar));
        if (variant == 2) {
            insns.add(new InsnNode(Opcodes.INEG));
            insns.add(new InsnNode(Opcodes.ISUB));
        } else {
            insns.add(new InsnNode(Opcodes.IADD));
        }
    }

    private void emitFlowKeyStore(InsnList insns, long flowKey, int flowKeyVar, int flowMixVar) {
        insns.add(new LdcInsnNode(flowKey));
        insns.add(new VarInsnNode(Opcodes.LSTORE, flowKeyVar));
        insns.add(AsmUtil.pushIntAny(foldFlowKey(flowKey)));
        insns.add(new VarInsnNode(Opcodes.ISTORE, flowMixVar));
    }

    private void emitRuntimeFlowContextSync(InsnList insns, int flowKeyVar) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, flowKeyVar));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT_OWNER,
            "setCurrentFlowKey", "(J)V", false));
    }

    private void recordInstructionFlowKey(PipelineContext pctx, AbstractInsnNode insn, long flowKey) {
        flowKeyValues(pctx).put(insn, flowKey);
    }

    private Set<String> flattenedMethods(PipelineContext pctx) {
        Set<String> flattenedMethods = pctx.getPassData(FLATTENED_METHODS_KEY);
        if (flattenedMethods == null) {
            flattenedMethods = new HashSet<>();
            pctx.putPassData(FLATTENED_METHODS_KEY, flattenedMethods);
        }
        return flattenedMethods;
    }

    private IdentityHashMap<AbstractInsnNode, Long> flowKeyValues(PipelineContext pctx) {
        IdentityHashMap<AbstractInsnNode, Long> flowKeys = pctx.getPassData(FLOW_KEY_VALUES_KEY);
        if (flowKeys == null) {
            flowKeys = new IdentityHashMap<>();
            pctx.putPassData(FLOW_KEY_VALUES_KEY, flowKeys);
        }
        return flowKeys;
    }

    private void emitOriginalLocalInitialization(InsnList insns, L1Method method, int originalMaxLocals) {
        if (originalMaxLocals <= 0) return;

        LocalInitKind[] kinds = inferOriginalLocalKinds(method, originalMaxLocals);
        for (int slot = parameterSlotCount(method); slot < originalMaxLocals; slot++) {
            LocalInitKind kind = kinds[slot];
            switch (kind) {
                case REFERENCE -> {
                    insns.add(new InsnNode(Opcodes.ACONST_NULL));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, slot));
                }
                case INT -> {
                    insns.add(new InsnNode(Opcodes.ICONST_0));
                    insns.add(new VarInsnNode(Opcodes.ISTORE, slot));
                }
                case FLOAT -> {
                    insns.add(new InsnNode(Opcodes.FCONST_0));
                    insns.add(new VarInsnNode(Opcodes.FSTORE, slot));
                }
                case LONG -> {
                    insns.add(new InsnNode(Opcodes.LCONST_0));
                    insns.add(new VarInsnNode(Opcodes.LSTORE, slot));
                    slot++;
                }
                case DOUBLE -> {
                    insns.add(new InsnNode(Opcodes.DCONST_0));
                    insns.add(new VarInsnNode(Opcodes.DSTORE, slot));
                    slot++;
                }
                default -> {
                }
            }
        }
    }

    private LocalInitKind[] inferOriginalLocalKinds(L1Method method, int originalMaxLocals) {
        LocalInitKind[] kinds = new LocalInitKind[originalMaxLocals];
        LocalInitKind[] firstSeenKinds = new LocalInitKind[originalMaxLocals];
        Arrays.fill(kinds, LocalInitKind.UNKNOWN);
        Arrays.fill(firstSeenKinds, LocalInitKind.UNKNOWN);

        int slot = 0;
        if (!method.isStatic() && originalMaxLocals > 0) {
            kinds[0] = LocalInitKind.REFERENCE;
            firstSeenKinds[0] = LocalInitKind.REFERENCE;
            slot = 1;
        }
        for (Type argumentType : method.argumentTypes()) {
            if (slot >= originalMaxLocals) break;
            markTypeKind(kinds, slot, argumentType);
            recordTypeKind(firstSeenKinds, slot, argumentType);
            slot += argumentType.getSize();
        }

        for (LocalVariableNode localVariable : method.localVariables()) {
            if (localVariable.index < 0 || localVariable.index >= originalMaxLocals) continue;
            markTypeKind(kinds, localVariable.index, Type.getType(localVariable.desc));
            recordTypeKind(firstSeenKinds, localVariable.index, Type.getType(localVariable.desc));
        }

        for (AbstractInsnNode insn = method.instructions().getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof VarInsnNode varInsn) {
                markOpcodeKind(kinds, varInsn.getOpcode(), varInsn.var);
                recordOpcodeKind(firstSeenKinds, varInsn.getOpcode(), varInsn.var);
            } else if (insn instanceof IincInsnNode iincInsn) {
                markLocalKind(kinds, iincInsn.var, LocalInitKind.INT);
                recordPreferredKind(firstSeenKinds, iincInsn.var, LocalInitKind.INT);
            }
        }

        for (int i = 0; i < kinds.length; i++) {
            if ((kinds[i] == LocalInitKind.UNKNOWN || kinds[i] == LocalInitKind.CONFLICT)
                    && firstSeenKinds[i] != LocalInitKind.UNKNOWN
                    && firstSeenKinds[i] != LocalInitKind.CONFLICT
                    && firstSeenKinds[i] != LocalInitKind.RESERVED) {
                kinds[i] = firstSeenKinds[i];
                if ((firstSeenKinds[i] == LocalInitKind.LONG || firstSeenKinds[i] == LocalInitKind.DOUBLE)
                        && i + 1 < kinds.length
                        && (kinds[i + 1] == LocalInitKind.UNKNOWN || kinds[i + 1] == LocalInitKind.CONFLICT)) {
                    kinds[i + 1] = LocalInitKind.RESERVED;
                }
            }
        }
        return kinds;
    }

    private int parameterSlotCount(L1Method method) {
        int slots = method.isStatic() ? 0 : 1;
        for (Type argumentType : method.argumentTypes()) {
            slots += argumentType.getSize();
        }
        return slots;
    }

    private void markOpcodeKind(LocalInitKind[] kinds, int opcode, int slot) {
        switch (opcode) {
            case Opcodes.ILOAD, Opcodes.ISTORE -> markLocalKind(kinds, slot, LocalInitKind.INT);
            case Opcodes.FLOAD, Opcodes.FSTORE -> markLocalKind(kinds, slot, LocalInitKind.FLOAT);
            case Opcodes.LLOAD, Opcodes.LSTORE -> markWideLocalKind(kinds, slot, LocalInitKind.LONG);
            case Opcodes.DLOAD, Opcodes.DSTORE -> markWideLocalKind(kinds, slot, LocalInitKind.DOUBLE);
            case Opcodes.ALOAD, Opcodes.ASTORE -> markLocalKind(kinds, slot, LocalInitKind.REFERENCE);
            default -> {
            }
        }
    }

    private void recordOpcodeKind(LocalInitKind[] kinds, int opcode, int slot) {
        switch (opcode) {
            case Opcodes.ILOAD, Opcodes.ISTORE -> recordPreferredKind(kinds, slot, LocalInitKind.INT);
            case Opcodes.FLOAD, Opcodes.FSTORE -> recordPreferredKind(kinds, slot, LocalInitKind.FLOAT);
            case Opcodes.LLOAD, Opcodes.LSTORE -> recordPreferredWideKind(kinds, slot, LocalInitKind.LONG);
            case Opcodes.DLOAD, Opcodes.DSTORE -> recordPreferredWideKind(kinds, slot, LocalInitKind.DOUBLE);
            case Opcodes.ALOAD, Opcodes.ASTORE -> recordPreferredKind(kinds, slot, LocalInitKind.REFERENCE);
            default -> {
            }
        }
    }

    private void markTypeKind(LocalInitKind[] kinds, int slot, Type type) {
        switch (type.getSort()) {
            case Type.LONG -> markWideLocalKind(kinds, slot, LocalInitKind.LONG);
            case Type.DOUBLE -> markWideLocalKind(kinds, slot, LocalInitKind.DOUBLE);
            case Type.FLOAT -> markLocalKind(kinds, slot, LocalInitKind.FLOAT);
            case Type.ARRAY, Type.OBJECT -> markLocalKind(kinds, slot, LocalInitKind.REFERENCE);
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT ->
                markLocalKind(kinds, slot, LocalInitKind.INT);
            default -> {
            }
        }
    }

    private void recordTypeKind(LocalInitKind[] kinds, int slot, Type type) {
        switch (type.getSort()) {
            case Type.LONG -> recordPreferredWideKind(kinds, slot, LocalInitKind.LONG);
            case Type.DOUBLE -> recordPreferredWideKind(kinds, slot, LocalInitKind.DOUBLE);
            case Type.FLOAT -> recordPreferredKind(kinds, slot, LocalInitKind.FLOAT);
            case Type.ARRAY, Type.OBJECT -> recordPreferredKind(kinds, slot, LocalInitKind.REFERENCE);
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT ->
                recordPreferredKind(kinds, slot, LocalInitKind.INT);
            default -> {
            }
        }
    }

    private void markWideLocalKind(LocalInitKind[] kinds, int slot, LocalInitKind kind) {
        markLocalKind(kinds, slot, kind);
        if (slot + 1 < kinds.length) {
            LocalInitKind existing = kinds[slot + 1];
            if (existing == LocalInitKind.UNKNOWN || existing == LocalInitKind.RESERVED) {
                kinds[slot + 1] = LocalInitKind.RESERVED;
            } else if (existing != kind) {
                kinds[slot + 1] = LocalInitKind.CONFLICT;
            }
        }
    }

    private void recordPreferredWideKind(LocalInitKind[] kinds, int slot, LocalInitKind kind) {
        recordPreferredKind(kinds, slot, kind);
        if (slot + 1 < kinds.length && kinds[slot + 1] == LocalInitKind.UNKNOWN) {
            kinds[slot + 1] = LocalInitKind.RESERVED;
        }
    }

    private void markLocalKind(LocalInitKind[] kinds, int slot, LocalInitKind kind) {
        if (slot < 0 || slot >= kinds.length) return;
        LocalInitKind existing = kinds[slot];
        if (existing == LocalInitKind.UNKNOWN || existing == kind) {
            kinds[slot] = kind;
            return;
        }
        if (existing == LocalInitKind.RESERVED && kind == LocalInitKind.RESERVED) {
            return;
        }
        kinds[slot] = LocalInitKind.CONFLICT;
    }

    private void recordPreferredKind(LocalInitKind[] kinds, int slot, LocalInitKind kind) {
        if (slot < 0 || slot >= kinds.length) {
            return;
        }
        if (kinds[slot] == LocalInitKind.UNKNOWN) {
            kinds[slot] = kind;
        }
    }

    private int[] blockEmissionOrder(PipelineContext pctx, int blockCount, boolean preserveTryCatchOrder) {
        if (!preserveTryCatchOrder) {
            return pctx.random().randomPermutation(blockCount);
        }

        int[] order = new int[blockCount];
        for (int i = 0; i < blockCount; i++) {
            order[i] = i;
        }
        return order;
    }

    private void partitionBlocks(List<BasicBlock> blocks, List<BasicBlock> dispatchBlocks, List<BasicBlock> handlerBlocks) {
        for (BasicBlock block : blocks) {
            if (block.isExceptionHandler()) {
                handlerBlocks.add(block);
            } else {
                dispatchBlocks.add(block);
            }
        }
    }

    private IdentityHashMap<LabelNode, Integer> labelCodePositions(InsnList insns) {
        IdentityHashMap<LabelNode, Integer> positions = new IdentityHashMap<>();
        int index = 0;
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode label) {
                positions.put(label, index);
            }
            if (isBytecodeInsn(insn)) {
                index++;
            }
        }
        return positions;
    }

    private IdentityHashMap<AbstractInsnNode, Integer> codePositions(InsnList insns) {
        IdentityHashMap<AbstractInsnNode, Integer> positions = new IdentityHashMap<>();
        int index = 0;
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            positions.put(insn, index);
            if (isBytecodeInsn(insn)) {
                index++;
            }
        }
        return positions;
    }

    private boolean isBytecodeInsn(AbstractInsnNode insn) {
        return !(insn instanceof LabelNode)
            && !(insn instanceof FrameNode)
            && !(insn instanceof LineNumberNode);
    }

    private IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> analyzeFrames(String ownerName, MethodNode mn) {
        IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> framesByInsn = new IdentityHashMap<>();
        try {
            Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicInterpreter());
            Frame<BasicValue>[] frames = analyzer.analyze(ownerName, mn);
            AbstractInsnNode[] instructions = mn.instructions.toArray();
            for (int i = 0; i < instructions.length; i++) {
                Frame<BasicValue> frame = frames[i];
                if (frame != null) {
                    framesByInsn.put(instructions[i], frame);
                }
            }
        } catch (AnalyzerException ignored) {
            return framesByInsn;
        }
        return framesByInsn;
    }

    private IdentityHashMap<AbstractInsnNode, Integer> extractStackHeights(
            IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> framesByInsn) {
        IdentityHashMap<AbstractInsnNode, Integer> stackHeights = new IdentityHashMap<>();
        for (Map.Entry<AbstractInsnNode, Frame<BasicValue>> entry : framesByInsn.entrySet()) {
            stackHeights.put(entry.getKey(), entry.getValue().getStackSize());
        }
        return stackHeights;
    }

    private Map<BasicBlock, List<StackSlotKind>> analyzeBlockEntryStacks(List<BasicBlock> blocks,
            IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> framesByInsn) {
        Map<BasicBlock, List<StackSlotKind>> blockEntryStacks = new HashMap<>();
        for (BasicBlock block : blocks) {
            AbstractInsnNode firstInsn = firstExecutableInsn(block);
            if (firstInsn == null) {
                blockEntryStacks.put(block, List.of());
                continue;
            }
            Frame<BasicValue> frame = framesByInsn.get(firstInsn);
            if (frame == null || frame.getStackSize() == 0) {
                blockEntryStacks.put(block, List.of());
                continue;
            }
            List<StackSlotKind> stackKinds = new ArrayList<>(frame.getStackSize());
            for (int i = 0; i < frame.getStackSize(); i++) {
                stackKinds.add(stackSlotKind(frame.getStack(i)));
            }
            blockEntryStacks.put(block, List.copyOf(stackKinds));
        }
        return blockEntryStacks;
    }

    private Map<BasicBlock, List<LocalSlotState>> analyzeBlockEntryLocals(List<BasicBlock> allBlocks,
            List<BasicBlock> dispatchBlocks,
            IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> framesByInsn, int originalMaxLocals) {
        Map<BasicBlock, List<LocalSlotState>> blockEntryLocals = new HashMap<>();
        if (originalMaxLocals <= 0 || allBlocks.isEmpty() || dispatchBlocks.isEmpty()) {
            return blockEntryLocals;
        }

        Map<BasicBlock, LocalFlowSummary> localFlowByBlock = new IdentityHashMap<>();
        Map<BasicBlock, Frame<BasicValue>> entryFrames = new IdentityHashMap<>();

        for (BasicBlock block : allBlocks) {
            AbstractInsnNode firstInsn = firstExecutableInsn(block);
            if (firstInsn == null) {
                localFlowByBlock.put(block, new LocalFlowSummary(new BitSet(), new BitSet()));
                continue;
            }
            Frame<BasicValue> frame = framesByInsn.get(firstInsn);
            if (frame == null || frame.getLocals() == 0 || originalMaxLocals <= 0) {
                localFlowByBlock.put(block, new LocalFlowSummary(new BitSet(), new BitSet()));
                continue;
            }
            entryFrames.put(block, frame);
            int upperBound = Math.min(originalMaxLocals, frame.getLocals());
            localFlowByBlock.put(block, summarizeLocalFlow(block, upperBound));
        }

        Map<BasicBlock, BitSet> liveIn = new IdentityHashMap<>();
        Map<BasicBlock, BitSet> liveOut = new IdentityHashMap<>();
        for (BasicBlock block : allBlocks) {
            LocalFlowSummary flow = localFlowByBlock.get(block);
            liveIn.put(block, copyBitSet(flow == null ? null : flow.uses()));
            liveOut.put(block, new BitSet());
        }

        boolean changed;
        do {
            changed = false;
            for (int i = allBlocks.size() - 1; i >= 0; i--) {
                BasicBlock block = allBlocks.get(i);
                LocalFlowSummary flow = localFlowByBlock.get(block);
                if (flow == null) {
                    continue;
                }

                BitSet newOut = new BitSet();
                for (CFGEdge edge : block.outEdges()) {
                    BitSet successorLiveIn = liveIn.get(edge.target());
                    if (successorLiveIn != null) {
                        newOut.or(successorLiveIn);
                    }
                }

                BitSet newIn = copyBitSet(newOut);
                newIn.andNot(flow.defs());
                newIn.or(flow.uses());

                if (!newOut.equals(liveOut.get(block))) {
                    liveOut.put(block, newOut);
                    changed = true;
                }
                if (!newIn.equals(liveIn.get(block))) {
                    liveIn.put(block, newIn);
                    changed = true;
                }
            }
        } while (changed);

        for (BasicBlock block : dispatchBlocks) {
            Frame<BasicValue> frame = entryFrames.get(block);
            if (frame == null || frame.getLocals() == 0) {
                blockEntryLocals.put(block, List.of());
                continue;
            }
            int upperBound = Math.min(originalMaxLocals, frame.getLocals());
            blockEntryLocals.put(block, materializeLiveInLocals(liveIn.get(block), frame, upperBound));
        }
        return blockEntryLocals;
    }

    private LocalFlowSummary summarizeLocalFlow(BasicBlock block, int upperBound) {
        if (upperBound <= 0) {
            return new LocalFlowSummary(new BitSet(), new BitSet());
        }

        boolean[] written = new boolean[upperBound];
        BitSet uses = new BitSet(upperBound);
        BitSet defs = new BitSet(upperBound);

        for (AbstractInsnNode insn : block.instructions()) {
            if (insn instanceof LabelNode || insn instanceof FrameNode || insn instanceof LineNumberNode) {
                continue;
            }

            if (insn instanceof IincInsnNode iinc) {
                if (iinc.var < upperBound && !written[iinc.var]) {
                    uses.set(iinc.var);
                }
                markWritten(written, iinc.var, 1);
                defs.set(iinc.var);
                continue;
            }

            if (!(insn instanceof VarInsnNode varInsn)) {
                continue;
            }

            int slot = varInsn.var;
            if (slot >= upperBound) {
                continue;
            }

            int opcode = varInsn.getOpcode();
            if (isLocalLoadOpcode(opcode)) {
                if (!written[slot]) {
                    uses.set(slot);
                }
                continue;
            }

            if (isLocalStoreOpcode(opcode)) {
                int size = localSlotSize(opcode);
                markWritten(written, slot, size);
                defs.set(slot);
                if (size == 2 && slot + 1 < upperBound) {
                    defs.set(slot + 1);
                }
            }
        }

        return new LocalFlowSummary(uses, defs);
    }

    private List<LocalSlotState> materializeLiveInLocals(BitSet liveIn,
            Frame<BasicValue> entryFrame, int upperBound) {
        if (liveIn == null || liveIn.isEmpty() || upperBound <= 0) {
            return List.of();
        }

        List<LocalSlotState> locals = new ArrayList<>();
        for (int slot = liveIn.nextSetBit(0); slot >= 0 && slot < upperBound; slot = liveIn.nextSetBit(slot + 1)) {
            BasicValue value = entryFrame.getLocal(slot);
            if (!isInitializedLocalValue(value)) {
                continue;
            }
            locals.add(new LocalSlotState(slot, stackSlotKind(value)));
        }
        return locals.isEmpty() ? List.of() : List.copyOf(locals);
    }

    private BitSet copyBitSet(BitSet source) {
        return source == null ? new BitSet() : (BitSet) source.clone();
    }

    private void markWritten(boolean[] written, int slot, int size) {
        if (slot < 0 || slot >= written.length) {
            return;
        }
        written[slot] = true;
        if (size == 2 && slot + 1 < written.length) {
            written[slot + 1] = true;
        }
    }

    private boolean isLocalLoadOpcode(int opcode) {
        return opcode == Opcodes.ILOAD
            || opcode == Opcodes.LLOAD
            || opcode == Opcodes.FLOAD
            || opcode == Opcodes.DLOAD
            || opcode == Opcodes.ALOAD;
    }

    private boolean isLocalStoreOpcode(int opcode) {
        return opcode == Opcodes.ISTORE
            || opcode == Opcodes.LSTORE
            || opcode == Opcodes.FSTORE
            || opcode == Opcodes.DSTORE
            || opcode == Opcodes.ASTORE;
    }

    private int localSlotSize(int opcode) {
        return opcode == Opcodes.LLOAD || opcode == Opcodes.DLOAD
            || opcode == Opcodes.LSTORE || opcode == Opcodes.DSTORE ? 2 : 1;
    }

    private AbstractInsnNode firstExecutableInsn(BasicBlock block) {
        for (AbstractInsnNode insn : block.instructions()) {
            if (insn instanceof LabelNode || insn instanceof FrameNode || insn instanceof LineNumberNode) {
                continue;
            }
            return insn;
        }
        return null;
    }

    private int allocateSpillLocals(List<BasicBlock> dispatchBlocks,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks,
            Map<BasicBlock, Integer> blockSpillBases, int nextLocal) {
        for (BasicBlock block : dispatchBlocks) {
            List<StackSlotKind> stackKinds = blockEntryStacks.get(block);
            if (stackKinds == null || stackKinds.isEmpty()) {
                continue;
            }
            blockSpillBases.put(block, nextLocal);
            for (StackSlotKind kind : stackKinds) {
                nextLocal += kind.slotSize();
            }
        }
        return nextLocal;
    }

    private int allocateLocalSpillLocals(List<BasicBlock> dispatchBlocks,
            Map<BasicBlock, List<LocalSlotState>> blockEntryLocals,
            Map<BasicBlock, Integer> blockLocalSpillBases, int nextLocal) {
        for (BasicBlock block : dispatchBlocks) {
            List<LocalSlotState> locals = blockEntryLocals.get(block);
            if (locals == null || locals.isEmpty()) {
                continue;
            }
            blockLocalSpillBases.put(block, nextLocal);
            for (LocalSlotState local : locals) {
                nextLocal += local.kind().slotSize();
            }
        }
        return nextLocal;
    }

    private void initializeSyntheticSpillLocals(InsnList insns,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks,
            Map<BasicBlock, Integer> blockSpillBases,
            Map<BasicBlock, List<LocalSlotState>> blockEntryLocals,
            Map<BasicBlock, Integer> blockLocalSpillBases) {
        for (Map.Entry<BasicBlock, Integer> entry : blockSpillBases.entrySet()) {
            initializeSpillRange(insns, blockEntryStacks.get(entry.getKey()), entry.getValue());
        }
        for (Map.Entry<BasicBlock, Integer> entry : blockLocalSpillBases.entrySet()) {
            initializeLocalSpillRange(insns, blockEntryLocals.get(entry.getKey()), entry.getValue());
        }
    }

    private void initializeSpillRange(InsnList insns, List<StackSlotKind> stackKinds, int spillBase) {
        if (stackKinds == null || stackKinds.isEmpty()) {
            return;
        }
        int offset = 0;
        for (StackSlotKind kind : stackKinds) {
            emitDefaultStore(insns, kind, spillBase + offset);
            offset += kind.slotSize();
        }
    }

    private void initializeLocalSpillRange(InsnList insns, List<LocalSlotState> locals, int spillBase) {
        if (locals == null || locals.isEmpty()) {
            return;
        }
        int offset = 0;
        for (LocalSlotState local : locals) {
            emitDefaultStore(insns, local.kind(), spillBase + offset);
            offset += local.kind().slotSize();
        }
    }

    private void emitDefaultStore(InsnList insns, StackSlotKind kind, int slot) {
        switch (kind) {
            case REFERENCE -> {
                insns.add(new InsnNode(Opcodes.ACONST_NULL));
                insns.add(new VarInsnNode(Opcodes.ASTORE, slot));
            }
            case INT -> {
                insns.add(new InsnNode(Opcodes.ICONST_0));
                insns.add(new VarInsnNode(Opcodes.ISTORE, slot));
            }
            case FLOAT -> {
                insns.add(new InsnNode(Opcodes.FCONST_0));
                insns.add(new VarInsnNode(Opcodes.FSTORE, slot));
            }
            case LONG -> {
                insns.add(new InsnNode(Opcodes.LCONST_0));
                insns.add(new VarInsnNode(Opcodes.LSTORE, slot));
            }
            case DOUBLE -> {
                insns.add(new InsnNode(Opcodes.DCONST_0));
                insns.add(new VarInsnNode(Opcodes.DSTORE, slot));
            }
        }
    }

    private boolean isInitializedLocalValue(BasicValue value) {
        return value != null && value != BasicValue.UNINITIALIZED_VALUE;
    }

    private int originalMaxLocals(L1Method method) {
        return method.asmNode().maxLocals;
    }

    private void restoreBlockEntryStack(InsnList insns, BasicBlock block,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks,
            Map<BasicBlock, Integer> blockSpillBases) {
        List<StackSlotKind> stackKinds = blockEntryStacks.get(block);
        if (stackKinds == null || stackKinds.isEmpty()) {
            return;
        }
        Integer spillBase = blockSpillBases.get(block);
        if (spillBase == null) {
            return;
        }
        int offset = 0;
        for (StackSlotKind kind : stackKinds) {
            insns.add(new VarInsnNode(kind.loadOpcode(), spillBase + offset));
            offset += kind.slotSize();
        }
    }

    private void restoreBlockEntryLocals(InsnList insns, BasicBlock block,
            Map<BasicBlock, List<LocalSlotState>> blockEntryLocals,
            Map<BasicBlock, Integer> blockLocalSpillBases) {
        List<LocalSlotState> locals = blockEntryLocals.get(block);
        if (locals == null || locals.isEmpty()) {
            return;
        }
        Integer spillBase = blockLocalSpillBases.get(block);
        if (spillBase == null) {
            return;
        }
        int offset = 0;
        for (LocalSlotState local : locals) {
            insns.add(new VarInsnNode(local.kind().loadOpcode(), spillBase + offset));
            insns.add(new VarInsnNode(local.kind().storeOpcode(), local.slot()));
            offset += local.kind().slotSize();
        }
    }

    private void spillStackForTarget(InsnList insns, BasicBlock target,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks,
            Map<BasicBlock, Integer> blockSpillBases) {
        List<StackSlotKind> stackKinds = blockEntryStacks.get(target);
        if (stackKinds == null || stackKinds.isEmpty()) {
            return;
        }
        Integer spillBase = blockSpillBases.get(target);
        if (spillBase == null) {
            return;
        }
        int[] offsets = new int[stackKinds.size()];
        int offset = 0;
        for (int i = 0; i < stackKinds.size(); i++) {
            offsets[i] = offset;
            offset += stackKinds.get(i).slotSize();
        }
        for (int i = stackKinds.size() - 1; i >= 0; i--) {
            StackSlotKind kind = stackKinds.get(i);
            insns.add(new VarInsnNode(kind.storeOpcode(), spillBase + offsets[i]));
        }
    }

    private void spillLocalsForTarget(InsnList insns, BasicBlock target,
            Map<BasicBlock, List<LocalSlotState>> blockEntryLocals,
            Map<BasicBlock, Integer> blockLocalSpillBases) {
        List<LocalSlotState> locals = blockEntryLocals.get(target);
        if (locals == null || locals.isEmpty()) {
            return;
        }
        Integer spillBase = blockLocalSpillBases.get(target);
        if (spillBase == null) {
            return;
        }
        int offset = 0;
        for (LocalSlotState local : locals) {
            insns.add(new VarInsnNode(local.kind().loadOpcode(), local.slot()));
            insns.add(new VarInsnNode(local.kind().storeOpcode(), spillBase + offset));
            offset += local.kind().slotSize();
        }
    }

    private StackSlotKind stackSlotKind(BasicValue value) {
        if (value == BasicValue.LONG_VALUE) {
            return StackSlotKind.LONG;
        }
        if (value == BasicValue.DOUBLE_VALUE) {
            return StackSlotKind.DOUBLE;
        }
        if (value == BasicValue.FLOAT_VALUE) {
            return StackSlotKind.FLOAT;
        }
        if (value != null && value.isReference()) {
            return StackSlotKind.REFERENCE;
        }
        return StackSlotKind.INT;
    }

    private List<RemappedTryCatchRange> remapTryCatchRanges(TryCatchBlockNode tcb,
            Map<LabelNode, LabelNode> labelRemap,
            IdentityHashMap<AbstractInsnNode, Integer> originalInstructionPositions,
            IdentityHashMap<LabelNode, Integer> emittedLabelPositions,
            InsnList emittedInsns) {
        LabelNode newHandler = labelRemap.get(tcb.handler);
        if (newHandler == null || !emittedLabelPositions.containsKey(newHandler)) {
            return List.of();
        }

        Integer originalStartPos = originalInstructionPositions.get(tcb.start);
        Integer originalEndPos = originalInstructionPositions.get(tcb.end);
        if (originalStartPos == null || originalEndPos == null || originalStartPos >= originalEndPos) {
            return List.of();
        }

        Set<LabelNode> protectedLabels = Collections.newSetFromMap(new IdentityHashMap<>());

        for (AbstractInsnNode insn = tcb.start; insn != null; insn = insn.getNext()) {
            Integer pos = originalInstructionPositions.get(insn);
            if (pos == null || pos >= originalEndPos) {
                break;
            }
            if (insn instanceof LabelNode originalLabel) {
                LabelNode emittedLabel = labelRemap.get(originalLabel);
                Integer emittedPos = emittedLabel == null ? null : emittedLabelPositions.get(emittedLabel);
                if (emittedPos == null) {
                    continue;
                }
                if (emittedLabel == newHandler) {
                    continue;
                }
                protectedLabels.add(emittedLabel);
            }
        }

        if (protectedLabels.isEmpty()) {
            return List.of();
        }

        List<RemappedTryCatchRange> remappedRanges = new ArrayList<>();
        LabelNode segmentStart = null;
        LabelNode segmentLastProtected = null;
        int segmentStartPos = Integer.MIN_VALUE;
        int segmentLastProtectedPos = Integer.MIN_VALUE;

        for (AbstractInsnNode insn = emittedInsns.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof LabelNode label)) {
                continue;
            }
            Integer pos = emittedLabelPositions.get(label);
            if (pos == null) {
                continue;
            }
            boolean isProtected = protectedLabels.contains(label);

            if (isProtected) {
                if (segmentStart == null) {
                    segmentStart = label;
                    segmentStartPos = pos;
                }
                segmentLastProtected = label;
                segmentLastProtectedPos = pos;
                continue;
            }

            if (segmentStart != null && pos > segmentLastProtectedPos) {
                appendRemappedTryCatchRange(remappedRanges, segmentStart, segmentStartPos, label, pos, newHandler);
                segmentStart = null;
                segmentLastProtected = null;
                segmentStartPos = Integer.MIN_VALUE;
                segmentLastProtectedPos = Integer.MIN_VALUE;
            }
        }

        if (segmentStart != null && segmentLastProtected != null) {
            LabelNode segmentEnd = nextEmittedLabelSkippingHandler(emittedInsns, segmentLastProtected, emittedLabelPositions, newHandler);
            Integer segmentEndPos = segmentEnd == null ? null : emittedLabelPositions.get(segmentEnd);
            if (segmentEndPos != null) {
                appendRemappedTryCatchRange(remappedRanges, segmentStart, segmentStartPos, segmentEnd, segmentEndPos, newHandler);
            }
        }

        return remappedRanges.isEmpty() ? List.of() : List.copyOf(remappedRanges);
    }

    private void appendRemappedTryCatchRange(List<RemappedTryCatchRange> remappedRanges,
            LabelNode start, int startPos, LabelNode end, Integer endPos, LabelNode handler) {
        if (start == null || end == null || endPos == null) {
            return;
        }
        if (start == handler || end == handler || start == end || startPos >= endPos) {
            return;
        }
        remappedRanges.add(new RemappedTryCatchRange(start, end, handler));
    }

    private LabelNode nextEmittedLabelSkippingHandler(InsnList insns, LabelNode from,
            IdentityHashMap<LabelNode, Integer> positions, LabelNode handler) {
        Integer fromPos = positions.get(from);
        if (fromPos == null) return null;
        for (AbstractInsnNode insn = from.getNext(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode label) {
                Integer pos = positions.get(label);
                if (pos != null && pos > fromPos && label != handler) {
                    return label;
                }
            }
        }
        return null;
    }

    private LabelNode nextEmittedLabel(InsnList insns, LabelNode from,
            IdentityHashMap<LabelNode, Integer> positions) {
        Integer fromPos = positions.get(from);
        if (fromPos == null) return null;
        for (AbstractInsnNode insn = from.getNext(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode label) {
                Integer pos = positions.get(label);
                if (pos != null && pos > fromPos) {
                    return label;
                }
            }
        }
        return null;
    }

    private List<CFGEdge> normalOutEdges(BasicBlock block) {
        List<CFGEdge> normalEdges = new ArrayList<>();
        for (CFGEdge edge : block.outEdges()) {
            if (edge.type() != CFGEdge.Type.EXCEPTION) {
                normalEdges.add(edge);
            }
        }
        return normalEdges;
    }

    private void emitHandlerBlock(InsnList insns, BasicBlock handlerBlock,
            Map<LabelNode, LabelNode> labelRemap, PipelineContext pctx,
            Map<BasicBlock, Long> flowKeyMap, int flowKeyVar, int flowMixVar,
            Map<BasicBlock, Integer> stateMap, int encodedStateVar, int stateMaskVar,
            int stateDeltaVar, int stateRotate, int tailSeedVar, int tailFlagVar,
            boolean zkmStyle, double tailChainIntensity, List<TailChain> tailChains,
            LabelNode loopStart, LabelNode loopEnd,
            IdentityHashMap<AbstractInsnNode, Integer> stackHeights,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks,
            Map<BasicBlock, Integer> blockSpillBases,
            Map<BasicBlock, List<LocalSlotState>> blockEntryLocals,
            Map<BasicBlock, Integer> blockLocalSpillBases) {
        boolean requiresStateTransition = !normalOutEdges(handlerBlock).isEmpty();
        AbstractInsnNode syncAnchor = requiresStateTransition
            ? findHandlerSyncAnchor(handlerBlock, stackHeights)
            : null;
        boolean syncedFlowKey = false;
        boolean emittedRealInsn = false;
        boolean waitingForExceptionConsumption = requiresStateTransition
            && handlerBlock.isExceptionHandler()
            && syncAnchor == null;
        for (AbstractInsnNode insn : handlerBlock.instructions()) {
            if (insn instanceof FrameNode) continue;
            if (insn instanceof LineNumberNode) continue;

            if (insn instanceof LabelNode origLabel) {
                LabelNode remapped = labelRemap.get(origLabel);
                if (remapped != null) insns.add(remapped);
                continue;
            }

            if (isTerminator(insn, handlerBlock)) continue;

            if (insn == syncAnchor && !syncedFlowKey) {
                emitFlowKeyStore(insns, flowKeyMap.getOrDefault(handlerBlock, 0L), flowKeyVar, flowMixVar);
                emitRuntimeFlowContextSync(insns, flowKeyVar);
                syncedFlowKey = true;
            }

            AbstractInsnNode clone = insn.clone(labelRemap);
            insns.add(clone);
            recordInstructionFlowKey(pctx, clone, flowKeyMap.getOrDefault(handlerBlock, 0L));
            emittedRealInsn = true;

            if (!syncedFlowKey && !waitingForExceptionConsumption) {
                emitFlowKeyStore(insns, flowKeyMap.getOrDefault(handlerBlock, 0L), flowKeyVar, flowMixVar);
                emitRuntimeFlowContextSync(insns, flowKeyVar);
                syncedFlowKey = true;
            }

            if (requiresFlowKeyResync(clone)) {
                emitRuntimeFlowContextSync(insns, flowKeyVar);
            }
        }

        if (syncAnchor == AFTER_HANDLER_SYNC_ANCHOR && !syncedFlowKey) {
            emitFlowKeyStore(insns, flowKeyMap.getOrDefault(handlerBlock, 0L), flowKeyVar, flowMixVar);
            emitRuntimeFlowContextSync(insns, flowKeyVar);
            syncedFlowKey = true;
            waitingForExceptionConsumption = false;
        }

        if (!syncedFlowKey && !emittedRealInsn) {
            emitFlowKeyStore(insns, flowKeyMap.getOrDefault(handlerBlock, 0L), flowKeyVar, flowMixVar);
            emitRuntimeFlowContextSync(insns, flowKeyVar);
        } else if (!syncedFlowKey && !waitingForExceptionConsumption) {
            emitFlowKeyStore(insns, flowKeyMap.getOrDefault(handlerBlock, 0L), flowKeyVar, flowMixVar);
            emitRuntimeFlowContextSync(insns, flowKeyVar);
        }

        emitStateTransition(insns, handlerBlock, stateMap, flowKeyMap, flowKeyVar, flowMixVar,
            encodedStateVar, stateMaskVar, stateDeltaVar, stateRotate, tailSeedVar, tailFlagVar,
            zkmStyle, tailChainIntensity, tailChains, loopStart, loopEnd,
            blockEntryStacks, blockSpillBases, blockEntryLocals, blockLocalSpillBases);
    }

    private AbstractInsnNode findHandlerSyncAnchor(BasicBlock handlerBlock,
            IdentityHashMap<AbstractInsnNode, Integer> stackHeights) {
        if (!handlerBlock.isExceptionHandler()) {
            return null;
        }

        for (AbstractInsnNode insn : handlerBlock.instructions()) {
            if (insn instanceof LabelNode || insn instanceof FrameNode || insn instanceof LineNumberNode) {
                continue;
            }
            Integer stackSize = stackHeights.get(insn);
            if (stackSize == null) {
                continue;
            }
            if (isTerminator(insn, handlerBlock)) {
                if (stackSize == 0) {
                    return AFTER_HANDLER_SYNC_ANCHOR;
                }
                continue;
            }
            if (stackSize == 0) {
                return insn;
            }
        }
        return null;
    }

    private boolean requiresFlowKeyResync(AbstractInsnNode insn) {
        return insn instanceof MethodInsnNode
            || insn instanceof InvokeDynamicInsnNode;
    }

    private String methodKey(L1Method method) {
        return method.owner().name() + '.' + method.name() + method.descriptor();
    }

    private boolean isZkmStyleEnabled(PipelineContext pctx) {
        TransformConfig config = pctx.config().transforms().get("controlFlowFlattening");
        if (config == null) return true;
        Object option = config.options().get(ZKM_STYLE_OPTION);
        return !(option instanceof Boolean enabled) || enabled;
    }

    private double tailChainIntensity(PipelineContext pctx, L1Method method) {
        TransformConfig config = pctx.config().transforms().get("controlFlowFlattening");
        double intensity = 0.7;
        if (config != null) {
            Object option = config.options().get(TAIL_CHAIN_INTENSITY_OPTION);
            if (option instanceof Number number) {
                intensity = Math.max(0.0, Math.min(1.0, number.doubleValue()));
            }
        }
        if (!method.tryCatchBlocks().isEmpty()) {
            double multiplier = doubleOption(config, TRY_CATCH_TAIL_CHAIN_MULTIPLIER_OPTION, 0.35);
            intensity *= multiplier;
            if (isEligibleTryCatchEntryPoint(method)) {
                intensity *= doubleOption(config, ENTRYPOINT_TAIL_CHAIN_MULTIPLIER_OPTION, 0.08);
            }
        }
        return Math.max(0.0, Math.min(1.0, intensity));
    }

    private boolean shouldUseTailChain(boolean zkmStyle, double tailChainIntensity, int variantSeed) {
        if (!zkmStyle || tailChainIntensity <= 0.0) return false;
        int bucket = Math.floorMod(variantSeed * 1103515245 + 12345, 1000);
        return bucket < (int) Math.round(tailChainIntensity * 1000.0);
    }

    private boolean isStructureSafe(L1Method method, PipelineContext pctx) {
        MethodSafetyStats stats = analyzeMethodStructure(method.instructions());
        TransformConfig config = pctx.config().transforms().get("controlFlowFlattening");
        boolean hasTryCatch = !method.tryCatchBlocks().isEmpty();
        boolean isEntryPoint = hasTryCatch && isEligibleTryCatchEntryPoint(method);

        if (hasTryCatch && !booleanOption(config, ALLOW_TRY_CATCH_METHODS_OPTION, true)) return false;
        if (hasTryCatch && booleanOption(config, TRY_CATCH_MAIN_ONLY_OPTION, true) && !isEntryPoint) {
            return false;
        }

        int maxTryCatchBlocks = intOption(config, MAX_TRY_CATCH_BLOCKS_OPTION, 18);
        if (isEntryPoint) {
            maxTryCatchBlocks = Math.max(maxTryCatchBlocks,
                intOption(config, ENTRYPOINT_MAX_TRY_CATCH_BLOCKS_OPTION, 64));
        }
        if (method.tryCatchBlocks().size() > maxTryCatchBlocks) return false;

        if (!isEntryPoint && stats.hasSwitch() && !booleanOption(config, ALLOW_SWITCH_METHODS_OPTION, false)) return false;
        if (!isEntryPoint && stats.hasMonitor() && !booleanOption(config, ALLOW_MONITOR_METHODS_OPTION, false)) return false;

        if (isEntryPoint) {
            return true;
        }

        int maxInstructionCount = intOption(config, MAX_INSTRUCTION_COUNT_OPTION, 180);
        if (hasTryCatch) {
            maxInstructionCount += intOption(config, TRY_CATCH_INSTRUCTION_BONUS_OPTION, 160);
        }
        if (method.instructionCount() > maxInstructionCount) return false;

        if (stats.backwardBranches() > intOption(config, MAX_BACKWARD_BRANCHES_OPTION, 2)) return false;

        int maxBranchCount = intOption(config, MAX_BRANCHES_OPTION, 16);
        if (hasTryCatch) {
            int bonusPerTryCatch = intOption(config, TRY_CATCH_BRANCH_BONUS_OPTION, 2);
            maxBranchCount += method.tryCatchBlocks().size() * bonusPerTryCatch;
        }
        return stats.branchCount() <= maxBranchCount;
    }

    private MethodSafetyStats analyzeMethodStructure(InsnList insns) {
        IdentityHashMap<AbstractInsnNode, Integer> positions = new IdentityHashMap<>();
        int index = 0;
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            positions.put(insn, index++);
        }

        int branchCount = 0;
        int backwardBranches = 0;
        boolean hasSwitch = false;
        boolean hasMonitor = false;

        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            int opcode = insn.getOpcode();
            if (opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT) {
                hasMonitor = true;
            }
            if (insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) {
                hasSwitch = true;
                branchCount++;
                continue;
            }
            if (insn instanceof JumpInsnNode jump) {
                branchCount++;
                Integer from = positions.get(insn);
                Integer target = positions.get(jump.label);
                if (from != null && target != null && target <= from) {
                    backwardBranches++;
                }
            }
        }

        return new MethodSafetyStats(branchCount, backwardBranches, hasSwitch, hasMonitor);
    }

    private boolean booleanOption(TransformConfig config, String key, boolean defaultValue) {
        if (config == null) return defaultValue;
        Object value = config.options().get(key);
        return value instanceof Boolean enabled ? enabled : defaultValue;
    }

    private int intOption(TransformConfig config, String key, int defaultValue) {
        if (config == null) return defaultValue;
        Object value = config.options().get(key);
        return value instanceof Number number ? Math.max(0, number.intValue()) : defaultValue;
    }

    private double doubleOption(TransformConfig config, String key, double defaultValue) {
        if (config == null) return defaultValue;
        Object value = config.options().get(key);
        return value instanceof Number number ? Math.max(0.0, number.doubleValue()) : defaultValue;
    }

    private boolean isEligibleTryCatchEntryPoint(L1Method method) {
        return method.isStatic()
            && "main".equals(method.name())
            && "([Ljava/lang/String;)V".equals(method.descriptor());
    }

    private long deriveMethodFlowSeed(long methodKey) {
        return dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.finalize_(
            dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.mix(methodKey ^ 0x4E454B4F464C4F57L,
                0x13579BDF2468ACE0L));
    }

    private long deriveBlockFlowKey(long methodFlowSeed, int state) {
        return dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.finalize_(
            dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.mix(methodFlowSeed, state));
    }

    private int foldFlowKey(long flowKey) {
        return foldMethodKey(flowKey ^ Long.rotateLeft(flowKey, 17));
    }

    private int foldMethodKey(long value) {
        int mixed = (int) (value ^ (value >>> 32));
        mixed ^= Integer.rotateLeft(mixed, 13);
        mixed ^= Integer.rotateRight(mixed, 7);
        return mixed != 0 ? mixed : 0x13579BDF;
    }

    private enum LocalInitKind {
        UNKNOWN,
        INT,
        FLOAT,
        LONG,
        DOUBLE,
        REFERENCE,
        RESERVED,
        CONFLICT
    }

    private enum StackSlotKind {
        INT(Opcodes.ILOAD, Opcodes.ISTORE, 1),
        FLOAT(Opcodes.FLOAD, Opcodes.FSTORE, 1),
        LONG(Opcodes.LLOAD, Opcodes.LSTORE, 2),
        DOUBLE(Opcodes.DLOAD, Opcodes.DSTORE, 2),
        REFERENCE(Opcodes.ALOAD, Opcodes.ASTORE, 1);

        private final int loadOpcode;
        private final int storeOpcode;
        private final int slotSize;

        StackSlotKind(int loadOpcode, int storeOpcode, int slotSize) {
            this.loadOpcode = loadOpcode;
            this.storeOpcode = storeOpcode;
            this.slotSize = slotSize;
        }

        int loadOpcode() {
            return loadOpcode;
        }

        int storeOpcode() {
            return storeOpcode;
        }

        int slotSize() {
            return slotSize;
        }
    }

    private record LocalFlowSummary(BitSet uses, BitSet defs) {}

    private record LocalSlotState(int slot, StackSlotKind kind) {}

    private record MethodSafetyStats(int branchCount, int backwardBranches, boolean hasSwitch, boolean hasMonitor) {}

    private record TailChain(LabelNode entry, InsnList body) {}

    private record RemappedTryCatchRange(LabelNode start, LabelNode end, LabelNode handler) {}

    private AbstractInsnNode findLastRealInsn(BasicBlock block) {
        for (int i = block.instructions().size() - 1; i >= 0; i--) {
            AbstractInsnNode insn = block.instructions().get(i);
            if (AsmUtil.isRealInstruction(insn)) return insn;
        }
        return null;
    }

    private void emitSafetyReturn(InsnList insns, org.objectweb.asm.Type retType) {
        switch (retType.getSort()) {
            case org.objectweb.asm.Type.VOID -> insns.add(new InsnNode(Opcodes.RETURN));
            case org.objectweb.asm.Type.INT, org.objectweb.asm.Type.BOOLEAN,
                 org.objectweb.asm.Type.BYTE, org.objectweb.asm.Type.CHAR,
                 org.objectweb.asm.Type.SHORT -> {
                insns.add(new InsnNode(Opcodes.ICONST_0));
                insns.add(new InsnNode(Opcodes.IRETURN));
            }
            case org.objectweb.asm.Type.LONG -> {
                insns.add(new InsnNode(Opcodes.LCONST_0));
                insns.add(new InsnNode(Opcodes.LRETURN));
            }
            case org.objectweb.asm.Type.FLOAT -> {
                insns.add(new InsnNode(Opcodes.FCONST_0));
                insns.add(new InsnNode(Opcodes.FRETURN));
            }
            case org.objectweb.asm.Type.DOUBLE -> {
                insns.add(new InsnNode(Opcodes.DCONST_0));
                insns.add(new InsnNode(Opcodes.DRETURN));
            }
            default -> {
                insns.add(new InsnNode(Opcodes.ACONST_NULL));
                insns.add(new InsnNode(Opcodes.ARETURN));
            }
        }
    }
}
