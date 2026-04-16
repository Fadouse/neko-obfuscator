package dev.nekoobfuscator.native_.translator;

import dev.nekoobfuscator.core.ir.l3.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * Translates individual JVM opcodes to C statements.
 * The JVM operand stack is simulated via a C stack array.
 */
public final class OpcodeTranslator {
    private int sp = 0; // simulated stack pointer

    public List<CStatement> translate(AbstractInsnNode insn) {
        List<CStatement> stmts = new ArrayList<>();
        int opcode = insn.getOpcode();

        switch (opcode) {
            // Constants
            case Opcodes.ACONST_NULL -> stmts.add(raw("PUSH_O(NULL);"));
            case Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1,
                 Opcodes.ICONST_2, Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5 ->
                stmts.add(raw("PUSH_I(" + (opcode - Opcodes.ICONST_0) + ");"));
            case Opcodes.LCONST_0 -> stmts.add(raw("PUSH_L(0LL);"));
            case Opcodes.LCONST_1 -> stmts.add(raw("PUSH_L(1LL);"));
            case Opcodes.FCONST_0 -> stmts.add(raw("PUSH_F(0.0f);"));
            case Opcodes.FCONST_1 -> stmts.add(raw("PUSH_F(1.0f);"));
            case Opcodes.FCONST_2 -> stmts.add(raw("PUSH_F(2.0f);"));
            case Opcodes.DCONST_0 -> stmts.add(raw("PUSH_D(0.0);"));
            case Opcodes.DCONST_1 -> stmts.add(raw("PUSH_D(1.0);"));
            case Opcodes.BIPUSH, Opcodes.SIPUSH -> {
                int val = ((IntInsnNode) insn).operand;
                stmts.add(raw("PUSH_I(" + val + ");"));
            }

            // LDC
            case Opcodes.LDC -> {
                LdcInsnNode ldc = (LdcInsnNode) insn;
                if (ldc.cst instanceof Integer i) stmts.add(raw("PUSH_I(" + i + ");"));
                else if (ldc.cst instanceof Long l) stmts.add(raw("PUSH_L(" + l + "LL);"));
                else if (ldc.cst instanceof Float f) stmts.add(raw("PUSH_F(" + f + "f);"));
                else if (ldc.cst instanceof Double d) stmts.add(raw("PUSH_D(" + d + ");"));
                else if (ldc.cst instanceof String s) {
                    stmts.add(raw("PUSH_O((*env)->NewStringUTF(env, " + cStringLiteral(s) + "));"));
                }
            }

            // Loads
            case Opcodes.ILOAD -> stmts.add(raw("PUSH_I(locals[" + ((VarInsnNode) insn).var + "].i);"));
            case Opcodes.LLOAD -> stmts.add(raw("PUSH_L(locals[" + ((VarInsnNode) insn).var + "].l);"));
            case Opcodes.FLOAD -> stmts.add(raw("PUSH_F(locals[" + ((VarInsnNode) insn).var + "].f);"));
            case Opcodes.DLOAD -> stmts.add(raw("PUSH_D(locals[" + ((VarInsnNode) insn).var + "].d);"));
            case Opcodes.ALOAD -> stmts.add(raw("PUSH_O(locals[" + ((VarInsnNode) insn).var + "].o);"));

            // Stores
            case Opcodes.ISTORE -> stmts.add(raw("locals[" + ((VarInsnNode) insn).var + "].i = POP_I();"));
            case Opcodes.LSTORE -> stmts.add(raw("locals[" + ((VarInsnNode) insn).var + "].l = POP_L();"));
            case Opcodes.FSTORE -> stmts.add(raw("locals[" + ((VarInsnNode) insn).var + "].f = POP_F();"));
            case Opcodes.DSTORE -> stmts.add(raw("locals[" + ((VarInsnNode) insn).var + "].d = POP_D();"));
            case Opcodes.ASTORE -> stmts.add(raw("locals[" + ((VarInsnNode) insn).var + "].o = POP_O();"));

            // Int arithmetic
            case Opcodes.IADD -> stmts.add(raw("{ jint b = POP_I(); jint a = POP_I(); PUSH_I(a + b); }"));
            case Opcodes.ISUB -> stmts.add(raw("{ jint b = POP_I(); jint a = POP_I(); PUSH_I(a - b); }"));
            case Opcodes.IMUL -> stmts.add(raw("{ jint b = POP_I(); jint a = POP_I(); PUSH_I(a * b); }"));
            case Opcodes.IDIV -> stmts.add(raw("{ jint b = POP_I(); jint a = POP_I(); PUSH_I(a / b); }"));
            case Opcodes.IREM -> stmts.add(raw("{ jint b = POP_I(); jint a = POP_I(); PUSH_I(a % b); }"));
            case Opcodes.INEG -> stmts.add(raw("PUSH_I(-POP_I());"));
            case Opcodes.ISHL -> stmts.add(raw("{ jint b = POP_I(); jint a = POP_I(); PUSH_I(a << (b & 0x1f)); }"));
            case Opcodes.ISHR -> stmts.add(raw("{ jint b = POP_I(); jint a = POP_I(); PUSH_I(a >> (b & 0x1f)); }"));
            case Opcodes.IUSHR -> stmts.add(raw("{ jint b = POP_I(); jint a = POP_I(); PUSH_I((jint)((uint32_t)a >> (b & 0x1f))); }"));
            case Opcodes.IAND -> stmts.add(raw("{ jint b = POP_I(); jint a = POP_I(); PUSH_I(a & b); }"));
            case Opcodes.IOR -> stmts.add(raw("{ jint b = POP_I(); jint a = POP_I(); PUSH_I(a | b); }"));
            case Opcodes.IXOR -> stmts.add(raw("{ jint b = POP_I(); jint a = POP_I(); PUSH_I(a ^ b); }"));

            // Long arithmetic
            case Opcodes.LADD -> stmts.add(raw("{ jlong b = POP_L(); jlong a = POP_L(); PUSH_L(a + b); }"));
            case Opcodes.LSUB -> stmts.add(raw("{ jlong b = POP_L(); jlong a = POP_L(); PUSH_L(a - b); }"));
            case Opcodes.LMUL -> stmts.add(raw("{ jlong b = POP_L(); jlong a = POP_L(); PUSH_L(a * b); }"));
            case Opcodes.LDIV -> stmts.add(raw("{ jlong b = POP_L(); jlong a = POP_L(); PUSH_L(a / b); }"));
            case Opcodes.LREM -> stmts.add(raw("{ jlong b = POP_L(); jlong a = POP_L(); PUSH_L(a % b); }"));
            case Opcodes.LNEG -> stmts.add(raw("PUSH_L(-POP_L());"));
            case Opcodes.LAND -> stmts.add(raw("{ jlong b = POP_L(); jlong a = POP_L(); PUSH_L(a & b); }"));
            case Opcodes.LOR -> stmts.add(raw("{ jlong b = POP_L(); jlong a = POP_L(); PUSH_L(a | b); }"));
            case Opcodes.LXOR -> stmts.add(raw("{ jlong b = POP_L(); jlong a = POP_L(); PUSH_L(a ^ b); }"));

            // Float arithmetic
            case Opcodes.FADD -> stmts.add(raw("{ jfloat b = POP_F(); jfloat a = POP_F(); PUSH_F(a + b); }"));
            case Opcodes.FSUB -> stmts.add(raw("{ jfloat b = POP_F(); jfloat a = POP_F(); PUSH_F(a - b); }"));
            case Opcodes.FMUL -> stmts.add(raw("{ jfloat b = POP_F(); jfloat a = POP_F(); PUSH_F(a * b); }"));
            case Opcodes.FDIV -> stmts.add(raw("{ jfloat b = POP_F(); jfloat a = POP_F(); PUSH_F(a / b); }"));
            case Opcodes.FREM -> stmts.add(raw("{ jfloat b = POP_F(); jfloat a = POP_F(); PUSH_F(fmodf(a, b)); }"));
            case Opcodes.FNEG -> stmts.add(raw("PUSH_F(-POP_F());"));

            // Double arithmetic
            case Opcodes.DADD -> stmts.add(raw("{ jdouble b = POP_D(); jdouble a = POP_D(); PUSH_D(a + b); }"));
            case Opcodes.DSUB -> stmts.add(raw("{ jdouble b = POP_D(); jdouble a = POP_D(); PUSH_D(a - b); }"));
            case Opcodes.DMUL -> stmts.add(raw("{ jdouble b = POP_D(); jdouble a = POP_D(); PUSH_D(a * b); }"));
            case Opcodes.DDIV -> stmts.add(raw("{ jdouble b = POP_D(); jdouble a = POP_D(); PUSH_D(a / b); }"));
            case Opcodes.DREM -> stmts.add(raw("{ jdouble b = POP_D(); jdouble a = POP_D(); PUSH_D(fmod(a, b)); }"));
            case Opcodes.DNEG -> stmts.add(raw("PUSH_D(-POP_D());"));

            // Type conversions
            case Opcodes.I2L -> stmts.add(raw("PUSH_L((jlong)POP_I());"));
            case Opcodes.I2F -> stmts.add(raw("PUSH_F((jfloat)POP_I());"));
            case Opcodes.I2D -> stmts.add(raw("PUSH_D((jdouble)POP_I());"));
            case Opcodes.L2I -> stmts.add(raw("PUSH_I((jint)POP_L());"));
            case Opcodes.L2F -> stmts.add(raw("PUSH_F((jfloat)POP_L());"));
            case Opcodes.L2D -> stmts.add(raw("PUSH_D((jdouble)POP_L());"));
            case Opcodes.F2I -> stmts.add(raw("PUSH_I((jint)POP_F());"));
            case Opcodes.F2L -> stmts.add(raw("PUSH_L((jlong)POP_F());"));
            case Opcodes.F2D -> stmts.add(raw("PUSH_D((jdouble)POP_F());"));
            case Opcodes.D2I -> stmts.add(raw("PUSH_I((jint)POP_D());"));
            case Opcodes.D2L -> stmts.add(raw("PUSH_L((jlong)POP_D());"));
            case Opcodes.D2F -> stmts.add(raw("PUSH_F((jfloat)POP_D());"));
            case Opcodes.I2B -> stmts.add(raw("PUSH_I((jbyte)POP_I());"));
            case Opcodes.I2C -> stmts.add(raw("PUSH_I((jchar)POP_I());"));
            case Opcodes.I2S -> stmts.add(raw("PUSH_I((jshort)POP_I());"));

            // Comparisons
            case Opcodes.LCMP -> stmts.add(raw("{ jlong b = POP_L(); jlong a = POP_L(); PUSH_I(a > b ? 1 : (a < b ? -1 : 0)); }"));
            case Opcodes.FCMPL -> stmts.add(raw("{ jfloat b = POP_F(); jfloat a = POP_F(); PUSH_I(a > b ? 1 : (a < b ? -1 : (a == b ? 0 : -1))); }"));
            case Opcodes.FCMPG -> stmts.add(raw("{ jfloat b = POP_F(); jfloat a = POP_F(); PUSH_I(a > b ? 1 : (a < b ? -1 : (a == b ? 0 : 1))); }"));
            case Opcodes.DCMPL -> stmts.add(raw("{ jdouble b = POP_D(); jdouble a = POP_D(); PUSH_I(a > b ? 1 : (a < b ? -1 : (a == b ? 0 : -1))); }"));
            case Opcodes.DCMPG -> stmts.add(raw("{ jdouble b = POP_D(); jdouble a = POP_D(); PUSH_I(a > b ? 1 : (a < b ? -1 : (a == b ? 0 : 1))); }"));

            // Stack operations
            case Opcodes.POP -> stmts.add(raw("sp--;"));
            case Opcodes.POP2 -> stmts.add(raw("sp -= 2;"));
            case Opcodes.DUP -> stmts.add(raw("stack[sp] = stack[sp-1]; sp++;"));
            case Opcodes.DUP_X1 -> stmts.add(raw("{ neko_slot t = stack[sp-1]; stack[sp-1] = stack[sp-2]; stack[sp-2] = t; stack[sp] = t; sp++; }"));
            case Opcodes.DUP2 -> stmts.add(raw("stack[sp] = stack[sp-2]; stack[sp+1] = stack[sp-1]; sp += 2;"));
            case Opcodes.SWAP -> stmts.add(raw("{ neko_slot t = stack[sp-1]; stack[sp-1] = stack[sp-2]; stack[sp-2] = t; }"));

            // Returns
            case Opcodes.IRETURN -> stmts.add(raw("return POP_I();"));
            case Opcodes.LRETURN -> stmts.add(raw("return POP_L();"));
            case Opcodes.FRETURN -> stmts.add(raw("return POP_F();"));
            case Opcodes.DRETURN -> stmts.add(raw("return POP_D();"));
            case Opcodes.ARETURN -> stmts.add(raw("return POP_O();"));
            case Opcodes.RETURN -> stmts.add(raw("return;"));

            // Object operations
            case Opcodes.ARRAYLENGTH -> stmts.add(raw("{ jarray arr = (jarray)POP_O(); PUSH_I((*env)->GetArrayLength(env, arr)); }"));
            case Opcodes.ATHROW -> stmts.add(raw("(*env)->Throw(env, (jthrowable)POP_O()); return 0;"));
            case Opcodes.MONITORENTER -> stmts.add(raw("(*env)->MonitorEnter(env, POP_O());"));
            case Opcodes.MONITOREXIT -> stmts.add(raw("(*env)->MonitorExit(env, POP_O());"));
            case Opcodes.NOP -> stmts.add(raw("/* nop */"));
            case Opcodes.IINC -> {
                IincInsnNode iinc = (IincInsnNode) insn;
                stmts.add(raw("locals[" + iinc.var + "].i += " + iinc.incr + ";"));
            }

            // NEW
            case Opcodes.NEW -> {
                TypeInsnNode ti = (TypeInsnNode) insn;
                stmts.add(raw("{ jclass cls = (*env)->FindClass(env, \"" + ti.desc + "\"); PUSH_O((*env)->AllocObject(env, cls)); }"));
            }
            case Opcodes.NEWARRAY -> {
                IntInsnNode in = (IntInsnNode) insn;
                String arrayType = switch (in.operand) {
                    case 4 -> "Boolean"; case 5 -> "Char"; case 6 -> "Float";
                    case 7 -> "Double"; case 8 -> "Byte"; case 9 -> "Short";
                    case 10 -> "Int"; case 11 -> "Long"; default -> "Int";
                };
                stmts.add(raw("{ jint len = POP_I(); PUSH_O((*env)->New" + arrayType + "Array(env, len)); }"));
            }
            case Opcodes.ANEWARRAY -> {
                TypeInsnNode ti = (TypeInsnNode) insn;
                stmts.add(raw("{ jint len = POP_I(); jclass cls = (*env)->FindClass(env, \"" + ti.desc + "\"); PUSH_O((*env)->NewObjectArray(env, len, cls, NULL)); }"));
            }

            // Field access
            case Opcodes.GETFIELD -> {
                FieldInsnNode fi = (FieldInsnNode) insn;
                String getter = jniFieldGetter(fi.desc, false);
                stmts.add(raw("{ jobject obj = POP_O(); jclass cls = (*env)->GetObjectClass(env, obj); jfieldID fid = (*env)->GetFieldID(env, cls, \"" + fi.name + "\", \"" + fi.desc + "\"); " + getter + " }"));
            }
            case Opcodes.PUTFIELD -> {
                FieldInsnNode fi = (FieldInsnNode) insn;
                String setter = jniFieldSetter(fi.desc, false);
                stmts.add(raw("{ " + setter + " jobject obj = POP_O(); jclass cls = (*env)->GetObjectClass(env, obj); jfieldID fid = (*env)->GetFieldID(env, cls, \"" + fi.name + "\", \"" + fi.desc + "\"); " + jniFieldSetCall(fi.desc, false) + " }"));
            }
            case Opcodes.GETSTATIC -> {
                FieldInsnNode fi = (FieldInsnNode) insn;
                stmts.add(raw("{ jclass cls = (*env)->FindClass(env, \"" + fi.owner + "\"); jfieldID fid = (*env)->GetStaticFieldID(env, cls, \"" + fi.name + "\", \"" + fi.desc + "\"); " + jniStaticFieldGet(fi.desc) + " }"));
            }
            case Opcodes.PUTSTATIC -> {
                FieldInsnNode fi = (FieldInsnNode) insn;
                stmts.add(raw("{ " + jniStaticFieldPop(fi.desc) + " jclass cls = (*env)->FindClass(env, \"" + fi.owner + "\"); jfieldID fid = (*env)->GetStaticFieldID(env, cls, \"" + fi.name + "\", \"" + fi.desc + "\"); " + jniStaticFieldSet(fi.desc) + " }"));
            }

            // Method invocations
            case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE -> {
                MethodInsnNode mi = (MethodInsnNode) insn;
                stmts.add(raw(translateMethodInvoke(mi, false)));
            }
            case Opcodes.INVOKESTATIC -> {
                MethodInsnNode mi = (MethodInsnNode) insn;
                stmts.add(raw(translateStaticInvoke(mi)));
            }
            case Opcodes.INVOKESPECIAL -> {
                MethodInsnNode mi = (MethodInsnNode) insn;
                stmts.add(raw(translateMethodInvoke(mi, true)));
            }

            // Type checks
            case Opcodes.INSTANCEOF -> {
                TypeInsnNode ti = (TypeInsnNode) insn;
                stmts.add(raw("{ jobject obj = POP_O(); jclass cls = (*env)->FindClass(env, \"" + ti.desc + "\"); PUSH_I((*env)->IsInstanceOf(env, obj, cls)); }"));
            }
            case Opcodes.CHECKCAST -> {
                TypeInsnNode ti = (TypeInsnNode) insn;
                stmts.add(new CStatement.Comment("checkcast " + ti.desc));
            }

            default -> stmts.add(new CStatement.Comment("TODO: opcode " + opcode));
        }
        return stmts;
    }

    // Translate jumps separately since they need label info
    public CStatement translateJump(JumpInsnNode jump, String targetLabel) {
        int opcode = jump.getOpcode();
        return switch (opcode) {
            case Opcodes.GOTO -> new CStatement.Goto(targetLabel);
            case Opcodes.IFEQ -> raw("if (POP_I() == 0) goto " + targetLabel + ";");
            case Opcodes.IFNE -> raw("if (POP_I() != 0) goto " + targetLabel + ";");
            case Opcodes.IFLT -> raw("if (POP_I() < 0) goto " + targetLabel + ";");
            case Opcodes.IFGE -> raw("if (POP_I() >= 0) goto " + targetLabel + ";");
            case Opcodes.IFGT -> raw("if (POP_I() > 0) goto " + targetLabel + ";");
            case Opcodes.IFLE -> raw("if (POP_I() <= 0) goto " + targetLabel + ";");
            case Opcodes.IF_ICMPEQ -> raw("{ jint b = POP_I(); jint a = POP_I(); if (a == b) goto " + targetLabel + "; }");
            case Opcodes.IF_ICMPNE -> raw("{ jint b = POP_I(); jint a = POP_I(); if (a != b) goto " + targetLabel + "; }");
            case Opcodes.IF_ICMPLT -> raw("{ jint b = POP_I(); jint a = POP_I(); if (a < b) goto " + targetLabel + "; }");
            case Opcodes.IF_ICMPGE -> raw("{ jint b = POP_I(); jint a = POP_I(); if (a >= b) goto " + targetLabel + "; }");
            case Opcodes.IF_ICMPGT -> raw("{ jint b = POP_I(); jint a = POP_I(); if (a > b) goto " + targetLabel + "; }");
            case Opcodes.IF_ICMPLE -> raw("{ jint b = POP_I(); jint a = POP_I(); if (a <= b) goto " + targetLabel + "; }");
            case Opcodes.IF_ACMPEQ -> raw("{ jobject b = POP_O(); jobject a = POP_O(); if (a == b) goto " + targetLabel + "; }");
            case Opcodes.IF_ACMPNE -> raw("{ jobject b = POP_O(); jobject a = POP_O(); if (a != b) goto " + targetLabel + "; }");
            case Opcodes.IFNULL -> raw("if (POP_O() == NULL) goto " + targetLabel + ";");
            case Opcodes.IFNONNULL -> raw("if (POP_O() != NULL) goto " + targetLabel + ";");
            default -> new CStatement.Comment("unknown jump " + opcode);
        };
    }

    private String translateMethodInvoke(MethodInsnNode mi, boolean isSpecial) {
        org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(mi.desc);
        org.objectweb.asm.Type ret = org.objectweb.asm.Type.getReturnType(mi.desc);
        StringBuilder sb = new StringBuilder("{ ");
        // Pop args in reverse
        for (int i = args.length - 1; i >= 0; i--) {
            String pop = popForType(args[i]);
            sb.append(jniTypeName(args[i])).append(" arg").append(i).append(" = ").append(pop).append("; ");
        }
        // Pop receiver
        sb.append("jobject obj = POP_O(); ");
        sb.append("jclass cls = (*env)->GetObjectClass(env, obj); ");
        sb.append("jmethodID mid = (*env)->GetMethodID(env, cls, \"").append(mi.name).append("\", \"").append(mi.desc).append("\"); ");
        String callMethod = jniCallMethod(ret);
        if (ret.getSort() == org.objectweb.asm.Type.VOID) {
            sb.append("(*env)->").append(callMethod).append("(env, obj, mid");
        } else {
            sb.append(jniTypeName(ret)).append(" result = (*env)->").append(callMethod).append("(env, obj, mid");
        }
        for (int i = 0; i < args.length; i++) sb.append(", arg").append(i);
        sb.append("); ");
        if (ret.getSort() != org.objectweb.asm.Type.VOID) {
            sb.append(pushForType(ret, "result")).append(" ");
        }
        sb.append("}");
        return sb.toString();
    }

    private String translateStaticInvoke(MethodInsnNode mi) {
        org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(mi.desc);
        org.objectweb.asm.Type ret = org.objectweb.asm.Type.getReturnType(mi.desc);
        StringBuilder sb = new StringBuilder("{ ");
        for (int i = args.length - 1; i >= 0; i--) {
            sb.append(jniTypeName(args[i])).append(" arg").append(i).append(" = ").append(popForType(args[i])).append("; ");
        }
        sb.append("jclass cls = (*env)->FindClass(env, \"").append(mi.owner).append("\"); ");
        sb.append("jmethodID mid = (*env)->GetStaticMethodID(env, cls, \"").append(mi.name).append("\", \"").append(mi.desc).append("\"); ");
        String callMethod = "CallStatic" + jniCallSuffix(ret) + "Method";
        if (ret.getSort() == org.objectweb.asm.Type.VOID) {
            sb.append("(*env)->").append(callMethod).append("(env, cls, mid");
        } else {
            sb.append(jniTypeName(ret)).append(" result = (*env)->").append(callMethod).append("(env, cls, mid");
        }
        for (int i = 0; i < args.length; i++) sb.append(", arg").append(i);
        sb.append("); ");
        if (ret.getSort() != org.objectweb.asm.Type.VOID) sb.append(pushForType(ret, "result")).append(" ");
        sb.append("}");
        return sb.toString();
    }

    private String jniCallMethod(org.objectweb.asm.Type ret) {
        return "Call" + jniCallSuffix(ret) + "Method";
    }

    private String jniCallSuffix(org.objectweb.asm.Type type) {
        return switch (type.getSort()) {
            case org.objectweb.asm.Type.VOID -> "Void";
            case org.objectweb.asm.Type.INT -> "Int";
            case org.objectweb.asm.Type.LONG -> "Long";
            case org.objectweb.asm.Type.FLOAT -> "Float";
            case org.objectweb.asm.Type.DOUBLE -> "Double";
            case org.objectweb.asm.Type.BOOLEAN -> "Boolean";
            case org.objectweb.asm.Type.BYTE -> "Byte";
            case org.objectweb.asm.Type.CHAR -> "Char";
            case org.objectweb.asm.Type.SHORT -> "Short";
            default -> "Object";
        };
    }

    private String jniTypeName(org.objectweb.asm.Type type) {
        return switch (type.getSort()) {
            case org.objectweb.asm.Type.INT -> "jint";
            case org.objectweb.asm.Type.LONG -> "jlong";
            case org.objectweb.asm.Type.FLOAT -> "jfloat";
            case org.objectweb.asm.Type.DOUBLE -> "jdouble";
            case org.objectweb.asm.Type.BOOLEAN -> "jboolean";
            case org.objectweb.asm.Type.BYTE -> "jbyte";
            case org.objectweb.asm.Type.CHAR -> "jchar";
            case org.objectweb.asm.Type.SHORT -> "jshort";
            default -> "jobject";
        };
    }

    private String popForType(org.objectweb.asm.Type type) {
        return switch (type.getSort()) {
            case org.objectweb.asm.Type.INT, org.objectweb.asm.Type.BOOLEAN,
                 org.objectweb.asm.Type.BYTE, org.objectweb.asm.Type.CHAR, org.objectweb.asm.Type.SHORT -> "POP_I()";
            case org.objectweb.asm.Type.LONG -> "POP_L()";
            case org.objectweb.asm.Type.FLOAT -> "POP_F()";
            case org.objectweb.asm.Type.DOUBLE -> "POP_D()";
            default -> "POP_O()";
        };
    }

    private String pushForType(org.objectweb.asm.Type type, String expr) {
        return switch (type.getSort()) {
            case org.objectweb.asm.Type.INT, org.objectweb.asm.Type.BOOLEAN,
                 org.objectweb.asm.Type.BYTE, org.objectweb.asm.Type.CHAR, org.objectweb.asm.Type.SHORT -> "PUSH_I(" + expr + ");";
            case org.objectweb.asm.Type.LONG -> "PUSH_L(" + expr + ");";
            case org.objectweb.asm.Type.FLOAT -> "PUSH_F(" + expr + ");";
            case org.objectweb.asm.Type.DOUBLE -> "PUSH_D(" + expr + ");";
            default -> "PUSH_O(" + expr + ");";
        };
    }

    private String jniFieldGetter(String desc, boolean isStatic) {
        String suffix = switch (desc.charAt(0)) {
            case 'I' -> "Int"; case 'J' -> "Long"; case 'F' -> "Float"; case 'D' -> "Double";
            case 'Z' -> "Boolean"; case 'B' -> "Byte"; case 'C' -> "Char"; case 'S' -> "Short";
            default -> "Object";
        };
        String push = desc.charAt(0) == 'J' ? "PUSH_L" : desc.charAt(0) == 'F' ? "PUSH_F" : desc.charAt(0) == 'D' ? "PUSH_D" : (desc.charAt(0) == 'L' || desc.charAt(0) == '[') ? "PUSH_O" : "PUSH_I";
        return push + "((*env)->Get" + suffix + "Field(env, obj, fid));";
    }

    private String jniFieldSetter(String desc, boolean isStatic) {
        return switch (desc.charAt(0)) {
            case 'I' -> "jint val = POP_I();";
            case 'J' -> "jlong val = POP_L();";
            case 'F' -> "jfloat val = POP_F();";
            case 'D' -> "jdouble val = POP_D();";
            default -> "jobject val = POP_O();";
        };
    }

    private String jniFieldSetCall(String desc, boolean isStatic) {
        String suffix = switch (desc.charAt(0)) {
            case 'I' -> "Int"; case 'J' -> "Long"; case 'F' -> "Float"; case 'D' -> "Double";
            case 'Z' -> "Boolean"; case 'B' -> "Byte"; case 'C' -> "Char"; case 'S' -> "Short";
            default -> "Object";
        };
        return "(*env)->Set" + suffix + "Field(env, obj, fid, val);";
    }

    private String jniStaticFieldGet(String desc) {
        String suffix = switch (desc.charAt(0)) {
            case 'I' -> "Int"; case 'J' -> "Long"; case 'F' -> "Float"; case 'D' -> "Double";
            case 'Z' -> "Boolean"; case 'B' -> "Byte"; case 'C' -> "Char"; case 'S' -> "Short";
            default -> "Object";
        };
        String push = desc.charAt(0) == 'J' ? "PUSH_L" : desc.charAt(0) == 'F' ? "PUSH_F" : desc.charAt(0) == 'D' ? "PUSH_D" : (desc.charAt(0) == 'L' || desc.charAt(0) == '[') ? "PUSH_O" : "PUSH_I";
        return push + "((*env)->GetStatic" + suffix + "Field(env, cls, fid));";
    }

    private String jniStaticFieldPop(String desc) {
        return switch (desc.charAt(0)) {
            case 'I', 'Z', 'B', 'C', 'S' -> "jint val = POP_I();";
            case 'J' -> "jlong val = POP_L();";
            case 'F' -> "jfloat val = POP_F();";
            case 'D' -> "jdouble val = POP_D();";
            default -> "jobject val = POP_O();";
        };
    }

    private String jniStaticFieldSet(String desc) {
        String suffix = switch (desc.charAt(0)) {
            case 'I' -> "Int"; case 'J' -> "Long"; case 'F' -> "Float"; case 'D' -> "Double";
            case 'Z' -> "Boolean"; case 'B' -> "Byte"; case 'C' -> "Char"; case 'S' -> "Short";
            default -> "Object";
        };
        return "(*env)->SetStatic" + suffix + "Field(env, cls, fid, val);";
    }

    private CStatement raw(String code) { return new CStatement.RawC(code); }

    private String cStringLiteral(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }
}
