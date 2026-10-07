package com.unciv.logic

import com.badlogic.gdx.Gdx
import com.unciv.logic.chain.ChallengePacks
import com.unciv.logic.chain.SignedChallengePacks
import com.unciv.json.json
import com.unciv.testing.BaseTestRunner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

@RunWith(BaseTestRunner::class)
class ChallengePackTests {
    private fun bundled() = Gdx.files.internal("jsons/ChallengePacks.json").readString("UTF-8")
    private fun payload() = String(Base64.getDecoder().decode(json().fromJson(SignedChallengePacks::class.java, bundled()).payload), Charsets.UTF_8)
    private fun checkSigned(content: String): Boolean {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val sign = Signature.getInstance("SHA256withRSA"); sign.initSign(key.private); sign.update(content.toByteArray(Charsets.UTF_8))
        val envelope = SignedChallengePacks().apply { payload = Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))
            signature = Base64.getEncoder().encodeToString(sign.sign()) }
        val publicKey = Base64.getEncoder().encodeToString(key.public.encoded)
        return runCatching { ChallengePacks.verify(json().toJson(envelope), publicKey) }.isSuccess
    }
    @Test fun bundledFeedContainsFiveOrderedPacksAndOnlyExpeditionIsInitiallyOpen() {
        val m = ChallengePacks.verify(bundled())
        assertEquals(listOf("expedition", "revival", "last-stand", "golden-age", "peaceful-rise"), m.packs.map { it.id })
        assertEquals(listOf("expedition"), m.packs.filter { it.enabled }.map { it.id })
        assertEquals(2, m.packs.first().goals.size)
        assertTrue(m.packs.all { it.guideTw.isNotEmpty() && it.guideCn.isNotEmpty() })
    }
    @Test fun modifiedPayloadAndWrongSignaturesAreRejected() {
        val envelope = json().fromJson(SignedChallengePacks::class.java, bundled())
        envelope.payload = Base64.getEncoder().encodeToString(payload().replace("Expedition", "Changed").toByteArray(Charsets.UTF_8))
        assertTrue(runCatching { ChallengePacks.verify(json().toJson(envelope)) }.isFailure)
        envelope.signature = Base64.getEncoder().encodeToString(ByteArray(384))
        assertTrue(runCatching { ChallengePacks.verify(json().toJson(envelope)) }.isFailure)
    }
    @Test fun validSignatureDoesNotPermitUnknownEngineMetricsOrExecutableTypeHints() {
        assertTrue(checkSigned(payload()))
        assertFalse(checkSigned(payload().replace("\"engine\":1", "\"engine\":2")))
        assertFalse(checkSigned(payload().replace("exploredTiles", "executeScript")))
        assertFalse(checkSigned(payload().replace("\"metric\":\"exploredTiles\"", "\"class\":\"java.lang.ProcessBuilder\",\"metric\":\"exploredTiles\"")))
        assertFalse(checkSigned(payload().replace("\"schema\":1", "\"schema\":1,\"script\":\"anything\"")))
    }
    @Test fun oversizedMalformedAndFutureBranchesAreRejected() {
        assertTrue(runCatching { ChallengePacks.verify("x".repeat(ChallengePacks.MAX_BYTES + 1)) }.isFailure)
        assertTrue(runCatching { ChallengePacks.verify("not-json") }.isFailure)
        assertFalse(checkSigned(payload().replace("\"mode\":\"milestone\"", "\"outcomeBranches\":[\"chapter-2\"],\"mode\":\"milestone\"")))
    }
    @Test fun onlyUnseenEnabledPackRevisionsAreIntroduced() {
        assertEquals(listOf("expedition"), ChallengePacks.unseen(emptyMap()).map { it.id })
        assertTrue(ChallengePacks.unseen(mapOf("expedition" to 1)).isEmpty())
        assertTrue(ChallengePacks.unseen(mapOf("expedition" to 2)).isEmpty())
    }
    @Test fun authoredChaptersStartFreshBaselinesAndKeepRulesAfterSerialization() {
        val test = com.unciv.testing.TestGame()
        test.makeHexagonalMap(4)
        val civ = test.addCiv(isPlayer = true)
        test.addCity(civ, test.getTile(0, 0))
        test.gameInfo.currentPlayer = civ.civID; test.gameInfo.currentPlayerCiv = civ
        val proto = ChallengePacks.verify(bundled()).packs.first().goals.first()
        val plans = (1..3).map { com.unciv.logic.chain.ScenarioChapterPlan().apply {
            optionalGoals = true; duration = 10
            challengeGoals.add(proto.copy().apply { conditions[0].target = 1 })
            goalOrder.add(proto.id)
        } }
        val scenario = requireNotNull(com.unciv.logic.chain.ScenarioAuthoring.draft(test.gameInfo, null, plans))
        assertEquals(2, scenario.version); assertEquals(1, scenario.goalCount)
        test.tileMap.values.first { !it.isExplored(civ) }.setExplored(civ, true)
        test.gameInfo.turns = 10; scenario.observe(test.gameInfo)
        assertEquals("completed", scenario.outcome)
        assertTrue(scenario.beginNextChapter(test.gameInfo))
        val next = requireNotNull(scenario.authoredChapter)
        assertEquals(-1, next.challengeGoals.single().completedTurn)
        assertEquals(0, next.completedCount)
        assertEquals(10, next.startTurn)
        val copy = json().fromJson(com.unciv.logic.chain.SharedScenario::class.java, json().toJson(scenario))
        assertEquals(scenario.definitionHash(), copy.definitionHash())
        assertEquals(next.challengeGoals.single().baseline, copy.authoredChapter!!.challengeGoals.single().baseline)
        val before = proto.conditions[0].target
        next.challengeGoals.single().conditions[0].target = 99
        assertEquals(before, proto.conditions[0].target)
    }

}
