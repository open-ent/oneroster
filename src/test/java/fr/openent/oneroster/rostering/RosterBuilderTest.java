package fr.openent.oneroster.rostering;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.Test;

import static fr.openent.oneroster.rostering.RosterDataset.refId;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class RosterBuilderTest {

	private final RosterDataset ds = Fixtures.dataset();

	@Test
	public void schoolAndDistrict() {
		final JsonObject school = ds.school(Fixtures.SCHOOL);
		assertEquals("0212119P", school.getString("identifier"));
		assertEquals(Fixtures.DSDEN, refId(school, "parent"));
		final JsonObject district = ds.org(Fixtures.DSDEN);
		assertEquals("district", district.getString("type"));
		assertEquals(Fixtures.SCHOOL, district.getJsonArray("children").getJsonObject(0).getString("sourcedId"));
		assertEquals("https://ent.example.org/ims/oneroster/rostering/v1p2/orgs/p1",
				school.getJsonObject("parent").getString("href"));
		assertEquals(1, ds.schools().size());
	}

	@Test
	public void sessionsFollowSchoolYearAndTrimesters() {
		final JsonObject year = ds.academicSession("sy.s1");
		assertEquals("schoolYear", year.getString("type"));
		assertEquals("2026", year.getString("schoolYear"));
		assertEquals("2025-09-01", year.getString("startDate"));
		assertEquals(3, year.getJsonArray("children").size());
		final JsonObject t2 = ds.session("term.s1.4", "term");
		assertEquals("Trimestre 2", t2.getString("title"));
		assertEquals("gp.s1.4", t2.getJsonArray("children").getJsonObject(0).getString("sourcedId"));
		assertEquals("term.s1.4", refId(ds.session("gp.s1.4", "gradingPeriod"), "parent"));
	}

	@Test
	public void homeroomAndSections() {
		final JsonObject homeroom = ds.clazz("c31");
		assertEquals("homeroom", homeroom.getString("classType"));
		assertEquals("course.homeroom.s1", refId(homeroom, "course"));
		assertEquals(new JsonArray().add("09"), homeroom.getJsonArray("grades"));
		assertEquals(3, homeroom.getJsonArray("terms").size());

		final JsonObject math = ds.clazz("sub-math.c31");
		assertEquals("scheduled", math.getString("classType"));
		assertEquals("MATHEMATIQUES - 3ème-1", math.getString("title"));
		assertEquals("sub-math", refId(math, "course"));

		final JsonObject latin = ds.clazz("sub-lat.g-lat");
		assertNotNull("section issue de TEACHES (externalId du groupe)", latin);
		assertEquals(new JsonArray().add("020300"), latin.getJsonArray("subjectCodes"));
	}

	@Test
	public void enrollments() {
		final JsonObject head = ds.enrollment("c31.tea1");
		assertEquals("teacher", head.getString("role"));
		assertTrue("professeur principal", head.getBoolean("primary"));
		assertEquals("student", ds.enrollment("c31.stu1").getString("role"));
		assertNull("les responsables ne sont pas inscrits", ds.enrollment("c31.par1"));
		assertNotNull(ds.enrollment("sub-math.c31.stu1"));
		assertNotNull(ds.enrollment("sub-math.c31.tea1"));
		assertNotNull(ds.enrollment("sub-lat.g-lat.stu2"));
		assertNull(ds.enrollment("sub-lat.g-lat.stu1"));
		assertEquals(2, ds.enrolledUsers(e -> "c31".equals(refId(e, "class")), "student").size());
	}

	@Test
	public void usersRolesAndAgents() {
		final JsonObject student = ds.user("stu1");
		assertEquals("student", student.getJsonArray("roles").getJsonObject(0).getString("role"));
		assertEquals("primary", student.getJsonArray("roles").getJsonObject(0).getString("roleType"));
		assertEquals("par1", student.getJsonArray("agents").getJsonObject(0).getString("sourcedId"));
		assertEquals(new JsonArray().add("09"), student.getJsonArray("grades"));
		assertEquals("INE", student.getJsonArray("userIds").getJsonObject(1).getString("type"));
		assertEquals("parent", ds.user("par1").getJsonArray("roles").getJsonObject(0).getString("role"));
		assertEquals("siteAdministrator", ds.user("adm1").getJsonArray("roles").getJsonObject(0).getString("role"));
		assertNull("invités non exposés", ds.user("gue1"));
		assertEquals("tobedeleted", ds.user("gone").getString("status"));
		assertFalse(student.containsKey("sms"));
		assertEquals(2, ds.usersWithRole("teacher").size());
	}

	@Test
	public void demographicsCarryBirthDateOnly() {
		final JsonObject d = ds.demographic("stu1");
		assertEquals("2013-05-20", d.getString("birthDate"));
		assertFalse(d.containsKey("sex"));
		assertNull(ds.demographic("tea1"));
	}

	@Test
	public void resourceShareTargetsResolveToClasses() {
		assertEquals("c31", ds.classOfProfileGroup("pg-c31-students"));
		assertEquals(Fixtures.SCHOOL, ds.structureOfGroup("g-lat"));
	}
}
