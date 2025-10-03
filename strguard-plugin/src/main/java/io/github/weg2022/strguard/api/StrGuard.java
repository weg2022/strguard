package io.github.weg2022.strguard.api;

public final class StrGuard {
  private static final IStrGuard IMPL = new StrGuardImpl();

  public static String decode(byte[] value, byte[] key) {
    return IMPL.decode(value, key);
  }

}