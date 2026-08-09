package com.hzzmonet.zkbomb.domain.validation

import com.hzzmonet.zkbomb.domain.model.BombResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AidlValidatorTest {

    @Test
    fun `valid package name passes validation`() {
        val validPackages = listOf(
            "com.example.app",
            "com.hzzmonet.zkbomb",
            "android",
            "com.android.systemui",
            "a.b.c_d",
        )
        for (pkg in validPackages) {
            val result = AidlValidator.validatePackageName(pkg)
            assertTrue("Package $pkg should be valid", result.isSuccess)
        }
    }

    @Test
    fun `invalid package name fails validation`() {
        val invalidPackages = listOf(
            null,
            "",
            "   ",
            ".leadingDot",
            "trailingDot.",
            "123.startwithdigit",
            "com.example..doubleDot",
            "com/example/slash",
            "com.example.app;injection",
        )
        for (pkg in invalidPackages) {
            val result = AidlValidator.validatePackageName(pkg)
            assertFalse("Package '$pkg' should be invalid", result.isSuccess)
            assertEquals(BombResult.Status.INVALID_ARGUMENT, result.status)
        }
    }

    @Test
    fun `valid userId passes validation`() {
        assertTrue(AidlValidator.validateUserId(0).isSuccess)
        assertTrue(AidlValidator.validateUserId(10).isSuccess)
        assertTrue(AidlValidator.validateUserId(999).isSuccess)
    }

    @Test
    fun `invalid userId fails validation`() {
        val negative = AidlValidator.validateUserId(-1)
        assertFalse(negative.isSuccess)
        assertEquals(BombResult.Status.INVALID_ARGUMENT, negative.status)

        val overflow = AidlValidator.validateUserId(10000)
        assertFalse(overflow.isSuccess)
        assertEquals(BombResult.Status.INVALID_ARGUMENT, overflow.status)
    }

    @Test
    fun `valid pid passes validation`() {
        assertTrue(AidlValidator.validatePid(1).isSuccess)
        assertTrue(AidlValidator.validatePid(12345).isSuccess)
    }

    @Test
    fun `invalid pid fails validation`() {
        assertFalse(AidlValidator.validatePid(0).isSuccess)
        assertFalse(AidlValidator.validatePid(-100).isSuccess)
    }

    @Test
    fun `valid uid passes validation`() {
        assertTrue(AidlValidator.validateUid(0).isSuccess)
        assertTrue(AidlValidator.validateUid(1000).isSuccess)
        assertTrue(AidlValidator.validateUid(10123).isSuccess)
    }

    @Test
    fun `invalid uid fails validation`() {
        assertFalse(AidlValidator.validateUid(-1).isSuccess)
    }

    @Test
    fun `freeze state validation accepts valid modes and rejects unknown`() {
        assertTrue(AidlValidator.validateFreezeState("NORMAL").isSuccess)
        assertTrue(AidlValidator.validateFreezeState("SOFT_FREEZE").isSuccess)
        assertTrue(AidlValidator.validateFreezeState("deep_freeze").isSuccess)
        assertTrue(AidlValidator.validateFreezeState("DISABLED").isSuccess)

        val invalid = AidlValidator.validateFreezeState("MAGIC_FREEZE")
        assertFalse(invalid.isSuccess)
        assertEquals(BombResult.Status.INVALID_ARGUMENT, invalid.status)
    }

    @Test
    fun `log level validation accepts valid levels and rejects unknown`() {
        assertTrue(AidlValidator.validateLogLevel("DEFAULT").isSuccess)
        assertTrue(AidlValidator.validateLogLevel("REDUCED").isSuccess)
        assertTrue(AidlValidator.validateLogLevel("OFF").isSuccess)

        val invalid = AidlValidator.validateLogLevel("VERBOSE_ALL")
        assertFalse(invalid.isSuccess)
        assertEquals(BombResult.Status.INVALID_ARGUMENT, invalid.status)
    }

    @Test
    fun `audit rate validation bounds rate`() {
        assertTrue(AidlValidator.validateAuditRate(-1).isSuccess)
        assertTrue(AidlValidator.validateAuditRate(0).isSuccess)
        assertTrue(AidlValidator.validateAuditRate(100).isSuccess)

        assertFalse(AidlValidator.validateAuditRate(-5).isSuccess)
    }
}
