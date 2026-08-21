package com.peter.mutter

/**
 * The model-lifecycle rules, kept pure so they are unit-tested rather than
 * discovered in the field. v0.7.0 broke [prunable] — it deleted the previous
 * model on service connect, before the replacement had been downloaded — and
 * every install went dark until a 620 MB fetch the user had to find and start
 * by hand.
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

    /**
     * The best model the phone can load right now, in [SttModel.KNOWN] order.
     *
     * [hasRecognizerFiles] deliberately excludes the VAD: without it
     * segmentation degrades to RMS but dictation still works, so a missing VAD
     * must not make a usable model look absent.
     */
    fun resolve(
        specs: List<SttModel.Spec>,
        hasRecognizerFiles: (SttModel.Spec) -> Boolean,
    ): SttModel.Spec? = specs.firstOrNull(hasRecognizerFiles)
}
