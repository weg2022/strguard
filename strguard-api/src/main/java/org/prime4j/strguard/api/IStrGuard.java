package org.prime4j.strguard.api;

public interface IStrGuard {

    byte[] encode(String raw, byte[] key);

    String decode(byte[] data, byte[] key);

    boolean apply(String raw);
}
