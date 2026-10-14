package fr.openent.oneroster.api;

import fr.openent.oneroster.core.OneRosterException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Paramètre « filter » de OneRoster 1.2 (annexe « Filtering ») :
 * {@code champ opérateur 'valeur'} reliés par AND / OR, AND étant prioritaire sur OR.
 * Opérateurs : = != &gt; &gt;= &lt; &lt;= ~ (contient, insensible à la casse).
 * Un champ peut désigner un sous-attribut (role.role) ; sur un tableau, la condition est
 * vraie si un élément la vérifie. Une référence (GUIDRef) se compare par son sourcedId.
 */
public final class Filter {

	private final List<List<Condition>> disjunction;

	private Filter(List<List<Condition>> disjunction) {
		this.disjunction = disjunction;
	}

	/** Filtre vide : tout passe. */
	public static Filter all() {
		return new Filter(Collections.<List<Condition>>emptyList());
	}

	public static Filter parse(String expression, Set<String> allowedFields) throws OneRosterException {
		if (expression == null || expression.trim().isEmpty()) {
			return all();
		}
		final Parser parser = new Parser(expression);
		final List<List<Condition>> or = new ArrayList<>();
		List<Condition> and = new ArrayList<>();
		and.add(parser.condition(allowedFields));
		while (!parser.atEnd()) {
			final String logical = parser.logical();
			final Condition next = parser.condition(allowedFields);
			if ("AND".equals(logical)) {
				and.add(next);
			} else {
				or.add(and);
				and = new ArrayList<>();
				and.add(next);
			}
		}
		or.add(and);
		return new Filter(or);
	}

	public boolean matches(JsonObject entity) {
		if (disjunction.isEmpty()) {
			return true;
		}
		for (List<Condition> conjunction : disjunction) {
			boolean ok = true;
			for (Condition c : conjunction) {
				if (!c.matches(entity)) {
					ok = false;
					break;
				}
			}
			if (ok) {
				return true;
			}
		}
		return false;
	}

	/** Valeurs scalaires atteintes par un chemin pointé (tableaux aplatis, références réduites au sourcedId). */
	static List<Object> resolve(JsonObject entity, String path) {
		List<Object> current = new ArrayList<>();
		current.add(entity);
		for (String part : path.split("\\.")) {
			final List<Object> next = new ArrayList<>();
			for (Object o : current) {
				if (o instanceof JsonObject) {
					flatten(((JsonObject) o).getValue(part), next);
				}
			}
			current = next;
		}
		final List<Object> values = new ArrayList<>();
		for (Object o : current) {
			if (o instanceof JsonObject) {
				final Object sourcedId = ((JsonObject) o).getValue("sourcedId");
				if (sourcedId != null) {
					values.add(sourcedId);
				}
			} else if (o != null) {
				values.add(o);
			}
		}
		return values;
	}

	private static void flatten(Object value, List<Object> into) {
		if (value instanceof JsonArray) {
			for (Object o : (JsonArray) value) {
				flatten(o, into);
			}
		} else if (value != null) {
			into.add(value);
		}
	}

	/** Comparaison numérique si les deux valeurs sont des nombres, lexicographique sinon (dates ISO 8601 comprises). */
	static int compare(Object value, String literal) {
		final Double a = asNumber(value);
		final Double b = asNumber(literal);
		if (a != null && b != null) {
			return Double.compare(a, b);
		}
		return String.valueOf(value).compareTo(literal);
	}

	private static Double asNumber(Object o) {
		if (o instanceof Number) {
			return ((Number) o).doubleValue();
		}
		if (o instanceof String) {
			final String s = ((String) o).trim();
			if (s.isEmpty() || !s.matches("-?\\d+(\\.\\d+)?")) {
				return null;
			}
			return Double.valueOf(s);
		}
		return null;
	}

	static final class Condition {
		final String field;
		final String op;
		final String value;

		Condition(String field, String op, String value) {
			this.field = field;
			this.op = op;
			this.value = value;
		}

		boolean matches(JsonObject entity) {
			final List<Object> values = resolve(entity, field);
			if ("!=".equals(op)) {
				for (Object v : values) {
					if (equalsLiteral(v)) {
						return false;
					}
				}
				return true;
			}
			for (Object v : values) {
				if (test(v)) {
					return true;
				}
			}
			return false;
		}

		private boolean equalsLiteral(Object v) {
			final Double a = asNumber(v);
			final Double b = asNumber(value);
			if (a != null && b != null) {
				return a.doubleValue() == b.doubleValue();
			}
			return String.valueOf(v).equalsIgnoreCase(value);
		}

		private boolean test(Object v) {
			switch (op) {
				case "=":
					return equalsLiteral(v);
				case "~":
					return String.valueOf(v).toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
				case ">":
					return compare(v, value) > 0;
				case ">=":
					return compare(v, value) >= 0;
				case "<":
					return compare(v, value) < 0;
				case "<=":
					return compare(v, value) <= 0;
				default:
					return false;
			}
		}
	}

	private static final class Parser {
		private final String s;
		private int pos;

		Parser(String s) {
			this.s = s;
		}

		boolean atEnd() {
			skipSpaces();
			return pos >= s.length();
		}

		Condition condition(Set<String> allowedFields) throws OneRosterException {
			skipSpaces();
			final int start = pos;
			while (pos < s.length() && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_' || s.charAt(pos) == '.')) {
				pos++;
			}
			final String field = s.substring(start, pos);
			if (field.isEmpty()) {
				throw invalid();
			}
			final String root = field.split("\\.")[0];
			if (allowedFields != null && !allowedFields.contains(root)) {
				throw new OneRosterException(400, OneRosterException.INVALID_FILTER_FIELD, "invalid.filter.field:" + field);
			}
			skipSpaces();
			final String op = operator();
			skipSpaces();
			return new Condition(field, op, quoted());
		}

		String logical() throws OneRosterException {
			skipSpaces();
			for (String keyword : new String[] {"AND", "OR"}) {
				if (s.regionMatches(true, pos, keyword, 0, keyword.length())
						&& pos + keyword.length() < s.length()
						&& Character.isWhitespace(s.charAt(pos + keyword.length()))) {
					pos += keyword.length();
					return keyword;
				}
			}
			throw invalid();
		}

		private String operator() throws OneRosterException {
			for (String op : new String[] {"!=", ">=", "<=", "=", ">", "<", "~"}) {
				if (s.startsWith(op, pos)) {
					pos += op.length();
					return op;
				}
			}
			throw invalid();
		}

		private String quoted() throws OneRosterException {
			if (pos >= s.length() || s.charAt(pos) != '\'') {
				throw invalid();
			}
			pos++;
			final StringBuilder value = new StringBuilder();
			while (pos < s.length()) {
				final char c = s.charAt(pos++);
				if (c == '\'') {
					if (pos < s.length() && s.charAt(pos) == '\'') {
						value.append('\'');
						pos++;
					} else {
						return value.toString();
					}
				} else {
					value.append(c);
				}
			}
			throw invalid();
		}

		private void skipSpaces() {
			while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
				pos++;
			}
		}

		private OneRosterException invalid() {
			return new OneRosterException(400, OneRosterException.INVALID_FILTER_FIELD, "invalid.filter");
		}
	}
}
