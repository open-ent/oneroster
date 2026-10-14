package fr.openent.oneroster.api;

import fr.openent.oneroster.core.OneRosterException;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class QueryTest {

	private static final Set<String> FIELDS = new HashSet<>(Arrays.asList("sourcedId", "title", "rank"));

	private static List<JsonObject> items() {
		final List<JsonObject> list = new ArrayList<>();
		for (int i = 1; i <= 25; i++) {
			list.add(new JsonObject().put("sourcedId", "id" + i).put("title", "T" + (char) ('a' + (i % 5))).put("rank", i));
		}
		return list;
	}

	private static Query query(String... kv) throws OneRosterException {
		final MultiMap params = MultiMap.caseInsensitiveMultiMap();
		for (int i = 0; i < kv.length; i += 2) {
			params.add(kv[i], kv[i + 1]);
		}
		return Query.parse(params, 50);
	}

	@Test
	public void paginates() throws Exception {
		final Query.Page page = query("limit", "10", "offset", "20").apply(items(), FIELDS);
		assertEquals(25, page.total);
		assertEquals(5, page.items.size());
		assertEquals("id21", page.items.get(0).getString("sourcedId"));
	}

	@Test
	public void limitIsCapped() throws Exception {
		assertEquals(50, query("limit", "100000").getLimit());
	}

	@Test
	public void sortsNumericallyDescending() throws Exception {
		final Query.Page page = query("sort", "rank", "orderBy", "desc", "limit", "3").apply(items(), FIELDS);
		assertEquals(25, (int) page.items.get(0).getInteger("rank"));
		assertEquals(23, (int) page.items.get(2).getInteger("rank"));
	}

	@Test
	public void filtersThenCounts() throws Exception {
		final Query.Page page = query("filter", "title='Ta'").apply(items(), FIELDS);
		assertEquals(5, page.total);
	}

	@Test
	public void projectsSelectedFieldsAndIgnoresUnknownOnes() throws Exception {
		final Query.Page page = query("fields", "sourcedId,unknown").apply(items(), FIELDS);
		final JsonObject first = page.items.get(0);
		assertTrue(first.containsKey("sourcedId"));
		assertFalse(first.containsKey("title"));
		assertEquals(1, first.size());
	}

	@Test
	public void rejectsInvalidParameters() {
		for (String[] kv : new String[][] {{"limit", "0"}, {"offset", "-1"}, {"limit", "abc"}, {"orderBy", "up"}}) {
			try {
				query(kv);
				fail(kv[0]);
			} catch (OneRosterException e) {
				assertEquals(400, e.getStatus());
			}
		}
	}

	@Test
	public void rejectsUnknownSortField() throws Exception {
		try {
			query("sort", "password").apply(items(), FIELDS);
			fail();
		} catch (OneRosterException e) {
			assertEquals(OneRosterException.INVALID_SORT_FIELD, e.getCodeMinor());
		}
	}

	@Test
	public void linkHeaderKeepsOtherParameters() throws Exception {
		final MultiMap params = MultiMap.caseInsensitiveMultiMap().add("limit", "10").add("offset", "10").add("filter", "title='Ta'");
		final Query.Page page = Query.parse(params, 50).apply(items(), FIELDS);
		final String links = Http.links("https://ent/x", params, new Query.Page(page.items, 25, 10, 10));
		assertTrue(links, links.contains("<https://ent/x?limit=10&offset=20&filter=title%3D%27Ta%27>; rel=\"next\""));
		assertTrue(links, links.contains("offset=0") && links.contains("rel=\"prev\"") && links.contains("rel=\"last\""));
	}
}
