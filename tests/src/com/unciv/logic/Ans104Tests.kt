package com.unciv.logic

import com.unciv.logic.chain.Ans104
import org.junit.Assert
import org.junit.Test

/**
 * The certificate upload envelope, checked byte for byte against the reference implementation.
 * The vector was produced by @dha-team/arbundles 1.x (pic/batch_review/_cert/ans/ref.mjs) with the
 * Ed25519 seed 01..20; the signature is taken from it, since Ed25519 itself is the Android module's
 * library - everything around the signature is what is being tested here.
 */
class Ans104Tests {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val owner = hex("79b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0e3910bad049664")
    private val data = hex("556e77726974204167657320414e532d3130342074657374")
    private val signature = hex("704605b73fd763890f1210f9b801baf55d568087b65eeabcd91928bf94a7efcf01f222b9455ef76a3c0f7b5cfdc8efd8f2c4193479ab628156617eb32627c80b")
    private val tags = listOf(Ans104.Tag("Content-Type", "text/plain"), Ans104.Tag("App-Name", "Unwrit Ages"))

    @Test
    fun signatureDataMatchesReference() {
        val message = Ans104.signatureData(owner, Ans104.encodeTags(tags), data)
        Assert.assertArrayEquals(hex("4d9f7a8aec6d439b3a3d36a4ad57ba14e1af987c5e712caee8a05fabf6a02f1823007a4a9c0b1d3e73049d207ca49c97"), message)
    }

    @Test
    fun rawItemAndIdMatchReference() {
        var signed: ByteArray? = null
        val item = Ans104.create(data, tags, owner) { signed = it; signature }
        Assert.assertArrayEquals(hex("4d9f7a8aec6d439b3a3d36a4ad57ba14e1af987c5e712caee8a05fabf6a02f1823007a4a9c0b1d3e73049d207ca49c97"), signed)
        Assert.assertArrayEquals(hex("0200704605b73fd763890f1210f9b801baf55d568087b65eeabcd91928bf94a7efcf01f222b9455ef76a3c0f7b5cfdc8efd8f2c4193479ab628156617eb32627c80b79b5562e8fe654f94078b112e8a98ba7901f853ae695bed7e0e3910bad049664000002000000000000002f000000000000000418436f6e74656e742d5479706514746578742f706c61696e104170702d4e616d6516556e77726974204167657300556e77726974204167657320414e532d3130342074657374"), item.raw)
        Assert.assertEquals("F9b7JxpS_G6QLBsRfHMnE-Y3hK8V7t_ZFPapgr2jkuY", item.id)
    }
}
