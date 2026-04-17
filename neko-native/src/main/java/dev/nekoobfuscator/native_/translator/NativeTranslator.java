package dev.nekoobfuscator.native_.translator;

import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.ir.l1.L1Method;
import dev.nekoobfuscator.core.ir.l3.CFunction;
import dev.nekoobfuscator.core.ir.l3.CStatement;
import dev.nekoobfuscator.core.ir.l3.CType;
import dev.nekoobfuscator.core.ir.l3.CVariable;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
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
    private static final String INDY_HELPER_OWNER = "dev/nekoobfuscator/runtime/NekoIndyDispatch";
    private static final String INDY_HELPER_NAME = "invoke";
    private static final String INDY_HELPER_DESC = "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;";

    private final CCodeGenerator codeGenerator;

    public NativeTranslator(String outputPrefix, boolean obfuscateJniSlotDispatch, boolean cacheJniIds, long masterSeed) {
        this.codeGenerator = new CCodeGenerator(masterSeed);
    }

    public TranslationResult translate(List<MethodSelection> selectedMethods) {
        List<NativeMethodBinding> bindings = new ArrayList<>();
        Map<String, NativeMethodBinding> bindingMap = new HashMap<>();
        Map<String, Integer> overloadCounts = new HashMap<>();
        for (MethodSelection selection : selectedMethods) {
            overloadCounts.merge(selection.owner().name() + '#' + selection.method().name(), 1, Integer::sum);
        }
        for (int i = 0; i < selectedMethods.size(); i++) {
            MethodSelection selection = selectedMethods.get(i);
            NativeMethodBinding binding = new NativeMethodBinding(
                selection.owner().name(),
                selection.method().name(),
                selection.method().descriptor(),
                jniFunctionName(
                    selection.owner().name(),
                    selection.method().name(),
                    selection.method().descriptor(),
                    overloadCounts.getOrDefault(selection.owner().name() + '#' + selection.method().name(), 0) > 1
                ),
                null,
                null,
                selection.method().isStatic(),
                isDirectCallSafe(selection.owner(), selection.method())
            );
            bindings.add(binding);
            bindingMap.put(bindingKey(binding.ownerInternalName(), binding.methodName(), binding.descriptor()), binding);
        }

        OpcodeTranslator opcodeTranslator = new OpcodeTranslator(codeGenerator, bindingMap);
        List<CFunction> functions = new ArrayList<>(selectedMethods.size());
        for (int i = 0; i < selectedMethods.size(); i++) {
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
        params.add(new CVariable("env", CType.JOBJECT, 0));
        params.add(new CVariable(method.isStatic() ? "clazz" : "self", method.isStatic() ? CType.JCLASS : CType.JOBJECT, 1));

        Type[] argTypes = Type.getArgumentTypes(method.descriptor());
        int paramIndex = 2;
        int argsLocalsSize = method.isStatic() ? 0 : 1;
        for (int i = 0; i < argTypes.length; i++) {
            params.add(new CVariable("p" + i, mapType(argTypes[i]), paramIndex++));
            argsLocalsSize += argTypes[i].getSize();
        }

        CFunction fn = new CFunction(binding.cFunctionName(), cReturnType, params);
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

    private String renderMultiANewArray(MultiANewArrayInsnNode insn) {
        return "{ jint __dims[" + insn.dims + "]; for (int __k = " + (insn.dims - 1) + "; __k >= 0; __k--) __dims[__k] = POP_I(); PUSH_O(neko_multi_new_array(env, " + insn.dims + ", __dims, \"" + c(insn.desc) + "\")); }";
    }

    private String renderInvokeDynamic(MethodSelection selection, InvokeDynamicInsnNode indy, int indyIndex) {
        Type[] argTypes = Type.getArgumentTypes(indy.desc);
        Type ret = Type.getReturnType(indy.desc);
        long siteId = stableSiteId(selection, indyIndex);
        StringBuilder sb = new StringBuilder("{ ");
        for (int i = argTypes.length - 1; i >= 0; i--) {
            sb.append(jniTypeName(argTypes[i])).append(" arg").append(i).append(" = ").append(popForType(argTypes[i])).append("; ");
        }
        sb.append("jclass __indyCls = neko_find_class(env, \"").append(INDY_HELPER_OWNER).append("\"); ");
        sb.append("jmethodID __indyMid = neko_get_static_method_id(env, __indyCls, \"").append(INDY_HELPER_NAME).append("\", \"").append(INDY_HELPER_DESC).append("\"); ");
        sb.append("jclass __objCls = neko_find_class(env, \"java/lang/Object\"); ");
        sb.append("jobjectArray __bootstrapArgs = neko_new_object_array(env, ").append(indy.bsmArgs.length).append(", __objCls, NULL); ");
        for (int i = 0; i < indy.bsmArgs.length; i++) {
            sb.append(renderBootstrapArg(i, indy.bsmArgs[i]));
        }
        sb.append("jobjectArray __invokeArgs = neko_new_object_array(env, ").append(argTypes.length).append(", __objCls, NULL); ");
        for (int i = 0; i < argTypes.length; i++) {
            sb.append("neko_set_object_array_element(env, __invokeArgs, ").append(i).append(", ")
                .append(boxValueExpression(argTypes[i], "arg" + i)).append("); ");
        }
        sb.append("jvalue __callArgs[8]; ");
        sb.append("__callArgs[0].j = (jlong)").append(siteId).append("LL; ");
        sb.append("__callArgs[1].l = neko_new_string_utf(env, \"").append(c(indy.bsm.getOwner())).append("\"); ");
        sb.append("__callArgs[2].l = neko_new_string_utf(env, \"").append(c(indy.bsm.getName())).append("\"); ");
        sb.append("__callArgs[3].l = neko_new_string_utf(env, \"").append(c(indy.bsm.getDesc())).append("\"); ");
        sb.append("__callArgs[4].l = neko_new_string_utf(env, \"").append(c(indy.name)).append("\"); ");
        sb.append("__callArgs[5].l = neko_new_string_utf(env, \"").append(c(indy.desc)).append("\"); ");
        sb.append("__callArgs[6].l = __bootstrapArgs; ");
        sb.append("__callArgs[7].l = __invokeArgs; ");
        sb.append("jobject __indyResult = neko_call_static_object_method_a(env, __indyCls, __indyMid, __callArgs); ");
        sb.append(unboxReturn(ret, "__indyResult"));
        sb.append(" }");
        return sb.toString();
    }

    private String renderBootstrapArg(int index, Object arg) {
        String valueExpr;
        if (arg instanceof Integer i) {
            valueExpr = "neko_box_int(env, " + i + ")";
        } else if (arg instanceof Long l) {
            valueExpr = "neko_box_long(env, " + l + "LL)";
        } else if (arg instanceof Float f) {
            valueExpr = "neko_box_float(env, " + formatFloat(f) + ")";
        } else if (arg instanceof Double d) {
            valueExpr = "neko_box_double(env, " + formatDouble(d) + ")";
        } else if (arg instanceof String s) {
            valueExpr = "neko_new_string_utf(env, \"" + c(s) + "\")";
        } else if (arg instanceof Type type) {
            valueExpr = "neko_new_string_utf(env, \"" + c(encodeType(type)) + "\")";
        } else if (arg instanceof Handle handle) {
            valueExpr = "neko_new_string_utf(env, \"" + c(encodeHandle(handle)) + "\")";
        } else {
            valueExpr = "NULL";
        }
        return "neko_set_object_array_element(env, __bootstrapArgs, " + index + ", " + valueExpr + "); ";
    }

    private String renderExceptionDispatch(List<TryHandler> handlers) {
        StringBuilder sb = new StringBuilder();
        sb.append("if (neko_exception_check(env)) { ");
        if (handlers.isEmpty()) {
            sb.append("jthrowable __exc = neko_exception_occurred(env); neko_exception_clear(env); neko_throw(env, __exc); goto __neko_exception_exit; }");
            return sb.toString();
        }
        sb.append("jthrowable __exc = neko_exception_occurred(env); neko_exception_clear(env); ");
        for (TryHandler handler : handlers) {
            if (handler.exceptionType == null) {
                sb.append("sp = 0; PUSH_O(__exc); goto ").append(handler.handlerLabel).append("; ");
            } else {
                sb.append("{ jclass __hcls = neko_find_class(env, \"").append(c(handler.exceptionType)).append("\"); ");
                sb.append("if (__hcls != NULL && neko_is_instance_of(env, __exc, __hcls)) { sp = 0; PUSH_O(__exc); goto ").append(handler.handlerLabel).append("; } }");
            }
        }
        sb.append("neko_throw(env, __exc); goto __neko_exception_exit; }");
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

    private String jniTypeName(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN -> "jboolean";
            case Type.BYTE -> "jbyte";
            case Type.CHAR -> "jchar";
            case Type.SHORT -> "jshort";
            case Type.INT -> "jint";
            case Type.FLOAT -> "jfloat";
            case Type.LONG -> "jlong";
            case Type.DOUBLE -> "jdouble";
            case Type.ARRAY -> "jarray";
            case Type.VOID -> "void";
            default -> "jobject";
        };
    }

    private String popForType(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> "POP_I()";
            case Type.FLOAT -> "POP_F()";
            case Type.LONG -> "POP_L()";
            case Type.DOUBLE -> "POP_D()";
            default -> "POP_O()";
        };
    }

    private String boxValueExpression(Type type, String valueExpr) {
        return switch (type.getSort()) {
            case Type.BOOLEAN -> "neko_box_boolean(env, " + valueExpr + ")";
            case Type.BYTE -> "neko_box_byte(env, " + valueExpr + ")";
            case Type.CHAR -> "neko_box_char(env, " + valueExpr + ")";
            case Type.SHORT -> "neko_box_short(env, " + valueExpr + ")";
            case Type.INT -> "neko_box_int(env, " + valueExpr + ")";
            case Type.FLOAT -> "neko_box_float(env, " + valueExpr + ")";
            case Type.LONG -> "neko_box_long(env, " + valueExpr + ")";
            case Type.DOUBLE -> "neko_box_double(env, " + valueExpr + ")";
            default -> valueExpr;
        };
    }

    private String unboxReturn(Type ret, String objExpr) {
        return switch (ret.getSort()) {
            case Type.VOID -> "";
            case Type.BOOLEAN -> "PUSH_I(neko_unbox_boolean(env, " + objExpr + ")); ";
            case Type.BYTE -> "PUSH_I((jint)neko_unbox_byte(env, " + objExpr + ")); ";
            case Type.CHAR -> "PUSH_I((jint)neko_unbox_char(env, " + objExpr + ")); ";
            case Type.SHORT -> "PUSH_I((jint)neko_unbox_short(env, " + objExpr + ")); ";
            case Type.INT -> "PUSH_I(neko_unbox_int(env, " + objExpr + ")); ";
            case Type.FLOAT -> "PUSH_F(neko_unbox_float(env, " + objExpr + ")); ";
            case Type.LONG -> "PUSH_L(neko_unbox_long(env, " + objExpr + ")); ";
            case Type.DOUBLE -> "PUSH_D(neko_unbox_double(env, " + objExpr + ")); ";
            default -> "PUSH_O(" + objExpr + "); ";
        };
    }

    private long stableSiteId(MethodSelection selection, int indyIndex) {
        String key = selection.owner().name() + '#' + selection.method().name() + selection.method().descriptor() + '#' + indyIndex;
        long h = 1125899906842597L;
        for (int i = 0; i < key.length(); i++) {
            h = 31L * h + key.charAt(i);
        }
        return h & Long.MAX_VALUE;
    }

    private String encodeType(Type type) {
        if (type.getSort() == Type.METHOD) {
            return "\u0001NEKO_MT:" + type.getDescriptor();
        }
        return "\u0001NEKO_CT:" + type.getDescriptor();
    }

    private String encodeHandle(Handle handle) {
        return "\u0001NEKO_H:" + handle.getTag() + "|" + handle.getOwner() + "|" + handle.getName() + "|" + handle.getDesc() + "|" + (handle.isInterface() ? '1' : '0');
    }

    private String formatFloat(float value) {
        if (Float.isNaN(value)) return "NAN";
        if (Float.isInfinite(value)) return value > 0 ? "INFINITY" : "-INFINITY";
        return Float.toString(value) + 'f';
    }

    private String formatDouble(double value) {
        if (Double.isNaN(value)) return "NAN";
        if (Double.isInfinite(value)) return value > 0 ? "INFINITY" : "-INFINITY";
        return Double.toString(value);
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

    private String jniFunctionName(String ownerInternalName, String methodName, String descriptor, boolean overloaded) {
        StringBuilder out = new StringBuilder("Java_")
            .append(jniMangle(ownerInternalName))
            .append('_')
            .append(jniMangle(methodName));
        if (overloaded) {
            Type[] argTypes = Type.getArgumentTypes(descriptor);
            StringBuilder params = new StringBuilder();
            for (Type argType : argTypes) {
                params.append(argType.getDescriptor());
            }
            out.append("__").append(jniMangle(params.toString()));
        }
        return out.toString();
    }

    private String jniMangle(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '/' -> out.append('_');
                case '_' -> out.append("_1");
                case ';' -> out.append("_2");
                case '[' -> out.append("_3");
                default -> {
                    if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                        out.append(ch);
                    } else {
                        out.append("_0");
                        String hex = Integer.toHexString(ch);
                        for (int pad = hex.length(); pad < 4; pad++) {
                            out.append('0');
                        }
                        out.append(hex);
                    }
                }
            }
        }
        return out.toString();
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
        String helperMethodName,
        String helperDescriptor,
        boolean isStatic,
        boolean directCallSafe
    ) {}

    private record TryHandler(String handlerLabel, String exceptionType) {}

    private record StringConcatPattern(String code, AbstractInsnNode lastInsn) {}
}
