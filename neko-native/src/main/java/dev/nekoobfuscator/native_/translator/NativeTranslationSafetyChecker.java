package dev.nekoobfuscator.native_.translator;

import dev.nekoobfuscator.core.ir.l1.L1Method;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Set;
import java.util.List;

/**
 * Rejects methods whose bytecode patterns are not yet safely supported by the native translator.
 */
public final class NativeTranslationSafetyChecker {
    public boolean isSafe(L1Method method, List<String> reasons) {
        int access = method.access();
        Type returnType = Type.getReturnType(method.asmNode().desc);

        if (method.isClassInit()) {
            reasons.add("class initializers are not translated");
        }
        if (method.isConstructor()) {
            reasons.add("constructors remain in bytecode form");
        }
        if ((access & Opcodes.ACC_NATIVE) != 0) {
            reasons.add("already-native methods are not translated");
        }
        if ((access & Opcodes.ACC_ABSTRACT) != 0) {
            reasons.add("abstract methods have no translatable bytecode body");
        }
        if ((access & Opcodes.ACC_SYNCHRONIZED) != 0) {
            reasons.add("synchronized methods are not translated");
        }
        if ((access & Opcodes.ACC_BRIDGE) != 0) {
            reasons.add("bridge methods are skipped");
        }
        if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0 && !method.hasCode()) {
            reasons.add("method has no translatable bytecode body");
        }
        if (isReferenceType(returnType)) {
            String referenceReturnReason = unsupportedReferenceReturnReason(method);
            if (referenceReturnReason != null) {
                addReason(reasons, referenceReturnReason);
            }
        }

        for (AbstractInsnNode insn = method.instructions().getFirst(); insn != null; insn = insn.getNext()) {
            int opcode = insn.getOpcode();
            switch (opcode) {
                case Opcodes.JSR, Opcodes.RET -> addReason(reasons, "JSR/RET bytecode is not supported");
                case Opcodes.INVOKEVIRTUAL,
                     Opcodes.INVOKESPECIAL,
                     Opcodes.INVOKESTATIC,
                     Opcodes.INVOKEINTERFACE -> {
                    if (insn instanceof MethodInsnNode methodInsn) {
                        String invokeReason = unsupportedInvokeReason(methodInsn, opcode);
                        if (invokeReason != null) {
                            addReason(reasons, invokeReason);
                        }
                    }
                }
                case Opcodes.INVOKEDYNAMIC -> addReason(reasons, "INVOKEDYNAMIC deferred to M5f");
                case Opcodes.NEW,
                     Opcodes.NEWARRAY,
                     Opcodes.ANEWARRAY,
                     Opcodes.MULTIANEWARRAY -> addReason(reasons, opcodeName(opcode) + " deferred beyond Wave 2");
                case Opcodes.GETFIELD -> {
                }
                case Opcodes.GETSTATIC -> {
                    if (insn instanceof FieldInsnNode fieldInsn) {
                        if (!isPrimitiveDescriptor(fieldInsn.desc)) {
                            addReason(reasons, "reference GETSTATIC deferred pending oop static field support");
                        }
                    }
                }
                case Opcodes.PUTFIELD,
                     Opcodes.PUTSTATIC -> {
                    if (opcode == Opcodes.PUTSTATIC) {
                        if (insn instanceof FieldInsnNode fieldInsn) {
                            if (!isPrimitiveDescriptor(fieldInsn.desc)) {
                                addReason(reasons, "reference PUTSTATIC deferred to M5h (GC write barriers)");
                            }
                        }
                        break;
                    }
                    if (insn instanceof FieldInsnNode fieldInsn && !isPrimitiveDescriptor(fieldInsn.desc)) {
                        addReason(reasons, opcode == Opcodes.PUTFIELD
                            ? "reference PUTFIELD deferred to M5h (GC write barriers)"
                            : "reference PUTSTATIC deferred to M5h (GC write barriers)");
                    }
                }
                case Opcodes.AALOAD,
                     Opcodes.AASTORE,
                     Opcodes.BALOAD,
                     Opcodes.BASTORE,
                     Opcodes.CALOAD,
                     Opcodes.CASTORE,
                     Opcodes.SALOAD,
                     Opcodes.SASTORE,
                     Opcodes.IALOAD,
                     Opcodes.IASTORE,
                     Opcodes.LALOAD,
                     Opcodes.LASTORE,
                     Opcodes.FALOAD,
                     Opcodes.FASTORE,
                     Opcodes.DALOAD,
                     Opcodes.DASTORE,
                     Opcodes.ARRAYLENGTH -> addReason(reasons, opcodeName(opcode) + " deferred beyond Wave 2");
                case Opcodes.ATHROW,
                     Opcodes.MONITORENTER,
                     Opcodes.MONITOREXIT,
                     Opcodes.CHECKCAST,
                     Opcodes.INSTANCEOF -> addReason(reasons, opcodeName(opcode) + " deferred beyond Wave 2");
                case Opcodes.IDIV,
                     Opcodes.IREM,
                     Opcodes.LDIV,
                     Opcodes.LREM -> addReason(reasons, opcodeName(opcode) + " is not yet supported for leaf-native translation");
                case Opcodes.LDC -> {
                    if (insn instanceof LdcInsnNode ldcInsn) {
                        String reason = unsupportedLdcReason(ldcInsn);
                        if (reason != null) {
                            addReason(reasons, reason);
                        }
                    }
                }
                default -> {
                }
            }
        }

        return reasons.isEmpty();
    }

    public boolean hasOnlyManifestInvokeTargets(String ownerInternalName, L1Method method, Set<String> manifestMethodKeys, List<String> reasons) {
        for (AbstractInsnNode insn = method.instructions().getFirst(); insn != null; insn = insn.getNext()) {
            int opcode = insn.getOpcode();
            if (opcode != Opcodes.INVOKEVIRTUAL
                && opcode != Opcodes.INVOKESPECIAL
                && opcode != Opcodes.INVOKESTATIC
                && opcode != Opcodes.INVOKEINTERFACE) {
                continue;
            }
            if (!(insn instanceof MethodInsnNode methodInsn)) {
                continue;
            }
            if (unsupportedInvokeReason(methodInsn, opcode) != null) {
                continue;
            }
            String targetKey = invokeTargetKey(methodInsn);
            if (!manifestMethodKeys.contains(targetKey)) {
                addReason(reasons, "INVOKE target not in neko manifest (translated→translated only)");
            }
        }
        return reasons.isEmpty();
    }

    private void addReason(List<String> reasons, String reason) {
        if (!reasons.contains(reason)) {
            reasons.add(reason);
        }
    }

    private String unsupportedReferenceReturnReason(L1Method method) {
        AbstractInsnNode firstRealInsn = nextRealInsn(method.instructions().getFirst());
        if (firstRealInsn == null) {
            return "reference return requires bytecode body";
        }

        boolean sawReferenceReturn = false;
        for (AbstractInsnNode insn = firstRealInsn; insn != null; insn = nextRealInsn(insn.getNext())) {
            if (insn.getOpcode() != Opcodes.ARETURN) {
                continue;
            }

            sawReferenceReturn = true;
            AbstractInsnNode producer = previousRealInsn(insn.getPrevious());
            if (producer == null) {
                return "reference return flow could not be proven";
            }
            if (!isSafeReferenceReturnProducer(producer)) {
                return "reference return requires direct ALOAD/ACONST_NULL producer";
            }
            if (producer instanceof VarInsnNode varInsn) {
                String localReason = validateReferenceReturnLocal(method, firstRealInsn, insn, varInsn.var);
                if (localReason != null) {
                    return localReason;
                }
            }
        }

        return sawReferenceReturn ? null : "reference return requires explicit ARETURN";
    }

    private boolean isSafeReferenceReturnProducer(AbstractInsnNode insn) {
        if (insn == null) {
            return false;
        }
        if (insn instanceof LdcInsnNode ldcInsn) {
            Object constant = ldcInsn.cst;
            return constant instanceof Type type && (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY);
        }
        if (insn instanceof VarInsnNode varInsn) {
            return varInsn.getOpcode() == Opcodes.ALOAD;
        }
        return insn instanceof InsnNode && insn.getOpcode() == Opcodes.ACONST_NULL;
    }

    private AbstractInsnNode nextRealInsn(AbstractInsnNode insn) {
        AbstractInsnNode cursor = insn;
        while (cursor instanceof LineNumberNode || cursor instanceof FrameNode) {
            cursor = cursor.getNext();
        }
        return cursor;
    }

    private AbstractInsnNode previousRealInsn(AbstractInsnNode insn) {
        AbstractInsnNode cursor = insn;
        while (cursor instanceof LineNumberNode || cursor instanceof FrameNode) {
            cursor = cursor.getPrevious();
        }
        return cursor;
    }

    private String validateReferenceReturnLocal(L1Method method, AbstractInsnNode firstRealInsn, AbstractInsnNode areturnInsn, int localIndex) {
        AbstractInsnNode lastWrite = previousReferenceLocalWrite(areturnInsn, localIndex);
        AbstractInsnNode gcScanStart = firstRealInsn;
        if (lastWrite != null) {
            AbstractInsnNode writeProducer = previousRealInsn(lastWrite.getPrevious());
            if (!isSafeReferenceReturnProducer(writeProducer)) {
                return "reference return requires direct ALOAD/ACONST_NULL producer";
            }
            gcScanStart = nextRealInsn(lastWrite.getNext());
        } else if (!isReferenceParameterLocal(method, localIndex)) {
            return "reference return requires parameter/local source";
        }

        if (hasGcPermittingOpcodeBetween(gcScanStart, areturnInsn)) {
            return "reference return requires no GC-permitting op between last write and ARETURN";
        }
        return null;
    }

    private AbstractInsnNode previousReferenceLocalWrite(AbstractInsnNode areturnInsn, int localIndex) {
        for (AbstractInsnNode insn = previousRealInsn(areturnInsn.getPrevious()); insn != null; insn = previousRealInsn(insn.getPrevious())) {
            if (insn instanceof VarInsnNode varInsn && varInsn.getOpcode() == Opcodes.ASTORE && varInsn.var == localIndex) {
                return insn;
            }
        }
        return null;
    }

    private boolean isReferenceParameterLocal(L1Method method, int localIndex) {
        int slot = 0;
        if (!method.isStatic()) {
            if (localIndex == 0) {
                return true;
            }
            slot = 1;
        }
        for (Type argumentType : method.argumentTypes()) {
            if (slot == localIndex && isReferenceType(argumentType)) {
                return true;
            }
            slot += argumentType.getSize();
        }
        return false;
    }

    private boolean hasGcPermittingOpcodeBetween(AbstractInsnNode startInsn, AbstractInsnNode endInsnExclusive) {
        for (AbstractInsnNode insn = startInsn; insn != null && insn != endInsnExclusive; insn = nextRealInsn(insn.getNext())) {
            if (isGcPermittingOpcode(insn)) {
                return true;
            }
        }
        return false;
    }

    private boolean isGcPermittingOpcode(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode == -1) {
            return false;
        }
        return switch (opcode) {
            case Opcodes.INVOKEVIRTUAL,
                 Opcodes.INVOKESPECIAL,
                 Opcodes.INVOKESTATIC,
                 Opcodes.INVOKEINTERFACE,
                 Opcodes.INVOKEDYNAMIC,
                 Opcodes.NEW,
                 Opcodes.NEWARRAY,
                 Opcodes.ANEWARRAY,
                 Opcodes.MULTIANEWARRAY,
                 Opcodes.GETFIELD,
                 Opcodes.PUTFIELD,
                 Opcodes.GETSTATIC,
                 Opcodes.PUTSTATIC,
                 Opcodes.AALOAD,
                 Opcodes.AASTORE,
                 Opcodes.ARRAYLENGTH,
                 Opcodes.ATHROW,
                 Opcodes.MONITORENTER,
                 Opcodes.MONITOREXIT,
                 Opcodes.CHECKCAST,
                 Opcodes.INSTANCEOF,
                 Opcodes.LDC -> true;
            default -> false;
        };
    }

    private String unsupportedLdcReason(LdcInsnNode ldcInsn) {
        Object constant = ldcInsn.cst;
        if (constant instanceof Integer
            || constant instanceof Float
            || constant instanceof Long
            || constant instanceof Double
            || constant instanceof String) {
            return null;
        }
        if (constant instanceof Type type) {
            return type.getSort() == Type.METHOD
                ? "LDC MethodType deferred to M4a (Wave 3)"
                : ((type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) ? null : "unsupported LDC Type sort: " + type.getSort());
        }
        if (constant instanceof Handle) {
            return "LDC MethodHandle deferred to M4a (Wave 3)";
        }
        return "unsupported LDC constant kind: " + constant.getClass().getSimpleName();
    }

    private String unsupportedInvokeReason(MethodInsnNode methodInsn, int opcode) {
        Type returnType = Type.getReturnType(methodInsn.desc);
        if (returnType.getSort() == Type.OBJECT || returnType.getSort() == Type.ARRAY) {
            return "INVOKE with reference return deferred to Wave 4 (oop return adapter)";
        }
        for (Type argumentType : Type.getArgumentTypes(methodInsn.desc)) {
            if (argumentType.getSort() == Type.OBJECT || argumentType.getSort() == Type.ARRAY) {
                return "INVOKE with reference arguments deferred pending JNI-free receiver spill hardening";
            }
        }
        if (opcode == Opcodes.INVOKEVIRTUAL) {
            return "INVOKEVIRTUAL deferred pending Wave 3 vtable dispatch hardening";
        }
        if (opcode == Opcodes.INVOKEINTERFACE) {
            return "INVOKEINTERFACE deferred pending Wave 3 itable dispatch hardening";
        }
        return null;
    }

    private String invokeTargetKey(MethodInsnNode methodInsn) {
        return methodInsn.owner + '#' + methodInsn.name + methodInsn.desc;
    }

    private boolean isPrimitiveDescriptor(String desc) {
        return desc != null && desc.length() == 1 && "ZBCSIJFD".indexOf(desc.charAt(0)) >= 0;
    }

    private boolean isReferenceType(Type type) {
        return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY;
    }

    private String opcodeName(int opcode) {
        return switch (opcode) {
            case Opcodes.INVOKEVIRTUAL -> "INVOKEVIRTUAL";
            case Opcodes.INVOKESPECIAL -> "INVOKESPECIAL";
            case Opcodes.INVOKESTATIC -> "INVOKESTATIC";
            case Opcodes.INVOKEINTERFACE -> "INVOKEINTERFACE";
            case Opcodes.INVOKEDYNAMIC -> "INVOKEDYNAMIC";
            case Opcodes.NEW -> "NEW";
            case Opcodes.NEWARRAY -> "NEWARRAY";
            case Opcodes.ANEWARRAY -> "ANEWARRAY";
            case Opcodes.MULTIANEWARRAY -> "MULTIANEWARRAY";
            case Opcodes.GETFIELD -> "GETFIELD";
            case Opcodes.PUTFIELD -> "PUTFIELD";
            case Opcodes.GETSTATIC -> "GETSTATIC";
            case Opcodes.PUTSTATIC -> "PUTSTATIC";
            case Opcodes.AALOAD -> "AALOAD";
            case Opcodes.AASTORE -> "AASTORE";
            case Opcodes.BALOAD -> "BALOAD";
            case Opcodes.BASTORE -> "BASTORE";
            case Opcodes.CALOAD -> "CALOAD";
            case Opcodes.CASTORE -> "CASTORE";
            case Opcodes.SALOAD -> "SALOAD";
            case Opcodes.SASTORE -> "SASTORE";
            case Opcodes.IALOAD -> "IALOAD";
            case Opcodes.IASTORE -> "IASTORE";
            case Opcodes.LALOAD -> "LALOAD";
            case Opcodes.LASTORE -> "LASTORE";
            case Opcodes.FALOAD -> "FALOAD";
            case Opcodes.FASTORE -> "FASTORE";
            case Opcodes.DALOAD -> "DALOAD";
            case Opcodes.DASTORE -> "DASTORE";
            case Opcodes.ARRAYLENGTH -> "ARRAYLENGTH";
            case Opcodes.ATHROW -> "ATHROW";
            case Opcodes.MONITORENTER -> "MONITORENTER";
            case Opcodes.MONITOREXIT -> "MONITOREXIT";
            case Opcodes.CHECKCAST -> "CHECKCAST";
            case Opcodes.INSTANCEOF -> "INSTANCEOF";
            case Opcodes.IDIV -> "IDIV";
            case Opcodes.IREM -> "IREM";
            case Opcodes.LDIV -> "LDIV";
            case Opcodes.LREM -> "LREM";
            default -> "opcode " + opcode;
        };
    }
}
