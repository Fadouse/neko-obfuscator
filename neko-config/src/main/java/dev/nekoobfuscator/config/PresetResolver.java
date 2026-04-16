package dev.nekoobfuscator.config;

import dev.nekoobfuscator.api.config.*;

import java.util.*;

/**
 * Resolves preset defaults and fills missing transform configs.
 */
public final class PresetResolver {
    private PresetResolver() {}

    public static void applyDefaults(ObfuscationConfig config) {
        Map<String, TransformConfig> defaults = getPresetDefaults(config.preset());
        Map<String, TransformConfig> existing = config.transforms();

        // Fill in defaults for any transform not explicitly configured
        for (var entry : defaults.entrySet()) {
            existing.putIfAbsent(entry.getKey(), entry.getValue());
        }
    }

    public static Map<String, TransformConfig> getPresetDefaults(TransformPreset preset) {
        Map<String, TransformConfig> m = new LinkedHashMap<>();
        switch (preset) {
            case PARANOID -> {
                m.put("advancedJvm", new TransformConfig(true, 1.0));
                m.put("stackObfuscation", new TransformConfig(true, 1.0));
                m.put("outliner", new TransformConfig(true, 0.8));
                // fall through to AGGRESSIVE
                m.put("exceptionObfuscation", new TransformConfig(true, 0.8));
                m.put("exceptionReturn", new TransformConfig(true, 0.8));
                // fall through to STANDARD
                m.put("controlFlowFlattening", new TransformConfig(true, 1.0));
                m.put("opaquePredicates", new TransformConfig(true, 1.0));
                m.put("invokeDynamic", new TransformConfig(true, 1.0));
                // fall through to LIGHT
                m.put("stringEncryption", new TransformConfig(true, 1.0));
                m.put("numberEncryption", new TransformConfig(true, 1.0));
            }
            case AGGRESSIVE -> {
                m.put("exceptionObfuscation", new TransformConfig(true, 0.6));
                m.put("exceptionReturn", new TransformConfig(true, 0.6));
                m.put("outliner", new TransformConfig(true, 0.5));
                m.put("stackObfuscation", new TransformConfig(true, 0.5));
                m.put("controlFlowFlattening", new TransformConfig(true, 0.8));
                m.put("opaquePredicates", new TransformConfig(true, 0.8));
                m.put("invokeDynamic", new TransformConfig(true, 0.8));
                m.put("stringEncryption", new TransformConfig(true, 1.0));
                m.put("numberEncryption", new TransformConfig(true, 1.0));
            }
            case STANDARD -> {
                m.put("controlFlowFlattening", new TransformConfig(true, 0.6));
                m.put("opaquePredicates", new TransformConfig(true, 0.6));
                m.put("invokeDynamic", new TransformConfig(true, 0.6));
                m.put("stringEncryption", new TransformConfig(true, 1.0));
                m.put("numberEncryption", new TransformConfig(true, 1.0));
            }
            case LIGHT -> {
                m.put("stringEncryption", new TransformConfig(true, 1.0));
                m.put("numberEncryption", new TransformConfig(true, 1.0));
            }
        }
        return m;
    }
}
