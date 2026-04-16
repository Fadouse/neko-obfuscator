package dev.nekoobfuscator.config;

import dev.nekoobfuscator.api.config.ObfuscationConfig;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates an ObfuscationConfig for correctness.
 */
public final class ConfigValidator {
    private ConfigValidator() {}

    public static List<String> validate(ObfuscationConfig config) {
        List<String> errors = new ArrayList<>();

        if (config.inputJar() == null) {
            errors.add("Input JAR path is required");
        } else if (!Files.exists(config.inputJar())) {
            errors.add("Input JAR does not exist: " + config.inputJar());
        }

        if (config.outputJar() == null) {
            errors.add("Output JAR path is required");
        }

        for (var cp : config.classpath()) {
            if (!Files.exists(cp)) {
                errors.add("Classpath entry does not exist: " + cp);
            }
        }

        for (var entry : config.transforms().entrySet()) {
            double intensity = entry.getValue().intensity();
            if (intensity < 0.0 || intensity > 1.0) {
                errors.add("Transform '" + entry.getKey() + "' intensity must be between 0.0 and 1.0, got: " + intensity);
            }
        }

        if (config.nativeConfig().enabled()) {
            if (config.nativeConfig().targets().isEmpty()) {
                errors.add("Native obfuscation enabled but no targets specified");
            }
        }

        return errors;
    }
}
