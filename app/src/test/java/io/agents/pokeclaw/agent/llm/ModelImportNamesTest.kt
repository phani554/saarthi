package io.agents.pokeclaw.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelImportNamesTest {

    @Test
    fun keepsPlainLitertlmName() {
        assertEquals(
            "gemma-4-E2B-it_qualcomm_sm8635.litertlm",
            ModelImportNames.targetFileName("gemma-4-E2B-it_qualcomm_sm8635.litertlm")
        )
    }

    @Test
    fun acceptsUpperCaseExtension() {
        assertEquals("model.litertlm", ModelImportNames.targetFileName("model.LITERTLM"))
    }

    @Test
    fun rejectsOtherFiles() {
        assertNull(ModelImportNames.targetFileName("model.task"))
        assertNull(ModelImportNames.targetFileName("model.litertlm.zip"))
        assertNull(ModelImportNames.targetFileName(null))
        assertNull(ModelImportNames.targetFileName(".litertlm"))
    }

    @Test
    fun stripsPathsAndOddCharacters() {
        assertEquals("evil.litertlm", ModelImportNames.targetFileName("../../evil.litertlm"))
        assertEquals("my_model__1_.litertlm", ModelImportNames.targetFileName("my model (1).litertlm"))
        assertEquals("x.litertlm", ModelImportNames.targetFileName("..x.litertlm"))
    }

    @Test
    fun tempNameIsBesideTarget() {
        assertEquals("a.litertlm.importing", ModelImportNames.tempFileName("a.litertlm"))
    }
}
