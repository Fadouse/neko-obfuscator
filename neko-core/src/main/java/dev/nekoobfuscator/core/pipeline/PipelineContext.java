package dev.nekoobfuscator.core.pipeline;

import dev.nekoobfuscator.api.config.ObfuscationConfig;
import dev.nekoobfuscator.api.transform.TransformContext;
import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.ir.l1.L1Method;
import dev.nekoobfuscator.core.ir.l2.ControlFlowGraph;
import dev.nekoobfuscator.core.ir.l2.SSAForm;
import dev.nekoobfuscator.core.ir.lift.L1ToL2Lifter;
import dev.nekoobfuscator.core.jar.ClassHierarchy;
import dev.nekoobfuscator.core.util.RandomUtil;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Extended pipeline context providing IR access to transform passes.
 * Lazily lifts methods to L2 on demand, caches results.
 */
public final class PipelineContext extends TransformContext {
    private final ClassHierarchy hierarchy;
    private final Map<String, L1Class> classMap;
    private final L1ToL2Lifter lifter = new L1ToL2Lifter();
    private final Map<String, ControlFlowGraph> cfgCache = new ConcurrentHashMap<>();
    private final Map<String, SSAForm> ssaCache = new ConcurrentHashMap<>();
    private final RandomUtil random;
    private final long masterSeed;

    // Current class/method being processed
    private L1Class currentL1Class;
    private L1Method currentL1Method;

    public PipelineContext(ObfuscationConfig config, ClassHierarchy hierarchy,
                           Map<String, L1Class> classMap) {
        super(config);
        this.hierarchy = hierarchy;
        this.classMap = classMap;
        long seed = config.keyConfig().masterSeed();
        this.masterSeed = seed != 0 ? seed : RandomUtil.secureLong();
        this.random = new RandomUtil(masterSeed);
    }

    public ClassHierarchy hierarchy() { return hierarchy; }
    public Map<String, L1Class> classMap() { return classMap; }
    public RandomUtil random() { return random; }
    public long masterSeed() { return masterSeed; }

    public L1Class currentL1Class() { return currentL1Class; }
    public void setCurrentL1Class(L1Class clazz) {
        this.currentL1Class = clazz;
        setCurrentClass(clazz.name());
    }

    public L1Method currentL1Method() { return currentL1Method; }
    public void setCurrentL1Method(L1Method method) {
        this.currentL1Method = method;
        if (method == null) {
            setCurrentMethod(null, null);
            return;
        }
        setCurrentMethod(method.name(), method.descriptor());
    }

    /**
     * Get CFG for a method (lifts from L1 if not cached).
     */
    public ControlFlowGraph getCFG(L1Method method) {
        String key = method.owner().name() + "." + method.name() + method.descriptor();
        return cfgCache.computeIfAbsent(key, k -> lifter.buildCFG(method));
    }

    /**
     * Get SSA form for a method (lifts from L1 if not cached).
     */
    public SSAForm getSSA(L1Method method) {
        String key = method.owner().name() + "." + method.name() + method.descriptor();
        return ssaCache.computeIfAbsent(key, k -> lifter.lift(method));
    }

    /**
     * Invalidate cached IR for a method (call after mutation).
     */
    public void invalidate(L1Method method) {
        String key = method.owner().name() + "." + method.name() + method.descriptor();
        cfgCache.remove(key);
        ssaCache.remove(key);
    }

    /**
     * Invalidate all cached IR.
     */
    public void invalidateAll() {
        cfgCache.clear();
        ssaCache.clear();
    }
}
