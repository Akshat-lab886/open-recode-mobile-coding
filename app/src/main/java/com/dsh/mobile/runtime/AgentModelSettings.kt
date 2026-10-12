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
 *
 * ## Still missing: the provider is not actually registered
 *
 * Verified on device. Writing this section is necessary but NOT sufficient.
 *
 * The `web` profile's `package.json` composes exactly two bundles:
 *
 *     "bundles": ["@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app"]
 *
 * `@deepseek-ai/dsh-llm-deepseek` - the plugin that registers the
 * `deepseek-official` route - is **not** among them. The selection therefore
 * names a route that does not exist in the running agent, and chat stays
 * disabled with send and attachment rendered off.
 *
 * ## Why a plain bundle entry is the wrong fix (verified on device)
 *
 * Adding it to `dsh.profile.bundles` fails at boot, and fails loudly:
 *
 *     Error: dsh: profile bundle "@deepseek-ai/dsh-llm-deepseek"
 *            declares no dsh.bundle in its package.json
 *
 * So `dsh-llm-deepseek` is a **plugin, not a bundle**. A bundle is a package
 * whose manifest declares `"dsh": { "bundle": { "patch": "./cordis.patch.yml" } }`
 * and which owns a patch layer; a plugin has no patch layer of its own and has
 * to be registered through the entry list instead. The shipped profile's
 * `cordis.patch.yml` is `[]`.
 *
 * The remaining piece is therefore: register the plugin through
 * `cordis.patch.yml`, and let it resolve. Per `@deepseek-ai/dsh-app-boot`,
 * resolution is two-anchor - profile-local `node_modules` wins, and
 * `$DSH_HOME/profiles/node_modules` supplies the installation closure through
 * Node's ordinary parent-walk. Every package needed
 * (`dsh-llm-deepseek`, `dsh-llm`, `dsh-brand`, `dsh-util-values`,
 * `schemastery`, `eventsource-parser`, `dsh-llm-retry`) is already present
 * under `agents/dsh/node_modules`, so nothing has to be downloaded - only
 * linked and registered.
 */
internal object AgentModelSettings {

    /** Top-level settings key that would shadow the agent's own model default. */
    const val SECTION = "agent-default-model"

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
     * Remove any user-layer [SECTION] override from [settingsFile].
     *
     * ## Why this deletes rather than writes
     *
     * An earlier version of this file wrote an `agent-default-model` section
     * naming `deepseek-official` / `deepseek-v4-flash`, on the assumption the
     * agent shipped with no model selected. That assumption was **wrong**, and
     * the write actively broke chat.
     *
     * `@deepseek-ai/dsh-base` already registers the `llm-deepseek` adapter and
     * ships this default in its own patch layer:
     *
     *     - id: agent-default-model
     *       name: '@deepseek-ai/dsh-agent-default-model'
     *       config:
     *         provider: deepseek-official
     *         model: deepseek-flash
     *
     * The user layer replaces a row's whole `config` rather than merging into
     * it, so our override shadowed that working default. With it present the
     * web UI rendered send and attachment disabled and showed "Chat and
     * attachments are off until a model is set"; with it removed the agent
     * boots and chat is enabled. Verified on device both ways.
     *
     * So the correct job here is to make sure no stale user-layer override
     * survives, and otherwise leave the agent's own defaults alone.
     *
     * Returns true when the file changed.
     */
    fun apply(settingsFile: File): Boolean {
        if (!settingsFile.isFile) return false
        val existing = settingsFile.readText()
        if (!existing.lines().any { it.trimEnd() == "$SECTION:" }) return false
        settingsFile.writeText(replaceOrAppendSection(existing, SECTION, ""))
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
        // An empty section means "remove it": this is how a stale user-layer
        // override is deleted so the agent's own default applies again.
        if (section.isBlank()) {
            lines.subList(start, end).clear()
            return lines.joinToString("\n").trimEnd() + "\n"
        }
        lines.subList(start, end).clear()
        lines.addAll(start, section.trimEnd().lines())
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