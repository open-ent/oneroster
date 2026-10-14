package fr.openent.oneroster.core;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** Références (GUIDRef) entre entités : {@code {"href", "sourcedId", "type"}}. */
public final class Refs {

	public static final String ROSTERING = "/ims/oneroster/rostering/v1p2";
	public static final String GRADEBOOK = "/ims/oneroster/gradebook/v1p2";
	public static final String RESOURCES = "/ims/oneroster/resources/v1p2";

	private static final Map<String, String> PATHS = new HashMap<>();

	static {
		PATHS.put("org", ROSTERING + "/orgs/");
		PATHS.put("academicSession", ROSTERING + "/academicSessions/");
		PATHS.put("gradingPeriod", ROSTERING + "/gradingPeriods/");
		PATHS.put("term", ROSTERING + "/terms/");
		PATHS.put("course", ROSTERING + "/courses/");
		PATHS.put("class", ROSTERING + "/classes/");
		PATHS.put("user", ROSTERING + "/users/");
		PATHS.put("student", ROSTERING + "/students/");
		PATHS.put("teacher", ROSTERING + "/teachers/");
		PATHS.put("enrollment", ROSTERING + "/enrollments/");
		PATHS.put("demographics", ROSTERING + "/demographics/");
		PATHS.put("lineItem", GRADEBOOK + "/lineItems/");
		PATHS.put("result", GRADEBOOK + "/results/");
		PATHS.put("category", GRADEBOOK + "/categories/");
		PATHS.put("scoreScale", GRADEBOOK + "/scoreScales/");
		PATHS.put("assessmentLineItem", GRADEBOOK + "/assessmentLineItems/");
		PATHS.put("assessmentResult", GRADEBOOK + "/assessmentResults/");
		PATHS.put("resource", RESOURCES + "/resources/");
	}

	private final String publicBase;

	/** @param publicBase origine publique de l'ENT, ex. https://ent.example.org */
	public Refs(String publicBase) {
		this.publicBase = publicBase;
	}

	public String getPublicBase() {
		return publicBase;
	}

	public JsonObject ref(String type, String sourcedId) {
		if (sourcedId == null) {
			return null;
		}
		final String path = PATHS.get(type);
		return new JsonObject()
				.put("href", publicBase + (path != null ? path : "/") + sourcedId)
				.put("sourcedId", sourcedId)
				.put("type", type);
	}

	public JsonArray refs(String type, Collection<String> sourcedIds) {
		final JsonArray array = new JsonArray();
		if (sourcedIds != null) {
			for (String id : sourcedIds) {
				if (id != null) {
					array.add(ref(type, id));
				}
			}
		}
		return array;
	}

	/** sourcedId d'une référence reçue dans un corps de requête (objet GUIDRef ou chaîne). */
	public static String sourcedId(Object ref) {
		if (ref instanceof JsonObject) {
			return ((JsonObject) ref).getString("sourcedId");
		}
		return ref instanceof String ? (String) ref : null;
	}
}
