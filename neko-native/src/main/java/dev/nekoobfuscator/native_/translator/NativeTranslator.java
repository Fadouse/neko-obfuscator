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

    public NativeTranslator(String outputPrefix, boolean obfuscateJniSlotDispatch, boolean cacheJniIds, long masterSeed) {
        this.codeGenerator = new CCodeGenerator(masterSeed);
    }

    public TranslationResult translate(List<MethodSelection> selectedMethods) {
        List<NativeMethodBinding> bindings = new ArrayList<>();
        Map<String, NativeMethodBinding> bindingMap = new HashMap<>();
        Map<String, L1Class> ownersByName = new HashMap<>();
        Map<String, Integer> overloadCounts = new HashMap<>();
        for (MethodSelection selection : selectedMethods) {
            overloadCounts.merge(selection.owner().name() + '#' + selection.method().name(), 1, Integer::sum);
            ownersByName.putIfAbsent(selection.owner().name(), selection.owner());
        }
        for (int i = 0; i < selectedMethods.size(); i++) {
            MethodSelection selection = selectedMethods.get(i);
            NativeMethodBinding binding = new NativeMethodBinding(
                selection.owner().name(),
                selection.method().name(),
                selection.method().descriptor(),
                i,
                "neko_impl_" + i,
                null,
                null,
                selection.method().isStatic(),
                isDirectCallSafe(selection.owner(), selection.method())
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
        return new TranslationResult(
            source,
            header,
            codeGenerator.generateAdditionalSources(bindings),
            bindings.size(),
            bindings,
            codeGenerator.signatureInfos(bindings)
        );
    }

    private CFunction translateMethod(MethodSelection selection, NativeMethodBinding binding, OpcodeTranslator opcodes) {
        L1Method method = selection.method();
        MethodNode node = method.asmNode();
        Type returnType = Type.getReturnType(method.descriptor());
        CType cReturnType = mapType(returnType);

        List<CVariable> params = new ArrayList<>();
        Type[] argTypes = Type.getArgumentTypes(method.descriptor());
        int paramIndex = 0;
        int argsLocalsSize = method.isStatic() ? 0 : 1;
        if (!method.isStatic()) {
            params.add(new CVariable("_this", CType.JOBJECT, paramIndex++));
        }
        for (int i = 0; i < argTypes.length; i++) {
            params.add(new CVariable("p" + i, mapType(argTypes[i]), paramIndex++));
            argsLocalsSize += argTypes[i].getSize();
        }

        CFunction fn = new CFunction(binding.cFunctionName(), cReturnType, params);
        fn.setTraceIndex(binding.manifestIndex());
        fn.setTraceSignature(method.descriptor());
        fn.setMaxStack(Math.max(method.maxStack(), 16));
        fn.setMaxLocals(Math.max(method.maxLocals(), argsLocalsSize));

        Map<LabelNode, String> labelMap = buildLabelMap(node);
        Map<AbstractInsnNode, Integer> pcMap = buildPcMap(node);
        Map<Integer, List<TryHandler>> activeHandlers = buildActiveHandlers(method, labelMap, pcMap);

        emitParamToLocals(fn, method, argTypes);
        opcodes.beginMethod(selection.owner().name(), selection.method().name(), selection.method().descriptor(), selection.method().isStatic());

        for (AbstractInsnNode insn = node.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode labelNode) {
                fn.addStatement(new CStatement.Label(labelMap.get(labelNode)));
                continue;
            }
            if (insn instanceof LineNumberNode || insn instanceof FrameNode) {
                continue;
            }
            StringConcatPattern concatPattern = renderedStringConcatPattern(insn);
            if (insn instanceof JumpInsnNode jumpInsn) {
                fn.addStatement(opcodes.translateJump(jumpInsn, labelMap.get(jumpInsn.label)));
            } else if (insn instanceof TableSwitchInsnNode tableSwitchInsn) {
                fn.addStatement(new CStatement.RawC(renderTableSwitch(tableSwitchInsn, labelMap)));
            } else if (insn instanceof LookupSwitchInsnNode lookupSwitchInsn) {
                fn.addStatement(new CStatement.RawC(renderLookupSwitch(lookupSwitchInsn, labelMap)));
            } else if (concatPattern != null) {
                fn.addStatement(new CStatement.RawC(concatPattern.code));
                insn = concatPattern.lastInsn;
            } else {
                for (CStatement statement : opcodes.translate(insn)) {
                    fn.addStatement(statement);
                }
            }

            if (isRealInsn(insn) && isPotentiallyExcepting(insn)) {
                List<TryHandler> handlers = activeHandlers.getOrDefault(pcMap.get(insn), List.of());
                fn.addStatement(new CStatement.RawC(renderExceptionDispatch(handlers)));
            }
        }

        fn.addStatement(new CStatement.Label("__neko_exception_exit"));
        emitDefaultReturn(fn, cReturnType);
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
            fn.addStatement(new CStatement.RawC("locals[0].o = _this;"));
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

    private StringConcatPattern renderedStringConcatPattern(AbstractInsnNode start) {
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
        String firstExpr = stringProducerExpression(first);
        if (firstExpr == null) {
            return null;
        }
        String code;
        if (second instanceof LdcInsnNode ldcInsn && ldcInsn.cst instanceof String s) {
            String literalVar = "__neko_concat_lit_" + Integer.toUnsignedString(s.hashCode(), 16);
            code = "{ static jstring " + literalVar + " = NULL; if (" + literalVar + " == NULL) { " + literalVar
                + " = (jstring)neko_new_global_ref(env, neko_new_string_utf(env, \"" + c(s) + "\")); } PUSH_O(neko_string_concat_string(env, "
                + firstExpr + ", " + literalVar + ")); }";
        } else {
            String secondExpr = stringProducerExpression(second);
            if (secondExpr == null) {
                return null;
            }
            code = "{ PUSH_O(neko_string_concat2(env, " + firstExpr + ", " + secondExpr + ")); }";
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

    private String stringProducerExpression(AbstractInsnNode insn) {
        if (insn instanceof VarInsnNode varInsn && varInsn.getOpcode() == Opcodes.ALOAD) {
            return "locals[" + varInsn.var + "].o";
        }
        if (insn instanceof LdcInsnNode ldcInsn && ldcInsn.cst instanceof String s) {
            return "neko_new_string_utf(env, \"" + c(s) + "\")";
        }
        return null;
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

    private String renderExceptionDispatch(List<TryHandler> handlers) {
        StringBuilder sb = new StringBuilder();
        sb.append("if (neko_pending_exception(thread) != NULL) { ");
        if (handlers.isEmpty()) {
            sb.append("goto __neko_exception_exit; }");
            return sb.toString();
        }
        sb.append("void *__exc = neko_pending_exception(thread); neko_clear_pending_exception(thread); ");
        for (TryHandler handler : handlers) {
            if (handler.exceptionType == null) {
                sb.append("sp = 0; PUSH_O(__exc); goto ").append(handler.handlerLabel).append("; ");
            } else {
                sb.append("{ jclass __hcls = neko_find_class(env, \"").append(c(handler.exceptionType)).append("\"); ");
                sb.append("if (__hcls != NULL && neko_is_instance_of(env, __exc, __hcls)) { sp = 0; PUSH_O(__exc); goto ").append(handler.handlerLabel).append("; } }");
            }
        }
        sb.append("neko_set_pending_exception(thread, __exc); goto __neko_exception_exit; }");
        return sb.toString();
    }

    private boolean isPotentiallyExcepting(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
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
                 Opcodes.IALOAD,
                 Opcodes.LALOAD,
                 Opcodes.FALOAD,
                 Opcodes.DALOAD,
                 Opcodes.AALOAD,
                 Opcodes.BALOAD,
                 Opcodes.CALOAD,
                 Opcodes.SALOAD,
                 Opcodes.IASTORE,
                 Opcodes.LASTORE,
                 Opcodes.FASTORE,
                 Opcodes.DASTORE,
                 Opcodes.AASTORE,
                 Opcodes.BASTORE,
                 Opcodes.CASTORE,
                 Opcodes.SASTORE,
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

    private void emitDefaultReturn(CFunction function, CType returnType) {
        switch (returnType) {
            case VOID -> function.addStatement(new CStatement.ReturnVoid());
            case JLONG -> function.addStatement(new CStatement.RawC("return 0;"));
            case JFLOAT -> function.addStatement(new CStatement.RawC("return 0.0f;"));
            case JDOUBLE -> function.addStatement(new CStatement.RawC("return 0.0;"));
            case JOBJECT, JCLASS, JSTRING, JARRAY -> function.addStatement(new CStatement.RawC("return NULL;"));
            default -> function.addStatement(new CStatement.RawC("return 0;"));
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

    private boolean isDirectCallSafe(L1Class owner, L1Method method) {
        if (method.isStatic()) {
            return true;
        }
        int access = method.access();
        return (access & (Opcodes.ACC_FINAL | Opcodes.ACC_PRIVATE)) != 0
            || (owner.access() & Opcodes.ACC_FINAL) != 0;
    }

    private String c(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    public record MethodSelection(L1Class owner, L1Method method) {}

    public record TranslationResult(
        String source,
        String header,
        List<CCodeGenerator.GeneratedSource> additionalSources,
        int methodCount,
        List<NativeMethodBinding> bindings,
        List<CCodeGenerator.SignatureInfo> signatures
    ) {}

    public record NativeMethodBinding(
        String ownerInternalName,
        String methodName,
        String descriptor,
        int manifestIndex,
        String cFunctionName,
        String helperMethodName,
        String helperDescriptor,
        boolean isStatic,
        boolean directCallSafe
    ) {
        public NativeMethodBinding(
            String ownerInternalName,
            String methodName,
            String descriptor,
            String cFunctionName,
            String helperMethodName,
            String helperDescriptor,
            boolean isStatic,
            boolean directCallSafe
        ) {
            this(ownerInternalName, methodName, descriptor, -1, cFunctionName, helperMethodName, helperDescriptor, isStatic, directCallSafe);
        }
    }

    private record TryHandler(String handlerLabel, String exceptionType) {}

    private record StringConcatPattern(String code, AbstractInsnNode lastInsn) {}
}
