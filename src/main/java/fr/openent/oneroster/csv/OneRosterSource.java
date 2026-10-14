package fr.openent.oneroster.csv;

import fr.openent.oneroster.core.OneRosterException;
import fr.openent.oneroster.core.Refs;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Données OneRoster à importer (orgs, users, classes, inscriptions, dates de naissance), lues
 * depuis une archive CSV (1.2 ou 1.1) ou depuis les réponses JSON d'un fournisseur REST.
 * Les enregistrements « tobedeleted » sont ignorés.
 */
public final class OneRosterSource {

	private static final long MAX_ENTRY_SIZE = 64L * 1024 * 1024;

	public final Map<String, Org> orgs = new LinkedHashMap<>();
	public final Map<String, User> users = new LinkedHashMap<>();
	public final Map<String, Clazz> classes = new LinkedHashMap<>();
	public final List<Enrollment> enrollments = new ArrayList<>();

	public static final class Org {
		public String sourcedId;
		public String name;
		public String type;
		public String identifier;
	}

	public static final class User {
		public String sourcedId;
		public String givenName;
		public String familyName;
		public String email;
		public String birthDate;
		public final Map<String, String> userIds = new HashMap<>();
		public final List<String> agents = new ArrayList<>();
		/** rôle → établissements où l'utilisateur a ce rôle */
		public final Map<String, List<String>> roles = new LinkedHashMap<>();

		void addRole(String role, String org) {
			if (role != null && org != null) {
				final List<String> orgs = roles.computeIfAbsent(role, k -> new ArrayList<>());
				if (!orgs.contains(org)) {
					orgs.add(org);
				}
			}
		}
	}

	public static final class Clazz {
		public String sourcedId;
		public String title;
		public String classType;
		public String school;
	}

	public static final class Enrollment {
		public String user;
		public String clazz;
		public String school;
		public String role;
	}

	// ================================================================= archive CSV

	public static OneRosterSource fromZip(byte[] zip) throws OneRosterException {
		final Map<String, String> files = unzip(zip);
		if (!files.containsKey("users.csv")) {
			throw new OneRosterException(422, "csv.users.missing");
		}
		final Map<String, String> manifest = new HashMap<>();
		if (files.containsKey("manifest.csv")) {
			for (Map<String, String> r : Csv.records(files.get("manifest.csv"))) {
				manifest.put(r.get("propertyName"), r.get("value"));
			}
			final String version = manifest.get("oneroster.version");
			if (version != null && !version.startsWith("1.")) {
				throw new OneRosterException(422, "csv.unsupported.version");
			}
		}
		final OneRosterSource source = new OneRosterSource();
		for (Map<String, String> r : records(files, "orgs.csv")) {
			final Org org = new Org();
			org.sourcedId = r.get("sourcedId");
			org.name = r.get("name");
			org.type = r.get("type");
			org.identifier = r.get("identifier");
			source.orgs.put(org.sourcedId, org);
		}
		for (Map<String, String> r : records(files, "users.csv")) {
			final User u = new User();
			u.sourcedId = r.get("sourcedId");
			u.givenName = r.get("givenName");
			u.familyName = r.get("familyName");
			u.email = blankToNull(r.get("email"));
			u.agents.addAll(Csv.list(r.get("agentSourcedIds")));
			parseUserIds(r.get("userIds"), u.userIds);
			// OneRoster 1.1 : rôle et établissements portés par users.csv
			for (String org : Csv.list(r.get("orgSourcedIds"))) {
				u.addRole(r.get("role"), org);
			}
			source.users.put(u.sourcedId, u);
		}
		for (Map<String, String> r : records(files, "roles.csv")) {
			final User u = source.users.get(r.get("userSourcedId"));
			if (u != null) {
				u.addRole(r.get("role"), r.get("orgSourcedId"));
			}
		}
		for (Map<String, String> r : records(files, "demographics.csv")) {
			final User u = source.users.get(r.get("sourcedId"));
			if (u != null) {
				u.birthDate = blankToNull(r.get("birthDate"));
			}
		}
		for (Map<String, String> r : records(files, "classes.csv")) {
			final Clazz c = new Clazz();
			c.sourcedId = r.get("sourcedId");
			c.title = r.get("title");
			c.classType = r.get("classType");
			c.school = r.get("schoolSourcedId");
			source.classes.put(c.sourcedId, c);
		}
		for (Map<String, String> r : records(files, "enrollments.csv")) {
			final Enrollment e = new Enrollment();
			e.user = r.get("userSourcedId");
			e.clazz = r.get("classSourcedId");
			e.school = r.get("schoolSourcedId");
			e.role = r.get("role");
			source.enrollments.add(e);
		}
		return source;
	}

	private static List<Map<String, String>> records(Map<String, String> files, String name) {
		final List<Map<String, String>> active = new ArrayList<>();
		if (!files.containsKey(name)) {
			return active;
		}
		for (Map<String, String> r : Csv.records(files.get(name))) {
			if (!"tobedeleted".equalsIgnoreCase(r.getOrDefault("status", ""))) {
				active.add(r);
			}
		}
		return active;
	}

	/** « {type:id},{type:id} » → type → id. */
	static void parseUserIds(String value, Map<String, String> into) {
		if (value == null) {
			return;
		}
		for (String part : value.split("}")) {
			final String p = part.replaceFirst("^[,\\s]*\\{", "").trim();
			final int colon = p.indexOf(':');
			if (colon > 0) {
				into.put(p.substring(0, colon).trim(), p.substring(colon + 1).trim());
			}
		}
	}

	static Map<String, String> unzip(byte[] zip) throws OneRosterException {
		final Map<String, String> files = new HashMap<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
			ZipEntry entry;
			final byte[] buffer = new byte[8192];
			while ((entry = in.getNextEntry()) != null) {
				if (entry.isDirectory()) {
					continue;
				}
				final ByteArrayOutputStream out = new ByteArrayOutputStream();
				int n;
				long total = 0;
				while ((n = in.read(buffer)) > 0) {
					total += n;
					if (total > MAX_ENTRY_SIZE) {
						throw new OneRosterException(413, "csv.file.too.large");
					}
					out.write(buffer, 0, n);
				}
				// Les fichiers peuvent être rangés dans un sous-dossier de l'archive : seul le nom compte.
				final String name = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
				files.put(name, new String(out.toByteArray(), StandardCharsets.UTF_8));
			}
		} catch (IOException e) {
			throw new OneRosterException(422, "csv.invalid.zip");
		}
		if (files.isEmpty()) {
			throw new OneRosterException(422, "csv.invalid.zip");
		}
		return files;
	}

	// ================================================================= fournisseur REST (JSON 1.2)

	public void addOrgs(JsonArray orgs) {
		for (JsonObject o : active(orgs)) {
			final Org org = new Org();
			org.sourcedId = o.getString("sourcedId");
			org.name = o.getString("name");
			org.type = o.getString("type");
			org.identifier = o.getString("identifier");
			this.orgs.put(org.sourcedId, org);
		}
	}

	public void addUsers(JsonArray users) {
		for (JsonObject o : active(users)) {
			final User u = new User();
			u.sourcedId = o.getString("sourcedId");
			u.givenName = o.getString("givenName");
			u.familyName = o.getString("familyName");
			u.email = blankToNull(o.getString("email"));
			for (Object a : o.getJsonArray("agents", new JsonArray())) {
				final String id = Refs.sourcedId(a);
				if (id != null) {
					u.agents.add(id);
				}
			}
			for (Object i : o.getJsonArray("userIds", new JsonArray())) {
				if (i instanceof JsonObject) {
					u.userIds.put(((JsonObject) i).getString("type"), ((JsonObject) i).getString("identifier"));
				}
			}
			for (Object r : o.getJsonArray("roles", new JsonArray())) {
				if (r instanceof JsonObject) {
					u.addRole(((JsonObject) r).getString("role"), Refs.sourcedId(((JsonObject) r).getValue("org")));
				}
			}
			// OneRoster 1.1 en JSON : role + orgs[]
			for (Object org : o.getJsonArray("orgs", new JsonArray())) {
				u.addRole(o.getString("role"), Refs.sourcedId(org));
			}
			this.users.put(u.sourcedId, u);
		}
	}

	public void addClasses(JsonArray classes) {
		for (JsonObject o : active(classes)) {
			final Clazz c = new Clazz();
			c.sourcedId = o.getString("sourcedId");
			c.title = o.getString("title");
			c.classType = o.getString("classType");
			c.school = Refs.sourcedId(o.getValue("school"));
			this.classes.put(c.sourcedId, c);
		}
	}

	public void addEnrollments(JsonArray enrollments) {
		for (JsonObject o : active(enrollments)) {
			final Enrollment e = new Enrollment();
			e.user = Refs.sourcedId(o.getValue("user"));
			e.clazz = Refs.sourcedId(o.getValue("class"));
			e.school = Refs.sourcedId(o.getValue("school"));
			e.role = o.getString("role");
			this.enrollments.add(e);
		}
	}

	public void addDemographics(JsonArray demographics) {
		for (JsonObject o : active(demographics)) {
			final User u = users.get(o.getString("sourcedId"));
			if (u != null) {
				u.birthDate = blankToNull(o.getString("birthDate"));
			}
		}
	}

	private static List<JsonObject> active(JsonArray array) {
		final List<JsonObject> list = new ArrayList<>();
		if (array != null) {
			for (Object o : array) {
				if (o instanceof JsonObject && !"tobedeleted".equals(((JsonObject) o).getString("status"))
						&& ((JsonObject) o).getString("sourcedId") != null) {
					list.add((JsonObject) o);
				}
			}
		}
		return list;
	}

	private static String blankToNull(String s) {
		return s == null || s.trim().isEmpty() ? null : s.trim();
	}
}
