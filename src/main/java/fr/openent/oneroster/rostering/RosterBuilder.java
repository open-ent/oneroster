package fr.openent.oneroster.rostering;

import fr.openent.oneroster.core.Entities;
import fr.openent.oneroster.core.Refs;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Construit un {@link RosterDataset} à partir des lectures brutes de l'annuaire et de la vie
 * scolaire. Conversion pure (aucun accès aux bases) : testable unitairement.
 *
 * <p>Identifiants OneRoster :</p>
 * <ul>
 *   <li>établissement, utilisateur, classe, matière : id de l'annuaire ;</li>
 *   <li>année scolaire {@code sy.<structure>}, période {@code term.<structure>.<type>}, période de
 *       notation {@code gp.<structure>.<type>} (type = viesco.rel_type_periode.id, celui que référence
 *       notes.devoirs.id_periode) ;</li>
 *   <li>cours « vie de classe » des classes {@code course.homeroom.<structure>} ;</li>
 *   <li>section (enseignement d'une matière à une classe ou un groupe) {@code <matière>.<groupe>} ;</li>
 *   <li>inscription {@code <classe>.<utilisateur>}.</li>
 * </ul>
 */
public final class RosterBuilder {

	public static final String HOMEROOM_COURSE_PREFIX = "course.homeroom.";

	/** Lectures brutes, une par requête de {@code RosterRepository}. */
	public static final class Raw {
		public JsonArray structures = new JsonArray();
		public JsonArray parents = new JsonArray();
		public JsonArray users = new JsonArray();
		public JsonArray classes = new JsonArray();
		public JsonArray classMembers = new JsonArray();
		public JsonArray classProfileGroups = new JsonArray();
		public JsonArray functionalGroups = new JsonArray();
		public JsonArray functionalGroupMembers = new JsonArray();
		public JsonArray subjects = new JsonArray();
		public JsonArray teaches = new JsonArray();
		public JsonArray services = new JsonArray();
		public JsonArray schoolYears = new JsonArray();
		public JsonArray periods = new JsonArray();
	}

	private final Refs refs;
	private final List<String> scope;
	private final LocalDate today;
	private final String builtAt;
	private final RosterDataset ds = new RosterDataset();

	// Index intermédiaires
	private final Map<String, String> classStructure = new HashMap<>();
	private final Map<String, String> classExternalToId = new HashMap<>();
	private final Map<String, String> groupExternalToId = new HashMap<>();
	private final Map<String, JsonObject> groups = new HashMap<>();
	private final Map<String, JsonObject> subjects = new HashMap<>();
	private final Map<String, List<String>> classTerms = new HashMap<>();
	private final Map<String, List<String>> structureTerms = new HashMap<>();
	private final Map<String, List<String>> studentClassNames = new HashMap<>();

	public RosterBuilder(Refs refs, List<String> scope, LocalDate today) {
		this.refs = refs;
		this.scope = scope;
		this.today = today;
		this.builtAt = Entities.nowIso();
	}

	public RosterDataset build(Raw raw) {
		buildOrgs(raw);
		buildSessions(raw);
		indexGroups(raw);
		buildCourses(raw);
		buildHomerooms(raw);
		buildSections(raw);
		buildUsers(raw);
		buildDemographics(raw);
		return ds;
	}

	// ---------------------------------------------------------------- orgs

	private void buildOrgs(Raw raw) {
		final Map<String, List<String>> childrenOfParent = new LinkedHashMap<>();
		for (JsonObject s : rows(raw.structures)) {
			final String id = s.getString("id");
			if (!scope.contains(id)) {
				continue;
			}
			final JsonObject org = Entities.base(id, Entities.ACTIVE, s.getString("modified"))
					.put("name", s.getString("name"))
					.put("type", "school")
					.put("identifier", s.getString("uai"))
					.put("children", new JsonArray());
			final List<String> parents = strings(s.getJsonArray("parents"));
			if (!parents.isEmpty()) {
				Collections.sort(parents);
				org.put("parent", refs.ref("org", parents.get(0)));
				childrenOfParent.computeIfAbsent(parents.get(0), k -> new ArrayList<>()).add(id);
			}
			ds.orgs.put(id, org);
		}
		for (JsonObject p : rows(raw.parents)) {
			final String id = p.getString("id");
			if (ds.orgs.containsKey(id) || !childrenOfParent.containsKey(id)) {
				continue;
			}
			ds.orgs.put(id, Entities.base(id, Entities.ACTIVE, p.getString("modified"))
					.put("name", p.getString("name"))
					.put("type", "district")
					.put("identifier", p.getString("uai"))
					.put("children", refs.refs("org", childrenOfParent.get(id))));
		}
	}

	// ---------------------------------------------------------------- sessions

	private void buildSessions(Raw raw) {
		final Map<String, JsonObject> yearByStructure = new HashMap<>();
		for (JsonObject y : rows(raw.schoolYears)) {
			// Triées par date de début : la dernière année paramétrée l'emporte.
			yearByStructure.put(y.getString("structureId"), y);
		}
		final Map<String, List<JsonObject>> periodsByStructure = new LinkedHashMap<>();
		for (JsonObject p : rows(raw.periods)) {
			periodsByStructure.computeIfAbsent(p.getString("structureId"), k -> new ArrayList<>()).add(p);
		}
		for (String structureId : ds.orgs.keySet()) {
			if (!"school".equals(ds.orgs.get(structureId).getString("type"))) {
				continue;
			}
			final List<JsonObject> periods = periodsByStructure.getOrDefault(structureId, Collections.<JsonObject>emptyList());
			final String[] bounds = yearBounds(yearByStructure.get(structureId), periods);
			final String schoolYear = bounds[1].substring(0, 4);
			final String yearId = schoolYearId(structureId);
			final JsonObject year = Entities.base(yearId, Entities.ACTIVE, builtAt)
					.put("title", yearTitle(yearByStructure.get(structureId), bounds))
					.put("type", "schoolYear")
					.put("startDate", bounds[0])
					.put("endDate", bounds[1])
					.put("schoolYear", schoolYear)
					.put("org", refs.ref("org", structureId));
			final List<String> termIds = new ArrayList<>();
			for (JsonObject p : periods) {
				final String typeId = String.valueOf(p.getValue("periodTypeId"));
				final String termId = "term." + structureId + "." + typeId;
				final String gpId = gradingPeriodId(structureId, typeId);
				final String title = (Integer.valueOf(2).equals(p.getInteger("type")) ? "Semestre " : "Trimestre ")
						+ p.getValue("ordre");
				final String start = Entities.date(p.getString("startDate"));
				final String end = Entities.date(p.getString("endDate"));
				ds.academicSessions.put(termId, Entities.base(termId, Entities.ACTIVE, builtAt)
						.put("title", title)
						.put("type", "term")
						.put("startDate", start)
						.put("endDate", end)
						.put("parent", refs.ref("academicSession", yearId))
						.put("children", new JsonArray().add(refs.ref("academicSession", gpId)))
						.put("schoolYear", schoolYear)
						.put("org", refs.ref("org", structureId)));
				ds.academicSessions.put(gpId, Entities.base(gpId, Entities.ACTIVE, builtAt)
						.put("title", title)
						.put("type", "gradingPeriod")
						.put("startDate", start)
						.put("endDate", end)
						.put("parent", refs.ref("academicSession", termId))
						.put("children", new JsonArray())
						.put("schoolYear", schoolYear)
						.put("org", refs.ref("org", structureId)));
				termIds.add(termId);
				for (String classId : strings(asArray(p.getValue("classes")))) {
					classTerms.computeIfAbsent(classId, k -> new ArrayList<>()).add(termId);
				}
			}
			year.put("children", refs.refs("academicSession", termIds));
			ds.academicSessions.put(yearId, year);
			structureTerms.put(structureId, termIds.isEmpty() ? Collections.singletonList(yearId) : termIds);
		}
	}

	/** Bornes de l'année : paramétrage YEAR, sinon étendue des périodes, sinon 1er septembre – 31 août. */
	private String[] yearBounds(JsonObject year, List<JsonObject> periods) {
		if (year != null && year.getString("startDate") != null && year.getString("endDate") != null) {
			return new String[] {Entities.date(year.getString("startDate")), Entities.date(year.getString("endDate"))};
		}
		String start = null;
		String end = null;
		for (JsonObject p : periods) {
			final String s = Entities.date(p.getString("startDate"));
			final String e = Entities.date(p.getString("endDate"));
			if (s != null && (start == null || s.compareTo(start) < 0)) {
				start = s;
			}
			if (e != null && (end == null || e.compareTo(end) > 0)) {
				end = e;
			}
		}
		if (start != null && end != null) {
			return new String[] {start, end};
		}
		final int firstYear = today.getMonthValue() >= 8 ? today.getYear() : today.getYear() - 1;
		return new String[] {firstYear + "-09-01", (firstYear + 1) + "-08-31"};
	}

	private static String yearTitle(JsonObject year, String[] bounds) {
		if (year != null && year.getString("description") != null && !year.getString("description").trim().isEmpty()) {
			return year.getString("description");
		}
		return "Année scolaire " + bounds[0].substring(0, 4) + "-" + bounds[1].substring(0, 4);
	}

	public static String schoolYearId(String structureId) {
		return "sy." + structureId;
	}

	public static String gradingPeriodId(String structureId, Object periodTypeId) {
		return "gp." + structureId + "." + periodTypeId;
	}

	// ---------------------------------------------------------------- courses & classes

	private void indexGroups(Raw raw) {
		for (JsonObject c : rows(raw.classes)) {
			if (!ds.orgs.containsKey(c.getString("structureId"))) {
				continue;
			}
			classStructure.put(c.getString("id"), c.getString("structureId"));
			if (c.getString("externalId") != null) {
				classExternalToId.put(c.getString("externalId"), c.getString("id"));
			}
			groups.put(c.getString("id"), c);
			ds.groupStructure.put(c.getString("id"), c.getString("structureId"));
		}
		for (JsonObject g : rows(raw.functionalGroups)) {
			if (!ds.orgs.containsKey(g.getString("structureId"))) {
				continue;
			}
			if (g.getString("externalId") != null) {
				groupExternalToId.put(g.getString("externalId"), g.getString("id"));
			}
			groups.put(g.getString("id"), g);
			ds.groupStructure.put(g.getString("id"), g.getString("structureId"));
		}
		for (JsonObject pg : rows(raw.classProfileGroups)) {
			ds.classOfGroup.put(pg.getString("groupId"), pg.getString("classId"));
		}
		for (JsonObject m : rows(raw.classMembers)) {
			if ("Student".equals(m.getString("profile")) && groups.containsKey(m.getString("classId"))) {
				studentClassNames.computeIfAbsent(m.getString("userId"), k -> new ArrayList<>())
						.add(groups.get(m.getString("classId")).getString("name"));
			}
		}
	}

	private void buildCourses(Raw raw) {
		for (String structureId : ds.orgs.keySet()) {
			if (!"school".equals(ds.orgs.get(structureId).getString("type"))) {
				continue;
			}
			final String id = HOMEROOM_COURSE_PREFIX + structureId;
			ds.courses.put(id, Entities.base(id, Entities.ACTIVE, builtAt)
					.put("title", "Vie de classe")
					.put("courseCode", "HOMEROOM")
					.put("schoolYear", refs.ref("academicSession", schoolYearId(structureId)))
					.put("grades", new JsonArray())
					.put("subjects", new JsonArray())
					.put("subjectCodes", new JsonArray())
					.put("org", refs.ref("org", structureId)));
		}
		for (JsonObject s : rows(raw.subjects)) {
			final String structureId = s.getString("structureId");
			if (!ds.orgs.containsKey(structureId)) {
				continue;
			}
			subjects.put(s.getString("id"), s);
			final JsonArray codes = new JsonArray();
			if (s.getString("code") != null) {
				codes.add(s.getString("code"));
			}
			ds.courses.put(s.getString("id"), Entities.base(s.getString("id"), Entities.ACTIVE, modified(s.getValue("modified")))
					.put("title", s.getString("label"))
					.put("courseCode", s.getString("code"))
					.put("schoolYear", refs.ref("academicSession", schoolYearId(structureId)))
					.put("grades", new JsonArray())
					.put("subjects", s.getString("label") != null ? new JsonArray().add(s.getString("label")) : new JsonArray())
					.put("subjectCodes", codes)
					.put("org", refs.ref("org", structureId)));
		}
	}

	private void buildHomerooms(Raw raw) {
		for (JsonObject c : rows(raw.classes)) {
			final String structureId = c.getString("structureId");
			if (!ds.orgs.containsKey(structureId)) {
				continue;
			}
			final String id = c.getString("id");
			ds.classes.put(id, Entities.base(id, Entities.ACTIVE, builtAt)
					.put("title", c.getString("name"))
					.put("classCode", c.getString("name"))
					.put("classType", "homeroom")
					.put("grades", Entities.grades(c.getString("level"), c.getString("name")))
					.put("subjects", new JsonArray())
					.put("subjectCodes", new JsonArray())
					.put("course", refs.ref("course", HOMEROOM_COURSE_PREFIX + structureId))
					.put("school", refs.ref("org", structureId))
					.put("terms", refs.refs("academicSession", termsOfGroup(id, structureId))));
		}
		for (JsonObject m : rows(raw.classMembers)) {
			final String classId = m.getString("classId");
			if (!ds.classes.containsKey(classId)) {
				continue;
			}
			final String role = enrollmentRole(m.getString("profile"));
			if (role != null) {
				addEnrollment(classId, m.getString("userId"), classStructure.get(classId), role,
						"teacher".equals(role) && Boolean.TRUE.equals(m.getBoolean("headTeacher")));
			}
		}
	}

	/** Sections : enseignements (matière × classe ou groupe), unions de TEACHES et de viesco.services. */
	private void buildSections(Raw raw) {
		final Map<String, Set<String>> teachersBySection = new LinkedHashMap<>();
		for (JsonObject t : rows(raw.teaches)) {
			for (String ext : strings(t.getJsonArray("groupExternalIds"))) {
				final String groupId = classExternalToId.containsKey(ext) ? classExternalToId.get(ext) : groupExternalToId.get(ext);
				addSection(teachersBySection, t.getString("subjectId"), groupId, t.getString("teacherId"));
			}
		}
		for (JsonObject s : rows(raw.services)) {
			addSection(teachersBySection, s.getString("subjectId"), s.getString("groupId"), s.getString("teacherId"));
		}
		final Map<String, List<String>> fgStudents = new HashMap<>();
		for (JsonObject m : rows(raw.functionalGroupMembers)) {
			if ("Student".equals(m.getString("profile"))) {
				fgStudents.computeIfAbsent(m.getString("groupId"), k -> new ArrayList<>()).add(m.getString("userId"));
			}
		}
		final Map<String, List<String>> classStudents = new HashMap<>();
		for (JsonObject m : rows(raw.classMembers)) {
			if ("Student".equals(m.getString("profile"))) {
				classStudents.computeIfAbsent(m.getString("classId"), k -> new ArrayList<>()).add(m.getString("userId"));
			}
		}
		for (Map.Entry<String, Set<String>> e : teachersBySection.entrySet()) {
			final String sectionId = e.getKey();
			final int dot = sectionId.indexOf('.');
			final String subjectId = sectionId.substring(0, dot);
			final String groupId = sectionId.substring(dot + 1);
			final JsonObject subject = subjects.get(subjectId);
			final JsonObject group = groups.get(groupId);
			final String structureId = ds.groupStructure.get(groupId);
			final String label = subject.getString("label");
			final JsonArray codes = new JsonArray();
			if (subject.getString("code") != null) {
				codes.add(subject.getString("code"));
			}
			ds.classes.put(sectionId, Entities.base(sectionId, Entities.ACTIVE, builtAt)
					.put("title", label + " - " + group.getString("name"))
					.put("classCode", (subject.getString("code") != null ? subject.getString("code") : label) + "-" + group.getString("name"))
					.put("classType", "scheduled")
					.put("grades", Entities.grades(group.getString("level"), group.getString("name")))
					.put("subjects", label != null ? new JsonArray().add(label) : new JsonArray())
					.put("subjectCodes", codes)
					.put("course", refs.ref("course", subjectId))
					.put("school", refs.ref("org", structureId))
					.put("terms", refs.refs("academicSession", termsOfGroup(groupId, structureId))));
			for (String teacherId : e.getValue()) {
				addEnrollment(sectionId, teacherId, structureId, "teacher", true);
			}
			final List<String> students = classStructure.containsKey(groupId)
					? classStudents.getOrDefault(groupId, Collections.<String>emptyList())
					: fgStudents.getOrDefault(groupId, Collections.<String>emptyList());
			for (String studentId : students) {
				addEnrollment(sectionId, studentId, structureId, "student", false);
			}
		}
	}

	private void addSection(Map<String, Set<String>> sections, String subjectId, String groupId, String teacherId) {
		if (subjectId == null || groupId == null || !subjects.containsKey(subjectId) || !groups.containsKey(groupId)) {
			return;
		}
		final Set<String> teachers = sections.computeIfAbsent(sectionId(subjectId, groupId), k -> new LinkedHashSet<>());
		if (teacherId != null) {
			teachers.add(teacherId);
		}
	}

	public static String sectionId(String subjectId, String groupId) {
		return subjectId + "." + groupId;
	}

	private List<String> termsOfGroup(String groupId, String structureId) {
		final List<String> terms = classTerms.get(groupId);
		if (terms != null && !terms.isEmpty()) {
			return terms;
		}
		return structureTerms.getOrDefault(structureId, Collections.singletonList(schoolYearId(structureId)));
	}

	private void addEnrollment(String classId, String userId, String structureId, String role, boolean primary) {
		final String id = classId + "." + userId;
		final JsonObject enrollment = Entities.base(id, Entities.ACTIVE, builtAt)
				.put("user", refs.ref("user", userId))
				.put("class", refs.ref("class", classId))
				.put("school", refs.ref("org", structureId))
				.put("role", role)
				.put("primary", primary);
		ds.enrollments.put(id, enrollment);
	}

	private static String enrollmentRole(String profile) {
		if ("Student".equals(profile)) {
			return "student";
		}
		if ("Teacher".equals(profile)) {
			return "teacher";
		}
		if ("Personnel".equals(profile)) {
			return "administrator";
		}
		return null;
	}

	// ---------------------------------------------------------------- users

	private void buildUsers(Raw raw) {
		final Set<String> known = new LinkedHashSet<>();
		for (JsonObject u : rows(raw.users)) {
			if (userRole(u.getString("profile"), false) != null) {
				known.add(u.getString("id"));
			}
		}
		for (JsonObject u : rows(raw.users)) {
			final String id = u.getString("id");
			if (!known.contains(id)) {
				continue;
			}
			final List<String> structures = new ArrayList<>();
			for (String s : strings(u.getJsonArray("structureIds"))) {
				if (ds.orgs.containsKey(s)) {
					structures.add(s);
				}
			}
			if (structures.isEmpty()) {
				continue;
			}
			Collections.sort(structures);
			final List<String> adml = strings(u.getJsonArray("admlScope"));
			final JsonArray roles = new JsonArray();
			for (String s : structures) {
				roles.add(new JsonObject()
						.put("roleType", roles.isEmpty() ? "primary" : "secondary")
						.put("role", userRole(u.getString("profile"), adml.contains(s)))
						.put("org", refs.ref("org", s)));
			}
			final JsonArray userIds = new JsonArray();
			if (u.getString("externalId") != null) {
				userIds.add(new JsonObject().put("type", "externalId").put("identifier", u.getString("externalId")));
			}
			if (u.getString("ine") != null) {
				userIds.add(new JsonObject().put("type", "INE").put("identifier", u.getString("ine")));
			}
			final List<String> agents = new ArrayList<>();
			for (String a : strings(u.getJsonArray("relatives"))) {
				if (known.contains(a) && !a.equals(id)) {
					agents.add(a);
				}
			}
			for (String a : strings(u.getJsonArray("children"))) {
				if (known.contains(a) && !a.equals(id) && !agents.contains(a)) {
					agents.add(a);
				}
			}
			final String email = u.getString("email") != null ? u.getString("email") : u.getString("emailAcademy");
			final JsonObject user = Entities.base(id, userStatus(u), modified(u.getValue("modified")))
					.put("username", u.getString("login"))
					.put("userIds", userIds)
					.put("enabledUser", !Boolean.TRUE.equals(u.getBoolean("blocked")) && u.getValue("deleteDate") == null)
					.put("givenName", u.getString("firstName"))
					.put("familyName", u.getString("lastName"))
					.put("roles", roles)
					.put("identifier", u.getString("externalId"))
					.put("agents", refs.refs("user", agents))
					.put("primaryOrg", refs.ref("org", structures.get(0)));
			if (email != null) {
				user.put("email", email);
			}
			final JsonArray grades = new JsonArray();
			if ("Student".equals(u.getString("profile"))) {
				final List<String> labels = new ArrayList<>();
				labels.add(u.getString("level"));
				labels.addAll(studentClassNames.getOrDefault(id, Collections.<String>emptyList()));
				for (Object g : Entities.grades(labels.toArray(new String[0]))) {
					grades.add(g);
				}
			}
			user.put("grades", grades);
			ds.users.put(id, user);
		}
		// Les inscriptions ne gardent que les utilisateurs exposés (élèves, enseignants, personnels du périmètre).
		ds.enrollments.values().removeIf(e -> !ds.users.containsKey(RosterDataset.refId(e, "user")));
	}

	private static String userStatus(JsonObject u) {
		return u.getValue("disappearanceDate") != null || u.getValue("deleteDate") != null
				? Entities.TO_BE_DELETED : Entities.ACTIVE;
	}

	/** Profil ENT → rôle OneRoster ; les invités ne sont pas exposés. */
	static String userRole(String profile, boolean adml) {
		if (profile == null) {
			return null;
		}
		switch (profile) {
			case "Student":
				return "student";
			case "Teacher":
				return "teacher";
			case "Relative":
				return "parent";
			case "Personnel":
				return adml ? "siteAdministrator" : "aide";
			default:
				return null;
		}
	}

	private void buildDemographics(Raw raw) {
		for (JsonObject u : rows(raw.users)) {
			final JsonObject user = ds.users.get(u.getString("id"));
			final String birthDate = Entities.date(u.getString("birthDate"));
			if (user == null || birthDate == null || !birthDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
				continue;
			}
			ds.demographics.put(u.getString("id"), Entities.base(u.getString("id"), user.getString("status"),
					user.getString("dateLastModified")).put("birthDate", birthDate));
		}
	}

	// ---------------------------------------------------------------- utilitaires

	/** Les dates « modified » de l'annuaire sont des chaînes ISO, ou des timestamps en millisecondes. */
	private String modified(Object value) {
		if (value instanceof Number) {
			return java.time.Instant.ofEpochMilli(((Number) value).longValue()).toString();
		}
		return value instanceof String ? (String) value : builtAt;
	}

	private static List<JsonObject> rows(JsonArray array) {
		final List<JsonObject> rows = new ArrayList<>();
		if (array != null) {
			for (Object o : array) {
				if (o instanceof JsonObject) {
					rows.add((JsonObject) o);
				}
			}
		}
		return rows;
	}

	private static JsonArray asArray(Object value) {
		if (value instanceof JsonArray) {
			return (JsonArray) value;
		}
		if (value instanceof String) {
			try {
				return new JsonArray((String) value);
			} catch (RuntimeException e) {
				return new JsonArray();
			}
		}
		return new JsonArray();
	}

	private static List<String> strings(JsonArray array) {
		final List<String> list = new ArrayList<>();
		if (array != null) {
			for (Object o : array) {
				if (o instanceof String && !list.contains(o)) {
					list.add((String) o);
				}
			}
		}
		return list;
	}
}
