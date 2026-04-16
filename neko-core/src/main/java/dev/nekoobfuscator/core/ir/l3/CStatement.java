package dev.nekoobfuscator.core.ir.l3;

import java.util.List;
import java.util.Map;

public sealed interface CStatement {
    record Assign(CVariable target, CExpression value) implements CStatement {}
    record ExprStmt(CExpression expr) implements CStatement {}
    record If(CExpression condition, List<CStatement> thenBlock, List<CStatement> elseBlock) implements CStatement {}
    record Switch(CExpression selector, Map<Integer, List<CStatement>> cases, List<CStatement> defaultCase) implements CStatement {}
    record Goto(String label) implements CStatement {}
    record Label(String name) implements CStatement {}
    record Return(CExpression value) implements CStatement {}
    record ReturnVoid() implements CStatement {}
    record Comment(String text) implements CStatement {}
    record VarDecl(CVariable var, CExpression init) implements CStatement {}
    record SetJmp(CVariable jmpBuf, String handlerLabel) implements CStatement {}
    record LongJmp(CVariable jmpBuf, CExpression value) implements CStatement {}
    record CheckException(String handlerLabel) implements CStatement {}
    record RawC(String code) implements CStatement {}
}
