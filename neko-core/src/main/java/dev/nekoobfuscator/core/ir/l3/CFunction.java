package dev.nekoobfuscator.core.ir.l3;

import java.util.ArrayList;
import java.util.List;

public final class CFunction {
    private final String name;
    private final CType returnType;
    private final List<CVariable> params;
    private final List<CVariable> locals;
    private final List<CStatement> body;
    private int maxStack;
    private int maxLocals;
    private int traceIndex = -1;
    private String traceSignature;

    public CFunction(String name, CType returnType, List<CVariable> params) {
        this.name = name;
        this.returnType = returnType;
        this.params = params;
        this.locals = new ArrayList<>();
        this.body = new ArrayList<>();
    }

    public String name() { return name; }
    public CType returnType() { return returnType; }
    public List<CVariable> params() { return params; }
    public List<CVariable> locals() { return locals; }
    public List<CStatement> body() { return body; }
    public int maxStack() { return maxStack; }
    public int maxLocals() { return maxLocals; }
    public int traceIndex() { return traceIndex; }
    public String traceSignature() { return traceSignature; }

    public void addLocal(CVariable var) { locals.add(var); }
    public void addStatement(CStatement stmt) { body.add(stmt); }
    public void setMaxStack(int maxStack) { this.maxStack = maxStack; }
    public void setMaxLocals(int maxLocals) { this.maxLocals = maxLocals; }
    public void setTraceIndex(int traceIndex) { this.traceIndex = traceIndex; }
    public void setTraceSignature(String traceSignature) { this.traceSignature = traceSignature; }

    public CVariable addStackVar(CType type, int index) {
        CVariable v = new CVariable("s" + index, type, index);
        locals.add(v);
        return v;
    }
}
