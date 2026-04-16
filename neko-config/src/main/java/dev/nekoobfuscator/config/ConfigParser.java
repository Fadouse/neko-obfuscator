package dev.nekoobfuscator.config;

import dev.nekoobfuscator.api.config.*;
import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Parses YAML configuration files into ObfuscationConfig.
 */
public final class ConfigParser {

    public ObfuscationConfig parse(Path yamlFile) throws IOException {
        try (InputStream is = Files.newInputStream(yamlFile)) {
            return parse(is, yamlFile.getParent());
        }
    }

    public ObfuscationConfig parse(InputStream yaml, Path basePath) {
        Yaml yamlParser = new Yaml();
        Map<String, Object> root = yamlParser.load(yaml);
        if (root == null) root = Map.of();
        return buildConfig(root, basePath);
    }

    @SuppressWarnings("unchecked")
    private ObfuscationConfig buildConfig(Map<String, Object> root, Path basePath) {
        ObfuscationConfig config = new ObfuscationConfig();

        // Input/Output
        if (root.containsKey("input")) {
            config.setInputJar(resolvePath(basePath, (String) root.get("input")));
        }
        if (root.containsKey("output")) {
            config.setOutputJar(resolvePath(basePath, (String) root.get("output")));
        }

        // Classpath
        if (root.containsKey("classpath")) {
            List<String> cp = (List<String>) root.get("classpath");
            config.setClasspath(cp.stream().map(s -> resolvePath(basePath, s)).toList());
        }

        // Preset
        if (root.containsKey("preset")) {
            config.setPreset(TransformPreset.valueOf(((String) root.get("preset")).toUpperCase()));
        }

        // Transforms
        if (root.containsKey("transforms")) {
            Map<String, Object> transforms = (Map<String, Object>) root.get("transforms");
            Map<String, TransformConfig> tcs = new LinkedHashMap<>();
            for (var entry : transforms.entrySet()) {
                tcs.put(entry.getKey(), parseTransformConfig(entry.getValue()));
            }
            config.setTransforms(tcs);
        }

        // Native config
        if (root.containsKey("native")) {
            Map<String, Object> nativeMap = (Map<String, Object>) root.get("native");
            ObfuscationConfig.NativeConfig nc = new ObfuscationConfig.NativeConfig();
            if (nativeMap.containsKey("enabled")) nc.setEnabled((Boolean) nativeMap.get("enabled"));
            if (nativeMap.containsKey("targets")) nc.setTargets((List<String>) nativeMap.get("targets"));
            if (nativeMap.containsKey("zigPath")) nc.setZigPath((String) nativeMap.get("zigPath"));
            if (nativeMap.containsKey("resources")) {
                Map<String, Object> res = (Map<String, Object>) nativeMap.get("resources");
                if (res.containsKey("encrypt")) nc.setResourceEncryption((Boolean) res.get("encrypt"));
                if (res.containsKey("algorithm")) nc.setEncryptionAlgorithm((String) res.get("algorithm"));
            }
            config.setNativeConfig(nc);
        }

        // Key config
        if (root.containsKey("keys")) {
            Map<String, Object> keysMap = (Map<String, Object>) root.get("keys");
            ObfuscationConfig.KeyConfig kc = new ObfuscationConfig.KeyConfig();
            if (keysMap.containsKey("masterSeed")) {
                Object seed = keysMap.get("masterSeed");
                if (seed instanceof Number n) {
                    kc.setMasterSeed(n.longValue());
                }
                // "auto" or null -> stays 0 (auto-generate)
            }
            if (keysMap.containsKey("layers")) kc.setLayers((List<String>) keysMap.get("layers"));
            if (keysMap.containsKey("mixing")) kc.setMixingAlgorithm((String) keysMap.get("mixing"));
            config.setKeyConfig(kc);
        }

        // Rules
        if (root.containsKey("rules")) {
            List<Map<String, Object>> rulesList = (List<Map<String, Object>>) root.get("rules");
            List<ClassRule> rules = new ArrayList<>();
            for (var ruleMap : rulesList) {
                String match = (String) ruleMap.get("match");
                boolean exclude = ruleMap.containsKey("exclude") && (Boolean) ruleMap.get("exclude");
                Map<String, TransformConfig> ruleTcs = Map.of();
                if (ruleMap.containsKey("transforms")) {
                    Map<String, Object> rt = (Map<String, Object>) ruleMap.get("transforms");
                    ruleTcs = new LinkedHashMap<>();
                    for (var e : rt.entrySet()) {
                        ruleTcs.put(e.getKey(), parseTransformConfig(e.getValue()));
                    }
                }
                rules.add(new ClassRule(match, exclude, ruleTcs));
            }
            config.setRules(rules);
        }

        // Apply preset defaults for missing transforms
        PresetResolver.applyDefaults(config);

        return config;
    }

    @SuppressWarnings("unchecked")
    private TransformConfig parseTransformConfig(Object value) {
        if (value instanceof Boolean b) {
            return new TransformConfig(b);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> m = (Map<String, Object>) map;
            boolean enabled = m.containsKey("enabled") ? (Boolean) m.get("enabled") : true;
            double intensity = m.containsKey("intensity") ? ((Number) m.get("intensity")).doubleValue() : 1.0;
            Map<String, Object> options = new LinkedHashMap<>(m);
            options.remove("enabled");
            options.remove("intensity");
            return new TransformConfig(enabled, intensity, options);
        }
        return new TransformConfig(true);
    }

    private Path resolvePath(Path basePath, String pathStr) {
        Path p = Path.of(pathStr);
        if (p.isAbsolute()) return p;
        return basePath != null ? basePath.resolve(p) : p;
    }
}
