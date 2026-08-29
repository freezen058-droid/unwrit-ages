# NOTICE

**CivilWars** is a fork of [Unciv](https://github.com/yairm210/Unciv) by Yair Morgenstern
and contributors, adding wallet login and on-chain save verification for the
Solana dApp Store.

## License

Unciv's source code is licensed under the Mozilla Public License 2.0 (MPL-2.0).
See [LICENSE](LICENSE) for the full text. This fork keeps the same license for
all files inherited from upstream Unciv. Any file we modify remains available
in source form in this repository, per MPL-2.0 §3.1/§3.2.

New files added by this fork specifically for wallet/on-chain integration are
noted in their own headers; unless stated otherwise they are also MPL-2.0.

## Assets

Unciv's art, audio, and other media assets are licensed individually under a
mix of CC BY-SA 4.0, CC BY 3.0/4.0, CC0, and Public Domain — see upstream's
credits for per-asset attribution. This fork has not added or replaced any
game assets; all in-game art/audio is unmodified from upstream Unciv and
retains its original license and attribution.

## Trademark / IP boundaries

- No assets, names, or logos from Firaxis Games' *Civilization* franchise are
  used in this project, consistent with upstream Unciv's policy.
- "CivilWars" is an original name for this fork and is not affiliated with,
  endorsed by, or associated with Take-Two Interactive, Firaxis Games, or the
  Unciv project maintainers.
- This fork does not use the Unciv name, logo, or contributor names to imply
  endorsement.

## What this fork changes

- Application id / package identity (`com.civilwars.app`) and display branding,
  to avoid impersonating or colliding with the upstream Unciv app on any store.
- Adds optional wallet login (Solana Mobile Wallet Adapter) and on-chain save
  verification. Game rules/mechanics are unmodified from upstream Unciv.

## Upstream project

Unciv: https://github.com/yairm210/Unciv — please direct issues about core
game mechanics, balance, or the base game itself upstream rather than to this
fork, unless they're specific to the wallet/on-chain features added here.
