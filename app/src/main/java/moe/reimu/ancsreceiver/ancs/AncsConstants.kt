package moe.reimu.ancsreceiver.ancs

object AncsConstants {
    const val NotificationAttributeIDAppIdentifier: Byte = 0
    const val NotificationAttributeIDTitle: Byte = 1
    const val NotificationAttributeIDSubtitle: Byte = 2
    const val NotificationAttributeIDMessage: Byte = 3
    const val NotificationAttributeIDPositiveActionLabel: Byte = 6
    const val NotificationAttributeIDNegativeActionLabel: Byte = 7
}

object AncsActionLabels {
    private val CLEAR_STRINGS = setOf(
        "Clear",
        "清除"
    ).map { it.uppercase() }.toSet()

    /**
     * Check if the given ANCS action label is a "Clear" action.
     * The label is in the iPhone's language, so we compare against all supported translations.
     */
    fun isClearAction(actionLabel: String): Boolean {
        return actionLabel.uppercase() in CLEAR_STRINGS
    }
}

