package com.alphasteg.pro

import com.alphasteg.pro.engine.VaultKeystore
import com.alphasteg.pro.engine.VaultKeystore.Kind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultKeystoreTest {

    private fun kek(seed: Int) = ByteArray(32) { (seed * 31 + it).toByte() }

    @Test
    fun anySlotOpensTheSameDek() {
        val (dek, empty) = VaultKeystore.create()
        val strongbox = kek(1)
        val yubi = kek(2)
        val ks = empty
            .addSlot(dek, strongbox, Kind.STRONGBOX, "Pixel Titan", oneTime = false)
            .addSlot(dek, yubi, Kind.FIDO2, "YubiKey 5C", oneTime = false)

        assertArrayEquals(dek, ks.open(strongbox)!!.dek)
        assertArrayEquals(dek, ks.open(yubi)!!.dek)  // recovery path on a new device
    }

    @Test
    fun wrongKekOpensNothing() {
        val (dek, empty) = VaultKeystore.create()
        val ks = empty.addSlot(dek, kek(1), Kind.STRONGBOX, "Titan", oneTime = false)
        assertNull(ks.open(kek(99)))
    }

    @Test
    fun otpYubiKeyBurnsAfterOneUse() {
        val (dek, empty) = VaultKeystore.create()
        val daily = kek(1)
        val otpYubi = kek(7)
        val ks = empty
            .addSlot(dek, daily, Kind.STRONGBOX, "Titan", oneTime = false)
            .addSlot(dek, otpYubi, Kind.FIDO2, "Courier OTP key", oneTime = true)

        val opened = ks.open(otpYubi)!!
        assertTrue(opened.burned)
        assertArrayEquals(dek, opened.dek)

        // The persisted keystore no longer has the OTP slot; the key opens nothing.
        val after = opened.keystore
        assertNull(after.open(otpYubi))
        assertEquals(1, after.slotCount)
        assertArrayEquals(dek, after.open(daily)!!.dek) // daily slot survives
    }

    @Test
    fun recoveryWorksWhenTheDeviceSlotIsGone() {
        // Phone lost: only the YubiKey remains. Removing the StrongBox slot still
        // leaves the vault openable by the security key.
        val (dek, empty) = VaultKeystore.create()
        val titan = kek(1)
        val yubi = kek(2)
        var ks = empty
            .addSlot(dek, titan, Kind.STRONGBOX, "Titan", oneTime = false)
            .addSlot(dek, yubi, Kind.FIDO2, "Backup key in safe", oneTime = false)
        // Simulate the phone (and its Titan slot) being gone.
        ks = ks.removeSlot(ks.slotInfo().first { it.kind == Kind.STRONGBOX }.id)
        assertNull(ks.open(titan))
        assertArrayEquals(dek, ks.open(yubi)!!.dek)
    }

    @Test
    fun codeAndOtpSlotsUseDerivedKeys() {
        val (dek, empty) = VaultKeystore.create()
        val salt = VaultKeystore.randomSalt()
        val codeKek = VaultKeystore.kekFromCode("c0ffee42", salt)
        val ks = empty.addSlot(dek, codeKek, Kind.CODE, "software", oneTime = false, slotData = salt)

        // Re-derive from the stored salt (as an unlock would) and open.
        val reKek = VaultKeystore.kekFromCode("c0ffee42", ks.slotData(ks.slotInfo().first().id)!!)
        assertArrayEquals(dek, ks.open(reKek)!!.dek)
        assertNull(ks.open(VaultKeystore.kekFromCode("wrongCode1", salt)))
    }

    @Test
    fun survivesJsonRoundTrip() {
        val (dek, empty) = VaultKeystore.create()
        val ks = empty
            .addSlot(dek, kek(1), Kind.STRONGBOX, "Titan", oneTime = false)
            .addSlot(dek, kek(2), Kind.FIDO2, "OTP key", oneTime = true, slotData = byteArrayOf(9, 9))

        val restored = VaultKeystore.fromJson(ks.toJson())
        assertEquals(2, restored.slotCount)
        assertArrayEquals(dek, restored.open(kek(1))!!.dek)
        val burned = restored.open(kek(2))!!
        assertTrue(burned.burned)
        assertNull(burned.keystore.open(kek(2)))
    }
}
