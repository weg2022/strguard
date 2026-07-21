package sample.kotlin.android

import android.content.Intent
import android.os.Build
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class StrGuardRuntimeTest {
    @Test
    fun protectedStringsLoadOnExpectedArtAbi() {
        val expectedAbi =
            InstrumentationRegistry.getArguments().getString("expectedAbi")
                ?: error("expectedAbi instrumentation argument is required")
        val processAbi = currentProcessAbi()
        assertEquals(
            "Expected $expectedAbi, ART process os.arch=${System.getProperty("os.arch")}",
            expectedAbi,
            processAbi,
        )
        assertTrue(
            "Expected $expectedAbi, device reports ${Build.SUPPORTED_ABIS.joinToString()}",
            expectedAbi in Build.SUPPORTED_ABIS,
        )
        if (InstrumentationRegistry.getArguments().getString("expectTamper").toBoolean()) {
            val failure = runCatching { MainActivity().identityFirst() }.exceptionOrNull()
            assertNotNull("Tampered vault unexpectedly decoded", failure)
            val messages = generateSequence(failure) { cause -> cause.cause }.mapNotNull(Throwable::message).toList()
            assertTrue(
                "Expected Native authentication failure, got $messages",
                messages.any { message -> message.contains("StrGuard vault authentication failed") },
            )
            return
        }

        assertConcurrentFirstAccess()
        val activity = MainActivity()
        assertSame(activity.identityFirst(), activity.identitySecond())
        assertSame(activity.identityFirst(), AndroidIdentityPeer.sharedIdentity())
        assertArrayEquals(
            "prefix\u0000\uD800middle\uDC00\uD83D\uDE00suffix".toCharArray(),
            activity.specialUtf16().toCharArray(),
        )

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val launched =
            instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
        try {
            instrumentation.waitForIdleSync()
            val content = launched.findViewById<ViewGroup>(android.R.id.content)
            val text = content.getChildAt(0) as TextView
            assertEquals("kotlin-android-protected-value", text.text.toString())
        } finally {
            launched.finish()
        }
    }

    private fun assertConcurrentFirstAccess() {
        val count = 16
        val values = arrayOfNulls<String>(count)
        val ready = CountDownLatch(count)
        val start = CountDownLatch(1)
        val threads =
            List(count) { index ->
                Thread {
                    ready.countDown()
                    start.await()
                    values[index] = AndroidIdentityPeer.concurrentIdentity()
                }.apply { start() }
            }
        assertTrue("Workers did not become ready", ready.await(10, TimeUnit.SECONDS))
        start.countDown()
        threads.forEach { thread -> thread.join(10_000) }
        assertTrue("A concurrent worker did not finish", threads.none(Thread::isAlive))
        values.forEach { value -> assertSame(values.first(), value) }
    }

    private fun currentProcessAbi(): String = when (
        val architecture = requireNotNull(System.getProperty("os.arch")) { "ART os.arch is unavailable" }.lowercase(Locale.ROOT)
    ) {
        "aarch64", "arm64" -> "arm64-v8a"
        "arm", "armv7", "armv7l" -> "armeabi-v7a"
        "x86_64", "amd64" -> "x86_64"
        "x86", "i386", "i486", "i586", "i686" -> "x86"
        else -> error("Unsupported ART process architecture '$architecture'")
    }
}
