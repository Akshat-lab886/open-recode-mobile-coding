package com.dsh.mobile.commands

/** How a slash command reaches the agent. */
enum class CommandKind {
    /**
     * DSH's own command, sent VERBATIM. Rewriting these into prose would break
     * them - the agent matches the literal token, so "/help" must never arrive
     * as "please show me the help".
     */
    NATIVE,

    /**
     * A prompt template. The user may type extra text after the command, and it
     * is appended to the template.
     */
    TEMPLATE,
}

data class SlashCommand(
    val name: String,
    val summary: String,
    val emoji: String,
    val kind: CommandKind,
    /** Sent as-is when [kind] is NATIVE; the instruction sent when TEMPLATE. */
    val payload: String,
    val aliases: List<String> = emptyList(),
) {
    /** Every token this command answers to, name first. */
    val tokens: List<String> get() = listOf(name) + aliases
}

/** What the composer should do with a typed line. */
sealed interface CommandResolution {
    /** No leading slash command; send [text] as-is. */
    data class Plain(val text: String) : CommandResolution

    /** A leading "/" that is still being typed, or with nothing after it. */
    data class Pending(val prefix: String) : CommandResolution

    /** A "/" whose name matches nothing; offer [suggestions]. */
    data class Unknown(val name: String, val suggestions: List<SlashCommand>) : CommandResolution

    /** Resolved: this is the exact [text] to send to the agent. */
    data class Resolved(val command: SlashCommand, val text: String) : CommandResolution
}

/**
 * The slash-command registry behind the composer.
 *
 * Two families live here on purpose. DSH already implements its own commands
 * (/help, /clear-context, ...) and those must arrive verbatim. The rest are
 * prompt templates - a convenience for a phone keyboard, not agent features -
 * and they expand into an instruction the agent understands.
 */
object SlashCommands {

    val ALL: List<SlashCommand> = listOf(
        // --- DSH native: sent verbatim ---
        SlashCommand("help", "List what the agent can do", "❓", CommandKind.NATIVE, "/help"),
        SlashCommand("stop", "Stop the current generation", "⏹", CommandKind.NATIVE, "/stop-generation", listOf("stop-generation")),
        SlashCommand("clear", "Clear the conversation", "🧹", CommandKind.NATIVE, "/clear-context", listOf("clear-context")),
        SlashCommand("reindex", "Re-scan the workspace", "🔄", CommandKind.NATIVE, "/reindex-workspace", listOf("reindex-workspace")),

        // --- Prompt templates ---
        SlashCommand(
            name = "plan",
            summary = "Plan before changing anything",
            emoji = "🗺️",
            kind = CommandKind.TEMPLATE,
            payload = "Plan this out before writing any code. Describe the approach as short numbered steps, " +
                "name the files you would touch, and wait for my approval before editing anything.",
        ),
        SlashCommand(
            name = "fix",
            summary = "Find and fix a bug",
            emoji = "🐛",
            kind = CommandKind.TEMPLATE,
            payload = "Find the bug, explain its root cause in one or two sentences, then fix it. " +
                "After the fix, restate what changed and why it was wrong.",
        ),
        SlashCommand(
            name = "test",
            summary = "Write or run tests for the current work",
            emoji = "🧪",
            kind = CommandKind.TEMPLATE,
            payload = "Write tests that cover the behaviour I just asked for, run them, and report " +
                "the actual output. If a test fails, say so plainly instead of claiming success.",
        ),
        SlashCommand(
            name = "compact",
            summary = "Summarise the conversation so far",
            emoji = "🗜️",
            kind = CommandKind.TEMPLATE,
            payload = "Summarise this conversation so far: the goal, decisions already made, files touched, " +
                "and what is still open. Keep it short enough to paste as a fresh starting point.",
        ),
        SlashCommand(
            name = "goal",
            summary = "State the goal before working",
            emoji = "🎯",
            kind = CommandKind.TEMPLATE,
            payload = "Before doing anything, restate the goal in one sentence and list what is in and " +
                "out of scope. Then stop and let me confirm.",
        ),
    )

    private val byToken: Map<String, SlashCommand> =
        ALL.flatMap { cmd -> cmd.tokens.map { it to cmd } }.toMap()

    /** Commands offered in the palette, in registry order. */
    val palette: List<SlashCommand> get() = ALL

    /** Look up a command by name or alias, case-insensitively. */
    fun find(name: String): SlashCommand? = byToken[name.trimStart('/').lowercase()]

    /** Palette entries starting with [prefix] (empty prefix returns everything). */
    fun matching(prefix: String): List<SlashCommand> {
        val p = prefix.trimStart('/').lowercase()
        if (p.isEmpty()) return ALL
        return ALL.filter { cmd -> cmd.tokens.any { it.lowercase().startsWith(p) } }
    }

    /**
     * Interprets a line typed into the composer.
     *
     * A leading "/" always routes through here. Crucially, a slash command that
     * takes no argument still swallows its trailing text for NATIVE commands -
     * "/help me" must not send "/help me" to an agent matching a literal token.
     */
    fun resolve(input: String): CommandResolution {
        val trimmed = input.trim()
        if (!trimmed.startsWith("/")) return CommandResolution.Plain(input)
        val spaceAt = trimmed.indexOfFirst { it == ' ' || it == '\t' }
        val namePart = if (spaceAt < 0) trimmed else trimmed.substring(0, spaceAt)
        val rest = if (spaceAt < 0) "" else trimmed.substring(spaceAt).trim()

        // Bare "/" or a name still being typed: show the palette instead of
        // sending anything.
        if (namePart.length <= 1) return CommandResolution.Pending(namePart)

        val command = find(namePart)
        if (command == null) {
            return CommandResolution.Unknown(
                name = namePart.trimStart('/'),
                suggestions = matching(namePart).ifEmpty { ALL.take(3) },
            )
        }
        val text = when (command.kind) {
            CommandKind.NATIVE -> command.payload
            CommandKind.TEMPLATE ->
                if (rest.isEmpty()) command.payload else "${command.payload}\n\nMy request: $rest"
        }
        return CommandResolution.Resolved(command, text)
    }

    /** The chips shown under the composer: the most-used few, always visible. */
    val QUICK_CHIPS: List<SlashCommand> =
        listOf("plan", "fix", "test", "compact", "goal", "clear")
            .mapNotNull { find(it) }
}
