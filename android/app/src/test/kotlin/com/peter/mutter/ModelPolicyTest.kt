package com.peter.mutter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ModelPolicyTest {

    private val preferred = SttModel.PREFERRED.dir
    // "Some model that is not the preferred one" — never a named one, so these
    // stay true through a model swap instead of quietly encoding this week's.
    private val previous = SttModel.KNOWN.first { it !== SttModel.PREFERRED }.dir

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

    // The fallback exists so a model swap never darkens the app; the order is
    // what makes the new model win once it lands.
    @Test
    fun the_preferred_model_is_first_and_a_fallback_always_exists() {
        assertSame(SttModel.PREFERRED, SttModel.KNOWN.first())
        // Without a second entry, swapping models darks the app — v0.7.0.
        assertEquals(true, SttModel.KNOWN.size >= 2)
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
        // Whatever is preferred must be fetchable, or a fresh install is dead.
        assertEquals(true, SttModel.PREFERRED.downloadable)
        val unverifiable = SttModel.PREFERRED.copy(
            assets = SttModel.PREFERRED.assets.mapIndexed { i, a ->
                if (i == 0) a.copy(sha256 = null) else a
            },
        )
        assertEquals(false, unverifiable.downloadable)
    }
}
