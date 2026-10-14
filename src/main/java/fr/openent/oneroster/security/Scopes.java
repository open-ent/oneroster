package fr.openent.oneroster.security;

import io.vertx.core.json.JsonArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Scopes OAuth 2.0 définis par OneRoster 1.2 (Rostering, Gradebook, Resources). */
public final class Scopes {

	private static final String PREFIX = "https://purl.imsglobal.org/spec/or/v1p2/scope/";

	public static final String ROSTER_CORE_READONLY = PREFIX + "roster-core.readonly";
	public static final String ROSTER_READONLY = PREFIX + "roster.readonly";
	public static final String ROSTER_DEMOGRAPHICS_READONLY = PREFIX + "roster-demographics.readonly";

	public static final String GRADEBOOK_CORE_READONLY = PREFIX + "gradebook-core.readonly";
	public static final String GRADEBOOK_READONLY = PREFIX + "gradebook.readonly";
	public static final String GRADEBOOK_CREATEPUT = PREFIX + "gradebook.createput";
	public static final String GRADEBOOK_CREATEPOST = PREFIX + "gradebook.createpost";
	public static final String GRADEBOOK_DELETE = PREFIX + "gradebook.delete";

	public static final String ASSESSMENT_READONLY = PREFIX + "assessment.readonly";
	public static final String ASSESSMENT_CREATEPUT = PREFIX + "assessment.createput";
	public static final String ASSESSMENT_DELETE = PREFIX + "assessment.delete";

	public static final String RESOURCE_CORE_READONLY = PREFIX + "resource-core.readonly";
	public static final String RESOURCE_READONLY = PREFIX + "resource.readonly";

	public static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(
			ROSTER_CORE_READONLY, ROSTER_READONLY, ROSTER_DEMOGRAPHICS_READONLY,
			GRADEBOOK_CORE_READONLY, GRADEBOOK_READONLY, GRADEBOOK_CREATEPUT, GRADEBOOK_CREATEPOST, GRADEBOOK_DELETE,
			ASSESSMENT_READONLY, ASSESSMENT_CREATEPUT, ASSESSMENT_DELETE,
			RESOURCE_CORE_READONLY, RESOURCE_READONLY));

	private Scopes() {
	}

	public static List<String> split(String scope) {
		final List<String> scopes = new ArrayList<>();
		if (scope != null) {
			for (String s : scope.trim().split("\\s+")) {
				if (!s.isEmpty() && !scopes.contains(s)) {
					scopes.add(s);
				}
			}
		}
		return scopes;
	}

	/**
	 * Scopes accordés : ceux demandés et autorisés pour le client. Sans scope demandé,
	 * le client reçoit tous ses scopes autorisés (RFC 6749 §3.3 laisse ce choix au serveur).
	 */
	public static List<String> grant(String requested, JsonArray allowed) {
		final List<String> granted = new ArrayList<>();
		final List<String> wanted = split(requested);
		if (allowed == null) {
			return granted;
		}
		if (wanted.isEmpty()) {
			for (Object o : allowed) {
				if (o instanceof String && ALL.contains(o) && !granted.contains(o)) {
					granted.add((String) o);
				}
			}
			return granted;
		}
		for (String s : wanted) {
			if (allowed.contains(s) && !granted.contains(s)) {
				granted.add(s);
			}
		}
		return granted;
	}
}
