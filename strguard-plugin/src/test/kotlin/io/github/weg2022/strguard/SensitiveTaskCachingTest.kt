package io.github.weg2022.strguard

import org.gradle.api.tasks.CacheableTask
import org.gradle.work.DisableCachingByDefault
import kotlin.test.*

class SensitiveTaskCachingTest {
    @Test
    fun `deterministic consumer tasks support the Gradle Build Cache`() {
        listOf(
            TransformClassesTask::class.java,
            TransformAndroidClassesTask::class.java,
            BuildNativeRuntimeTask::class.java,
            BuildAndroidNativeRuntimeTask::class.java,
            MergeStrGuardJniLibsTask::class.java,
            VerifyStrGuardShrunkArtifactTask::class.java,
        ).forEach { taskType ->
            assertNotNull(taskType.getAnnotation(CacheableTask::class.java), taskType.name)
            assertNull(taskType.getAnnotation(DisableCachingByDefault::class.java), taskType.name)
        }
    }
}
