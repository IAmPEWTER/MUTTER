package com.peter.mutter

/**
 * The rule that v0.7.0 broke, kept pure so it is unit-tested rather than
 * rediscovered in the field: it pruned the previous model on service connect,
 * before the replacement had been downloaded, and every install went dark.
 */
object ModelPolicy {

    /**
     * Which model directories may be deleted.
     *
     * Empty until the preferred model is verified complete. An update may cost
     * the user storage while its model downloads; it may never cost dictation.
     */
    fun prunable(
        dirNames: List<String>,
        preferredDir: String,
        preferredComplete: Boolean,
    ): List<String> =
        if (!preferredComplete) emptyList() else dirNames.filterNot { it == preferredDir }
}
