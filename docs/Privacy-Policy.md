# Privacy Policy

Last updated 22 September 2026. Applies to Unwrit Ages 1.0.0 (`com.unwritages.app`).

The published copy of this policy is the one linked from the app under Options → About; this
file is its source.

## We collect no personal information

At all, in any way. Unwrit Ages has no account system, no analytics, and no advertising or
tracking SDKs. We operate no server that receives anything about you, and there is nothing to
opt out of because there is no collection to begin with.

## Your saved games

Saves live on your device. Multiplayer games are stored on the multiplayer server you choose; if
you would rather use a different backend, you can set your own server URL in Options.

## Wallet connection — optional, off until you use it

Unwrit Ages can connect to a Solana wallet app already installed on your device (Phantom,
Solflare and others) through the standard Solana Mobile Wallet Adapter. We never see your seed
phrase or your private keys: the connection and every signature happen inside your own wallet
app, and you approve each one individually.

Connecting shares your wallet's public address with the game, for as long as the app is open in
that session. We don't store that address anywhere off your device and don't transmit it to any
server we control.

## Recording a save on-chain — optional, off by default

If you switch this on in the Wallet menu, the game can record a proof-of-save on the Solana
blockchain, one save at a time, only when you trigger it. Each record is a small memo
transaction containing the save's name and a cryptographic hash of the save file — never the
save file itself, and nothing about its contents beyond that hash.

This costs **1 SKR** plus the ordinary Solana network fee, signed and paid from your own
connected wallet.

## The victory certificate — optional, and it costs money

If you win a game you may mint a certificate of that victory as an NFT into your own wallet.
Nothing mints without your signature, and declining costs you nothing.

What the certificate records, and therefore what becomes **permanently and publicly readable on
the Solana blockchain**:

| | |
|---|---|
| Recorded | The certificate's name and description, one illustration shared by every certificate, and six attributes: victory type, civilization, turn, in-game year, difficulty and game speed. |
| Not recorded | Your save file, the game's event history, and the ranking statistics. None of it is included. |
| Attached to | The wallet address you signed with, which is public by the nature of a public blockchain. |

A public blockchain is permanent and world-readable by design; that is inherent to how it works
rather than something this game adds. A minted certificate cannot be withdrawn from it.

The fee is **US$0.75**, charged in SOL converted at the live SOL/USD rate from an on-chain price
feed so the price you see does not drift, and held between hard bounds so a misbehaving feed can
only ever charge you less than the nominal fee, never more. You also pay the ordinary Solana
account rent and network fee, which go to the network and not to us.

**This is the only charge in Unwrit Ages.** There is no subscription, no advertising, and
nothing else to buy inside the game.

## Children

The game is suitable for general audiences and collects nothing from anyone. The wallet features
require a separately installed third-party wallet app and are not usable without one.

## Changes

If a future version changes what leaves your device, this page changes with it, and the date at
the top is the date it last did.
