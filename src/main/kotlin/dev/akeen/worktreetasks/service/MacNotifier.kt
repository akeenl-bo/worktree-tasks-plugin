package dev.akeen.worktreetasks.service

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path

/**
 * macOS banners and sounds for task notifications. IntelliJ's own system notifications use the
 * deprecated NSUserNotification API, which recent macOS no longer shows, so banners go through a
 * tiny helper app (`notifier/WorktreeTasksNotifier.swift`) compiled here on first use into
 * `~/Library/Application Support/worktree-tasks/`. It shows up as "Worktree Tasks" in System Settings,
 * and clicking a banner opens that task in this IDE. Without a Swift compiler it falls back to an
 * `osascript` banner, which works but is credited to Script Editor. Blocking; call off the EDT.
 */
object MacNotifier {

    private const val HELPER_VERSION = "1"
    private const val BUNDLE_ID = "dev.akeen.worktreetasks.notify"
    private const val EXECUTABLE = "WorktreeTasksNotifier"
    private val LOG = Logger.getInstance(MacNotifier::class.java)

    private val home: Path = Path.of(System.getProperty("user.home"), "Library", "Application Support", "worktree-tasks")
    private val app: Path = home.resolve("Worktree Tasks.app")
    private val versionFile: Path = home.resolve(".notifier-version")

    @Volatile private var helperReady: Boolean? = null

    /** Build the helper now so the first banner isn't delayed by compiling. */
    fun prepare() {
        if (SystemInfo.isMac) helperReady()
    }

    /** A banner that opens [openPath] in this IDE when clicked, with [sound] (a macOS sound name) or silent. */
    fun banner(title: String, body: String, openPath: Path, sound: String?) {
        if (!SystemInfo.isMac) return
        if (helperReady()) {
            exec(
                "/usr/bin/open", "-n", "-g", "-a", app.toString(), "--args",
                "--title", title, "--body", body, "--open", openPath.toString(),
                "--app", ideApp(), "--sound", sound?.let { "$it.aiff" }.orEmpty(),
            )
        } else {
            val soundClause = sound?.let { " sound name \"$it\"" }.orEmpty()
            exec("/usr/bin/osascript", "-e", "display notification \"${escape(body)}\" with title \"${escape(title)}\"$soundClause")
        }
    }

    /** Just the sound, for when the IDE is in front and shows its own popup. */
    fun sound(sound: String?) {
        if (!SystemInfo.isMac || sound == null) return
        val file = Path.of("/System/Library/Sounds/$sound.aiff")
        if (Files.isRegularFile(file)) {
            try {
                GeneralCommandLine("/usr/bin/afplay", file.toString()).createProcess()
            } catch (t: Throwable) {
                LOG.warn("afplay failed", t)
            }
        }
    }

    /** A macOS system sound name from settings, or null for silent (blank or not a plain name). */
    fun soundName(setting: String): String? = setting.trim().takeIf { it.matches(Regex("[A-Za-z]+")) }

    @Synchronized
    private fun helperReady(): Boolean {
        helperReady?.let { return it }
        val ready = try {
            val current = Files.isRegularFile(versionFile) && Files.readString(versionFile).trim() == HELPER_VERSION &&
                Files.isExecutable(app.resolve("Contents/MacOS/$EXECUTABLE"))
            current || build()
        } catch (t: Throwable) {
            LOG.warn("Couldn't build the notifier helper; falling back to osascript", t)
            false
        }
        helperReady = ready
        return ready
    }

    private fun build(): Boolean {
        val source = MacNotifier::class.java.getResourceAsStream("/notifier/$EXECUTABLE.swift")
            ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return false
        Files.createDirectories(home)
        val sourceFile = home.resolve("$EXECUTABLE.swift")
        Files.writeString(sourceFile, source)
        app.toFile().deleteRecursively()
        val macos = Files.createDirectories(app.resolve("Contents/MacOS"))
        Files.writeString(app.resolve("Contents/Info.plist"), infoPlist())

        val compiled = exec(
            "/usr/bin/xcrun", "swiftc", "-swift-version", "5", "-O",
            "-o", macos.resolve(EXECUTABLE).toString(), sourceFile.toString(),
            "-framework", "AppKit", "-framework", "UserNotifications",
            timeoutMs = 180_000,
        )
        if (!compiled) return false
        if (!exec("/usr/bin/codesign", "--force", "--sign", "-", app.toString())) return false
        Files.writeString(versionFile, HELPER_VERSION)
        return true
    }

    private fun infoPlist() = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0"><dict>
        <key>CFBundleIdentifier</key><string>$BUNDLE_ID</string>
        <key>CFBundleName</key><string>Worktree Tasks</string>
        <key>CFBundleDisplayName</key><string>Worktree Tasks</string>
        <key>CFBundleExecutable</key><string>$EXECUTABLE</string>
        <key>CFBundlePackageType</key><string>APPL</string>
        <key>CFBundleShortVersionString</key><string>$HELPER_VERSION</string>
        <key>CFBundleVersion</key><string>$HELPER_VERSION</string>
        <key>LSUIElement</key><true/>
        <key>LSMinimumSystemVersion</key><string>13.0</string>
        </dict></plist>
    """.trimIndent()

    /** The running IDE's .app bundle, so a banner click opens the task in this IDE, not another. */
    private fun ideApp(): String {
        val home = Path.of(PathManager.getHomePath())
        return (if (home.fileName?.toString() == "Contents") home.parent else home).toString()
    }

    private fun exec(vararg command: String, timeoutMs: Int = 20_000): Boolean = try {
        val out = ExecUtil.execAndGetOutput(GeneralCommandLine(*command), timeoutMs)
        if (out.exitCode != 0) LOG.warn("${command.first()} exited ${out.exitCode}: ${out.stderr.take(500)}")
        out.exitCode == 0
    } catch (t: Throwable) {
        LOG.warn("${command.first()} failed", t)
        false
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
}
