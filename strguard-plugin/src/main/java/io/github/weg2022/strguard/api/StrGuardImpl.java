package io.github.weg2022.strguard.api;

import java.nio.charset.StandardCharsets;

public class StrGuardImpl implements IStrGuard{
    @Override
    public byte[] encode(String raw, byte[] key) {
        return xor(raw.getBytes(StandardCharsets.UTF_8), key);
    }

    @Override
    public String decode(byte[] data, byte[] key) {
        return new String(xor(data, key), StandardCharsets.UTF_8);
    }

    @Override
    public boolean apply(String raw) {
        return true;
    }

    private static byte[] xor(byte[] data, byte[] key) {
        int len = data.length;
        int lenKey = key.length;
        int i = 0;
        int j = 0;
        while (i < len) {
            if (j >= lenKey) {
                j = 0;
            }
            data[i] = (byte) (data[i] ^ key[j]);
            i++;
            j++;
        }
        return data;
    }
}
