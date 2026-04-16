package dev.nekoobfuscator.core.ir.l3;

import java.util.ArrayList;
import java.util.List;

public final class CBlock {
    private final String label;
    private final List<CStatement> statements;

    public CBlock(String label) {
        this.label = label;
        this.statements = new ArrayList<>();
    }

    public String label() { return label; }
    public List<CStatement> statements() { return statements; }
    public void add(CStatement stmt) { statements.add(stmt); }
}
