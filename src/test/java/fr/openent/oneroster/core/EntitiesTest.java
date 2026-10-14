package fr.openent.oneroster.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class EntitiesTest {

	@Test
	public void frenchLevelsMapToCedsGrades() {
		assertEquals("06", Entities.grade("6ème A"));
		assertEquals("07", Entities.grade("5e-2"));
		assertEquals("09", Entities.grade("3ème-1"));
		assertEquals("10", Entities.grade("2nde 4"));
		assertEquals("10", Entities.grade("Seconde générale"));
		assertEquals("11", Entities.grade("1ère STMG"));
		assertEquals("12", Entities.grade("Terminale S"));
		assertEquals("12", Entities.grade("TLE 2"));
		assertEquals("01", Entities.grade("CP"));
		assertEquals("05", Entities.grade("CM2 B"));
		assertEquals("KG", Entities.grade("GS"));
		assertEquals("PR", Entities.grade("PS-MS"));
	}

	@Test
	public void unknownLabelsHaveNoGrade() {
		assertNull(Entities.grade("Atelier théâtre"));
		assertNull(Entities.grade("5 A"));
		assertNull(Entities.grade(null));
	}

	@Test
	public void datesAreNormalized() {
		assertEquals("2025-09-01T00:00:00.000Z", Entities.dateTime("2025-09-01"));
		assertEquals("2025-09-01T08:00:00.000Z", Entities.dateTime("2025-09-01 08:00:00.000"));
		assertEquals("2024-12-04T08:16:43.633Z", Entities.dateTime("2024-12-04T08:16:43.633Z"));
		assertEquals("2026-10-07T22:00:00.000Z", Entities.dateTime("2026-10-08T00:00:00.000000+0200"));
		assertEquals("2026-10-07T22:00:00.000Z", Entities.dateTime("2026-10-08T00:00:00+02:00"));
		assertEquals("2026-10-14T20:51:22.025Z", Entities.dateTime("2026-10-14T20:51:22.025351769Z"));
		assertTrue(Entities.nowIso().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));
		assertEquals("2025-09-01", Entities.date("2025-09-01T00:00:00"));
		assertNull(Entities.date("2025"));
	}
}
