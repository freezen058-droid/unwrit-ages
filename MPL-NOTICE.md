# MPL-2.0 notice

CivilWars is a fork of **Unciv** by Yair Morgenstern and contributors,
<https://github.com/yairm210/Unciv>, licensed under the
**Mozilla Public License, version 2.0** (see `LICENSE`).

MPL-2.0 is a *per-file* copyleft. It covers the files that came from Unciv: if we
distribute a binary, the Source Code Form of every **Covered File we modified** must be
made available to the people who receive that binary, under MPL-2.0. Files we **added** —
the art under `android/Images.*`, the tileset under
`android/Images.Tilesets/TileSets/CivilWars/`, and the tooling under
`pic/batch_review/_sd/` — are not Covered Software and are not published by this notice.

## Covered files modified in this fork

Generated 2026-09-17 from `git diff --name-only upstream/master..HEAD`.

* `android/AndroidManifest.xml`
* `android/build.gradle.kts`
* `android/res/values/strings.xml`
* `android/res/values/uncivicon_background.xml`
* `android/src/com/unciv/app/AndroidLauncher.kt`
* `android/src/com/unciv/app/AndroidWalletService.kt`
* `android/src/com/unciv/app/WalletBridgeActivity.kt`
* `build.gradle.kts`
* `buildSrc/src/main/kotlin/AndroidImagePacker.kt`
* `buildSrc/src/main/kotlin/BuildConfig.kt`
* `core/src/com/unciv/Constants.kt`
* `core/src/com/unciv/logic/chain/ChainWallet.kt`
* `core/src/com/unciv/logic/chain/PlatformWalletService.kt`
* `core/src/com/unciv/logic/files/UncivFiles.kt`
* `core/src/com/unciv/models/metadata/GameSettings.kt`
* `core/src/com/unciv/models/skins/SkinConfig.kt`
* `core/src/com/unciv/ui/images/ImageGetter.kt`
* `core/src/com/unciv/ui/images/Portrait.kt`
* `core/src/com/unciv/ui/popups/WalletPopup.kt`
* `core/src/com/unciv/ui/popups/options/AboutTab.kt`
* `core/src/com/unciv/ui/popups/options/AdvancedTab.kt`
* `core/src/com/unciv/ui/popups/options/OptionsPopupPages.kt`
* `core/src/com/unciv/ui/screens/mainmenuscreen/MainMenuScreen.kt`
* `core/src/com/unciv/ui/screens/pickerscreens/GreatPersonPickerScreen.kt`
* `core/src/com/unciv/ui/screens/pickerscreens/UnitRenamePopup.kt`
* `core/src/com/unciv/ui/screens/savescreens/QuickSave.kt`
* `core/src/com/unciv/ui/screens/savescreens/SaveGameScreen.kt`
* `core/src/com/unciv/ui/screens/worldscreen/WorldScreen.kt`
* `desktop/src/com/unciv/app/desktop/DesktopLauncher.kt`
* `desktop/src/com/unciv/app/desktop/DesktopWalletService.kt`

## How the source is offered

Set `BuildConfig.sourceOfferUrl` to the place these files can be obtained before you
publish a build. Either is enough:

* a public repository containing the files listed above, or
* a written offer — a contact address where a recipient can request them.

The app shows whichever is set under **Options → About**. Shipping with it empty means
the binary is distributed without the offer MPL-2.0 §3.2 requires.
