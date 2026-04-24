package dev.nekoobfuscator.native_.codegen.emit;

import dev.nekoobfuscator.core.ir.l3.CFunction;
import dev.nekoobfuscator.core.ir.l3.CStatement;
import dev.nekoobfuscator.core.ir.l3.CType;
import dev.nekoobfuscator.core.ir.l3.CVariable;

public final class ImplBodyEmitter {
    public String renderFunction(CFunction fn) {
        StringBuilder sb = new StringBuilder();
        sb.append("__attribute__((used, visibility(\"default\"))) ").append(rawFunctionReturnType(fn.returnType())).append(' ').append(fn.name()).append('(');
        if (fn.params().isEmpty()) {
            sb.append("void");
        } else {
            for (int i = 0; i < fn.params().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(renderParam(fn.params().get(i)));
            }
        }
        sb.append(") {\n");
        sb.append("    JNIEnv *env = neko_current_env();\n");
        sb.append("    if (!neko_loader_ready()) {\n");
        sb.append("        void *error = neko_new_exception_oop(env, \"java/lang/LinkageError\", \"please check your native library load correctly\");\n");
        sb.append("        if (error != NULL) neko_throw(env, (jthrowable)error);\n");
        sb.append("        return");
        if (fn.returnType() != CType.VOID) {
            sb.append(" ").append(defaultReturnValue(fn.returnType()));
        }
        sb.append(";\n");
        sb.append("    }\n");
        sb.append("    void *thread = neko_get_current_thread();\n");
        if (fn.traceIndex() >= 0 && fn.traceSignature() != null) {
            sb.append("    NEKO_TRACE(2, \"[nk] e idx=%d sig=\\\"%s\\\"\\n\", ")
                .append(fn.traceIndex())
                .append(", \"")
                .append(cStringLiteral(fn.traceSignature()))
                .append("\");\n");
        }
        if (requiresLocalCapacity(fn)) {
            sb.append("    if (env != NULL) neko_ensure_local_capacity(env, 8192);\n");
        }
        sb.append("    neko_slot stack[").append(fn.maxStack() + 16).append("];\n");
        sb.append("    int sp = 0;\n");
        sb.append("    neko_slot locals[").append(fn.maxLocals() + 8).append("];\n");
        sb.append("    memset(locals, 0, sizeof(locals));\n");
        for (CStatement statement : fn.body()) {
            sb.append(renderStatement(statement));
        }
        sb.append("}\n");
        return sb.toString();
    }

    private String renderStatement(CStatement statement) {
        if (statement instanceof CStatement.RawC raw) {
            return "    " + raw.code() + "\n";
        }
        if (statement instanceof CStatement.Label label) {
            return label.name() + ": ;\n";
        }
        if (statement instanceof CStatement.Goto go) {
            return "    goto " + go.label() + ";\n";
        }
        if (statement instanceof CStatement.ReturnVoid) {
            return "    return;\n";
        }
        if (statement instanceof CStatement.Return ret) {
            return "    return " + ret.value() + ";\n";
        }
        if (statement instanceof CStatement.Comment comment) {
            return "    /* " + comment.text() + " */\n";
        }
        throw new IllegalStateException("Unsupported C statement in generator: " + statement.getClass().getSimpleName());
    }

    private boolean requiresLocalCapacity(CFunction fn) {
        for (CStatement statement : fn.body()) {
            if (statement instanceof CStatement.RawC raw) {
                String code = raw.code();
                if (code.contains("neko_new_") || code.contains("neko_call_") || code.contains("neko_get_object_array_element")
                    || code.contains("neko_set_object_array_element") || code.contains("neko_get_object_class")
                    || code.contains("NEKO_ENSURE_STRING") || code.contains("neko_string_concat")
                    || code.contains("neko_class_for_descriptor") || code.contains("neko_resolve_indy")
                    || code.contains("neko_resolve_constant_dynamic")) {
                    return true;
                }
            }
        }
        return false;
    }

    private String rawFunctionReturnType(CType type) {
        return switch (type) {
            case VOID -> "void";
            case JLONG -> "int64_t";
            case JFLOAT -> "float";
            case JDOUBLE -> "double";
            case JOBJECT, JCLASS, JSTRING, JARRAY -> CEmissionContext.RAW_OOP_ABI_C_TYPE;
            default -> "int32_t";
        };
    }

    private String defaultReturnValue(CType type) {
        return switch (type) {
            case VOID -> "";
            case JLONG -> "0LL";
            case JFLOAT -> "0.0f";
            case JDOUBLE -> "0.0";
            case JOBJECT, JCLASS, JSTRING, JARRAY -> "NULL";
            default -> "0";
        };
    }

    private String rawFunctionParamType(CVariable variable) {
        return rawFunctionReturnType(variable.type());
    }

    private String renderParam(CVariable variable) {
        return rawFunctionParamType(variable) + " " + variable.name();
    }

    private String cStringLiteral(String s) {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
