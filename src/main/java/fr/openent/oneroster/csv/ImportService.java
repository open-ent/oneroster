package fr.openent.oneroster.csv;

import fr.openent.oneroster.OneRosterContext;
import fr.openent.oneroster.core.OneRosterException;
import fr.openent.oneroster.service.Db;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Import OneRoster → annuaire. Les données d'un établissement sont converties en fichiers CSV
 * de l'annuaire puis confiées au feeder (bus « entcore.feeder »), comme un import CSV de la
 * console d'administration : dédoublonnage, groupes de profil et rattachements restent ceux
 * de l'ENT. Le traitement est asynchrone ; son rapport est conservé dans oneroster.import.
 */
public class ImportService {

	private static final Logger log = LoggerFactory.getLogger(ImportService.class);
	private static final String FEEDER = "entcore.feeder";
	private static final long FEEDER_TIMEOUT = 30L * 60 * 1000;

	public static final String VALIDATE = "validate";
	public static final String IMPORT = "import";

	private final OneRosterContext ctx;
	private final String importPath;

	public ImportService(OneRosterContext ctx) {
		this.ctx = ctx;
		this.importPath = ctx.config.getJsonObject("import", new JsonObject())
				.getString("path", System.getProperty("java.io.tmpdir") + File.separator + "oneroster-import");
	}

	/**
	 * Lance l'import (ou sa seule validation) et retourne aussitôt son identifiant.
	 * @param admlStructures établissements administrés par l'auteur (null pour un super administrateur)
	 */
	public Future<String> start(String structureId, String sourceType, OneRosterSource source, String orgSourcedId,
			String mode, String userId, JsonArray admlStructures) {
		final String org;
		try {
			org = EntCsvConverter.chooseOrg(source, orgSourcedId);
		} catch (OneRosterException e) {
			return Future.failedFuture(e);
		}
		final Map<String, String> files = new EntCsvConverter(source, org).convert();
		if (files.isEmpty()) {
			return Future.failedFuture(new OneRosterException(422, "no.user.to.import"));
		}
		final String id = UUID.randomUUID().toString();
		final JsonObject report = new JsonObject().put("mode", mode).put("orgSourcedId", org)
				.put("counts", EntCsvConverter.counts(files));
		return Db.sql("INSERT INTO oneroster.import (id, structure_id, source, status, report, created_by) " +
				"VALUES (?, ?, ?, 'running', ?::jsonb, ?)",
				new JsonArray().add(id).add(structureId).add(sourceType).add(report.encode()).add(userId))
				.map(v -> {
					run(id, structureId, files, mode, admlStructures, report);
					return id;
				});
	}

	public Future<JsonArray> list(String structureId) {
		return Db.sql("SELECT id, structure_id, source, status, report, created_by, created_at, finished_at " +
				"FROM oneroster.import WHERE structure_id = ? ORDER BY created_at DESC LIMIT 50",
				new JsonArray().add(structureId));
	}

	private void run(String id, String structureId, Map<String, String> files, String mode, JsonArray admlStructures,
			JsonObject report) {
		structure(structureId)
				.compose(structure -> write(id, structure, files).compose(path -> feeder(path, structure, mode, admlStructures)
						.onComplete(ar -> ctx.vertx.fileSystem().deleteRecursive(path, true, d -> { }))))
				.onSuccess(result -> {
					final JsonObject errors = result.getJsonObject("errors", new JsonObject());
					report.put("feeder", result);
					finish(id, errors.isEmpty() ? (IMPORT.equals(mode) ? "imported" : "validated") : "failed", report);
					if (IMPORT.equals(mode) && errors.isEmpty()) {
						ctx.roster.invalidate();
					}
				})
				.onFailure(e -> {
					log.error("[OneRoster] Import " + id + " failed", e);
					finish(id, "failed", report.put("error", e.getMessage()));
				});
	}

	private Future<JsonObject> structure(String structureId) {
		return Db.neo4j("MATCH (s:Structure {id: $id}) RETURN s.id AS id, s.name AS name, " +
				"s.externalId AS externalId, s.UAI AS uai", new JsonObject().put("id", structureId))
				.compose(rows -> rows.isEmpty() ? Future.failedFuture(new OneRosterException(404, "structure.not.found"))
						: Future.succeededFuture(rows.getJsonObject(0)));
	}

	/**
	 * Écrit les fichiers dans un dossier temporaire puis les range dans le stockage, sous
	 * {@code <import-path>/<id>/<nom>@<externalId>_<UAI>/}, disposition attendue par le feeder.
	 */
	private Future<String> write(String id, JsonObject structure, Map<String, String> files) {
		final String base = importPath + File.separator + id;
		final String tmp = importPath + File.separator + "tmp-" + id;
		final String dirName = structure.getString("name").replace("/", "-").replace("@", "-").replace("_", "-") + "@"
				+ structure.getString("externalId") + (structure.getString("uai") != null ? "_" + structure.getString("uai") : "");
		Future<Void> chain = ctx.vertx.fileSystem().mkdirs(tmp);
		for (Map.Entry<String, String> f : files.entrySet()) {
			chain = chain.compose(v -> ctx.vertx.fileSystem().writeFile(tmp + File.separator + f.getKey(),
					Buffer.buffer(f.getValue(), "UTF-8")));
		}
		return chain.compose(v -> ctx.vertx.fileSystem().mkdirs(base))
				.compose(v -> ctx.storage.moveFsDirectory(tmp, base + File.separator + dirName))
				.map(v -> base);
	}

	private Future<JsonObject> feeder(String path, JsonObject structure, String mode, JsonArray admlStructures) {
		final JsonObject message = new JsonObject()
				.put("action", IMPORT.equals(mode) ? "import" : "validate")
				.put("feeder", "CSV")
				.put("type", "CSV")
				.put("path", path)
				.put("structureId", structure.getString("id"))
				.put("structureExternalId", structure.getString("externalId"))
				.put("structureName", structure.getString("name"))
				.put("UAI", structure.getString("uai"))
				.put("language", "fr")
				.put("preDelete", false);
		if (admlStructures != null) {
			message.put("adml-structures", admlStructures);
		}
		return ctx.eb.<JsonObject>request(FEEDER, message, new DeliveryOptions().setSendTimeout(FEEDER_TIMEOUT))
				.compose(reply -> {
					final JsonObject body = reply.body();
					if (!"ok".equals(body.getString("status"))) {
						return Future.failedFuture(new OneRosterException(502, body.getString("message", "feeder.error")));
					}
					return Future.succeededFuture(body.getJsonObject("result", new JsonObject()));
				});
	}

	private void finish(String id, String status, JsonObject report) {
		Db.sql("UPDATE oneroster.import SET status = ?, report = ?::jsonb, finished_at = NOW() WHERE id = ?",
				new JsonArray().add(status).add(report.encode()).add(id))
				.onFailure(e -> log.error("[OneRoster] Unable to save import report " + id, e));
	}

	public static List<String> strings(JsonArray array) {
		return fr.openent.oneroster.security.Client.strings(array);
	}
}
