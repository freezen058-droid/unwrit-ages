"""Offline tests for verify_certificates.py: python -m unittest discover -s tools/verify"""
import copy
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import verify_certificates as vc  # noqa: E402

WALLET = "7xKXtg2CW87d97TXJSDpbD5jBkheTqA83TZRuJosgAsU"
OTHER_WALLET = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
START_SIG = "5VERv8NMvzbJMEkV8xnrLkEaWRtSz9CosKDYjCJjBRnbJLgp8uirBgmQpjKhoR4tjF3ZpRzrFmBV6UjKdiSZkQUW"
MINT_SIG = "3AsdoALgZFuq2oUVWrDYhg2pNeaLJKPLf8hU2mQ6U8qJxeJ6hsrhKRBbRsrDvoiY9kJ6Qd8wG9t8UfCvQjpbX7pz"
ASSET = "AssetXyz1111111111111111111111111111111111"
GAME_ID = "0e5b3c1a-8f7d-4a1e-9b2c-3d4e5f6a7b8c"


def memo_ix(text):
    return {"program": "spl-memo", "programId": "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr",
            "parsed": text, "stackHeight": None}


def start_tx(wallet=WALLET, memo=None, slot=1000, block_time=1_700_000_000, err=None):
    """A jsonParsed getTransaction result for an anchored start, as the app sends it."""
    return {
        "slot": slot, "blockTime": block_time,
        "meta": {"err": err, "preBalances": [10_000_000, 1], "postBalances": [9_995_000, 1]},
        "transaction": {"signatures": [START_SIG], "message": {
            "accountKeys": [
                {"pubkey": wallet, "signer": True, "writable": True, "source": "transaction"},
                {"pubkey": "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr", "signer": False, "writable": False},
            ],
            "instructions": [memo_ix(memo if memo is not None else "unwritages-start:" + GAME_ID)],
        }},
    }


def create_v1_data(name, uri):
    def s(v):
        b = v.encode()
        return len(b).to_bytes(4, "little") + b
    return bytes([0, 0]) + s(name) + s(uri) + b"\x01" + b"\x00\x00\x00\x00"


def mint_tx(payer=WALLET, uri="https://turbo-gateway.com/" + "A" * 43, slot=2000, fee=5_000_000):
    core = vc.MPL_CORE_PROGRAM_ID
    return {
        "slot": slot, "blockTime": 1_700_000_500,
        "meta": {"err": None, "preBalances": [100_000_000, 0, 50, 1, 1], "postBalances": [90_000_000, 3_000_000, 50 + fee, 1, 1]},
        "transaction": {"message": {
            "accountKeys": [
                {"pubkey": payer, "signer": True, "writable": True},
                {"pubkey": ASSET, "signer": True, "writable": True},
                {"pubkey": vc.TREASURY_ADDRESS, "signer": False, "writable": True},
                {"pubkey": "11111111111111111111111111111111", "signer": False, "writable": False},
                {"pubkey": core, "signer": False, "writable": False},
            ],
            "instructions": [
                {"programId": core, "stackHeight": None,
                 "accounts": [ASSET, core, core, payer, core, core, "11111111111111111111111111111111", core],
                 "data": vc.b58encode(create_v1_data("Unwrit Ages Victory", uri))},
                {"program": "system", "programId": "11111111111111111111111111111111",
                 "parsed": {"type": "transfer", "info": {"source": payer, "destination": vc.TREASURY_ADDRESS, "lamports": fee}}},
            ],
        }},
    }


def anchored_metadata(wallet=WALLET, sig=START_SIG, game_id=GAME_ID, seed=None):
    return {
        "name": "Unwrit Ages Victory",
        "attributes": [{"trait_type": "Civilization", "value": "Rome"},
                       {"trait_type": "Origin", "value": "Anchored start"}],
        "unwritAges": {"gameId": game_id, "saveUri": "",
                       "mapSeed": vc.seed_for(wallet, sig) if seed is None else seed,
                       "startWallet": wallet, "startSignature": sig},
    }


class SeedTests(unittest.TestCase):
    def test_vector_shared_with_kotlin(self):
        # tests/src/com/unciv/logic/CertificateMetadataTests.kt
        self.assertEqual(105442945842005338, vc.seed_for("Wa11et", "S1g"))
        self.assertNotEqual(vc.seed_for("Wa11et", "S1g"), vc.seed_for("Other", "S1g"))

    def test_seed_is_signed_64_bit(self):
        seeds = [vc.seed_for("w", str(i)) for i in range(64)]
        self.assertTrue(all(-2 ** 63 <= s < 2 ** 63 for s in seeds))
        self.assertTrue(any(s < 0 for s in seeds))


class ParsingTests(unittest.TestCase):
    def test_memo_exact(self):
        self.assertEqual(GAME_ID, vc.parse_start_memo("unwritages-start:" + GAME_ID))
        self.assertIsNone(vc.parse_start_memo("unwritages-start:"))
        self.assertIsNone(vc.parse_start_memo("unwritages-save:" + GAME_ID + ":x:ab"))
        self.assertIsNone(vc.parse_start_memo(" unwritages-start:" + GAME_ID))
        self.assertIsNone(vc.parse_start_memo(None))

    def test_memo_from_raw_json_encoding(self):
        # encoding=json: memo data is base58 and program ids are indices.
        text = "unwritages-start:" + GAME_ID
        tx = {"slot": 1, "meta": {"err": None}, "transaction": {"message": {
            "header": {"numRequiredSignatures": 1},
            "accountKeys": [WALLET, "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr"],
            "instructions": [{"programIdIndex": 1, "accounts": [0], "data": vc.b58encode(text.encode())}]}}}
        self.assertEqual([text], [ix["memo"] for ix in vc.top_level_instructions(tx)])
        self.assertEqual(WALLET, vc.fee_payer(tx))

    def test_base58_roundtrip(self):
        for b in (b"", b"\x00\x00\x01", bytes(range(40))):
            self.assertEqual(b, vc.b58decode(vc.b58encode(b)))
        self.assertEqual(32, len(vc.b58decode(vc.TREASURY_ADDRESS)))

    def test_core_create_found(self):
        creates = vc.find_core_creates(mint_tx())
        self.assertEqual(1, len(creates))
        self.assertEqual(ASSET, creates[0]["asset"])
        self.assertEqual(WALLET, creates[0]["payer"])
        self.assertEqual("https://turbo-gateway.com/" + "A" * 43, creates[0]["uri"])
        self.assertEqual(5_000_000, vc.lamports_received(mint_tx(), vc.TREASURY_ADDRESS))

    def test_save_record_is_not_a_certificate(self):
        tx = start_tx(memo="unwritages-save:g:name:abcd")
        self.assertEqual([], vc.find_core_creates(tx))

    def test_asset_account(self):
        def s(v):
            return len(v.encode()).to_bytes(4, "little") + v.encode()
        owner = vc.b58decode(WALLET)
        data = b"\x01" + owner + b"\x01" + owner + s("Unwrit Ages Victory") + s("https://x/y") + b"\x00"
        acct = vc.parse_core_asset_account(data)
        self.assertEqual(WALLET, acct["owner"])
        self.assertEqual("https://x/y", acct["uri"])

    def test_gateway_fallback(self):
        tx_id = "b" * 43
        self.assertEqual(["https://turbo-gateway.com/" + tx_id, "https://arweave.net/" + tx_id],
                         vc.gateway_urls("https://turbo-gateway.com/" + tx_id))
        self.assertEqual(["https://turbo-gateway.com/" + tx_id, "https://arweave.net/" + tx_id],
                         vc.gateway_urls("ar://" + tx_id))
        self.assertEqual(["https://example.com/m.json"], vc.gateway_urls("https://example.com/m.json"))


class VerdictTests(unittest.TestCase):
    """The anchored certificate verifies; each broken variant must fail for its own reason."""

    def verdict(self, metadata, start=None, minter=WALLET, mint_slot=2000):
        entry = {"asset": ASSET, "minter": minter, "mintSlot": mint_slot, "_mintBlockTime": 1_700_000_500}
        loaded = []

        def loader(sig):
            loaded.append(sig)
            return start_tx() if start is None else start

        vc.judge(entry, metadata, loader)
        return entry, loaded

    def assertFailsWith(self, entry, fragment):
        self.assertEqual("failed", entry["verdict"], entry)
        self.assertTrue(any(fragment in r for r in entry["reasons"]), entry["reasons"])

    def test_anchored_verifies(self):
        entry, loaded = self.verdict(anchored_metadata())
        self.assertEqual("verified", entry["verdict"], entry["reasons"])
        self.assertEqual([], entry["reasons"])
        self.assertEqual([START_SIG], loaded)
        self.assertEqual(1000, entry["startSlot"])

    def test_unanchored_is_stated_not_accused(self):
        md = {"attributes": [{"trait_type": "Origin", "value": "Unanchored"}],
              "unwritAges": {"gameId": GAME_ID, "mapSeed": 42}}
        entry, loaded = self.verdict(md)
        self.assertEqual("unanchored", entry["verdict"])
        self.assertEqual([], entry["reasons"])
        self.assertEqual([], loaded)  # nothing to look up

    def test_no_origin_attribute_is_unanchored(self):
        entry, _ = self.verdict({"attributes": [], "unwritAges": {"gameId": GAME_ID, "mapSeed": 1}})
        self.assertEqual("unanchored", entry["verdict"])

    def test_minted_by_another_wallet_fails(self):
        # The anchor is genuine, but someone else's wallet minted the certificate.
        entry, _ = self.verdict(anchored_metadata(), minter=OTHER_WALLET)
        self.assertFailsWith(entry, "is not the minting wallet")

    def test_start_signed_by_another_wallet_fails(self):
        # Metadata names WALLET, but the start transaction was paid/signed by another wallet.
        entry, _ = self.verdict(anchored_metadata(), start=start_tx(wallet=OTHER_WALLET))
        self.assertFailsWith(entry, "fee payer/signer")

    def test_wrong_seed_fails(self):
        entry, _ = self.verdict(anchored_metadata(seed=vc.seed_for(WALLET, START_SIG) + 1))
        self.assertFailsWith(entry, "is not seedFor")

    def test_memo_game_id_mismatch_fails(self):
        entry, _ = self.verdict(anchored_metadata(), start=start_tx(memo="unwritages-start:some-other-game"))
        self.assertFailsWith(entry, "start memo names gameId some-other-game")

    def test_memo_missing_fails(self):
        entry, _ = self.verdict(anchored_metadata(), start=start_tx(memo="hello"))
        self.assertFailsWith(entry, "has no memo")

    def test_start_after_mint_fails(self):
        entry, _ = self.verdict(anchored_metadata(), start=start_tx(slot=2500))
        self.assertFailsWith(entry, "is not before the mint")

    def test_start_in_same_slot_fails(self):
        entry, _ = self.verdict(anchored_metadata(), start=start_tx(slot=2000))
        self.assertFailsWith(entry, "is not before the mint")

    def test_failed_start_transaction_fails(self):
        entry, _ = self.verdict(anchored_metadata(), start=start_tx(err={"InstructionError": [0, "Custom"]}))
        self.assertFailsWith(entry, "failed on chain")

    def test_missing_start_transaction_fails(self):
        entry = {"asset": ASSET, "minter": WALLET, "mintSlot": 2000}
        vc.judge(entry, anchored_metadata(), lambda sig: None)
        self.assertFailsWith(entry, "not found on chain")

    def test_anchored_claim_without_anchor_fields_fails(self):
        md = anchored_metadata()
        del md["unwritAges"]["startSignature"]
        entry, _ = self.verdict(md)
        self.assertFailsWith(entry, "no startSignature")

    def test_each_failure_is_isolated(self):
        # A variant breaks exactly one thing: the verified fixture must stay verified if copied.
        entry, _ = self.verdict(copy.deepcopy(anchored_metadata()), start=copy.deepcopy(start_tx()))
        self.assertEqual("verified", entry["verdict"])


class CursorTests(unittest.TestCase):
    class FakeRpc:
        def __init__(self, history):
            self.history = history  # newest first

        def signatures(self, address, before, until, limit):
            sigs = [h for h in self.history]
            if before:
                sigs = sigs[sigs.index(before) + 1:]
            if until:
                sigs = sigs[:sigs.index(until)] if until in sigs else sigs
            return [{"signature": s, "err": None} for s in sigs[:limit]]

    def test_capped_runs_never_skip(self):
        history = ["s%02d" % i for i in range(30, 0, -1)]  # s30 newest
        rpc = self.FakeRpc(history)
        seen = []
        cursor = {}
        got, cursor = vc.collect_signatures(rpc, "T", cursor, 7)
        seen += [g["signature"] for g in got]
        # New activity arrives between runs.
        rpc.history = ["s33", "s32", "s31"] + history
        for _ in range(10):
            got, cursor = vc.collect_signatures(rpc, "T", cursor, 7)
            seen += [g["signature"] for g in got]
        self.assertEqual(sorted(rpc.history), sorted(seen))
        self.assertEqual(len(seen), len(set(seen)))
        self.assertEqual({"newest": "s33", "pending": []}, cursor)


if __name__ == "__main__":
    unittest.main()
