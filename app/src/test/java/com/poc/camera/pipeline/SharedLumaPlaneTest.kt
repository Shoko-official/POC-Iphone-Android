package com.poc.camera.pipeline

import com.poc.camera.pipeline.quality.SyntheticScenes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bit-identity proofs for the shared denoised-state luma plane (issue #113): every consumer
 * fed the plane extracted by [FinishingPipeline.sharedLumaPlane] must produce EXACTLY the
 * doubles/bytes it produces when deriving its own luma internally -- sharing is a pure
 * refactoring, not an approximation, so the assertions use zero tolerance. Proven on scenes
 * where the masks genuinely fire (landscape: blue sky + foliage + skin; overcast: gray sky)
 * and on a noisy merged-style variant, plus the tiled-path rowOffset case, so the equality
 * is never vacuous.
 */
class SharedLumaPlaneTest {

    private val scenes = listOf(
        "landscape" to SyntheticScenes.landscapeClean(),
        "overcast" to SyntheticScenes.overcastClean(),
        "landscape-noisy" to SyntheticScenes.noisy(SyntheticScenes.landscapeClean(), seed = 0x113L),
    )

    @Test
    fun sharedPlaneMatchesTheDocumentedRec601Convention() {
        val (_, frame) = scenes[0]
        val luma = FinishingPipeline.sharedLumaPlane(frame, FinishingParams.RENDITION)
        assertNotNull("RENDITION has >= 2 consumers, so the plane must be computed", luma)
        val src = frame.argb
        for (i in src.indices) {
            val pixel = src[i]
            val expected = 0.299 * ((pixel shr 16) and 0xFF) +
                0.587 * ((pixel shr 8) and 0xFF) +
                0.114 * (pixel and 0xFF)
            assertEquals("luma[$i] must be the exact Rec. 601 double", expected, luma!![i], 0.0)
        }
    }

    @Test
    fun masksWithSharedPlaneAreBitIdenticalToStandalone() {
        for ((name, frame) in scenes) {
            val luma = FinishingPipeline.sharedLumaPlane(frame, FinishingParams.RENDITION)!!

            val skin = SkinMask.compute(frame)
            assertArrayEquals("$name: skin mask must be double-exact", skin, SkinMask.compute(frame, luma = luma), 0.0)

            val sky = SkyMask.compute(frame)
            assertArrayEquals("$name: sky mask must be double-exact", sky, SkyMask.compute(frame, luma = luma), 0.0)

            val overcast = OvercastSkyMask.compute(frame)
            assertArrayEquals(
                "$name: overcast mask must be double-exact",
                overcast,
                OvercastSkyMask.compute(frame, luma = luma),
                0.0,
            )

            val foliage = FoliageMask.compute(frame)
            assertArrayEquals(
                "$name: foliage mask must be double-exact",
                foliage,
                FoliageMask.compute(frame, luma = luma),
                0.0,
            )

            assertArrayEquals(
                "$name: detail energy must be double-exact",
                OvercastSkyMask.detailEnergy(frame),
                OvercastSkyMask.detailEnergy(frame, luma = luma),
                0.0,
            )

            // Guard against vacuous equality: at least one mask must genuinely fire.
            assertTrue(
                "$name: at least one mask must be non-zero for the proof to bite",
                skin.any { it > 0.0 } || sky.any { it > 0.0 } || overcast.any { it > 0.0 } || foliage.any { it > 0.0 },
            )
        }
    }

    @Test
    fun tiledRowOffsetMasksWithSharedPlaneAreBitIdenticalToStandalone() {
        // The tiled path shares the plane PER TILE with a non-zero rowOffset feeding the sky
        // position priors -- the shared plane must not perturb that geometry.
        for ((name, frame) in scenes) {
            val luma = FinishingPipeline.sharedLumaPlane(frame, FinishingParams.RENDITION)!!
            val rowOffset = 37
            val imageHeight = frame.height + 200
            assertArrayEquals(
                "$name: offset sky mask must be double-exact",
                SkyMask.compute(frame, rowOffset = rowOffset, imageHeight = imageHeight),
                SkyMask.compute(frame, rowOffset = rowOffset, imageHeight = imageHeight, luma = luma),
                0.0,
            )
            assertArrayEquals(
                "$name: offset overcast mask must be double-exact",
                OvercastSkyMask.compute(frame, rowOffset = rowOffset, imageHeight = imageHeight),
                OvercastSkyMask.compute(frame, rowOffset = rowOffset, imageHeight = imageHeight, luma = luma),
                0.0,
            )
        }
    }

    @Test
    fun localToneMapperWithSharedPlaneIsByteIdenticalToStandalone() {
        // LocalToneMapper's input IS the denoised state in both finishing paths, so it reads
        // the same shared plane; with and without it the output frame must be byte-exact,
        // both bare and under a skin modulation plane.
        for ((name, frame) in scenes) {
            val luma = FinishingPipeline.sharedLumaPlane(frame, FinishingParams.RENDITION)!!
            val params = FinishingPipeline.localToneParams(FinishingParams.REF_LOCAL_CONTRAST)
            assertArrayEquals(
                "$name: local tone output must be byte-exact",
                LocalToneMapper.apply(frame, params).argb,
                LocalToneMapper.apply(frame, params, luma = luma).argb,
            )
            val modulation = FinishingPipeline.skinModulation(frame, FinishingParams.RENDITION, luma)
            assertArrayEquals(
                "$name: modulated local tone output must be byte-exact",
                LocalToneMapper.apply(frame, params, modulation).argb,
                LocalToneMapper.apply(frame, params, modulation, luma).argb,
            )
        }
    }

    @Test
    fun planeIsOnlyComputedWhenAtLeastTwoPassesConsumeIt() {
        val frame = scenes[0].second
        // Skin protection alone is a single extraction pass: sharing would trade one pass
        // for one pass plus the plane's memory, so the helper opts out.
        val skinOnly = FinishingParams.DEFAULT.copy(localContrast = 0.0)
        assertNull("one consumer must not trigger the shared plane", FinishingPipeline.sharedLumaPlane(frame, skinOnly))
        // Local contrast alone likewise.
        val localOnly = FinishingParams.DEFAULT.copy(skinProtection = 0.0)
        assertNull("one consumer must not trigger the shared plane", FinishingPipeline.sharedLumaPlane(frame, localOnly))
        // DEFAULT runs skin protection AND local contrast (two passes); RENDITION adds the
        // four semantic-mask passes.
        assertNotNull("DEFAULT shares across two passes", FinishingPipeline.sharedLumaPlane(frame, FinishingParams.DEFAULT))
        assertNotNull("RENDITION shares across six passes", FinishingPipeline.sharedLumaPlane(frame, FinishingParams.RENDITION))
    }
}
