package com.ninepointnine.desktoplyrics.commercial

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
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
    fun `plain text upstream 5xx is treated as a transient network failure`() {
        val api = DeviceCommerceJsonApi(
            DeviceCommerceTransport {
                DeviceCommerceHttpResponse(
                    statusCode = 530,
                    body = "error code: 1033"
                )
            }
        )

        val result = api.readCurrentCampaign()
        val failure = (result as DeviceCommerceApiResult.Failure).failure

        assertEquals(DeviceCommerceApiFailureKind.NETWORK, failure.kind)
        assertEquals(530, failure.httpStatus)
    }

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

            val storedBeforeCheck = (fixture.store.read(SecureCommercialRecord.LICENSE)
                as SecureStoreReadResult.Value).bytes
            val claimsBeforeCheck = DeviceCommerceLicenseClaimsParser.parse(
                SignedLicenseEnvelopeCodec.decode(storedBeforeCheck).rawPayload
            )

            val query = fixture.gateway.queryEntitlement(fixture.now) as EntitlementQueryResult.Ready
            assertEquals(EntitlementState.Pro, query.snapshot.entitlement)
            assertEquals(null, query.snapshot.quote)
            val storedAfterCheck = (fixture.store.read(SecureCommercialRecord.LICENSE)
                as SecureStoreReadResult.Value).bytes
            val claimsAfterCheck = DeviceCommerceLicenseClaimsParser.parse(
                SignedLicenseEnvelopeCodec.decode(storedAfterCheck).rawPayload
            )
            assertArrayEquals(storedBeforeCheck, storedAfterCheck)
            assertEquals(claimsBeforeCheck.licenseId, claimsAfterCheck.licenseId)
            assertEquals(0, fixture.transport.requestCount("license/refresh"))
            assertEquals(
                PaymentCreationResult.AlreadyOwned,
                fixture.gateway.createPayment(quote, PaymentMethod.WECHAT, fixture.now)
        )
    }

    @Test
    fun `revoked check clears credentials and keeps the gate closed when a delete fails`() =
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

            fixture.store.failedDeletes += SecureCommercialRecord.LICENSE
            fixture.transport.entitlementScenario = DebugEntitlementScenario.REVOKED
            assertEquals(
                CommercialAccessRefreshResult.Failure(CommercialFailure.ENTITLEMENT_REVOKED),
                fixture.gateway.forceRefreshAccess(fixture.now)
            )
            assertTrue(
                fixture.store.read(SecureCommercialRecord.LICENSE) is SecureStoreReadResult.Value
            )
            assertEquals(
                SecureStoreReadResult.Missing,
                fixture.store.read(SecureCommercialRecord.DEVICE_TOKEN)
            )
            assertEquals(
                true,
                fixture.store.read(SecureCommercialRecord.ACCESS_REVOCATION) is
                    SecureStoreReadResult.Value
            )
            assertArrayEquals(
                byteArrayOf(1),
                (fixture.store.read(SecureCommercialRecord.ACCESS_REVOCATION)
                    as SecureStoreReadResult.Value).bytes
            )
            assertEquals(
                SecureStoreReadResult.Missing,
                fixture.store.read(SecureCommercialRecord.ENTITLEMENT_RECHECK_PENDING)
            )
            assertEquals(
                CommercialAccessDecision.Denied(CommercialAccessDenial.ENTITLEMENT_REVOKED),
                fixture.licenseRepository.accessDecision(fixture.now)
            )

            val repeated = fixture.gateway.forceRefreshAccess(fixture.now)
            assertEquals(
                CommercialAccessRefreshResult.Failure(CommercialFailure.ENTITLEMENT_REVOKED),
                repeated
            )
            assertEquals(
                true,
                fixture.store.read(SecureCommercialRecord.ACCESS_REVOCATION) is
                    SecureStoreReadResult.Value
            )
            assertArrayEquals(
                byteArrayOf(1),
                (fixture.store.read(SecureCommercialRecord.ACCESS_REVOCATION)
                    as SecureStoreReadResult.Value).bytes
            )
            assertEquals(0, fixture.transport.requestCount("license/refresh"))
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
    fun `revoked check does not restore the original trial`() = runBlocking {
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
        val refreshed = fixture.gateway.queryEntitlement(fixture.now)
        assertEquals(
            EntitlementQueryResult.Failure(CommercialFailure.ENTITLEMENT_REVOKED),
            refreshed
        )

        assertEquals(
            SecureStoreReadResult.Missing,
            fixture.store.read(SecureCommercialRecord.LICENSE)
        )
        assertEquals(
            SecureStoreReadResult.Missing,
            fixture.store.read(SecureCommercialRecord.DEVICE_TOKEN)
        )
        assertEquals(
            CommercialAccessDecision.Denied(CommercialAccessDenial.ENTITLEMENT_REVOKED),
            fixture.licenseRepository.accessDecision(fixture.now)
        )
    }

    @Test
    fun `expired cloud trial is reported as revoked and clears the local lease`() = runBlocking {
        val fixture = FixtureHarness()
        val initial = fixture.gateway.queryEntitlement(fixture.now)
            as EntitlementQueryResult.Ready
        val trialEndsAt = (initial.snapshot.entitlement as EntitlementState.Trial)
            .expiresAtEpochMs

        fixture.now = trialEndsAt
        fixture.transport.entitlementScenario = DebugEntitlementScenario.EXPIRED

        assertEquals(
            CommercialAccessRefreshResult.Failure(CommercialFailure.ENTITLEMENT_REVOKED),
            fixture.gateway.checkEntitlement(fixture.now)
        )
        assertEquals(
            SecureStoreReadResult.Missing,
            fixture.store.read(SecureCommercialRecord.LICENSE)
        )
        assertEquals(
            CommercialAccessDecision.Denied(CommercialAccessDenial.ENTITLEMENT_REVOKED),
            fixture.licenseRepository.accessDecision(fixture.now)
        )
        assertEquals(0, fixture.transport.requestCount("license/refresh"))
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
            assertEquals(2, fixture.transport.requestCount("license/check"))
            assertEquals(1, fixture.transport.requestCount("recover"))
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
    fun `manual entitlement query preserves a valid local trial when upstream returns 530`() =
        runBlocking {
            val fixture = FixtureHarness()
            val initial = fixture.gateway.queryEntitlement(fixture.now)
                as EntitlementQueryResult.Ready
            assertTrue(initial.snapshot.entitlement is EntitlementState.Trial)

            fixture.transport.entitlementScenario = DebugEntitlementScenario.QUERY_ERROR

            val offline = fixture.gateway.forceQueryEntitlement(fixture.now)
                as EntitlementQueryResult.Ready
            assertTrue(offline.snapshot.entitlement is EntitlementState.Trial)
        }

    @Test
    fun `manual entitlement query reports the signed lease expiry when upstream is unavailable`() =
        runBlocking {
            val fixture = FixtureHarness()
            fixture.gateway.queryEntitlement(fixture.now)
            fixture.now += TRIAL_DURATION_MS + 1
            fixture.transport.entitlementScenario = DebugEntitlementScenario.QUERY_ERROR

            val offline = fixture.gateway.forceQueryEntitlement(fixture.now)
                as EntitlementQueryResult.Ready
            assertEquals(EntitlementState.Expired, offline.snapshot.entitlement)
            assertEquals(
                CommercialAccessDecision.Denied(
                    reason = CommercialAccessDenial.LICENSE_EXPIRED,
                    trialEndsAtEpochMs = fixture.now - 1,
                    expiresAtEpochMs = fixture.now -
                        (TRIAL_DURATION_MS - TRIAL_LICENSE_MAX_DURATION_MS + 1),
                    offlineGraceUntilEpochMs = fixture.now -
                        (TRIAL_DURATION_MS - TRIAL_LICENSE_MAX_DURATION_MS + 1)
                ),
                fixture.licenseRepository.accessDecision(fixture.now)
            )
        }

    @Test
    fun `permanent pro check detects revocation immediately without license refresh`() =
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

            val before = requireNotNull(
                (fixture.store.read(SecureCommercialRecord.LICENSE) as SecureStoreReadResult.Value)
                    .bytes
            )
            fixture.transport.entitlementScenario = DebugEntitlementScenario.PRO
            assertEquals(
                CommercialAccessRefreshResult.Ready(EntitlementState.Pro),
                fixture.gateway.refreshAccess(fixture.now)
            )
            val afterActive = (fixture.store.read(SecureCommercialRecord.LICENSE)
                as SecureStoreReadResult.Value).bytes
            assertTrue(before.contentEquals(afterActive))
            assertEquals(0, fixture.transport.requestCount("license/refresh"))

            fixture.transport.entitlementScenario = DebugEntitlementScenario.QUERY_ERROR
            assertEquals(
                CommercialAccessRefreshResult.Ready(EntitlementState.Pro),
                fixture.gateway.refreshAccess(fixture.now)
            )
            assertTrue(
                fixture.store.read(SecureCommercialRecord.ENTITLEMENT_RECHECK_PENDING) is
                    SecureStoreReadResult.Value
            )

            fixture.transport.entitlementScenario = DebugEntitlementScenario.REVOKED
            assertEquals(
                CommercialAccessRefreshResult.Failure(CommercialFailure.ENTITLEMENT_REVOKED),
                fixture.gateway.refreshAccess(fixture.now)
            )
            assertEquals(
                SecureStoreReadResult.Missing,
                fixture.store.read(SecureCommercialRecord.LICENSE)
            )
            assertEquals(0, fixture.transport.requestCount("license/refresh"))
        }

    @Test
    fun `trial check renews only the 24 hour lease and keeps the original seven day end`() =
        runBlocking {
            val fixture = FixtureHarness()
            fixture.gateway.queryEntitlement(fixture.now)
            val firstEnvelope = SignedLicenseEnvelopeCodec.decode(
                (fixture.store.read(SecureCommercialRecord.LICENSE)
                    as SecureStoreReadResult.Value).bytes
            )
            val firstClaims = DeviceCommerceLicenseClaimsParser.parse(firstEnvelope.rawPayload)
            assertEquals(
                TRIAL_LICENSE_MAX_DURATION_MS,
                firstClaims.expiresAtEpochMs!! - firstClaims.issuedAtEpochMs
            )
            val originalTrialEnd = firstClaims.trialEndsAtEpochMs

            fixture.now += TRIAL_LICENSE_MAX_DURATION_MS + 1
            val renewed = fixture.gateway.queryEntitlement(fixture.now)
                as EntitlementQueryResult.Ready
            assertTrue(renewed.snapshot.entitlement is EntitlementState.Trial)
            val secondEnvelope = SignedLicenseEnvelopeCodec.decode(
                (fixture.store.read(SecureCommercialRecord.LICENSE)
                    as SecureStoreReadResult.Value).bytes
            )
            val secondClaims = DeviceCommerceLicenseClaimsParser.parse(secondEnvelope.rawPayload)
            assertEquals(originalTrialEnd, secondClaims.trialEndsAtEpochMs)
            assertEquals(firstClaims.deviceKeyVersion, secondClaims.deviceKeyVersion)
            assertTrue(
                secondClaims.expiresAtEpochMs!! - secondClaims.issuedAtEpochMs <=
                    TRIAL_LICENSE_MAX_DURATION_MS
            )
            assertTrue(fixture.transport.requestCount("license/check") >= 2)
            assertTrue(fixture.transport.requestCount("trial/start") >= 2)
            assertEquals(0, fixture.transport.requestCount("recover"))
            assertEquals(0, fixture.transport.requestCount("license/refresh"))
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
        val storedBeforeRepeat = (fixture.store.read(SecureCommercialRecord.LICENSE)
            as SecureStoreReadResult.Value).bytes

        fixture.now += 60 * 60 * 1000L
        val repeated = fixture.gateway.queryEntitlement(fixture.now) as EntitlementQueryResult.Ready
        assertEquals(
            originalEnd,
            (repeated.snapshot.entitlement as EntitlementState.Trial).expiresAtEpochMs
        )
        assertArrayEquals(
            storedBeforeRepeat,
            (fixture.store.read(SecureCommercialRecord.LICENSE)
                as SecureStoreReadResult.Value).bytes
        )
        assertEquals(2, fixture.transport.requestCount("license/check"))
        assertEquals(1, fixture.transport.requestCount("trial/start"))
        assertEquals(0, fixture.transport.requestCount("license/refresh"))

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
    fun `not started check rebuilds trial from the original local first open`() = runBlocking {
        val fixture = FixtureHarness()
        val first = fixture.gateway.queryEntitlement(fixture.now) as EntitlementQueryResult.Ready
        val originalEnd = (first.snapshot.entitlement as EntitlementState.Trial).expiresAtEpochMs

        // Simulate the cloud trial row being absent while the locally signed
        // trial is still valid. The first-open clock must remain authoritative.
        fixture.transport.resetIdentityState()

        val repaired = fixture.gateway.queryEntitlement(fixture.now)
            as EntitlementQueryResult.Ready

        assertEquals(
            originalEnd,
            (repaired.snapshot.entitlement as EntitlementState.Trial).expiresAtEpochMs
        )
        assertEquals(1, fixture.transport.requestCount("license/check"))
        assertEquals(1, fixture.transport.requestCount("trial/start"))
        assertEquals(0, fixture.transport.requestCount("recover"))
        assertEquals(0, fixture.transport.requestCount("license/refresh"))
    }

    @Test
    fun `expired trial lease accepts a cloud permanent pro response`() = runBlocking {
        val fixture = FixtureHarness()
        fixture.gateway.queryEntitlement(fixture.now)
        fixture.now += TRIAL_LICENSE_MAX_DURATION_MS + 1
        fixture.transport.entitlementScenario = DebugEntitlementScenario.PRO

        val result = fixture.gateway.queryEntitlement(fixture.now)
            as EntitlementQueryResult.Ready

        assertEquals(EntitlementState.Pro, result.snapshot.entitlement)
        val claims = DeviceCommerceLicenseClaimsParser.parse(
            SignedLicenseEnvelopeCodec.decode(
                (fixture.store.read(SecureCommercialRecord.LICENSE)
                    as SecureStoreReadResult.Value).bytes
            ).rawPayload
        )
        assertEquals(CommercialTier.PRO, claims.tier)
        assertEquals(LicenseValidity.PERMANENT, claims.validity)
        assertEquals(null, claims.expiresAtEpochMs)
        assertEquals(null, claims.offlineGraceUntilEpochMs)
        assertEquals(null, claims.trialEndsAtEpochMs)
        assertEquals(0, fixture.transport.requestCount("recover"))
        assertEquals(0, fixture.transport.requestCount("license/refresh"))
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
        private const val TRIAL_LICENSE_MAX_DURATION_MS = 24L * 60 * 60 * 1000

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
