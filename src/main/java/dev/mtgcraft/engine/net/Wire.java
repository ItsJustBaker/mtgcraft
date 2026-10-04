package dev.mtgcraft.engine.net;

/** Carries raw protocol bytes to the other end (Minecraft packets in the mod, an in-memory queue in tests). */
@FunctionalInterface
public interface Wire {
    void send(byte[] data);
}
