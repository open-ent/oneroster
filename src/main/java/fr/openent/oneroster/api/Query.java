package fr.openent.oneroster.api;

import fr.openent.oneroster.core.OneRosterException;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Paramètres de lecture d'une collection : pagination (limit / offset), tri (sort / orderBy),
 * filtre (filter) et projection (fields). Le tout s'applique en mémoire, sur les entités
 * déjà converties au format OneRoster : les noms de champs sont donc ceux de la norme.
 */
public final class Query {

	public static final int DEFAULT_LIMIT = 100;

	private final int limit;
	private final int offset;
	private final String sort;
	private final boolean descending;
	private final String filterExpression;
	private final List<String> fields;

	public Query(int limit, int offset, String sort, boolean descending, String filterExpression, List<String> fields) {
		this.limit = limit;
		this.offset = offset;
		this.sort = sort;
		this.descending = descending;
		this.filterExpression = filterExpression;
		this.fields = fields;
	}

	public static Query parse(MultiMap params, int maxLimit) throws OneRosterException {
		final int limit = integer(params.get("limit"), DEFAULT_LIMIT, 1);
		final int offset = integer(params.get("offset"), 0, 0);
		final String orderBy = params.get("orderBy");
		if (orderBy != null && !"asc".equalsIgnoreCase(orderBy) && !"desc".equalsIgnoreCase(orderBy)) {
			throw new OneRosterException("invalid.orderBy");
		}
		final List<String> fields = new ArrayList<>();
		for (String value : params.getAll("fields")) {
			for (String f : value.split(",")) {
				if (!f.trim().isEmpty() && !fields.contains(f.trim())) {
					fields.add(f.trim());
				}
			}
		}
		return new Query(Math.min(limit, maxLimit), offset, blankToNull(params.get("sort")),
				"desc".equalsIgnoreCase(orderBy), params.get("filter"), fields);
	}

	/** Requête « tout » : utilisée par l'export CSV. */
	public static Query everything() {
		return new Query(Integer.MAX_VALUE, 0, null, false, null, Collections.<String>emptyList());
	}

	public int getLimit() {
		return limit;
	}

	public int getOffset() {
		return offset;
	}

	/**
	 * Filtre, trie, pagine et projette. Les noms de champs (filter, sort, fields) sont vérifiés
	 * contre ceux de l'entité : un champ inconnu dans filter ou sort est une erreur, un champ
	 * inconnu dans fields est ignoré.
	 */
	public Page apply(List<JsonObject> entities, Set<String> knownFields) throws OneRosterException {
		final Filter filter = Filter.parse(filterExpression, knownFields);
		final List<JsonObject> selected = new ArrayList<>();
		for (JsonObject e : entities) {
			if (filter.matches(e)) {
				selected.add(e);
			}
		}
		if (sort != null) {
			if (!knownFields.contains(sort.split("\\.")[0])) {
				throw new OneRosterException(400, OneRosterException.INVALID_SORT_FIELD, "invalid.sort.field:" + sort);
			}
			final Comparator<JsonObject> comparator = byField(sort);
			selected.sort(descending ? comparator.reversed() : comparator);
		}
		final int total = selected.size();
		final int from = Math.min(offset, total);
		final int to = (int) Math.min((long) from + limit, total);
		final List<String> projection = new ArrayList<>();
		for (String f : fields) {
			if (knownFields.contains(f)) {
				projection.add(f);
			}
		}
		final List<JsonObject> items = new ArrayList<>(to - from);
		for (JsonObject e : selected.subList(from, to)) {
			items.add(project(e, projection));
		}
		return new Page(items, total, limit, offset);
	}

	/** Projection d'une entité seule (lecture par sourcedId). */
	public JsonObject project(JsonObject entity, Set<String> knownFields) {
		final List<String> projection = new ArrayList<>();
		for (String f : fields) {
			if (knownFields.contains(f)) {
				projection.add(f);
			}
		}
		return project(entity, projection);
	}

	private static JsonObject project(JsonObject entity, List<String> projection) {
		if (projection.isEmpty()) {
			return entity;
		}
		final JsonObject projected = new JsonObject();
		for (String f : projection) {
			if (entity.containsKey(f)) {
				projected.put(f, entity.getValue(f));
			}
		}
		return projected;
	}

	/** Tri sur la première valeur du champ ; les entités sans valeur passent en dernier. */
	private static Comparator<JsonObject> byField(final String path) {
		return (a, b) -> {
			final List<Object> va = Filter.resolve(a, path);
			final List<Object> vb = Filter.resolve(b, path);
			if (va.isEmpty() || vb.isEmpty()) {
				return Boolean.compare(va.isEmpty(), vb.isEmpty());
			}
			return Filter.compare(va.get(0), String.valueOf(vb.get(0)));
		};
	}

	private static int integer(String value, int defaultValue, int min) throws OneRosterException {
		if (value == null || value.trim().isEmpty()) {
			return defaultValue;
		}
		try {
			final int parsed = Integer.parseInt(value.trim());
			if (parsed < min) {
				throw new OneRosterException("invalid.pagination");
			}
			return parsed;
		} catch (NumberFormatException e) {
			throw new OneRosterException("invalid.pagination");
		}
	}

	private static String blankToNull(String s) {
		return s == null || s.trim().isEmpty() ? null : s.trim();
	}

	/** Page de résultats et nombre total d'entités correspondant au filtre. */
	public static final class Page {
		public final List<JsonObject> items;
		public final int total;
		public final int limit;
		public final int offset;

		Page(List<JsonObject> items, int total, int limit, int offset) {
			this.items = items;
			this.total = total;
			this.limit = limit;
			this.offset = offset;
		}
	}
}
