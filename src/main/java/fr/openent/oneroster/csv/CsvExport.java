package fr.openent.oneroster.csv;

import fr.openent.oneroster.core.Entities;
import fr.openent.oneroster.resources.ResourceService;
import fr.openent.oneroster.rostering.RosterDataset;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static fr.openent.oneroster.rostering.RosterDataset.refId;

/**
 * Export au format CSV OneRoster 1.2.1, mode « bulk » : un fichier par entité (en-têtes et ordre
 * des colonnes du binding CSV), un manifest.csv, le tout dans une archive ZIP. En mode bulk,
 * status et dateLastModified restent vides et les entités « tobedeleted » sont omises.
 * Un fichier sans ligne n'est pas produit (le binding l'interdit) et figure « absent » au manifest.
 */
public final class CsvExport {

	static final List<String> MANIFEST_FILES = Arrays.asList("academicSessions", "categories", "classes",
			"classResources", "courses", "courseResources", "demographics", "enrollments",
			"lineItemLearningObjectiveIds", "lineItems", "lineItemScoreScales", "orgs", "resources",
			"resultLearningObjectiveIds", "results", "resultScoreScales", "roles", "scoreScales", "userProfiles",
			"userResources", "users");

	private final Map<String, String> files = new LinkedHashMap<>();

	public CsvExport roster(RosterDataset ds) {
		file("academicSessions", Arrays.asList("title", "type", "startDate", "endDate", "parentSourcedId", "schoolYear"),
				ds.academicSessions(), s -> row(s.getString("title"), s.getString("type"), s.getString("startDate"),
						s.getString("endDate"), refId(s, "parent"), s.getString("schoolYear")));
		file("orgs", Arrays.asList("name", "type", "identifier", "parentSourcedId"), ds.orgs(),
				o -> row(o.getString("name"), o.getString("type"), o.getString("identifier"), refId(o, "parent")));
		file("courses", Arrays.asList("schoolYearSourcedId", "title", "courseCode", "grades", "orgSourcedId", "subjects",
				"subjectCodes"), ds.courses(), c -> row(refId(c, "schoolYear"), c.getString("title"),
				c.getString("courseCode"), join(c.getJsonArray("grades")), refId(c, "org"),
				join(c.getJsonArray("subjects")), join(c.getJsonArray("subjectCodes"))));
		file("classes", Arrays.asList("title", "grades", "courseSourcedId", "classCode", "classType", "location",
				"schoolSourcedId", "termSourcedIds", "subjects", "subjectCodes", "periods"), ds.classes(),
				c -> row(c.getString("title"), join(c.getJsonArray("grades")), refId(c, "course"), c.getString("classCode"),
						c.getString("classType"), c.getString("location"), refId(c, "school"),
						joinRefs(c.getJsonArray("terms")), join(c.getJsonArray("subjects")),
						join(c.getJsonArray("subjectCodes")), join(c.getJsonArray("periods"))));
		file("users", Arrays.asList("enabledUser", "username", "userIds", "givenName", "familyName", "middleName",
				"identifier", "email", "sms", "phone", "agentSourcedIds", "grades", "password", "userMasterIdentifier",
				"preferredGivenName", "preferredMiddleName", "preferredFamilyName", "primaryOrgSourcedId", "pronouns"),
				ds.users(), u -> row(String.valueOf(u.getBoolean("enabledUser", true)), u.getString("username"),
						userIds(u.getJsonArray("userIds")), u.getString("givenName"), u.getString("familyName"),
						u.getString("middleName"), u.getString("identifier"), u.getString("email"), u.getString("sms"),
						u.getString("phone"), joinRefs(u.getJsonArray("agents")), join(u.getJsonArray("grades")), null,
						u.getString("userMasterIdentifier"), null, null, null, refId(u, "primaryOrg"), null));
		final List<JsonObject> roles = new ArrayList<>();
		for (JsonObject u : ds.users()) {
			if (Entities.TO_BE_DELETED.equals(u.getString("status"))) {
				continue;
			}
			for (Object o : u.getJsonArray("roles", new JsonArray())) {
				final JsonObject r = (JsonObject) o;
				roles.add(new JsonObject().put("sourcedId", u.getString("sourcedId") + "." + refId(r, "org"))
						.put("status", Entities.ACTIVE).put("user", u.getString("sourcedId")).put("role", r));
			}
		}
		file("roles", Arrays.asList("userSourcedId", "roleType", "role", "beginDate", "endDate", "orgSourcedId",
				"userProfileSourcedId"), roles, x -> {
					final JsonObject r = x.getJsonObject("role");
					return row(x.getString("user"), r.getString("roleType"), r.getString("role"), r.getString("beginDate"),
							r.getString("endDate"), refId(r, "org"), null);
				});
		file("enrollments", Arrays.asList("classSourcedId", "schoolSourcedId", "userSourcedId", "role", "primary",
				"beginDate", "endDate"), ds.enrollments(), e -> row(refId(e, "class"), refId(e, "school"),
				refId(e, "user"), e.getString("role"), String.valueOf(e.getBoolean("primary", false)),
				e.getString("beginDate"), e.getString("endDate")));
		file("demographics", Arrays.asList("birthDate", "sex", "americanIndianOrAlaskaNative", "asian",
				"blackOrAfricanAmerican", "nativeHawaiianOrOtherPacificIslander", "white", "demographicRaceTwoOrMoreRaces",
				"hispanicOrLatinoEthnicity", "countryOfBirthCode", "stateOfBirthAbbreviation", "cityOfBirth",
				"publicSchoolResidenceStatus"), ds.demographics(), d -> row(d.getString("birthDate"), d.getString("sex"),
				null, null, null, null, null, null, null, null, null, null, null));
		return this;
	}

	public CsvExport gradebook(List<JsonObject> lineItems, List<JsonObject> results, List<JsonObject> categories) {
		file("categories", Arrays.asList("title", "weight"), categories,
				c -> row(c.getString("title"), c.getValue("weight") != null ? String.valueOf(c.getValue("weight")) : null));
		file("lineItems", Arrays.asList("title", "description", "assignDate", "dueDate", "classSourcedId",
				"categorySourcedId", "academicSessionSourcedId", "resultValueMin", "resultValueMax", "schoolSourcedId"),
				lineItems, l -> row(l.getString("title"), l.getString("description"), l.getString("assignDate"),
						l.getString("dueDate"), refId(l, "class"), refId(l, "category"), refId(l, "gradingPeriod"),
						number(l.getValue("resultValueMin")), number(l.getValue("resultValueMax")), refId(l, "school")));
		file("results", Arrays.asList("lineItemSourcedId", "studentSourcedId", "scoreStatus", "score", "scoreDate",
				"comment", "textScore", "classSourcedId", "inProgress", "incomplete", "late", "missing"), results,
				r -> row(refId(r, "lineItem"), refId(r, "student"), r.getString("scoreStatus"), number(r.getValue("score")),
						Entities.date(r.getString("scoreDate")), r.getString("comment"), r.getString("textScore"),
						refId(r, "class"), bool(r, "inProgress"), bool(r, "incomplete"), bool(r, "late"), bool(r, "missing")));
		return this;
	}

	public CsvExport resources(RosterDataset ds, ResourceService.Linked linked) {
		file("resources", Arrays.asList("vendorResourceId", "title", "roles", "importance", "vendorId", "applicationId"),
				linked.all(), r -> row(r.getString("vendorResourceId"), r.getString("title"), join(r.getJsonArray("roles")),
						r.getString("importance"), r.getString("vendorId"), r.getString("applicationId")));
		final List<JsonObject> classLinks = new ArrayList<>();
		for (Map.Entry<String, List<String>> e : linked.classLinks().entrySet()) {
			for (String classId : e.getValue()) {
				classLinks.add(new JsonObject().put("sourcedId", classId + "." + e.getKey()).put("status", Entities.ACTIVE)
						.put("class", classId).put("resource", e.getKey())
						.put("title", linked.get(e.getKey()).getString("title")));
			}
		}
		file("classResources", Arrays.asList("title", "classSourcedId", "resourceSourcedId"), classLinks,
				l -> row(l.getString("title"), l.getString("class"), l.getString("resource")));
		final List<JsonObject> userLinks = new ArrayList<>();
		for (Map.Entry<String, List<String>> e : linked.userLinks().entrySet()) {
			for (String userId : e.getValue()) {
				final JsonObject user = ds.user(userId);
				userLinks.add(new JsonObject().put("sourcedId", userId + "." + e.getKey()).put("status", Entities.ACTIVE)
						.put("user", userId).put("resource", e.getKey())
						.put("org", user != null ? refId(user, "primaryOrg") : null));
			}
		}
		file("userResources", Arrays.asList("userSourcedId", "orgSourcedId", "classSourcedId", "resourceSourcedId"),
				userLinks, l -> row(l.getString("user"), l.getString("org"), null, l.getString("resource")));
		return this;
	}

	/** Fichiers produits (nom → contenu), manifest compris. */
	public Map<String, String> files(String systemName) {
		final StringBuilder manifest = new StringBuilder(Csv.line(Arrays.asList("propertyName", "value"), ',', false));
		manifest.append(Csv.line(Arrays.asList("manifest.version", "1.0"), ',', false));
		manifest.append(Csv.line(Arrays.asList("oneroster.version", "1.2"), ',', false));
		for (String f : MANIFEST_FILES) {
			manifest.append(Csv.line(Arrays.asList("file." + f, files.containsKey(f + ".csv") ? "bulk" : "absent"), ',', false));
		}
		manifest.append(Csv.line(Arrays.asList("source.systemName", systemName), ',', false));
		manifest.append(Csv.line(Arrays.asList("source.systemCode", "open-ent"), ',', false));
		final Map<String, String> all = new LinkedHashMap<>();
		all.put("manifest.csv", manifest.toString());
		all.putAll(files);
		return all;
	}

	public byte[] zip(String systemName) throws IOException {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
			for (Map.Entry<String, String> f : files(systemName).entrySet()) {
				zip.putNextEntry(new ZipEntry(f.getKey()));
				zip.write(f.getValue().getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		return out.toByteArray();
	}

	private void file(String name, List<String> columns, List<JsonObject> entities, Function<JsonObject, List<String>> row) {
		final StringBuilder sb = new StringBuilder();
		final List<String> header = new ArrayList<>(Arrays.asList("sourcedId", "status", "dateLastModified"));
		header.addAll(columns);
		int count = 0;
		for (JsonObject e : entities) {
			if (Entities.TO_BE_DELETED.equals(e.getString("status"))) {
				continue;
			}
			final List<String> values = new ArrayList<>(Arrays.asList(e.getString("sourcedId"), "", ""));
			values.addAll(row.apply(e));
			sb.append(Csv.line(values, ',', false));
			count++;
		}
		if (count > 0) {
			files.put(name + ".csv", Csv.line(header, ',', false) + sb);
		}
	}

	private static List<String> row(String... values) {
		return Arrays.asList(values);
	}

	private static String join(JsonArray array) {
		if (array == null || array.isEmpty()) {
			return null;
		}
		final List<String> values = new ArrayList<>();
		for (Object o : array) {
			values.add(String.valueOf(o));
		}
		return String.join(",", values);
	}

	private static String joinRefs(JsonArray refs) {
		if (refs == null || refs.isEmpty()) {
			return null;
		}
		final List<String> ids = new ArrayList<>();
		for (Object o : refs) {
			ids.add(((JsonObject) o).getString("sourcedId"));
		}
		return String.join(",", ids);
	}

	/** userIds au format CSV : {type:identifiant},{type:identifiant}. */
	private static String userIds(JsonArray userIds) {
		if (userIds == null || userIds.isEmpty()) {
			return null;
		}
		final List<String> values = new ArrayList<>();
		for (Object o : userIds) {
			final JsonObject id = (JsonObject) o;
			values.add("{" + id.getString("type") + ":" + id.getString("identifier") + "}");
		}
		return String.join(",", values);
	}

	private static String number(Object value) {
		if (!(value instanceof Number)) {
			return null;
		}
		final double d = ((Number) value).doubleValue();
		return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
	}

	private static String bool(JsonObject o, String field) {
		return o.getValue(field) instanceof Boolean ? String.valueOf(o.getBoolean(field)) : null;
	}
}
