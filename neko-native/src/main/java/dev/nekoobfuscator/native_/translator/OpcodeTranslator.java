package dev.nekoobfuscator.native_.translator;

import dev.nekoobfuscator.core.ir.l3.CStatement;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator;
import dev.nekoobfuscator.native_.codegen.emit.SignaturePlan;
import dev.nekoobfuscator.native_.translator.NativeTranslator.NativeMethodBinding;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
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
    private String currentMethodName = "";
    private boolean currentMethodStatic = false;
    private String currentMethodKey = "";
    private boolean currentMethodShadowEnabled = true;
    private int indyIndex = 0;
    private int invokeSiteIndex = 0;
    private int stringConcatTempIndex = 0;

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
        beginMethod(owner, name, desc, isStatic, true);
    }

    public void beginMethod(String owner, String name, String desc, boolean isStatic, boolean shadowEnabled) {
        this.currentOwnerInternalName = owner;
        this.currentMethodName = name;
        this.currentMethodStatic = isStatic;
        this.currentMethodKey = owner + '#' + name + desc;
        this.currentMethodShadowEnabled = shadowEnabled;
        this.indyIndex = 0;
        this.invokeSiteIndex = 0;
        this.stringConcatTempIndex = 0;
        this.codeGenerator.registerBindingOwner(owner);
    }

    public int stringCacheCount() {
        return stringCacheVars.size();
    }

    public record FusedTranslation(String code, AbstractInsnNode lastInsn) {}

    /**
     * Peephole-fuse {@code AALOAD; <int-push>; XALOAD} into a single C
     * statement that reads the inner element directly off the outer array's
     * raw oop slot — eliminating the JNI handle allocation that the
     * standalone AALOAD would otherwise emit. Hot path for nested-array
     * patterns like matrix multiply ({@code a[i][k]}).
     *
     * Returns null when the pattern does not apply; in that case the caller
     * should fall back to the regular {@link #translate} path.
     */
    public FusedTranslation tryFuseArrayLoad(AbstractInsnNode insn) {
        if (insn.getOpcode() != Opcodes.AALOAD) return null;
        AbstractInsnNode n1 = nextNonMetaInsn(insn);
        if (!isStraightLineFusable(n1)) return null;
        String idx2Expr = intPushExpression(n1);
        if (idx2Expr == null) return null;
        AbstractInsnNode n2 = nextNonMetaInsn(n1);
        if (!isStraightLineFusable(n2)) return null;
        return buildFusedAALoad(idx2Expr, n2);
    }

    private boolean isStraightLineFusable(AbstractInsnNode n) {
        return n != null && !(n instanceof LabelNode);
    }

    private AbstractInsnNode nextNonMetaInsn(AbstractInsnNode from) {
        AbstractInsnNode n = from.getNext();
        while (n != null && (n instanceof LineNumberNode || n instanceof FrameNode)) {
            n = n.getNext();
        }
        return n;
    }

    private String intPushExpression(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op == Opcodes.ICONST_M1) return "-1";
        if (op >= Opcodes.ICONST_0 && op <= Opcodes.ICONST_5) return String.valueOf(op - Opcodes.ICONST_0);
        if (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH) return String.valueOf(((IntInsnNode) insn).operand);
        if (op == Opcodes.ILOAD) return "locals[" + ((VarInsnNode) insn).var + "].i";
        return null;
    }

    private FusedTranslation buildFusedAALoad(String idx2Expr, AbstractInsnNode loadInsn) {
        String prelude = "{ jint __idx2 = " + idx2Expr + "; jint __idx1 = POP_I(); jobjectArray __outer = (jobjectArray)POP_O(); ";
        return switch (loadInsn.getOpcode()) {
            case Opcodes.BALOAD -> new FusedTranslation(prelude + fusedArrayLoad("jbyte", "neko_fast_aaload_baload(thread, env, __outer, __idx1, __idx2, &__reason)", "PUSH_I((jint)__value);") + " }", loadInsn);
            case Opcodes.CALOAD -> new FusedTranslation(prelude + fusedArrayLoad("jchar", "neko_fast_aaload_caload(thread, env, __outer, __idx1, __idx2, &__reason)", "PUSH_I((jint)__value);") + " }", loadInsn);
            case Opcodes.SALOAD -> new FusedTranslation(prelude + fusedArrayLoad("jshort", "neko_fast_aaload_saload(thread, env, __outer, __idx1, __idx2, &__reason)", "PUSH_I((jint)__value);") + " }", loadInsn);
            case Opcodes.IALOAD -> new FusedTranslation(prelude + fusedArrayLoad("jint", "neko_fast_aaload_iaload(thread, env, __outer, __idx1, __idx2, &__reason)", "PUSH_I(__value);") + " }", loadInsn);
            case Opcodes.LALOAD -> new FusedTranslation(prelude + fusedArrayLoad("jlong", "neko_fast_aaload_laload(thread, env, __outer, __idx1, __idx2, &__reason)", "PUSH_L(__value);") + " }", loadInsn);
            case Opcodes.FALOAD -> new FusedTranslation(prelude + fusedArrayLoad("jfloat", "neko_fast_aaload_faload(thread, env, __outer, __idx1, __idx2, &__reason)", "PUSH_F(__value);") + " }", loadInsn);
            case Opcodes.DALOAD -> new FusedTranslation(prelude + fusedArrayLoad("jdouble", "neko_fast_aaload_daload(thread, env, __outer, __idx1, __idx2, &__reason)", "PUSH_D(__value);") + " }", loadInsn);
            case Opcodes.AALOAD -> new FusedTranslation(prelude + fusedArrayLoad("jobject", "neko_fast_aaload_aaload(thread, env, __outer, __idx1, __idx2, &__reason)", "PUSH_O(__value);") + " }", loadInsn);
            default -> null;
        };
    }

    private String primitiveArrayLoad(String arrayType, String success) {
        return arrayLoad(arrayType, success);
    }

    private String arrayLoad(String arrayType, String success) {
        return "{ jint __i = POP_I(); " + arrayType + " __a = (" + arrayType + ")POP_O(); "
            + arrayAccessBody("__a", "__i", success) + " }";
    }

    private String primitiveArrayStore(String valueType, String pop, String arrayType, String success) {
        return "{ " + valueType + " __v = (" + valueType + ")" + pop + "; " + arrayStoreBody(arrayType, success) + " }";
    }

    private String wideArrayStore(String valueType, String arrayType, String success) {
        String pop = "jlong".equals(valueType) ? "POP_L()" : "POP_D()";
        return "{ " + valueType + " __v = " + pop + "; " + arrayStoreBody(arrayType, success) + " }";
    }

    private String arrayStoreBody(String arrayType, String success) {
        return "jint __i = POP_I(); " + arrayType + " __a = (" + arrayType + ")POP_O(); "
            + arrayAccessBody("__a", "__i", success);
    }

    private String arrayAccessBody(String arrayExpr, String indexExpr, String success) {
        return "if (" + arrayExpr + " == NULL) { " + raiseImplicitException("java/lang/NullPointerException")
            + "; } else { jint __len = neko_fast_array_length((jarray)" + arrayExpr + "); if ("
            + indexExpr + " < 0 || " + indexExpr + " >= __len) { "
            + raiseImplicitException("java/lang/ArrayIndexOutOfBoundsException")
            + "; } else { " + success + " } }";
    }

    private String fusedArrayLoad(String valueType, String call, String success) {
        return "int __reason = 0; " + valueType + " __value = " + call + "; "
            + "if (__reason != NEKO_FAST_ARRAY_OK) { " + raiseFastArrayReason("__reason") + "; } else { " + success + " }";
    }

    private String raiseFastArrayReason(String reasonExpr) {
        String ctorDesc = "(Ljava/lang/String;)V";
        String dispatcher = codeGenerator.registerInvokeShape(false, 'V', new char[] { 'L' });
        return "neko_raise_fast_array_reason(thread, env, " + reasonExpr + ", "
            + cachedClassExpression("java/lang/NullPointerException") + ", "
            + cachedMethodPtrExpression("java/lang/NullPointerException", "<init>", ctorDesc, false) + ", "
            + cachedMethodIEntryExpression("java/lang/NullPointerException", "<init>", ctorDesc, false) + ", "
            + dispatcher + ", "
            + cachedClassExpression("java/lang/ArrayIndexOutOfBoundsException") + ", "
            + cachedMethodPtrExpression("java/lang/ArrayIndexOutOfBoundsException", "<init>", ctorDesc, false) + ", "
            + cachedMethodIEntryExpression("java/lang/ArrayIndexOutOfBoundsException", "<init>", ctorDesc, false) + ", "
            + dispatcher + ")";
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

            case Opcodes.IALOAD -> stmts.add(raw(primitiveArrayLoad("jintArray", "PUSH_I(neko_fast_iaload(__a, __i));")));
            case Opcodes.LALOAD -> stmts.add(raw(primitiveArrayLoad("jlongArray", "PUSH_L(neko_fast_laload(__a, __i));")));
            case Opcodes.FALOAD -> stmts.add(raw(primitiveArrayLoad("jfloatArray", "PUSH_F(neko_fast_faload(__a, __i));")));
            case Opcodes.DALOAD -> stmts.add(raw(primitiveArrayLoad("jdoubleArray", "PUSH_D(neko_fast_daload(__a, __i));")));
            case Opcodes.AALOAD -> stmts.add(raw(arrayLoad("jobjectArray", "PUSH_O(neko_fast_aaload(thread, env, __a, __i));")));
            case Opcodes.BALOAD -> stmts.add(raw(primitiveArrayLoad("jbyteArray", "PUSH_I((jint)neko_fast_baload(__a, __i));")));
            case Opcodes.CALOAD -> stmts.add(raw(primitiveArrayLoad("jcharArray", "PUSH_I((jint)neko_fast_caload(__a, __i));")));
            case Opcodes.SALOAD -> stmts.add(raw(primitiveArrayLoad("jshortArray", "PUSH_I((jint)neko_fast_saload(__a, __i));")));

            case Opcodes.IASTORE -> stmts.add(raw(primitiveArrayStore("jint", "POP_I()", "jintArray", "neko_fast_iastore(__a, __i, __v);")));
            case Opcodes.LASTORE -> stmts.add(raw(wideArrayStore("jlong", "jlongArray", "neko_fast_lastore(__a, __i, __v);")));
            case Opcodes.FASTORE -> stmts.add(raw(primitiveArrayStore("jfloat", "POP_F()", "jfloatArray", "neko_fast_fastore(__a, __i, __v);")));
            case Opcodes.DASTORE -> stmts.add(raw(wideArrayStore("jdouble", "jdoubleArray", "neko_fast_dastore(__a, __i, __v);")));
            case Opcodes.AASTORE -> stmts.add(raw("{ jobject __v = POP_O(); " + arrayStoreBody("jobjectArray", "neko_fast_aastore(thread, env, __a, __i, __v);") + " }"));
            case Opcodes.BASTORE -> stmts.add(raw(primitiveArrayStore("jint", "POP_I()", "jbyteArray", "neko_fast_bastore(__a, __i, (jbyte)__v);")));
            case Opcodes.CASTORE -> stmts.add(raw(primitiveArrayStore("jint", "POP_I()", "jcharArray", "neko_fast_castore(__a, __i, (jchar)__v);")));
            case Opcodes.SASTORE -> stmts.add(raw(primitiveArrayStore("jint", "POP_I()", "jshortArray", "neko_fast_sastore(__a, __i, (jshort)__v);")));

            case Opcodes.IADD -> stmts.add(raw("{ jint b = POP_I(); jint a = POP_I(); PUSH_I(a + b); }"));
            case Opcodes.ISUB -> stmts.add(raw("{ jint b = POP_I(); jint a = POP_I(); PUSH_I(a - b); }"));
            case Opcodes.IMUL -> stmts.add(raw("{ jint b = POP_I(); jint a = POP_I(); PUSH_I(a * b); }"));
            case Opcodes.IDIV -> stmts.add(raw("{ jint b = POP_I(); jint a = POP_I(); if (b == 0) { " + raiseImplicitException("java/lang/ArithmeticException") + "; } else { PUSH_I(a / b); } }"));
            case Opcodes.IREM -> stmts.add(raw("{ jint b = POP_I(); jint a = POP_I(); if (b == 0) { " + raiseImplicitException("java/lang/ArithmeticException") + "; } else { PUSH_I(a % b); } }"));
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
            case Opcodes.LDIV -> stmts.add(raw("{ jlong b = POP_L(); jlong a = POP_L(); if (b == 0) { " + raiseImplicitException("java/lang/ArithmeticException") + "; } else { PUSH_L(a / b); } }"));
            case Opcodes.LREM -> stmts.add(raw("{ jlong b = POP_L(); jlong a = POP_L(); if (b == 0) { " + raiseImplicitException("java/lang/ArithmeticException") + "; } else { PUSH_L(a % b); } }"));
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

            case Opcodes.IRETURN -> stmts.add(raw("{ jint __ret = POP_I(); " + shadowPop() + "return __ret; }"));
            case Opcodes.LRETURN -> stmts.add(raw("{ jlong __ret = POP_L(); " + shadowPop() + "return __ret; }"));
            case Opcodes.FRETURN -> stmts.add(raw("{ jfloat __ret = POP_F(); " + shadowPop() + "return __ret; }"));
            case Opcodes.DRETURN -> stmts.add(raw("{ jdouble __ret = POP_D(); " + shadowPop() + "return __ret; }"));
            case Opcodes.ARETURN -> stmts.add(raw("{ jobject __ret = POP_O(); " + shadowPop() + "return __ret; }"));
            case Opcodes.RETURN -> stmts.add(raw(shadowPop() + "return;"));

            case Opcodes.ARRAYLENGTH -> stmts.add(raw("{ jarray arr = (jarray)POP_O(); if (arr == NULL) { " + raiseImplicitException("java/lang/NullPointerException") + "; } else { PUSH_I(neko_fast_array_length(arr)); } }"));
            case Opcodes.ATHROW -> stmts.add(raw("{ jthrowable __athrow = (jthrowable)POP_O(); if (__athrow == NULL) { " + raiseImplicitException("java/lang/NullPointerException") + "; } else { neko_set_pending_exception(thread, __athrow); } }"));
            case Opcodes.MONITORENTER -> stmts.add(raw("{ jobject __mon = POP_O(); neko_fast_monitor_enter(thread, __mon, &monitors[monitor_sp++]); }"));
            case Opcodes.MONITOREXIT -> stmts.add(raw("{ jobject __mon = POP_O(); if (monitor_sp <= 0) { fprintf(stderr, \"[neko-direct] MONITOREXIT without matching MONITORENTER\\n\"); abort(); } neko_fast_monitor_exit(thread, __mon, &monitors[--monitor_sp]); }"));
            case Opcodes.NOP -> stmts.add(raw("/* nop */"));
            case Opcodes.IINC -> {
                IincInsnNode iinc = (IincInsnNode) insn;
                stmts.add(raw("locals[" + iinc.var + "].i += " + iinc.incr + ";"));
            }

            case Opcodes.NEW -> {
                TypeInsnNode ti = (TypeInsnNode) insn;
                stmts.add(raw("{ jclass cls = " + cachedClassExpression(ti.desc) + "; PUSH_O(neko_fast_alloc_object(thread, env, cls)); }"));
            }
            case Opcodes.NEWARRAY -> stmts.add(raw("{ jint len = POP_I(); PUSH_O(" + newArrayCall(((IntInsnNode) insn).operand) + "); }"));
            case Opcodes.ANEWARRAY -> {
                TypeInsnNode ti = (TypeInsnNode) insn;
                String cls = cachedClassExpression(ti.desc);
                String klassBits = codeGenerator.objectArrayKlassBitsSlotName(ti.desc);
                stmts.add(raw("{ jint len = POP_I(); jclass cls = " + cls + "; if (cls != NULL) { PUSH_O(neko_fast_new_object_array(thread, env, len, " + klassBits + ", NULL)); } }"));
            }

            case Opcodes.GETFIELD -> stmts.add(raw(translateFieldGet((FieldInsnNode) insn, false)));
            case Opcodes.PUTFIELD -> stmts.add(raw(translateFieldPut((FieldInsnNode) insn, false)));
            case Opcodes.GETSTATIC -> stmts.add(raw(translateFieldGet((FieldInsnNode) insn, true)));
            case Opcodes.PUTSTATIC -> stmts.add(raw(translateFieldPut((FieldInsnNode) insn, true)));

            case Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE, Opcodes.INVOKESPECIAL -> stmts.add(raw(translateMethodInvoke((MethodInsnNode) insn, opcode)));
            case Opcodes.INVOKESTATIC -> stmts.add(raw(translateStaticInvoke((MethodInsnNode) insn)));

            case Opcodes.INSTANCEOF -> {
                TypeInsnNode ti = (TypeInsnNode) insn;
                stmts.add(raw("{ jobject obj = POP_O(); jclass cls = " + cachedTypeClassExpression(ti.desc) + "; jint result = neko_fast_is_instance_of(env, obj, cls); PUSH_I(result); }"));
            }
            case Opcodes.CHECKCAST -> {
                TypeInsnNode ti = (TypeInsnNode) insn;
                stmts.add(raw("{ jobject obj = POP_O(); if (obj != NULL) { jclass cls = " + cachedTypeClassExpression(ti.desc) + "; if (!neko_fast_is_instance_of(env, obj, cls)) { " + raiseImplicitException("java/lang/ClassCastException") + "; goto __neko_exception_exit; } } if (!neko_exception_check(env)) { PUSH_O(obj); } }"));
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
            stmts.add(raw("PUSH_O(" + cachedStringExpression(s) + ");"));
        } else if (ldc.cst instanceof Type type) {
            stmts.add(raw("PUSH_O(" + cachedTypeClassExpression(type.getDescriptor()) + ");"));
        } else {
            stmts.add(raw("/* unsupported ldc constant */"));
        }
    }

    private String translateMethodInvoke(MethodInsnNode mi, int opcode) {
        String intrinsic = intrinsicsEnabled() ? translateIntrinsicMethodInvoke(mi, opcode) : null;
        if (intrinsic != null) {
            return intrinsic;
        }
        if ("java/lang/invoke/MethodHandle".equals(mi.owner)
            && ("invokeExact".equals(mi.name) || "invoke".equals(mi.name))) {
            return translateMethodHandleInvoke(mi);
        }
        NativeMethodBinding binding = translatedBindings.get(bindingKey(mi.owner, mi.name, mi.desc));
        if (canDirectInvoke(binding, opcode)) {
            return translateDirectInvoke(mi, binding, opcode == Opcodes.INVOKESTATIC, opcode == Opcodes.INVOKESPECIAL);
        }
        if (isClassLoaderDefineClass(mi)) {
            return translateClassLoaderDefineClassInvoke(mi, opcode);
        }
        if (opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE) {
            return translateVirtualDispatchWithCache(mi);
        }

        return translateBoundNjxInvoke(mi, false);
    }

    private boolean isClassLoaderDefineClass(MethodInsnNode mi) {
        if (!"defineClass".equals(mi.name) || !Type.getReturnType(mi.desc).equals(Type.getType(Class.class))) {
            return false;
        }
        Type[] args = Type.getArgumentTypes(mi.desc);
        return args.length >= 2
            && args[0].equals(Type.getType(String.class))
            && args[1].equals(Type.getType(byte[].class));
    }

    private String translateClassLoaderDefineClassInvoke(MethodInsnNode mi, int opcode) {
        Type[] args = Type.getArgumentTypes(mi.desc);
        Type ret = Type.getReturnType(mi.desc);
        String directDispatcher = codeGenerator.registerInvokeShape(false, SignaturePlan.collapseKind(ret), collapseArgKinds(args));
        StringBuilder sb = new StringBuilder("{ ");
        sb.append(declarePoppedArgs(args));
        sb.append("jobject obj = POP_O(); ");
        sb.append("jvalue __njx_result; __njx_result.j = 0; ");
        sb.append("if (obj == NULL) { ").append(raiseImplicitException("java/lang/NullPointerException")).append("; } else { ");
        sb.append("__njx_result = ").append(directDispatcher)
            .append("(thread, env, ")
            .append(cachedMethodPtrExpression(mi.owner, mi.name, mi.desc, false)).append(", ")
            .append(cachedMethodIEntryExpression(mi.owner, mi.name, mi.desc, false)).append(", obj, __args); ");
        sb.append("} ");
        sb.append("if (!neko_exception_check(env)) { ");
        sb.append("if (__njx_result.l != NULL) neko_manifest_patch_defined_class(env, (jclass)__njx_result.l); ");
        sb.append("PUSH_O(__njx_result.l); ");
        sb.append("} ");
        sb.append("}");
        return sb.toString();
    }

    private String translateVirtualDispatchWithCache(MethodInsnNode mi) {
        Type[] args = Type.getArgumentTypes(mi.desc);
        Type ret = Type.getReturnType(mi.desc);
        int siteIndex = invokeSiteIndex++;
        /* Register the callee's signature shape with the native→Java direct
         * invoke emitter and capture its dispatcher symbol so the icache
         * meta can route through it at runtime. */
        String directDispatcher = codeGenerator.registerInvokeShape(false, SignaturePlan.collapseKind(ret), collapseArgKinds(args));
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
            directCandidate == null ? null : directStub,
            directDispatcher
        );
        StringBuilder sb = new StringBuilder("{ ");
        sb.append(declarePoppedArgs(args));
        sb.append("jobject __recv = POP_O(); ");
        sb.append("jmethodID mid = ").append(cachedMethodExpression(mi.owner, mi.name, mi.desc, false)).append("; ");
        if (ret.getSort() == Type.VOID) {
            sb.append("neko_icache_dispatch(thread, env, &").append(cacheSite).append(", &")
                .append(metaSite).append(", __recv, mid, __args); ");
        } else {
            sb.append("jvalue __ic_result = neko_icache_dispatch(thread, env, &").append(cacheSite).append(", &")
                .append(metaSite).append(", __recv, mid, __args); ");
            sb.append("if (!neko_exception_check(env)) { ").append(pushForType(ret, "__ic_result" + jvalueAccessor(ret))).append(" } ");
        }
        sb.append("}");
        return sb.toString();
    }

    private static char[] collapseArgKinds(Type[] args) {
        char[] kinds = new char[args.length];
        for (int i = 0; i < args.length; i++) kinds[i] = SignaturePlan.collapseKind(args[i]);
        return kinds;
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
        if (opcode == Opcodes.INVOKESTATIC) {
            if ("java/lang/invoke/MethodHandles".equals(mi.owner) && "lookup".equals(mi.name) && "()Ljava/lang/invoke/MethodHandles$Lookup;".equals(mi.desc)) {
                String callerClass = currentMethodStatic ? "clazz" : "neko_fast_get_object_class(thread, self)";
                String privateLookupDesc = "(Ljava/lang/Class;Ljava/lang/invoke/MethodHandles$Lookup;)Ljava/lang/invoke/MethodHandles$Lookup;";
                String dispatcher = codeGenerator.registerInvokeShape(true, 'L', new char[] { 'L', 'L' });
                return "{ jclass __callerCls = " + callerClass + "; "
                    + "jclass __lookupCls = " + cachedClassExpression("java/lang/invoke/MethodHandles$Lookup") + "; "
                    + "jfieldID __implFid = " + cachedFieldExpression("java/lang/invoke/MethodHandles$Lookup", "IMPL_LOOKUP", "Ljava/lang/invoke/MethodHandles$Lookup;", true) + "; "
                    + "jobject __implLookup = neko_fast_get_static_object_field(thread, env, __lookupCls, __implFid, "
                    + codeGenerator.staticFieldBaseSlotName("java/lang/invoke/MethodHandles$Lookup", "IMPL_LOOKUP", "Ljava/lang/invoke/MethodHandles$Lookup;", true) + ", "
                    + codeGenerator.staticFieldOffsetSlotName("java/lang/invoke/MethodHandles$Lookup", "IMPL_LOOKUP", "Ljava/lang/invoke/MethodHandles$Lookup;", true) + "); "
                    + "jvalue __lookupArgs[2]; __lookupArgs[0].l = __callerCls; __lookupArgs[1].l = __implLookup; "
                    + "jvalue __lookupRet = " + dispatcher + "(thread, env, "
                    + cachedMethodPtrExpression("java/lang/invoke/MethodHandles", "privateLookupIn", privateLookupDesc, true) + ", "
                    + cachedMethodIEntryExpression("java/lang/invoke/MethodHandles", "privateLookupIn", privateLookupDesc, true)
                    + ", NULL, __lookupArgs); if (!neko_exception_check(env)) { PUSH_O(__lookupRet.l); } }";
            }
            if ("java/lang/Thread".equals(mi.owner) && "sleep".equals(mi.name) && "(J)V".equals(mi.desc)) {
                String dispatcher = codeGenerator.registerInvokeShape(true, 'V', new char[] { 'J' });
                return "{ jlong arg0 = POP_L(); jvalue __args[1]; __args[0].j = (jlong)arg0; "
                    + dispatcher + "(thread, env, "
                    + cachedMethodPtrExpression(mi.owner, mi.name, mi.desc, true) + ", "
                    + cachedMethodIEntryExpression(mi.owner, mi.name, mi.desc, true)
                    + ", NULL, __args); "
                    + "if (!neko_exception_check(env) && arg0 >= 50) { neko_post_blocking_call_yield(); } }";
            }
        }
        if (opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKESPECIAL) {
            if ("java/lang/String".equals(mi.owner) && "length".equals(mi.name) && "()I".equals(mi.desc)) {
                return "{ jstring obj = (jstring)POP_O(); if (obj == NULL) { "
                    + raiseImplicitException("java/lang/NullPointerException")
                    + "; } else { jfieldID __stringValue = "
                    + cachedFieldExpression("java/lang/String", "value", "[B", false)
                    + "; jfieldID __stringCoder = "
                    + cachedFieldExpression("java/lang/String", "coder", "B", false)
                    + "; (void)__stringValue; (void)__stringCoder; PUSH_I(neko_fast_string_length(env, obj, "
                    + codeGenerator.fieldOffsetSlotName("java/lang/String", "value", "[B", false)
                    + ", "
                    + codeGenerator.fieldOffsetSlotName("java/lang/String", "coder", "B", false)
                    + ")); } }";
            }
            if ("java/lang/Object".equals(mi.owner) && "getClass".equals(mi.name) && "()Ljava/lang/Class;".equals(mi.desc)) {
                return "{ jobject obj = POP_O(); if (obj == NULL) { "
                    + raiseImplicitException("java/lang/NullPointerException")
                    + "; } else { PUSH_O(neko_fast_get_object_class(thread, obj)); } }";
            }
            if ("java/lang/Throwable".equals(mi.owner) && "getStackTrace".equals(mi.name) && "()[Ljava/lang/StackTraceElement;".equals(mi.desc)) {
                return "{ jobject obj = POP_O(); if (obj == NULL) { "
                    + raiseImplicitException("java/lang/NullPointerException")
                    + "; } else { jobjectArray __trace = neko_shadow_stack_trace(thread, env); if (!neko_exception_check(env)) { PUSH_O(__trace); } } }";
            }
            if ("java/util/concurrent/atomic/AtomicLong".equals(mi.owner) && "addAndGet".equals(mi.name) && "(J)J".equals(mi.desc)) {
                return "{ jlong __delta = POP_L(); jobject obj = POP_O(); if (obj == NULL) { "
                    + raiseImplicitException("java/lang/NullPointerException")
                    + "; } else { jfieldID __value = "
                    + cachedFieldExpression("java/util/concurrent/atomic/AtomicLong", "value", "J", false)
                    + "; (void)__value; PUSH_L(neko_fast_atomic_long_add_and_get(env, obj, __delta, "
                    + codeGenerator.fieldOffsetSlotName("java/util/concurrent/atomic/AtomicLong", "value", "J", false)
                    + ")); } }";
            }
            if ("java/util/concurrent/atomic/AtomicInteger".equals(mi.owner) && "addAndGet".equals(mi.name) && "(I)I".equals(mi.desc)) {
                return "{ jint __delta = POP_I(); jobject obj = POP_O(); if (obj == NULL) { "
                    + raiseImplicitException("java/lang/NullPointerException")
                    + "; } else { jfieldID __value = "
                    + cachedFieldExpression("java/util/concurrent/atomic/AtomicInteger", "value", "I", false)
                    + "; (void)__value; PUSH_I(neko_fast_atomic_int_add_and_get(env, obj, __delta, "
                    + codeGenerator.fieldOffsetSlotName("java/util/concurrent/atomic/AtomicInteger", "value", "I", false)
                    + ")); } }";
            }
            if ("java/lang/reflect/Method".equals(mi.owner) && "invoke".equals(mi.name) && "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;".equals(mi.desc)) {
                String adapterDesc = "(Ljava/lang/Object;[Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;";
                Type[] adapterArgs = Type.getArgumentTypes(adapterDesc);
                String adapterDispatcher = codeGenerator.registerInvokeShape(false, 'L', collapseArgKinds(adapterArgs));
                String callerClass = currentMethodStatic ? "clazz" : "neko_fast_get_object_class(thread, self)";
                return "{ jobject __invokeArgsArray = POP_O(); "
                    + "jobject __targetObj = POP_O(); "
                    + "jobject __methodObj = POP_O(); "
                    + "if (__methodObj == NULL) { "
                    + raiseImplicitException("java/lang/NullPointerException")
                    + "; } else { "
                    + "jclass __callerCls = " + callerClass + "; "
                    + "jvalue __reflectResult; __reflectResult.j = 0; "
                    + "if (__callerCls != NULL) { "
                    + "jvalue __reflectArgs[3]; "
                    + "__reflectArgs[0].l = __targetObj; "
                    + "__reflectArgs[1].l = __invokeArgsArray; "
                    + "__reflectArgs[2].l = __callerCls; "
                    + "__reflectResult = " + adapterDispatcher + "(thread, env, "
                    + cachedMethodPtrExpression("java/lang/reflect/Method", "invoke", adapterDesc, false) + ", "
                    + cachedMethodIEntryExpression("java/lang/reflect/Method", "invoke", adapterDesc, false) + ", "
                    + "__methodObj, __reflectArgs); "
                    + "} "
                    + "if (!neko_exception_check(env)) { PUSH_O(__reflectResult.l); } } }";
            }
        }
        return null;
    }

    public static String simpleSourceFileName(String ownerInternalName) {
        int slash = ownerInternalName.lastIndexOf('/');
        String simple = slash >= 0 ? ownerInternalName.substring(slash + 1) : ownerInternalName;
        return simple + ".java";
    }

    private String translateMethodHandleInvoke(MethodInsnNode mi) {
        if (("invoke".equals(mi.name) || "invokeExact".equals(mi.name)) && methodHandleBridgeFactory != null && !currentOwnerInternalName.isEmpty()) {
            MethodHandleBridge bridge = methodHandleBridgeFactory.ensureBridge(currentOwnerInternalName, mi.desc);
            if (bridge != null) {
                return translateMethodHandleBridgeInvoke(mi, bridge);
            }
        }

        throw new IllegalStateException("Unsupported MethodHandle invoke without native call-stub bridge: "
            + currentMethodKey + " -> " + mi.name + mi.desc);
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
            sb.append(jvalueStore(args[i], "__bridgeArgs[" + (i + 1) + "]", "arg" + i)).append(' ');
        }
        Type[] bridgeArgs = Type.getArgumentTypes(bridge.descriptor());
        String bridgeDispatcher = codeGenerator.registerInvokeShape(true, SignaturePlan.collapseKind(ret), collapseArgKinds(bridgeArgs));
        sb.append("(void)__bridgeCls; ");
        if (ret.getSort() == Type.VOID) {
            sb.append(bridgeDispatcher).append("(thread, env, ")
                .append(cachedMethodPtrExpression(bridge.ownerInternalName(), bridge.name(), bridge.descriptor(), true)).append(", ")
                .append(cachedMethodIEntryExpression(bridge.ownerInternalName(), bridge.name(), bridge.descriptor(), true))
                .append(", NULL, __bridgeArgs); ");
        } else {
            sb.append("jvalue __bridgeResult = ").append(bridgeDispatcher).append("(thread, env, ")
                .append(cachedMethodPtrExpression(bridge.ownerInternalName(), bridge.name(), bridge.descriptor(), true)).append(", ")
                .append(cachedMethodIEntryExpression(bridge.ownerInternalName(), bridge.name(), bridge.descriptor(), true))
                .append(", NULL, __bridgeArgs); ");
            sb.append("if (!neko_exception_check(env)) { ").append(pushForType(ret, "__bridgeResult" + jvalueAccessor(ret))).append(" } ");
        }
        sb.append("}");
        return sb.toString();
    }

    private String translateStaticInvoke(MethodInsnNode mi) {
        String intrinsic = intrinsicsEnabled() ? translateIntrinsicMethodInvoke(mi, Opcodes.INVOKESTATIC) : null;
        if (intrinsic != null) {
            return intrinsic;
        }
        NativeMethodBinding binding = translatedBindings.get(bindingKey(mi.owner, mi.name, mi.desc));
        if (binding != null && binding.isStatic()) {
            return translateDirectInvoke(mi, binding, true, false);
        }

        Type[] args = Type.getArgumentTypes(mi.desc);
        Type ret = Type.getReturnType(mi.desc);
        return translateBoundNjxInvoke(mi, true);
    }

    private String translateBoundNjxInvoke(MethodInsnNode mi, boolean isStatic) {
        Type[] args = Type.getArgumentTypes(mi.desc);
        Type ret = Type.getReturnType(mi.desc);
        String directDispatcher = codeGenerator.registerInvokeShape(isStatic, SignaturePlan.collapseKind(ret), collapseArgKinds(args));
        StringBuilder sb = new StringBuilder("{ ");
        sb.append(declarePoppedArgs(args));
        sb.append("/* jclass cls = direct; jmethodID mid = direct; */ ");
        String receiver = "NULL";
        if (!isStatic) {
            sb.append("jobject obj = POP_O(); ");
            receiver = "obj";
        }
        if (ret.getSort() == Type.VOID) {
            if (!isStatic) {
                sb.append("if (obj == NULL) { ").append(raiseImplicitException("java/lang/NullPointerException"))
                    .append("; } else { ");
            } else {
                sb.append("{ ");
            }
            sb.append(directDispatcher)
                .append("(thread, env, ")
                .append(cachedMethodPtrExpression(mi.owner, mi.name, mi.desc, isStatic)).append(", ")
                .append(cachedMethodIEntryExpression(mi.owner, mi.name, mi.desc, isStatic)).append(", ")
                .append(receiver).append(", __args); } ");
        } else {
            sb.append("jvalue __njx_result; __njx_result.j = 0; ");
            if (!isStatic) {
                sb.append("if (obj == NULL) { ").append(raiseImplicitException("java/lang/NullPointerException"))
                    .append("; } else { __njx_result = ");
            } else {
                sb.append("__njx_result = ");
            }
            sb.append(directDispatcher)
                .append("(thread, env, ")
                .append(cachedMethodPtrExpression(mi.owner, mi.name, mi.desc, isStatic)).append(", ")
                .append(cachedMethodIEntryExpression(mi.owner, mi.name, mi.desc, isStatic)).append(", ")
                .append(receiver).append(", __args); ");
            if (!isStatic) {
                sb.append("} ");
            }
            sb.append("if (!neko_exception_check(env)) { ")
                .append(pushForType(ret, "__njx_result" + jvalueAccessor(ret))).append(" } ");
        }
        sb.append("}");
        return sb.toString();
    }

    private String translateDirectInvoke(MethodInsnNode mi, NativeMethodBinding binding, boolean isStatic, boolean isSpecial) {
        Type[] args = Type.getArgumentTypes(mi.desc);
        Type ret = Type.getReturnType(mi.desc);
        StringBuilder sb = new StringBuilder("{ ");
        for (int i = args.length - 1; i >= 0; i--) {
            sb.append(jniTypeName(args[i])).append(" arg").append(i).append(" = ").append(popForType(args[i])).append("; ");
        }
        String receiverExpr;
        String guardExpr = null;
        if (isStatic) {
            sb.append("jclass receiverCls = ").append(cachedClassExpression(mi.owner)).append("; ");
            receiverExpr = "receiverCls";
            guardExpr = "receiverCls != NULL";
        } else {
            sb.append("jobject obj = POP_O(); ");
            receiverExpr = "obj";
        }
        if (ret.getSort() == Type.VOID) {
            if (guardExpr != null) {
                sb.append("if (").append(guardExpr).append(") ");
            }
            sb.append(binding.rawFunctionName()).append("(thread, env, ").append(receiverExpr);
        } else {
            sb.append(jniTypeName(ret)).append(" result = (").append(jniTypeName(ret)).append(")0; ");
            if (guardExpr != null) {
                sb.append("if (").append(guardExpr).append(") ");
            }
            sb.append("result = ").append(binding.rawFunctionName()).append("(thread, env, ").append(receiverExpr);
        }
        for (int i = 0; i < args.length; i++) {
            sb.append(", arg").append(i);
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

    private String translateFieldGet(FieldInsnNode fi, boolean isStatic) {
        if (isPrimitiveFastField(fi.desc)) {
            return translatePrimitiveFieldGet(fi, isStatic);
        }
        StringBuilder sb = new StringBuilder("{ ");
        if (isStatic) {
            sb.append("jclass cls = ").append(cachedClassExpression(fi.owner)).append("; ");
            sb.append(staticClassInitExpression(fi.owner)).append(' ');
            sb.append("jfieldID fid = ").append(cachedFieldExpression(fi.owner, fi.name, fi.desc, true)).append("; ");
            sb.append(pushForType(Type.getType(fi.desc),
                "neko_fast_get_static_object_field(thread, env, cls, fid, "
                    + codeGenerator.staticFieldBaseSlotName(fi.owner, fi.name, fi.desc, true) + ", "
                    + codeGenerator.staticFieldOffsetSlotName(fi.owner, fi.name, fi.desc, true) + ")"
            ));
        } else {
            sb.append("jobject obj = POP_O(); ");
            sb.append("jfieldID fid = ").append(cachedFieldExpression(fi.owner, fi.name, fi.desc, false)).append("; ");
            sb.append(pushForType(Type.getType(fi.desc),
                "neko_fast_get_object_field(thread, env, obj, fid, "
                    + codeGenerator.fieldOffsetSlotName(fi.owner, fi.name, fi.desc, false) + ")"
            ));
        }
        sb.append("}");
        return sb.toString();
    }

    private String translateFieldPut(FieldInsnNode fi, boolean isStatic) {
        Type type = Type.getType(fi.desc);
        if (isPrimitiveFastField(fi.desc)) {
            return translatePrimitiveFieldPut(fi, isStatic, type);
        }
        StringBuilder sb = new StringBuilder("{ ");
        sb.append(jniTypeName(type)).append(" val = ").append(popForType(type)).append("; ");
        if (isStatic) {
            sb.append("jclass cls = ").append(cachedClassExpression(fi.owner)).append("; ");
            sb.append(staticClassInitExpression(fi.owner)).append(' ');
            sb.append("jfieldID fid = ").append(cachedFieldExpression(fi.owner, fi.name, fi.desc, true)).append("; ");
            sb.append("neko_fast_set_static_object_field(thread, env, cls, fid, ")
                .append(codeGenerator.staticFieldBaseSlotName(fi.owner, fi.name, fi.desc, true)).append(", ")
                .append(codeGenerator.staticFieldOffsetSlotName(fi.owner, fi.name, fi.desc, true)).append(", val); ");
        } else {
            sb.append("jobject obj = POP_O(); ");
            sb.append("jfieldID fid = ").append(cachedFieldExpression(fi.owner, fi.name, fi.desc, false)).append("; ");
            sb.append("neko_fast_set_object_field(thread, env, obj, fid, ")
                .append(codeGenerator.fieldOffsetSlotName(fi.owner, fi.name, fi.desc, false)).append(", val); ");
        }
        sb.append("}");
        return sb.toString();
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
            sb.append(jvalueStore(args[i], "__args[" + i + "]", "arg" + i)).append(' ');
        }
        return sb.toString();
    }

    private boolean isPrimitiveFastField(String desc) {
        return desc.length() == 1 && "ZBCSIJFD".indexOf(desc.charAt(0)) >= 0;
    }

    private String translatePrimitiveFieldGet(FieldInsnNode fi, boolean isStatic) {
        Type type = Type.getType(fi.desc);
        char primitive = fi.desc.charAt(0);
        StringBuilder sb = new StringBuilder("{ ");
        if (isStatic) {
            sb.append("jclass cls = ").append(cachedClassExpression(fi.owner)).append("; ");
            sb.append(staticClassInitExpression(fi.owner)).append(' ');
            sb.append("jfieldID fid = ").append(cachedFieldExpression(fi.owner, fi.name, fi.desc, true)).append("; ");
            sb.append(pushForType(type, "neko_fast_get_static_" + primitive + "_field(env, cls, fid, "
                    + codeGenerator.staticFieldBaseSlotName(fi.owner, fi.name, fi.desc, true) + ", "
                    + codeGenerator.staticFieldOffsetSlotName(fi.owner, fi.name, fi.desc, true) + ")"));
        } else {
            sb.append("jobject obj = POP_O(); jfieldID fid = ").append(cachedFieldExpression(fi.owner, fi.name, fi.desc, false)).append("; ");
            sb.append(pushForType(type, "neko_fast_get_" + primitive + "_field(env, obj, fid, "
                    + codeGenerator.fieldOffsetSlotName(fi.owner, fi.name, fi.desc, false) + ", \""
                    + cStringLiteral(fi.owner) + "\", \"" + cStringLiteral(fi.name) + "\")"));
        }
        sb.append("}");
        return sb.toString();
    }

    private String translatePrimitiveFieldPut(FieldInsnNode fi, boolean isStatic, Type type) {
        char primitive = fi.desc.charAt(0);
        StringBuilder sb = new StringBuilder("{ ");
        sb.append(jniTypeName(type)).append(" val = ").append(popForType(type)).append("; ");
        if (isStatic) {
            sb.append("jclass cls = ").append(cachedClassExpression(fi.owner)).append("; ");
            sb.append(staticClassInitExpression(fi.owner)).append(' ');
            sb.append("jfieldID fid = ").append(cachedFieldExpression(fi.owner, fi.name, fi.desc, true)).append("; ");
            sb.append("neko_fast_set_static_").append(primitive).append("_field(env, cls, fid, ")
                .append(codeGenerator.staticFieldBaseSlotName(fi.owner, fi.name, fi.desc, true)).append(", ")
                .append(codeGenerator.staticFieldOffsetSlotName(fi.owner, fi.name, fi.desc, true)).append(", val); ");
        } else {
            sb.append("jobject obj = POP_O(); jfieldID fid = ").append(cachedFieldExpression(fi.owner, fi.name, fi.desc, false)).append("; ");
            sb.append("neko_fast_set_").append(primitive).append("_field(env, obj, fid, ")
                .append(codeGenerator.fieldOffsetSlotName(fi.owner, fi.name, fi.desc, false)).append(", val, \"")
                .append(cStringLiteral(fi.owner)).append("\", \"").append(cStringLiteral(fi.name)).append("\"); ");
        }
        sb.append("}");
        return sb.toString();
    }

    private String newArrayCall(int operand) {
        return switch (operand) {
            case 4 -> "neko_fast_new_primitive_array(thread, env, len, NEKO_PRIM_Z)";
            case 5 -> "neko_fast_new_primitive_array(thread, env, len, NEKO_PRIM_C)";
            case 6 -> "neko_fast_new_primitive_array(thread, env, len, NEKO_PRIM_F)";
            case 7 -> "neko_fast_new_primitive_array(thread, env, len, NEKO_PRIM_D)";
            case 8 -> "neko_fast_new_primitive_array(thread, env, len, NEKO_PRIM_B)";
            case 9 -> "neko_fast_new_primitive_array(thread, env, len, NEKO_PRIM_S)";
            case 10 -> "neko_fast_new_primitive_array(thread, env, len, NEKO_PRIM_I)";
            case 11 -> "neko_fast_new_primitive_array(thread, env, len, NEKO_PRIM_J)";
            default -> "neko_fast_new_primitive_array(thread, env, len, NEKO_PRIM_I)";
        };
    }

    private boolean canDirectInvoke(NativeMethodBinding binding, int opcode) {
        if (binding == null) {
            return false;
        }
        if (currentOwnerInternalName != null
            && currentOwnerInternalName.contains("$NekoLambda$")
            && opcode != Opcodes.INVOKESTATIC) {
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
            codeGenerator.registerOwnerPrimitiveClassReference(currentOwnerInternalName, desc);
            return "neko_bound_class(thread, env, " + codeGenerator.primitiveClassSlotName(desc) + ", \"" + cStringLiteral(desc) + "\")";
        }
        if (classLookupName.equals(currentOwnerInternalName)) {
            return "neko_bound_current_owner_class(thread, env, " + codeGenerator.classSlotName(classLookupName)
                + ", \"" + cStringLiteral(classLookupName) + "\", "
                + (currentMethodStatic ? "(jobject)clazz" : "self")
                + ", " + (currentMethodStatic ? "JNI_TRUE" : "JNI_FALSE") + ")";
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

    private String jvalueStore(Type type, String slotExpr, String valueExpr) {
        return switch (type.getSort()) {
            case Type.BOOLEAN -> slotExpr + ".i = (jint)((jboolean)" + valueExpr + ");";
            case Type.BYTE -> slotExpr + ".i = (jint)((jbyte)" + valueExpr + ");";
            case Type.CHAR -> slotExpr + ".i = (jint)((jchar)" + valueExpr + ");";
            case Type.SHORT -> slotExpr + ".i = (jint)((jshort)" + valueExpr + ");";
            case Type.INT -> slotExpr + ".i = (jint)" + valueExpr + ";";
            case Type.FLOAT -> slotExpr + ".f = (jfloat)" + valueExpr + ";";
            case Type.LONG -> slotExpr + ".j = (jlong)" + valueExpr + ";";
            case Type.DOUBLE -> slotExpr + ".d = (jdouble)" + valueExpr + ";";
            default -> slotExpr + ".l = (jobject)" + valueExpr + ";";
        };
    }

    private String translateMultiANewArray(MultiANewArrayInsnNode insn) {
        StringBuilder sb = new StringBuilder("{ jint __dims[").append(insn.dims).append("]; ");
        for (int i = insn.dims - 1; i >= 0; i--) {
            sb.append("__dims[").append(i).append("] = POP_I(); ");
        }
        sb.append("PUSH_O(neko_multi_new_array(thread, env, ").append(insn.dims).append(", __dims, \"")
            .append(cStringLiteral(insn.desc)).append("\", ").append(currentOwnerClassExpression()).append(")); }");
        return sb.toString();
    }

    private String currentOwnerClassExpression() {
        return "neko_bound_current_owner_class(thread, env, " + codeGenerator.classSlotName(currentOwnerInternalName)
            + ", \"" + cStringLiteral(currentOwnerInternalName) + "\", "
            + (currentMethodStatic ? "(jobject)clazz" : "self")
            + ", " + (currentMethodStatic ? "JNI_TRUE" : "JNI_FALSE") + ")";
    }

    private String translateInvokeDynamic(InvokeDynamicInsnNode indy) {
        Type[] argTypes = Type.getArgumentTypes(indy.desc);
        if ("java/lang/invoke/StringConcatFactory".equals(indy.bsm.getOwner())
            && "makeConcatWithConstants".equals(indy.bsm.getName())) {
            return translateStringConcatInvokeDynamic(indy, argTypes);
        }
        if ("java/lang/runtime/SwitchBootstraps".equals(indy.bsm.getOwner())
            && ("typeSwitch".equals(indy.bsm.getName()) || "enumSwitch".equals(indy.bsm.getName()))) {
            return translateSwitchInvokeDynamic(indy);
        }
        throw new IllegalStateException("Unsupported invokedynamic without native lowering: "
            + currentMethodKey + " -> " + indy.bsm.getOwner() + "." + indy.bsm.getName() + indy.bsm.getDesc());
    }

    private String translateSwitchInvokeDynamic(InvokeDynamicInsnNode indy) {
        Type[] args = Type.getArgumentTypes(indy.desc);
        if (args.length != 2 || args[0].getSort() != Type.OBJECT || args[1].getSort() != Type.INT
            || Type.getReturnType(indy.desc).getSort() != Type.INT) {
            throw new IllegalStateException("Unsupported switch invokedynamic descriptor: " + indy.desc);
        }
        StringBuilder sb = new StringBuilder("{ ");
        sb.append("jint __restart = POP_I(); jobject __selector = POP_O(); jint __switchResult = -1; ");
        for (int i = 0; i < indy.bsmArgs.length; i++) {
            Object label = indy.bsmArgs[i];
            if (label instanceof Type type && type.getSort() == Type.OBJECT) {
                sb.append("if (__switchResult < 0 && __restart <= ").append(i).append(" && __selector != NULL && ")
                    .append("neko_fast_is_instance_of(env, __selector, ").append(cachedTypeClassExpression(type.getDescriptor())).append(")) ")
                    .append("{ __switchResult = ").append(i).append("; } ");
            } else if (label instanceof String value) {
                String equalsDesc = "(Ljava/lang/Object;)Z";
                String equalsDispatcher = codeGenerator.registerInvokeShape(false, 'Z', new char[] { 'L' });
                sb.append("if (__switchResult < 0 && __restart <= ").append(i).append(" && __selector != NULL) { ")
                    .append("jvalue __switchArgs[1]; __switchArgs[0].l = ").append(cachedStringExpression(value)).append("; ")
                    .append("jvalue __switchEq = ").append(equalsDispatcher).append("(thread, env, ")
                    .append(cachedMethodPtrExpression("java/lang/String", "equals", equalsDesc, false)).append(", ")
                    .append(cachedMethodIEntryExpression("java/lang/String", "equals", equalsDesc, false))
                    .append(", __selector, __switchArgs); if (!neko_exception_check(env) && __switchEq.z) { __switchResult = ")
                    .append(i).append("; } } ");
            } else if (label instanceof Integer value) {
                sb.append("if (__switchResult < 0 && __restart <= ").append(i)
                    .append(" && __selector == (jobject)(intptr_t)").append(value).append(") { __switchResult = ")
                    .append(i).append("; } ");
            } else {
                throw new IllegalStateException("Unsupported switch bootstrap label: " + label);
            }
        }
        sb.append("PUSH_I(__switchResult); }");
        return sb.toString();
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
        sb.append("jstring __acc = NULL; ");

        int dynIndex = 0;
        int constIndex = 1;
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < recipe.length(); i++) {
            char ch = recipe.charAt(i);
            if (ch == '\u0001' || ch == '\u0002') {
                if (literal.length() > 0) {
                    appendSimpleConcatLiteral(sb, literal.toString());
                    literal.setLength(0);
                }
                if (ch == '\u0001') {
                    Type argType = argTypes[dynIndex];
                    String concatArg = appendStringValueOf(sb, argType, "arg" + dynIndex);
                    appendSimpleConcatArg(sb, concatArg);
                    dynIndex++;
                } else {
                    Object constant = constIndex < indy.bsmArgs.length ? indy.bsmArgs[constIndex++] : "";
                    appendSimpleConcatLiteral(sb, String.valueOf(constant));
                }
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
        appendDirectStringConcat(sb, literalExpr);
    }

    private void appendSimpleConcatArg(StringBuilder sb, String valueExpr) {
        sb.append("if (__acc == NULL) { __acc = (jstring)(").append(valueExpr).append(" == NULL ? neko_string_null(env) : ")
            .append(valueExpr).append("); } else { ");
        appendDirectStringConcat(sb, valueExpr);
        sb.append("} ");
    }

    private String appendStringValueOf(StringBuilder sb, Type type, String valueExpr) {
        if ("Ljava/lang/String;".equals(type.getDescriptor())) {
            return valueExpr;
        }
        String valueOfDesc = stringValueOfDesc(type);
        String valueOfDispatcher = codeGenerator.registerInvokeShape(true, 'L', collapseArgKinds(Type.getArgumentTypes(valueOfDesc)));
        String temp = "__concatStr" + stringConcatTempIndex++;
        sb.append("jstring ").append(temp).append(" = NULL; { jvalue __valueOfArgs[1]; ")
            .append(jvalueStore(Type.getArgumentTypes(valueOfDesc)[0], "__valueOfArgs[0]", valueExpr)).append(' ')
            .append("jvalue __valueOfResult = ").append(valueOfDispatcher).append("(thread, env, ")
            .append(cachedMethodPtrExpression("java/lang/String", "valueOf", valueOfDesc, true)).append(", ")
            .append(cachedMethodIEntryExpression("java/lang/String", "valueOf", valueOfDesc, true))
            .append(", NULL, __valueOfArgs); if (!neko_exception_check(env)) { ")
            .append(temp).append(" = (jstring)__valueOfResult.l; } } ");
        return temp;
    }

    private String stringValueOfDesc(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN -> "(Z)Ljava/lang/String;";
            case Type.CHAR -> "(C)Ljava/lang/String;";
            case Type.BYTE, Type.SHORT, Type.INT -> "(I)Ljava/lang/String;";
            case Type.LONG -> "(J)Ljava/lang/String;";
            case Type.FLOAT -> "(F)Ljava/lang/String;";
            case Type.DOUBLE -> "(D)Ljava/lang/String;";
            default -> "(Ljava/lang/Object;)Ljava/lang/String;";
        };
    }

    private void appendDirectStringConcat(StringBuilder sb, String rhsExpr) {
        sb.append("{ jstring __lhs = __acc == NULL ? neko_string_null(env) : __acc; ");
        sb.append("jstring __rhs = (jstring)(").append(rhsExpr).append(" == NULL ? neko_string_null(env) : ").append(rhsExpr).append("); ");
        sb.append("jfieldID __stringValue = ").append(cachedFieldExpression("java/lang/String", "value", "[B", false)).append("; ");
        sb.append("jfieldID __stringCoder = ").append(cachedFieldExpression("java/lang/String", "coder", "B", false)).append("; ");
        sb.append("(void)__stringValue; (void)__stringCoder; ");
        sb.append("__acc = (jstring)neko_require_fast_string_concat(thread, env, __lhs, __rhs, ")
            .append(codeGenerator.fieldOffsetSlotName("java/lang/String", "value", "[B", false)).append(", ")
            .append(codeGenerator.fieldOffsetSlotName("java/lang/String", "coder", "B", false)).append("); } ");
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
            return "neko_box_boolean(thread, env, " + (value ? "JNI_TRUE" : "JNI_FALSE") + ")";
        }
        if (arg instanceof Byte value) {
            return "neko_box_byte(thread, env, (jbyte)" + value + ")";
        }
        if (arg instanceof Character value) {
            return "neko_box_char(thread, env, (jchar)" + (int) value.charValue() + ")";
        }
        if (arg instanceof Short value) {
            return "neko_box_short(thread, env, (jshort)" + value + ")";
        }
        if (arg instanceof Integer value) {
            return "neko_box_int(thread, env, " + value + ")";
        }
        if (arg instanceof Long value) {
            return "neko_box_long(thread, env, " + value + "LL)";
        }
        if (arg instanceof Float value) {
            return "neko_box_float(thread, env, " + floatLiteral(value) + ")";
        }
        if (arg instanceof Double value) {
            return "neko_box_double(thread, env, " + doubleLiteral(value) + ")";
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
            throw new IllegalStateException("Unsupported bootstrap MethodHandle argument after invokedynamic lowering: "
                + handle.getOwner() + "." + handle.getName() + handle.getDesc());
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
            case Type.BOOLEAN -> "neko_box_boolean(thread, env, " + valueExpr + ")";
            case Type.BYTE -> "neko_box_byte(thread, env, " + valueExpr + ")";
            case Type.CHAR -> "neko_box_char(thread, env, " + valueExpr + ")";
            case Type.SHORT -> "neko_box_short(thread, env, " + valueExpr + ")";
            case Type.INT -> "neko_box_int(thread, env, " + valueExpr + ")";
            case Type.FLOAT -> "neko_box_float(thread, env, " + valueExpr + ")";
            case Type.LONG -> "neko_box_long(thread, env, " + valueExpr + ")";
            case Type.DOUBLE -> "neko_box_double(thread, env, " + valueExpr + ")";
            default -> valueExpr;
        };
    }

    private String unboxReturn(Type ret, String objExpr) {
        return switch (ret.getSort()) {
            case Type.VOID -> "";
            case Type.BOOLEAN -> unboxReturnWithNullCheck("PUSH_I(neko_unbox_boolean(thread, env, __unboxObj)); ", objExpr);
            case Type.BYTE -> unboxReturnWithNullCheck("PUSH_I((jint)neko_unbox_byte(thread, env, __unboxObj)); ", objExpr);
            case Type.CHAR -> unboxReturnWithNullCheck("PUSH_I((jint)neko_unbox_char(thread, env, __unboxObj)); ", objExpr);
            case Type.SHORT -> unboxReturnWithNullCheck("PUSH_I((jint)neko_unbox_short(thread, env, __unboxObj)); ", objExpr);
            case Type.INT -> unboxReturnWithNullCheck("PUSH_I(neko_unbox_int(thread, env, __unboxObj)); ", objExpr);
            case Type.FLOAT -> unboxReturnWithNullCheck("PUSH_F(neko_unbox_float(thread, env, __unboxObj)); ", objExpr);
            case Type.LONG -> unboxReturnWithNullCheck("PUSH_L(neko_unbox_long(thread, env, __unboxObj)); ", objExpr);
            case Type.DOUBLE -> unboxReturnWithNullCheck("PUSH_D(neko_unbox_double(thread, env, __unboxObj)); ", objExpr);
            default -> "PUSH_O(" + objExpr + "); ";
        };
    }

    private String unboxReturnWithNullCheck(String successCode, String objExpr) {
        return "{ jobject __unboxObj = " + objExpr + "; if (__unboxObj == NULL) { "
            + raiseImplicitException("java/lang/NullPointerException")
            + "; } else { " + successCode + "} } ";
    }

    private void appendStringBuilderLiteral(StringBuilder sb, String literal) {
        sb.append("{ jstring __lit = ").append(cachedStringExpression(literal)).append("; ");
        sb.append("jvalue __appendArgs[1]; __appendArgs[0].l = __lit; ");
        String appendDesc = "(Ljava/lang/String;)Ljava/lang/StringBuilder;";
        String appendDispatcher = codeGenerator.registerInvokeShape(false, 'L', collapseArgKinds(Type.getArgumentTypes(appendDesc)));
        sb.append("if (__sb != NULL) { ").append(appendDispatcher)
            .append("(thread, env, ")
            .append(cachedMethodPtrExpression("java/lang/StringBuilder", "append", appendDesc, false)).append(", ")
            .append(cachedMethodIEntryExpression("java/lang/StringBuilder", "append", appendDesc, false))
            .append(", __sb, __appendArgs); } } ");
    }

    private void appendStringBuilderValue(StringBuilder sb, String appendSig, String accessor, String valueExpr) {
        sb.append("{ ");
        Type appendArg = Type.getArgumentTypes(appendSig)[0];
        sb.append("jvalue __appendArgs[1]; ").append(jvalueStore(appendArg, "__appendArgs[0]", valueExpr)).append(' ');
        String appendDispatcher = codeGenerator.registerInvokeShape(false, 'L', collapseArgKinds(Type.getArgumentTypes(appendSig)));
        sb.append("if (__sb != NULL) { ").append(appendDispatcher)
            .append("(thread, env, ")
            .append(cachedMethodPtrExpression("java/lang/StringBuilder", "append", appendSig, false)).append(", ")
            .append(cachedMethodIEntryExpression("java/lang/StringBuilder", "append", appendSig, false))
            .append(", __sb, __appendArgs); } } ");
    }

    private String cachedClassExpression(String owner) {
        codeGenerator.registerOwnerClassReference(currentOwnerInternalName, owner);
        return "neko_bound_class(thread, env, " + classCacheVar(owner) + ", \"" + cStringLiteral(owner) + "\")";
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

    private String staticClassInitExpression(String owner) {
        return "neko_ensure_class_initialized_once(env, cls, \"" + cStringLiteral(owner) + "\", &"
            + codeGenerator.classInitializedSlotName(owner) + ");";
    }

    private String methodCacheVar(String owner, String name, String desc, boolean isStatic) {
        return codeGenerator.methodSlotName(owner, name, desc, isStatic);
    }

    private String cachedMethodPtrExpression(String owner, String name, String desc, boolean isStatic) {
        codeGenerator.registerOwnerMethodReference(currentOwnerInternalName, owner, name, desc, isStatic);
        return codeGenerator.methodPtrSlotName(owner, name, desc, isStatic);
    }

    private String cachedMethodIEntryExpression(String owner, String name, String desc, boolean isStatic) {
        codeGenerator.registerOwnerMethodReference(currentOwnerInternalName, owner, name, desc, isStatic);
        return "neko_bound_method_i_entry(" + codeGenerator.methodPtrSlotName(owner, name, desc, isStatic)
            + ", &" + codeGenerator.methodIEntrySlotName(owner, name, desc, isStatic)
            + ", \"" + cStringLiteral(owner) + "\", \"" + cStringLiteral(name) + "\", \""
            + cStringLiteral(desc) + "\")";
    }

    private String raiseImplicitException(String owner) {
        String ctorDesc = "(Ljava/lang/String;)V";
        String dispatcher = codeGenerator.registerInvokeShape(false, 'V', new char[] { 'L' });
        return "neko_raise_implicit_exception(thread, env, "
            + cachedClassExpression(owner) + ", "
            + cachedMethodPtrExpression(owner, "<init>", ctorDesc, false) + ", "
            + cachedMethodIEntryExpression(owner, "<init>", ctorDesc, false) + ", "
            + dispatcher + ", \"" + cStringLiteral(owner) + "\")";
    }

    private String fieldCacheVar(String owner, String name, String desc, boolean isStatic) {
        return codeGenerator.fieldSlotName(owner, name, desc, isStatic);
    }

    private String cachedStringExpression(String value) {
        String cacheVar = stringCacheVar(value);
        codeGenerator.registerOwnerStringReference(currentOwnerInternalName, value, cacheVar);
        return "neko_bound_string(thread, env, &" + cacheVar + ", &" + cacheVar + "_oop, \"" + cStringLiteral(value) + "\")";
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

    private String shadowPop() {
        return currentMethodShadowEnabled ? "neko_shadow_pop(); " : "";
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
