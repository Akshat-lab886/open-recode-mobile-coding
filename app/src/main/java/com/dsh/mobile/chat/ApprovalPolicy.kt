package com.dsh.mobile.chat

/**
 * How dangerous a pending tool call is.
 *
 * The reference implementation carries a risk level on every approval, and it
 * earns its place: an approval sheet that renders `rm -rf build` and
 * `rm -rf /` identically trains the user to tap Approve without reading, which
 * is worse than having no approval step at all - it manufactures consent.
 *
 * Pure and string-based so the rules are testable without an Android runtime.
 */
object ApprovalPolicy {

    /** Coarse category, from the gateway's own classification. */
    fun riskForKind(kind: String): RiskLevel = when (kind.uppercase()) {
        // A shell command can do anything the app can do.
        "COMMAND" -> RiskLevel.HIGH
        "FILE_WRITE" -> RiskLevel.MEDIUM
        else -> RiskLevel.MEDIUM
    }

    /**
     * Destructive or irreversible shell patterns.
     *
     * Deliberately pattern-based and deliberately shallow: this is a "look
     * carefully" signal, not a sandbox. Anything that claims to be a safety
     * boundary would be lying, and a lying boundary is worse than an honest
     * warning. It only ever RAISES risk, never lowers it.
     */
    private val DESTRUCTIVE = listOf(
        Regex("""\brm\s+(-[a-zA-Z]*[rf][a-zA-Z]*\s+)+"""),        // rm -rf, rm -fr
        Regex("""\brm\s+-[a-zA-Z]*[rf]"""),
        Regex("""\bsudo\b"""),
        Regex("""\b(mkfs|fdisk|parted)\b"""),
        Regex("""\bdd\s+.*\bof="""),
        Regex("""\bchmod\s+(-R\s+)?[0-7]*777"""),
        Regex("""\bchown\s+-R\b"""),
        Regex("""\bgit\s+push\b.*(--force|-f)\b"""),
        Regex("""\bgit\s+reset\s+--hard\b"""),
        Regex("""\bgit\s+clean\s+-[a-zA-Z]*[fd]"""),
        Regex("""\b(npm|pnpm|yarn)\s+publish\b"""),
        Regex("""\b(shutdown|reboot|halt|poweroff)\b"""),
        Regex("""\bkill(all)?\s+-9\b"""),
        Regex("""\b(curl|wget)\b[^|]*\|\s*(sudo\s+)?(sh|bash|zsh)\b"""),  // pipe-to-shell
        Regex("""\btruncate\s+-s\s*0"""),
        Regex("""\b>\s*/dev/(sd|block)"""),
        Regex("""\b:\(\)\s*\{"""),                                          // fork bomb
        Regex("""\beval\b"""),
        Regex("""\brm\b.*\s/(\s|$)"""),                                     // rm something at root
    )

    /** True when [command] matches a destructive pattern. */
    fun isDestructive(command: String?): Boolean {
        if (command.isNullOrBlank()) return false
        // Collapse whitespace so "rm    -rf" cannot slip past the patterns.
        val normalized = command.replace(Regex("""\s+"""), " ")
        return DESTRUCTIVE.any { it.containsMatchIn(normalized) }
    }

    /**
     * The final risk for an approval.
     *
     * Destructive content escalates to HIGH regardless of how the tool was
     * classified, because a file-write tool that has been told to overwrite a
     * config with a shell redirect is still a destructive act.
     */
    fun assess(kind: String, commandPreview: String?, affectedPaths: List<String>): RiskLevel {
        if (isDestructive(commandPreview)) return RiskLevel.HIGH
        // Writing to many files at once is harder to undo than writing to one.
        if (affectedPaths.size >= 10) return RiskLevel.HIGH
        return riskForKind(kind)
    }

    /** A short, honest caption for the approval sheet. */
    fun describe(risk: RiskLevel, commandPreview: String?): String = when {
        isDestructive(commandPreview) ->
            "This looks destructive and may not be reversible."
        risk == RiskLevel.HIGH -> "This can change or delete files."
        risk == RiskLevel.MEDIUM -> "This will modify your workspace."
        else -> "This is read-only."
    }
}
