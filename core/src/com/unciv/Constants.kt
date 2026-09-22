package com.unciv

object Constants {
    const val settler = "Settler"
    const val eraSpecificUnit = "Era Starting Unit"
    const val lowercaseAll = "all"
    const val uppercaseAll = "All"
    val all = setOf(uppercaseAll, lowercaseAll)
    const val NO_ID = -1

    const val english = "English"

    // Terrain
    const val impassable = "Impassable"
    const val ocean = "Ocean"

    /** The "Coast" _terrain_
     *  @see com.unciv.models.ruleset.tile.Terrain.isCoast
     */
    @Deprecated("By PR #15123, except for tests. Remove use in Terrain and this deprecation after a grace period.")
    const val coast = "Coast"
    /** The "Coastal" terrain _filter_ */
    const val coastal = "Coastal"

    /** Used as filter and the name of the pseudo-TerrainFeature defining river Stats */
    const val river = "River"

    const val mountain = "Mountain"
    const val hill = "Hill"
    const val plains = "Plains"
    const val desert = "Desert"
    const val grassland = "Grassland"
    // TODO: GameStarter places "Tundra" startBias first. Can we make it generic by checking count by terrain?
    const val tundra = "Tundra" 
    const val snow = "Snow"

    const val forest = "Forest"
    const val jungle = "Jungle"
    const val ice = "Ice"
    val vegetation = arrayOf(forest, jungle)

    // Note the difference in case. **Not** interchangeable!
    // TODO this is very opaque behaviour to modders
    /** The "Fresh water" terrain _unique_ */
    const val freshWater = "Fresh water"
    /** The "Fresh Water" terrain _filter_ */
    const val freshWaterFilter = "Fresh Water"

    const val barbarianEncampment = "Barbarian encampment"
    const val cityCenter = "City center"
    const val allRoad = "All Road"

    // Treaties
    const val peaceTreaty = "Peace Treaty"
    const val researchAgreement = "Research Agreement"
    const val defensivePact = "Defensive Pact"

    // Agreements
    const val openBorders = "Open Borders"

    // Other trade items
    const val acceptEmbassy = "Accept Embassy"
    const val goldPerTurn = "Gold per turn"
    const val flatGold = "Gold"

    /** Used as origin in StatMap or ResourceSupplyList, or the toggle button in DiplomacyOverviewTab */
    const val cityStates = "City-States"
    /** Used as origin in ResourceSupplyList */
    const val tradable = "Tradable"

    const val random = "Random"
    const val unknownNationName = "???"
    const val unknownCityName = "???"

    const val fort = "Fort"

    const val futureTech = "Future Tech"
    // Easter egg name. Is to avoid conflicts when players name their own religions.
    // This religion name should never be displayed.
    const val noReligionName = "The religion of TheLegend27"
    const val spyHideout = "Spy Hideout"

    const val neutralVictoryType = "Neutral"

    const val cancelImprovementOrder = "Cancel improvement order"
    const val tutorialPopupNamePrefix = "Tutorial: "
    const val thisUnit = "This Unit"
    const val targetUnit = "Target Unit"

    const val OK = "OK"
    const val close = "Close"
    const val cancel = "Cancel"
    const val yes = "Yes"
    const val no = "No"
    const val loading = "Loading..."
    const val working = "Working..."

    const val barbarians = "Barbarians"
    const val spectator = "Spectator"
    const val humanPlayer = "Human player"
    const val aiPlayer = "AI player"

    const val embarked = "Embarked"
    const val wounded = "Wounded"

    const val remove = "Remove "
    const val repair = "Repair"

    const val uniqueOrDelimiter = "\" OR \""
    const val stringSplitCharacter = '␟' // U+241 - Unit separator character. Used to join texts and split them with a char that is virtually guaranteed to not be used in normal text. 


    const val simulationCiv1 = "SimulationCiv1"
    const val simulationCiv2 = "SimulationCiv2"

    const val dropboxMultiplayerServer = "Dropbox"
    const val uncivXyzServer = "https://uncivserver.xyz"

    /** Where a recipient of a published build can obtain the Source Code Form of the
     *  MPL-2.0 covered files this fork modified - the list is in MPL-NOTICE.md.
     *  MPL-2.0 section 3.2 requires this to be offered alongside any distributed binary.
     *  A repository URL or a contact address; shown under Options - About.
     *  MUST be filled in before a public release: left empty, the About tab says so out
     *  loud rather than quietly omitting the offer.
     *  Same string as the download link on https://unwritages.pages.dev/license - one
     *  place per release, so the two cannot drift. Regenerate the archive with
     *  tools/mpl_source_bundle.py --stage-website and redeploy the site. */
    const val sourceOfferUrl = "https://unwritages.pages.dev/mpl-source-1.0.0.tar.gz"

    /** Where the published privacy policy lives - shown under Options - About, and the same URL
     *  given to the store as privacy_policy_url. The dApp Store developer agreement requires the
     *  policy to be reachable from inside the app, not only from the listing, so an empty value
     *  says so out loud rather than quietly omitting the link. Source: docs/Privacy-Policy.md. */
    const val privacyPolicyUrl = "https://unwritages.pages.dev/privacy.html"

    const val defaultTileset = "UnwritAges"
    /** Default for TileSetConfig.fallbackTileSet - Don't change unless you've also moved the crosshatch, borders, and arrows as well */
    const val defaultFallbackTileset = "FantasyHex"
    const val defaultUnitset = "AbsoluteUnits"
    const val defaultSkin = "Minimal"
    const val defaultFallbackSkin = "Minimal"

    /**
     * Use this to determine whether a [MapUnit][com.unciv.logic.map.mapunit.MapUnit]'s movement is exhausted
     * (currentMovement <= this) if and only if a fuzzy comparison is needed to account for Float rounding errors.
     * _Most_ checks do compare to 0!
     */
    const val minimumMovementEpsilon = 0.05f  // 0.1f was used previously, too - here for global searches
    const val aiPreferInquisitorOverMissionaryPressureDifference = 3000f

    const val defaultFontSize = 18
    const val headingFontSize = 24
    const val smallerHeadingFontSize = 20

    /** URL to the root of the Unciv repository, including trailing slash */
    // Note: Should the project move, this covers external links, but not comments e.g. mentioning issues
    const val uncivRepoURL = "https://github.com/yairm210/Unciv/"
    /** URL to the wiki, including trailing slash */
    const val wikiURL = "https://yairm210.github.io/Unciv/"
}
