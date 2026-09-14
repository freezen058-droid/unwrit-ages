# Privacy Policy

## We don't store personal information

At all, in any way. CivilWars has no account system, no analytics, and no ad or tracking SDKs.

## We do store your Multiplayer games

If you want to use an alternative server backend, you can set your server URL in the Options menu

## Wallet connection (optional)

CivilWars can optionally connect to a Solana wallet app (Phantom, Solflare, etc.) already
installed on your device, using the standard Solana Mobile Wallet Adapter. We never see your
seed phrase or private keys — the connection and every signature happen inside your own wallet
app, which you approve individually each time.

Connecting only shares your wallet's public address with CivilWars, for as long as the app is
open in that session. We don't store this address anywhere outside your device, and don't send
it to any server we control.

## On-chain save recording (optional, off by default)

If you turn this on in the Wallet menu, CivilWars can record a short proof-of-save on the
Solana blockchain, one save at a time, only when you (or, if you also enable the autosave
reminder, a prompt you approve) trigger it. Each record is a small "memo" transaction
containing the save's name and a cryptographic hash of the save file — never the save file
itself, and never anything about the save's content beyond that hash. You sign and pay the
small Solana network fee for this from your own connected wallet; we don't collect any fee and
don't submit anything on your behalf without your signature.

Once sent, this memo is public on the Solana blockchain like any other transaction — that's
inherent to how a public blockchain works, not something CivilWars adds on top. If you never
connect a wallet or never enable this option, nothing about your saves ever leaves your device.
