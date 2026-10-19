package com.dsh.mobile.chat

/** Who produced a message. */
enum class ChatRole { USER, AGENT }

/** How much damage a tool call could do if approved. */
enum class RiskLevel { LOW, MEDIUM, HIGH }

/** A file or image travelling with a message. */
data class ChatAttachment(
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val path: String,
) {
    val isImage: Boolean get() = mimeType.startsWith("image/")
}

/**
 * A tool call awaiting the user's decision.
 *
 * Carries the affected paths and a command preview because approving "Bash"
 * without seeing what it will run is not consent, it is a coin flip.
 */
data class ToolApproval(
    val id: String,
    val toolName: String,
    val explanation: String,
    val affectedPaths: List<String> = emptyList(),
    val commandPreview: String? = null,
    val risk: RiskLevel = RiskLevel.MEDIUM,
)

/** One entry in the conversation. */
data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val text: String,
    val attachments: List<ChatAttachment> = emptyList(),
    /** How long the agent worked on this reply; 0 when unknown or still running. */
    val workedMillis: Long = 0L,
    /** True while deltas are still arriving. */
    val streaming: Boolean = false,
    /** Set when the turn ended in an error rather than a reply. */
    val error: String? = null,
)
