package dev.nekoobfuscator.native_.translator;

import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.ir.l1.L1Method;
import dev.nekoobfuscator.core.ir.l3.CFunction;
import dev.nekoobfuscator.core.ir.l3.CStatement;
import dev.nekoobfuscator.core.ir.l3.CType;
import dev.nekoobfuscator.core.ir.l3.CVariable;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class NativeTranslator {
    private static final String METHOD_HANDLE_DESC = "Ljava/lang/invoke/MethodHandle;";
    private static final String LAMBDA_FORM_HIDDEN_DESC = "Ljava/lang/invoke/LambdaForm$Hidden;";
    private static final String JDK_HIDDEN_DESC = "Ljdk/internal/vm/annotation/Hidden;";

    private final CCodeGenerator codeGenerator;
    private int concatLiteralIndex = 0;

    public NativeTranslator(String outputPrefix, boolean obfuscateJniSlotDispatch, boolean cacheJniIds, long masterSeed) {
        this.codeGenerator = new CCodeGenerator(masterSeed);
    }

    public TranslationResult translate(List<MethodSelection> selectedMethods) {
        Map<String, L1Class> ownersByName = new HashMap<>();
        for (MethodSelection selection : selectedMethods) {
            ownersByName.putIfAbsent(selection.owner().name(), selection.owner());
        }
        return translate(selectedMethods, ownersByName.values());
    }

    public TranslationResult translate(List<MethodSelection> selectedMethods, Iterable<L1Class> applicationClasses) {
        List<NativeMethodBinding> bindings = new ArrayList<>();
        Map<String, NativeMethodBinding> bindingMap = new HashMap<>();
        Map<String, L1Class> ownersByName = new HashMap<>();
        for (MethodSelection selection : selectedMethods) {
            ownersByName.putIfAbsent(selection.owner().name(), selection.owner());
        }
        Map<String, L1Class> applicationClassesByName = new HashMap<>();
        for (L1Class applicationClass : applicationClasses) {
            applicationClassesByName.putIfAbsent(applicationClass.name(), applicationClass);
        }
        for (int i = 0; i < selectedMethods.size(); i++) {
            MethodSelection selection = selectedMethods.get(i);
            String cFunctionName = "neko_native_entry_" + i;
            String rawFunctionName = "neko_native_impl_" + i;
            NativeMethodBinding binding = new NativeMethodBinding(
                selection.owner().name(),
                selection.method().name(),
                selection.method().descriptor(),
                cFunctionName,
                rawFunctionName,
                null,
                null,
                selection.method().isStatic(),
                isDirectCallSafe(selection.owner(), selection.method(), applicationClassesByName)
            );
            bindings.add(binding);
            bindingMap.put(bindingKey(binding.ownerInternalName(), binding.methodName(), binding.descriptor()), binding);
        }

        OpcodeTranslator opcodeTranslator = new OpcodeTranslator(codeGenerator, bindingMap, new MethodHandleBridgeRegistry(ownersByName));
        List<CFunction> functions = new ArrayList<>(selectedMethods.size());
        for (int i = 0; i < selectedMethods.size(); i++) {
            codeGenerator.registerBindingOwner(selectedMethods.get(i).owner().name());
            functions.add(translateMethod(selectedMethods.get(i), bindings.get(i), opcodeTranslator));
        }

        codeGenerator.configureStringCacheCount(opcodeTranslator.stringCacheCount());

        String source = codeGenerator.generateSource(functions, bindings);
        String header = codeGenerator.generateHeader(bindings);
        return new TranslationResult(source, header, bindings.size(), bindings);
    }

    private CFunction translateMethod(MethodSelection selection, NativeMethodBinding binding, OpcodeTranslator opcodes) {
        L1Method method = selection.method();
        MethodNode node = method.asmNode();
        Type returnType = Type.getReturnType(method.descriptor());
        CType cReturnType = mapType(returnType);

        List<CVariable> params = new ArrayList<>();
        params.add(new CVariable("thread", CType.JOBJECT, 0));
        params.add(new CVariable("env", CType.JOBJECT, 0));
        params.add(new CVariable(method.isStatic() ? "clazz" : "self", method.isStatic() ? CType.JCLASS : CType.JOBJECT, 1));

        Type[] argTypes = Type.getArgumentTypes(method.descriptor());
        int paramIndex = 2;
        int argsLocalsSize = method.isStatic() ? 0 : 1;
        for (int i = 0; i < argTypes.length; i++) {
            params.add(new CVariable("p" + i, mapType(argTypes[i]), paramIndex++));
            argsLocalsSize += argTypes[i].getSize();
        }

        CFunction fn = new CFunction(binding.rawFunctionName(), cReturnType, params);
        fn.setMaxStack(Math.max(method.maxStack(), 16));
        fn.setMaxLocals(Math.max(method.maxLocals(), argsLocalsSize));

        Map<LabelNode, String> labelMap = buildLabelMap(node);
        Map<AbstractInsnNode, Integer> pcMap = buildPcMap(node);
        Map<Integer, List<TryHandler>> activeHandlers = buildActiveHandlers(method, labelMap, pcMap);

        emitParamToLocals(fn, method, argTypes);
        boolean shadowEnabled = true;
        opcodes.beginMethod(selection.owner().name(), selection.method().name(), selection.method().descriptor(), selection.method().isStatic(), shadowEnabled);
        fn.addStatement(new CStatement.RawC(codeGenerator.ownerStringBindCall(selection.owner().name())));
        fn.addStatement(new CStatement.RawC(
            "neko_shadow_push(\"" + c(selection.owner().name()) + "\", \"" + c(selection.method().name()) + "\", \""
                + c(OpcodeTranslator.simpleSourceFileName(selection.owner().name())) + "\");"
        ));
        /* Tail-call landing pad: tryTailRecursion rewrites self-recursion
         * into `goto __neko_tco_entry`. Emitted unconditionally so unrelated
         * label numbering (L0/L1/…) is unaffected. */
        fn.addStatement(new CStatement.Label("__neko_tco_entry"));

        /* --- exception-check coalescing state ---
         * HotSpot's interpreter polls _pending_exception only at safepoints
         * (back-edges, method exits, and try-region transitions), not after
         * every JNI call. We mirror that: instead of emitting a check after
         * every potentially-throwing op, defer it as `pendingHandlers` and
         * flush it at:
         *   - A control-flow boundary that needs accurate dispatch (jump,
         *     switch, return, athrow, label that's a branch target)
         *   - An instruction whose active handler set differs from the
         *     pending op's (we're crossing a try-region edge, so an exception
         *     thrown by the pending op must still be caught by the OLDER
         *     handlers, not the new ones)
         * If the method ends without flushing, the pending exception is
         * preserved naturally — the JVM observes _pending_exception when our
         * native function returns. */
        List<TryHandler> pendingHandlers = null;
        for (AbstractInsnNode insn = node.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode labelNode) {
                if (pendingHandlers != null) {
                    fn.addStatement(new CStatement.RawC(renderExceptionDispatch(pendingHandlers, selection.owner().name())));
                    pendingHandlers = null;
                }
                fn.addStatement(new CStatement.Label(labelMap.get(labelNode)));
                continue;
            }
            if (insn instanceof LineNumberNode || insn instanceof FrameNode) {
                continue;
            }
            if (pendingHandlers != null && needsCheckBefore(insn, pendingHandlers, activeHandlers, pcMap)) {
                fn.addStatement(new CStatement.RawC(renderExceptionDispatch(pendingHandlers, selection.owner().name())));
                pendingHandlers = null;
            }
            StringConcatPattern concatPattern = renderedStringConcatPattern(insn, selection.owner().name());
            OpcodeTranslator.FusedTranslation fused = (concatPattern == null) ? tryFusedAALoad(opcodes, insn, activeHandlers, pcMap) : null;
            TailCallRewrite tail = (concatPattern == null && fused == null)
                ? tryTailRecursion(insn, selection, argTypes, activeHandlers, pcMap)
                : null;
            if (insn instanceof JumpInsnNode jumpInsn) {
                fn.addStatement(opcodes.translateJump(jumpInsn, labelMap.get(jumpInsn.label)));
            } else if (insn instanceof TableSwitchInsnNode tableSwitchInsn) {
                fn.addStatement(new CStatement.RawC(renderTableSwitch(tableSwitchInsn, labelMap)));
            } else if (insn instanceof LookupSwitchInsnNode lookupSwitchInsn) {
                fn.addStatement(new CStatement.RawC(renderLookupSwitch(lookupSwitchInsn, labelMap)));
            } else if (concatPattern != null) {
                fn.addStatement(new CStatement.RawC(concatPattern.code));
                insn = concatPattern.lastInsn;
            } else if (fused != null) {
                fn.addStatement(new CStatement.RawC(fused.code()));
                insn = fused.lastInsn();
            } else if (tail != null) {
                fn.addStatement(new CStatement.RawC(tail.code));
                pendingHandlers = null;
                continue;
            } else {
                for (CStatement statement : opcodes.translate(insn)) {
                    fn.addStatement(statement);
                }
            }

            if (isRealInsn(insn) && isPotentiallyExcepting(insn)) {
                List<TryHandler> handlers = activeHandlers.getOrDefault(pcMap.get(insn), List.of());
                pendingHandlers = handlers;
            }
        }
        if (pendingHandlers != null) {
            fn.addStatement(new CStatement.RawC(renderExceptionDispatch(pendingHandlers, selection.owner().name())));
        }

        fn.addStatement(new CStatement.Label("__neko_exception_exit"));
        emitDefaultReturn(fn, cReturnType, shadowEnabled);
        return fn;
    }

    private Map<LabelNode, String> buildLabelMap(MethodNode node) {
        Map<LabelNode, String> labels = new LinkedHashMap<>();
        int counter = 0;
        for (AbstractInsnNode insn = node.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode labelNode) {
                labels.put(labelNode, "L" + counter++);
            }
        }
        return labels;
    }

    private Map<AbstractInsnNode, Integer> buildPcMap(MethodNode node) {
        Map<AbstractInsnNode, Integer> pcMap = new IdentityHashMap<>();
        int pc = 0;
        for (AbstractInsnNode insn = node.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (isRealInsn(insn)) {
                pcMap.put(insn, pc++);
            }
        }
        return pcMap;
    }

    private Map<Integer, List<TryHandler>> buildActiveHandlers(L1Method method, Map<LabelNode, String> labelMap, Map<AbstractInsnNode, Integer> pcMap) {
        Map<Integer, List<TryHandler>> active = new HashMap<>();
        for (TryCatchBlockNode tcb : method.tryCatchBlocks()) {
            int startPc = resolvePcAtOrAfter(tcb.start, pcMap);
            int endPc = resolvePcAtOrAfter(tcb.end, pcMap);
            if (startPc < 0 || endPc < 0 || startPc >= endPc) {
                continue;
            }
            TryHandler handler = new TryHandler(labelMap.get(tcb.handler), tcb.type);
            for (int pc = startPc; pc < endPc; pc++) {
                active.computeIfAbsent(pc, ignored -> new ArrayList<>()).add(handler);
            }
        }
        return active;
    }

    private int resolvePcAtOrAfter(AbstractInsnNode node, Map<AbstractInsnNode, Integer> pcMap) {
        for (AbstractInsnNode cur = node; cur != null; cur = cur.getNext()) {
            Integer pc = pcMap.get(cur);
            if (pc != null) {
                return pc;
            }
        }
        return -1;
    }

    private void emitParamToLocals(CFunction fn, L1Method method, Type[] argTypes) {
        int localIndex = 0;
        if (!method.isStatic()) {
            fn.addStatement(new CStatement.RawC("locals[0].o = self;"));
            localIndex = 1;
        }
        for (int i = 0; i < argTypes.length; i++) {
            fn.addStatement(new CStatement.RawC("locals[" + localIndex + "]." + slotField(argTypes[i]) + " = p" + i + ";"));
            localIndex += argTypes[i].getSize();
        }
    }

    private boolean isRealInsn(AbstractInsnNode insn) {
        return !(insn instanceof LabelNode) && !(insn instanceof LineNumberNode) && !(insn instanceof FrameNode);
    }

    private static final class MethodHandleBridgeRegistry implements OpcodeTranslator.MethodHandleBridgeFactory {
        private final Map<String, L1Class> ownersByName;
        private final Map<String, Integer> bridgeCounters = new HashMap<>();

        private MethodHandleBridgeRegistry(Map<String, L1Class> ownersByName) {
            this.ownersByName = ownersByName;
        }

        @Override
        public OpcodeTranslator.MethodHandleBridge ensureBridge(String ownerInternalName, String invokeDescriptor) {
            L1Class owner = ownersByName.get(ownerInternalName);
            if (owner == null) {
                return null;
            }

            Type[] invokeArgs = Type.getArgumentTypes(invokeDescriptor);
            Type[] bridgeArgs = new Type[invokeArgs.length + 1];
            bridgeArgs[0] = Type.getType(METHOD_HANDLE_DESC);
            System.arraycopy(invokeArgs, 0, bridgeArgs, 1, invokeArgs.length);

            Type returnType = Type.getReturnType(invokeDescriptor);
            String bridgeName = "neko$mh$" + bridgeCounters.merge(ownerInternalName, 1, Integer::sum);
            String bridgeDescriptor = Type.getMethodDescriptor(returnType, bridgeArgs);

            MethodNode bridge = new MethodNode(Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, bridgeName, bridgeDescriptor, null, null);
            if (owner.version() >= 53) {
                addHiddenAnnotation(bridge, LAMBDA_FORM_HIDDEN_DESC);
                addHiddenAnnotation(bridge, JDK_HIDDEN_DESC);
            }

            int localIndex = 0;
            for (Type argumentType : bridgeArgs) {
                bridge.instructions.add(new VarInsnNode(argumentType.getOpcode(Opcodes.ILOAD), localIndex));
                localIndex += argumentType.getSize();
            }
            bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invokeExact", invokeDescriptor, false));
            bridge.instructions.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
            bridge.maxStack = Math.max(localIndex, returnType.getSize());
            bridge.maxLocals = localIndex;

            owner.asmNode().methods.add(bridge);
            owner.methods().add(new L1Method(owner, bridge));
            owner.markDirty();
            return new OpcodeTranslator.MethodHandleBridge(ownerInternalName, bridgeName, bridgeDescriptor);
        }

        private void addHiddenAnnotation(MethodNode bridge, String descriptor) {
            if (bridge.visibleAnnotations == null) {
                bridge.visibleAnnotations = new ArrayList<>();
            }
            bridge.visibleAnnotations.add(new AnnotationNode(descriptor));
        }
    }

    /**
     * Decide whether the deferred exception check (for the most recent
     * potentially-throwing op, with its handler set) MUST be flushed before
     * the next instruction. Required when:
     *   - The next instruction's active handlers differ from the pending
     *     op's. (Crossing a try-region boundary; the pending op's exception
     *     must dispatch to its OWN handler set, not the new one.)
     *   - The next instruction is a branch / switch / return / athrow.
     *     Branches must observe the exception so dispatch is correct;
     *     returns are safe in principle (JVM sees pending exception on
     *     return) but skipping a check before athrow could mask the
     *     original exception with the new one.
     * Sequential straight-line instructions with the same handler set don't
     * trigger a flush — letting subsequent JNI calls become no-ops on
     * pending exception is harmless and the deferred check at the next
     * boundary catches it.
     */
    private boolean needsCheckBefore(
        AbstractInsnNode insn,
        List<TryHandler> pendingHandlers,
        Map<Integer, List<TryHandler>> activeHandlers,
        Map<AbstractInsnNode, Integer> pcMap
    ) {
        Integer pc = pcMap.get(insn);
        if (pc != null) {
            List<TryHandler> here = activeHandlers.getOrDefault(pc, List.of());
            if (!here.equals(pendingHandlers)) return true;
        }
        if (insn instanceof JumpInsnNode
            || insn instanceof TableSwitchInsnNode
            || insn instanceof LookupSwitchInsnNode) {
            return true;
        }
        int op = insn.getOpcode();
        return op == Opcodes.IRETURN
            || op == Opcodes.LRETURN
            || op == Opcodes.FRETURN
            || op == Opcodes.DRETURN
            || op == Opcodes.ARETURN
            || op == Opcodes.RETURN
            || op == Opcodes.ATHROW;
    }

    private record TailCallRewrite(String code, AbstractInsnNode lastInsn) {}

    /**
     * Tail-call elimination for self-recursion: when an INVOKESTATIC /
     * INVOKESPECIAL targets the current method and is in tail position
     * (immediately followed by a matching XRETURN, modulo labels/lines/frames),
     * rewrite the call into a `goto L0` that re-enters the method body with
     * the new argument values written into the local table.
     *
     * Mirrors what HotSpot's interpreter does for self-static recursion in
     * the rewriter — eliminates the JNI stack-frame and shadow-stack push for
     * every recursive level. Universal for any recursive Java method, not
     * just hand-picked ones.
     *
     * Skipped when an exception handler is active over the call site (the
     * handler must observe the call frame).
     */
    private TailCallRewrite tryTailRecursion(
        AbstractInsnNode insn,
        MethodSelection selection,
        Type[] argTypes,
        Map<Integer, List<TryHandler>> activeHandlers,
        Map<AbstractInsnNode, Integer> pcMap
    ) {
        if (!(insn instanceof MethodInsnNode mi)) return null;
        int opcode = mi.getOpcode();
        if (opcode != Opcodes.INVOKESTATIC && opcode != Opcodes.INVOKESPECIAL) return null;
        L1Method current = selection.method();
        if (!mi.owner.equals(selection.owner().name())
            || !mi.name.equals(current.name())
            || !mi.desc.equals(current.descriptor())) {
            return null;
        }
        boolean staticCall = (opcode == Opcodes.INVOKESTATIC);
        if (staticCall != current.isStatic()) return null;
        Integer callPc = pcMap.get(insn);
        if (callPc == null) return null;
        if (!activeHandlers.getOrDefault(callPc, List.of()).isEmpty()) return null;
        AbstractInsnNode next = nextRealInsn(insn);
        if (next == null) return null;
        Type returnType = Type.getReturnType(current.descriptor());
        if (!isMatchingReturn(next.getOpcode(), returnType)) return null;

        StringBuilder sb = new StringBuilder("{ /* tail-call → goto L0 */ ");
        for (int i = argTypes.length - 1; i >= 0; i--) {
            sb.append(jniTypeName(argTypes[i])).append(" __tco").append(i).append(" = ").append(popForType(argTypes[i])).append("; ");
        }
        if (!staticCall) {
            sb.append("jobject __tco_recv = POP_O(); ");
        }
        int localIndex = 0;
        if (!staticCall) {
            sb.append("locals[0].o = __tco_recv; ");
            localIndex = 1;
        }
        for (int i = 0; i < argTypes.length; i++) {
            sb.append("locals[").append(localIndex).append("].").append(slotField(argTypes[i])).append(" = __tco").append(i).append("; ");
            localIndex += argTypes[i].getSize();
        }
        sb.append("sp = 0; goto __neko_tco_entry; }");
        return new TailCallRewrite(sb.toString(), next);
    }

    private boolean isMatchingReturn(int opcode, Type returnType) {
        return switch (returnType.getSort()) {
            case Type.VOID -> opcode == Opcodes.RETURN;
            case Type.LONG -> opcode == Opcodes.LRETURN;
            case Type.FLOAT -> opcode == Opcodes.FRETURN;
            case Type.DOUBLE -> opcode == Opcodes.DRETURN;
            case Type.OBJECT, Type.ARRAY -> opcode == Opcodes.ARETURN;
            default -> opcode == Opcodes.IRETURN;
        };
    }

    private String popForType(Type t) {
        return switch (t.getSort()) {
            case Type.LONG -> "POP_L()";
            case Type.FLOAT -> "POP_F()";
            case Type.DOUBLE -> "POP_D()";
            case Type.OBJECT, Type.ARRAY -> "POP_O()";
            default -> "POP_I()";
        };
    }

    private String jniTypeName(Type t) {
        return switch (t.getSort()) {
            case Type.BOOLEAN -> "jboolean";
            case Type.BYTE -> "jbyte";
            case Type.CHAR -> "jchar";
            case Type.SHORT -> "jshort";
            case Type.INT -> "jint";
            case Type.LONG -> "jlong";
            case Type.FLOAT -> "jfloat";
            case Type.DOUBLE -> "jdouble";
            case Type.OBJECT, Type.ARRAY -> "jobject";
            case Type.VOID -> "void";
            default -> "jint";
        };
    }

    private OpcodeTranslator.FusedTranslation tryFusedAALoad(
        OpcodeTranslator opcodes,
        AbstractInsnNode insn,
        Map<Integer, List<TryHandler>> activeHandlers,
        Map<AbstractInsnNode, Integer> pcMap
    ) {
        if (insn.getOpcode() != Opcodes.AALOAD) return null;
        OpcodeTranslator.FusedTranslation candidate = opcodes.tryFuseArrayLoad(insn);
        if (candidate == null) return null;
        Integer aaloadPc = pcMap.get(insn);
        Integer lastPc = pcMap.get(candidate.lastInsn());
        if (aaloadPc == null || lastPc == null) return null;
        List<TryHandler> aaloadHandlers = activeHandlers.getOrDefault(aaloadPc, List.of());
        List<TryHandler> lastHandlers = activeHandlers.getOrDefault(lastPc, List.of());
        if (!aaloadHandlers.equals(lastHandlers)) return null;
        return candidate;
    }

    private StringConcatPattern renderedStringConcatPattern(AbstractInsnNode start, String currentOwnerInternalName) {
        if (!(start instanceof org.objectweb.asm.tree.TypeInsnNode newInsn)
            || start.getOpcode() != Opcodes.NEW
            || !"java/lang/StringBuilder".equals(newInsn.desc)) {
            return null;
        }
        AbstractInsnNode dup = nextRealInsn(start);
        AbstractInsnNode init = nextRealInsn(dup);
        AbstractInsnNode first = nextRealInsn(init);
        AbstractInsnNode append1 = nextRealInsn(first);
        AbstractInsnNode second = nextRealInsn(append1);
        AbstractInsnNode append2 = nextRealInsn(second);
        AbstractInsnNode toString = nextRealInsn(append2);
        if (!(dup instanceof InsnNode dupInsn) || dupInsn.getOpcode() != Opcodes.DUP) {
            return null;
        }
        if (!(init instanceof MethodInsnNode initCall)
            || initCall.getOpcode() != Opcodes.INVOKESPECIAL
            || !"java/lang/StringBuilder".equals(initCall.owner)
            || !"<init>".equals(initCall.name)
            || !"()V".equals(initCall.desc)) {
            return null;
        }
        if (!(append1 instanceof MethodInsnNode appendCall1)
            || appendCall1.getOpcode() != Opcodes.INVOKEVIRTUAL
            || !"java/lang/StringBuilder".equals(appendCall1.owner)
            || !"append".equals(appendCall1.name)
            || !"(Ljava/lang/String;)Ljava/lang/StringBuilder;".equals(appendCall1.desc)) {
            return null;
        }
        if (!(append2 instanceof MethodInsnNode appendCall2)
            || appendCall2.getOpcode() != Opcodes.INVOKEVIRTUAL
            || !"java/lang/StringBuilder".equals(appendCall2.owner)
            || !"append".equals(appendCall2.name)
            || !"(Ljava/lang/String;)Ljava/lang/StringBuilder;".equals(appendCall2.desc)) {
            return null;
        }
        if (!(toString instanceof MethodInsnNode toStringCall)
            || toStringCall.getOpcode() != Opcodes.INVOKEVIRTUAL
            || !"java/lang/StringBuilder".equals(toStringCall.owner)
            || !"toString".equals(toStringCall.name)
            || !"()Ljava/lang/String;".equals(toStringCall.desc)) {
            return null;
        }
        StringProducer firstProducer = stringProducer(first);
        if (firstProducer == null) {
            return null;
        }
        String code;
        codeGenerator.registerOwnerFieldReference(currentOwnerInternalName, "java/lang/String", "value", "[B", false);
        codeGenerator.registerOwnerFieldReference(currentOwnerInternalName, "java/lang/String", "coder", "B", false);
        if (second instanceof LdcInsnNode ldcInsn && ldcInsn.cst instanceof String s) {
            StringProducer secondProducer = literalStringProducer(s);
            code = "{ " + firstProducer.prefix + secondProducer.prefix
                + "jstring __lhs = (jstring)(" + firstProducer.expr + " == NULL ? neko_string_null(env) : " + firstProducer.expr + "); "
                + "jstring __rhs = " + secondProducer.expr + " == NULL ? neko_string_null(env) : " + secondProducer.expr + "; "
                + "jobject __fastConcat = neko_require_fast_string_concat(thread, env, __lhs, __rhs, "
                + codeGenerator.fieldOffsetSlotName("java/lang/String", "value", "[B", false) + ", "
                + codeGenerator.fieldOffsetSlotName("java/lang/String", "coder", "B", false) + "); "
                + "PUSH_O(__fastConcat); }";
        } else {
            StringProducer secondProducer = stringProducer(second);
            if (secondProducer == null) {
                return null;
            }
            code = "{ " + firstProducer.prefix + secondProducer.prefix
                + "jstring __lhs = (jstring)(" + firstProducer.expr + " == NULL ? neko_string_null(env) : " + firstProducer.expr + "); "
                + "jstring __rhs = (jstring)(" + secondProducer.expr + " == NULL ? neko_string_null(env) : " + secondProducer.expr + "); "
                + "jobject __fastConcat = neko_require_fast_string_concat(thread, env, __lhs, __rhs, "
                + codeGenerator.fieldOffsetSlotName("java/lang/String", "value", "[B", false) + ", "
                + codeGenerator.fieldOffsetSlotName("java/lang/String", "coder", "B", false) + "); "
                + "PUSH_O(__fastConcat); }";
        }
        return new StringConcatPattern(code, toString);
    }

    private AbstractInsnNode nextRealInsn(AbstractInsnNode insn) {
        for (AbstractInsnNode cur = insn == null ? null : insn.getNext(); cur != null; cur = cur.getNext()) {
            if (isRealInsn(cur)) {
                return cur;
            }
        }
        return null;
    }

    private StringProducer stringProducer(AbstractInsnNode insn) {
        if (insn instanceof VarInsnNode varInsn && varInsn.getOpcode() == Opcodes.ALOAD) {
            return new StringProducer("", "locals[" + varInsn.var + "].o");
        }
        if (insn instanceof LdcInsnNode ldcInsn && ldcInsn.cst instanceof String s) {
            return literalStringProducer(s);
        }
        return null;
    }

    private StringProducer literalStringProducer(String value) {
        String literalVar = "__neko_concat_lit_" + concatLiteralIndex++;
        String prefix = "static jstring " + literalVar + " = NULL; "
            + "static void *" + literalVar + "_oop = NULL; "
            + "neko_bind_string_slot(thread, env, &" + literalVar + ", &" + literalVar + "_oop, \"" + c(value) + "\"); ";
        return new StringProducer(prefix, literalVar);
    }

    private String renderTableSwitch(TableSwitchInsnNode insn, Map<LabelNode, String> labelMap) {
        StringBuilder sb = new StringBuilder();
        sb.append("{ jint __key = POP_I(); switch(__key) {");
        for (int i = 0; i < insn.labels.size(); i++) {
            sb.append(" case ").append(insn.min + i).append(": goto ").append(labelMap.get(insn.labels.get(i))).append(';');
        }
        sb.append(" default: goto ").append(labelMap.get(insn.dflt)).append("; } }");
        return sb.toString();
    }

    private String renderLookupSwitch(LookupSwitchInsnNode insn, Map<LabelNode, String> labelMap) {
        StringBuilder sb = new StringBuilder();
        sb.append("{ jint __key = POP_I(); switch(__key) {");
        for (int i = 0; i < insn.keys.size(); i++) {
            sb.append(" case ").append(insn.keys.get(i)).append(": goto ").append(labelMap.get(insn.labels.get(i))).append(';');
        }
        sb.append(" default: goto ").append(labelMap.get(insn.dflt)).append("; } }");
        return sb.toString();
    }

    private String renderExceptionDispatch(List<TryHandler> handlers, String bindingOwner) {
        StringBuilder sb = new StringBuilder();
        if (handlers.isEmpty()) {
            sb.append("if (neko_pending_exception_oop(thread) != NULL) { goto __neko_exception_exit; }");
            return sb.toString();
        }
        sb.append("{ jthrowable __exc = neko_take_pending_exception(thread); if (__exc != NULL) { ");
        for (TryHandler handler : handlers) {
            if (handler.exceptionType == null) {
                sb.append("sp = 0; PUSH_O(__exc); goto ").append(handler.handlerLabel).append("; ");
            } else {
                sb.append("{ jclass __hcls = ").append(cachedHandlerClassExpression(bindingOwner, handler.exceptionType)).append("; ");
                sb.append("if (neko_fast_is_instance_of(env, __exc, __hcls)) { sp = 0; PUSH_O(__exc); goto ").append(handler.handlerLabel).append("; } }");
            }
        }
        sb.append("neko_set_pending_exception(thread, __exc); goto __neko_exception_exit; } }");
        return sb.toString();
    }

    private String cachedHandlerClassExpression(String bindingOwner, String exceptionType) {
        codeGenerator.registerOwnerClassReference(bindingOwner, exceptionType);
        return "neko_bound_class(thread, env, " + codeGenerator.classSlotName(exceptionType) + ", \"" + c(exceptionType) + "\")";
    }

    private boolean isPotentiallyExcepting(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (insn instanceof org.objectweb.asm.tree.FieldInsnNode fi
            && (opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC)
            && fi.desc.length() == 1
            && "ZBCSIJFD".indexOf(fi.desc.charAt(0)) >= 0) {
            return false;
        }
        return switch (opcode) {
            case Opcodes.NEW,
                 Opcodes.NEWARRAY,
                 Opcodes.ANEWARRAY,
                 Opcodes.MULTIANEWARRAY,
                 Opcodes.ATHROW,
                 Opcodes.CHECKCAST,
                 Opcodes.INVOKEVIRTUAL,
                 Opcodes.INVOKEINTERFACE,
                 Opcodes.INVOKESPECIAL,
                 Opcodes.INVOKESTATIC,
                 Opcodes.INVOKEDYNAMIC,
                 Opcodes.AASTORE,
                 Opcodes.ARRAYLENGTH,
                 Opcodes.GETFIELD,
                 Opcodes.PUTFIELD,
                 Opcodes.GETSTATIC,
                 Opcodes.PUTSTATIC,
                 Opcodes.IDIV,
                 Opcodes.IREM,
                 Opcodes.LDIV,
                 Opcodes.LREM,
                 Opcodes.MONITORENTER,
                 Opcodes.MONITOREXIT -> true;
            default -> false;
        };
    }

    private void emitDefaultReturn(CFunction function, CType returnType, boolean shadowEnabled) {
        if (shadowEnabled) {
            function.addStatement(new CStatement.RawC("neko_shadow_pop();"));
        }
        switch (returnType) {
            case VOID -> function.addStatement(new CStatement.ReturnVoid());
            case JLONG -> function.addStatement(new CStatement.RawC("return (jlong)0;"));
            case JFLOAT -> function.addStatement(new CStatement.RawC("return (jfloat)0;"));
            case JDOUBLE -> function.addStatement(new CStatement.RawC("return (jdouble)0;"));
            case JOBJECT, JCLASS, JSTRING, JARRAY -> function.addStatement(new CStatement.RawC("return NULL;"));
            default -> function.addStatement(new CStatement.RawC("return (" + returnType.jniName() + ")0;"));
        }
    }

    private CType mapType(Type type) {
        return switch (type.getSort()) {
            case Type.VOID -> CType.VOID;
            case Type.BOOLEAN -> CType.JBOOLEAN;
            case Type.CHAR -> CType.JCHAR;
            case Type.BYTE -> CType.JBYTE;
            case Type.SHORT -> CType.JSHORT;
            case Type.INT -> CType.JINT;
            case Type.FLOAT -> CType.JFLOAT;
            case Type.LONG -> CType.JLONG;
            case Type.DOUBLE -> CType.JDOUBLE;
            case Type.ARRAY -> CType.JARRAY;
            default -> CType.JOBJECT;
        };
    }

    private String slotField(Type type) {
        return switch (type.getSort()) {
            case Type.LONG -> "j";
            case Type.FLOAT -> "f";
            case Type.DOUBLE -> "d";
            case Type.ARRAY, Type.OBJECT -> "o";
            default -> "i";
        };
    }

    private String bindingKey(String owner, String name, String desc) {
        return owner + '#' + name + desc;
    }

    private boolean isDirectCallSafe(L1Class owner, L1Method method, Map<String, L1Class> applicationClassesByName) {
        if (method.isStatic()) {
            return true;
        }
        int access = method.access();
        if ((access & (Opcodes.ACC_FINAL | Opcodes.ACC_PRIVATE)) != 0
            || (owner.access() & Opcodes.ACC_FINAL) != 0) {
            return true;
        }
        if ((access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0) {
            return false;
        }
        for (L1Class candidate : applicationClassesByName.values()) {
            if (candidate.name().equals(owner.name())) {
                continue;
            }
            if (!isSubclassOf(candidate, owner.name(), applicationClassesByName)) {
                continue;
            }
            if (declaresVirtualOverride(candidate, method)) {
                return false;
            }
        }
        return true;
    }

    private boolean isSubclassOf(L1Class candidate, String ownerName, Map<String, L1Class> applicationClassesByName) {
        String cursor = candidate.asmNode().superName;
        while (cursor != null) {
            if (cursor.equals(ownerName)) {
                return true;
            }
            L1Class next = applicationClassesByName.get(cursor);
            if (next == null) {
                return false;
            }
            cursor = next.asmNode().superName;
        }
        return false;
    }

    private boolean declaresVirtualOverride(L1Class candidate, L1Method method) {
        for (L1Method candidateMethod : candidate.methods()) {
            if (!candidateMethod.name().equals(method.name()) || !candidateMethod.descriptor().equals(method.descriptor())) {
                continue;
            }
            int access = candidateMethod.access();
            if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0) {
                return true;
            }
        }
        return false;
    }

    private String c(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    public record MethodSelection(L1Class owner, L1Method method) {}

    public record TranslationResult(
        String source,
        String header,
        int methodCount,
        List<NativeMethodBinding> bindings
    ) {}

    public record NativeMethodBinding(
        String ownerInternalName,
        String methodName,
        String descriptor,
        String cFunctionName,
        String rawFunctionName,
        String helperMethodName,
        String helperDescriptor,
        boolean isStatic,
        boolean directCallSafe
    ) {}

    private record TryHandler(String handlerLabel, String exceptionType) {}

    private record StringConcatPattern(String code, AbstractInsnNode lastInsn) {}
    private record StringProducer(String prefix, String expr) {}
}
