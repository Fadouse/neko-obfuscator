package dev.nekoobfuscator.core.pipeline;

import dev.nekoobfuscator.api.transform.TransformPass;
import dev.nekoobfuscator.api.transform.TransformPhase;

import java.util.*;

/**
 * Schedules transform passes in correct execution order.
 * Uses topological sort based on pass dependencies and phase ordering.
 */
public final class PassScheduler {

    /**
     * Sort passes respecting phase ordering and inter-pass dependencies.
     */
    public List<TransformPass> schedule(Collection<TransformPass> passes) {
        // Group by phase
        Map<TransformPhase, List<TransformPass>> byPhase = new LinkedHashMap<>();
        for (TransformPhase phase : TransformPhase.values()) {
            byPhase.put(phase, new ArrayList<>());
        }
        for (TransformPass pass : passes) {
            byPhase.get(pass.phase()).add(pass);
        }

        // Within each phase, topological sort by dependencies
        List<TransformPass> result = new ArrayList<>();
        for (TransformPhase phase : TransformPhase.values()) {
            result.addAll(topologicalSort(byPhase.get(phase)));
        }
        return result;
    }

    private List<TransformPass> topologicalSort(List<TransformPass> passes) {
        if (passes.size() <= 1) return new ArrayList<>(passes);

        Map<String, TransformPass> byId = new LinkedHashMap<>();
        Map<String, Set<String>> deps = new HashMap<>();
        for (TransformPass p : passes) {
            byId.put(p.id(), p);
            deps.put(p.id(), new HashSet<>(p.dependsOn()));
        }

        List<TransformPass> sorted = new ArrayList<>();
        Set<String> resolved = new HashSet<>();

        while (sorted.size() < passes.size()) {
            boolean progress = false;
            for (var entry : byId.entrySet()) {
                if (resolved.contains(entry.getKey())) continue;
                Set<String> unmet = new HashSet<>(deps.getOrDefault(entry.getKey(), Set.of()));
                unmet.removeAll(resolved);
                // Also remove deps that aren't in this phase
                unmet.retainAll(byId.keySet());
                if (unmet.isEmpty()) {
                    sorted.add(entry.getValue());
                    resolved.add(entry.getKey());
                    progress = true;
                }
            }
            if (!progress) {
                // Circular dependency - add remaining in original order
                for (TransformPass p : passes) {
                    if (!resolved.contains(p.id())) {
                        sorted.add(p);
                    }
                }
                break;
            }
        }
        return sorted;
    }
}
