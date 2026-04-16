package dev.nekoobfuscator.core.ir.l3;

import java.util.List;

public sealed interface CExpression {
    record IntLiteral(long value) implements CExpression {
        @Override public String emit() { return value + ""; }
    }
    record FloatLiteral(double value) implements CExpression {
        @Override public String emit() { return value + ""; }
    }
    record StringLiteral(String value) implements CExpression {
        @Override public String emit() { return "\"" + escapeC(value) + "\""; }
        private static String escapeC(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        }
    }
    record VarRef(CVariable var) implements CExpression {
        @Override public String emit() { return var.name(); }
    }
    record BinaryOp(String op, CExpression left, CExpression right) implements CExpression {
        @Override public String emit() { return "(" + left.emit() + " " + op + " " + right.emit() + ")"; }
    }
    record UnaryOp(String op, CExpression operand) implements CExpression {
        @Override public String emit() { return "(" + op + operand.emit() + ")"; }
    }
    record Cast(CType targetType, CExpression operand) implements CExpression {
        @Override public String emit() { return "((" + targetType.jniName() + ")" + operand.emit() + ")"; }
    }
    record ArrayAccess(CExpression array, CExpression index) implements CExpression {
        @Override public String emit() { return array.emit() + "[" + index.emit() + "]"; }
    }
    record FunctionCall(String function, List<CExpression> args) implements CExpression {
        @Override public String emit() {
            StringBuilder sb = new StringBuilder(function).append("(");
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(args.get(i).emit());
            }
            return sb.append(")").toString();
        }
    }
    record JNICall(String envMethod, List<CExpression> args) implements CExpression {
        @Override public String emit() {
            StringBuilder sb = new StringBuilder("(*env)->").append(envMethod).append("(env");
            for (CExpression arg : args) sb.append(", ").append(arg.emit());
            return sb.append(")").toString();
        }
    }
    record Ternary(CExpression cond, CExpression t, CExpression f) implements CExpression {
        @Override public String emit() { return "(" + cond.emit() + " ? " + t.emit() + " : " + f.emit() + ")"; }
    }
    record Null() implements CExpression {
        @Override public String emit() { return "NULL"; }
    }
    record StackPush(CType type, CExpression value) implements CExpression {
        @Override public String emit() { return "PUSH_" + type.name().substring(1).toUpperCase() + "(" + value.emit() + ")"; }
    }
    record StackPop(CType type) implements CExpression {
        @Override public String emit() { return "POP_" + type.name().substring(1).toUpperCase() + "()"; }
    }

    String emit();
}
