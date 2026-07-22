package sample.java;

public final class ProtectedMessage {
    public static final String CONSTANT = "java-plugin-static-final-value";

    private ProtectedMessage() {
    }

    public static String value() {
        return "java-plugin-protected-value";
    }

    public static String arrayValue(int index) {
        return new String[]{
                "java-plugin-array-zero",
                "java-plugin-line-one\njava-plugin-line-two",
                "java-plugin-unicode-\u4F60\u597D-\uD83D\uDE80"
        }[index];
    }

    public static String switchValue(String key) {
        switch (key) {
            case "java-plugin-alpha-key":
                return "java-plugin-switch-alpha";
            case "java-plugin-beta-key":
                return "java-plugin-switch-beta\twith-tab";
            default:
                return "java-plugin-switch-default";
        }
    }

    public static String lambdaValue() {
        java.util.function.Supplier<String> supplier = () -> "java-plugin-lambda-value";
        return supplier.get();
    }

    public static String specialUtf16() {
        return "java-plugin\0\uD800middle\uDC00\uD83D\uDE00suffix";
    }
}
