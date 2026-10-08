# Unwrit Ages

A turn-based 4X strategy game for Android, built for the Solana Seeker and live on the Solana dApp Store.
Found a city with one settler and take it from pottery to spaceflight; win by conquest, science,
culture or diplomacy. No ads, no pay-to-win, and it plays offline.

**Unwrit Ages is a fork of [Unciv](https://github.com/yairm210/Unciv) by Yair Morgenstern and
contributors, MPL-2.0 licensed** - see [LICENSE](LICENSE), [NOTICE.md](NOTICE.md) and
[MPL-NOTICE.md](MPL-NOTICE.md). The first commit in this repository (`4.21.13`) is Unciv's own
release, unchanged; every commit after it is ours. The game rules, map, AI and most of the engine
are Unciv's work. The art made for Unwrit Ages is not under the MPL - see
[ART-NOTICE.md](ART-NOTICE.md).

## Hackathon source and security evidence

The current Android 1.0.3 release is pinned to [v1.0.3-final-20261008](https://github.com/freezen058-droid/unwrit-ages/tree/v1.0.3-final-20261008). It includes strict finalized SKR transfer/memo binding, author verification, Android network/entry-point hardening, player-authored chapters, signed data-only challenge packs and matching Guide introductions. Behavioral regression: 874 tests, zero failures/errors, 16 skips. The 21 tip-verifier fixture cases were freshly rerun on October 8 and all passed; final UI changes were separately checked in the APK and on Seeker. Seeker checks cover wallet authorization, private restore, 1-SKR save cancellation/retry and the final Guide/goal editor. All challenge packs are initially disabled; shared saves and author-created chapters are available now. These manual checks do not change the earlier automated audit coverage.

[Download APK](https://unwritages.pages.dev/download/unwrit-ages-1.0.3.apk). SHA-256: `9080a31b28e17d299fff4b61e5bad63a971b20f17392ea3d9d94d53ad469fe24`.

See the [product deck and technical appendix](https://unwritages.pages.dev/hackathon/deck.pdf) and [file-by-file evidence with validation boundaries](https://unwritages.pages.dev/hackathon/source-evidence). These checks are not an independent security audit or evidence of player retention. The default branch contains development work outside the submitted APK; use the pinned release tag to review the submitted scope. Council, strategic chapter branches and result tiers remain future work.

## What this fork adds

On Solana, through the Mobile Wallet Adapter (`core/src/com/unciv/logic/chain/`,
`android/src/com/unciv/app/AndroidWalletService.kt`):

- **Victory certificate** - win, and mint the victory as an NFT (Metaplex Core): a painted stele that
  records who won, how, on which turn and with how many AutoPlay turns. The art and metadata are
  stored on Arweave. The fee (US$0.90) is paid in the same transaction, in SOL at the Pyth price or
  in SKR at the market price - the player picks the token before the wallet opens. Its lettering is
  drawn as outlines, so a phone's accessibility settings cannot restyle a permanent record.
- **Anchored start** - opt in, and a signed memo transaction seeds the map and links its starting point to the wallet.
  Anchor incurs the SOL network fee and records provenance; it does not prove the subsequent play was cheat-free.
- **Cloud saves (1 SKR each)** - record a save on-chain: it is uploaded to Arweave and named in a memo.
  Private saves are AES-256-GCM encrypted with a key derived from a wallet signature, so the same
  wallet can derive the key for restoration. Private-save data and cached wallet signatures require protection; wallet private keys remain in the wallet. Shared saves let anyone continue
  the game; a victory won from one says "Continued from turn N" on its certificate.
- **Shared-save gallery** - every shared save in one place, with no server: the records are read from
  the transactions that paid their 1 SKR, and each carries its civ, map, era and turn. Sort by what
  players tipped or by date, filter by civ, map and era, and preview the map (drawn from the save
  itself) before loading it. Players tip authors in SKR (1, 10, 17 for Unciv's founding year, or any
  amount); a tip counts only once its transaction shows the author received it. The first save ever
  shared is marked Genesis.
  A game taken over from a shared save and shared again names its parent, so the gallery shows each
  save's line of players ("relayed N times"), and a certificate won from a shared save names it.
  Saves shared from a Seeker are marked: the author's wallet holds a Seeker Genesis Token (checked
  from public chain data, as Solana Mobile's docs describe).

For the phone:

- New look (UI skin, tileset, icons, launcher art), text sized for a phone, a first-launch tour,
  a new-player guide, and full Traditional and Simplified Chinese.
- Fewer taps per turn (1.0.3): repeat a unit in a city's production queue, fortify all idle
  units at once, Automate on the first page, and cities that choose their own production.
- The Council (in progress, coming in 1.1): hand a city to the domestic advisor with an order - grow, production,
  research, treasury, culture, stockpile the military, hold - and it runs the city with the game's
  own AI, weighted by the order, reporting everything it does. When enemies mass near a city, the
  council asks what to do, with one-tap answers.

## Build and run

Requirements: JDK 21 and the Android SDK (set `sdk.dir` in `local.properties`).

```
./gradlew :android:assembleDebug        # APK in android/build/outputs/apk/debug/
./gradlew :desktop:run                  # the desktop version, without the wallet features
./gradlew :tests:test                   # unit tests
```

The debug APK runs on any Android phone with a Solana wallet app (the Seeker has one built in). Release signing reads its keystore from `local.properties` and is not part of
this repository.

---

*What follows is Unciv's own README.*

## What is this?

An open source, moddability-focused Android and Desktop remake of Civ V, made with [LibGDX](https://github.com/libgdx/libgdx).

## Is this any good?

Depends what you're looking for. If you're in the market for high-res graphics, amazing soundtracks, animations etc, I highly recommend Firaxis's Civ-V-like game, "Civilization V".

If you want a small, fast, moddable, FOSS, in-depth 4X that can still run on a potato, you've come to the right place :)

## How do I install?

- **Android** - [Google Play](https://play.google.com/store/apps/details?id=com.unciv.app) or [F-droid](https://f-droid.org/en/packages/com.unciv.app/)
- **Linux** - [itch.io](https://yairm210.itch.io/unciv), Flatpak via [Flathub](https://flathub.org/apps/details/io.github.yairm210.unciv), or [AUR](https://aur.archlinux.org/packages/unciv-bin)
- **Windows** - [Grab the MSI](https://github.com/yairm210/Unciv/releases/latest/download/Unciv.msi), or get from [itch.io](https://yairm210.itch.io/unciv), [Chocolatey](https://community.chocolatey.org/packages/unciv), or [Scoop](https://github.com/Calinou/scoop-games)
- **Raspberry Pi** - [Pi-apps](https://github.com/Botspot/pi-apps)
- **MacOS** - Via [Brew](https://brew.sh/) (`brew update && brew install unciv`) or install [with this guide](https://yairm210.github.io/Unciv/Other/Installing-on-macOS/) 
- Jars, APKs and Windows/Linux builds also available in [Releases](https://github.com/yairm210/Unciv/releases) (run jar with `java -jar Unciv.jar`) - *not recommended* since we update frequently and you will quickly become out-of-date
- [Build from scratch](https://yairm210.github.io/Unciv/Developers/Building-Locally/#without-android-studio) if that's your thing

## What's the roadmap?

In this order:

* Polish!
    * UI+UX improvements ([suggestions welcome!](https://github.com/yairm210/Unciv/issues/new?assignees=&labels=feature&template=feature_request.md&title=Feature+request%3A+))
    * Better automation, AI etc. in-game
* G&K mechanics - see [#4697](https://www.github.com/yairm210/Unciv/issues/4697)
* BNW mechanics - trade routes, world congress, etc.

## Contributing

Programmers start [here](https://yairm210.github.io/Unciv/Developers/Building-Locally/)!

Translators start [here](https://yairm210.github.io/Unciv/Translating/Translating/)! Language completion status [here](https://github.com/yairm210/Unciv/blob/master/android/assets/jsons/translations/completionPercentages.properties) 

Modders start [here](https://yairm210.github.io/Unciv/Modders/Mods/)!

You can join us in any of the open issues, or work on improving anything you want - once you're finished, issue a pull request and it'll go into the next version!

If not, you can help by spreading the word - vote for Unciv where you can, mention it on Reddit or Twitter etc, and help us with new ideas of how to get the word out!


## FAQ

### How about iOS?

I'm not planning on it. It means paying money to Apple, yet another release path, and since I don't have an iOS device it means I can't test it properly.

### Steam release?

Steam has decided that they don't want to host Unciv, they probably don't want to risk legal issues with Firaxis (although those should be non-existent, see below).
 
### Will you implement {feature}?

If it's in the original Civ V, then yes!

If not, then the feature won't be added to the base game - possibly it will be added as a way to mod the game, which is constantly expanding.

#### Why not? This is its own game, why not add features that weren't in Civ V?

Having a clear vision is important for actually getting things done.

Anyone can make a suggestion. Not all are good, viable, or simple. Not many can actually implement stuff.

As an open source project, this stuff is done in our spare time, of which there isn't much.

We need a clear-cut criteria to decide what to work on and what not to work on.

#### Will you implement Civ VI?

Considering how long it took to get this far, no.

### How can I learn to play? Where's the wiki?

All the tutorial information is available in-game at menu > civilopedia > tutorials

All the information is included in the amazing [Civ V wiki](https://civilization.fandom.com/wiki/)

Since this is a Civ V clone, you can search Google for how to play Civ V and there are loads of answers =)

Alternatively, you could [join us on Discord](https://discord.gg/bjrB4Xw) and ask there =D

### Aren't you basically making a Civ V clone? Is that even legal?

According to the [US Copyright Office FL-108](https://upload.wikimedia.org/wikipedia/commons/9/96/U.S._Copyright_Office_fl108.pdf), intellectual property rights *do not* apply to mechanics - as I'm sure you know, there are a billion Flappy Bird knockoffs.

It is definitely illegal:
 - To use any assets from the original game (images, sound etc) - they belong to Firaxis

It is probably illegal (no solid sources on this):
 - To use the Civilization name
 - To impersonate the Civ games (so calling yourself civi|zation with a similar logo, for instance)

Interestingly, [Civilization is a registered trademark](https://tsdr.uspto.gov/#caseNumber=74166752&caseType=SERIAL_NO&searchType=statusSearch), but it looks like it's only *that particular logo* which is trademarked, so technically you could make another game called "Civilization" and it'll stick. In any case we're not going there :) 

## Run with Docker [![Docker](https://github.com/yairm210/Unciv/actions/workflows/dockerPublish.yml/badge.svg)](https://github.com/yairm210/Unciv/actions/workflows/dockerPublish.yml)

If you have docker compose installed:

 ```$ docker compose build && docker compose up```

and then goto http://localhost:6901/vnc.html?password=headless

If just docker:

```$ docker build . -t unciv && docker run -d -p 6901:6901 -p 5901:5901 unciv  ```

Or just use our already built one:

```$ docker run -d -p 6901:6901 -p 5901:5901 ghcr.io/yairm210/unciv ```

and then goto http://localhost:6901/vnc.html?password=headless

## Licensing and credits

This game is available under the MPL 2.0 (see [LICENSE](LICENSE]). The media
files are authored by many people, available under a mix of CC BY-SA 4.0,
CC BY 3.0/4.0, CC0, Public Domain (see [docs/Credits.md](docs/Credits.md)).
