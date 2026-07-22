package sample.application;

public final class Main {
    private static final String APPLICATION_CONSTANT = "application-static-final-value";

    private Main() {
    }

    public static void main(String[] args) {
        String[] expected = {
                APPLICATION_CONSTANT,
                "application-array-value",
                "application-line-one\napplication-line-two",
                "application-unicode-\u4F60\u597D-\uD83D\uDE80",
                "application-lambda-value"
        };
        java.util.function.Supplier<String> lambda = () -> "application-lambda-value";
        String selected;
        switch (args.length == 0 ? "application-default-key" : args[0]) {
            case "application-alpha-key":
                selected = "application-switch-alpha";
                break;
            default:
                selected = "application-switch-default";
        }
        if (!expected[4].equals(lambda.get()) || !"application-switch-default".equals(selected)) {
            throw new IllegalStateException("application-complex-string-verification-failed");
        }
        System.out.println(String.join("|", expected));
    }
}
