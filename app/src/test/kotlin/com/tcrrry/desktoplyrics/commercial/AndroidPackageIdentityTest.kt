package com.tcrrry.desktoplyrics.commercial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPackageIdentityTest {
    @Test
    fun `official signer accepts normalized matching sha256`() {
        val digest = "ab".repeat(32)
        val colonSeparated = digest.uppercase().chunked(2).joinToString(":")

        assertTrue(
            OfficialPackageIdentityPolicy.matches(
                expectedSha256 = colonSeparated,
                actualSha256 = setOf("cd".repeat(32), digest)
            )
        )
        assertEquals(digest, OfficialPackageIdentityPolicy.normalizeSha256(colonSeparated))
    }

    @Test
    fun `official signer rejects missing malformed and different digests`() {
        val official = "ab".repeat(32)

        assertFalse(OfficialPackageIdentityPolicy.matches("", setOf(official)))
        assertFalse(OfficialPackageIdentityPolicy.matches("not-a-digest", setOf(official)))
        assertFalse(
            OfficialPackageIdentityPolicy.matches(
                official,
                setOf("cd".repeat(32))
            )
        )
    }
}
