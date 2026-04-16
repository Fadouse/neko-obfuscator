package dev.nekoobfuscator.core.ir.l1;

import org.objectweb.asm.tree.AnnotationNode;
import java.util.*;

public final class L1Annotation {
    private final String descriptor;
    private final Map<String, Object> values;

    public L1Annotation(AnnotationNode node) {
        this.descriptor = node.desc;
        this.values = new LinkedHashMap<>();
        if (node.values != null) {
            for (int i = 0; i < node.values.size(); i += 2) {
                values.put((String) node.values.get(i), node.values.get(i + 1));
            }
        }
    }

    public String descriptor() { return descriptor; }
    public Map<String, Object> values() { return values; }

    @SuppressWarnings("unchecked")
    public <T> T getValue(String key) { return (T) values.get(key); }

    @Override
    public String toString() {
        return "L1Annotation{" + descriptor + "}";
    }
}
