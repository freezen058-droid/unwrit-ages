package com.unciv.logic.chain

import com.unciv.logic.GameInfo
import com.unciv.logic.IsPartOfGameInfoSerialization

/** Local scenario milestones, not a proof of legal play. A relay keeps the original deadline. */
class SharedScenario : IsPartOfGameInfoSerialization {
    var version = 1
    var id = ""
    var title = ""
    var description = ""
    var civilization = ""
    var startTurn = 0
    var duration = 20
    var technology = "Currency"
    var cityId = ""
    var cityName = ""
    var building = "Market"
    var opponent = ""
    var researchTurn = -1
    var constructionTurn = -1
    var holdTurn = -1
    var outcome = "" // empty = underway, completed / unfinished / defeated
    var finalGold = 0
    var finalCities = 0
    var finalAtWar = false
    var briefingShown = false
    var resultShown = false
    var chapter: SharedScenarioChapter? = null
    var navalCampaign: NavalCampaign? = null
    var nextChapterPlans = ArrayList<ScenarioChapterPlan>()
    var authoredChapterHistory = ArrayList<SharedScenario>()
    val authoredChapter: SharedScenario? get() = authoredChapterHistory.lastOrNull()
    val chapterNumber get() = authoredChapterHistory.size.takeIf { it > 0 }?.plus(1)
        ?: navalCampaign?.active?.number ?: (if (chapter != null) 2 else 1)
    val totalChapters get() = if (nextChapterPlans.isNotEmpty()) nextChapterPlans.size + 1
        else if (navalCampaign != null) 3 else if (id == "civilization-on-the-brink-v1") 2 else 1
    var freePlay = false

    val hasNextChapter get() = if (nextChapterPlans.isNotEmpty()) outcome == "completed" &&
        authoredChapterHistory.size < nextChapterPlans.size && (authoredChapter?.outcome ?: outcome) == "completed"
        else navalCampaign?.let { outcome == "completed" && it.hasNextChapter }
        ?: (id == "civilization-on-the-brink-v1" && outcome in listOf("completed", "unfinished") && chapter == null)
    val currentOutcome: String get() = authoredChapter?.outcome ?: navalCampaign?.active?.outcome ?: chapter?.outcome ?: outcome
    val needsPopup get() = authoredChapter?.let { !it.briefingShown || it.outcome.isNotEmpty() && !it.resultShown }
        ?: navalCampaign?.active?.let {
        !it.briefingShown || it.outcome.isNotEmpty() && !it.resultShown
    } ?: chapter?.takeIf { it.supported }?.let {
        !it.briefingShown || it.outcome.isNotEmpty() && !it.resultShown
    } ?: (!briefingShown || outcome.isNotEmpty() && !resultShown)

    /** Chapter one stays immutable. The next chapter starts when the player accepts it. */
    fun beginNextChapter(game: GameInfo): Boolean {
        if (!supported || !hasNextChapter || freePlay) return false
        if (nextChapterPlans.isNotEmpty()) {
            val civ = game.civilizations.firstOrNull { it.civID == civilization } ?: return false
            if (civ.isDefeated()) return false
            val plan = nextChapterPlans[authoredChapterHistory.size]
            if (!plan.supported || game.turns > Int.MAX_VALUE - plan.duration) return false
            val next = SharedScenario().apply {
                id = "${this@SharedScenario.id}-chapter-${this@SharedScenario.authoredChapterHistory.size + 2}"; title = this@SharedScenario.title
                civilization = this@SharedScenario.civilization; startTurn = game.turns; duration = plan.duration
                technology = plan.technology; building = plan.building; cityId = plan.cityId; cityName = plan.cityName
                opponent = this@SharedScenario.opponent
                if (civ.cities.any { it.id == cityId && it.cityConstructions.isBuilt(building) }) constructionTurn = game.turns
            }
            next.observe(game)
            authoredChapterHistory.add(next)
            return true
        }
        navalCampaign?.let { return it.beginNext(game, this) }
        val civ = game.civilizations.firstOrNull { it.civID == civilization } ?: return false
        if (civ.isDefeated() || game.turns > Int.MAX_VALUE - 15) return false
        chapter = SharedScenarioChapter().apply {
            path = if (this@SharedScenario.outcome == "completed") "renewal" else "recovery"
            startTurn = game.turns; startingGold = civ.gold
            researchTurn = this@SharedScenario.researchTurn
            constructionTurn = this@SharedScenario.constructionTurn
            if (path == "recovery" && constructionTurn < 0 && civ.cities.any {
                    it.id == cityId && it.cityConstructions.isBuilt(building) }) constructionTurn = game.turns
        }
        chapter!!.observe(game, this)
        return true
    }

    val deadline get() = startTurn + duration
    /** Remaining turns only while a supported challenge is active. */
    fun remainingTurns(turn: Int): Int? {
        if (!supported || freePlay || turn < startTurn) return null
        authoredChapter?.let { return it.remainingTurns(turn) }
        navalCampaign?.active?.let {
            if (!it.supported || it.outcome.isNotEmpty() || turn < it.startTurn) return null
            return (it.deadline - turn).takeIf { remaining -> remaining > 0 }
        }
        val activeChapter = chapter
        if (activeChapter != null) {
            if (!activeChapter.supported || activeChapter.outcome.isNotEmpty() || turn < activeChapter.startTurn) return null
            return (activeChapter.deadline - turn).takeIf { it > 0 }
        }
        if (outcome.isNotEmpty()) return null
        return (deadline - turn).takeIf { it > 0 }
    }
    val completedCount get() = listOf(researchTurn, constructionTurn, holdTurn).count { it >= 0 }
    val supported get() = version == 1 && id.isNotBlank() && civilization.isNotBlank() &&
        cityId.isNotBlank() && technology.isNotBlank() && building.isNotBlank() &&
        duration in 1..100 && startTurn in 0..(Int.MAX_VALUE - duration) &&
        nextChapterPlans.size <= 2 && nextChapterPlans.all { it.supported } &&
        authoredChapterHistory.size <= nextChapterPlans.size

    /** Immutable goal definition used to reject comparisons against a changed challenge. */
    fun definitionHash(): String = ChainWallet.sha256Hex(com.unciv.json.json().toJson(listOf(
        version.toString(), id, civilization, startTurn.toString(), duration.toString(),
        technology, cityId, building, opponent) +
        (navalCampaign?.let { listOf("naval-campaign-v1", it.enemyPortId, "25", "35", "3", "4", "2") } ?: emptyList()) +
        nextChapterPlans.flatMap { listOf("author-chapter-v1", it.technology, it.building, it.cityId, it.duration.toString()) }))

    fun copy(): SharedScenario = SharedScenario().also {
        it.version = version; it.id = id; it.title = title; it.description = description; it.civilization = civilization
        it.startTurn = startTurn; it.duration = duration; it.technology = technology
        it.cityId = cityId; it.cityName = cityName; it.building = building; it.opponent = opponent
        it.researchTurn = researchTurn; it.constructionTurn = constructionTurn; it.holdTurn = holdTurn
        it.outcome = outcome; it.finalGold = finalGold; it.finalCities = finalCities
        it.finalAtWar = finalAtWar; it.briefingShown = briefingShown; it.resultShown = resultShown
        it.chapter = chapter?.copy()
        it.navalCampaign = navalCampaign?.copy()
        it.nextChapterPlans = ArrayList(nextChapterPlans.map { plan -> plan.copy() })
        it.authoredChapterHistory = ArrayList(authoredChapterHistory.map { past -> past.copy() })
        it.freePlay = freePlay
    }

    /** Called from the engine as well as the UI, including automated turns. Never changes rules. */
    fun observe(game: GameInfo) {
        if (!supported || game.turns < startTurn) return
        chapter?.observe(game, this)
        navalCampaign?.let { it.active?.observe(game, this, it) }
        authoredChapter?.observe(game)
        if (outcome.isNotEmpty()) return
        val civ = game.civilizations.firstOrNull { it.civID == civilization } ?: return
        evaluate(game.turns, technology in civ.tech.techsResearched,
            civ.cities.any { it.id == cityId }, civ.isDefeated(), civ.gold, civ.cities.size,
            civ.diplomacy[opponent]?.diplomaticStatus?.name == "War")
    }

    /** A construction counts only when it is added while the scenario player owns the city. */
    fun constructed(turn: Int, owner: String, city: String, name: String) {
        authoredChapter?.constructed(turn, owner, city, name)
        chapter?.let {
            if (supported && it.path == "recovery" && it.outcome.isEmpty() &&
                turn in it.startTurn..it.deadline && owner == civilization && city == cityId &&
                name == building && it.constructionTurn < 0) it.constructionTurn = turn
        }
        if (supported && outcome.isEmpty() && turn in startTurn..deadline &&
            owner == civilization && city == cityId && name == building && constructionTurn < 0)
            constructionTurn = turn
    }

    fun evaluate(turn: Int, researched: Boolean, ownsCity: Boolean, defeated: Boolean,
                          gold: Int, cities: Int, atWar: Boolean) {
        if (!supported || outcome.isNotEmpty() || turn < startTurn) return
        // Missed checkpoints must not turn achievements earned after the deadline into success.
        if (turn <= deadline && researched && researchTurn < 0) researchTurn = turn
        if (defeated || turn >= deadline) {
            if (!defeated && turn == deadline && ownsCity) holdTurn = turn
            outcome = if (defeated) "defeated" else if (completedCount == 3) "completed" else "unfinished"
            finalGold = gold; finalCities = cities; finalAtWar = atWar
        }
    }
}
