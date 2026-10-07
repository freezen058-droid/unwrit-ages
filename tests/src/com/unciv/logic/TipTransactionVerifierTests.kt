package com.unciv.logic

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import com.badlogic.gdx.utils.JsonWriter
import com.unciv.logic.chain.TipTransactionVerifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TipTransactionVerifierTests {
    private val signature = "34odfuUAHeT9jh3iRFiU1sheo347wT7Mnqy4dbzaroURwoXNv85QrTsK5PfiusJqHgQaoprboZ2aLWunLKJZHiU4"
    private val save = "5DVjGn1RNVEdSBM6zTWdouSqtyYeAASVCfqnFikBpkK2aJZwNFxy9VELXARnXmwv95FByvvUzArYpj5PoswiP4s3"
    private val author = "2hJFxhGzLqS5zjVqVzpAE46ECQPKV7okwbAhFeBD4Luu"
    private fun fixture() = requireNotNull(javaClass.getResource("/chain/tip-17-skr.json")).readText()
    private fun verify(json: String = fixture(), recipient: String = author, amount: Long = 17) =
        TipTransactionVerifier.verifyTip(json, signature, save, recipient, amount)
    private fun mutate(change: (JsonValue) -> Unit): String {
        val root = JsonReader().parse(fixture())
        change(root)
        return root.toJson(JsonWriter.OutputType.json)
    }

    @Test fun actualFinalizedMainnetTipIsAccepted() { assertTrue(verify()) }
    @Test fun claimedAmountMustMatchExactTransfer() { assertFalse(verify(amount = 1)) }
    @Test fun anotherRecipientCannotClaimTheTip() {
        assertFalse(verify(recipient = "3ucwpKsFJ8LUvYETvq6xEzWB8MMkYFzJJzfLAC6EdXLM"))
    }
    @Test fun overflowCannotBecomeAValidPayment() { assertFalse(verify(amount = Long.MAX_VALUE)) }
    @Test fun failedTransactionIsRejected() {
        assertFalse(verify(fixture().replace("\"err\": null", "\"err\": {\"InstructionError\":[0,1]}")))
    }
    @Test fun unrelatedTokenIsRejected() {
        assertFalse(verify(fixture().replace(TipTransactionVerifier.MINT, TipTransactionVerifier.REFERENCE)))
    }
    @Test fun referenceCannotBeRemoved() {
        assertFalse(verify(fixture().replace("[3, 4, 2, 0, 1]", "[3, 4, 2, 0]")))
    }
    @Test fun memoWithoutSignerIsRejected() {
        assertFalse(verify(fixture().replace("\"numRequiredSignatures\": 1", "\"numRequiredSignatures\": 0")))
    }
    @Test fun balanceChangeMustMatchTheInstruction() {
        assertFalse(verify(fixture().replace("\"amount\": \"19734632\"", "\"amount\": \"19734633\"")))
    }
    @Test fun malformedRpcDataFailsClosed() { assertFalse(verify("{}")) }
    @Test fun memoWithoutTransferCannotScore() {
        assertFalse(verify(mutate { it.get("transaction").get("message").get("instructions").remove(0) }))
    }
    @Test fun multipleMemosAreAmbiguous() {
        assertFalse(verify(mutate {
            val instructions = it.get("transaction").get("message").get("instructions")
            instructions.addChild(JsonReader().parse(instructions.get(1).toJson(JsonWriter.OutputType.json)))
        }))
    }
    @Test fun multipleTransfersAreAmbiguous() {
        assertFalse(verify(mutate {
            val instructions = it.get("transaction").get("message").get("instructions")
            instructions.addChild(JsonReader().parse(instructions.get(0).toJson(JsonWriter.OutputType.json)))
        }))
    }
    @Test fun transferAuthorityMustSignTheMemo() {
        assertFalse(verify(mutate {
            it.get("transaction").get("message").get("instructions").get(0).get("accounts").get(3).set(JsonValue(2L))
        }))
    }
    @Test fun unsignedMemoAccountIsRejected() {
        assertFalse(verify(mutate {
            it.get("transaction").get("message").get("instructions").get(1).get("accounts").get(0).set(JsonValue(2L))
        }))
    }
    @Test fun wrongSaveCannotClaimTheTransaction() {
        assertFalse(TipTransactionVerifier.verifyTip(fixture(), signature, signature, author, 17))
    }
    @Test fun innerTransferCannotHideAnAmbiguousPayment() {
        assertFalse(verify(mutate {
            val transfer = it.get("transaction").get("message").get("instructions").get(0)
                .toJson(JsonWriter.OutputType.json)
            val meta = it.get("meta")
            meta.remove("innerInstructions")
            meta.addChild("innerInstructions", JsonReader().parse("[{\"index\":0,\"instructions\":[$transfer]}]"))
        }))
    }
    @Test fun malformedUtf8MemoFailsClosed() {
        assertFalse(verify(mutate {
            it.get("transaction").get("message").get("instructions").get(1).get("data").set("5Q")
        }))
    }
    @Test fun loadedAddressesResolveVersionedTransactionKeys() {
        assertTrue(verify(mutate {
            val message = it.get("transaction").get("message")
            val tokenKey = message.get("accountKeys").remove(8).asString()
            val meta = it.get("meta")
            meta.remove("loadedAddresses")
            meta.addChild("loadedAddresses", JsonReader().parse("{\"writable\":[],\"readonly\":[\"$tokenKey\"]}"))
        }))
    }
    @Test fun actualSaveAuthorIsVerified() {
        val json = requireNotNull(javaClass.getResource("/chain/shared-save.json")).readText()
        assertTrue(TipTransactionVerifier.verifyAuthor(json, save, author,
            "4FHEBH1tspMLq2oeUMSp88JmkbVyzdG6veVh5FzMJ1v2"))
    }
    @Test fun metadataCannotRedirectTipsToAnotherAuthor() {
        val json = requireNotNull(javaClass.getResource("/chain/shared-save.json")).readText()
        assertFalse(TipTransactionVerifier.verifyAuthor(json, save,
            "3ucwpKsFJ8LUvYETvq6xEzWB8MMkYFzJJzfLAC6EdXLM",
            "4FHEBH1tspMLq2oeUMSp88JmkbVyzdG6veVh5FzMJ1v2"))
    }
}
