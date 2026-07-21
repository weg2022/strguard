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
}

object AndroidIdentityPeer {
    fun sharedIdentity(): String = "kotlin-android-shared-identity"

    fun concurrentIdentity(): String = "kotlin-android-concurrent-first-access"
}
