package fr.openent.oneroster.rostering;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Photographie Rostering d'un périmètre d'établissements, déjà au format OneRoster 1.2.
 * Les entités sont indexées par sourcedId ; les listes conservent l'ordre de construction.
 */
public final class RosterDataset {

	final Map<String, JsonObject> orgs = new LinkedHashMap<>();
	final Map<String, JsonObject> academicSessions = new LinkedHashMap<>();
	final Map<String, JsonObject> courses = new LinkedHashMap<>();
	final Map<String, JsonObject> classes = new LinkedHashMap<>();
	final Map<String, JsonObject> users = new LinkedHashMap<>();
	final Map<String, JsonObject> enrollments = new LinkedHashMap<>();
	final Map<String, JsonObject> demographics = new LinkedHashMap<>();
	/** Groupes de l'annuaire (classe ou groupe d'enseignement) → établissement, pour la vérification d'écritures. */
	final Map<String, String> groupStructure = new LinkedHashMap<>();
	/** Groupes de profil d'une classe → classe : sert à rattacher les partages de ressources. */
	final Map<String, String> classOfGroup = new LinkedHashMap<>();

	public List<JsonObject> orgs() {
		return list(orgs);
	}

	public List<JsonObject> schools() {
		return where(orgs, o -> "school".equals(o.getString("type")));
	}

	public List<JsonObject> academicSessions() {
		return list(academicSessions);
	}

	public List<JsonObject> sessionsOfType(String type) {
		return where(academicSessions, s -> type.equals(s.getString("type")));
	}

	public List<JsonObject> courses() {
		return list(courses);
	}

	public List<JsonObject> classes() {
		return list(classes);
	}

	public List<JsonObject> users() {
		return list(users);
	}

	public List<JsonObject> usersWithRole(String role) {
		return where(users, u -> hasRole(u, role));
	}

	public List<JsonObject> enrollments() {
		return list(enrollments);
	}

	public List<JsonObject> demographics() {
		return list(demographics);
	}

	public JsonObject org(String id) {
		return orgs.get(id);
	}

	public JsonObject school(String id) {
		final JsonObject o = orgs.get(id);
		return o != null && "school".equals(o.getString("type")) ? o : null;
	}

	public JsonObject academicSession(String id) {
		return academicSessions.get(id);
	}

	public JsonObject session(String id, String type) {
		final JsonObject s = academicSessions.get(id);
		return s != null && type.equals(s.getString("type")) ? s : null;
	}

	public JsonObject course(String id) {
		return courses.get(id);
	}

	public JsonObject clazz(String id) {
		return classes.get(id);
	}

	public JsonObject user(String id) {
		return users.get(id);
	}

	public JsonObject userWithRole(String id, String role) {
		final JsonObject u = users.get(id);
		return u != null && hasRole(u, role) ? u : null;
	}

	public JsonObject enrollment(String id) {
		return enrollments.get(id);
	}

	public JsonObject demographic(String id) {
		return demographics.get(id);
	}

	public String structureOfGroup(String groupId) {
		return groupStructure.get(groupId);
	}

	public String classOfProfileGroup(String groupId) {
		return classOfGroup.get(groupId);
	}

	// ---- Collections dérivées (routes imbriquées) ----

	public List<JsonObject> classesWhere(Predicate<JsonObject> predicate) {
		return where(classes, predicate);
	}

	public List<JsonObject> coursesWhere(Predicate<JsonObject> predicate) {
		return where(courses, predicate);
	}

	public List<JsonObject> enrollmentsWhere(Predicate<JsonObject> predicate) {
		return where(enrollments, predicate);
	}

	public List<JsonObject> sessionsWhere(Predicate<JsonObject> predicate) {
		return where(academicSessions, predicate);
	}

	/** Utilisateurs inscrits dans une classe (ou un établissement) avec un rôle d'inscription donné. */
	public List<JsonObject> enrolledUsers(Predicate<JsonObject> enrollmentPredicate, String role) {
		final Map<String, JsonObject> result = new LinkedHashMap<>();
		for (JsonObject e : enrollments.values()) {
			if (role.equals(e.getString("role")) && enrollmentPredicate.test(e)) {
				final String userId = refId(e, "user");
				final JsonObject u = users.get(userId);
				if (u != null) {
					result.put(userId, u);
				}
			}
		}
		return new ArrayList<>(result.values());
	}

	/** Classes dans lesquelles un utilisateur est inscrit (tous rôles confondus). */
	public List<JsonObject> classesOfUser(String userId) {
		final Map<String, JsonObject> result = new LinkedHashMap<>();
		for (JsonObject e : enrollments.values()) {
			if (userId.equals(refId(e, "user"))) {
				final String classId = refId(e, "class");
				final JsonObject c = classes.get(classId);
				if (c != null) {
					result.put(classId, c);
				}
			}
		}
		return new ArrayList<>(result.values());
	}

	public static String refId(JsonObject entity, String field) {
		final Object ref = entity.getValue(field);
		return ref instanceof JsonObject ? ((JsonObject) ref).getString("sourcedId") : null;
	}

	public static boolean refsContain(JsonObject entity, String field, String sourcedId) {
		final JsonArray refs = entity.getJsonArray(field);
		if (refs == null) {
			return false;
		}
		for (Object o : refs) {
			if (o instanceof JsonObject && sourcedId.equals(((JsonObject) o).getString("sourcedId"))) {
				return true;
			}
		}
		return false;
	}

	static boolean hasRole(JsonObject user, String role) {
		final JsonArray roles = user.getJsonArray("roles");
		if (roles == null) {
			return false;
		}
		for (Object o : roles) {
			if (o instanceof JsonObject && role.equals(((JsonObject) o).getString("role"))) {
				return true;
			}
		}
		return false;
	}

	private static List<JsonObject> list(Map<String, JsonObject> map) {
		return Collections.unmodifiableList(new ArrayList<>(map.values()));
	}

	private static List<JsonObject> where(Map<String, JsonObject> map, Predicate<JsonObject> predicate) {
		final List<JsonObject> list = new ArrayList<>();
		for (JsonObject o : map.values()) {
			if (predicate.test(o)) {
				list.add(o);
			}
		}
		return list;
	}
}
