package dev.nekoobfuscator.core.pipeline;

import dev.nekoobfuscator.api.transform.TransformPass;
import dev.nekoobfuscator.api.transform.TransformPhase;

import java.util.*;

/**
 * Registry of all available transform passes.
 * Passes are registered by ID and organized by phase.
 */
public final class PassRegistry {
    private final Map<String, TransformPass> passes = new LinkedHashMap<>();

    public void register(TransformPass pass) {
        passes.put(pass.id(), pass);
    }

    public TransformPass get(String id) {
        return passes.get(id);
    }

    public Collection<TransformPass> all() {
        return passes.values();
    }

    public List<TransformPass> getByPhase(TransformPhase phase) {
        List<TransformPass> result = new ArrayList<>();
        for (TransformPass pass : passes.values()) {
            if (pass.phase() == phase) result.add(pass);
        }
        return result;
    }

    public int size() {
        return passes.size();
    }
}
