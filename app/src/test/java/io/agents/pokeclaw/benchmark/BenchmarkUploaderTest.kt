package io.agents.pokeclaw.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkUploaderTest {
    @Test fun escapesQuotesNewlinesAndControlChars() {
        assertEquals("\"a\\\"b\\nc\\\\d\\u0001\"", BenchmarkUploader.q("a\"b\nc\\d\u0001"))
    }

    @Test fun payloadCarriesTokenTesterAndEveryFile() {
        val body = BenchmarkUploader.payload("tok", "Asha", "vivo I2221", listOf(
            BenchmarkUploader.Part("runs.jsonl", "{\"x\":1}\n{\"x\":2}\n"),
            BenchmarkUploader.Part("summary.csv", "a,b\n1,2\n", "text/csv"),
        ))
        assertTrue(body.startsWith("{\"token\":\"tok\",\"tester\":\"Asha\",\"device\":\"vivo I2221\",\"files\":["))
        assertTrue(body.contains("{\"name\":\"runs.jsonl\",\"mime\":\"text/plain\",\"content\":\"{\\\"x\\\":1}\\n{\\\"x\\\":2}\\n\"}"))
        assertTrue(body.contains("\"name\":\"summary.csv\",\"mime\":\"text/csv\""))
        assertTrue(body.endsWith("]}"))
    }

    @Test fun keepsUnicodeAsIs() {
        assertEquals("\"₹128 टमाटर\"", BenchmarkUploader.q("₹128 टमाटर"))
    }
}
