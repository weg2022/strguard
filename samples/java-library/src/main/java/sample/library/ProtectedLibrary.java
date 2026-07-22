package sample.library;

public final class ProtectedLibrary {
    public static final String LIBRARY_CONSTANT = "java-library-static-final-value";

    public String value() {
        return "java-library-protected-value";
    }

    public String collectionValue(String key) {
        return java.util.Map.of(
                "java-library-first-key", "java-library-first-value",
                "java-library-second-key", "java-library-line-one\njava-library-line-two"
        ).getOrDefault(key, "java-library-default-value");
    }

    public String unicodeValue() {
        return "java-library-unicode-\u4F60\u597D-\uD83D\uDE80-\0-end";
    }

    public String lambdaValue() {
        return ((java.util.function.Supplier<String>) () -> "java-library-lambda-value").get();
    }
}
