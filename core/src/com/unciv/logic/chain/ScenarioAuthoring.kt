package com.unciv.logic.chain

import com.unciv.logic.GameInfo
import java.util.UUID

object ScenarioAuthoring {
    fun canEdit(game: GameInfo): Boolean = game.sharedScenario == null ||
        game.sharedScenario!!.let { it.supported && it.navalCampaign == null && game.continuedFromSave.isEmpty() &&
            (it.authorDraft || runCatching { UUID.fromString(it.id) }.isSuccess) }

    fun plans(scenario: SharedScenario): List<ScenarioChapterPlan> = listOf(ScenarioChapterPlan().apply {
        technology = scenario.technology; building = scenario.building; cityId = scenario.cityId; cityName = scenario.cityName
        challengeGoals = ArrayList(scenario.challengeGoals.map { it.copy().apply { reset() } })
        duration = scenario.duration; optionalGoals = true
        militaryGoals = scenario.militaryGoals?.copy(); goalOrder = ArrayList(scenario.goalOrder)
        holdCityId = if (scenario.optionalGoals) scenario.holdCityId else scenario.cityId
        holdCityName = if (scenario.optionalGoals) scenario.holdCityName else scenario.cityName
    }) + scenario.nextChapterPlans.map { old -> old.copy().apply {
        if (!optionalGoals) { holdCityId = cityId; holdCityName = cityName }
        optionalGoals = true
    } }

    /** A single empty chapter means a normal save. Every chapter in a longer plan needs a goal. */
    fun draft(game: GameInfo, current: SharedScenario?, plans: List<ScenarioChapterPlan>): SharedScenario? {
        require(plans.size in 1..3)
        if (plans.size == 1 && plans.single().goalCount == 0) return null
        require(plans.all { it.goalCount > 0 }) { "Every chapter must have at least one goal." }
        val active = plans
        require(active.size <= 3 && active.all { it.supported })
        fun signatures(items: List<ScenarioChapterPlan>) = items.map {
            listOf(it.technology, it.building, it.cityId, it.holdCityId, it.duration.toString()) +
                (it.militaryGoals?.definitionParts() ?: emptyList()) + it.goalOrder + it.challengeGoals.flatMap { goal -> goal.definitionParts() }
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
            militaryGoals = first.militaryGoals?.copy(); goalOrder = ArrayList(first.goalOrder)
            challengeGoals = ArrayList(first.challengeGoals.map { it.copy().apply { reset() } })
            val values = ChallengeGoal.snapshot(game, civilization)
            challengeGoals.forEach { it.initialize(values) }
            version = if (active.any { it.challengeGoals.isNotEmpty() || it.duration > 100 }) 2 else 1
            nextChapterPlans = ArrayList(active.drop(1).map { it.copy() })
        }
    }
}
