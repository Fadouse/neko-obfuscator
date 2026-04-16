package dev.nekoobfuscator.api.transform;

import dev.nekoobfuscator.api.config.ObfuscationConfig;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared context passed to every transform pass.
 * Provides access to configuration, class hierarchy, and cross-pass data.
 * Concrete implementation in neko-core fills in IR access methods.
 */
public class TransformContext {

    private final ObfuscationConfig config;
    private final Map<String, Object> passData = new ConcurrentHashMap<>();

    // Current class/method being processed (set by pipeline before each call)
    private String currentClassName;
    private String currentMethodName;
    private String currentMethodDesc;

    public TransformContext(ObfuscationConfig config) {
        this.config = config;
    }

    public ObfuscationConfig config() {
        return config;
    }

    public String currentClassName() {
        return currentClassName;
    }

    public void setCurrentClass(String name) {
        this.currentClassName = name;
    }

    public String currentMethodName() {
        return currentMethodName;
    }

    public String currentMethodDesc() {
        return currentMethodDesc;
    }

    public void setCurrentMethod(String name, String desc) {
        this.currentMethodName = name;
        this.currentMethodDesc = desc;
    }

    @SuppressWarnings("unchecked")
    public <T> T getPassData(String key) {
        return (T) passData.get(key);
    }

    public void putPassData(String key, Object value) {
        passData.put(key, value);
    }
}
