package dev.nekoobfuscator.native_.translator;

import dev.nekoobfuscator.core.ir.l1.*;
import dev.nekoobfuscator.core.ir.l3.*;
import dev.nekoobfuscator.native_.codegen.CCodeGenerator;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * Translates selected Java methods to C native functions.
 */
public final class NativeTranslator {

    private final OpcodeTranslator opcodeTranslator = new OpcodeTranslator();

    public TranslationResult translate(L1Class clazz, List<L1Method> methods) {
        List<CFunction> functions = new ArrayList<>();
        for (L1Method method : methods) {
            if (!method.hasCode()) continue;
            CFunction fn = translateMethod(clazz, method);
            if (fn != null) functions.add(fn);
        }
        CCodeGenerator gen = new CCodeGenerator();
        String source = gen.generateSource(functions, clazz.name());
        String header = gen.generateHeader(functions);
        return new TranslationResult(source, header, functions.size());
    }

    private CFunction translateMethod(L1Class clazz, L1Method method) {
        String jniName = mangleName(clazz.name(), method.name());
        Type retType = method.returnType();
        CType cRetType = CType.fromJvmType(retType.getSort() == Type.VOID ? 'V' : retType.getDescriptor().charAt(0));

        // Build params: JNIEnv already handled, add jobject/jclass + method params
        List<CVariable> params = new ArrayList<>();
        int paramIdx = 0;
        if (!method.isStatic()) {
            params.add(new CVariable("self", CType.JOBJECT, paramIdx++));
        } else {
            params.add(new CVariable("clazz", CType.JCLASS, paramIdx++));
        }
        for (Type arg : method.argumentTypes()) {
            CType ct = CType.fromJvmType(arg.getDescriptor().charAt(0));
            params.add(new CVariable("p" + paramIdx, ct, paramIdx));
            paramIdx++;
        }

        CFunction fn = new CFunction(jniName, cRetType, params);
        fn.setMaxStack(method.maxStack());

        // Translate instructions
        InsnList insns = method.instructions();
        Map<LabelNode, String> labelMap = new HashMap<>();
        int labelCounter = 0;
        // Pre-scan labels
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode label) {
                labelMap.put(label, "L" + (labelCounter++));
            }
        }
        // Translate
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode label) {
                fn.addStatement(new CStatement.Label(labelMap.get(label)));
            } else if (insn instanceof LineNumberNode || insn instanceof FrameNode) {
                continue;
            } else if (insn instanceof JumpInsnNode jump) {
                String target = labelMap.getOrDefault(jump.label, "L_unknown");
                fn.addStatement(opcodeTranslator.translateJump(jump, target));
            } else if (insn instanceof TableSwitchInsnNode ts) {
                fn.addStatement(new CStatement.RawC("{ jint key = POP_I();"));
                fn.addStatement(new CStatement.RawC("switch(key) {"));
                for (int i = 0; i < ts.labels.size(); i++) {
                    String lbl = labelMap.getOrDefault(ts.labels.get(i), "L_unknown");
                    fn.addStatement(new CStatement.RawC("case " + (ts.min + i) + ": goto " + lbl + ";"));
                }
                fn.addStatement(new CStatement.RawC("default: goto " + labelMap.getOrDefault(ts.dflt, "L_unknown") + ";"));
                fn.addStatement(new CStatement.RawC("}}"));
            } else if (insn instanceof LookupSwitchInsnNode ls) {
                fn.addStatement(new CStatement.RawC("{ jint key = POP_I();"));
                fn.addStatement(new CStatement.RawC("switch(key) {"));
                for (int i = 0; i < ls.keys.size(); i++) {
                    String lbl = labelMap.getOrDefault(ls.labels.get(i), "L_unknown");
                    fn.addStatement(new CStatement.RawC("case " + ls.keys.get(i) + ": goto " + lbl + ";"));
                }
                fn.addStatement(new CStatement.RawC("default: goto " + labelMap.getOrDefault(ls.dflt, "L_unknown") + ";"));
                fn.addStatement(new CStatement.RawC("}}"));
            } else {
                List<CStatement> translated = opcodeTranslator.translate(insn);
                translated.forEach(fn::addStatement);
            }
        }
        return fn;
    }

    private String mangleName(String className, String methodName) {
        return "Java_" + className.replace('/', '_').replace('$', '_') + "_" + methodName.replace('<', '_').replace('>', '_');
    }

    public record TranslationResult(String source, String header, int methodCount) {}
}
