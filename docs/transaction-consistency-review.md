# Transaction and app consistency review

Unwrit Ages · 8 October 2026 · developer-performed review

## What this review establishes

We reviewed all four player-facing transaction paths in the submitted Android source: anchored starts, cloud/shared saves, victory-certificate minting and SKR tips. We traced each payment through submission, app callbacks, persistence, retry and discovery. We found several places where a correct on-chain action can still produce confusing app behavior. A targeted save-payment recovery fix has been implemented in a separate development branch; it is **not included in the already-submitted APK**.

This is a team source review plus executed local tests and previously recorded device evidence. It is not an independent security audit, a complete fault-injected phone test, or an audit of Solana/Metaplex programs. Findings below describe supported code-level failure scenarios, not a claim that users have lost funds in each scenario.

Submitted source: [v1.0.3-final-20261008](https://github.com/freezen058-droid/unwrit-ages/tree/v1.0.3-final-20261008), commit `079209afbbb15b9bbf710c62302151ba9f3c48dd`. Submitted APK SHA-256: `9080a31b28e17d299fff4b61e5bad63a971b20f17392ea3d9d94d53ad469fe24`.

Fix source: [save-payment recovery commit](https://github.com/freezen058-droid/unwrit-ages/commit/972bb6dee9f04ec322971206a840416aab58ad8b). The production APK, store submission and original 2:59 demonstration remain unchanged.

## Evidence a reviewer can check

- [Machine-readable review and executed tests](https://unwritages.pages.dev/hackathon/transaction-consistency.json): file hashes, pinned source locations, actual test cases and open checks.
- [Existing 21-case tip-verifier results](https://unwritages.pages.dev/hackathon/tip-verification.json): finalized mainnet fixture acceptance and rejection of altered transfers/memos.
- [QA2 private-save evidence](https://unwritages.pages.dev/hackathon/private-save-recovery.json): successful retry, exact 1-SKR payment, expected payer/recipient and private-save memo.
- [QA2 finalized transaction](https://solscan.io/tx/5acvxBVce3Moztf1vqbCMvXFXeYpMUQForf75QiWKs8q17yVoBhhEfowGZXXgJgG814DaHysoLywbRPZmgfcttrV): the chain receipt is independently inspectable. It does not prove post-payment interruption recovery.
- [Full source evidence](https://unwritages.pages.dev/hackathon/source-evidence): prior device restoration, relay and current artifact checks, with their limits.

## Implemented save-payment repair

Previously, the wallet signed and sent the save payment. If its response was lost, the app lacked a durable receipt to check before a new payment. The new save path asks the wallet to **sign only**, checks that the transaction message is unchanged, and durably commits its signature, signed payload, storage memo, snapshot identity and blockhash expiry **before any broadcast**.

On retry or restart, it first checks the recorded signature using historical status lookup. A finalized successful receipt completes the original operation without another payment. An unresolved attempt remains pending; a retry may rebroadcast the exact original signed bytes, never request a second signature/payment for that unresolved operation. A new fee is allowed only after the original attempt has a finalized failure, or remains absent after finalized block height passes its signing lease. RPC errors or incomplete results remain inconclusive.

The journal is scoped by payer and exact named snapshot, serializes save-payment attempts within the process, and refuses unsafe/corrupt/full journal states. Completed receipts are retained; their broadcastable bytes are removed. The recovery copy is included in the existing Guide, rather than adding another entry point. A pending result no longer uses the ordinary definitive-failure message.

**Limits:** this is a source/test improvement awaiting phone acceptance and a future APK release. It does not repair mint/tip/Anchor recovery. It does not automatically migrate old unknown 1.0.3 payments into the journal; reinstalling/clearing app data loses this local journal. It relies on an honest, sufficiently synchronized historical RPC. The journal has a fail-closed 256-entry limit that needs an archive policy before high-volume release. Retrying still passes through the existing upload stage, so unreferenced duplicate uploads are possible even when no additional SKR payment occurs.

## Findings and current disposition

### TX-01 · Success shown before chain settlement · High

**Scenario:** RPC accepts a mint transaction but it is later dropped or fails. The app may already show the certificate as owned. Submitted save recording similarly reports success from a wallet-returned signature without an explicit app confirmation check.

**Evidence:** `AndroidWalletService.sendSignedTransaction`, `mintVictoryCertificate` and `sendMemoWithSkrFee`; `VictoryCertificateOffer.showMinted` writes the asset address and displays success. Returning a signature is not proof of settlement. [Solana sendTransaction reference](https://solana.com/docs/rpc/http/sendtransaction).

**Disposition:** save success now requires finalized state in the development fix. Mint remains open: persist signature/asset address and verify transaction plus resulting asset before success.

### TX-02 · Lost response can lead to another payment · High

**Scenario:** the first transaction lands, its callback is lost, and a user retries. Saves can create another paid record; a mint retry creates a fresh asset keypair; tips are separate transfers. The existing UI does not establish an end-to-end idempotent recovery guarantee.

**Evidence:** submitted `recordSaveHash`, `mintVictoryCertificate` and `tipSaveAuthor`; no durable pending-transaction journal on those submitted paths. `VictoryCertificateService` preserves uploaded metadata, not a paid mint's signature/asset identity.

**Disposition:** repaired for newly journaled saves in the development branch. Mint/tip recovery remains a priority. [Solana retry guidance](https://solana.com/developers/cookbook/transactions/retry) requires checking expiry before re-signing.

### TX-03 · Anchor timeout can leave an orphaned start · Medium

**Scenario:** an Anchor is sent but its confirmation check times out, the app restarts, or game generation subsequently fails. The UI can offer an unanchored start even if the memo later lands. The player may pay a network fee without the intended anchored game being retained.

**Evidence:** `anchorGameStart` does poll confirmation; `NewGameScreen.anchorThenStart` creates a new game ID and offers an unanchored fallback on error. No durable pending Anchor/map-setup recovery was found.

**Disposition:** open. Retain the operation's game ID, signer, signature and map setup; distinguish unknown from definitively failed before offering a replacement start. Anchor does not charge the 1-SKR save fee.

### TX-04 · Uploaded certificate metadata can become stale · Medium

**Scenario:** a cancelled mint's metadata is reused after the wallet or relevant game provenance changes. The minted owner can differ from the origin/appearance frozen in the reused metadata.

**Evidence:** `VictoryCertificateService.alreadyMintedUpload` and `remember` cache metadata by game ID alone; `mintConnected` recalculates origin for the current wallet but passes the previously uploaded URI. This is a source-supported stale-cache risk, not a live reproduced incident.

**Disposition:** open. Cache by immutable certificate-content hash and intended owner; reuse only matching metadata.

### TX-05 · Async wallet context can drift · Medium

**Scenario:** wallet connection changes while an upload/key request is in flight. A prebuilt save description or private key can be associated with the earlier account while a later payment uses the then-connected account. Most wrong-signature cases should be refused by the wallet/network, but the app should not rely on failure as its identity check.

**Evidence:** save data/meta are prepared before the asynchronous upload; later wallet calls read `_connectedAddress` again. `cloudSaveKey` caches the response under the address captured before its round trip.

**Disposition:** partly addressed by save repair payer capture/message validation. Full-flow account/key binding and wallet-switch fault testing remain open. No demonstrated unauthorized debit is claimed.

### TX-06 · Paid chain receipt does not guarantee immediate download · Medium

**Scenario:** storage accepts bytes and the paid record lands, but a gateway is unavailable or has not propagated them. The app may show a recorded save/certificate while downloading its content fails.

**Evidence:** `uploadToArweave` checks the returned ID; uploads precede payment. `CloudSaveDownload` has two gateways and bounded concurrency; `decodeCloudSave` checks decrypted content and hash. These protect correctness but cannot guarantee external availability.

**Disposition:** existing safeguards/tested fallback retained; open improvements include retry/backoff and checking retrievability before payment. Read/restore retries must never create a new fee transaction.

### TX-07 · Tip submission and display can diverge · Medium

**Scenario:** a tip has been submitted but not finalized, so support does not increase yet; a lost response can prompt another tip. Rapid repeated tip clicks may create competing requests.

**Evidence:** `SaveGalleryPopup.tip` says submission is being checked; rankings use finalized transaction verification rather than optimistically trusting the claimed amount. The tip chips are not disabled by a global durable payment operation.

**Disposition:** ranking integrity checks are already implemented and fixture-tested; pending-tip receipt, serialized submission and explicit settlement feedback remain open. Two separately authorized genuine tips may both legitimately count; transaction deduplication is not payer uniqueness.

### TX-08 · Old saves may disappear from the current query window · Medium

**Scenario:** a valid paid save is older than the newest 1,000 wallet/treasury transactions. Its bytes and chain record still exist, but the app's save list can omit it. Tips use a separate paginated scan.

**Evidence:** `listSaveRecords` calls `memoHistory` without the complete flag; ordinary save discovery reads one page. Save discovery parses memos; the stricter original-author/payment verification used by tips must not be represented as having verified every gallery record.

**Disposition:** open. Paginate save discovery, expose retry/incomplete history, and authenticate records before attributing authorship. This finding is about discovery/authentication scope, not a reproduced successful payment forgery.

### TX-09 · Failure wording can hide real SOL fees · Low

**Scenario:** an on-chain transaction fails. Application transfers and asset creation roll back, but the network transaction fee can still be charged. Treating every failure/timeout as “nothing charged” misleads players.

**Evidence:** submitted mint error hints and broad retry messaging need scope review. [Solana transaction semantics](https://solana.com/docs/core/transactions) distinguish reverted instructions from transaction fees.

**Disposition:** the new save failure message acknowledges possible SOL network fees; remaining mint/Anchor wording remains open. A wallet rejection before broadcast is different from an on-chain execution failure.

### TX-10 · Memo character limits are not transaction byte limits · Low

**Scenario:** a large late-game save or multibyte names/metadata produce a transaction larger than the network packet limit. Upload can complete before signing/submission rejects the oversized record, creating confusing failed-share feedback.

**Evidence:** submitted `recordSaveHash` checks memo character count; the certificate path separately checks serialized transaction size. Character count alone does not establish the save transaction's byte size.

**Disposition:** open. Check final serialized save-transaction size before upload/payment approval and report an actionable error. This scenario has not been reproduced on the phone; no successful debit is inferred from an oversized rejected payload.

## Existing safeguards and their boundaries

For saves, the SKR transfer and memo share one transaction; for certificates, fee transfer and Metaplex Core creation share one transaction. Thus an instruction failure cannot settle the application fee while omitting the paired on-chain record/asset. This atomicity does not synchronize app UI, guarantee external storage availability, or refund network fees.

Encrypted saves use authenticated encryption and restore-time hash checks. Tip validation binds recipient, signer, mint, memo, save and amount to actual token movements; ambiguous/malformed transfers are rejected. These are targeted protections, not proof of cheat-free play or external-program security.

## Tests executed for the development fix

Executed **67 tests**, with **0 failures, 0 errors and 0 skips**: 19 save-payment journal/decision cases, 12 cloud-save encryption/serialization cases, 4 download/fallback cases, 3 shared-save cache cases, 8 certificate-metadata cases and 21 tip-verifier cases. Android release Kotlin compilation passed. Separately, 4 locally mocked Pages RPC boundary tests passed. The earlier first build failed while edits were still in progress; these results refer to the subsequent completed-source run, whose final log reports BUILD SUCCESSFUL.

The new tests simulate restart by reconstructing the journal from serialized storage, plus lost UI acknowledgement, unknown outcomes, finalized failure/expiry, wallet separation, corrupt storage and write failures. They execute the real journal and decision code. They do **not** execute Android SharedPreferences durability, real MWA interruption, the RPC transport, or a complete phone lifecycle. Android compilation checks the integration's API/types; it does not replace those runtime checks.

Reproduce with JDK 17 and an Android SDK: `gradlew.bat tests:test --tests com.unciv.logic.SavePaymentJournalTests --tests com.unciv.logic.CloudSaveTests --tests com.unciv.logic.CloudSaveDownloadTests --tests com.unciv.logic.SharedSaveCacheTests --tests com.unciv.logic.CertificateMetadataTests --tests com.unciv.logic.TipTransactionVerifierTests android:compileReleaseKotlin --max-workers=2`.

## Checks still required before shipping the repair

Confirm the sign-only wallet flow on Seeker; inject loss of network response after broadcast; restart the app before callback; verify no second debit and successful restore of the original snapshot. Repeat for delayed/finalized failures, wallet switching, full/corrupt journal and the required RPC method. Extend the same lifecycle model to mint, Anchor and tips. No new mainnet payment or device access was required for this review.


## Pinned source locations

- [android/src/com/unciv/app/AndroidWalletService.kt: private suspend fun sendSignedTransaction, line 455](https://github.com/freezen058-droid/unwrit-ages/blob/079209afbbb15b9bbf710c62302151ba9f3c48dd/android/src/com/unciv/app/AndroidWalletService.kt#L455)
- [android/src/com/unciv/app/AndroidWalletService.kt: override fun mintVictoryCertificate, line 656](https://github.com/freezen058-droid/unwrit-ages/blob/079209afbbb15b9bbf710c62302151ba9f3c48dd/android/src/com/unciv/app/AndroidWalletService.kt#L656)
- [android/src/com/unciv/app/AndroidWalletService.kt: private suspend fun awaitConfirmation, line 956](https://github.com/freezen058-droid/unwrit-ages/blob/079209afbbb15b9bbf710c62302151ba9f3c48dd/android/src/com/unciv/app/AndroidWalletService.kt#L956)
- [android/src/com/unciv/app/AndroidWalletService.kt: override fun anchorGameStart, line 994](https://github.com/freezen058-droid/unwrit-ages/blob/079209afbbb15b9bbf710c62302151ba9f3c48dd/android/src/com/unciv/app/AndroidWalletService.kt#L994)
- [android/src/com/unciv/app/AndroidWalletService.kt: override fun cloudSaveKey, line 1047](https://github.com/freezen058-droid/unwrit-ages/blob/079209afbbb15b9bbf710c62302151ba9f3c48dd/android/src/com/unciv/app/AndroidWalletService.kt#L1047)
- [android/src/com/unciv/app/AndroidWalletService.kt: override fun listSaveRecords, line 1094](https://github.com/freezen058-droid/unwrit-ages/blob/079209afbbb15b9bbf710c62302151ba9f3c48dd/android/src/com/unciv/app/AndroidWalletService.kt#L1094)
- [android/src/com/unciv/app/AndroidWalletService.kt: private suspend fun memoHistory, line 1117](https://github.com/freezen058-droid/unwrit-ages/blob/079209afbbb15b9bbf710c62302151ba9f3c48dd/android/src/com/unciv/app/AndroidWalletService.kt#L1117)
- [android/src/com/unciv/app/AndroidWalletService.kt: override fun tipSaveAuthor, line 1308](https://github.com/freezen058-droid/unwrit-ages/blob/079209afbbb15b9bbf710c62302151ba9f3c48dd/android/src/com/unciv/app/AndroidWalletService.kt#L1308)
- [android/src/com/unciv/app/AndroidWalletService.kt: override fun recordSaveHash, line 1481](https://github.com/freezen058-droid/unwrit-ages/blob/079209afbbb15b9bbf710c62302151ba9f3c48dd/android/src/com/unciv/app/AndroidWalletService.kt#L1481)
- [android/src/com/unciv/app/AndroidWalletService.kt: private fun sendMemoWithSkrFee, line 1503](https://github.com/freezen058-droid/unwrit-ages/blob/079209afbbb15b9bbf710c62302151ba9f3c48dd/android/src/com/unciv/app/AndroidWalletService.kt#L1503)
- [core/src/com/unciv/logic/files/UncivFiles.kt: private fun recordSaveHashOnChainIfEnabled, line 206](https://github.com/freezen058-droid/unwrit-ages/blob/079209afbbb15b9bbf710c62302151ba9f3c48dd/core/src/com/unciv/logic/files/UncivFiles.kt#L206)
- [core/src/com/unciv/logic/chain/VictoryCertificateService.kt: fun alreadyMintedUpload, line 23](https://github.com/freezen058-droid/unwrit-ages/blob/079209afbbb15b9bbf710c62302151ba9f3c48dd/core/src/com/unciv/logic/chain/VictoryCertificateService.kt#L23)
- [core/src/com/unciv/logic/chain/VictoryCertificateService.kt: private fun mintConnected, line 63](https://github.com/freezen058-droid/unwrit-ages/blob/079209afbbb15b9bbf710c62302151ba9f3c48dd/core/src/com/unciv/logic/chain/VictoryCertificateService.kt#L63)
- [core/src/com/unciv/logic/chain/VictoryCertificateService.kt: fun remember, line 93](https://github.com/freezen058-droid/unwrit-ages/blob/079209afbbb15b9bbf710c62302151ba9f3c48dd/core/src/com/unciv/logic/chain/VictoryCertificateService.kt#L93)
- [core/src/com/unciv/ui/screens/newgamescreen/NewGameScreen.kt: private fun anchorThenStart, line 249](https://github.com/freezen058-droid/unwrit-ages/blob/079209afbbb15b9bbf710c62302151ba9f3c48dd/core/src/com/unciv/ui/screens/newgamescreen/NewGameScreen.kt#L249)
- [core/src/com/unciv/ui/screens/victoryscreen/VictoryCertificateOffer.kt: const val RETRY_HINT, line 48](https://github.com/freezen058-droid/unwrit-ages/blob/079209afbbb15b9bbf710c62302151ba9f3c48dd/core/src/com/unciv/ui/screens/victoryscreen/VictoryCertificateOffer.kt#L48)
- [core/src/com/unciv/ui/screens/victoryscreen/VictoryCertificateOffer.kt: private fun showMinted, line 136](https://github.com/freezen058-droid/unwrit-ages/blob/079209afbbb15b9bbf710c62302151ba9f3c48dd/core/src/com/unciv/ui/screens/victoryscreen/VictoryCertificateOffer.kt#L136)
- [core/src/com/unciv/ui/screens/savescreens/SaveGalleryPopup.kt: private fun tip(, line 508](https://github.com/freezen058-droid/unwrit-ages/blob/079209afbbb15b9bbf710c62302151ba9f3c48dd/core/src/com/unciv/ui/screens/savescreens/SaveGalleryPopup.kt#L508)
- [core/src/com/unciv/logic/chain/CloudSaveDownload.kt: suspend fun fetch, line 16](https://github.com/freezen058-droid/unwrit-ages/blob/079209afbbb15b9bbf710c62302151ba9f3c48dd/core/src/com/unciv/logic/chain/CloudSaveDownload.kt#L16)
- [core/src/com/unciv/logic/chain/TipTransactionVerifier.kt: fun verifyTip, line 103](https://github.com/freezen058-droid/unwrit-ages/blob/079209afbbb15b9bbf710c62302151ba9f3c48dd/core/src/com/unciv/logic/chain/TipTransactionVerifier.kt#L103)
- [core/src/com/unciv/logic/chain/TipTransactionVerifier.kt: fun verifyAuthor, line 111](https://github.com/freezen058-droid/unwrit-ages/blob/079209afbbb15b9bbf710c62302151ba9f3c48dd/core/src/com/unciv/logic/chain/TipTransactionVerifier.kt#L111)
