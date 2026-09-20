package mihon.gradle

import org.gradle.api.Project
import kotlin.time.Clock
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

// Git is needed in your system PATH for these commands to work.
// If it's not installed, you can return a random value as a workaround
fun Project.getLatestCommitCount(): String {
    return runCatching { exec("git rev-list --count HEAD") }.getOrElse { "30" }
}

fun Project.getLatestCommitSha(): String {
    return runCatching { exec("git rev-parse --short HEAD") }.getOrElse { "dev" }
}

/**
 * @param useLatestCommitTime If `true`, the build time is based on the timestamp of the last Git commit;
 *                          otherwise, the current time is used. Both are in UTC.
 * @return An ISO 8601 formatted string representing the build time.
 */
fun Project.getBuildTime(useLatestCommitTime: Boolean): String {
    return if (useLatestCommitTime) {
        val epoch = runCatching { exec("git log -1 --format=%ct").toLong() }.getOrNull()
        if (epoch != null) {
            Instant.fromEpochSeconds(epoch).toString()
        } else {
            val now = Clock.System.now()
            (now - now.nanosecondsOfSecond.nanoseconds).toString()
        }
    } else {
        val now = Clock.System.now()
        (now - now.nanosecondsOfSecond.nanoseconds).toString()
    }
}

fun Project.exec(command: String): String {
    return providers.exec {
        commandLine = command.split(" ")
    }
        .standardOutput
        .asText
        .get()
        .trim()
}