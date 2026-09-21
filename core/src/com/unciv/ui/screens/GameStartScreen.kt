package com.unciv.ui.screens

import com.unciv.ui.screens.basescreen.BaseScreen

/**
 * The root screen for the moment between launch and the main menu, while the atlas and skin load.
 *
 * Deliberately empty. It used to fade in `ExtraImages/banner.png` - upstream's UNCIV wordmark, and
 * then this fork's - but a splash is a thing a player looks at instead of playing, and this one was
 * on screen for a fraction of a second on a modern device. What is left is the clear colour, so the
 * app opens straight into the menu's own background rather than flashing a logo at it. The class
 * stays because [com.unciv.UncivGame] and [BaseScreen] both test for it by type: it is how they
 * tell "still starting up" from "a real screen".
 */
class GameStartScreen : BaseScreen()
