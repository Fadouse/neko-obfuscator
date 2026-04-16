package dev.nekoobfuscator.api.config;

import java.nio.file.Path;
import java.util.*;

public final class ObfuscationConfig {

    private Path inputJar;
    private Path outputJar;
    private List<Path> classpath = new ArrayList<>();
    private TransformPreset preset = TransformPreset.STANDARD;
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

    public TransformPreset preset() { return preset; }
    public void setPreset(TransformPreset preset) { this.preset = preset; }

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

    public double getTransformIntensity(String transformId) {
        TransformConfig tc = transforms.get(transformId);
        return tc != null ? tc.intensity() : 0.0;
    }

    public static final class NativeConfig {
        private boolean enabled = false;
        private List<String> targets = List.of("LINUX_X64", "WINDOWS_X64");
        private String zigPath = "zig";
        private boolean resourceEncryption = true;
        private String encryptionAlgorithm = "AES_256_GCM";

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
