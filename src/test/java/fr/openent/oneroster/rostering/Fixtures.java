package fr.openent.oneroster.rostering;

import fr.openent.oneroster.core.Refs;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.LocalDate;
import java.util.Collections;

/**
 * Petit collège : une classe 3ème-1 (2 élèves, professeur principal), un groupe de latin,
 * deux matières, un responsable, un personnel ADML et un invité (non exposé).
 */
public final class Fixtures {

	public static final String SCHOOL = "s1";
	public static final String DSDEN = "p1";

	private Fixtures() {
	}

	public static RosterBuilder.Raw raw() {
		final RosterBuilder.Raw raw = new RosterBuilder.Raw();
		raw.structures.add(new JsonObject().put("id", SCHOOL).put("name", "Collège Malraux").put("uai", "0212119P")
				.put("externalId", "EXT-S1").put("modified", "2026-08-12T04:10:15.369Z").put("parents", new JsonArray().add(DSDEN)));
		raw.parents.add(new JsonObject().put("id", DSDEN).put("name", "DSDEN 21").put("uai", "0219999E"));
		raw.users
				.add(user("stu1", "Student", "Inès", "Baron").put("birthDate", "2013-05-20").put("ine", "123456789AB")
						.put("relatives", new JsonArray().add("par1")))
				.add(user("stu2", "Student", "Matteo", "Robin").put("birthDate", "2012-12-09"))
				.add(user("tea1", "Teacher", "Jean", "Martin").put("email", "jean.martin@ac.fr"))
				.add(user("tea2", "Teacher", "Lucie", "Durand"))
				.add(user("par1", "Relative", "Paul", "Baron").put("children", new JsonArray().add("stu1")))
				.add(user("adm1", "Personnel", "Anne", "Leroy").put("admlScope", new JsonArray().add(SCHOOL)))
				.add(user("gue1", "Guest", "Invité", "Externe"))
				.add(user("gone", "Student", "Ancien", "Élève").put("disappearanceDate", 1700000000000L));
		raw.classes.add(new JsonObject().put("id", "c31").put("name", "3ème-1").put("externalId", "EXT-S1$3ème-1")
				.put("structureId", SCHOOL));
		raw.classMembers
				.add(member("c31", "stu1", "Student", false))
				.add(member("c31", "stu2", "Student", false))
				.add(member("c31", "tea1", "Teacher", true))
				.add(member("c31", "par1", "Relative", false));
		raw.classProfileGroups.add(new JsonObject().put("groupId", "pg-c31-students").put("classId", "c31"));
		raw.functionalGroups.add(new JsonObject().put("id", "g-lat").put("name", "3 LATIN").put("externalId", "EXT-S1$3 LATIN")
				.put("structureId", SCHOOL));
		raw.functionalGroupMembers.add(new JsonObject().put("groupId", "g-lat").put("userId", "stu2").put("profile", "Student"));
		raw.subjects
				.add(new JsonObject().put("id", "sub-math").put("code", "061300").put("label", "MATHEMATIQUES").put("structureId", SCHOOL))
				.add(new JsonObject().put("id", "sub-lat").put("code", "020300").put("label", "LATIN").put("structureId", SCHOOL));
		raw.teaches.add(new JsonObject().put("teacherId", "tea2").put("subjectId", "sub-lat").put("structureId", SCHOOL)
				.put("groupExternalIds", new JsonArray().add("EXT-S1$3 LATIN")));
		raw.services.add(new JsonObject().put("structureId", SCHOOL).put("teacherId", "tea1").put("subjectId", "sub-math")
				.put("groupId", "c31"));
		raw.schoolYears.add(new JsonObject().put("structureId", SCHOOL).put("startDate", "2025-09-01T00:00:00")
				.put("endDate", "2026-07-04T23:59:59").put("description", "Année scolaire 2025-2026"));
		raw.periods
				.add(period(3, 3, 1, "2025-09-01", "2025-12-31"))
				.add(period(4, 3, 2, "2026-01-01", "2026-04-04"))
				.add(period(5, 3, 3, "2026-04-05", "2026-07-04"));
		return raw;
	}

	public static RosterDataset dataset() {
		return new RosterBuilder(new Refs("https://ent.example.org"), Collections.singletonList(SCHOOL),
				LocalDate.of(2026, 1, 15)).build(raw());
	}

	private static JsonObject user(String id, String profile, String firstName, String lastName) {
		return new JsonObject().put("id", id).put("externalId", "EXT-" + id).put("login", (firstName + "." + lastName).toLowerCase())
				.put("firstName", firstName).put("lastName", lastName).put("profile", profile)
				.put("modified", "2026-08-28T14:44:06.311Z").put("structureIds", new JsonArray().add(SCHOOL))
				.put("relatives", new JsonArray()).put("children", new JsonArray()).put("admlScope", new JsonArray());
	}

	private static JsonObject member(String classId, String userId, String profile, boolean headTeacher) {
		return new JsonObject().put("classId", classId).put("userId", userId).put("profile", profile).put("headTeacher", headTeacher);
	}

	private static JsonObject period(int typeId, int type, int order, String start, String end) {
		return new JsonObject().put("structureId", SCHOOL).put("periodTypeId", typeId).put("type", type).put("ordre", order)
				.put("startDate", start + " 00:00:00").put("endDate", end + " 00:00:00").put("classes", "[\"c31\"]");
	}
}
