package fr.openent.oneroster.controller;

import fr.openent.oneroster.OneRosterContext;
import fr.openent.oneroster.core.Entities;
import fr.openent.oneroster.core.Refs;
import fr.openent.oneroster.rostering.RosterDataset;
import fr.openent.oneroster.security.Scopes;
import fr.wseduc.rs.Get;
import fr.wseduc.webutils.http.BaseController;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.function.Function;

import static fr.openent.oneroster.rostering.RosterDataset.refId;
import static fr.openent.oneroster.rostering.RosterDataset.refsContain;

/**
 * Service Rostering OneRoster 1.2 (lecture seule), sous /ims/oneroster/rostering/v1p2.
 * Routes hors session ENT : l'accès est contrôlé par le jeton Bearer du client OAuth.
 */
public class RosteringController extends BaseController {

	private static final String[] CORE = Api.scopes(Scopes.ROSTER_CORE_READONLY, Scopes.ROSTER_READONLY);
	private static final String[] FULL = Api.scopes(Scopes.ROSTER_READONLY);
	private static final String[] DEMOGRAPHICS = Api.scopes(Scopes.ROSTER_DEMOGRAPHICS_READONLY);

	private final OneRosterContext ctx;
	private final Api api;

	public RosteringController(OneRosterContext ctx) {
		this.ctx = ctx;
		this.api = new Api(ctx);
		this.pathPrefix = Refs.ROSTERING;
	}

	private <T> Function<fr.openent.oneroster.security.Client, Future<T>> data(Function<RosterDataset, Future<T>> f) {
		return client -> ctx.roster.dataset(client.getStructures()).compose(f);
	}

	private static <T> Future<T> ok(T value) {
		return Future.succeededFuture(value);
	}

	private static String id(HttpServerRequest request) {
		return request.params().get("id");
	}

	// ---------------------------------------------------------------- academicSessions

	@Get("/academicSessions")
	public void academicSessions(HttpServerRequest request) {
		api.list(request, "academicSessions", Entities.ACADEMIC_SESSION, CORE, data(ds -> ok(ds.academicSessions())));
	}

	@Get("/academicSessions/:id")
	public void academicSession(HttpServerRequest request) {
		api.one(request, "academicSession", "academicSession", Entities.ACADEMIC_SESSION, CORE,
				data(ds -> ok(ds.academicSession(id(request)))));
	}

	@Get("/gradingPeriods")
	public void gradingPeriods(HttpServerRequest request) {
		api.list(request, "academicSessions", Entities.ACADEMIC_SESSION, CORE,
				data(ds -> ok(ds.sessionsOfType("gradingPeriod"))));
	}

	@Get("/gradingPeriods/:id")
	public void gradingPeriod(HttpServerRequest request) {
		api.one(request, "academicSession", "gradingPeriod", Entities.ACADEMIC_SESSION, CORE,
				data(ds -> ok(ds.session(id(request), "gradingPeriod"))));
	}

	@Get("/terms")
	public void terms(HttpServerRequest request) {
		api.list(request, "academicSessions", Entities.ACADEMIC_SESSION, CORE, data(ds -> ok(ds.sessionsOfType("term"))));
	}

	@Get("/terms/:id")
	public void term(HttpServerRequest request) {
		api.one(request, "academicSession", "term", Entities.ACADEMIC_SESSION, CORE,
				data(ds -> ok(ds.session(id(request), "term"))));
	}

	@Get("/terms/:id/classes")
	public void termClasses(HttpServerRequest request) {
		final String termId = id(request);
		api.list(request, "classes", Entities.CLASS, FULL, data(ds -> Api.require(ds.session(termId, "term"), "term",
				ds.classesWhere(c -> refsContain(c, "terms", termId)))));
	}

	@Get("/terms/:id/gradingPeriods")
	public void termGradingPeriods(HttpServerRequest request) {
		final String termId = id(request);
		api.list(request, "academicSessions", Entities.ACADEMIC_SESSION, FULL,
				data(ds -> Api.require(ds.session(termId, "term"), "term", ds.sessionsWhere(s ->
						"gradingPeriod".equals(s.getString("type")) && termId.equals(refId(s, "parent"))))));
	}

	// ---------------------------------------------------------------- classes

	@Get("/classes")
	public void classes(HttpServerRequest request) {
		api.list(request, "classes", Entities.CLASS, CORE, data(ds -> ok(ds.classes())));
	}

	@Get("/classes/:id")
	public void clazz(HttpServerRequest request) {
		api.one(request, "class", "class", Entities.CLASS, CORE, data(ds -> ok(ds.clazz(id(request)))));
	}

	@Get("/classes/:id/students")
	public void classStudents(HttpServerRequest request) {
		classMembers(request, id(request), "student");
	}

	@Get("/classes/:id/teachers")
	public void classTeachers(HttpServerRequest request) {
		classMembers(request, id(request), "teacher");
	}

	private void classMembers(HttpServerRequest request, String classId, String role) {
		api.list(request, "users", Entities.USER, FULL, data(ds -> Api.require(ds.clazz(classId), "class",
				ds.enrolledUsers(e -> classId.equals(refId(e, "class")), role))));
	}

	// ---------------------------------------------------------------- courses

	@Get("/courses")
	public void courses(HttpServerRequest request) {
		api.list(request, "courses", Entities.COURSE, CORE, data(ds -> ok(ds.courses())));
	}

	@Get("/courses/:id")
	public void course(HttpServerRequest request) {
		api.one(request, "course", "course", Entities.COURSE, CORE, data(ds -> ok(ds.course(id(request)))));
	}

	@Get("/courses/:id/classes")
	public void courseClasses(HttpServerRequest request) {
		final String courseId = id(request);
		api.list(request, "classes", Entities.CLASS, FULL, data(ds -> Api.require(ds.course(courseId), "course",
				ds.classesWhere(c -> courseId.equals(refId(c, "course"))))));
	}

	// ---------------------------------------------------------------- demographics

	@Get("/demographics")
	public void demographics(HttpServerRequest request) {
		api.list(request, "demographics", Entities.DEMOGRAPHICS, DEMOGRAPHICS, data(ds -> ok(ds.demographics())));
	}

	@Get("/demographics/:id")
	public void demographic(HttpServerRequest request) {
		api.one(request, "demographics", "demographics", Entities.DEMOGRAPHICS, DEMOGRAPHICS,
				data(ds -> ok(ds.demographic(id(request)))));
	}

	// ---------------------------------------------------------------- enrollments

	@Get("/enrollments")
	public void enrollments(HttpServerRequest request) {
		api.list(request, "enrollments", Entities.ENROLLMENT, CORE, data(ds -> ok(ds.enrollments())));
	}

	@Get("/enrollments/:id")
	public void enrollment(HttpServerRequest request) {
		api.one(request, "enrollment", "enrollment", Entities.ENROLLMENT, CORE, data(ds -> ok(ds.enrollment(id(request)))));
	}

	// ---------------------------------------------------------------- orgs & schools

	@Get("/orgs")
	public void orgs(HttpServerRequest request) {
		api.list(request, "orgs", Entities.ORG, CORE, data(ds -> ok(ds.orgs())));
	}

	@Get("/orgs/:id")
	public void org(HttpServerRequest request) {
		api.one(request, "org", "org", Entities.ORG, CORE, data(ds -> ok(ds.org(id(request)))));
	}

	@Get("/schools")
	public void schools(HttpServerRequest request) {
		api.list(request, "orgs", Entities.ORG, CORE, data(ds -> ok(ds.schools())));
	}

	@Get("/schools/:id")
	public void school(HttpServerRequest request) {
		api.one(request, "org", "school", Entities.ORG, CORE, data(ds -> ok(ds.school(id(request)))));
	}

	@Get("/schools/:id/classes")
	public void schoolClasses(HttpServerRequest request) {
		final String schoolId = id(request);
		api.list(request, "classes", Entities.CLASS, FULL, data(ds -> Api.require(ds.school(schoolId), "school",
				ds.classesWhere(c -> schoolId.equals(refId(c, "school"))))));
	}

	@Get("/schools/:id/courses")
	public void schoolCourses(HttpServerRequest request) {
		final String schoolId = id(request);
		api.list(request, "courses", Entities.COURSE, FULL, data(ds -> Api.require(ds.school(schoolId), "school",
				ds.coursesWhere(c -> schoolId.equals(refId(c, "org"))))));
	}

	@Get("/schools/:id/enrollments")
	public void schoolEnrollments(HttpServerRequest request) {
		final String schoolId = id(request);
		api.list(request, "enrollments", Entities.ENROLLMENT, FULL, data(ds -> Api.require(ds.school(schoolId), "school",
				ds.enrollmentsWhere(e -> schoolId.equals(refId(e, "school"))))));
	}

	@Get("/schools/:id/students")
	public void schoolStudents(HttpServerRequest request) {
		schoolUsers(request, id(request), "student");
	}

	@Get("/schools/:id/teachers")
	public void schoolTeachers(HttpServerRequest request) {
		schoolUsers(request, id(request), "teacher");
	}

	/** Utilisateurs ayant le rôle donné dans l'établissement (rôle porté par roles[].org). */
	private void schoolUsers(HttpServerRequest request, String schoolId, String role) {
		api.list(request, "users", Entities.USER, FULL, data(ds -> Api.require(ds.school(schoolId), "school",
				filterUsers(ds.usersWithRole(role), schoolId, role))));
	}

	private static List<JsonObject> filterUsers(List<JsonObject> users, String schoolId, String role) {
		final List<JsonObject> result = new java.util.ArrayList<>();
		for (JsonObject u : users) {
			for (Object o : u.getJsonArray("roles")) {
				final JsonObject r = (JsonObject) o;
				if (role.equals(r.getString("role")) && schoolId.equals(refId(r, "org"))) {
					result.add(u);
					break;
				}
			}
		}
		return result;
	}

	@Get("/schools/:id/terms")
	public void schoolTerms(HttpServerRequest request) {
		final String schoolId = id(request);
		api.list(request, "academicSessions", Entities.ACADEMIC_SESSION, FULL,
				data(ds -> Api.require(ds.school(schoolId), "school", ds.sessionsWhere(s ->
						"term".equals(s.getString("type")) && schoolId.equals(refId(s, "org"))))));
	}

	@Get("/schools/:id/classes/:classId/enrollments")
	public void schoolClassEnrollments(HttpServerRequest request) {
		final String schoolId = id(request);
		final String classId = request.params().get("classId");
		api.list(request, "enrollments", Entities.ENROLLMENT, FULL, data(ds -> Api.require(schoolClass(ds, schoolId, classId),
				"class", ds.enrollmentsWhere(e -> classId.equals(refId(e, "class"))))));
	}

	@Get("/schools/:id/classes/:classId/students")
	public void schoolClassStudents(HttpServerRequest request) {
		schoolClassMembers(request, "student");
	}

	@Get("/schools/:id/classes/:classId/teachers")
	public void schoolClassTeachers(HttpServerRequest request) {
		schoolClassMembers(request, "teacher");
	}

	private void schoolClassMembers(HttpServerRequest request, String role) {
		final String schoolId = id(request);
		final String classId = request.params().get("classId");
		api.list(request, "users", Entities.USER, FULL, data(ds -> Api.require(schoolClass(ds, schoolId, classId),
				"class", ds.enrolledUsers(e -> classId.equals(refId(e, "class")), role))));
	}

	private static JsonObject schoolClass(RosterDataset ds, String schoolId, String classId) {
		final JsonObject c = ds.clazz(classId);
		return c != null && schoolId.equals(refId(c, "school")) ? c : null;
	}

	// ---------------------------------------------------------------- users, students, teachers

	@Get("/users")
	public void users(HttpServerRequest request) {
		api.list(request, "users", Entities.USER, CORE, data(ds -> ok(ds.users())));
	}

	@Get("/users/:id")
	public void user(HttpServerRequest request) {
		api.one(request, "user", "user", Entities.USER, CORE, data(ds -> ok(ds.user(id(request)))));
	}

	@Get("/users/:id/classes")
	public void userClasses(HttpServerRequest request) {
		userClasses(request, id(request), null);
	}

	@Get("/students")
	public void students(HttpServerRequest request) {
		api.list(request, "users", Entities.USER, CORE, data(ds -> ok(ds.usersWithRole("student"))));
	}

	@Get("/students/:id")
	public void student(HttpServerRequest request) {
		api.one(request, "user", "student", Entities.USER, CORE, data(ds -> ok(ds.userWithRole(id(request), "student"))));
	}

	@Get("/students/:id/classes")
	public void studentClasses(HttpServerRequest request) {
		userClasses(request, id(request), "student");
	}

	@Get("/teachers")
	public void teachers(HttpServerRequest request) {
		api.list(request, "users", Entities.USER, CORE, data(ds -> ok(ds.usersWithRole("teacher"))));
	}

	@Get("/teachers/:id")
	public void teacher(HttpServerRequest request) {
		api.one(request, "user", "teacher", Entities.USER, CORE, data(ds -> ok(ds.userWithRole(id(request), "teacher"))));
	}

	@Get("/teachers/:id/classes")
	public void teacherClasses(HttpServerRequest request) {
		userClasses(request, id(request), "teacher");
	}

	private void userClasses(HttpServerRequest request, String userId, String role) {
		api.list(request, "classes", Entities.CLASS, FULL, data(ds -> {
			final JsonObject user = role == null ? ds.user(userId) : ds.userWithRole(userId, role);
			return Api.require(user, role == null ? "user" : role, ds.classesOfUser(userId));
		}));
	}
}
