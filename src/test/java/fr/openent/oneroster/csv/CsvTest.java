package fr.openent.oneroster.csv;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class CsvTest {

	@Test
	public void roundTripsQuotesSeparatorsAndNewlines() {
		final List<String> values = Arrays.asList("a", "b,c", "d\"e", "", "multi\nline", null);
		final String line = Csv.line(values, ',', false);
		assertEquals("a,\"b,c\",\"d\"\"e\",,\"multi\nline\",\r\n", line);
		final List<List<String>> parsed = Csv.parse("h1,h2,h3,h4,h5,h6\r\n" + line, ',');
		assertEquals(Arrays.asList("a", "b,c", "d\"e", "", "multi\nline", ""), parsed.get(1));
	}

	@Test
	public void ignoresBomAndBlankLines() {
		final List<Map<String, String>> records = Csv.records("﻿sourcedId,title\n\nx,\"Maths, 3A\"\n");
		assertEquals(1, records.size());
		assertEquals("Maths, 3A", records.get(0).get("title"));
		assertEquals("x", records.get(0).get("sourcedId"));
	}

	@Test
	public void parsesUserIdsAndLists() {
		final Map<String, String> ids = new HashMap<>();
		OneRosterSource.parseUserIds("{LDAP:jdoe},{INE:123456789AB}", ids);
		assertEquals("jdoe", ids.get("LDAP"));
		assertEquals("123456789AB", ids.get("INE"));
		assertEquals(Arrays.asList("1", "3", "5"), Csv.list("1, 3,,5"));
	}

	@Test
	public void frenchDates() {
		assertEquals("20/05/2013", EntCsvConverter.frenchDate("2013-05-20"));
		assertEquals(null, EntCsvConverter.frenchDate("20/05/2013"));
	}
}
