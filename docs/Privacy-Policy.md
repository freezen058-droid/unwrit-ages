# Privacy Policy

Last updated 26 September 2026. Applies to Unwrit Ages 1.0.1 (`com.unwritages.app`).

The published copy of this policy is the one linked from the app under Options → About; this
file is its source.

## We collect no personal information

At all, in any way. Unwrit Ages has no account system, no analytics, and no advertising or
tracking SDKs, and there is nothing to opt out of because there is no collection to begin with.
When you use a Solana feature, the game's blockchain requests pass through a relay at
unwritages.pages.dev/rpc (Cloudflare), which forwards them to Helius and keeps no logs. A signed
transaction contains your public address, as every Solana transaction does. Files you choose to
put on-chain (a recorded save, a victory certificate's picture and record) are uploaded to
Arweave, a permanent public storage network, through ArDrive's Turbo service. The full policy is
at https://unwritages.pages.dev/privacy.html.

## Your saved games

Saves live on your device. A save leaves it only if you record it on-chain (below).

## Wallet connection — optional, off until you use it

Unwrit Ages can connect to a Solana wallet app already installed on your device (Phantom,
Solflare and others) through the standard Solana Mobile Wallet Adapter. We never see your seed
phrase or your private keys: the connection and every signature happen inside your own wallet
app, and you approve each one individually.

Connecting shares your wallet's public address with the game, for as long as the app is open in
that session. We don't store that address anywhere off your device and don't transmit it to any
server we control.

## Recording a save on-chain — optional, off by default

If you switch this on in the Wallet menu, the game can record a save on the Solana blockchain,
one save at a time, only when you trigger it. Each record is a small memo transaction containing
the save's name, a cryptographic hash of the save, and the addresses of a copy of the save stored
on Arweave, so you can restore it on another device with the same wallet.

- **Private (the default).** The copy is encrypted with a key that only your wallet can produce:
  the game asks your wallet to sign a fixed message, derives the key from that signature, keeps
  it in memory while the app is open and never stores or sends it. Anyone can see that an
  encrypted file exists; no one without your wallet can read it.
- **Shared (only if you switch it on).** The copy is not encrypted, so anyone can download it,
  load it and play on. A save holds the state of the game — the map, the civilizations, the names
  you gave your cities — and no personal information beyond what you typed into the game.

Both the record and the copy are **permanent**: a public blockchain and Arweave cannot delete
them, and neither can we. This costs **1 SKR** plus the ordinary Solana network fee, signed and
paid from your own connected wallet; the upload itself costs you nothing.

## Anchoring a game's start — optional, free

If you tick "Anchor the start on-chain" before a new game, your wallet signs a memo transaction
containing that game's random identifier, and the map is made from the signature. The memo is
public and permanent, and a certificate won in that game records your wallet address and that
signature so anyone can check it. You pay only the ordinary Solana network fee.

## The victory certificate — optional, and it costs money

If you win a game you may mint a certificate of that victory as an NFT into your own wallet.
Nothing mints without your signature, and declining costs you nothing.

What the certificate records, and therefore what becomes **permanently and publicly readable on
the Solana blockchain**:

| | |
|---|---|
| Recorded | The certificate's name and description; its picture, the stele carved with your civilization, victory, turn, year, difficulty, number of players, rivals, eliminated civilizations, game speed and map; attributes for the victory, civilization, turn, year, difficulty, speed, map, rivals eliminated, the game's origin (anchored start, unanchored, or continued from someone else's save), AutoPlay turns if any, and the emblem's credit; and, for anyone who wants to replay its shape, the game's identifier, map seed, chronicle of events and score, technology, military, territory and population curves. For an anchored game, the start's wallet address and signature. |
| Not recorded | Your save file. |
| Attached to | The wallet address you signed with, which is public by the nature of a public blockchain. |

A public blockchain is permanent and world-readable by design; that is inherent to how it works
rather than something this game adds. A minted certificate cannot be withdrawn from it.

The fee is **US$0.90**, charged in SOL converted at the live SOL/USD rate from an on-chain price
feed so the price you see does not drift, and held between hard bounds so a misbehaving feed can
only ever charge you less than the nominal fee, never more. You also pay the ordinary Solana
account rent and network fee, which go to the network and not to us.

**This and the 1 SKR save record are the only charges in Unwrit Ages.** There is no
subscription, no advertising, and nothing else to buy inside the game.

## Children

The game is suitable for general audiences and collects nothing from anyone. The wallet features
require a separately installed third-party wallet app and are not usable without one.

## Changes

If a future version changes what leaves your device, this page changes with it, and the date at
the top is the date it last did.
