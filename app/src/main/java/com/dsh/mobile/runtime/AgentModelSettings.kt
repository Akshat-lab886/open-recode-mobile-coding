package com.dsh.mobile.runtime

import java.io.File

/**
 * Writes the agent's own settings file so a stored API key actually reaches a
 * model.
 *
 * ## Why this file exists
 *
 * The app stores a provider key in the Keystore and injects it into the agent
 * as an environment variable. That is necessary but nowhere near sufficient.
 * DSH resolves a model through two *settings* sections, and with neither one
 * configured it boots with no usable provider at all: the send and attachment
 * buttons render disabled, the chat box accepts nothing, and tapping it does
 * nothing. The shell then shows "Chat and attachments are off until a model is
 * set" - which reads like a UI problem but is a configuration one.
 *
 * ## The contract, read from the agent rather than guessed
 *
 * Both keys were verified against the installed agent's own type definitions:
 *
 *  - `@deepseek-ai/dsh-agent-default-model` declares the namespace
 *    `agent-default-model` with fields `provider` (a registered provider route)
 *    and `model` (a provider-owned model id).
 *  - `@deepseek-ai/dsh-llm-deepseek` registers the route `deepseek-official`
 *    and documents that `apiKeyEnv` **defaults to `DEEPSEEK_API_KEY`** - which
 *    the app already sets. So no key has to be written into this file, and the
 *    secret never lands in a plaintext settings file.
 *
 * Writing the wrong namespace here is what produced the shipped config, which
 * pointed at `http://localhost:20128/v1` with `apiKeyEnv: GH_API_KEY` - a dead
 * endpoint left by a different tool.
 */
internal object AgentModelSettings {

    /** Provider route registered by the bundled DeepSeek LLM plugin. */
    const val PROVIDER_DEEPSEEK = "deepseek-official"

    /**
     * Default model.
     *
     * `deepseek-v4-flash` is in the adapter's default catalog and is the
     * cheapest of the verified ids, which matters on a handset. The ids were
     * read from the installed plugin rather than assumed.
     */
    const val DEFAULT_MODEL = "deepseek-v4-flash"

    /** Model ids the bundled adapter advertises. */
    val KNOWN_MODELS = listOf(
        "deepseek-v4-flash",
        "deepseek-v4-pro",
        "deepseek-v4-flash-vision-exp",
        "deepseek-flash",
    )

    /**
     * The settings.yaml body for [model].
     *
     * Written as a literal rather than round-tripped through a YAML library:
     * this file is appended to a document the agent owns, and a parser/emit
     * cycle would reorder and rewrite every key the agent wrote - including
     * the user's own theme and agent presets.
     */
    fun renderYamlSection(provider: String, model: String): String =
        """
        agent-default-model:
          provider: $provider
          model: $model
        """.trimIndent() + "\n"

    /**
     * Make [settingsFile] select [model], replacing any previous selection.
     *
     * Returns true when the file changed. The file is only rewritten when the
     * content actually differs, because the agent watches this path and a
     * no-op write would look like a configuration change on every boot.
     */
    fun apply(settingsFile: File, provider: String = PROVIDER_DEEPSEEK, model: String = DEFAULT_MODEL): Boolean {
        val desired = renderYamlSection(provider, model)
        val existing = if (settingsFile.isFile) settingsFile.readText() else ""
        val updated = replaceOrAppendSection(existing, "agent-default-model", desired)
        if (updated == existing) return false
        settingsFile.parentFile?.mkdirs()
        settingsFile.writeText(updated)
        return true
    }

    /**
     * Replace a top-level YAML section, or append it when absent.
     *
     * Hand-rolled because the file is small, machine-shaped and already
     * hand-maintained elsewhere in this project; a YAML library would reformat
     * the agent's own keys as a side effect of changing one of them.
     */
    internal fun replaceOrAppendSection(existing: String, key: String, section: String): String {
        val lines = existing.lines().toMutableList()
        val start = lines.indexOfFirst { it.trimEnd() == "$key:" }
        if (start == -1) {
            val prefix = if (existing.isBlank()) "" else existing.trimEnd() + "\n"
            return prefix + section
        }
        // The section runs until the next line that starts a new top-level key.
        var end = start + 1
        while (end < lines.size && !isTopLevelKey(lines[end])) end++
        val replacement = section.trimEnd().lines()
        lines.subList(start, end).clear()
        lines.addAll(start, replacement)
        return lines.joinToString("\n").trimEnd() + "\n"
    }

    /** True when [line] begins a new top-level YAML key (no leading indent). */
    private fun isTopLevelKey(line: String): Boolean {
        if (line.isBlank() || line.trimStart().startsWith("#")) return false
        if (line.first().isWhitespace()) return false
        return line.contains(':')
    }

    /**
     * True when [yaml] already selects [provider]/[model].
     *
     * Used by the test and by callers that want to avoid touching the file.
     */
    fun selects(yaml: String, provider: String = PROVIDER_DEEPSEEK, model: String = DEFAULT_MODEL): Boolean {
        val providerLine = yaml.lines().firstOrNull { it.trimStart().startsWith("provider:") }
            ?.substringAfter(':')?.trim()
        val modelLine = yaml.lines().firstOrNull { it.trimStart().startsWith("model:") }
            ?.substringAfter(':')?.trim()
        return providerLine == provider && modelLine == model
    }
}