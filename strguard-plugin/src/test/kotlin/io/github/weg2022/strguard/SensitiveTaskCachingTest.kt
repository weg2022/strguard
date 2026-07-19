package io.github.weg2022.strguard

import org.gradle.api.tasks.CacheableTask
import org.gradle.work.DisableCachingByDefault
import kotlin.test.*

class SensitiveTaskCachingTest {
    @Test
    fun `seed-derived transform outputs cannot enter the Gradle Build Cache`() {
        listOf(
            TransformClassesTask::class.java,
            TransformAndroidClassesTask::class.java,
        ).forEach { taskType ->
            assertNull(taskType.getAnnotation(CacheableTask::class.java))
            assertNotNull(taskType.getAnnotation(DisableCachingByDefault::class.java))
        }
    }
}
