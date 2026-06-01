package dev.nekoobfuscator.api.config;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

public final class ObfuscationConfig {

    private Path inputJar;
    private Path outputJar;
    private List<Path> classpath = new ArrayList<>();
    private Map<String, TransformConfig> transforms = new LinkedHashMap<>();
    private NativeConfig nativeConfig = new NativeConfig();
    private KeyConfig keyConfig = new KeyConfig();
    private List<ClassRule> rules = new ArrayList<>();

    public Path inputJar() { return inputJar; }
    public void setInputJar(Path inputJar) { this.inputJar = inputJar; }

    public Path outputJar() { return outputJar; }
    public void setOutputJar(Path outputJar) { this.outputJar = outputJar; }

    public List<Path> classpath() { return classpath; }
    public void setClasspath(List<Path> classpath) { this.classpath = classpath; }

    public Map<String, TransformConfig> transforms() { return transforms; }
    public void setTransforms(Map<String, TransformConfig> transforms) { this.transforms = transforms; }

    public NativeConfig nativeConfig() { return nativeConfig; }
    public void setNativeConfig(NativeConfig nativeConfig) { this.nativeConfig = nativeConfig; }

    public KeyConfig keyConfig() { return keyConfig; }
    public void setKeyConfig(KeyConfig keyConfig) { this.keyConfig = keyConfig; }

    public List<ClassRule> rules() { return rules; }
    public void setRules(List<ClassRule> rules) { this.rules = rules; }

    public boolean isTransformEnabled(String transformId) {
        TransformConfig tc = transforms.get(transformId);
        return tc != null && tc.enabled();
    }

    public boolean isTransformEnabledForAnyClass(String transformId) {
        if (isTransformEnabled(transformId)) return true;
        for (ClassRule rule : rules) {
            if (rule.exclude()) continue;
            TransformConfig tc = rule.transforms().get(transformId);
            if (tc != null && tc.enabled()) return true;
        }
        return false;
    }

    public boolean isTransformEnabledForClass(String transformId, String className) {
        TransformConfig tc = transformForClass(transformId, className);
        return tc != null && tc.enabled();
    }

    public TransformConfig transformForClass(String transformId, String className) {
        TransformConfig effective = transforms.get(transformId);
        boolean excluded = false;
        for (ClassRule rule : rules) {
            if (!matches(rule.match(), className)) continue;
            excluded = rule.exclude();
            TransformConfig scoped = rule.transforms().get(transformId);
            if (scoped != null) {
                effective = scoped;
            }
        }
        return excluded ? null : effective;
    }

    public double getTransformIntensity(String transformId) {
        TransformConfig tc = transforms.get(transformId);
        return tc != null ? tc.intensity() : 0.0;
    }

    private boolean matches(String pattern, String className) {
        if (pattern == null || pattern.isBlank() || className == null || className.isBlank()) {
            return false;
        }
        String normalizedPattern = normalizeClassPattern(pattern);
        String normalizedName = normalizeClassName(className);
        return Pattern.matches(toRegex(normalizedPattern), normalizedName);
    }

    private String normalizeClassPattern(String pattern) {
        String text = pattern.trim();
        if (text.endsWith(".class")) {
            text = text.substring(0, text.length() - ".class".length());
        }
        return text.replace('.', '/');
    }

    private String normalizeClassName(String className) {
        String text = className.trim();
        if (text.endsWith(".class")) {
            text = text.substring(0, text.length() - ".class".length());
        }
        return text.replace('.', '/');
    }

    private String toRegex(String glob) {
        StringBuilder out = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char ch = glob.charAt(i);
            if (ch == '*') {
                if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                    out.append(".*");
                    i++;
                } else {
                    out.append("[^/]*");
                }
                continue;
            }
            if (ch == '?') {
                out.append("[^/]");
                continue;
            }
            if ("\\.^$+{}[]()|".indexOf(ch) >= 0) {
                out.append('\\');
            }
            out.append(ch);
        }
        out.append('$');
        return out.toString();
    }

    public static final class NativeConfig {
        private boolean enabled = false;
        private List<String> targets = List.of("LINUX_X64", "WINDOWS_X64");
        private String zigPath = "zig";
        private boolean resourceEncryption = true;
        private String encryptionAlgorithm = "AES_256_GCM";
        private List<String> methods = new ArrayList<>(List.of("**/*"));
        private List<String> excludePatterns = new ArrayList<>();
        private boolean includeAnnotated = true;
        private boolean skipOnError = true;
        private String outputPrefix = "neko_impl_";
        private boolean obfuscateJniSlotDispatch = false;
        private boolean cacheJniIds = false;

        public boolean enabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> targets() { return targets; }
        public void setTargets(List<String> targets) { this.targets = targets; }
        public String zigPath() { return zigPath; }
        public void setZigPath(String zigPath) { this.zigPath = zigPath; }
        public boolean resourceEncryption() { return resourceEncryption; }
        public void setResourceEncryption(boolean v) { this.resourceEncryption = v; }
        public String encryptionAlgorithm() { return encryptionAlgorithm; }
        public void setEncryptionAlgorithm(String a) { this.encryptionAlgorithm = a; }
        public List<String> methods() { return methods; }
        public void setMethods(List<String> methods) { this.methods = methods; }
        public List<String> excludePatterns() { return excludePatterns; }
        public void setExcludePatterns(List<String> excludePatterns) { this.excludePatterns = excludePatterns; }
        public boolean includeAnnotated() { return includeAnnotated; }
        public void setIncludeAnnotated(boolean includeAnnotated) { this.includeAnnotated = includeAnnotated; }
        public boolean skipOnError() { return skipOnError; }
        public void setSkipOnError(boolean skipOnError) { this.skipOnError = skipOnError; }
        public String outputPrefix() { return outputPrefix; }
        public void setOutputPrefix(String outputPrefix) { this.outputPrefix = outputPrefix; }
        public boolean obfuscateJniSlotDispatch() { return obfuscateJniSlotDispatch; }
        public void setObfuscateJniSlotDispatch(boolean obfuscateJniSlotDispatch) { this.obfuscateJniSlotDispatch = obfuscateJniSlotDispatch; }
        public boolean cacheJniIds() { return cacheJniIds; }
        public void setCacheJniIds(boolean cacheJniIds) { this.cacheJniIds = cacheJniIds; }
    }

    public static final class KeyConfig {
        private long masterSeed = 0; // 0 = auto-generate
        private List<String> layers = List.of("CLASS", "METHOD", "INSTRUCTION", "CONTROL_FLOW");
        private String mixingAlgorithm = "SIP_HASH";

        public long masterSeed() { return masterSeed; }
        public void setMasterSeed(long masterSeed) { this.masterSeed = masterSeed; }
        public List<String> layers() { return layers; }
        public void setLayers(List<String> layers) { this.layers = layers; }
        public String mixingAlgorithm() { return mixingAlgorithm; }
        public void setMixingAlgorithm(String a) { this.mixingAlgorithm = a; }
    }
}
