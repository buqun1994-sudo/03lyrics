package com.tcrrry.desktoplyrics.commercial

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class CloudDeviceCommercialGatewayTest {
    @Test
    fun `fixture starts online trial and automatically binds staging campaign quote`() = runBlocking {
        val fixture = FixtureHarness()

        val result = fixture.gateway.queryEntitlement(fixture.now) as EntitlementQueryResult.Ready
        val trial = result.snapshot.entitlement as EntitlementState.Trial
        val quote = requireNotNull(result.snapshot.quote)

        assertEquals(TRIAL_DURATION_MS, trial.remainingMillis)
        assertEquals(2, quote.originalAmountCents)
        assertEquals("¥0.02", quote.originalPrice.text)
        assertEquals(5_000, quote.paymentRatioBps)
        assertEquals("5折", quote.discountLabel)
        assertEquals(1, quote.calculatedAmountCents)
        assertEquals(1, quote.finalAmountCents)
        assertEquals("¥0.01", quote.finalPrice.text)
        assertFalse(quote.minimumChargeApplied)
        assertEquals(setOf(PaymentMethod.WECHAT, PaymentMethod.ALIPAY), quote.availablePaymentMethods)
    }

    @Test
    fun `fixture quotes cover no campaign invalid expired unavailable production and zero ratio`() =
        runBlocking {
            val fixture = FixtureHarness()
            fixture.transport.campaignAvailable = false
            val noCampaign = fixture.gateway.queryEntitlement(fixture.now)
                as EntitlementQueryResult.Ready
            assertEquals(DiscountResolution.NONE, noCampaign.snapshot.quote?.discountResolution)
            assertEquals(2, noCampaign.snapshot.quote?.finalAmountCents)

            val invalid = fixture.gateway.requestQuote("not valid", fixture.now)
                as QuoteRequestResult.Ready
            assertEquals(DiscountResolution.INVALID, invalid.quote.discountResolution)
            assertEquals(invalid.quote.originalAmountCents, invalid.quote.finalAmountCents)

            val expired = fixture.gateway.requestQuote(
                DebugCommercialFixtureCatalog.EXPIRED_DISCOUNT_CODE,
                fixture.now
            ) as QuoteRequestResult.Ready
            assertEquals(DiscountResolution.EXPIRED, expired.quote.discountResolution)

            val unavailable = fixture.gateway.requestQuote(
                DebugCommercialFixtureCatalog.UNAVAILABLE_DISCOUNT_CODE,
                fixture.now
            ) as QuoteRequestResult.Ready
            assertEquals(DiscountResolution.UNAVAILABLE, unavailable.quote.discountResolution)

            fixture.transport.pricing = DebugCommercialFixtureCatalog.production
            val production = fixture.gateway.requestQuote(
                DebugCommercialFixtureCatalog.PUBLIC_CAMPAIGN_CODE,
                fixture.now
            ) as QuoteRequestResult.Ready
            assertEquals(4_900, production.quote.originalAmountCents)
            assertEquals(2_940, production.quote.finalAmountCents)

            fixture.transport.pricing = DebugCommercialFixtureCatalog.zeroRatio
            val zero = fixture.gateway.requestQuote(
                DebugCommercialFixtureCatalog.PUBLIC_CAMPAIGN_CODE,
                fixture.now
            ) as QuoteRequestResult.Ready
            assertEquals(0, zero.quote.calculatedAmountCents)
            assertEquals(1, zero.quote.finalAmountCents)
            assertTrue(zero.quote.minimumChargeApplied)
        }

    @Test
    fun `wechat and alipay sessions use the exact quote final amount`() = runBlocking {
        val fixture = FixtureHarness()
        val quote = (fixture.gateway.queryEntitlement(fixture.now) as EntitlementQueryResult.Ready)
            .snapshot.quote!!

        val wechat = fixture.gateway.createPayment(quote, PaymentMethod.WECHAT, fixture.now)
            as PaymentCreationResult.Ready
        val alipay = fixture.gateway.createPayment(quote, PaymentMethod.ALIPAY, fixture.now)
            as PaymentCreationResult.Ready

        assertEquals(quote.finalAmountCents, wechat.session.finalAmountCents)
        assertEquals(quote.finalPrice, wechat.session.finalAmount)
        assertEquals(quote.finalAmountCents, alipay.session.finalAmountCents)
        assertEquals(quote.finalPrice, alipay.session.finalAmount)
    }

    @Test
    fun `quote changed returns latest quote and second confirmation can create order`() = runBlocking {
        val fixture = FixtureHarness()
        val quote = (fixture.gateway.queryEntitlement(fixture.now) as EntitlementQueryResult.Ready)
            .snapshot.quote!!
        fixture.transport.paymentOutcome = DebugPaymentOutcome.QUOTE_CHANGED

        val changed = fixture.gateway.createPayment(quote, PaymentMethod.WECHAT, fixture.now)
            as PaymentCreationResult.QuoteChanged
        assertEquals(DiscountResolution.NONE, changed.latestQuote.discountResolution)
        assertEquals(2, changed.latestQuote.finalAmountCents)

        val confirmed = fixture.gateway.createPayment(
            changed.latestQuote,
            PaymentMethod.WECHAT,
            fixture.now
        )
        assertTrue(confirmed is PaymentCreationResult.Ready)
    }

    @Test
    fun `paid poll stores license and device token then enables local pro access`() = runBlocking {
        val fixture = FixtureHarness()
        val quote = (fixture.gateway.queryEntitlement(fixture.now) as EntitlementQueryResult.Ready)
            .snapshot.quote!!
        val payment = fixture.gateway.createPayment(quote, PaymentMethod.WECHAT, fixture.now)
            as PaymentCreationResult.Ready
        fixture.transport.paymentOutcome = DebugPaymentOutcome.PAID

        assertEquals(
            PaymentStatusResult.Paid,
            fixture.gateway.refreshPayment(payment.session, fixture.now)
        )
        assertTrue(fixture.store.read(SecureCommercialRecord.DEVICE_TOKEN) is SecureStoreReadResult.Value)
        assertTrue(fixture.store.read(SecureCommercialRecord.LICENSE) is SecureStoreReadResult.Value)
        assertTrue(fixture.licenseRepository.accessDecision(fixture.now) is CommercialAccessDecision.Allowed)

        val query = fixture.gateway.queryEntitlement(fixture.now) as EntitlementQueryResult.Ready
        assertEquals(EntitlementState.Pro, query.snapshot.entitlement)
        assertEquals(null, query.snapshot.quote)
        assertEquals(
            PaymentCreationResult.AlreadyOwned,
            fixture.gateway.createPayment(quote, PaymentMethod.WECHAT, fixture.now)
        )
    }

    @Test
    fun `refund revocation atomically replaces old pro with the original trial`() =
        runBlocking {
            val fixture = FixtureHarness()
            val initial = fixture.gateway.queryEntitlement(fixture.now)
                as EntitlementQueryResult.Ready
            val originalTrialEnd = (initial.snapshot.entitlement as EntitlementState.Trial)
                .expiresAtEpochMs
            val payment = fixture.gateway.createPayment(
                initial.snapshot.quote!!,
                PaymentMethod.WECHAT,
                fixture.now
            ) as PaymentCreationResult.Ready
            fixture.transport.paymentOutcome = DebugPaymentOutcome.PAID
            assertEquals(
                PaymentStatusResult.Paid,
                fixture.gateway.refreshPayment(payment.session, fixture.now)
            )

            fixture.store.failedDeletes += SecureCommercialRecord.LICENSE
            fixture.transport.entitlementScenario = DebugEntitlementScenario.REVOKED
            val refreshed = fixture.gateway.forceRefreshAccess(fixture.now)
                as CommercialAccessRefreshResult.Ready
            assertEquals(
                originalTrialEnd,
                (refreshed.entitlement as EntitlementState.Trial).expiresAtEpochMs
            )
            assertTrue(
                fixture.store.read(SecureCommercialRecord.LICENSE) is SecureStoreReadResult.Value
            )
            assertEquals(
                SecureStoreReadResult.Missing,
                fixture.store.read(SecureCommercialRecord.ACCESS_REVOCATION)
            )
            assertTrue(
                fixture.licenseRepository.accessDecision(fixture.now) is
                    CommercialAccessDecision.Allowed
            )

            val repeated = fixture.gateway.forceRefreshAccess(fixture.now)
                as CommercialAccessRefreshResult.Ready
            assertEquals(
                originalTrialEnd,
                (repeated.entitlement as EntitlementState.Trial).expiresAtEpochMs
            )
            assertEquals(
                SecureStoreReadResult.Missing,
                fixture.store.read(SecureCommercialRecord.ACCESS_REVOCATION)
            )
            assertTrue(
                fixture.licenseRepository.accessDecision(fixture.now) is
                    CommercialAccessDecision.Allowed
            )
        }

    @Test
    fun `refund trial replacement stays revoked until the new license is fully persisted`() =
        runBlocking {
            val fixture = FixtureHarness()
            val initial = fixture.gateway.queryEntitlement(fixture.now)
                as EntitlementQueryResult.Ready
            val payment = fixture.gateway.createPayment(
                initial.snapshot.quote!!,
                PaymentMethod.WECHAT,
                fixture.now
            ) as PaymentCreationResult.Ready
            fixture.transport.paymentOutcome = DebugPaymentOutcome.PAID
            assertEquals(
                PaymentStatusResult.Paid,
                fixture.gateway.refreshPayment(payment.session, fixture.now)
            )

            fixture.store.failedDeletes += SecureCommercialRecord.ACCESS_REVOCATION
            fixture.transport.entitlementScenario = DebugEntitlementScenario.REVOKED

            assertEquals(
                CommercialAccessRefreshResult.Failure(
                    CommercialFailure.ENTITLEMENT_REVOKED
                ),
                fixture.gateway.forceRefreshAccess(fixture.now)
            )
            assertEquals(
                CommercialAccessDecision.Denied(
                    CommercialAccessDenial.ENTITLEMENT_REVOKED
                ),
                fixture.licenseRepository.accessDecision(fixture.now)
            )
        }

    @Test
    fun `refund after the original seven day window cannot restore trial access`() = runBlocking {
        val fixture = FixtureHarness()
        val initial = fixture.gateway.queryEntitlement(fixture.now)
            as EntitlementQueryResult.Ready
        val payment = fixture.gateway.createPayment(
            initial.snapshot.quote!!,
            PaymentMethod.WECHAT,
            fixture.now
        ) as PaymentCreationResult.Ready
        fixture.transport.paymentOutcome = DebugPaymentOutcome.PAID
        assertEquals(
            PaymentStatusResult.Paid,
            fixture.gateway.refreshPayment(payment.session, fixture.now)
        )

        fixture.now += TRIAL_DURATION_MS + 1
        fixture.transport.entitlementScenario = DebugEntitlementScenario.REVOKED
        val refreshed = fixture.gateway.queryEntitlement(fixture.now)
            as EntitlementQueryResult.Ready

        assertEquals(EntitlementState.Expired, refreshed.snapshot.entitlement)
        assertTrue(
            fixture.licenseRepository.accessDecision(fixture.now) is
                CommercialAccessDecision.Denied
        )
    }

    @Test
    fun `same fingerprint reinstall automatically recovers pro during entitlement query`() =
        runBlocking {
            val fixture = FixtureHarness()
            val quote = (fixture.gateway.queryEntitlement(fixture.now) as EntitlementQueryResult.Ready)
                .snapshot.quote!!
            val payment = fixture.gateway.createPayment(quote, PaymentMethod.WECHAT, fixture.now)
                as PaymentCreationResult.Ready
            fixture.transport.paymentOutcome = DebugPaymentOutcome.PAID
            assertEquals(
                PaymentStatusResult.Paid,
                fixture.gateway.refreshPayment(payment.session, fixture.now)
            )

            val reinstalled = fixture.newClientWithSameFingerprint()
            val restored = reinstalled.gateway.queryEntitlement(reinstalled.now)
                as EntitlementQueryResult.Ready

            assertEquals(EntitlementState.Pro, restored.snapshot.entitlement)
            assertFalse(reinstalled.identity.previousSignatureUsed)
            assertTrue(
                reinstalled.store.read(SecureCommercialRecord.LICENSE) is SecureStoreReadResult.Value
            )
            assertTrue(
                reinstalled.store.read(SecureCommercialRecord.DEVICE_TOKEN) is
                    SecureStoreReadResult.Value
            )
        }

    @Test
    fun `network failure preserves valid local license and first trial cannot start offline`() =
        runBlocking {
            val fixture = FixtureHarness()
            val initial = fixture.gateway.queryEntitlement(fixture.now) as EntitlementQueryResult.Ready
            assertTrue(initial.snapshot.entitlement is EntitlementState.Trial)

            fixture.transport.entitlementScenario = DebugEntitlementScenario.QUERY_ERROR
            val offline = fixture.gateway.refreshAccess(fixture.now)
                as CommercialAccessRefreshResult.Ready
            assertTrue(offline.entitlement is EntitlementState.Trial)

            val freshOffline = FixtureHarness().apply {
                transport.entitlementScenario = DebugEntitlementScenario.QUERY_ERROR
            }
            val firstStart = freshOffline.gateway.refreshAccess(freshOffline.now)
            assertEquals(
                CommercialAccessRefreshResult.Failure(CommercialFailure.NETWORK),
                firstStart
            )
            assertFalse(
                freshOffline.store.read(SecureCommercialRecord.LICENSE) is SecureStoreReadResult.Value
            )
        }

    @Test
    fun `pro refresh is deferred until signed renewal and transient failures cool down for one day`() =
        runBlocking {
            val fixture = FixtureHarness()
            val initial = fixture.gateway.queryEntitlement(fixture.now)
                as EntitlementQueryResult.Ready
            val payment = fixture.gateway.createPayment(
                initial.snapshot.quote!!,
                PaymentMethod.WECHAT,
                fixture.now
            ) as PaymentCreationResult.Ready
            fixture.transport.paymentOutcome = DebugPaymentOutcome.PAID
            assertEquals(
                PaymentStatusResult.Paid,
                fixture.gateway.refreshPayment(payment.session, fixture.now)
            )

            fixture.transport.entitlementScenario = DebugEntitlementScenario.REVOKED
            fixture.now += 7L * 24 * 60 * 60 * 1000 - 1
            assertEquals(
                CommercialAccessRefreshResult.Ready(EntitlementState.Pro),
                fixture.gateway.refreshAccess(fixture.now)
            )

            fixture.now += 1
            fixture.transport.entitlementScenario = DebugEntitlementScenario.QUERY_ERROR
            assertEquals(
                CommercialAccessRefreshResult.Ready(EntitlementState.Pro),
                fixture.gateway.refreshAccess(fixture.now)
            )

            fixture.transport.entitlementScenario = DebugEntitlementScenario.REVOKED
            fixture.now += 60 * 60 * 1000L
            assertEquals(
                CommercialAccessRefreshResult.Ready(EntitlementState.Pro),
                fixture.gateway.refreshAccess(fixture.now)
            )

            fixture.now += 23L * 60 * 60 * 1000
            assertEquals(
                CommercialAccessRefreshResult.Ready(EntitlementState.Expired),
                fixture.gateway.refreshAccess(fixture.now)
            )
        }

    @Test
    fun `lost first paid device token is repaired through same device recovery`() = runBlocking {
        val fixture = FixtureHarness()
        val quote = (fixture.gateway.queryEntitlement(fixture.now) as EntitlementQueryResult.Ready)
            .snapshot.quote!!
        val payment = fixture.gateway.createPayment(quote, PaymentMethod.WECHAT, fixture.now)
            as PaymentCreationResult.Ready
        fixture.transport.paymentOutcome = DebugPaymentOutcome.PAID
        fixture.transport.omitFirstPaidDeviceToken = true

        assertEquals(
            PaymentStatusResult.Paid,
            fixture.gateway.refreshPayment(payment.session, fixture.now)
        )
        assertTrue(fixture.identity.previousSignatureUsed)
        assertTrue(
            fixture.store.read(SecureCommercialRecord.DEVICE_TOKEN) is SecureStoreReadResult.Value
        )
    }

    @Test
    fun `trial repeat and same fingerprint recovery do not reset original trial end`() = runBlocking {
        val fixture = FixtureHarness()
        fixture.transport.recoveryGrantsPro = false
        val first = fixture.gateway.queryEntitlement(fixture.now) as EntitlementQueryResult.Ready
        val originalEnd = (first.snapshot.entitlement as EntitlementState.Trial).expiresAtEpochMs

        fixture.now += 60 * 60 * 1000L
        val repeated = fixture.gateway.queryEntitlement(fixture.now) as EntitlementQueryResult.Ready
        assertEquals(
            originalEnd,
            (repeated.snapshot.entitlement as EntitlementState.Trial).expiresAtEpochMs
        )

        val recovered = fixture.gateway.restorePurchase(fixture.now)
            as PurchaseRecoveryResult.Success
        assertTrue(fixture.identity.previousSignatureUsed)
        assertEquals(
            originalEnd,
            (recovered.entitlement as EntitlementState.Trial).expiresAtEpochMs
        )

        val reinstalled = fixture.newClientWithSameFingerprint()
        val withoutOldKey = reinstalled.gateway.queryEntitlement(fixture.now)
            as EntitlementQueryResult.Ready
        assertFalse(reinstalled.identity.previousSignatureUsed)
        assertEquals(
            originalEnd,
            (withoutOldKey.snapshot.entitlement as EntitlementState.Trial).expiresAtEpochMs
        )
    }

    @Test
    fun `automatic recovery keeps same fingerprint rejection fail closed`() = runBlocking {
        val fixture = FixtureHarness()
        fixture.gateway.queryEntitlement(fixture.now)
        fixture.transport.recoveryScenario = DebugRecoveryScenario.DIFFERENT_DEVICE

        val reinstalled = fixture.newClientWithSameFingerprint()

        assertEquals(
            EntitlementQueryResult.Failure(CommercialFailure.DEVICE_MISMATCH),
            reinstalled.gateway.queryEntitlement(reinstalled.now)
        )
        assertFalse(
            reinstalled.store.read(SecureCommercialRecord.LICENSE) is SecureStoreReadResult.Value
        )
    }

    @Test
    fun `different fingerprint recovery is rejected and expired quote is not ordered`() =
        runBlocking {
            val fixture = FixtureHarness()
            val quote = (fixture.gateway.queryEntitlement(fixture.now) as EntitlementQueryResult.Ready)
                .snapshot.quote!!

            val otherDevice = fixture.newClientWithFingerprint("b".repeat(64))
            assertEquals(
                PurchaseRecoveryResult.Failure(CommercialFailure.DEVICE_MISMATCH),
                otherDevice.gateway.restorePurchase(fixture.now)
            )

            fixture.now = quote.expiresAtEpochMs
            assertEquals(
                PaymentCreationResult.QuoteExpired,
                fixture.gateway.createPayment(quote, PaymentMethod.WECHAT, fixture.now)
            )
        }

    private class FixtureHarness private constructor(private val bundle: FixtureBundle) {
        val signer: TestFixtureSigner = bundle.signer
        val transport: FixtureDeviceCommerceTransport = bundle.transport
        val store: MapSecureStore = bundle.store
        val identity: TestIdentityProvider = bundle.identity
        val licenseRepository: CommercialLicenseRepository = bundle.licenseRepository
        val gateway: CloudDeviceCommercialGateway = bundle.gateway

        var now: Long
            get() = bundle.clock.value
            set(value) {
                bundle.clock.value = value
            }

        constructor() : this(createBundle())

        fun newClientWithSameFingerprint(): FixtureHarness = newClientWithFingerprint(
            identity.fingerprint
        )

        fun newClientWithFingerprint(fingerprint: String): FixtureHarness {
            val newStore = MapSecureStore()
            val newIdentity = TestIdentityProvider(fingerprint)
            val repository = CommercialLicenseRepository(
                store = newStore,
                identityProvider = newIdentity,
                trust = DeviceCommerceLicenseTrust(signer.keyId, signer.publicKey())
            )
            return FixtureHarness(
                FixtureBundle(
                    clock = bundle.clock,
                    signer = signer,
                    transport = transport,
                    store = newStore,
                    identity = newIdentity,
                    licenseRepository = repository,
                    gateway = CloudDeviceCommercialGateway(
                        api = DeviceCommerceJsonApi(transport),
                        identityProvider = newIdentity,
                        store = newStore,
                        trialRepository = FirstOpenTrialRepository(newStore),
                        licenseRepository = repository,
                        clientVersion = "test"
                    )
                )
            )
        }

        companion object {
            private fun createBundle(): FixtureBundle {
                val clock = TestClock(1_700_000_000_000L)
                val signer = TestFixtureSigner()
                val transport = FixtureDeviceCommerceTransport(signer) { clock.value }
                val store = MapSecureStore()
                val identity = TestIdentityProvider("a".repeat(64))
                val repository = CommercialLicenseRepository(
                    store = store,
                    identityProvider = identity,
                    trust = DeviceCommerceLicenseTrust(signer.keyId, signer.publicKey())
                )
                return FixtureBundle(
                    clock = clock,
                    signer = signer,
                    transport = transport,
                    store = store,
                    identity = identity,
                    licenseRepository = repository,
                    gateway = CloudDeviceCommercialGateway(
                        api = DeviceCommerceJsonApi(transport),
                        identityProvider = identity,
                        store = store,
                        trialRepository = FirstOpenTrialRepository(store),
                        licenseRepository = repository,
                        clientVersion = "test"
                    )
                )
            }
        }
    }

    private data class TestClock(var value: Long)

    private data class FixtureBundle(
        val clock: TestClock,
        val signer: TestFixtureSigner,
        val transport: FixtureDeviceCommerceTransport,
        val store: MapSecureStore,
        val identity: TestIdentityProvider,
        val licenseRepository: CommercialLicenseRepository,
        val gateway: CloudDeviceCommercialGateway
    )

    private class TestFixtureSigner : FixtureLicenseSigner {
        private val keys = generateKeys()
        override val keyId: String = DebugCommercialFixtureCatalog.LICENSE_KEY_ID
        override fun publicKey() = keys.public
        override fun sign(payload: ByteArray): ByteArray = signWith(keys, payload)
    }

    private class TestIdentityProvider(val fingerprint: String) : DeviceIdentityProvider {
        private var currentKeys = generateKeys()
        var previousSignatureUsed = false
            private set

        override fun loadOrCreate(): DeviceCommercialIdentity = identity(currentKeys)

        override fun signChallenge(challenge: ByteArray): ByteArray = signWith(
            currentKeys,
            challenge
        )

        override fun signPurchasePoll(input: PurchasePollProofInput): ByteArray = signWith(
            currentKeys,
            CommercialSignatureMessages.purchasePoll(input)
        )

        override fun beginRecovery(rotateKnownKey: Boolean): RecoveryDeviceIdentitySession {
            val previous = currentKeys
            val pending = if (rotateKnownKey) generateKeys() else currentKeys
            return object : RecoveryDeviceIdentitySession {
                override val identity: DeviceCommercialIdentity = identity(pending)

                override fun signChallenge(challenge: ByteArray): ByteArray = signWith(
                    pending,
                    challenge
                )

                override fun signWithPreviousKeyIfAvailable(challenge: ByteArray): ByteArray? {
                    if (!rotateKnownKey) return null
                    previousSignatureUsed = true
                    return signWith(previous, challenge)
                }

                override fun commit(): Boolean {
                    currentKeys = pending
                    return true
                }

                override fun abort() = Unit
            }
        }

        private fun identity(keys: KeyPair) = DeviceCommercialIdentity(
            publicKeySpkiBase64 = java.util.Base64.getEncoder().encodeToString(keys.public.encoded),
            publicKeySha256 = CommercialDigests.sha256Hex(keys.public.encoded),
            deviceFingerprintSha256 = fingerprint,
            signingCertSha256 = "c".repeat(64),
            attestationStatus = DeviceAttestationStatus.UNAVAILABLE
        )
    }

    private class MapSecureStore : SecureCommercialStore {
        private val values = mutableMapOf<SecureCommercialRecord, ByteArray>()
        val failedDeletes = mutableSetOf<SecureCommercialRecord>()

        override fun read(record: SecureCommercialRecord): SecureStoreReadResult =
            values[record]?.copyOf()?.let(SecureStoreReadResult::Value)
                ?: SecureStoreReadResult.Missing

        override fun write(record: SecureCommercialRecord, bytes: ByteArray): Boolean {
            values[record] = bytes.copyOf()
            return true
        }

        override fun delete(record: SecureCommercialRecord): Boolean {
            if (record in failedDeletes) return false
            values.remove(record)
            return true
        }
    }

    companion object {
        private const val TRIAL_DURATION_MS = 7L * 24 * 60 * 60 * 1000

        private fun generateKeys(): KeyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

        private fun signWith(keys: KeyPair, value: ByteArray): ByteArray =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(keys.private)
                update(value)
                sign()
            }
    }
}
