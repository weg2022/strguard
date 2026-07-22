package sample.kotlin.android

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "kotlin-android-protected-value" })
    }

    fun identityFirst(): String = "kotlin-android-shared-identity"

    fun identitySecond(): String = "kotlin-android-shared-identity"

    fun specialUtf16(): String = "prefix\u0000\uD800middle\uDC00\uD83D\uDE00suffix"

    fun arrayValue(index: Int): String =
        arrayOf(
            "kotlin-android-array-zero",
            "kotlin-android-line-one\nkotlin-android-line-two",
            "kotlin-android-unicode-\u4F60\u597D-\uD83D\uDE80",
        )[index]

    fun whenValue(code: Int): String =
        when (code) {
            0 -> "kotlin-android-when-zero"
            1 -> "kotlin-android-when-one\twith-tab"
            else -> "kotlin-android-when-default"
        }

    fun lambdaValue(): String = { "kotlin-android-lambda-value" }()
}

object AndroidIdentityPeer {
    fun sharedIdentity(): String = "kotlin-android-shared-identity"

    fun concurrentIdentity(): String = "kotlin-android-concurrent-first-access"
}
