package sample.android.library;

public final class ProtectedLibrary {
    public static final String LIBRARY_CONSTANT = "android-library-static-final-value";

    public String value() {
        return "android-library-protected-value";
    }

    public String stringMatrix(int code) {
        String selected;
        switch (code) {
            case 0:
                selected = "android-library-switch-zero";
                break;
            case 1:
                selected = "android-library-switch-one\twith-tab";
                break;
            default:
                selected = "android-library-switch-default";
        }
        StringSupplier lambda = () -> "android-library-lambda-value";
        return LIBRARY_CONSTANT
               + "|android-library-line-one\nandroid-library-line-two"
               + "|android-library-unicode-\u4F60\u597D-\uD83D\uDE80|"
               + selected
               + "|"
               + lambda.get();
    }

    private interface StringSupplier {
        String get();
    }
}
