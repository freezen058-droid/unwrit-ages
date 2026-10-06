package com.unciv.logic.chain

import com.unciv.logic.GameInfo
import java.util.UUID

object ScenarioAuthoring {
    fun canEdit(game: GameInfo): Boolean = game.sharedScenario == null ||
        game.sharedScenario!!.let { it.supported && it.navalCampaign == null && game.continuedFromSave.isEmpty() &&
            (it.authorDraft || runCatching { UUID.fromString(it.id) }.isSuccess) }

    fun plans(scenario: SharedScenario): List<ScenarioChapterPlan> = listOf(ScenarioChapterPlan().apply {
        technology = scenario.technology; building = scenario.building; cityId = scenario.cityId; cityName = scenario.cityName
        duration = scenario.duration; optionalGoals = true
        holdCityId = if (scenario.optionalGoals) scenario.holdCityId else scenario.cityId
        holdCityName = if (scenario.optionalGoals) scenario.holdCityName else scenario.cityName
    }) + scenario.nextChapterPlans.map { old -> old.copy().apply {
        if (!optionalGoals) { holdCityId = cityId; holdCityName = cityName }
        optionalGoals = true
    } }

    /** No active chapters means a normal save. Changing a definition never inherits old results. */
    fun draft(game: GameInfo, current: SharedScenario?, plans: List<ScenarioChapterPlan>): SharedScenario? {
        val active = plans.filter { it.goalCount > 0 }
        if (active.isEmpty()) return null
        require(active.size <= 3 && active.all { it.supported })
        fun signatures(items: List<ScenarioChapterPlan>) = items.map {
            listOf(it.technology, it.building, it.cityId, it.holdCityId, it.duration.toString())
        }
        if (current?.authorDraft == true && signatures(ScenarioAuthoring.plans(current)) == signatures(active)) return current.copy()
        val civ = game.getCurrentPlayerCivilization()
        val first = active.first()
        return SharedScenario().apply {
            id = UUID.randomUUID().toString(); title = current?.title ?: "Shared save goals"
            civilization = civ.civID; opponent = civ.getCivsAtWarWith().firstOrNull()?.civID.orEmpty()
            startTurn = game.turns; duration = first.duration
            technology = first.technology; building = first.building; cityId = first.cityId; cityName = first.cityName
            optionalGoals = true; holdCityId = first.holdCityId; holdCityName = first.holdCityName; authorDraft = true
            nextChapterPlans = ArrayList(active.drop(1).map { it.copy() })
        }
    }
}
