package com.alphasteg.pro

import com.alphasteg.pro.engine.KeyRing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyRingTest {

    @Test
    fun masterCodeOpensAndReturnsSameDek() {
        val (dek, empty) = KeyRing.create()
        val ring = empty.addGrant(dek, "masterCode01", "master", oneTime = false)

        val opened = ring.open("masterCode01")
        assertNotNull(opened)
        assertEquals(dek, opened!!.dek)
        assertFalse(opened.burned)
        // Non-one-time grant survives, so it opens again.
        assertNotNull(ring.open("masterCode01"))
    }

    @Test
    fun twoDistinctCodesRevealTheSameDataset() {
        val (dek, empty) = KeyRing.create()
        val ring = empty
            .addGrant(dek, "alphaCode001", "master", oneTime = false)
            .addGrant(dek, "bravoCode002", "partner", oneTime = false)

        // Different codes, one dataset: both recover the identical DEK.
        assertEquals(ring.open("alphaCode001")!!.dek, ring.open("bravoCode002")!!.dek)
    }

    @Test
    fun wrongCodeOpensNothing() {
        val (dek, empty) = KeyRing.create()
        val ring = empty.addGrant(dek, "realCode1234", "master", oneTime = false)

        assertNull(ring.open("wrongCode999"))
        assertNull(ring.open(""))
        assertNull(KeyRing.create().second.open("realCode1234")) // empty ring opens nothing
    }

    @Test
    fun oneTimeCodeBurnsAfterFirstUse() {
        val (dek, empty) = KeyRing.create()
        val ring = empty
            .addGrant(dek, "permanentKey1", "master", oneTime = false)
            .addGrant(dek, "burnOnce00001", "otp-1", oneTime = true)

        val first = ring.open("burnOnce00001")
        assertNotNull(first)
        assertTrue(first!!.burned)
        assertEquals(dek, first.dek)

        // The persisted ring is the burned one; the OTP no longer opens it,
        // but the master code still does.
        val after = first.ring
        assertNull(after.open("burnOnce00001"))
        assertEquals(dek, after.open("permanentKey1")!!.dek)
        assertEquals(1, after.grantCount)
    }

    @Test
    fun oneTimeSetOfPinsEachBurnIndependently() {
        val (dek, empty) = KeyRing.create()
        var ring = empty.addGrant(dek, "keeperKey0001", "master", oneTime = false)
        val otps = listOf("otpPinAaaaaa", "otpPinBbbbbb", "otpPinCccccc")
        otps.forEachIndexed { i, p -> ring = ring.addGrant(dek, p, "otp-$i", oneTime = true) }

        // Spend them one at a time; each is good exactly once.
        for (p in otps) {
            val o = ring.open(p) ?: error("otp should open once")
            assertTrue(o.burned)
            ring = o.ring
            assertNull(ring.open(p)) // spent
        }
        // All OTPs spent; only the keeper remains.
        assertEquals(1, ring.grantCount)
        assertEquals(dek, ring.open("keeperKey0001")!!.dek)
    }

    @Test
    fun survivesJsonRoundTrip() {
        val (dek, empty) = KeyRing.create()
        val ring = empty
            .addGrant(dek, "masterKey0001", "master", oneTime = false)
            .addGrant(dek, "oneTimeKey002", "otp-1", oneTime = true)

        val restored = KeyRing.fromJson(ring.toJson())
        assertEquals(2, restored.grantCount)
        assertEquals(dek, restored.open("masterKey0001")!!.dek)
        val burned = restored.open("oneTimeKey002")!!
        assertTrue(burned.burned)
        assertNull(burned.ring.open("oneTimeKey002"))
    }

    @Test
    fun revokeRemovesACode() {
        val (dek, empty) = KeyRing.create()
        val ring = empty
            .addGrant(dek, "stayCode00001", "master", oneTime = false)
            .addGrant(dek, "goneCode00002", "guest", oneTime = false)

        val revoked = ring.removeGrant("guest")
        assertNull(revoked.open("goneCode00002"))
        assertNotNull(revoked.open("stayCode00001"))
    }
}
