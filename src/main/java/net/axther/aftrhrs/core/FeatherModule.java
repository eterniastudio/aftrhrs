package net.axther.aftrhrs.core;

/** A feature bundled into the mod (a Feather module). Enabled at server start, reloaded by /feather reload, disabled on stop. */
public interface FeatherModule {
    String name();

    void enable();

    default void reload() {
    }

    default void disable() {
    }
}
