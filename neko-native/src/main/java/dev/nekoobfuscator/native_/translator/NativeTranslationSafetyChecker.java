package dev.nekoobfuscator.native_.translator;

import dev.nekoobfuscator.core.ir.l1.L1Method;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

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
        if (returnType.getSort() == Type.OBJECT || returnType.getSort() == Type.ARRAY) {
            reasons.add("reference return types deferred until oop return adapters land");
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
                        // Wave 4b-1 cached_klass infrastructure (ManifestEmitter NekoManifest_FieldSite.cached_klass + BootstrapEmitter holder derivation)
                        // is in place and resolves the SIGSEGV that occurred when class_klass_offset was unavailable on JDK 21.
                        // However, mirror+offset arithmetic for static fields on JDK 9+ still returns wrong values (Calc fixture detects mismatch).
                        // Re-close until a future wave investigates the actual JDK 21 static field base semantics
                        // (likely involves _static_fields_addr or different offset scheme than Unsafe.staticFieldOffset returns).
                        addReason(reasons, isPrimitiveDescriptor(fieldInsn.desc)
                            ? "GETSTATIC deferred pending static field base correctness on JDK 21 (cached_klass infrastructure landed Wave 4b-1)"
                            : "reference GETSTATIC deferred pending oop static field support");
                    }
                }
                case Opcodes.PUTFIELD,
                     Opcodes.PUTSTATIC -> {
                    if (opcode == Opcodes.PUTSTATIC) {
                        if (insn instanceof FieldInsnNode fieldInsn) {
                            addReason(reasons, isPrimitiveDescriptor(fieldInsn.desc)
                                ? "PUTSTATIC deferred pending static field base correctness on JDK 21 (cached_klass infrastructure landed Wave 4b-1)"
                                : "reference PUTSTATIC deferred to M5h (GC write barriers)");
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
                : null;
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
