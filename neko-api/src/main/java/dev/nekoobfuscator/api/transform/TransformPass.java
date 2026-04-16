package dev.nekoobfuscator.api.transform;

import java.util.Set;

/**
 * Core interface for all obfuscation transform passes.
 * Each pass operates on a specific IR level and belongs to a pipeline phase.
 */
public interface TransformPass {

    String id();

    String name();

    TransformPhase phase();

    IRLevel requiredLevel();

    void transformClass(TransformContext ctx);

    void transformMethod(TransformContext ctx);

    default Set<String> dependsOn() {
        return Set.of();
    }

    default Set<String> conflictsWith() {
        return Set.of();
    }

    default boolean isApplicable(TransformContext ctx) {
        return true;
    }
}
