package dev.nekoobfuscator.api.config;

import java.util.Map;

public record ClassRule(
    String match,
    boolean exclude,
    Map<String, TransformConfig> transforms
) {
    public ClassRule(String match, boolean exclude) {
        this(match, exclude, Map.of());
    }
}
