package sample.android.application;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public final class MainActivity extends Activity {
    public static final String DISPLAY_CONSTANT = "android-application-static-final-value";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView message = new TextView(this);
        message.setText(complexValue("android-application-beta-key"));
        setContentView(message);
    }

    public String complexValue(String key) {
        String selected;
        switch (key) {
            case "android-application-alpha-key":
                selected = "android-application-switch-alpha";
                break;
            case "android-application-beta-key":
                selected = "android-application-switch-beta\twith-tab";
                break;
            default:
                selected = "android-application-switch-default";
        }
        StringSupplier lambda = () -> "android-application-lambda-value";
        return DISPLAY_CONSTANT
               + "|android-application-line-one\nandroid-application-line-two"
               + "|android-application-unicode-\u4F60\u597D-\uD83D\uDE80|"
               + selected
               + "|"
               + lambda.get();
    }

    private interface StringSupplier {
        String get();
    }
}
