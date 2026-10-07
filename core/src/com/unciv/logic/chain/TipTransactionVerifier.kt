package com.unciv.logic.chain

import com.badlogic.gdx.utils.JsonReader
import com.badlogic.gdx.utils.JsonValue
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction

/** Fail-closed validation of finalized RPC transactions. The RPC remains a trust boundary. */
object TipTransactionVerifier {
    const val TOKEN = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
    const val MEMO = "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr"
    const val MINT = "SKRbvo6Gf7GondiT3BbTfuRDPqLWei4j2Qy2NPGZhW3"
    const val REFERENCE = "44BCs5bxqSWJ4pw12hJUBibeLtbA34cvKztYCpCq68Q9"
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    private fun decode(text: String): ByteArray {
        require(text.isNotEmpty() && text.length <= 2048)
        var number = BigInteger.ZERO
        for (char in text) {
            val digit = ALPHABET.indexOf(char)
            require(digit >= 0)
            number = number.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit.toLong()))
        }
        val bytes = if (number.signum() == 0) byteArrayOf() else number.toByteArray().let {
            if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
        }
        return ByteArray(text.takeWhile { it == '1' }.length) + bytes
    }

    private fun values(value: JsonValue): List<JsonValue> = value.toList()
    private data class Instruction(val program: String, val accounts: List<Int>, val data: ByteArray)
    private class Transaction(json: String, signature: String) {
        val root = JsonReader().parse(json.also { require(it.length <= 512 * 1024) })
        val meta = root.get("meta")!!
        val transaction = root.get("transaction")!!
        val message = transaction.get("message")!!
        val staticKeys = values(message.get("accountKeys")).map { it.asString() }
        val keys = staticKeys +
            listOf("writable", "readonly").flatMap { field ->
                meta.get("loadedAddresses")?.get(field)?.let { values(it).map(JsonValue::asString) }.orEmpty()
            }
        val signers = keys.take(message.get("header").getInt("numRequiredSignatures"))
        val instructions: List<Instruction>
        init {
            require(decode(signature).size == 64)
            require(transaction.get("signatures").getString(0) == signature)
            require(meta.get("err")?.isNull == true)
            require(keys.size <= 256 && keys.distinct().size == keys.size)
            require(keys.all { decode(it).size == 32 })
            require(message.get("header").getInt("numRequiredSignatures") in 1..staticKeys.size)
            require(signers.isNotEmpty() && signers.size <= keys.size)
            instructions = values(message.get("instructions")).map { instruction ->
                Instruction(keys[instruction.getInt("programIdIndex")],
                    values(instruction.get("accounts")).map { it.asInt().also { index -> require(index in keys.indices) } },
                    instruction.getString("data").let { if (it.isEmpty()) byteArrayOf() else decode(it) })
            }
            require(instructions.size <= 64)
            // ATA creation may initialize accounts, but an additional inner transfer is ambiguous.
            for (group in meta.get("innerInstructions")?.let(::values).orEmpty()) {
                for (instruction in values(group.get("instructions"))) {
                    val program = keys[instruction.getInt("programIdIndex")]
                    val data = instruction.getString("data").let { if (it.isEmpty()) byteArrayOf() else decode(it) }
                    require(program != TOKEN || data.firstOrNull()?.toInt() !in listOf(3, 12))
                }
            }
        }
        fun memo(): Pair<String, String> {
            val memo = instructions.filter { it.program == MEMO }.single()
            require(memo.accounts.size == 1)
            val signer = keys[memo.accounts.single()]
            require(signer in signers)
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(memo.data)).toString()
            return text to signer
        }
        fun payment(owner: String, recipient: String, amount: Long, reference: Boolean): Boolean {
            val transfers = instructions.filter { it.program == TOKEN && it.data.firstOrNull()?.toInt() in listOf(3, 12) }
            val transfer = transfers.single()
            require(transfer.data.size == 10 && transfer.data[0].toInt() == 12 && transfer.data[9].toInt() == 6)
            require(ByteBuffer.wrap(transfer.data, 1, 8).order(ByteOrder.LITTLE_ENDIAN).long == amount)
            require(transfer.accounts.size == if (reference) 5 else 4)
            val (source, mint, destination, authority) = transfer.accounts
            require(source != destination && keys[mint] == MINT && keys[authority] == owner && owner in signers)
            require(owner != recipient)
            if (reference) require(keys[transfer.accounts[4]] == REFERENCE)
            fun balance(field: String, index: Int, expectedOwner: String, missing: Boolean): Long {
                val entries = values(meta.get(field)).filter { it.getInt("accountIndex") == index }
                if (missing && entries.isEmpty()) return 0
                val entry = entries.single()
                require(entry.getString("mint") == MINT && entry.getString("owner") == expectedOwner)
                val token = entry.get("uiTokenAmount")
                require(token.getInt("decimals") == 6)
                return token.getString("amount").toLong().also { require(it >= 0) }
            }
            require(balance("preTokenBalances", source, owner, false) - balance("postTokenBalances", source, owner, false) == amount)
            require(balance("postTokenBalances", destination, recipient, false) - balance("preTokenBalances", destination, recipient, true) == amount)
            return true
        }
    }

    fun verifyTip(json: String, signature: String, save: String, author: String, wholeSkr: Long): Boolean = try {
        require(wholeSkr in 1..Long.MAX_VALUE / 1_000_000L && decode(save).size == 64)
        val transaction = Transaction(json, signature)
        val (memo, signer) = transaction.memo()
        require(memo == CloudSave.tipMemo(save, wholeSkr))
        transaction.payment(signer, author, wholeSkr * 1_000_000L, true)
    } catch (_: Exception) { false }

    fun verifyAuthor(json: String, signature: String, author: String, treasury: String): Boolean = try {
        val transaction = Transaction(json, signature)
        val (memo, signer) = transaction.memo()
        require(signer == author && memo.startsWith(CloudSave.MEMO_PREFIX))
        val record = requireNotNull(CloudSave.parse(memo, signature))
        require(record.visibility == CloudSave.SHARED && record.meta?.author == author && record.restorable)
        transaction.payment(author, treasury, 1_000_000L, false)
    } catch (_: Exception) { false }
}
