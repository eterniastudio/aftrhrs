package net.axther.aftrhrs.content;

/**
 * A module block entity on furniture (see {@link ArcanaContent#blockEntity}) that wants a tick on each side. Only the
 * sides registered with a true flag ({@code ArcanaContent.blockEntity(ids, factory, serverTicks, clientTicks)}) get a
 * ticker: a module that adds a tick override must also turn its flag on.
 */
public interface FurnitureTicker {
    default void serverTick() {
    }

    default void clientTick() {
    }
}
