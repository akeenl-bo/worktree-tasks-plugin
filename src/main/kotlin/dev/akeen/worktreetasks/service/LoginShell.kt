package dev.akeen.worktreetasks.service

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Path

/**
 * Runs tools the way the user's login shell would find them. An IDE launched from the Dock lacks
 * Homebrew's PATH, so `gh`, `git`, and `claude` are resolved through `$SHELL -l`. Blocking.
 */
object LoginShell {

    private val LOG = Logger.getInstance(LoginShell::class.java)

    /**
     * Auth variables that would make `claude` use an API key instead of the user's claude.ai login,
     * which turns off claude.ai connectors (Jira) in the session.
     */
    private val CLAUDE_AUTH_OVERRIDES = listOf("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN")

    private val shell: String get() = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/zsh"

    val path: String by lazy {
        run("printf %s \"\$PATH\"")?.takeIf { it.isNotBlank() } ?: System.getenv("PATH").orEmpty()
    }

    /** Absolute path of [command] on the login PATH, or null. */
    fun which(command: String): String? = run("command -v ${quote(command)}")?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.startsWith("/") }

    /** `gh` with [args] in [workDir]; stdout on success, else null. */
    fun gh(workDir: Path, vararg args: String): String? = run((listOf("gh") + args).joinToString(" ") { quote(it) }, workDir)

    /** The process environment with the login PATH and without [CLAUDE_AUTH_OVERRIDES]. */
    fun claudeEnvironment(): Map<String, String> =
        System.getenv().filterKeys { it !in CLAUDE_AUTH_OVERRIDES } + ("PATH" to path)

    private fun run(script: String, workDir: Path? = null): String? = try {
        val cmd = GeneralCommandLine(shell, "-l", "-c", script).withCharset(Charsets.UTF_8)
        workDir?.let { cmd.withWorkDirectory(it.toString()) }
        val out = ExecUtil.execAndGetOutput(cmd, 30_000)
        out.stdout.trim().takeIf { out.exitCode == 0 }
    } catch (t: Throwable) {
        LOG.warn("Login shell command failed: ${script.take(120)}", t)
        null
    }

    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
