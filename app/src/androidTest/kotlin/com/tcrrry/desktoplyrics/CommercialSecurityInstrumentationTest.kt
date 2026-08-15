package com.tcrrry.desktoplyrics

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tcrrry.desktoplyrics.commercial.AndroidDeviceIdentityManager
import com.tcrrry.desktoplyrics.commercial.AndroidSecureCommercialStore
import com.tcrrry.desktoplyrics.commercial.CommercialAccessDecision
import com.tcrrry.desktoplyrics.commercial.CommercialDigests
import com.tcrrry.desktoplyrics.commercial.CommercialRuntimeFactory
import com.tcrrry.desktoplyrics.commercial.CommercialSignatureMessages
import com.tcrrry.desktoplyrics.commercial.CommercialTier
import com.tcrrry.desktoplyrics.commercial.DebugCommercialRuntime
import com.tcrrry.desktoplyrics.commercial.DebugEntitlementScenario
import com.tcrrry.desktoplyrics.commercial.EntitlementQueryResult
import com.tcrrry.desktoplyrics.commercial.SecureCommercialRecord
import com.tcrrry.desktoplyrics.commercial.SecureStoreReadResult
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class CommercialSecurityInstrumentationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store = AndroidSecureCommercialStore(
        context = context,
        keyAlias = TEST_STORAGE_ALIAS,
        filePrefix = TEST_FILE_PREFIX
    )

    @After
    fun cleanTestStorage() {
        SecureCommercialRecord.entries.forEach(store::delete)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(TEST_STORAGE_ALIAS)
    }

    @Test
    fun deviceKeyExportsSpkiAndSignsRawChallenge() {
        val manager = AndroidDeviceIdentityManager(context)
        val identity = manager.loadOrCreate()
        val spki = Base64.getDecoder().decode(identity.publicKeySpkiBase64)
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
        val challenge = "instrumentation-challenge".toByteArray()
        val signature = manager.signChallenge(challenge)

        val verified = Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(CommercialSignatureMessages.challenge(challenge))
            verify(signature)
        }

        assertTrue(verified)
        assertEquals(CommercialDigests.sha256Hex(spki), identity.publicKeySha256)
        assertEquals(64, identity.deviceFingerprintSha256.length)
    }

    @Test
    fun encryptedRecordsStayInNoBackupStorageAndDoNotContainPlaintext() {
        val plaintext = "commercial-secret-value".toByteArray()

        assertTrue(store.write(SecureCommercialRecord.DEVICE_TOKEN, plaintext))
        val storedFile = store.storageFile(SecureCommercialRecord.DEVICE_TOKEN)
        assertTrue(storedFile.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
        assertFalse(storedFile.readBytes().toString(Charsets.UTF_8).contains("commercial-secret-value"))
        val restored = store.read(SecureCommercialRecord.DEVICE_TOKEN)
        assertTrue(restored is SecureStoreReadResult.Value)
        assertArrayEquals(plaintext, (restored as SecureStoreReadResult.Value).bytes)
    }

    @Test
    fun debugFixtureProducesAValidSignedTrialForTheRuntimeGate() {
        val runtime: DebugCommercialRuntime = CommercialRuntimeFactory.debugRuntime(context)
        runtime.selectEntitlementScenario(DebugEntitlementScenario.TRIAL)
        val query = runBlocking {
            runtime.gateway.queryEntitlement(System.currentTimeMillis())
        }
        assertTrue(query is EntitlementQueryResult.Ready)

        val access = runtime.accessGate.evaluate(System.currentTimeMillis())

        assertTrue(access is CommercialAccessDecision.Allowed)
        assertEquals(CommercialTier.TRIAL, (access as CommercialAccessDecision.Allowed).tier)
    }

    private companion object {
        const val TEST_STORAGE_ALIAS = "03lyrics_test_commercial_storage_key_v1"
        const val TEST_FILE_PREFIX = "commercial_test_secure_v1_"
    }
}
