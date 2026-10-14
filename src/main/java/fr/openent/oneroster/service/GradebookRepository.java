package fr.openent.oneroster.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Devoirs, notes et types d'évaluation du module Compétences (schéma notes), avec les
 * identifiants fournis par les clients OneRoster (oneroster.sourced_id_map).
 */
public class GradebookRepository {

	public static final String LINE_ITEM = "lineItem";
	public static final String RESULT = "result";

	private static final String GROUP_OF_DEVOIR =
			"(SELECT r.id_groupe FROM notes.rel_devoirs_groupes r WHERE r.id_devoir = d.id ORDER BY r.id_groupe LIMIT 1)";

	private static final String LINE_ITEM_SELECT =
			"SELECT d.id, d.name, d.libelle, d.owner, d.id_matiere AS \"subjectId\", d.id_periode AS \"periodTypeId\", " +
			"d.id_type AS \"typeId\", d.id_etablissement AS \"structureId\", d.diviseur, d.date, " +
			"d.date_publication AS \"publicationDate\", d.modified, " + GROUP_OF_DEVOIR + " AS \"groupId\", " +
			"m.sourced_id AS \"suppliedId\", m.client_id AS \"clientId\", m.data AS \"mapData\" " +
			"FROM notes.devoirs d " +
			"LEFT JOIN oneroster.sourced_id_map m ON m.kind = 'lineItem' AND m.local_id = d.id::text ";

	private static final String RESULT_SELECT =
			"SELECT n.id, n.id_eleve AS \"studentId\", n.id_devoir AS \"lineItemId\", n.valeur, n.modified, n.created, " +
			"d.id_matiere AS \"subjectId\", d.id_etablissement AS \"structureId\", " + GROUP_OF_DEVOIR + " AS \"groupId\", " +
			"(SELECT a.valeur FROM notes.appreciations a WHERE a.id_devoir = n.id_devoir AND a.id_eleve = n.id_eleve " +
			"ORDER BY a.id DESC LIMIT 1) AS comment, " +
			"ml.sourced_id AS \"lineItemSuppliedId\", mr.sourced_id AS \"suppliedId\", mr.client_id AS \"clientId\", " +
			"mr.data AS \"mapData\" " +
			"FROM notes.notes n JOIN notes.devoirs d ON d.id = n.id_devoir " +
			"LEFT JOIN oneroster.sourced_id_map ml ON ml.kind = 'lineItem' AND ml.local_id = d.id::text " +
			"LEFT JOIN oneroster.sourced_id_map mr ON mr.kind = 'result' AND mr.local_id = n.id::text ";

	public Future<JsonArray> lineItems(List<String> structures) {
		return Db.sqlOptional(LINE_ITEM_SELECT + "WHERE d.id_etablissement IN (" + Db.placeholders(structures.size()) +
				") ORDER BY d.id", strings(structures));
	}

	public Future<JsonObject> lineItem(long id) {
		return Db.sqlOne(LINE_ITEM_SELECT + "WHERE d.id = ?", new JsonArray().add(id));
	}

	public Future<JsonArray> results(List<String> structures) {
		return Db.sqlOptional(RESULT_SELECT + "WHERE d.id_etablissement IN (" + Db.placeholders(structures.size()) +
				") ORDER BY n.id", strings(structures));
	}

	public Future<JsonObject> result(long id) {
		return Db.sqlOne(RESULT_SELECT + "WHERE n.id = ?", new JsonArray().add(id));
	}

	public Future<JsonArray> categories(List<String> structures) {
		return Db.sqlOptional("SELECT id, nom, id_etablissement AS \"structureId\" FROM notes.type " +
				"WHERE id_etablissement IN (" + Db.placeholders(structures.size()) + ") ORDER BY id", strings(structures));
	}

	/** Type d'évaluation par défaut de l'établissement (sinon le premier). */
	public Future<JsonObject> defaultCategory(String structureId) {
		return Db.sqlOne("SELECT id FROM notes.type WHERE id_etablissement = ? " +
				"ORDER BY default_type DESC NULLS LAST, id LIMIT 1", new JsonArray().add(structureId));
	}

	public Future<JsonObject> mapping(String kind, String sourcedId) {
		return Db.sqlOne("SELECT kind, sourced_id, client_id, local_id, data FROM oneroster.sourced_id_map " +
				"WHERE kind = ? AND sourced_id = ?", new JsonArray().add(kind).add(sourcedId));
	}

	public Future<JsonObject> mappingOfLocal(String kind, String localId) {
		return Db.sqlOne("SELECT kind, sourced_id, client_id, local_id, data FROM oneroster.sourced_id_map " +
				"WHERE kind = ? AND local_id = ?", new JsonArray().add(kind).add(localId));
	}

	/** Prochain identifiant de devoir (comme le module Compétences, pour lier groupe et devoir dans la même transaction). */
	public Future<Long> nextLineItemId() {
		return Db.sqlOne("SELECT nextval('notes.devoirs_id_seq') AS id", new JsonArray()).map(row -> row.getLong("id"));
	}

	public Future<JsonArray> createLineItem(long id, JsonObject devoir, String groupId, int groupType,
			String ownerName, String clientId, String sourcedId, JsonObject data) {
		final JsonArray statements = new JsonArray()
				.add(Db.statement("SELECT notes.merge_users(?, ?)",
						new JsonArray().add(devoir.getString("owner")).add(ownerName)))
				.add(Db.statement("INSERT INTO notes.devoirs (id, name, owner, libelle, id_periode, id_type, " +
						"id_etablissement, diviseur, id_matiere, ramener_sur, date_publication, date, is_evaluated, " +
						"id_etat, coefficient) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, false, ?::date, ?::date, true, 1, 1)",
						new JsonArray().add(id).add(devoir.getString("name")).add(devoir.getString("owner"))
								.add(devoir.getString("libelle")).add(devoir.getLong("id_periode"))
								.add(devoir.getLong("id_type")).add(devoir.getString("id_etablissement"))
								.add(devoir.getNumber("diviseur")).add(devoir.getString("id_matiere"))
								.add(devoir.getString("date_publication")).add(devoir.getString("date"))))
				.add(Db.statement("INSERT INTO notes.rel_devoirs_groupes (id_groupe, id_devoir, type_groupe) VALUES (?, ?, ?)",
						new JsonArray().add(groupId).add(id).add(groupType)))
				.add(mapInsert(LINE_ITEM, sourcedId, clientId, String.valueOf(id), data));
		return Db.transaction(statements);
	}

	public Future<JsonArray> updateLineItem(long id, JsonObject devoir, String sourcedId, JsonObject data) {
		final JsonArray statements = new JsonArray()
				.add(Db.statement("UPDATE notes.devoirs SET name = ?, libelle = ?, id_periode = ?, id_type = ?, " +
						"diviseur = ?, date_publication = ?::date, date = ?::date, modified = NOW() WHERE id = ?",
						new JsonArray().add(devoir.getString("name")).add(devoir.getString("libelle"))
								.add(devoir.getLong("id_periode")).add(devoir.getLong("id_type"))
								.add(devoir.getNumber("diviseur")).add(devoir.getString("date_publication"))
								.add(devoir.getString("date")).add(id)))
				.add(mapUpdate(LINE_ITEM, sourcedId, data));
		return Db.transaction(statements);
	}

	public Future<JsonArray> deleteLineItem(long id, String sourcedId) {
		return Db.transaction(new JsonArray()
				.add(Db.statement("DELETE FROM oneroster.sourced_id_map WHERE kind = 'result' AND local_id IN " +
						"(SELECT id::text FROM notes.notes WHERE id_devoir = ?)", new JsonArray().add(id)))
				.add(Db.statement("DELETE FROM notes.devoirs WHERE id = ?", new JsonArray().add(id)))
				.add(Db.statement("DELETE FROM oneroster.sourced_id_map WHERE kind = ? AND sourced_id = ?",
						new JsonArray().add(LINE_ITEM).add(sourcedId))));
	}

	public Future<JsonObject> noteOf(long lineItemId, String studentId) {
		return Db.sqlOne("SELECT id FROM notes.notes WHERE id_devoir = ? AND id_eleve = ? ORDER BY id LIMIT 1",
				new JsonArray().add(lineItemId).add(studentId));
	}

	/**
	 * Enregistre la note (et l'appréciation si fournie) puis la correspondance du sourcedId.
	 * @param noteId note existante à mettre à jour, ou null pour en créer une
	 */
	public Future<Long> saveResult(Long noteId, long lineItemId, String studentId, Number score, String owner,
			String comment, String clientId, String sourcedId, JsonObject data, boolean mappingExists) {
		final Future<Long> idFuture = noteId != null ? Future.succeededFuture(noteId)
				: Db.sqlOne("SELECT nextval('notes.notes_id_seq') AS id", new JsonArray()).map(r -> r.getLong("id"));
		return idFuture.compose(id -> {
			final JsonArray statements = new JsonArray();
			if (noteId != null) {
				statements.add(Db.statement("UPDATE notes.notes SET valeur = ?, modified = NOW() WHERE id = ?",
						new JsonArray().add(score).add(id)));
			} else {
				statements.add(Db.statement("INSERT INTO notes.notes (id, id_eleve, id_devoir, valeur, owner, created, " +
						"modified) VALUES (?, ?, ?, ?, ?, NOW(), NOW())",
						new JsonArray().add(id).add(studentId).add(lineItemId).add(score).add(owner)));
			}
			addComment(statements, lineItemId, studentId, owner, comment);
			statements.add(mappingExists ? mapUpdate(RESULT, sourcedId, data)
					: mapInsert(RESULT, sourcedId, clientId, String.valueOf(id), data));
			return Db.transaction(statements).map(r -> id);
		});
	}

	/** Statut « exempt » ou « not submitted » sans note : la note éventuelle est retirée. */
	public Future<JsonArray> clearResult(Long noteId, long lineItemId, String studentId, String owner, String comment) {
		final JsonArray statements = new JsonArray();
		if (noteId != null) {
			statements.add(Db.statement("DELETE FROM oneroster.sourced_id_map WHERE kind = 'result' AND local_id = ?",
					new JsonArray().add(String.valueOf(noteId))));
			statements.add(Db.statement("DELETE FROM notes.notes WHERE id = ?", new JsonArray().add(noteId)));
		}
		addComment(statements, lineItemId, studentId, owner, comment);
		return statements.isEmpty() ? Future.succeededFuture(new JsonArray()) : Db.transaction(statements);
	}

	public Future<JsonArray> deleteResult(long noteId, long lineItemId, String studentId, String sourcedId) {
		return Db.transaction(new JsonArray()
				.add(Db.statement("DELETE FROM notes.notes WHERE id = ?", new JsonArray().add(noteId)))
				.add(Db.statement("DELETE FROM notes.appreciations WHERE id_devoir = ? AND id_eleve = ?",
						new JsonArray().add(lineItemId).add(studentId)))
				.add(Db.statement("DELETE FROM oneroster.sourced_id_map WHERE kind = ? AND sourced_id = ?",
						new JsonArray().add(RESULT).add(sourcedId))));
	}

	/** Saisie close : la date de fin de saisie de la période est dépassée pour cette classe. */
	public Future<Boolean> gradingClosed(String classId, long periodTypeId) {
		return Db.sqlOptional("SELECT 1 FROM viesco.periode WHERE id_classe = ? AND id_type = ? " +
				"AND date_fin_saisie IS NOT NULL AND date_fin_saisie < NOW()",
				new JsonArray().add(classId).add(periodTypeId)).map(rows -> !rows.isEmpty());
	}

	private static void addComment(JsonArray statements, long lineItemId, String studentId, String owner, String comment) {
		if (comment == null) {
			return;
		}
		statements.add(Db.statement("DELETE FROM notes.appreciations WHERE id_devoir = ? AND id_eleve = ?",
				new JsonArray().add(lineItemId).add(studentId)));
		if (!comment.trim().isEmpty()) {
			statements.add(Db.statement("INSERT INTO notes.appreciations (id_eleve, id_devoir, valeur, owner, created, " +
					"modified) VALUES (?, ?, ?, ?, NOW(), NOW())",
					new JsonArray().add(studentId).add(lineItemId).add(comment).add(owner)));
		}
	}

	private static JsonObject mapInsert(String kind, String sourcedId, String clientId, String localId, JsonObject data) {
		return Db.statement("INSERT INTO oneroster.sourced_id_map (kind, sourced_id, client_id, local_id, data) " +
				"VALUES (?, ?, ?, ?, ?::jsonb)",
				new JsonArray().add(kind).add(sourcedId).add(clientId).add(localId).add(data.encode()));
	}

	private static JsonObject mapUpdate(String kind, String sourcedId, JsonObject data) {
		return Db.statement("UPDATE oneroster.sourced_id_map SET data = ?::jsonb, updated_at = NOW() " +
				"WHERE kind = ? AND sourced_id = ?", new JsonArray().add(data.encode()).add(kind).add(sourcedId));
	}

	private static JsonArray strings(List<String> values) {
		return new JsonArray(new ArrayList<Object>(values));
	}
}
