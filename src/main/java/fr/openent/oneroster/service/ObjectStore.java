package fr.openent.oneroster.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Objets OneRoster sans équivalent dans l'ENT (catégories propres au client, barèmes, évaluations
 * hors classe) : conservés tels que reçus et visibles du seul client qui les a déposés.
 */
public class ObjectStore {

	public Future<List<JsonObject>> list(String clientId, String kind) {
		return Db.sql("SELECT data FROM oneroster.object WHERE client_id = ? AND kind = ? ORDER BY sourced_id",
				new JsonArray().add(clientId).add(kind)).map(ObjectStore::objects);
	}

	public Future<JsonObject> get(String clientId, String kind, String sourcedId) {
		return Db.sqlOne("SELECT data FROM oneroster.object WHERE client_id = ? AND kind = ? AND sourced_id = ?",
				new JsonArray().add(clientId).add(kind).add(sourcedId))
				.map(row -> row == null ? null : Db.jsonObject(row, "data"));
	}

	/** Crée ou remplace ; retourne vrai si l'objet a été créé. */
	public Future<Boolean> put(String clientId, String kind, String sourcedId, JsonObject data) {
		return Db.sqlOne("INSERT INTO oneroster.object (client_id, kind, sourced_id, data) VALUES (?, ?, ?, ?::jsonb) " +
				"ON CONFLICT (client_id, kind, sourced_id) DO UPDATE SET data = EXCLUDED.data, updated_at = NOW() " +
				"RETURNING (xmax = 0) AS created",
				new JsonArray().add(clientId).add(kind).add(sourcedId).add(data.encode()))
				.map(row -> row != null && Boolean.TRUE.equals(row.getBoolean("created")));
	}

	public Future<Boolean> delete(String clientId, String kind, String sourcedId) {
		return Db.sql("DELETE FROM oneroster.object WHERE client_id = ? AND kind = ? AND sourced_id = ? RETURNING sourced_id",
				new JsonArray().add(clientId).add(kind).add(sourcedId)).map(rows -> !rows.isEmpty());
	}

	private static List<JsonObject> objects(JsonArray rows) {
		final List<JsonObject> list = new ArrayList<>();
		for (Object o : rows) {
			final JsonObject data = Db.jsonObject((JsonObject) o, "data");
			if (data != null) {
				list.add(data);
			}
		}
		return list;
	}
}
