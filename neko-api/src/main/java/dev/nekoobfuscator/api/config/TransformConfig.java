package dev.nekoobfuscator.api.config;

import java.util.Map;

public record TransformConfig(
    boolean enabled,
    double intensity,
    Map<String, Object> options
) {
    public TransformConfig(boolean enabled) {
        this(enabled, 1.0, Map.of());
    }

    public TransformConfig(boolean enabled, double intensity) {
        this(enabled, intensity, Map.of());
    }
}
