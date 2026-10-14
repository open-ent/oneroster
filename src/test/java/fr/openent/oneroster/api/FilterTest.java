package fr.openent.oneroster.api;

import fr.openent.oneroster.core.OneRosterException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class FilterTest {

	private static final Set<String> FIELDS = new HashSet<>(Arrays.asList("givenName", "familyName", "roles",
			"dateLastModified", "score", "school", "status"));

	private static final JsonObject USER = new JsonObject()
			.put("givenName", "Inès")
			.put("familyName", "Baron")
			.put("status", "active")
			.put("dateLastModified", "2026-08-28T14:44:06.311Z")
			.put("score", 15.5)
			.put("school", new JsonObject().put("sourcedId", "s1").put("type", "org"))
			.put("roles", new JsonArray()
					.add(new JsonObject().put("role", "student").put("org", new JsonObject().put("sourcedId", "s1")))
					.add(new JsonObject().put("role", "parent").put("org", new JsonObject().put("sourcedId", "s2"))));

	private static boolean matches(String expression) throws OneRosterException {
		return Filter.parse(expression, FIELDS).matches(USER);
	}

	@Test
	public void equalityIsCaseInsensitive() throws Exception {
		assertTrue(matches("familyName='baron'"));
		assertFalse(matches("familyName='Robin'"));
	}

	@Test
	public void notEqualsAndContains() throws Exception {
		assertTrue(matches("familyName!='Robin'"));
		assertFalse(matches("familyName!='Baron'"));
		assertTrue(matches("givenName~'nè'"));
	}

	@Test
	public void dateAndNumberComparisons() throws Exception {
		assertTrue(matches("dateLastModified>'2026-01-01'"));
		assertFalse(matches("dateLastModified<'2026-01-01'"));
		assertTrue(matches("score>='15.5'"));
		assertTrue(matches("score<'100'"));
		assertFalse(matches("score>'20'"));
	}

	@Test
	public void nestedArraysAndReferences() throws Exception {
		assertTrue(matches("roles.role='parent'"));
		assertTrue(matches("roles.org='s2'"));
		assertTrue(matches("school='s1'"));
		assertFalse(matches("roles.role='teacher'"));
	}

	@Test
	public void andBindsTighterThanOr() throws Exception {
		assertTrue(matches("familyName='Robin' AND givenName='Zoé' OR status='active'"));
		assertFalse(matches("familyName='Robin' OR givenName='Zoé' AND status='active'"));
		assertTrue(matches("familyName='Baron' AND roles.role='student'"));
	}

	@Test
	public void quotedValuesMayContainSpacesAndQuotes() throws Exception {
		final JsonObject o = new JsonObject().put("familyName", "D'Artagnan de Castelmore");
		assertTrue(Filter.parse("familyName='D''Artagnan de Castelmore'", FIELDS).matches(o));
	}

	@Test
	public void unknownFieldIsRejected() {
		try {
			Filter.parse("password='x'", FIELDS);
			fail();
		} catch (OneRosterException e) {
			assertEquals(400, e.getStatus());
			assertEquals(OneRosterException.INVALID_FILTER_FIELD, e.getCodeMinor());
		}
	}

	@Test
	public void malformedExpressionIsRejected() {
		for (String bad : new String[] {"familyName=Baron", "familyName='Baron", "='x'", "familyName='a' XOR givenName='b'"}) {
			try {
				Filter.parse(bad, FIELDS);
				fail(bad);
			} catch (OneRosterException e) {
				assertEquals(400, e.getStatus());
			}
		}
	}
}
