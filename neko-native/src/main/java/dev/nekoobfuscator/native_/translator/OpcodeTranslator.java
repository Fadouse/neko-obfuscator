package dev.nekoobfuscator.native_.translator;

import dev.nekoobfuscator.core.ir.l3.CStatement;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator;
import dev.nekoobfuscator.native_.translator.NativeTranslator.NativeMethodBinding;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OpcodeTranslator {
    @FunctionalInterface
    public interface MethodHandleBridgeFactory {
        MethodHandleBridge ensureBridge(String ownerInternalName, String invokeDescriptor);
    }

    public record MethodHandleBridge(String ownerInternalName, String name, String descriptor) {}

    private final CCodeGenerator codeGenerator;
    private final Map<String, NativeMethodBinding> translatedBindings;
    private final MethodHandleBridgeFactory methodHandleBridgeFactory;
    private final Map<String, String> stringCacheVars = new LinkedHashMap<>();
    private String currentOwnerInternalName = "";
    private String currentMethodKey = "";
    private String currentMethodDescriptor = "";
    private int currentManifestMethodIndex = -1;
    private int indyIndex = 0;
    private int invokeSiteIndex = 0;

    public OpcodeTranslator(CCodeGenerator codeGenerator, Map<String, NativeMethodBinding> translatedBindings) {
        this(codeGenerator, translatedBindings, null);
    }

    public OpcodeTranslator(
        CCodeGenerator codeGenerator,
        Map<String, NativeMethodBinding> translatedBindings,
        MethodHandleBridgeFactory methodHandleBridgeFactory
    ) {
        this.codeGenerator = codeGenerator;
        this.translatedBindings = translatedBindings;
        this.methodHandleBridgeFactory = methodHandleBridgeFactory;
    }

    public void beginMethod(String owner, String name, String desc, boolean isStatic) {
        this.currentOwnerInternalName = owner;
        this.currentMethodKey = owner + '#' + name + desc;
        this.currentMethodDescriptor = desc;
        this.indyIndex = 0;
        this.invokeSiteIndex = 0;
        this.codeGenerator.registerBindingOwner(owner);
        this.currentManifestMethodIndex = this.codeGenerator.registerManifestMethod(this.currentMethodKey);
    }

    public int stringCacheCount() {
        return stringCacheVars.size();
    }

    public List<CStatement> translate(AbstractInsnNode insn) {
        List<CStatement> stmts = new ArrayList<>();
        int opcode = insn.getOpcode();

        switch (opcode) {
            case Opcodes.ACONST_NULL -> stmts.add(raw("PUSH_O(NULL);"));
            case Opcodes.ICONST_M1 -> stmts.add(raw("PUSH_I(-1);"));
            case Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2, Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5 ->
                stmts.add(raw("PUSH_I(" + (opcode - Opcodes.ICONST_0) + ");"));
            case Opcodes.LCONST_0 -> stmts.add(raw("PUSH_L(0LL);"));
            case Opcodes.LCONST_1 -> stmts.add(raw("PUSH_L(1LL);"));
            case Opcodes.FCONST_0 -> stmts.add(raw("PUSH_F(0.0f);"));
            case Opcodes.FCONST_1 -> stmts.add(raw("PUSH_F(1.0f);"));
            case Opcodes.FCONST_2 -> stmts.add(raw("PUSH_F(2.0f);"));
            case Opcodes.DCONST_0 -> stmts.add(raw("PUSH_D(0.0);"));
            case Opcodes.DCONST_1 -> stmts.add(raw("PUSH_D(1.0);"));
            case Opcodes.BIPUSH, Opcodes.SIPUSH -> stmts.add(raw("PUSH_I(" + ((IntInsnNode) insn).operand + ");"));
            case Opcodes.LDC -> translateLdc(stmts, (LdcInsnNode) insn);

            case Opcodes.ILOAD -> stmts.add(raw("PUSH_I(locals[" + ((VarInsnNode) insn).var + "].i);"));
            case Opcodes.LLOAD -> stmts.add(raw("PUSH_L(locals[" + ((VarInsnNode) insn).var + "].j);"));
            case Opcodes.FLOAD -> stmts.add(raw("PUSH_F(locals[" + ((VarInsnNode) insn).var + "].f);"));
            case Opcodes.DLOAD -> stmts.add(raw("PUSH_D(locals[" + ((VarInsnNode) insn).var + "].d);"));
            case Opcodes.ALOAD -> stmts.add(raw("PUSH_O(locals[" + ((VarInsnNode) insn).var + "].o);"));

            case Opcodes.ISTORE -> stmts.add(raw("locals[" + ((VarInsnNode) insn).var + "].i = POP_I();"));
            case Opcodes.LSTORE -> stmts.add(raw("locals[" + ((VarInsnNode) insn).var + "].j = POP_L();"));
            case Opcodes.FSTORE -> stmts.add(raw("locals[" + ((VarInsnNode) insn).var + "].f = POP_F();"));
            case Opcodes.DSTORE -> stmts.add(raw("locals[" + ((VarInsnNode) insn).var + "].d = POP_D();"));
            case Opcodes.ASTORE -> stmts.add(raw("locals[" + ((VarInsnNode) insn).var + "].o = POP_O();"));

            case Opcodes.IALOAD -> stmts.add(raw("{ jint __i = POP_I(); jintArray __a = (jintArray)POP_O(); PUSH_I(neko_fast_iaload(env, __a, __i)); /* fallback: neko_get_int_array_region */ }"));
            case Opcodes.LALOAD -> stmts.add(raw("{ jint __i = POP_I(); jlongArray __a = (jlongArray)POP_O(); PUSH_L(neko_fast_laload(env, __a, __i)); /* fallback: neko_get_long_array_region */ }"));
            case Opcodes.FALOAD -> stmts.add(raw("{ jint __i = POP_I(); jfloatArray __a = (jfloatArray)POP_O(); PUSH_F(neko_fast_faload(env, __a, __i)); /* fallback: neko_get_float_array_region */ }"));
            case Opcodes.DALOAD -> stmts.add(raw("{ jint __i = POP_I(); jdoubleArray __a = (jdoubleArray)POP_O(); PUSH_D(neko_fast_daload(env, __a, __i)); /* fallback: neko_get_double_array_region */ }"));
            case Opcodes.AALOAD -> stmts.add(raw("{ jint __i = POP_I(); jobjectArray __a = (jobjectArray)POP_O(); PUSH_O(neko_get_object_array_element(env, __a, __i)); }"));
            case Opcodes.BALOAD -> stmts.add(raw("{ jint __i = POP_I(); jbyteArray __a = (jbyteArray)POP_O(); PUSH_I((jint)neko_fast_baload(env, __a, __i)); /* fallback: neko_get_byte_array_region */ }"));
            case Opcodes.CALOAD -> stmts.add(raw("{ jint __i = POP_I(); jcharArray __a = (jcharArray)POP_O(); PUSH_I((jint)neko_fast_caload(env, __a, __i)); /* fallback: neko_get_char_array_region */ }"));
            case Opcodes.SALOAD -> stmts.add(raw("{ jint __i = POP_I(); jshortArray __a = (jshortArray)POP_O(); PUSH_I((jint)neko_fast_saload(env, __a, __i)); /* fallback: neko_get_short_array_region */ }"));

            case Opcodes.IASTORE -> stmts.add(raw("{ jint __v = POP_I(); jint __i = POP_I(); jintArray __a = (jintArray)POP_O(); neko_fast_iastore(env, __a, __i, __v); /* fallback: neko_set_int_array_region */ }"));
            case Opcodes.LASTORE -> stmts.add(raw("{ jlong __v = POP_L(); jint __i = POP_I(); jlongArray __a = (jlongArray)POP_O(); neko_fast_lastore(env, __a, __i, __v); /* fallback: neko_set_long_array_region */ }"));
            case Opcodes.FASTORE -> stmts.add(raw("{ jfloat __v = POP_F(); jint __i = POP_I(); jfloatArray __a = (jfloatArray)POP_O(); neko_fast_fastore(env, __a, __i, __v); /* fallback: neko_set_float_array_region */ }"));
            case Opcodes.DASTORE -> stmts.add(raw("{ jdouble __v = POP_D(); jint __i = POP_I(); jdoubleArray __a = (jdoubleArray)POP_O(); neko_fast_dastore(env, __a, __i, __v); /* fallback: neko_set_double_array_region */ }"));
            case Opcodes.AASTORE -> stmts.add(raw("{ jobject __v = POP_O(); jint __i = POP_I(); jobjectArray __a = (jobjectArray)POP_O(); neko_set_object_array_element(env, __a, __i, __v); }"));
            case Opcodes.BASTORE -> stmts.add(raw("{ jint __v = POP_I(); jint __i = POP_I(); jbyteArray __a = (jbyteArray)POP_O(); neko_fast_bastore(env, __a, __i, (jbyte)__v); /* fallback: neko_set_byte_array_region */ }"));
            case Opcodes.CASTORE -> stmts.add(raw("{ jint __v = POP_I(); jint __i = POP_I(); jcharArray __a = (jcharArray)POP_O(); neko_fast_castore(env, __a, __i, (jchar)__v); /* fallback: neko_set_char_array_region */ }"));
            case Opcodes.SASTORE -> stmts.add(raw("{ jint __v = POP_I(); jint __i = POP_I(); jshortArray __a = (jshortArray)POP_O(); neko_fast_sastore(env, __a, __i, (jshort)__v); /* fallback: neko_set_short_array_region */ }"));

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

            case Opcodes.LADD -> stmts.add(raw("{ jlong b = POP_L(); jlong a = POP_L(); PUSH_L(a + b); }"));
            case Opcodes.LSUB -> stmts.add(raw("{ jlong b = POP_L(); jlong a = POP_L(); PUSH_L(a - b); }"));
            case Opcodes.LMUL -> stmts.add(raw("{ jlong b = POP_L(); jlong a = POP_L(); PUSH_L(a * b); }"));
            case Opcodes.LDIV -> stmts.add(raw("{ jlong b = POP_L(); jlong a = POP_L(); PUSH_L(a / b); }"));
            case Opcodes.LREM -> stmts.add(raw("{ jlong b = POP_L(); jlong a = POP_L(); PUSH_L(a % b); }"));
            case Opcodes.LNEG -> stmts.add(raw("PUSH_L(-POP_L());"));
            case Opcodes.LSHL -> stmts.add(raw("{ jint __s = POP_I(); jlong __v = POP_L(); PUSH_L(__v << (__s & 0x3F)); }"));
            case Opcodes.LSHR -> stmts.add(raw("{ jint __s = POP_I(); jlong __v = POP_L(); PUSH_L(__v >> (__s & 0x3F)); }"));
            case Opcodes.LUSHR -> stmts.add(raw("{ jint __s = POP_I(); jlong __v = POP_L(); PUSH_L((jlong)((uint64_t)__v >> (__s & 0x3F))); }"));
            case Opcodes.LAND -> stmts.add(raw("{ jlong b = POP_L(); jlong a = POP_L(); PUSH_L(a & b); }"));
            case Opcodes.LOR -> stmts.add(raw("{ jlong b = POP_L(); jlong a = POP_L(); PUSH_L(a | b); }"));
            case Opcodes.LXOR -> stmts.add(raw("{ jlong b = POP_L(); jlong a = POP_L(); PUSH_L(a ^ b); }"));

            case Opcodes.FADD -> stmts.add(raw("{ jfloat b = POP_F(); jfloat a = POP_F(); PUSH_F(a + b); }"));
            case Opcodes.FSUB -> stmts.add(raw("{ jfloat b = POP_F(); jfloat a = POP_F(); PUSH_F(a - b); }"));
            case Opcodes.FMUL -> stmts.add(raw("{ jfloat b = POP_F(); jfloat a = POP_F(); PUSH_F(a * b); }"));
            case Opcodes.FDIV -> stmts.add(raw("{ jfloat b = POP_F(); jfloat a = POP_F(); PUSH_F(a / b); }"));
            case Opcodes.FREM -> stmts.add(raw("{ jfloat b = POP_F(); jfloat a = POP_F(); PUSH_F(fmodf(a, b)); }"));
            case Opcodes.FNEG -> stmts.add(raw("PUSH_F(-POP_F());"));

            case Opcodes.DADD -> stmts.add(raw("{ jdouble b = POP_D(); jdouble a = POP_D(); PUSH_D(a + b); }"));
            case Opcodes.DSUB -> stmts.add(raw("{ jdouble b = POP_D(); jdouble a = POP_D(); PUSH_D(a - b); }"));
            case Opcodes.DMUL -> stmts.add(raw("{ jdouble b = POP_D(); jdouble a = POP_D(); PUSH_D(a * b); }"));
            case Opcodes.DDIV -> stmts.add(raw("{ jdouble b = POP_D(); jdouble a = POP_D(); PUSH_D(a / b); }"));
            case Opcodes.DREM -> stmts.add(raw("{ jdouble b = POP_D(); jdouble a = POP_D(); PUSH_D(fmod(a, b)); }"));
            case Opcodes.DNEG -> stmts.add(raw("PUSH_D(-POP_D());"));

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

            case Opcodes.LCMP -> stmts.add(raw("{ jlong b = POP_L(); jlong a = POP_L(); PUSH_I(a > b ? 1 : (a < b ? -1 : 0)); }"));
            case Opcodes.FCMPL -> stmts.add(raw("{ jfloat b = POP_F(); jfloat a = POP_F(); PUSH_I(a > b ? 1 : (a < b ? -1 : (a == b ? 0 : -1))); }"));
            case Opcodes.FCMPG -> stmts.add(raw("{ jfloat b = POP_F(); jfloat a = POP_F(); PUSH_I(a > b ? 1 : (a < b ? -1 : (a == b ? 0 : 1))); }"));
            case Opcodes.DCMPL -> stmts.add(raw("{ jdouble b = POP_D(); jdouble a = POP_D(); PUSH_I(a > b ? 1 : (a < b ? -1 : (a == b ? 0 : -1))); }"));
            case Opcodes.DCMPG -> stmts.add(raw("{ jdouble b = POP_D(); jdouble a = POP_D(); PUSH_I(a > b ? 1 : (a < b ? -1 : (a == b ? 0 : 1))); }"));

            case Opcodes.POP -> stmts.add(raw("sp--;"));
            case Opcodes.POP2 -> stmts.add(raw("sp -= 2;"));
            case Opcodes.DUP -> stmts.add(raw("stack[sp] = stack[sp-1]; sp++;"));
            case Opcodes.DUP_X1 -> stmts.add(raw("{ neko_slot t = stack[sp-1]; stack[sp-1] = stack[sp-2]; stack[sp-2] = t; stack[sp] = t; sp++; }"));
            case Opcodes.DUP_X2 -> stmts.add(raw("{ neko_slot v1 = stack[sp-1]; neko_slot v2 = stack[sp-2]; neko_slot v3 = stack[sp-3]; stack[sp-3] = v1; stack[sp-2] = v3; stack[sp-1] = v2; stack[sp] = v1; sp++; }"));
            case Opcodes.DUP2 -> stmts.add(raw("stack[sp] = stack[sp-2]; stack[sp+1] = stack[sp-1]; sp += 2;"));
            case Opcodes.DUP2_X1 -> stmts.add(raw("{ neko_slot v1 = stack[sp-1]; neko_slot v2 = stack[sp-2]; neko_slot v3 = stack[sp-3]; stack[sp-3] = v2; stack[sp-2] = v1; stack[sp-1] = v3; stack[sp] = v2; stack[sp+1] = v1; sp += 2; }"));
            case Opcodes.DUP2_X2 -> stmts.add(raw("{ neko_slot v1 = stack[sp-1]; neko_slot v2 = stack[sp-2]; neko_slot v3 = stack[sp-3]; neko_slot v4 = stack[sp-4]; stack[sp-4] = v2; stack[sp-3] = v1; stack[sp-2] = v4; stack[sp-1] = v3; stack[sp] = v2; stack[sp+1] = v1; sp += 2; }"));
            case Opcodes.SWAP -> stmts.add(raw("{ neko_slot t = stack[sp-1]; stack[sp-1] = stack[sp-2]; stack[sp-2] = t; }"));

            case Opcodes.IRETURN -> stmts.add(raw("return POP_I();"));
            case Opcodes.LRETURN -> stmts.add(raw("return POP_L();"));
            case Opcodes.FRETURN -> stmts.add(raw("return POP_F();"));
            case Opcodes.DRETURN -> stmts.add(raw("return POP_D();"));
            case Opcodes.ARETURN -> stmts.add(raw("return (void*)POP_O();"));
            case Opcodes.RETURN -> stmts.add(raw("return;"));

            case Opcodes.ARRAYLENGTH -> stmts.add(raw("{ jarray arr = (jarray)POP_O(); PUSH_I(neko_get_array_length(env, arr)); }"));
            case Opcodes.ATHROW -> stmts.add(raw("{ neko_throw(env, (jthrowable)POP_O()); }"));
            case Opcodes.MONITORENTER -> stmts.add(raw("neko_monitor_enter(env, POP_O());"));
            case Opcodes.MONITOREXIT -> stmts.add(raw("neko_monitor_exit(env, POP_O());"));
            case Opcodes.NOP -> stmts.add(raw("/* nop */"));
            case Opcodes.IINC -> {
                IincInsnNode iinc = (IincInsnNode) insn;
                stmts.add(raw("locals[" + iinc.var + "].i += " + iinc.incr + ";"));
            }

            case Opcodes.NEW -> {
                TypeInsnNode ti = (TypeInsnNode) insn;
                stmts.add(raw("{ jclass cls = " + cachedClassExpression(ti.desc) + "; if (cls != NULL) { PUSH_O(neko_alloc_object(env, cls)); } }"));
            }
            case Opcodes.NEWARRAY -> stmts.add(raw("{ jint len = POP_I(); PUSH_O(" + newArrayCall(((IntInsnNode) insn).operand) + "); }"));
            case Opcodes.ANEWARRAY -> {
                TypeInsnNode ti = (TypeInsnNode) insn;
                stmts.add(raw("{ jint len = POP_I(); jclass cls = " + cachedClassExpression(ti.desc) + "; if (cls != NULL) { PUSH_O(neko_new_object_array(env, len, cls, NULL)); } }"));
            }

            case Opcodes.GETFIELD -> stmts.add(raw(translateFieldGet((FieldInsnNode) insn, false)));
            case Opcodes.PUTFIELD -> stmts.add(raw(translateFieldPut((FieldInsnNode) insn, false)));
            case Opcodes.GETSTATIC -> stmts.add(raw(translateFieldGet((FieldInsnNode) insn, true)));
            case Opcodes.PUTSTATIC -> stmts.add(raw(translateFieldPut((FieldInsnNode) insn, true)));

            case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE, Opcodes.INVOKESPECIAL -> stmts.add(raw(translateMethodInvoke((MethodInsnNode) insn, opcode)));
            case Opcodes.INVOKESTATIC -> stmts.add(raw(translateStaticInvoke((MethodInsnNode) insn)));

            case Opcodes.INSTANCEOF -> {
                TypeInsnNode ti = (TypeInsnNode) insn;
                stmts.add(raw("{ jobject obj = POP_O(); jclass cls = " + cachedTypeClassExpression(ti.desc) + "; jint result = 0; if (cls != NULL) { result = neko_is_instance_of(env, obj, cls); } PUSH_I(result); }"));
            }
            case Opcodes.CHECKCAST -> {
                TypeInsnNode ti = (TypeInsnNode) insn;
                stmts.add(raw("{ jobject obj = POP_O(); if (obj != NULL) { jclass cls = " + cachedTypeClassExpression(ti.desc) + "; if (cls != NULL && !neko_is_instance_of(env, obj, cls)) { jclass exc = " + cachedClassExpression("java/lang/ClassCastException") + "; if (exc != NULL) { neko_throw_new(env, exc, \"\"); goto __neko_exception_exit; } } } if (!neko_exception_check(env)) { PUSH_O(obj); } }"));
            }
            case Opcodes.MULTIANEWARRAY -> stmts.add(raw(translateMultiANewArray((MultiANewArrayInsnNode) insn)));
            case Opcodes.INVOKEDYNAMIC -> stmts.add(raw(translateInvokeDynamic((InvokeDynamicInsnNode) insn)));

            default -> stmts.add(raw("/* UNSUPPORTED_OPCODE_" + opcode + " */"));
        }
        return stmts;
    }

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
            default -> raw("/* UNSUPPORTED_JUMP_" + opcode + " */");
        };
    }

    private void translateLdc(List<CStatement> stmts, LdcInsnNode ldc) {
        if (ldc.cst instanceof Integer i) {
            stmts.add(raw("PUSH_I(" + i + ");"));
        } else if (ldc.cst instanceof Long l) {
            stmts.add(raw("PUSH_L(" + l + "LL);"));
        } else if (ldc.cst instanceof Float f) {
            stmts.add(raw("PUSH_F(" + floatLiteral(f) + ");"));
        } else if (ldc.cst instanceof Double d) {
            stmts.add(raw("PUSH_D(" + doubleLiteral(d) + ");"));
        } else if (ldc.cst instanceof String s) {
            String siteExpr = codeGenerator.reserveManifestStringLdcSite(currentMethodKey, currentOwnerInternalName, s);
            stmts.add(raw("{ jobject __ldc = neko_ldc_string_site_oop(env, " + siteExpr + "); if (neko_exception_check(env)) goto __neko_exception_exit; PUSH_O(neko_handle_oop(__ldc)); }"));
        } else if (ldc.cst instanceof Type type) {
            if (type.getSort() == Type.METHOD) {
                throw new IllegalStateException("LDC MethodType deferred to M4a (Wave 3)");
            } else if (type.getSort() != Type.OBJECT && type.getSort() != Type.ARRAY) {
                throw new IllegalStateException("unsupported LDC Type sort: " + type.getSort());
            } else {
                String siteExpr = codeGenerator.reserveManifestClassLdcSite(currentMethodKey, currentOwnerInternalName, type.getDescriptor());
                stmts.add(raw("{ void *__ldc = neko_ldc_class_site_oop(thread, " + siteExpr + "); if (neko_pending_exception(thread) != NULL) goto __neko_exception_exit; PUSH_O(__ldc); }"));
            }
        } else if (ldc.cst instanceof Handle) {
            throw new IllegalStateException("LDC MethodHandle deferred to M4a (Wave 3)");
        } else {
            stmts.add(raw("/* unsupported ldc constant */"));
        }
    }

    private String translateMethodInvoke(MethodInsnNode mi, int opcode) {
        return switch (opcode) {
            case Opcodes.INVOKESPECIAL -> translateCompiledInvoke(mi, false, true);
            case Opcodes.INVOKEVIRTUAL -> throw new IllegalStateException("INVOKEVIRTUAL deferred pending Wave 3 vtable dispatch hardening");
            case Opcodes.INVOKEINTERFACE -> throw new IllegalStateException("INVOKEINTERFACE deferred pending Wave 3 itable dispatch hardening");
            default -> throw new IllegalStateException("unsupported invoke opcode: " + opcode);
        };
    }

    private String translateVirtualDispatchWithCache(MethodInsnNode mi) {
        Type[] args = Type.getArgumentTypes(mi.desc);
        Type ret = Type.getReturnType(mi.desc);
        int siteIndex = invokeSiteIndex++;
        String cacheSite = codeGenerator.reserveInvokeCacheSite(currentOwnerInternalName, currentMethodKey, siteIndex);
        List<NativeMethodBinding> directCandidates = directInvokeCacheCandidates(mi);
        NativeMethodBinding directCandidate = directCandidates.isEmpty() ? null : directCandidates.get(0);
        String directStub = directCandidate == null
            ? "NULL"
            : codeGenerator.reserveInvokeCacheDirectStub(currentOwnerInternalName, currentMethodKey, siteIndex, directCandidate, args, ret);
        String directClassSlot = null;
        if (directCandidate != null) {
            codeGenerator.registerOwnerClassReference(currentOwnerInternalName, directCandidate.ownerInternalName());
            directClassSlot = codeGenerator.classSlotName(directCandidate.ownerInternalName());
        }
        String metaSite = codeGenerator.reserveInvokeCacheMeta(
            currentOwnerInternalName,
            currentMethodKey,
            siteIndex,
            mi.name,
            mi.desc,
            mi.getOpcode() == Opcodes.INVOKEINTERFACE,
            directClassSlot,
            directCandidate == null ? null : directStub
        );
        StringBuilder sb = new StringBuilder("{ ");
        sb.append(declarePoppedArgs(args));
        sb.append("jobject __recv = POP_O(); ");
        sb.append("jmethodID mid = ").append(cachedMethodExpression(mi.owner, mi.name, mi.desc, false)).append("; ");
        if (ret.getSort() == Type.VOID) {
            sb.append("neko_icache_dispatch(env, &").append(cacheSite).append(", &")
                .append(metaSite).append(", __recv, mid, __args); ");
        } else {
            sb.append("jvalue __ic_result = neko_icache_dispatch(env, &").append(cacheSite).append(", &")
                .append(metaSite).append(", __recv, mid, __args); ");
            sb.append("if (!neko_exception_check(env)) { ").append(pushForType(ret, "__ic_result" + jvalueAccessor(ret))).append(" } ");
        }
        sb.append("}");
        return sb.toString();
    }

    private List<NativeMethodBinding> directInvokeCacheCandidates(MethodInsnNode mi) {
        List<NativeMethodBinding> candidates = new ArrayList<>();
        NativeMethodBinding binding = translatedBindings.get(bindingKey(mi.owner, mi.name, mi.desc));
        if (binding != null && !binding.isStatic() && binding.directCallSafe()) {
            candidates.add(binding);
        }
        return candidates;
    }

    private String translateIntrinsicMethodInvoke(MethodInsnNode mi, int opcode) {
        if (opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKESPECIAL) {
            if ("java/lang/String".equals(mi.owner) && "length".equals(mi.name) && "()I".equals(mi.desc)) {
                return "{ jstring obj = (jstring)POP_O(); if (obj == NULL) { jclass exc = "
                    + cachedClassExpression("java/lang/NullPointerException")
                    + "; neko_throw_new(env, exc, \"\"); } else { PUSH_I((jint)neko_get_string_length(env, obj)); } }";
            }
            if ("java/lang/Object".equals(mi.owner) && "getClass".equals(mi.name) && "()Ljava/lang/Class;".equals(mi.desc)) {
                return "{ jobject obj = POP_O(); if (obj == NULL) { jclass exc = "
                    + cachedClassExpression("java/lang/NullPointerException")
                    + "; neko_throw_new(env, exc, \"\"); } else { PUSH_O(neko_get_object_class(env, obj)); } }";
            }
        }
        return null;
    }

    private String translateMethodHandleInvoke(MethodInsnNode mi) {
        if (("invoke".equals(mi.name) || "invokeExact".equals(mi.name)) && methodHandleBridgeFactory != null && !currentOwnerInternalName.isEmpty()) {
            MethodHandleBridge bridge = methodHandleBridgeFactory.ensureBridge(currentOwnerInternalName, mi.desc);
            if (bridge != null) {
                return translateMethodHandleBridgeInvoke(mi, bridge);
            }
        }

        return translateMethodHandleFallbackInvoke(mi);
    }

    private String translateMethodHandleBridgeInvoke(MethodInsnNode mi, MethodHandleBridge bridge) {
        Type[] args = Type.getArgumentTypes(mi.desc);
        Type ret = Type.getReturnType(mi.desc);
        StringBuilder sb = new StringBuilder("{ ");
        for (int i = args.length - 1; i >= 0; i--) {
            sb.append(jniTypeName(args[i])).append(" arg").append(i).append(" = ").append(popForType(args[i])).append("; ");
        }
        sb.append("jobject __mh = POP_O(); ");
        sb.append("jclass __bridgeCls = ").append(cachedClassExpression(bridge.ownerInternalName())).append("; ");
        sb.append("jmethodID __bridge = ").append(cachedMethodExpression(bridge.ownerInternalName(), bridge.name(), bridge.descriptor(), true)).append("; ");
        sb.append("jvalue __bridgeArgs[").append(args.length + 1).append("]; ");
        sb.append("__bridgeArgs[0].l = __mh; ");
        for (int i = 0; i < args.length; i++) {
            sb.append("__bridgeArgs[").append(i + 1).append(']').append(jvalueAccessor(args[i])).append(" = arg").append(i).append("; ");
        }
        sb.append(emitGuardedCallResult(ret, "__bridgeCls != NULL && __bridge != NULL", staticCallWrapper(ret), "env, __bridgeCls, __bridge, __bridgeArgs"));
        sb.append("}");
        return sb.toString();
    }

    private String translateMethodHandleFallbackInvoke(MethodInsnNode mi) {
        Type[] args = Type.getArgumentTypes(mi.desc);
        Type ret = Type.getReturnType(mi.desc);
        StringBuilder sb = new StringBuilder("{ ");
        for (int i = args.length - 1; i >= 0; i--) {
            sb.append(jniTypeName(args[i])).append(" arg").append(i).append(" = ").append(popForType(args[i])).append("; ");
        }
        sb.append("jobject __mh = POP_O(); ");
        sb.append("jclass __objCls = ").append(cachedClassExpression("java/lang/Object")).append("; ");
        sb.append("jobjectArray __invokeArgs = neko_new_object_array(env, ").append(args.length).append(", __objCls, NULL); ");
        for (int i = 0; i < args.length; i++) {
            sb.append("neko_set_object_array_element(env, __invokeArgs, ").append(i).append(", ")
                .append(boxValueExpression(args[i], "arg" + i)).append("); ");
        }
        sb.append("jobject __invokeResult = neko_call_mh(env, __mh, __invokeArgs); ");
        sb.append(unboxReturn(ret, "__invokeResult"));
        sb.append("}");
        return sb.toString();
    }

    private String translateStaticInvoke(MethodInsnNode mi) {
        NativeMethodBinding binding = translatedBindings.get(bindingKey(mi.owner, mi.name, mi.desc));
        if (binding != null && binding.isStatic()) {
            return translateDirectInvoke(mi, binding, true, false);
        }
        return translateCompiledInvoke(mi, true, false);
    }

    private String translateDirectInvoke(MethodInsnNode mi, NativeMethodBinding binding, boolean isStatic, boolean isSpecial) {
        Type[] args = Type.getArgumentTypes(mi.desc);
        Type ret = Type.getReturnType(mi.desc);
        StringBuilder sb = new StringBuilder("{ ");
        for (int i = args.length - 1; i >= 0; i--) {
            sb.append(jniTypeName(args[i])).append(" arg").append(i).append(" = ").append(popForType(args[i])).append("; ");
        }
        String receiverExpr = null;
        String guardExpr = null;
        if (isStatic) {
            codeGenerator.registerOwnerClassReference(currentOwnerInternalName, mi.owner);
        } else {
            sb.append("jobject obj = POP_O(); ");
            receiverExpr = "obj";
        }
        sb.append("NEKO_TRACE(2, \"[nk] di c=%d t=%d sig=\\\"%s\\\"\\n\", ")
            .append(currentManifestMethodIndex)
            .append(", ")
            .append(binding.manifestIndex())
            .append(", \"")
            .append(cStringLiteral(mi.desc))
            .append("\"); ");
        if (ret.getSort() == Type.VOID) {
            if (guardExpr != null) {
                sb.append("if (").append(guardExpr).append(") ");
            }
            sb.append(binding.cFunctionName()).append('(');
            appendDirectInvokeCallArgs(sb, args, receiverExpr);
        } else {
            sb.append(jniTypeName(ret)).append(" result = (").append(jniTypeName(ret)).append(")0; ");
            if (guardExpr != null) {
                sb.append("if (").append(guardExpr).append(") ");
            }
            sb.append("result = ").append(binding.cFunctionName()).append('(');
            appendDirectInvokeCallArgs(sb, args, receiverExpr);
        }
        sb.append("); ");
        if (ret.getSort() != Type.VOID) {
            sb.append("if (!neko_exception_check(env)) { ").append(pushForType(ret, "result")).append(" } ");
        }
        if (isSpecial) {
            sb.append("/* translated invokespecial */ ");
        }
        sb.append("}");
        return sb.toString();
    }

    private void appendDirectInvokeCallArgs(StringBuilder sb, Type[] args, String receiverExpr) {
        boolean first = true;
        if (receiverExpr != null) {
            sb.append(receiverExpr);
            first = false;
        }
        for (int i = 0; i < args.length; i++) {
            if (!first) {
                sb.append(", ");
            }
            sb.append("arg").append(i);
            first = false;
        }
        if (first) {
            sb.append("void");
        }
    }

    private String translateCompiledInvoke(MethodInsnNode mi, boolean isStatic, boolean isSpecial) {
        Type[] args = Type.getArgumentTypes(mi.desc);
        Type ret = Type.getReturnType(mi.desc);
        validateCompiledInvoke(mi, args, ret, isStatic, isSpecial);
        String siteExpr = codeGenerator.reserveManifestInvokeSite(
            currentMethodKey,
            currentOwnerInternalName,
            mi.owner,
            mi.name,
            mi.desc,
            isStatic ? Opcodes.INVOKESTATIC : mi.getOpcode()
        );
        StringBuilder sb = new StringBuilder("{ ");
        for (int i = args.length - 1; i >= 0; i--) {
            sb.append(compiledEntryType(args[i])).append(" arg").append(i).append(" = ").append(popForType(args[i])).append("; ");
        }
        sb.append("NekoManifestInvokeSite *__site = ").append(siteExpr).append("; ");
        if (!isStatic) {
            sb.append("void *__recv = POP_O(); ");
            sb.append("if (__recv == NULL) { neko_raise_null_pointer_exception(thread); goto __neko_exception_exit; } ");
        }
        sb.append("void *__method = neko_resolve_invoke_site(__site); ");
        sb.append("if (__method == NULL) goto __neko_exception_exit; ");
        sb.append("void *__entry = neko_method_compiled_entry(__method); ");
        sb.append("if (__entry == NULL) goto __neko_exception_exit; ");
        if (ret.getSort() == Type.VOID) {
            sb.append("((").append(compiledEntryFunctionPointerType(args, ret, isStatic)).append(")__entry)(");
        } else {
            sb.append(compiledEntryType(ret)).append(" __result = ((").append(compiledEntryFunctionPointerType(args, ret, isStatic)).append(")__entry)(");
        }
        appendCompiledInvokeCallArgs(sb, args, isStatic ? null : "__recv");
        sb.append("); ");
        sb.append("if (neko_pending_exception(thread) != NULL) goto __neko_exception_exit; ");
        if (ret.getSort() != Type.VOID) {
            sb.append(pushForType(ret, "__result")).append(' ');
        }
        sb.append("}");
        return sb.toString();
    }

    private void validateCompiledInvoke(MethodInsnNode mi, Type[] args, Type ret, boolean isStatic, boolean isSpecial) {
        if (ret.getSort() == Type.OBJECT || ret.getSort() == Type.ARRAY) {
            throw new IllegalStateException("INVOKE with reference return deferred to Wave 4 (oop return adapter)");
        }
        for (Type arg : args) {
            if (arg.getSort() == Type.OBJECT || arg.getSort() == Type.ARRAY) {
                throw new IllegalStateException("INVOKE with reference arguments deferred pending JNI-free receiver spill hardening");
            }
        }
        if (!isStatic && !isSpecial && mi.getOpcode() != Opcodes.INVOKEVIRTUAL) {
            throw new IllegalStateException("unsupported compiled invoke opcode: " + mi.getOpcode());
        }
    }

    private void appendCompiledInvokeCallArgs(StringBuilder sb, Type[] args, String receiverExpr) {
        boolean first = true;
        if (receiverExpr != null) {
            sb.append(receiverExpr);
            first = false;
        }
        for (int i = 0; i < args.length; i++) {
            if (!first) {
                sb.append(", ");
            }
            sb.append("arg").append(i);
            first = false;
        }
    }

    private String compiledEntryFunctionPointerType(Type[] args, Type ret, boolean isStatic) {
        StringBuilder sb = new StringBuilder();
        sb.append(compiledEntryType(ret)).append(" (*) (");
        boolean first = true;
        if (!isStatic) {
            sb.append("void*");
            first = false;
        }
        for (Type arg : args) {
            if (!first) {
                sb.append(", ");
            }
            sb.append(compiledEntryType(arg));
            first = false;
        }
        if (first) {
            sb.append("void");
        }
        sb.append(')');
        return sb.toString();
    }

    private String compiledEntryType(Type type) {
        return switch (type.getSort()) {
            case Type.VOID -> "void";
            case Type.LONG -> "int64_t";
            case Type.FLOAT -> "float";
            case Type.DOUBLE -> "double";
            case Type.OBJECT, Type.ARRAY -> "void*";
            default -> "int32_t";
        };
    }

    private String translateFieldGet(FieldInsnNode fi, boolean isStatic) {
        String siteExpr = codeGenerator.reserveManifestFieldSite(currentMethodKey, currentOwnerInternalName, fi.owner, fi.name, fi.desc, isStatic);
        StringBuilder sb = new StringBuilder("{ ");
        sb.append("NekoManifestFieldSite *__site = ").append(siteExpr).append("; ");
        if (isPrimitiveFastField(fi.desc)) {
            return translatePrimitiveFieldGet(sb, Type.getType(fi.desc), isStatic);
        }
        if (isStatic) {
            sb.append("void *__base = neko_field_site_static_base(thread, __site); ");
            sb.append("if (neko_pending_exception(thread) != NULL) goto __neko_exception_exit; ");
            sb.append("if (__base == NULL) goto __neko_exception_exit; ");
            sb.append("PUSH_O(neko_field_read_oop(__base, __site)); ");
        } else {
            sb.append("void *__recv = POP_O(); ");
            sb.append("if (__recv == NULL) { neko_raise_null_pointer_exception(thread); goto __neko_exception_exit; } ");
            sb.append("if (!neko_ensure_field_site_resolved(thread, __site)) goto __neko_exception_exit; ");
            sb.append("PUSH_O(neko_field_read_oop(__recv, __site)); ");
        }
        sb.append("}");
        return sb.toString();
    }

    private String translateFieldPut(FieldInsnNode fi, boolean isStatic) {
        Type type = Type.getType(fi.desc);
        if (!isPrimitiveFastField(fi.desc)) {
            throw new IllegalStateException((isStatic ? "reference PUTSTATIC deferred to M5h (GC write barriers)" : "reference PUTFIELD deferred to M5h (GC write barriers)"));
        }
        String siteExpr = codeGenerator.reserveManifestFieldSite(currentMethodKey, currentOwnerInternalName, fi.owner, fi.name, fi.desc, isStatic);
        StringBuilder sb = new StringBuilder("{ ");
        sb.append("NekoManifestFieldSite *__site = ").append(siteExpr).append("; ");
        return translatePrimitiveFieldPut(sb, type, isStatic);
    }

    private String declarePoppedArgs(Type[] args) {
        StringBuilder sb = new StringBuilder();
        if (args.length == 0) {
            sb.append("jvalue * __args = NULL; ");
            return sb.toString();
        }
        for (int i = args.length - 1; i >= 0; i--) {
            sb.append(jniTypeName(args[i])).append(" arg").append(i).append(" = ").append(popForType(args[i])).append("; ");
        }
        sb.append("jvalue __args[").append(args.length).append("]; ");
        for (int i = 0; i < args.length; i++) {
            sb.append("__args[").append(i).append(']').append(jvalueAccessor(args[i])).append(" = arg").append(i).append("; ");
        }
        return sb.toString();
    }

    private String emitGuardedCallResult(Type ret, String guardExpr, String wrapper, String callArgs) {
        if (ret.getSort() == Type.VOID) {
            return "if (" + guardExpr + ") { " + wrapper + "(" + callArgs + "); } ";
        }
        return jniTypeName(ret) + " result = (" + jniTypeName(ret) + ")0; if (" + guardExpr + ") { result = " + wrapper + "(" + callArgs + "); } if (!neko_exception_check(env)) { " + pushForType(ret, "result") + " } ";
    }

    private String fieldGetter(String desc) {
        return switch (desc.charAt(0)) {
            case 'Z' -> "neko_get_boolean_field";
            case 'B' -> "neko_get_byte_field";
            case 'C' -> "neko_get_char_field";
            case 'S' -> "neko_get_short_field";
            case 'I' -> "neko_get_int_field";
            case 'J' -> "neko_get_long_field";
            case 'F' -> "neko_get_float_field";
            case 'D' -> "neko_get_double_field";
            default -> "neko_get_object_field";
        };
    }

    private String fieldSetter(String desc) {
        return switch (desc.charAt(0)) {
            case 'Z' -> "neko_set_boolean_field";
            case 'B' -> "neko_set_byte_field";
            case 'C' -> "neko_set_char_field";
            case 'S' -> "neko_set_short_field";
            case 'I' -> "neko_set_int_field";
            case 'J' -> "neko_set_long_field";
            case 'F' -> "neko_set_float_field";
            case 'D' -> "neko_set_double_field";
            default -> "neko_set_object_field";
        };
    }

    private String staticFieldGetter(String desc) {
        return switch (desc.charAt(0)) {
            case 'Z' -> "neko_get_static_boolean_field";
            case 'B' -> "neko_get_static_byte_field";
            case 'C' -> "neko_get_static_char_field";
            case 'S' -> "neko_get_static_short_field";
            case 'I' -> "neko_get_static_int_field";
            case 'J' -> "neko_get_static_long_field";
            case 'F' -> "neko_get_static_float_field";
            case 'D' -> "neko_get_static_double_field";
            default -> "neko_get_static_object_field";
        };
    }

    private String staticFieldSetter(String desc) {
        return switch (desc.charAt(0)) {
            case 'Z' -> "neko_set_static_boolean_field";
            case 'B' -> "neko_set_static_byte_field";
            case 'C' -> "neko_set_static_char_field";
            case 'S' -> "neko_set_static_short_field";
            case 'I' -> "neko_set_static_int_field";
            case 'J' -> "neko_set_static_long_field";
            case 'F' -> "neko_set_static_float_field";
            case 'D' -> "neko_set_static_double_field";
            default -> "neko_set_static_object_field";
        };
    }

    private boolean isPrimitiveFastField(String desc) {
        return desc.length() == 1 && "ZBCSIJFD".indexOf(desc.charAt(0)) >= 0;
    }

    private String translatePrimitiveFieldGet(StringBuilder sb, Type type, boolean isStatic) {
        char primitive = type.getDescriptor().charAt(0);
        if (isStatic) {
            sb.append("void *__base = neko_field_site_static_base(thread, __site); ");
            sb.append("if (__base == NULL) { if (neko_pending_exception(thread) == NULL) neko_wave2_capture_pending(env, thread, \"java/lang/IllegalStateException\", \"failed to resolve static field base\"); goto __neko_exception_exit; } ");
            if (isStaticIntLike(primitive)) {
                sb.append(staticI32GetExpression(type));
            } else {
                sb.append(pushForType(type, staticGetHelper(primitive) + "(__site)")).append(' ');
            }
        } else {
            sb.append("void *__recv = POP_O(); ");
            sb.append("if (__recv == NULL) { neko_raise_null_pointer_exception(thread); goto __neko_exception_exit; } ");
            sb.append("if (!neko_ensure_field_site_resolved(thread, __site)) goto __neko_exception_exit; ");
            sb.append(pushForType(type, "neko_field_read_" + primitive + "(__recv, __site)")).append(' ');
        }
        sb.append("}");
        return sb.toString();
    }

    private String translatePrimitiveFieldPut(StringBuilder sb, Type type, boolean isStatic) {
        char primitive = type.getDescriptor().charAt(0);
        sb.append(jniTypeName(type)).append(" val = ").append(popForType(type)).append("; ");
        if (isStatic) {
            sb.append("void *__base = neko_field_site_static_base(thread, __site); ");
            sb.append("if (__base == NULL) { if (neko_pending_exception(thread) == NULL) neko_wave2_capture_pending(env, thread, \"java/lang/IllegalStateException\", \"failed to resolve static field base\"); goto __neko_exception_exit; } ");
            if (isStaticIntLike(primitive)) {
                sb.append(staticI32PutExpression(type));
            } else {
                sb.append(staticPutHelper(primitive)).append("(__site, val); ");
            }
        } else {
            sb.append("void *__recv = POP_O(); ");
            sb.append("if (__recv == NULL) { neko_raise_null_pointer_exception(thread); goto __neko_exception_exit; } ");
            sb.append("if (!neko_ensure_field_site_resolved(thread, __site)) goto __neko_exception_exit; ");
            sb.append("neko_field_write_").append(primitive).append("(__recv, __site, val); ");
        }
        sb.append("}");
        return sb.toString();
    }

    private boolean isStaticIntLike(char primitive) {
        return "ZBCSI".indexOf(primitive) >= 0;
    }

    private String staticGetHelper(char primitive) {
        return switch (primitive) {
            case 'J' -> "neko_getstatic_i64";
            case 'F' -> "neko_getstatic_f32";
            case 'D' -> "neko_getstatic_f64";
            default -> "neko_getstatic_i32";
        };
    }

    private String staticPutHelper(char primitive) {
        return switch (primitive) {
            case 'J' -> "neko_putstatic_i64";
            case 'F' -> "neko_putstatic_f32";
            case 'D' -> "neko_putstatic_f64";
            default -> "neko_putstatic_i32";
        };
    }

    private String staticI32GetExpression(Type type) {
        String typedValue = castStaticI32Value(type, "__cur");
        return "int32_t __cur = neko_getstatic_i32(__site); "
            + "{ void *m = neko_static_mirror(__site); int32_t *addr = neko_static_i32_addr(__site); "
            + "NEKO_TRACE(2, \"[nk] sfg c=%d sig=\\\"%s\\\" site=%p slot=%p raw=%p mir=%p off=%ld addr=%p val=%d\\n\", "
            + currentManifestMethodIndex + ", \"" + cStringLiteral(currentMethodDescriptor) + "\", (void*)__site, (void*)__site->static_base_slot, (void*)*(__site->static_base_slot), m, (long)__site->field_offset_cookie, (void*)addr, __cur); } "
            + pushForType(type, typedValue) + ' ';
    }

    private String staticI32PutExpression(Type type) {
        String valueExpr = castStaticI32Value(type, "val");
        return "int32_t __nv = " + valueExpr + "; "
            + "{ void *m = neko_static_mirror(__site); int32_t *addr = neko_static_i32_addr(__site); int32_t oldv = *addr; "
            + "NEKO_TRACE(2, \"[nk] sfp c=%d sig=\\\"%s\\\" site=%p slot=%p raw=%p mir=%p off=%ld addr=%p old=%d new=%d\\n\", "
            + currentManifestMethodIndex + ", \"" + cStringLiteral(currentMethodDescriptor) + "\", (void*)__site, (void*)__site->static_base_slot, (void*)*(__site->static_base_slot), m, (long)__site->field_offset_cookie, (void*)addr, oldv, __nv); "
            + "neko_putstatic_i32(__site, __nv); "
            + "NEKO_TRACE(2, \"[nk] sfd c=%d sig=\\\"%s\\\" addr=%p now=%d\\n\", " + currentManifestMethodIndex + ", \"" + cStringLiteral(currentMethodDescriptor) + "\", (void*)addr, *addr); "
            + "{ uint8_t *p = (uint8_t*)addr - 8; NEKO_TRACE(2, \"[nk] sfbx %02x %02x %02x %02x %02x %02x %02x %02x | %02x %02x %02x %02x %02x %02x %02x %02x\\n\", p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7], p[8], p[9], p[10], p[11], p[12], p[13], p[14], p[15]); } } ";
    }

    private String castStaticI32Value(Type type, String expr) {
        return switch (type.getSort()) {
            case Type.BOOLEAN -> "((int32_t)(" + expr + " ? 1 : 0))";
            case Type.CHAR -> "((int32_t)(uint32_t)" + expr + ")";
            default -> "((int32_t)" + expr + ")";
        };
    }


    private String virtualCallWrapper(Type ret) {
        return switch (ret.getSort()) {
            case Type.VOID -> "neko_call_void_method_a";
            case Type.BOOLEAN -> "neko_call_boolean_method_a";
            case Type.BYTE -> "neko_call_byte_method_a";
            case Type.CHAR -> "neko_call_char_method_a";
            case Type.SHORT -> "neko_call_short_method_a";
            case Type.INT -> "neko_call_int_method_a";
            case Type.LONG -> "neko_call_long_method_a";
            case Type.FLOAT -> "neko_call_float_method_a";
            case Type.DOUBLE -> "neko_call_double_method_a";
            default -> "neko_call_object_method_a";
        };
    }

    private String staticCallWrapper(Type ret) {
        return switch (ret.getSort()) {
            case Type.VOID -> "neko_call_static_void_method_a";
            case Type.BOOLEAN -> "neko_call_static_boolean_method_a";
            case Type.BYTE -> "neko_call_static_byte_method_a";
            case Type.CHAR -> "neko_call_static_char_method_a";
            case Type.SHORT -> "neko_call_static_short_method_a";
            case Type.INT -> "neko_call_static_int_method_a";
            case Type.LONG -> "neko_call_static_long_method_a";
            case Type.FLOAT -> "neko_call_static_float_method_a";
            case Type.DOUBLE -> "neko_call_static_double_method_a";
            default -> "neko_call_static_object_method_a";
        };
    }

    private String nonvirtualCallWrapper(Type ret) {
        return switch (ret.getSort()) {
            case Type.VOID -> "neko_call_nonvirtual_void_method_a";
            case Type.BOOLEAN -> "neko_call_nonvirtual_boolean_method_a";
            case Type.BYTE -> "neko_call_nonvirtual_byte_method_a";
            case Type.CHAR -> "neko_call_nonvirtual_char_method_a";
            case Type.SHORT -> "neko_call_nonvirtual_short_method_a";
            case Type.INT -> "neko_call_nonvirtual_int_method_a";
            case Type.LONG -> "neko_call_nonvirtual_long_method_a";
            case Type.FLOAT -> "neko_call_nonvirtual_float_method_a";
            case Type.DOUBLE -> "neko_call_nonvirtual_double_method_a";
            default -> "neko_call_nonvirtual_object_method_a";
        };
    }

    private String newArrayCall(int operand) {
        return switch (operand) {
            case 4 -> "neko_new_boolean_array(env, len)";
            case 5 -> "neko_new_char_array(env, len)";
            case 6 -> "neko_new_float_array(env, len)";
            case 7 -> "neko_new_double_array(env, len)";
            case 8 -> "neko_new_byte_array(env, len)";
            case 9 -> "neko_new_short_array(env, len)";
            case 10 -> "neko_new_int_array(env, len)";
            case 11 -> "neko_new_long_array(env, len)";
            default -> "neko_new_int_array(env, len)";
        };
    }

    private boolean canDirectInvoke(NativeMethodBinding binding, int opcode) {
        if (binding == null) {
            return false;
        }
        if (!binding.isStatic() && binding.ownerInternalName().equals(currentOwnerInternalName)) {
            return false;
        }
        return switch (opcode) {
            case Opcodes.INVOKESTATIC -> binding.isStatic();
            case Opcodes.INVOKESPECIAL -> !binding.isStatic();
            case Opcodes.INVOKEVIRTUAL -> !binding.isStatic() && binding.directCallSafe();
            case Opcodes.INVOKEINTERFACE -> false;
            default -> false;
        };
    }

    private String cachedTypeClassExpression(String desc) {
        String classLookupName = classLookupName(desc);
        if (classLookupName == null) {
            return "neko_class_for_descriptor(env, \"" + cStringLiteral(typeToDescriptor(desc)) + "\")";
        }
        return cachedClassExpression(classLookupName);
    }

    private String classLookupName(String desc) {
        if (desc.startsWith("[")) {
            return desc;
        }
        if (desc.startsWith("L") && desc.endsWith(";")) {
            return desc.substring(1, desc.length() - 1);
        }
        return desc.length() == 1 ? null : desc;
    }

    private String typeToDescriptor(String desc) {
        return desc.startsWith("[") ? desc : (desc.length() == 1 ? desc : 'L' + desc + ';');
    }

    private String jniTypeName(Type type) {
        return switch (type.getSort()) {
            case Type.INT -> "jint";
            case Type.LONG -> "jlong";
            case Type.FLOAT -> "jfloat";
            case Type.DOUBLE -> "jdouble";
            case Type.BOOLEAN -> "jboolean";
            case Type.BYTE -> "jbyte";
            case Type.CHAR -> "jchar";
            case Type.SHORT -> "jshort";
            case Type.VOID -> "void";
            case Type.ARRAY -> "jarray";
            default -> "jobject";
        };
    }

    private String popForType(Type type) {
        return switch (type.getSort()) {
            case Type.INT, Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT -> "POP_I()";
            case Type.LONG -> "POP_L()";
            case Type.FLOAT -> "POP_F()";
            case Type.DOUBLE -> "POP_D()";
            default -> "POP_O()";
        };
    }

    private String pushForType(Type type, String expr) {
        return switch (type.getSort()) {
            case Type.INT, Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT -> "PUSH_I(" + expr + ");";
            case Type.LONG -> "PUSH_L(" + expr + ");";
            case Type.FLOAT -> "PUSH_F(" + expr + ");";
            case Type.DOUBLE -> "PUSH_D(" + expr + ");";
            default -> "PUSH_O(" + expr + ");";
        };
    }

    private String jvalueAccessor(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN -> ".z";
            case Type.BYTE -> ".b";
            case Type.CHAR -> ".c";
            case Type.SHORT -> ".s";
            case Type.INT -> ".i";
            case Type.FLOAT -> ".f";
            case Type.LONG -> ".j";
            case Type.DOUBLE -> ".d";
            default -> ".l";
        };
    }

    private String translateMultiANewArray(MultiANewArrayInsnNode insn) {
        StringBuilder sb = new StringBuilder("{ jint __dims[").append(insn.dims).append("]; ");
        for (int i = insn.dims - 1; i >= 0; i--) {
            sb.append("__dims[").append(i).append("] = POP_I(); ");
        }
        sb.append("PUSH_O(neko_multi_new_array(env, ").append(insn.dims).append(", __dims, \"")
            .append(cStringLiteral(insn.desc)).append("\")); }");
        return sb.toString();
    }

    private String translateInvokeDynamic(InvokeDynamicInsnNode indy) {
        Type[] argTypes = Type.getArgumentTypes(indy.desc);
        if ("java/lang/invoke/StringConcatFactory".equals(indy.bsm.getOwner())
            && "makeConcatWithConstants".equals(indy.bsm.getName())) {
            return translateStringConcatInvokeDynamic(indy, argTypes);
        }
        return translateGenericInvokeDynamic(indy, argTypes, Type.getReturnType(indy.desc), nextIndySiteId());
    }

    private String translateStringConcatInvokeDynamic(InvokeDynamicInsnNode indy, Type[] argTypes) {
        String recipe = indy.bsmArgs.length > 0 && indy.bsmArgs[0] instanceof String s ? s : "";
        if (intrinsicsEnabled() && canUseSimpleStringConcatFastPath(indy, argTypes, recipe)) {
            return translateSimpleStringConcatRecipe(argTypes, recipe);
        }
        StringBuilder sb = new StringBuilder("{ ");
        for (int i = argTypes.length - 1; i >= 0; i--) {
            sb.append(jniTypeName(argTypes[i])).append(" arg").append(i).append(" = ").append(popForType(argTypes[i])).append("; ");
        }
        sb.append("jclass __sbCls = ").append(cachedClassExpression("java/lang/StringBuilder")).append("; ");
        sb.append("jmethodID __sbCtor = ").append(cachedMethodExpression("java/lang/StringBuilder", "<init>", "()V", false)).append("; ");
        sb.append("jobject __sb = (__sbCls != NULL && __sbCtor != NULL) ? neko_new_object_a(env, __sbCls, __sbCtor, NULL) : NULL; ");

        int dynIndex = 0;
        int constIndex = 1;
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < recipe.length(); i++) {
            char ch = recipe.charAt(i);
            if (ch == '\u0001' || ch == '\u0002') {
                if (literal.length() > 0) {
                    appendStringBuilderLiteral(sb, literal.toString());
                    literal.setLength(0);
                }
                if (ch == '\u0001') {
                    Type argType = argTypes[dynIndex];
                    appendStringBuilderValue(sb, stringBuilderAppendSig(argType), jvalueAccessor(argType), "arg" + dynIndex);
                    dynIndex++;
                } else {
                    Object constant = constIndex < indy.bsmArgs.length ? indy.bsmArgs[constIndex++] : "";
                    appendStringBuilderLiteral(sb, String.valueOf(constant));
                }
            } else {
                literal.append(ch);
            }
        }
        if (literal.length() > 0) {
            appendStringBuilderLiteral(sb, literal.toString());
        }
        sb.append("jmethodID __toString = ").append(cachedMethodExpression("java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false)).append("; ");
        sb.append("if (__sb != NULL && __toString != NULL) { PUSH_O(neko_call_object_method_a(env, __sb, __toString, NULL)); } }");
        return sb.toString();
    }

    private boolean canUseSimpleStringConcatFastPath(InvokeDynamicInsnNode indy, Type[] argTypes, String recipe) {
        if (indy.bsmArgs.length != 1 || !"Ljava/lang/String;".equals(Type.getReturnType(indy.desc).getDescriptor())) {
            return false;
        }
        int placeholders = 0;
        for (int i = 0; i < recipe.length(); i++) {
            char ch = recipe.charAt(i);
            if (ch == '\u0001') {
                placeholders++;
                continue;
            }
            if (ch == '\u0002' || ch == '\0' || ch > 0x7E || (ch < 0x20 && ch != '\n' && ch != '\r' && ch != '\t')) {
                return false;
            }
        }
        if (placeholders != argTypes.length) {
            return false;
        }
        for (Type argType : argTypes) {
            if (!"Ljava/lang/String;".equals(argType.getDescriptor())) {
                return false;
            }
        }
        return true;
    }

    private String translateSimpleStringConcatRecipe(Type[] argTypes, String recipe) {
        StringBuilder sb = new StringBuilder("{ ");
        for (int i = argTypes.length - 1; i >= 0; i--) {
            sb.append(jniTypeName(argTypes[i])).append(" arg").append(i).append(" = ").append(popForType(argTypes[i])).append("; ");
        }
        sb.append("jstring __acc = NULL; ");

        int dynIndex = 0;
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < recipe.length(); i++) {
            char ch = recipe.charAt(i);
            if (ch == '\u0001') {
                if (literal.length() > 0) {
                    appendSimpleConcatLiteral(sb, literal.toString());
                    literal.setLength(0);
                }
                appendSimpleConcatArg(sb, "arg" + dynIndex++);
            } else {
                literal.append(ch);
            }
        }
        if (literal.length() > 0) {
            appendSimpleConcatLiteral(sb, literal.toString());
        }
        sb.append("if (__acc == NULL) { __acc = ").append(cachedStringExpression("")).append("; } ");
        sb.append("PUSH_O(__acc); }");
        return sb.toString();
    }

    private void appendSimpleConcatLiteral(StringBuilder sb, String literal) {
        String literalExpr = cachedStringExpression(literal);
        sb.append("if (__acc == NULL) { __acc = ").append(literalExpr).append("; } else { __acc = neko_string_concat_string(env, __acc, ")
            .append(literalExpr).append("); } ");
    }

    private void appendSimpleConcatArg(StringBuilder sb, String valueExpr) {
        sb.append("if (__acc == NULL) { __acc = (jstring)(").append(valueExpr).append(" == NULL ? neko_string_null(env) : ")
            .append(valueExpr).append("); } else { __acc = neko_string_concat2(env, __acc, ").append(valueExpr).append("); } ");
    }

    private String translateGenericInvokeDynamic(InvokeDynamicInsnNode indy, Type[] argTypes, Type retType, long siteId) {
        Type[] bootstrapArgTypes = Type.getArgumentTypes(indy.bsm.getDesc());
        Object[] bootstrapArgs = adaptBootstrapArgs(indy.bsmArgs, bootstrapArgTypes);
        StringBuilder sb = new StringBuilder("{ ");
        for (int i = argTypes.length - 1; i >= 0; i--) {
            sb.append(jniTypeName(argTypes[i])).append(" arg").append(i).append(" = ").append(popForType(argTypes[i])).append("; ");
        }
        sb.append("jobject __mh = neko_get_indy_mh(").append(siteId).append("LL); ");
        sb.append("jclass __objCls = neko_find_class(env, \"java/lang/Object\"); ");
        sb.append("if (__mh == NULL) { ");
        sb.append("jobjectArray __bootstrapArgs = neko_new_object_array(env, ").append(bootstrapArgs.length).append(", __objCls, NULL); ");
        int[] tempCounter = {0};
        for (int i = 0; i < bootstrapArgs.length; i++) {
            appendBootstrapArgAssignment(sb, "__bootstrapArgs", i, bootstrapArgs[i], tempCounter, bootstrapArgTypes[i + 3]);
        }
        sb.append("__mh = neko_resolve_indy(env, ").append(siteId).append("LL, \"").append(cStringLiteral(currentOwnerInternalName)).append("\", \"")
            .append(cStringLiteral(indy.name)).append("\", \"")
            .append(cStringLiteral(indy.desc)).append("\", \"").append(cStringLiteral(indy.bsm.getOwner())).append("\", \"")
            .append(cStringLiteral(indy.bsm.getName())).append("\", \"").append(cStringLiteral(indy.bsm.getDesc())).append("\", __bootstrapArgs); ");
        sb.append("} ");
        sb.append("jobjectArray __invokeArgs = neko_new_object_array(env, ").append(argTypes.length).append(", __objCls, NULL); ");
        for (int i = 0; i < argTypes.length; i++) {
            sb.append("neko_set_object_array_element(env, __invokeArgs, ").append(i).append(", ")
                .append(boxValueExpression(argTypes[i], "arg" + i)).append("); ");
        }
        sb.append("jobject __indyResult = neko_call_mh(env, __mh, __invokeArgs); ");
        sb.append(unboxReturn(retType, "__indyResult"));
        sb.append("}");
        return sb.toString();
    }

    private Object[] adaptBootstrapArgs(Object[] originalArgs, Type[] bootstrapArgTypes) {
        int targetArgLength = Math.max(0, bootstrapArgTypes.length - 3);
        if (originalArgs.length < targetArgLength) {
            Object[] expanded = new Object[targetArgLength];
            System.arraycopy(originalArgs, 0, expanded, 0, originalArgs.length);
            if (targetArgLength - originalArgs.length != 1 || bootstrapArgTypes[originalArgs.length + 3].getSort() != Type.ARRAY) {
                throw new IllegalStateException("Unexpected bootstrap argument arity for " + currentMethodKey);
            }
            expanded[originalArgs.length] = new Object[0];
            return expanded;
        }
        boolean needsVarArgPacking = originalArgs.length > targetArgLength
            || (bootstrapArgTypes.length > 0
                && bootstrapArgTypes[bootstrapArgTypes.length - 1].getSort() == Type.ARRAY
                && originalArgs.length > 0
                && !(originalArgs[originalArgs.length - 1] instanceof Object[]));
        if (!needsVarArgPacking) {
            return originalArgs.clone();
        }
        Object[] packed = new Object[targetArgLength];
        System.arraycopy(originalArgs, 0, packed, 0, Math.max(0, targetArgLength - 1));
        Object[] varArgs = new Object[originalArgs.length - targetArgLength + 1];
        System.arraycopy(originalArgs, targetArgLength - 1, varArgs, 0, varArgs.length);
        packed[targetArgLength - 1] = varArgs;
        return packed;
    }

    private void appendBootstrapArgAssignment(StringBuilder sb, String arrayVar, int index, Object arg, int[] tempCounter, Type expectedType) {
        String expr = bootstrapArgExpression(sb, arg, tempCounter, expectedType);
        sb.append("neko_set_object_array_element(env, ").append(arrayVar).append(", ").append(index).append(", ")
            .append(expr).append("); ");
    }

    private String bootstrapArgExpression(StringBuilder sb, Object arg, int[] tempCounter, Type expectedType) {
        if (arg == null) {
            return "NULL";
        }
        if (arg instanceof Boolean value) {
            return "neko_box_boolean(env, " + (value ? "JNI_TRUE" : "JNI_FALSE") + ")";
        }
        if (arg instanceof Byte value) {
            return "neko_box_byte(env, (jbyte)" + value + ")";
        }
        if (arg instanceof Character value) {
            return "neko_box_char(env, (jchar)" + (int) value.charValue() + ")";
        }
        if (arg instanceof Short value) {
            return "neko_box_short(env, (jshort)" + value + ")";
        }
        if (arg instanceof Integer value) {
            return "neko_box_int(env, " + value + ")";
        }
        if (arg instanceof Long value) {
            return "neko_box_long(env, " + value + "LL)";
        }
        if (arg instanceof Float value) {
            return "neko_box_float(env, " + floatLiteral(value) + ")";
        }
        if (arg instanceof Double value) {
            return "neko_box_double(env, " + doubleLiteral(value) + ")";
        }
        if (arg instanceof String value) {
            return cachedStringExpression(value);
        }
        if (arg instanceof Type type) {
            return type.getSort() == Type.METHOD
                ? "neko_method_type_from_descriptor(env, \"" + cStringLiteral(type.getDescriptor()) + "\")"
                : "neko_class_for_descriptor(env, \"" + cStringLiteral(type.getDescriptor()) + "\")";
        }
        if (arg instanceof Handle handle) {
            return "neko_method_handle_from_parts(env, " + handle.getTag() + ", \"" + cStringLiteral(handle.getOwner()) + "\", \""
                + cStringLiteral(handle.getName()) + "\", \"" + cStringLiteral(handle.getDesc()) + "\", "
                + (handle.isInterface() ? "JNI_TRUE" : "JNI_FALSE") + ")";
        }
        if (arg instanceof ConstantDynamic constantDynamic) {
            return constantDynamicExpression(sb, constantDynamic, tempCounter);
        }
        if (arg instanceof Object[] array) {
            String arrayVar = "__indyArr" + tempCounter[0]++;
            Type componentType = expectedType != null && expectedType.getSort() == Type.ARRAY ? expectedType.getElementType() : null;
            String elementClassExpr = componentType != null && componentType.getSort() != Type.BOOLEAN && componentType.getSort() != Type.BYTE
                && componentType.getSort() != Type.CHAR && componentType.getSort() != Type.SHORT && componentType.getSort() != Type.INT
                && componentType.getSort() != Type.FLOAT && componentType.getSort() != Type.LONG && componentType.getSort() != Type.DOUBLE
                ? "neko_class_for_descriptor(env, \"" + cStringLiteral(componentType.getDescriptor()) + "\")"
                : "__objCls";
            sb.append("jobjectArray ").append(arrayVar).append(" = neko_new_object_array(env, ").append(array.length).append(", ")
                .append(elementClassExpr).append(", NULL); ");
            for (int i = 0; i < array.length; i++) {
                appendBootstrapArgAssignment(sb, arrayVar, i, array[i], tempCounter, componentType);
            }
            return arrayVar;
        }
        throw new IllegalStateException("Unsupported bootstrap argument type: " + arg.getClass().getName());
    }

    private String constantDynamicExpression(StringBuilder sb, ConstantDynamic constantDynamic, int[] tempCounter) {
        String argsVar = "__condyArgs" + tempCounter[0]++;
        sb.append("jobjectArray ").append(argsVar).append(" = neko_new_object_array(env, ").append(constantDynamic.getBootstrapMethodArgumentCount())
            .append(", __objCls, NULL); ");
        Type[] bootstrapArgTypes = Type.getArgumentTypes(constantDynamic.getBootstrapMethod().getDesc());
        for (int i = 0; i < constantDynamic.getBootstrapMethodArgumentCount(); i++) {
            appendBootstrapArgAssignment(sb, argsVar, i, constantDynamic.getBootstrapMethodArgument(i), tempCounter, bootstrapArgTypes[i + 3]);
        }
        Handle bsm = constantDynamic.getBootstrapMethod();
        return "neko_resolve_constant_dynamic(env, \"" + cStringLiteral(currentOwnerInternalName) + "\", \"" + cStringLiteral(constantDynamic.getName()) + "\", \""
            + cStringLiteral(constantDynamic.getDescriptor()) + "\", \"" + cStringLiteral(bsm.getOwner()) + "\", \""
            + cStringLiteral(bsm.getName()) + "\", \"" + cStringLiteral(bsm.getDesc()) + "\", " + argsVar + ")";
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

    private void appendStringBuilderLiteral(StringBuilder sb, String literal) {
        sb.append("{ jstring __lit = ").append(cachedStringExpression(literal)).append("; ");
        sb.append("jmethodID __append = ").append(cachedMethodExpression("java/lang/StringBuilder", "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false)).append("; ");
        sb.append("jvalue __appendArgs[1]; __appendArgs[0].l = __lit; ");
        sb.append("if (__append != NULL) { neko_call_object_method_a(env, __sb, __append, __appendArgs); } } ");
    }

    private void appendStringBuilderValue(StringBuilder sb, String appendSig, String accessor, String valueExpr) {
        sb.append("{ jmethodID __append = ").append(cachedMethodExpression("java/lang/StringBuilder", "append", appendSig, false)).append("; ");
        sb.append("jvalue __appendArgs[1]; __appendArgs[0]").append(accessor).append(" = ").append(valueExpr).append("; ");
        sb.append("if (__append != NULL) { neko_call_object_method_a(env, __sb, __append, __appendArgs); } } ");
    }

    private String cachedClassExpression(String owner) {
        codeGenerator.registerOwnerClassReference(currentOwnerInternalName, owner);
        return "neko_bound_class(env, " + classCacheVar(owner) + ", \"" + cStringLiteral(owner) + "\")";
    }

    private String cachedMethodExpression(String owner, String name, String desc, boolean isStatic) {
        codeGenerator.registerOwnerMethodReference(currentOwnerInternalName, owner, name, desc, isStatic);
        return "neko_bound_method(env, " + methodCacheVar(owner, name, desc, isStatic) + ", \"" + cStringLiteral(owner) + "\", \""
            + cStringLiteral(name) + "\", \"" + cStringLiteral(desc) + "\", " + (isStatic ? "JNI_TRUE" : "JNI_FALSE") + ")";
    }

    private String cachedFieldExpression(String owner, String name, String desc, boolean isStatic) {
        codeGenerator.registerOwnerFieldReference(currentOwnerInternalName, owner, name, desc, isStatic);
        return "neko_bound_field(env, " + fieldCacheVar(owner, name, desc, isStatic) + ", \"" + cStringLiteral(owner) + "\", \""
            + cStringLiteral(name) + "\", \"" + cStringLiteral(desc) + "\", " + (isStatic ? "JNI_TRUE" : "JNI_FALSE") + ")";
    }

    private String classCacheVar(String owner) {
        return codeGenerator.classSlotName(owner);
    }

    private String methodCacheVar(String owner, String name, String desc, boolean isStatic) {
        return codeGenerator.methodSlotName(owner, name, desc, isStatic);
    }

    private String fieldCacheVar(String owner, String name, String desc, boolean isStatic) {
        return codeGenerator.fieldSlotName(owner, name, desc, isStatic);
    }

    private String cachedStringExpression(String value) {
        String cacheVar = stringCacheVar(value);
        codeGenerator.registerOwnerStringReference(currentOwnerInternalName, value, cacheVar);
        return "neko_bound_string(env, " + cacheVar + ", \"" + cStringLiteral(value) + "\")";
    }

    private String stringCacheVar(String value) {
        return stringCacheVars.computeIfAbsent(value, ignored -> "g_str_" + stringCacheVars.size());
    }

    private String stringBuilderAppendSig(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN -> "(Z)Ljava/lang/StringBuilder;";
            case Type.CHAR -> "(C)Ljava/lang/StringBuilder;";
            case Type.BYTE, Type.SHORT, Type.INT -> "(I)Ljava/lang/StringBuilder;";
            case Type.LONG -> "(J)Ljava/lang/StringBuilder;";
            case Type.FLOAT -> "(F)Ljava/lang/StringBuilder;";
            case Type.DOUBLE -> "(D)Ljava/lang/StringBuilder;";
            default -> "Ljava/lang/String;".equals(type.getDescriptor())
                ? "(Ljava/lang/String;)Ljava/lang/StringBuilder;"
                : "(Ljava/lang/Object;)Ljava/lang/StringBuilder;";
        };
    }

    private long nextIndySiteId() {
        String key = currentMethodKey + '#' + indyIndex++;
        long hash = 1125899906842597L;
        for (int i = 0; i < key.length(); i++) {
            hash = 31L * hash + key.charAt(i);
        }
        return hash & Long.MAX_VALUE;
    }

    private boolean intrinsicsEnabled() {
        return !currentMethodKey.isEmpty();
    }

    private CStatement raw(String code) {
        return new CStatement.RawC(code);
    }

    private String bindingKey(String owner, String name, String desc) {
        return owner + '#' + name + desc;
    }

    private String cStringLiteral(String s) {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    private String floatLiteral(float value) {
        if (Float.isNaN(value)) {
            return "NAN";
        }
        if (Float.isInfinite(value)) {
            return value > 0 ? "INFINITY" : "-INFINITY";
        }
        return Float.toString(value) + 'f';
    }

    private String doubleLiteral(double value) {
        if (Double.isNaN(value)) {
            return "NAN";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "INFINITY" : "-INFINITY";
        }
        return Double.toString(value);
    }
}
