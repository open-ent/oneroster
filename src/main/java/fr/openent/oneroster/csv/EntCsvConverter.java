package fr.openent.oneroster.csv;

import fr.openent.oneroster.core.OneRosterException;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Convertit les données OneRoster d'un établissement en fichiers d'import CSV de l'annuaire
 * (un fichier par profil, séparateur « ; », en-têtes reconnus par le feeder) :
 * <ul>
 *   <li>student → Student.csv ; teacher → Teacher.csv ; parent, guardian, relative → Relative.csv ;
 *       autres rôles → Personnel.csv ;</li>
 *   <li>classes « homeroom » → classes de l'annuaire, classes « scheduled » → groupes ;</li>
 *   <li>sourcedId (ou identifiant externe d'origine) → externalId, ce qui rend l'import rejouable.</li>
 * </ul>
 */
public final class EntCsvConverter {

	private static final Set<String> RELATIVE_ROLES = new LinkedHashSet<>(Arrays.asList("parent", "guardian", "relative"));

	private final OneRosterSource source;
	private final String orgId;

	public EntCsvConverter(OneRosterSource source, String orgId) {
		this.source = source;
		this.orgId = orgId;
	}

	/** Établissement à importer : celui désigné, sinon l'unique org de type « school ». */
	public static String chooseOrg(OneRosterSource source, String requested) throws OneRosterException {
		if (requested != null && !requested.trim().isEmpty()) {
			if (!source.orgs.containsKey(requested) && !hasMembers(source, requested)) {
				throw new OneRosterException(422, "org.not.found.in.source");
			}
			return requested;
		}
		final List<String> schools = new ArrayList<>();
		for (OneRosterSource.Org o : source.orgs.values()) {
			if ("school".equals(o.type)) {
				schools.add(o.sourcedId);
			}
		}
		if (schools.size() != 1) {
			throw new OneRosterException(422, schools.isEmpty() ? "no.school.in.source" : "several.schools.choose.orgSourcedId");
		}
		return schools.get(0);
	}

	private static boolean hasMembers(OneRosterSource source, String orgId) {
		for (OneRosterSource.User u : source.users.values()) {
			for (List<String> orgs : u.roles.values()) {
				if (orgs.contains(orgId)) {
					return true;
				}
			}
		}
		return false;
	}

	/** Fichiers par profil (Student, Teacher, Relative, Personnel), contenu CSV UTF-8 avec BOM. */
	public Map<String, String> convert() {
		final Map<String, String> profileOf = new LinkedHashMap<>();
		for (OneRosterSource.User u : source.users.values()) {
			final String profile = profile(u);
			if (profile != null) {
				profileOf.put(u.sourcedId, profile);
			}
		}
		final Map<String, List<String>> classesOf = new LinkedHashMap<>();
		final Map<String, List<String>> groupsOf = new LinkedHashMap<>();
		for (OneRosterSource.Enrollment e : source.enrollments) {
			final OneRosterSource.Clazz c = source.classes.get(e.clazz);
			if (c == null || !orgId.equals(c.school) || c.title == null) {
				continue;
			}
			if (!profileOf.containsKey(e.user) && ("student".equals(e.role) || "teacher".equals(e.role))) {
				// inscrit sans rôle explicite dans l'établissement (OneRoster 1.1, roles.csv absent)
				profileOf.put(e.user, "student".equals(e.role) ? "Student" : "Teacher");
			}
			if (wholeClassSection(c)) {
				continue;
			}
			final Map<String, List<String>> target = "scheduled".equals(c.classType) ? groupsOf : classesOf;
			final List<String> names = target.computeIfAbsent(e.user, k -> new ArrayList<>());
			if (!names.contains(c.title.trim())) {
				names.add(c.title.trim());
			}
		}
		// Responsables des élèves importés, même sans rôle déclaré dans l'établissement.
		final Map<String, List<String>> childrenOf = new LinkedHashMap<>();
		for (OneRosterSource.User u : source.users.values()) {
			if (!"Student".equals(profileOf.get(u.sourcedId))) {
				continue;
			}
			for (String agent : u.agents) {
				final OneRosterSource.User a = source.users.get(agent);
				if (a != null && (!profileOf.containsKey(agent) || "Relative".equals(profileOf.get(agent)))) {
					profileOf.put(agent, "Relative");
					add(childrenOf, agent, u.sourcedId);
				}
			}
		}
		for (OneRosterSource.User u : source.users.values()) {
			if ("Relative".equals(profileOf.get(u.sourcedId))) {
				for (String agent : u.agents) {
					if ("Student".equals(profileOf.get(agent))) {
						add(childrenOf, u.sourcedId, agent);
					}
				}
			}
		}
		final Map<String, String> files = new LinkedHashMap<>();
		files.put("Student", file(profileOf, "Student", classesOf, groupsOf, childrenOf));
		files.put("Teacher", file(profileOf, "Teacher", classesOf, groupsOf, childrenOf));
		files.put("Personnel", file(profileOf, "Personnel", classesOf, groupsOf, childrenOf));
		files.put("Relative", file(profileOf, "Relative", classesOf, groupsOf, childrenOf));
		files.values().removeIf(content -> content == null);
		return files;
	}

	/**
	 * Section Open ENT d'une classe entière ({@code <matière>.<classe>}, classe présente dans le
	 * fichier) : elle n'apporte aucun regroupement de plus que la classe et ne devient pas un groupe.
	 */
	private boolean wholeClassSection(OneRosterSource.Clazz c) {
		if (!"scheduled".equals(c.classType) || c.sourcedId == null) {
			return false;
		}
		final int dot = c.sourcedId.indexOf('.');
		if (dot <= 0) {
			return false;
		}
		final OneRosterSource.Clazz group = source.classes.get(c.sourcedId.substring(dot + 1));
		return group != null && "homeroom".equals(group.classType);
	}

	/** Effectifs par profil, pour le rapport d'import. */
	public static JsonObject counts(Map<String, String> files) {
		final JsonObject counts = new JsonObject();
		for (Map.Entry<String, String> f : files.entrySet()) {
			counts.put(f.getKey(), Csv.parse(f.getValue(), ';').size() - 1);
		}
		return counts;
	}

	private String profile(OneRosterSource.User u) {
		String profile = null;
		for (Map.Entry<String, List<String>> r : u.roles.entrySet()) {
			if (!r.getValue().contains(orgId)) {
				continue;
			}
			final String p = "student".equals(r.getKey()) ? "Student" : "teacher".equals(r.getKey()) ? "Teacher"
					: RELATIVE_ROLES.contains(r.getKey()) ? "Relative" : "Personnel";
			// Priorité : élève, puis enseignant, puis personnel, puis responsable.
			if (profile == null || rank(p) < rank(profile)) {
				profile = p;
			}
		}
		return profile;
	}

	private static int rank(String profile) {
		return Arrays.asList("Student", "Teacher", "Personnel", "Relative").indexOf(profile);
	}

	private String file(Map<String, String> profileOf, String profile, Map<String, List<String>> classesOf,
			Map<String, List<String>> groupsOf, Map<String, List<String>> childrenOf) {
		final List<OneRosterSource.User> users = new ArrayList<>();
		int maxClasses = 0;
		int maxGroups = 0;
		int maxChildren = 0;
		for (Map.Entry<String, String> e : profileOf.entrySet()) {
			if (!profile.equals(e.getValue())) {
				continue;
			}
			final OneRosterSource.User u = source.users.get(e.getKey());
			if (u == null || u.familyName == null || u.givenName == null) {
				continue;
			}
			if ("Relative".equals(profile) && !childrenOf.containsKey(u.sourcedId)) {
				// Responsable sans élève importé : rien à rattacher dans cet établissement.
				continue;
			}
			users.add(u);
			maxClasses = Math.max(maxClasses, classesOf.getOrDefault(u.sourcedId, new ArrayList<>()).size());
			maxGroups = Math.max(maxGroups, groupsOf.getOrDefault(u.sourcedId, new ArrayList<>()).size());
			maxChildren = Math.max(maxChildren, childrenOf.getOrDefault(u.sourcedId, new ArrayList<>()).size());
		}
		if (users.isEmpty()) {
			return null;
		}
		final boolean student = "Student".equals(profile);
		final boolean relative = "Relative".equals(profile);
		final boolean member = student || "Teacher".equals(profile);
		final List<String> header = new ArrayList<>(Arrays.asList("externalid", "nom", "prenom", "email"));
		if (student) {
			header.add("datedenaissance");
			header.add("ine");
		}
		if (member) {
			repeat(header, "classe", maxClasses);
			repeat(header, "regroupement", maxGroups);
		}
		if (relative) {
			repeat(header, "idenfant", maxChildren);
		}
		final StringBuilder sb = new StringBuilder("﻿").append(Csv.line(header, ';', true));
		for (OneRosterSource.User u : users) {
			final List<String> row = new ArrayList<>(Arrays.asList(externalId(u), u.familyName, u.givenName, u.email));
			if (student) {
				row.add(frenchDate(u.birthDate));
				row.add(ine(u));
			}
			if (member) {
				pad(row, classesOf.get(u.sourcedId), maxClasses, false);
				pad(row, groupsOf.get(u.sourcedId), maxGroups, false);
			}
			if (relative) {
				// Le feeder ne saute pas les cellules « enfant » vides : on répète le premier enfant (lien idempotent).
				final List<String> children = new ArrayList<>();
				for (String child : childrenOf.getOrDefault(u.sourcedId, new ArrayList<>())) {
					children.add(externalId(source.users.get(child)));
				}
				pad(row, children, maxChildren, true);
			}
			sb.append(Csv.line(row, ';', true));
		}
		return sb.toString();
	}

	/**
	 * Identifiant externe dans l'annuaire : celui d'origine si l'archive vient d'un ENT Open ENT
	 * (userIds de type « externalId »), sinon le sourcedId. Un nouvel import du même fichier
	 * met donc à jour les comptes au lieu d'en créer de nouveaux.
	 */
	static String externalId(OneRosterSource.User u) {
		final String original = u.userIds.get("externalId");
		return original != null && !original.trim().isEmpty() ? original.trim() : u.sourcedId;
	}

	private static String ine(OneRosterSource.User u) {
		for (Map.Entry<String, String> id : u.userIds.entrySet()) {
			if ("INE".equalsIgnoreCase(id.getKey())) {
				return id.getValue();
			}
		}
		return null;
	}

	/** AAAA-MM-JJ → JJ/MM/AAAA, format attendu par l'import CSV de l'annuaire. */
	static String frenchDate(String isoDate) {
		if (isoDate == null || !isoDate.matches("\\d{4}-\\d{2}-\\d{2}.*")) {
			return null;
		}
		return isoDate.substring(8, 10) + "/" + isoDate.substring(5, 7) + "/" + isoDate.substring(0, 4);
	}

	private static void repeat(List<String> header, String column, int count) {
		for (int i = 0; i < count; i++) {
			header.add(column);
		}
	}

	private static void pad(List<String> row, List<String> values, int count, boolean repeatFirst) {
		final List<String> v = values == null ? new ArrayList<>() : values;
		for (int i = 0; i < count; i++) {
			row.add(i < v.size() ? v.get(i) : repeatFirst && !v.isEmpty() ? v.get(0) : null);
		}
	}

	private static void add(Map<String, List<String>> map, String key, String value) {
		final List<String> list = map.computeIfAbsent(key, k -> new ArrayList<>());
		if (!list.contains(value)) {
			list.add(value);
		}
	}
}
