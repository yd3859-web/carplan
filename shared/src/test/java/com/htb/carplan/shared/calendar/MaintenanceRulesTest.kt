package com.htb.carplan.shared.calendar

import com.htb.carplan.shared.model.MaintenanceItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MaintenanceRulesTest {
    private val d = LocalDate.of(2026, 10, 30)

    @Test fun `engine oil - the km rule comes first when you drive a lot`() {
        val due = MaintenanceRules.nextDue(MaintenanceItem.ENGINE_OIL, d, 20_000.0, MaintenanceRules.DEFAULTS.getValue(MaintenanceItem.ENGINE_OIL), kmPerDay = 100.0)!!
        assertTrue(due.byKm); assertEquals(30_000.0, due.dueKm!!, 0.0); assertEquals(d.plusDays(100), due.date)
    }
    @Test fun `engine oil - the date rule comes first when you barely drive`() {
        val due = MaintenanceRules.nextDue(MaintenanceItem.ENGINE_OIL, d, 20_000.0, MaintenanceRules.DEFAULTS.getValue(MaintenanceItem.ENGINE_OIL), kmPerDay = 10.0)!!
        assertTrue(!due.byKm); assertEquals(d.plusDays(365), due.date)
    }
    @Test fun `car wash is date only`() {
        val due = MaintenanceRules.nextDue(MaintenanceItem.CAR_WASH, d, null, MaintenanceRules.DEFAULTS.getValue(MaintenanceItem.CAR_WASH))!!
        assertEquals(d.plusDays(14), due.date); assertNull(due.dueKm)
    }
    @Test fun `insurance has no rule`() = assertNull(MaintenanceRules.nextDue(MaintenanceItem.INSURANCE, d, null, MaintenanceRules.DEFAULTS.getValue(MaintenanceItem.INSURANCE)))
    @Test fun `override replaces the default`() {
        val iv = MaintenanceRules.interval(MaintenanceItem.CAR_WASH, mapOf("CAR_WASH" to mapOf("km" to 0L, "days" to 7L)))
        assertEquals(7L, iv.days); assertNull(iv.km)
    }
}
