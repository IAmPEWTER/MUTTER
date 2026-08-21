package com.peter.mutter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ModelPolicyTest {

    private val preferred = SttModel.PREFERRED.dir
    private val previous = SttModel.DISTIL_SMALL_EN.dir

    // The v0.7.0 outage, as a test: pruning ran on service connect regardless
    // of whether the new model had arrived, so the phone kept neither.
    @Test
    fun keeps_every_model_until_the_preferred_one_is_complete() {
        assertEquals(
            emptyList(),
            ModelPolicy.prunable(listOf(previous, "some-retired-model"), preferred, false),
        )
    }

    @Test
    fun drops_superseded_models_once_the_preferred_one_is_complete() {
        assertEquals(
            listOf(previous, "some-retired-model"),
            ModelPolicy.prunable(listOf(previous, "some-retired-model"), preferred, true),
        )
    }

    @Test
    fun never_prunes_the_preferred_model_itself() {
        assertEquals(
            emptyList(),
            ModelPolicy.prunable(listOf(preferred), preferred, true),
        )
    }

    @Test
    fun resolves_the_preferred_model_when_both_are_present() {
        assertSame(SttModel.PREFERRED, ModelPolicy.resolve(SttModel.KNOWN) { true })
    }

    @Test
    fun falls_back_to_the_previous_model_while_the_new_one_downloads() {
        val resolved = ModelPolicy.resolve(SttModel.KNOWN) { it != SttModel.PREFERRED }
        assertSame(SttModel.DISTIL_SMALL_EN, resolved)
    }

    @Test
    fun reports_nothing_when_the_phone_has_no_model() {
        assertNull(ModelPolicy.resolve(SttModel.KNOWN) { false })
    }

    // A missing VAD degrades segmentation to RMS; it must not make a model that
    // can still transcribe look absent.
    @Test
    fun recognizer_assets_exclude_the_vad() {
        for (spec in SttModel.KNOWN) {
            assertEquals(
                spec.assets.size - 1,
                spec.recognizerAssets.size,
                "${spec.dir} should require every asset but the VAD",
            )
        }
    }

    @Test
    fun only_a_fully_hashed_model_may_be_fetched() {
        assertEquals(true, SttModel.PREFERRED.downloadable)
        // No hashes were recorded for it when it shipped, so it is load-only.
        assertEquals(false, SttModel.DISTIL_SMALL_EN.downloadable)
    }
}
