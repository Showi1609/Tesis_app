package com.example.tesis

import com.example.tesis.util.calculateSha256
import com.example.tesis.util.isBioCountOriginal
import com.example.tesis.util.sanitizeForFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePipelineTest {

    @Test
    fun `sanitizeForFileName elimina caracteres invalidos`() {
        assertEquals("unknown", sanitizeForFileName(null))
        assertEquals("unknown", sanitizeForFileName(""))
        assertEquals("Finca1", sanitizeForFileName("Finca 1"))
        assertEquals("T-01", sanitizeForFileName("T-01!@#"))
        assertEquals("Invernadero_A", sanitizeForFileName("Invernadero_A"))
    }

    @Test
    fun `isBioCountOriginal detecta correctamente el origen de la imagen`() {
        assertTrue(isBioCountOriginal(null, "BioCount_Finca1_Inv1_T1_A_20261004_120000_123456.jpg"))
        assertFalse(isBioCountOriginal(null, "IMG_20261004_120000.jpg"))
        
        assertTrue(isBioCountOriginal("content://media/external/images/media/123/Pictures/BioCount MIP/IMG.jpg", null))
        assertFalse(isBioCountOriginal("content://media/external/images/media/123/DCIM/Camera/IMG.jpg", null))
    }

    @Test
    fun `calculateSha256 calcula el hash correcto`() {
        // "test" en utf-8
        val bytes = "test".toByteArray()
        val expectedSha256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        assertEquals(expectedSha256, calculateSha256(bytes))
    }
}
