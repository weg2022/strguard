package io.github.weg2022.strguard.api;


import java.nio.charset.StandardCharsets;

public class HardCodeKeyGenerator implements IkeyGenerator {

    private final byte[] mKey;

    public HardCodeKeyGenerator() {
        this("StrGuard");
    }

    public HardCodeKeyGenerator(String key) {
        this(key.getBytes(StandardCharsets.UTF_8));
    }

    public HardCodeKeyGenerator(byte[] key) {
        mKey = key;
    }

    @Override
    public byte[] generate(String text) {
        return mKey;
    }
    
}
