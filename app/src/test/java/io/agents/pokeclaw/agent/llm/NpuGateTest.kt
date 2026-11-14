package io.agents.pokeclaw.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NpuGateTest {

    private val allLibs = NpuGate.CORE_LIBS + listOf(
        "libQnnHtpV73Stub.so", "libQnnHtpV73Skel.so", "libQnnHtpV75Stub.so", "libQnnHtpV75Skel.so",
        "libQnnHtpV79Stub.so", "libQnnHtpV79Skel.so",
    )

    @Test
    fun parsesChipFromFileNames() {
        assertEquals("SM8635", NpuGate.targetSocFromFileName("/data/x/gemma-4-E2B-it_NPU_SM8635.litertlm"))
        assertEquals("QCS8275", NpuGate.targetSocFromFileName("gemma-4-E2B-it_qualcomm_qcs8275.litertlm"))
        assertEquals("SM8750", NpuGate.targetSocFromFileName("Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm"))
        assertNull(NpuGate.targetSocFromFileName("gemma-4-E2B-it.litertlm"))
    }

    @Test
    fun npuBuildDetection() {
        assertTrue(NpuGate.isNpuBuild("gemma-4-E2B-it_NPU_SM8635.litertlm"))
        assertTrue(NpuGate.isNpuBuild("gemma-4-E2B-it_NPU_anything.litertlm"))
        assertTrue(NpuGate.isNpuBuild("gemma-4-E2B-it_Qualcomm_SM8650.litertlm"))
        assertFalse(NpuGate.isNpuBuild("gemma-4-E2B-it.litertlm"))
    }

    @Test
    fun matchingChipWithLibsIsTried() {
        val d = NpuGate.decide("gemma-4-E2B-it_NPU_SM8635.litertlm", false, "SM8635", allLibs)
        assertTrue(d.reason, d.tryNpu)
    }

    @Test
    fun otherSnapdragonNpuBuildIsNoLongerBlocked() {
        // Old gate only accepted "_NPU_SM8635"; an SM8650 file on an SM8650 phone must be tried.
        val d = NpuGate.decide("gemma-4-E2B-it_NPU_SM8650.litertlm", false, "SM8650", allLibs)
        assertTrue(d.reason, d.tryNpu)
    }

    @Test
    fun chipMismatchIsSkippedWithReason() {
        val d = NpuGate.decide("gemma-4-E2B-it_NPU_SM8650.litertlm", false, "SM8635", allLibs)
        assertFalse(d.tryNpu)
        assertTrue(d.reason, d.reason.contains("SM8650") && d.reason.contains("SM8635"))
    }

    @Test
    fun unknownChipIsTriedWithFallback() {
        assertTrue(NpuGate.decide("gemma-4-E2B-it_NPU_SM8635.litertlm", false, null, allLibs).tryNpu)
        assertTrue(NpuGate.decide("gemma-4-E2B-it_NPU_custom.litertlm", false, "SM8635", allLibs).tryNpu)
    }

    @Test
    fun genericFileOnlyWhenUserAsksForNpu() {
        assertFalse(NpuGate.decide("gemma-4-E2B-it.litertlm", false, "SM8635", allLibs).tryNpu)
        assertTrue(NpuGate.decide("gemma-4-E2B-it.litertlm", true, "SM8635", allLibs).tryNpu)
    }

    @Test
    fun missingLibsBlockAndAreNamed() {
        val d = NpuGate.decide("gemma-4-E2B-it_NPU_SM8635.litertlm", false, "SM8635", NpuGate.CORE_LIBS)
        assertFalse(d.tryNpu)
        assertTrue(d.reason, d.reason.contains("libQnnHtpV73Skel.so"))
        val none = NpuGate.decide("gemma-4-E2B-it_NPU_SM8635.litertlm", false, "SM8635", emptyList())
        assertTrue(none.reason, none.reason.contains("libLiteRtDispatch_Qualcomm.so"))
    }

    @Test
    fun unknownChipNeedsSomeStubSkel() {
        val d = NpuGate.decide("model_NPU_x.litertlm", false, "SM9999", NpuGate.CORE_LIBS)
        assertFalse(d.tryNpu)
        assertTrue(NpuGate.decide("model_NPU_x.litertlm", false, "SM9999", allLibs).tryNpu)
    }
}
