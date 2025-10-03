package io.github.weg2022.strguard.api;


import java.security.SecureRandom;

public class RandomKeyGenerator implements IkeyGenerator {

    private static final int DEFAULT_LENGTH = 4;

    private final SecureRandom mSecureRandom;
    private final int mLength;

    public RandomKeyGenerator() {
        this(DEFAULT_LENGTH);
    }

    public RandomKeyGenerator(int length) {
        mLength = length;
        mSecureRandom = new SecureRandom();
    }

    @Override
    public byte[] generate(String text) {
        byte[] key = new byte[mLength];
        mSecureRandom.nextBytes(key);
        return key;
    }

}
