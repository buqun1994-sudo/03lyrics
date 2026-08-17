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
import com.tcrrry.desktoplyrics.commercial.DebugEntitlementScenario
import com.tcrrry.desktoplyrics.commercial.EntitlementQueryResult
import com.tcrrry.desktoplyrics.commercial.SecureCommercialRecord
import com.tcrrry.desktoplyrics.commercial.SecureStoreReadResult
import org.junit.After
import org.junit.Before
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
    private val identityManager = AndroidDeviceIdentityManager(
        context = context,
        secureStore = store,
        baseKeyAlias = TEST_DEVICE_KEY_ALIAS
    )

    @Before
    fun cleanBeforeTest() {
        cleanTestArtifacts()
    }

    @After
    fun cleanAfterTest() {
        cleanTestArtifacts()
    }

    @Test
    fun cleanupContractLeavesNoTestArtifacts() {
        assertTestArtifactsAbsent()
    }

    private fun cleanTestArtifacts() {
        SecureCommercialRecord.entries.forEach { record ->
            assertTrue("Failed to delete test record ${record.name}", store.delete(record))
        }
        context.noBackupFilesDir.listFiles()
            .orEmpty()
            .filter { it.name.startsWith(TEST_FILE_PREFIX) }
            .forEach { file ->
                assertTrue("Failed to delete test file ${file.name}", !file.exists() || file.delete())
            }

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val aliasesToDelete = mutableListOf<String>()
        val aliases = keyStore.aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            if (isTestKeyAlias(alias)) aliasesToDelete += alias
        }
        aliasesToDelete.forEach(keyStore::deleteEntry)
        assertTestArtifactsAbsent(keyStore)
    }

    private fun assertTestArtifactsAbsent(
        keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    ) {
        val remainingFiles = context.noBackupFilesDir.listFiles()
            .orEmpty()
            .filter { it.name.startsWith(TEST_FILE_PREFIX) }
        assertTrue(
            "Test commercial files remain: ${remainingFiles.joinToString { it.name }}",
            remainingFiles.isEmpty()
        )

        val remainingAliases = mutableListOf<String>()
        val aliases = keyStore.aliases()
        while (aliases.hasMoreElements()) {
            aliases.nextElement().takeIf(::isTestKeyAlias)?.let(remainingAliases::add)
        }
        assertTrue(
            "Test AndroidKeyStore aliases remain: ${remainingAliases.joinToString()}",
            remainingAliases.isEmpty()
        )
    }

    @Test
    fun deviceKeyExportsSpkiAndSignsRawChallenge() {
        val identity = identityManager.loadOrCreate()
        val spki = Base64.getDecoder().decode(identity.publicKeySpkiBase64)
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
        val challenge = "instrumentation-challenge".toByteArray()
        val signature = identityManager.signChallenge(challenge)

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
        val runtime = CommercialRuntimeFactory.createIsolatedFixtureRuntime(
            store = store,
            identityProvider = identityManager,
            signerKeyAlias = TEST_FIXTURE_SIGNER_ALIAS
        )
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
        fun isTestKeyAlias(alias: String): Boolean =
            alias == TEST_STORAGE_ALIAS ||
                alias == TEST_FIXTURE_SIGNER_ALIAS ||
                alias == TEST_DEVICE_KEY_ALIAS ||
                alias.startsWith("$TEST_DEVICE_KEY_ALIAS.recovery.")

        const val TEST_STORAGE_ALIAS = "03lyrics_test_commercial_storage_key_v1"
        const val TEST_DEVICE_KEY_ALIAS = "03lyrics_test_device_key_v1"
        const val TEST_FIXTURE_SIGNER_ALIAS = "03lyrics_test_fixture_license_signer_v1"
        const val TEST_FILE_PREFIX = "commercial_test_secure_v1_"
    }
}
