package io.github.weg2022.strguard

import org.gradle.testkit.runner.GradleRunner
import java.nio.file.Files
import java.nio.file.Path

/**
 * 共享的 TestKit runner 工厂。
 *
 * 默认 TestKit 目录是每次 JVM 运行创建的临时目录,导致每次 `gradlew test`
 * 都重新下载 kotlin/compose/AGP 等数百 MB 依赖。这里固定为
 * `~/.gradle-test-kit/strguard`,让 Gradle 依赖缓存与 daemon 跨测试运行复用,
 * 大幅缩短功能测试耗时(CI 可缓存该目录,效果一致)。
 */
internal fun gradleRunnerFor(
    projectDirectory: Path,
    vararg arguments: String,
    withPluginClasspath: Boolean = false,
): GradleRunner {
    val runner =
        GradleRunner.create()
            .withProjectDir(projectDirectory.toFile())
            .withTestKitDir(testKitHome().toFile())
            .withArguments(*arguments, "--stacktrace")
            .forwardOutput()
    return if (withPluginClasspath) runner.withPluginClasspath() else runner
}

internal fun testKitHome(): Path {
    val home = Path.of(System.getProperty("user.home"), ".gradle-test-kit", "strguard")
    Files.createDirectories(home)
    return home
}
