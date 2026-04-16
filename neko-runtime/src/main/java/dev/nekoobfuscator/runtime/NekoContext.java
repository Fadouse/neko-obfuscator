package dev.nekoobfuscator.runtime;

/**
 * Runtime context state tracking for control-flow-dependent key derivation.
 * Tracks the current state of the control flow dispatcher to feed into key computation.
 */
public final class NekoContext {
    private static final ThreadLocal<NekoContext> INSTANCE = ThreadLocal.withInitial(NekoContext::new);

    private long controlFlowState;
    private long currentFlowKey;

    private NekoContext() {
        this.controlFlowState = 0;
        this.currentFlowKey = 0;
    }

    public static NekoContext get() { return INSTANCE.get(); }

    public long controlFlowState() { return controlFlowState; }

    public long currentFlowKey() { return currentFlowKey; }

    public static long flowKey() { return get().currentFlowKey; }

    public static void setCurrentFlowKey(long flowKey) {
        NekoContext ctx = get();
        ctx.currentFlowKey = flowKey;
        ctx.controlFlowState = flowKey;
    }

    public void updateState(int newState) {
        controlFlowState = NekoKeyDerivation.mix(controlFlowState, newState);
        currentFlowKey = controlFlowState;
    }

    public void reset() {
        controlFlowState = 0;
        currentFlowKey = 0;
    }
}
