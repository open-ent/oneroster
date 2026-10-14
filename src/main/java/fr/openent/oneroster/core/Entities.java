package fr.openent.oneroster.core;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Champs communs, dates et nomenclatures des entités OneRoster 1.2. */
public final class Entities {

	public static final String ACTIVE = "active";
	public static final String TO_BE_DELETED = "tobedeleted";

	private static final String[] BASE = {"sourcedId", "status", "dateLastModified", "metadata"};

	public static final Set<String> ORG = fields("name", "type", "identifier", "parent", "children");
	public static final Set<String> ACADEMIC_SESSION = fields("title", "startDate", "endDate", "type", "parent",
			"children", "schoolYear", "org");
	public static final Set<String> COURSE = fields("title", "schoolYear", "courseCode", "grades", "subjects", "org",
			"subjectCodes", "resources");
	public static final Set<String> CLASS = fields("title", "classCode", "classType", "location", "grades", "subjects",
			"course", "school", "terms", "subjectCodes", "periods", "resources");
	public static final Set<String> USER = fields("userMasterIdentifier", "username", "userIds", "enabledUser",
			"givenName", "familyName", "middleName", "preferredFirstName", "preferredMiddleName", "preferredLastName",
			"pronouns", "roles", "userProfiles", "identifier", "email", "sms", "phone", "agents", "grades", "password",
			"resources", "primaryOrg");
	public static final Set<String> ENROLLMENT = fields("user", "class", "school", "role", "primary", "beginDate",
			"endDate");
	public static final Set<String> DEMOGRAPHICS = fields("birthDate", "sex", "americanIndianOrAlaskaNative", "asian",
			"blackOrAfricanAmerican", "nativeHawaiianOrOtherPacificIslander", "white", "demographicRaceTwoOrMoreRaces",
			"hispanicOrLatinoEthnicity", "countryOfBirthCode", "stateOfBirthAbbreviation", "cityOfBirth",
			"publicSchoolResidenceStatus");
	public static final Set<String> LINE_ITEM = fields("title", "description", "assignDate", "dueDate", "class",
			"school", "category", "gradingPeriod", "academicSession", "scoreScale", "resultValueMin", "resultValueMax",
			"learningObjectiveSet");
	public static final Set<String> RESULT = fields("lineItem", "student", "class", "scoreScale", "scoreStatus", "score",
			"textScore", "scoreDate", "comment", "learningObjectiveSet", "inProgress", "incomplete", "late", "missing");
	public static final Set<String> CATEGORY = fields("title", "weight");
	public static final Set<String> SCORE_SCALE = fields("title", "type", "class", "course", "scoreScaleValue");
	public static final Set<String> ASSESSMENT_LINE_ITEM = fields("title", "description", "class",
			"parentAssessmentLineItem", "scoreScale", "resultValueMin", "resultValueMax", "learningObjectiveSet");
	public static final Set<String> ASSESSMENT_RESULT = fields("assessmentLineItem", "student", "score", "textScore",
			"scoreDate", "scoreScale", "scoreStatus", "comment", "learningObjectiveSet", "inProgress", "incomplete",
			"late", "missing");
	public static final Set<String> RESOURCE = fields("title", "roles", "importance", "vendorResourceId", "vendorId",
			"applicationId");

	private static final DateTimeFormatter DATE_TIME =
			DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
	private static final DateTimeFormatter FLEXIBLE = new DateTimeFormatterBuilder()
			.append(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
			.optionalStart().appendOffset("+HH:MM", "Z").optionalEnd()
			.optionalStart().appendOffset("+HHMM", "Z").optionalEnd()
			.toFormatter();

	private static final Pattern SECONDARY = Pattern.compile("^(TERM|TLE|TALE|[1-6])(EME|E|ERE|RE|NDE|DE|ND)?\\b");
	private static final Pattern PRIMARY = Pattern.compile("\\b(TPS|PS|MS|GS|CP|CE1|CE2|CM1|CM2)\\b");

	private Entities() {
	}

	private static Set<String> fields(String... specific) {
		final Set<String> set = new HashSet<>(Arrays.asList(BASE));
		set.addAll(Arrays.asList(specific));
		return Collections.unmodifiableSet(set);
	}

	/** Squelette commun : sourcedId, status, dateLastModified. */
	public static JsonObject base(String sourcedId, String status, String dateLastModified) {
		return new JsonObject()
				.put("sourcedId", sourcedId)
				.put("status", status == null ? ACTIVE : status)
				.put("dateLastModified", dateTime(dateLastModified));
	}

	/**
	 * Date-heure au format OneRoster (YYYY-MM-DDTHH:MM:SS.sssZ, UTC). Accepte les formats rencontrés
	 * dans l'annuaire et PostgreSQL (décalage « +02:00 », « +0200 » ou « Z », fractions de 0 à 9
	 * chiffres, date seule). Sans date connue, l'heure courante : un client en synchronisation
	 * différentielle relira l'entité plutôt que de manquer une modification.
	 */
	public static String dateTime(String value) {
		if (value == null || value.trim().isEmpty()) {
			return nowIso();
		}
		final String v = value.trim().replace(' ', 'T');
		if (v.length() == 10) {
			return v + "T00:00:00.000Z";
		}
		try {
			return DATE_TIME.format(OffsetDateTime.parse(v, FLEXIBLE).toInstant());
		} catch (DateTimeParseException e) {
			try {
				return DATE_TIME.format(LocalDateTime.parse(v).toInstant(ZoneOffset.UTC));
			} catch (DateTimeParseException e2) {
				return v;
			}
		}
	}

	/** Date seule (YYYY-MM-DD) à partir d'une date ou d'une date-heure. */
	public static String date(String value) {
		if (value == null || value.length() < 10) {
			return null;
		}
		return value.substring(0, 10);
	}

	public static String nowIso() {
		return DATE_TIME.format(Instant.now());
	}

	/**
	 * Niveau français → code de niveau CEDS utilisé par OneRoster (01 à 12, KG, PK, PR, IT).
	 * Reconnaît « 6ème », « 5e », « 2nde », « 1ère », « Terminale », « CM2 », « GS »…
	 * Retourne null si le libellé ne contient pas de niveau reconnaissable.
	 */
	public static String grade(String label) {
		if (label == null) {
			return null;
		}
		final String n = Normalizer.normalize(label, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
				.toUpperCase(Locale.ROOT).trim();
		final Matcher p = PRIMARY.matcher(n);
		if (p.find()) {
			switch (p.group(1)) {
				case "TPS": return "IT";
				case "PS": return "PR";
				case "MS": return "PK";
				case "GS": return "KG";
				case "CP": return "01";
				case "CE1": return "02";
				case "CE2": return "03";
				case "CM1": return "04";
				default: return "05";
			}
		}
		if (n.startsWith("SECONDE")) {
			return "10";
		}
		if (n.startsWith("PREMIERE")) {
			return "11";
		}
		if (n.startsWith("TERMINALE")) {
			return "12";
		}
		final Matcher s = SECONDARY.matcher(n);
		if (s.find()) {
			final String level = s.group(1);
			final String suffix = s.group(2);
			if (level.startsWith("T")) {
				return "12";
			}
			if (suffix == null) {
				return null;
			}
			switch (level) {
				case "6": return "06";
				case "5": return "07";
				case "4": return "08";
				case "3": return "09";
				case "2": return "10";
				default: return "11";
			}
		}
		return null;
	}

	public static JsonArray grades(String... labels) {
		final JsonArray grades = new JsonArray();
		for (String label : labels) {
			final String g = grade(label);
			if (g != null && !grades.contains(g)) {
				grades.add(g);
			}
		}
		return grades;
	}
}
