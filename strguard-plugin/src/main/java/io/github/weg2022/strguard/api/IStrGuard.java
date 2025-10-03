package io.github.weg2022.strguard.api;

public interface IStrGuard {

    byte[] encode(String raw, byte[] key);

    String decode(byte[] data, byte[] key);

    boolean apply(String raw);
}
