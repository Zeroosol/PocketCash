package com.patternsystems.pocketcash.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId

/** spec/vectors/cycles.json: the numbers the iPhone and Android apps must compute the same way. */
class CycleVectorsTest {
    private val file = File(System.getProperty("pocketcash.vectors") ?: "../../spec/vectors", "cycles.json")
    private val cases = Json.parseToJsonElement(file.readText()).jsonObject["cases"]!!.jsonArray

    @TestFactory
    fun vectors(): List<DynamicTest> {
        assertTrue(cases.size >= 4, "expected the shared vectors, found ${cases.size} cases")
        return cases.map { element ->
            val case = element.jsonObject
            DynamicTest.dynamicTest(case.str("name")) { check(case) }
        }
    }

    private fun check(case: JsonObject) {
        val now = Instant.parse(case.str("now"))
        val zone = ZoneId.of(case.str("timeZone"))
        val txs = case["transactions"]!!.jsonArray.map { t ->
            val o = t.jsonObject
            CashTransaction(
                id = o.str("id"),
                walletId = "w",
                amount = BigDecimal(o.str("amount")),
                timestamp = Instant.parse(o.str("timestamp")).toEpochMilli(),
                kind = Json.decodeFromString(Kind.serializer(), "\"${o.str("kind")}\""),
                tag = o.strOrNull("tag"),
            )
        }
        val expected = case["expected"]!!.jsonObject
        val name = case.str("name")

        val balance = Analytics.balance(txs)
        assertDecimal(expected.str("balance"), balance, "$name balance")
        assertDecimal(expected.str("todaySpent"), Analytics.todaySpent(txs, now, zone), "$name todaySpent")

        val burn = Analytics.burnRatePerDay(txs, now)
        assertNumber(expected["burnRatePerDay"], burn, "$name burnRatePerDay")
        assertNumber(expected["runwayDays"], Analytics.runwayDays(balance, burn), "$name runwayDays")

        val cycles = Analytics.cycles(txs, now)
        val want = expected["cycles"]!!.jsonArray
        assertEquals(want.size, cycles.size, "$name cycle count")
        want.zip(cycles).forEachIndexed { i, (w, got) ->
            val e = w.jsonObject
            val at = "$name cycle $i"
            assertEquals(e["isATM"]!!.jsonPrimitive.boolean, got.isATM, "$at isATM")
            assertEquals(Instant.parse(e.str("start")), got.start, "$at start")
            assertEquals(e.strOrNull("end")?.let(Instant::parse), got.end, "$at end")
            assertEquals(e["isOpen"]!!.jsonPrimitive.boolean, got.isOpen, "$at isOpen")
            assertDecimal(e.str("withdrawn"), got.withdrawn, "$at withdrawn")
            assertDecimal(e.str("spent"), got.spent, "$at spent")
            assertDecimal(e.str("inflow"), got.inflow, "$at inflow")
            assertNumber(e["days"], got.days, "$at days")
            assertNumber(e["avgDaily"], got.avgDaily, "$at avgDaily")
            val top = e.strOrNull("topAmount")
            if (top == null) assertNull(got.topAmount, "$at topAmount") else assertDecimal(top, got.topAmount, "$at topAmount")
            assertEquals(e["topAmountCount"]!!.jsonPrimitive.int, got.topAmountCount, "$at topAmountCount")
            assertDecimal(e.str("topAmountTotal"), got.topAmountTotal, "$at topAmountTotal")
            assertEquals(e.strOrNull("topTag"), got.topTag, "$at topTag")
        }
    }

    private fun assertDecimal(expected: String, actual: BigDecimal?, what: String) {
        assertNotNull(actual, what)
        assertEquals(0, BigDecimal(expected).compareTo(actual), "$what: expected $expected, got ${actual!!.toPlainString()}")
    }

    private fun assertNumber(expected: JsonElement?, actual: Double?, what: String) {
        if (expected == null || expected is JsonNull) {
            assertNull(actual, what)
            return
        }
        assertNotNull(actual, what)
        assertEquals(expected.jsonPrimitive.double, actual!!, 0.01, what)
    }

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
    private fun JsonObject.strOrNull(key: String): String? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content
}
