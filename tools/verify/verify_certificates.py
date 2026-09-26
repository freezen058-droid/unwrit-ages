#!/usr/bin/env python3
"""
Verify every Unwrit Ages victory certificate and publish the result as static JSON.

Inputs are all public: the Solana chain and the certificates' metadata on Arweave. Nothing here
needs a key; the default RPC is the public mainnet endpoint and can be overridden with
SOLANA_RPC_URL (or --rpc).

How certificates are found, without an indexer: every certificate mint pays its fee to the
treasury in the same transaction as the Metaplex Core CreateV1 (android/.../AndroidWalletService.kt
mintVictoryCertificate), so the treasury's signature history lists every mint. Other treasury
traffic (the `unwritages-save:` SKR records, plain transfers) carries no Core create and is skipped.

For each certificate the verdict is one of:
  verified    - claimed "Anchored start", and the start transaction proves it (see check_anchor)
  failed      - claimed "Anchored start", and at least one check does not hold; `reasons` says which
  unanchored  - did not claim an anchored start (stated, not accused)
  error       - could not be checked this run (metadata or RPC unavailable); retried next run

The report doubles as the cache: certificates with a final verdict are not fetched again, and a
cursor records how far the treasury history has been read, so a daily run only reads new
signatures. Standard library only.
"""
from __future__ import annotations

import argparse
import base64
import datetime as _dt
import hashlib
import json
import os
import random
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Callable, Dict, List, Optional, Tuple

# --- Constants mirrored from the app (keep in sync with AndroidWalletService.kt / StartAnchor.kt) ---

TREASURY_ADDRESS = "4FHEBH1tspMLq2oeUMSp88JmkbVyzdG6veVh5FzMJ1v2"
MPL_CORE_PROGRAM_ID = "CoREENxT6tW1HoK8ypY1SxRMZTcVPm7R94rH4PZNhX7d"
# The app uses the current Memo program; v1 is accepted too since it carries the same UTF-8 data.
MEMO_PROGRAM_IDS = {
    "MemoSq4gqABAXKb96qnH8TysNcWxMyWCqXgDLGmfcHr",
    "Memo1UhkJRfHyvLMcVucJwxXeuD728EqVDDwQDxFMNo",
}
START_MEMO_PREFIX = "unwritages-start:"
ORIGIN_ANCHORED = "Anchored start"
ORIGIN_UNANCHORED = "Unanchored"

# mpl-core instruction discriminators whose data starts with dataState, name, uri (Borsh).
CORE_CREATE_V1 = 0
CORE_CREATE_V2 = 20

DEFAULT_RPC = "https://api.mainnet-beta.solana.com"
ARWEAVE_GATEWAYS = ("https://turbo-gateway.com/", "https://arweave.net/")
USER_AGENT = "unwritages-certificate-verifier/1 (+https://unwritages.pages.dev)"
REPORT_SCHEMA = 1
MAX_METADATA_BYTES = 2 * 1024 * 1024
FINAL_VERDICTS = {"verified", "failed", "unanchored"}

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DEFAULT_REPORT = os.path.join(REPO_ROOT, "website", "verified", "certificates.json")


def log(msg: str) -> None:
    print(msg, file=sys.stderr, flush=True)


# --------------------------------------------------------------------------------------------
# Pure helpers (unit-tested, no network)
# --------------------------------------------------------------------------------------------

def seed_for(wallet: str, signature: str) -> int:
    """StartAnchor.seedFor: first 8 bytes of SHA-256(UTF-8(wallet + signature)), big-endian,
    as a signed 64-bit integer (a Kotlin Long)."""
    digest = hashlib.sha256((wallet + signature).encode("utf-8")).digest()
    return int.from_bytes(digest[:8], "big", signed=True)


_B58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
_B58_INDEX = {c: i for i, c in enumerate(_B58)}


def b58decode(s: str) -> bytes:
    n = 0
    for c in s:
        if c not in _B58_INDEX:
            raise ValueError("invalid base58 character %r" % c)
        n = n * 58 + _B58_INDEX[c]
    body = n.to_bytes((n.bit_length() + 7) // 8, "big") if n else b""
    pad = len(s) - len(s.lstrip("1"))
    return b"\x00" * pad + body


def b58encode(b: bytes) -> str:
    n = int.from_bytes(b, "big")
    out = ""
    while n:
        n, r = divmod(n, 58)
        out = _B58[r] + out
    pad = len(b) - len(b.lstrip(b"\x00"))
    return "1" * pad + out


def parse_start_memo(text: Optional[str]) -> Optional[str]:
    """The gameId named by an anchored-start memo, or None if [text] is not exactly one.
    Exact means: the prefix, then a non-empty gameId, nothing trimmed or tolerated."""
    if not isinstance(text, str) or not text.startswith(START_MEMO_PREFIX):
        return None
    game_id = text[len(START_MEMO_PREFIX):]
    return game_id or None


def _read_borsh_string(data: bytes, offset: int) -> Tuple[str, int]:
    if offset + 4 > len(data):
        raise ValueError("truncated Borsh string length")
    length = int.from_bytes(data[offset:offset + 4], "little")
    start, end = offset + 4, offset + 4 + length
    if end > len(data):
        raise ValueError("truncated Borsh string")
    return data[start:end].decode("utf-8"), end


def parse_core_create(data: bytes) -> Optional[Dict[str, str]]:
    """Name and uri of an mpl-core CreateV1/CreateV2 instruction payload, or None if [data] is
    some other Core instruction. Layout (see createV1InstructionData): u8 discriminator,
    u8 dataState, Borsh name, Borsh uri, ..."""
    if len(data) < 2 or data[0] not in (CORE_CREATE_V1, CORE_CREATE_V2):
        return None
    try:
        name, off = _read_borsh_string(data, 2)
        uri, _ = _read_borsh_string(data, off)
    except (ValueError, UnicodeDecodeError):
        return None
    return {"name": name, "uri": uri}


def parse_core_asset_account(data: bytes) -> Optional[Dict[str, str]]:
    """Owner and current uri of an mpl-core AssetV1 account: u8 key (1), owner[32],
    updateAuthority enum (0 None | 1 Address[32] | 2 Collection[32]), Borsh name, Borsh uri."""
    if len(data) < 34 or data[0] != 1:
        return None
    owner = b58encode(data[1:33])
    off = 33
    kind = data[off]
    off += 1
    if kind in (1, 2):
        off += 32
    elif kind != 0:
        return None
    try:
        name, off = _read_borsh_string(data, off)
        uri, _ = _read_borsh_string(data, off)
    except (ValueError, UnicodeDecodeError):
        return None
    return {"owner": owner, "name": name, "uri": uri}


def _account_keys(tx: Dict[str, Any]) -> List[Dict[str, Any]]:
    """Account keys as [{pubkey, signer}], from either jsonParsed or json encoding."""
    msg = (tx.get("transaction") or {}).get("message") or {}
    keys = msg.get("accountKeys") or []
    out = []
    header = msg.get("header") or {}
    n_signers = header.get("numRequiredSignatures")
    for i, k in enumerate(keys):
        if isinstance(k, dict):
            out.append({"pubkey": k.get("pubkey"), "signer": bool(k.get("signer"))})
        else:
            out.append({"pubkey": k, "signer": n_signers is not None and i < n_signers})
    # json encoding lists lookup-table accounts separately (after the static keys).
    loaded = (tx.get("meta") or {}).get("loadedAddresses") or {}
    if keys and not isinstance(keys[0], dict):
        for k in (loaded.get("writable") or []) + (loaded.get("readonly") or []):
            out.append({"pubkey": k, "signer": False})
    return out


def fee_payer(tx: Dict[str, Any]) -> Optional[str]:
    keys = _account_keys(tx)
    return keys[0]["pubkey"] if keys and keys[0]["signer"] else None


def tx_succeeded(tx: Dict[str, Any]) -> bool:
    meta = tx.get("meta")
    return isinstance(meta, dict) and meta.get("err") is None


def top_level_instructions(tx: Dict[str, Any]) -> List[Dict[str, Any]]:
    """Top-level instructions normalised to {programId, accounts[list of base58], data(bytes)|None,
    memo(str)|None}."""
    msg = (tx.get("transaction") or {}).get("message") or {}
    keys = [k["pubkey"] for k in _account_keys(tx)]
    out = []
    for ix in msg.get("instructions") or []:
        if "programId" in ix:
            program = ix["programId"]
        elif "programIdIndex" in ix and ix["programIdIndex"] < len(keys):
            program = keys[ix["programIdIndex"]]
        else:
            continue
        accounts = []
        for a in ix.get("accounts") or []:
            accounts.append(keys[a] if isinstance(a, int) and a < len(keys) else a)
        data = None
        memo = None
        if isinstance(ix.get("data"), str):
            try:
                data = b58decode(ix["data"])
            except ValueError:
                data = None
        if program in MEMO_PROGRAM_IDS:
            parsed = ix.get("parsed")
            if isinstance(parsed, str):
                memo = parsed
            elif data is not None:
                try:
                    memo = data.decode("utf-8")
                except UnicodeDecodeError:
                    memo = None
        out.append({"programId": program, "accounts": accounts, "data": data, "memo": memo})
    return out


def find_core_creates(tx: Dict[str, Any]) -> List[Dict[str, str]]:
    """Every top-level Core create in [tx]: {asset, payer, name, uri}."""
    found = []
    for ix in top_level_instructions(tx):
        if ix["programId"] != MPL_CORE_PROGRAM_ID or ix["data"] is None:
            continue
        parsed = parse_core_create(ix["data"])
        if parsed is None or len(ix["accounts"]) < 4:
            continue
        found.append({"asset": ix["accounts"][0], "payer": ix["accounts"][3],
                      "name": parsed["name"], "uri": parsed["uri"]})
    return found


def lamports_received(tx: Dict[str, Any], address: str) -> Optional[int]:
    meta = tx.get("meta") or {}
    pre, post = meta.get("preBalances"), meta.get("postBalances")
    keys = [k["pubkey"] for k in _account_keys(tx)]
    if address not in keys or not pre or not post:
        return None
    i = keys.index(address)
    if i >= len(pre) or i >= len(post):
        return None
    return post[i] - pre[i]


def attribute(metadata: Dict[str, Any], trait: str) -> Optional[str]:
    attrs = metadata.get("attributes")
    if not isinstance(attrs, list):
        return None
    for a in attrs:
        if isinstance(a, dict) and a.get("trait_type") == trait:
            v = a.get("value")
            return v if isinstance(v, str) else (None if v is None else str(v))
    return None


def _as_int(v: Any) -> Optional[int]:
    if isinstance(v, bool):
        return None
    if isinstance(v, int):
        return v
    if isinstance(v, str):
        try:
            return int(v.strip())
        except ValueError:
            return None
    return None


def check_anchor(metadata: Dict[str, Any], minter: str, mint_slot: Optional[int],
                 mint_time: Optional[int], start_tx: Optional[Dict[str, Any]]) -> List[str]:
    """Every reason an "Anchored start" claim does not hold; an empty list means verified.

    Checks: the start transaction exists and succeeded; its fee payer (and signer) is
    startWallet; it holds a top-level Memo instruction whose text is exactly
    `unwritages-start:<gameId>` for the metadata's gameId; startWallet is the minting wallet;
    mapSeed == seed_for(startWallet, startSignature); the start lands before the mint."""
    reasons: List[str] = []
    ua = metadata.get("unwritAges")
    if not isinstance(ua, dict):
        return ["metadata has no unwritAges object"]
    game_id = ua.get("gameId")
    wallet = ua.get("startWallet")
    signature = ua.get("startSignature")
    map_seed = _as_int(ua.get("mapSeed"))
    if not isinstance(game_id, str) or not game_id:
        reasons.append("metadata has no gameId")
    if not isinstance(wallet, str) or not wallet:
        reasons.append("metadata has no startWallet")
    if not isinstance(signature, str) or not signature:
        reasons.append("metadata has no startSignature")
    if map_seed is None:
        reasons.append("metadata has no integer mapSeed")
    if reasons:
        return reasons

    if wallet != minter:
        reasons.append("startWallet %s is not the minting wallet %s" % (wallet, minter))
    expected_seed = seed_for(wallet, signature)
    if map_seed != expected_seed:
        reasons.append("mapSeed %d is not seedFor(startWallet, startSignature) = %d" % (map_seed, expected_seed))

    if start_tx is None:
        reasons.append("start transaction %s not found on chain" % signature)
        return reasons
    if not tx_succeeded(start_tx):
        reasons.append("start transaction failed on chain: %s" % json.dumps((start_tx.get("meta") or {}).get("err")))
    payer = fee_payer(start_tx)
    if payer != wallet:
        reasons.append("start transaction fee payer/signer is %s, not startWallet %s" % (payer, wallet))
    memos = [ix["memo"] for ix in top_level_instructions(start_tx) if ix["memo"] is not None]
    expected_memo = START_MEMO_PREFIX + game_id
    if expected_memo not in memos:
        named = [parse_start_memo(m) for m in memos]
        named = [g for g in named if g is not None]
        if named:
            reasons.append("start memo names gameId %s, metadata says %s" % (", ".join(named), game_id))
        else:
            reasons.append("start transaction has no memo %r" % expected_memo)

    start_slot = start_tx.get("slot")
    start_time = start_tx.get("blockTime")
    if isinstance(start_slot, int) and isinstance(mint_slot, int):
        if start_slot >= mint_slot:
            reasons.append("start (slot %d) is not before the mint (slot %d)" % (start_slot, mint_slot))
    elif isinstance(start_time, int) and isinstance(mint_time, int):
        if start_time >= mint_time:
            reasons.append("start (time %d) is not before the mint (time %d)" % (start_time, mint_time))
    else:
        reasons.append("cannot order the start against the mint (no slot/time)")
    return reasons


def iso(ts: Optional[int]) -> Optional[str]:
    if not isinstance(ts, int):
        return None
    return _dt.datetime.fromtimestamp(ts, tz=_dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _clip(v: Any, n: int = 200) -> Any:
    return v[:n] if isinstance(v, str) else v


def judge(entry: Dict[str, Any], metadata: Dict[str, Any], start_tx_loader: Callable[[str], Optional[Dict[str, Any]]]) -> None:
    """Fill [entry]'s claim and verdict from [metadata]. [start_tx_loader] fetches a transaction by
    signature (None when it does not exist) and is only called for an anchored claim."""
    ua = metadata.get("unwritAges") if isinstance(metadata.get("unwritAges"), dict) else {}
    origin = attribute(metadata, "Origin")
    entry["claimedOrigin"] = origin
    entry["gameId"] = _clip(ua.get("gameId"))
    entry["mapSeed"] = _as_int(ua.get("mapSeed"))
    for trait, key in (("Civilization", "civilization"), ("Victory", "victory"),
                       ("Turn", "turn"), ("Difficulty", "difficulty"), ("AutoPlay", "autoPlay")):
        value = attribute(metadata, trait)
        if value is not None:
            entry[key] = _clip(value, 80)

    if origin != ORIGIN_ANCHORED:
        entry["verdict"] = "unanchored"
        entry["reasons"] = [] if origin == ORIGIN_UNANCHORED else [
            "no Origin attribute" if origin is None else "Origin is %r" % _clip(origin, 80)]
        return

    entry["startWallet"] = _clip(ua.get("startWallet"), 64)
    entry["startSignature"] = _clip(ua.get("startSignature"), 128)
    start_tx = None
    sig = ua.get("startSignature")
    if isinstance(sig, str) and sig:
        start_tx = start_tx_loader(sig)
        if start_tx is not None:
            entry["startSlot"] = start_tx.get("slot")
            entry["startTime"] = iso(start_tx.get("blockTime"))
    reasons = check_anchor(metadata, entry["minter"], entry.get("mintSlot"), entry.get("_mintBlockTime"), start_tx)
    entry["verdict"] = "failed" if reasons else "verified"
    entry["reasons"] = reasons


def gateway_urls(uri: str) -> List[str]:
    """[uri] itself, then the same Arweave id at each known gateway."""
    urls: List[str] = []
    if uri.startswith("ar://"):
        tx_id = uri[5:].split("/")[0]
    else:
        urls.append(uri)
        parsed = urllib.parse.urlparse(uri)
        segs = [s for s in parsed.path.split("/") if s]
        tx_id = segs[-1] if len(segs) == 1 else ""
        host = parsed.netloc.lower()
        if not any(h in host for h in ("arweave", "turbo-gateway", "ardrive")):
            tx_id = ""
    if tx_id and len(tx_id) == 43 and all(c.isalnum() or c in "-_" for c in tx_id):
        for g in ARWEAVE_GATEWAYS:
            u = g + tx_id
            if u not in urls:
                urls.append(u)
    return urls


# --------------------------------------------------------------------------------------------
# Network
# --------------------------------------------------------------------------------------------

class RpcError(Exception):
    pass


class Http:
    def __init__(self, attempts: int = 6, base_delay: float = 1.0, timeout: float = 30.0):
        self.attempts = attempts
        self.base_delay = base_delay
        self.timeout = timeout

    def _sleep(self, attempt: int, retry_after: Optional[str] = None) -> None:
        delay = self.base_delay * (2 ** attempt) + random.uniform(0, 0.5)
        if retry_after:
            try:
                delay = max(delay, float(retry_after))
            except ValueError:
                pass
        time.sleep(min(delay, 60.0))

    def request(self, url: str, body: Optional[bytes] = None, headers: Optional[Dict[str, str]] = None,
                max_bytes: int = 16 * 1024 * 1024) -> Tuple[int, bytes]:
        """(status, body) for a final response; retries 429/5xx/network errors with backoff.
        4xx other than 408/429 is returned at once, not retried."""
        hdrs = {"User-Agent": USER_AGENT, "Accept": "application/json"}
        hdrs.update(headers or {})
        last: Optional[Exception] = None
        for attempt in range(self.attempts):
            req = urllib.request.Request(url, data=body, headers=hdrs, method="POST" if body is not None else "GET")
            try:
                with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                    return resp.status, resp.read(max_bytes + 1)
            except urllib.error.HTTPError as e:
                if e.code in (408, 429) or e.code >= 500:
                    last = e
                    log("  HTTP %d from %s, retrying (%d/%d)" % (e.code, _host(url), attempt + 1, self.attempts))
                    self._sleep(attempt, e.headers.get("Retry-After") if e.headers else None)
                    continue
                return e.code, b""
            except (urllib.error.URLError, TimeoutError, ConnectionError, OSError) as e:
                last = e
                log("  network error from %s: %s, retrying (%d/%d)" % (_host(url), e, attempt + 1, self.attempts))
                self._sleep(attempt)
        raise RpcError("giving up on %s: %s" % (_host(url), last))


def _host(url: str) -> str:
    return urllib.parse.urlparse(url).netloc or url


class Rpc:
    def __init__(self, url: str, http: Http, min_interval: float = 0.25):
        self.url = url
        self.http = http
        self.min_interval = min_interval
        self._last = 0.0
        self.calls = 0

    def call(self, method: str, params: list) -> Any:
        for attempt in range(self.http.attempts):
            wait = self._last + self.min_interval - time.monotonic()
            if wait > 0:
                time.sleep(wait)
            self._last = time.monotonic()
            self.calls += 1
            payload = json.dumps({"jsonrpc": "2.0", "id": 1, "method": method, "params": params}).encode()
            status, raw = self.http.request(self.url, payload, {"Content-Type": "application/json"})
            if status != 200:
                raise RpcError("%s: HTTP %d" % (method, status))
            try:
                doc = json.loads(raw)
            except ValueError:
                raise RpcError("%s: response is not JSON" % method)
            err = doc.get("error")
            if err:
                code = err.get("code") if isinstance(err, dict) else None
                message = str(err.get("message") if isinstance(err, dict) else err)
                # Rate limits and "node is behind" are worth waiting out; anything else is not.
                if code in (429, -32429, -32005, -32004, -32014) or "rate" in message.lower():
                    log("  RPC %s: %s, retrying (%d/%d)" % (method, message, attempt + 1, self.http.attempts))
                    self.http._sleep(attempt)
                    continue
                raise RpcError("%s: %s" % (method, message))
            return doc.get("result")
        raise RpcError("%s: rate limited, gave up" % method)

    def signatures(self, address: str, before: Optional[str], until: Optional[str], limit: int) -> List[Dict[str, Any]]:
        opts: Dict[str, Any] = {"limit": limit, "commitment": "finalized"}
        if before:
            opts["before"] = before
        if until:
            opts["until"] = until
        return self.call("getSignaturesForAddress", [address, opts]) or []

    def transaction(self, signature: str) -> Optional[Dict[str, Any]]:
        return self.call("getTransaction", [signature, {"encoding": "jsonParsed", "commitment": "finalized",
                                                        "maxSupportedTransactionVersion": 0}])

    def account_data(self, address: str) -> Optional[bytes]:
        res = self.call("getAccountInfo", [address, {"encoding": "base64", "commitment": "finalized"}])
        value = (res or {}).get("value")
        if not value:
            return None
        data = value.get("data")
        if isinstance(data, list) and data:
            return base64.b64decode(data[0])
        return None


def fetch_metadata(uri: str, http: Http) -> Tuple[Optional[Dict[str, Any]], List[str]]:
    """(metadata, problems). Tries [uri] and then each Arweave gateway; the first JSON object wins."""
    problems: List[str] = []
    for url in gateway_urls(uri):
        if not url.startswith("https://"):
            problems.append("%s: not an https uri" % _clip(url, 120))
            continue
        try:
            status, raw = http.request(url, max_bytes=MAX_METADATA_BYTES)
        except RpcError as e:
            problems.append(str(e))
            continue
        if status != 200:
            problems.append("%s: HTTP %d" % (_host(url), status))
            continue
        if len(raw) > MAX_METADATA_BYTES:
            problems.append("%s: metadata larger than %d bytes" % (_host(url), MAX_METADATA_BYTES))
            continue
        try:
            doc = json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            problems.append("%s: not valid JSON" % _host(url))
            continue
        if not isinstance(doc, dict):
            problems.append("%s: JSON is not an object" % _host(url))
            continue
        return doc, problems
    return None, problems


# --------------------------------------------------------------------------------------------
# Report / run
# --------------------------------------------------------------------------------------------

def load_report(path: str) -> Dict[str, Any]:
    try:
        with open(path, encoding="utf-8") as f:
            doc = json.load(f)
        if isinstance(doc, dict) and doc.get("schema") == REPORT_SCHEMA:
            return doc
        log("existing report has another schema; starting over")
    except FileNotFoundError:
        pass
    except ValueError:
        log("existing report is not valid JSON; starting over")
    return {}


def collect_signatures(rpc: Rpc, treasury: str, cursor: Dict[str, Any], limit: int) -> Tuple[List[Dict[str, Any]], Dict[str, Any]]:
    """Treasury signatures not read yet, newest first, at most [limit]; and the cursor after them.

    The cursor is {newest, pending}: `newest` is the newest signature ever read, and `pending` holds
    ranges {before, until} that a capped run left unread, so nothing between runs is ever skipped.
    Each run reads the new range (before=None, until=newest) first, then the pending ranges."""
    ranges = [{"before": None, "until": cursor.get("newest")}] + list(cursor.get("pending") or [])
    out: List[Dict[str, Any]] = []
    new_pending: List[Dict[str, Any]] = []
    newest = cursor.get("newest")
    budget = limit
    for i, rng in enumerate(ranges):
        before, until = rng.get("before"), rng.get("until")
        exhausted = False
        while budget > 0:
            requested = min(1000, budget)
            page = rpc.signatures(treasury, before, until, requested)
            if i == 0 and before is None and page:
                newest = page[0]["signature"]
            out.extend(page)
            budget -= len(page)
            if page:
                before = page[-1]["signature"]
            if len(page) < requested:
                exhausted = True
                break
        if not exhausted:
            if i == 0 and before is None:
                continue  # read nothing of the new range; the next run starts it over anyway
            # Unread remainder of this range; reads resume below `before` next run.
            new_pending.append({"before": before, "until": until})
    return out, {"newest": newest, "pending": new_pending}


def process_signature(sig_info: Dict[str, Any], rpc: Rpc, http: Http, treasury: str,
                      known: Dict[str, Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Certificate entries created by one treasury transaction (usually zero or one)."""
    if sig_info.get("err") is not None:
        return []
    tx = rpc.transaction(sig_info["signature"])
    if tx is None or not tx_succeeded(tx):
        return []
    creates = find_core_creates(tx)
    if not creates:
        return []  # a save record, a plain transfer, anything that is not a mint
    minter = fee_payer(tx)
    entries = []
    for c in creates:
        if c["asset"] in known and known[c["asset"]].get("verdict") in FINAL_VERDICTS:
            continue
        entry = {
            "asset": c["asset"],
            "mintSignature": sig_info["signature"],
            "minter": minter,
            "mintSlot": tx.get("slot"),
            "mintTime": iso(tx.get("blockTime")),
            "_mintBlockTime": tx.get("blockTime"),
            "name": _clip(c["name"], 96),
            "metadataUri": _clip(c["uri"], 300),
            "feeLamports": lamports_received(tx, treasury),
        }
        if c["payer"] != minter:
            entry["note"] = "CreateV1 payer %s differs from the fee payer" % c["payer"]
        entries.append(entry)
    return entries


def verify_entry(entry: Dict[str, Any], rpc: Rpc, http: Http, max_attempts: int) -> None:
    entry["attempts"] = int(entry.get("attempts") or 0) + 1
    entry["checkedAt"] = iso(int(time.time()))
    # The asset as it stands now: the owner may have updated its uri or burned it since.
    uri = entry["metadataUri"]
    try:
        data = rpc.account_data(entry["asset"])
        if data is None:
            entry["burned"] = True
        else:
            acct = parse_core_asset_account(data)
            if acct:
                entry["owner"] = acct["owner"]
                if acct["uri"] != entry["metadataUri"]:
                    entry["currentUri"] = _clip(acct["uri"], 300)
                    uri = acct["uri"]
    except RpcError as e:
        log("  asset account %s: %s (verifying the mint-time uri)" % (entry["asset"], e))

    metadata, problems = fetch_metadata(uri, http)
    if metadata is None:
        entry["verdict"] = "error"
        entry["reasons"] = ["metadata unavailable: " + "; ".join(problems) if problems else "metadata unavailable"]
        if entry["attempts"] >= max_attempts:
            entry["verdict"] = "failed"
            entry["reasons"].append("gave up after %d runs" % entry["attempts"])
        return
    try:
        judge(entry, metadata, rpc.transaction)
    except RpcError as e:
        entry["verdict"] = "error"
        entry["reasons"] = ["RPC: %s" % e]


def summarize(certs: List[Dict[str, Any]]) -> Dict[str, int]:
    counts = {"total": len(certs), "verified": 0, "failed": 0, "unanchored": 0, "error": 0}
    for c in certs:
        counts[c.get("verdict", "error")] = counts.get(c.get("verdict", "error"), 0) + 1
    return counts


def _public(entry: Dict[str, Any]) -> Dict[str, Any]:
    return {k: v for k, v in entry.items() if not k.startswith("_")}


def build_report(old: Dict[str, Any], certs: List[Dict[str, Any]], cursor: Dict[str, Any],
                 treasury: str, rpc_url: str) -> Dict[str, Any]:
    certs = sorted(certs, key=lambda c: (c.get("mintSlot") or 0, c["asset"]), reverse=True)
    return {
        "schema": REPORT_SCHEMA,
        "about": "Unwrit Ages victory certificates, checked against the chain. 'verified': the anchored "
                 "start is proven by its on-chain memo and the map seed recomputes. 'unanchored': no "
                 "anchored start was claimed (stated, not accused). Checker: tools/verify/verify_certificates.py",
        "cluster": "mainnet-beta",
        "treasury": treasury,
        "coreProgram": MPL_CORE_PROGRAM_ID,
        "rpc": _host(rpc_url),
        "updatedAt": old.get("updatedAt"),
        "cursor": cursor,
        "summary": summarize(certs),
        "certificates": [_public(c) for c in certs],
    }


def _comparable(doc: Dict[str, Any]) -> str:
    d = dict(doc)
    d.pop("updatedAt", None)
    d.pop("rpc", None)
    return json.dumps(d, sort_keys=True)


def run(args: argparse.Namespace) -> int:
    http = Http(attempts=args.attempts)
    rpc = Rpc(args.rpc, http, min_interval=args.rpc_interval)
    old = {} if args.fresh else load_report(args.report)
    known: Dict[str, Dict[str, Any]] = {c["asset"]: dict(c) for c in old.get("certificates") or [] if c.get("asset")}
    cursor = old.get("cursor") or {}

    log("RPC %s, treasury %s" % (_host(args.rpc), args.treasury))
    sigs, new_cursor = collect_signatures(rpc, args.treasury, cursor, args.limit)
    log("read %d new treasury signatures%s" % (len(sigs), " (pending ranges left: %d)" % len(new_cursor["pending"]) if new_cursor["pending"] else ""))

    found = 0
    for i, s in enumerate(sigs):
        try:
            entries = process_signature(s, rpc, http, args.treasury, known)
        except RpcError as e:
            # Not knowing what a treasury transaction was means its range must be read again.
            log("  %s: %s - the cursor is not advanced; these signatures are read again next run" % (s["signature"], e))
            new_cursor = cursor
            break
        for e in entries:
            known[e["asset"]] = e
            found += 1
        if (i + 1) % 50 == 0:
            log("  %d/%d signatures, %d certificates so far" % (i + 1, len(sigs), found))
    log("%d new certificates" % found)

    to_check = [c for c in known.values() if args.recheck or c.get("verdict") not in FINAL_VERDICTS]
    for c in to_check:
        if args.recheck:
            c["attempts"] = 0
        verify_entry(c, rpc, http, args.max_attempts)
        log("  %s  %-10s %s" % (c["asset"], c["verdict"], "; ".join(c.get("reasons") or [])))

    report = build_report(old, list(known.values()), new_cursor, args.treasury, args.rpc)
    changed = _comparable(report) != _comparable(old) if old else True
    if changed:
        report["updatedAt"] = iso(int(time.time()))
    log("summary: %s; %d RPC calls; report %s" % (json.dumps(report["summary"]), rpc.calls,
                                                   "changed" if changed else "unchanged"))
    if args.dry_run:
        print(json.dumps(report, indent=2, ensure_ascii=False))
        return 0
    if changed:
        os.makedirs(os.path.dirname(args.report), exist_ok=True)
        tmp = args.report + ".tmp"
        with open(tmp, "w", encoding="utf-8", newline="\n") as f:
            json.dump(report, f, indent=2, ensure_ascii=False)
            f.write("\n")
        os.replace(tmp, args.report)
    return 0


def main(argv: Optional[List[str]] = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--rpc", default=os.environ.get("SOLANA_RPC_URL") or DEFAULT_RPC,
                   help="Solana JSON-RPC URL (default: $SOLANA_RPC_URL or the public mainnet endpoint)")
    p.add_argument("--report", default=DEFAULT_REPORT, help="report path (read as cache, written if changed)")
    p.add_argument("--treasury", default=TREASURY_ADDRESS)
    p.add_argument("--limit", type=int, default=20000, help="max treasury signatures read this run")
    p.add_argument("--dry-run", action="store_true", help="print the report to stdout, write nothing")
    p.add_argument("--fresh", action="store_true", help="ignore the existing report (no cache)")
    p.add_argument("--recheck", action="store_true", help="re-verify every cached certificate")
    p.add_argument("--attempts", type=int, default=6, help="HTTP/RPC retries per request")
    p.add_argument("--max-attempts", type=int, default=7, help="runs before unavailable metadata counts as failed")
    p.add_argument("--rpc-interval", type=float, default=float(os.environ.get("RPC_MIN_INTERVAL") or 0.25),
                   help="minimum seconds between RPC calls")
    args = p.parse_args(argv)
    try:
        return run(args)
    except RpcError as e:
        log("aborted: %s" % e)
        return 2


if __name__ == "__main__":
    sys.exit(main())
