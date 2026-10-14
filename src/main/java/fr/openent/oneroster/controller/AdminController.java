package fr.openent.oneroster.controller;

import fr.openent.oneroster.OneRosterContext;
import fr.openent.oneroster.api.Http;
import fr.openent.oneroster.core.OneRosterException;
import fr.openent.oneroster.core.Refs;
import fr.openent.oneroster.csv.CsvExport;
import fr.openent.oneroster.csv.ImportService;
import fr.openent.oneroster.csv.OneRosterSource;
import fr.openent.oneroster.csv.RemoteProvider;
import fr.openent.oneroster.security.Client;
import fr.openent.oneroster.security.Scopes;
import fr.openent.oneroster.security.Secrets;
import fr.wseduc.rs.Delete;
import fr.wseduc.rs.Get;
import fr.wseduc.rs.Post;
import fr.wseduc.rs.Put;
import fr.wseduc.security.ActionType;
import fr.wseduc.security.SecuredAction;
import fr.wseduc.webutils.http.BaseController;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.entcore.common.http.filter.AdmlOfStructure;
import org.entcore.common.http.filter.ResourceFilter;
import org.entcore.common.http.filter.SuperAdminFilter;
import org.entcore.common.user.DefaultFunctions;
import org.entcore.common.user.UserInfos;
import org.entcore.common.user.UserUtils;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Administration : clients OAuth de l'API (super administrateur), export CSV et imports
 * OneRoster d'un établissement (administrateur local de l'établissement).
 */
public class AdminController extends BaseController {

	private static final int MAX_UPLOAD = 64 * 1024 * 1024;

	private final OneRosterContext ctx;
	private final ImportService imports;
	private final RemoteProvider remote;

	public AdminController(OneRosterContext ctx) {
		this.ctx = ctx;
		this.imports = new ImportService(ctx);
		this.remote = new RemoteProvider(ctx.vertx, ImportService.strings(
				ctx.config.getJsonObject("import", new JsonObject()).getJsonArray("remote-providers", new JsonArray())));
	}

	// ================================================================= configuration

	/** Adresses à communiquer à un éditeur pour brancher son outil. */
	@Get("/admin/configuration")
	@ResourceFilter(SuperAdminFilter.class)
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	public void configuration(HttpServerRequest request) {
		renderJson(request, new JsonObject()
				.put("token_endpoint", ctx.tokenEndpoint())
				.put("rostering", ctx.publicBase + Refs.ROSTERING)
				.put("gradebook", ctx.publicBase + Refs.GRADEBOOK)
				.put("resources", ctx.publicBase + Refs.RESOURCES)
				.put("scopes", new JsonArray(new java.util.ArrayList<Object>(Scopes.ALL)))
				.put("remote_import_enabled", !ImportService.strings(ctx.config.getJsonObject("import", new JsonObject())
						.getJsonArray("remote-providers", new JsonArray())).isEmpty()));
	}

	// ================================================================= clients OAuth

	@Get("/admin/clients")
	@ResourceFilter(SuperAdminFilter.class)
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	public void listClients(HttpServerRequest request) {
		ctx.clients.list().onSuccess(clients -> renderJson(request, clients)).onFailure(e -> Http.error(request, e));
	}

	/** Création : le secret n'est renvoyé qu'une fois, dans cette réponse. */
	@Post("/admin/clients")
	@ResourceFilter(SuperAdminFilter.class)
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	public void createClient(HttpServerRequest request) {
		request.pause();
		UserUtils.getUserInfos(eb, request, user -> Http.json(request).compose(AdminController::validate).compose(client -> {
			final String id = UUID.randomUUID().toString();
			final String secret = Secrets.random(32);
			return ctx.clients.create(id, client, Secrets.hash(secret), user != null ? user.getUserId() : null)
					.map(created -> new JsonObject().put("client", created).put("client_secret", secret));
		}).onSuccess(result -> renderJson(request, result, 201)).onFailure(e -> Http.error(request, e)));
	}

	@Put("/admin/clients/:id")
	@ResourceFilter(SuperAdminFilter.class)
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	public void updateClient(HttpServerRequest request) {
		final String id = request.params().get("id");
		Http.json(request).compose(AdminController::validate).compose(client -> ctx.clients.update(id, client))
				.compose(client -> client == null ? Future.failedFuture(OneRosterException.notFound("client"))
						: Future.succeededFuture(client))
				.onSuccess(client -> renderJson(request, client))
				.onFailure(e -> Http.error(request, e));
	}

	@Post("/admin/clients/:id/secret")
	@ResourceFilter(SuperAdminFilter.class)
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	public void rotateSecret(HttpServerRequest request) {
		final String secret = Secrets.random(32);
		ctx.clients.rotateSecret(request.params().get("id"), Secrets.hash(secret))
				.onSuccess(row -> {
					if (row == null) {
						Http.error(request, OneRosterException.notFound("client"));
					} else {
						renderJson(request, new JsonObject().put("client_id", row.getString("id")).put("client_secret", secret));
					}
				})
				.onFailure(e -> Http.error(request, e));
	}

	@Delete("/admin/clients/:id")
	@ResourceFilter(SuperAdminFilter.class)
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	public void deleteClient(HttpServerRequest request) {
		ctx.clients.delete(request.params().get("id"))
				.onSuccess(rows -> {
					if (rows.isEmpty()) {
						Http.error(request, OneRosterException.notFound("client"));
					} else {
						Http.noContent(request);
					}
				})
				.onFailure(e -> Http.error(request, e));
	}

	/** Contrôle d'un client saisi dans l'administration. */
	static Future<JsonObject> validate(JsonObject body) {
		final String name = body.getString("name");
		if (name == null || name.trim().isEmpty() || name.length() > 255) {
			return Future.failedFuture(new OneRosterException(422, "client.name.required"));
		}
		final List<String> structures = Client.strings(body.getJsonArray("structures"));
		if (structures.isEmpty()) {
			return Future.failedFuture(new OneRosterException(422, "client.structures.required"));
		}
		final List<String> scopes = Client.strings(body.getJsonArray("scopes"));
		if (scopes.isEmpty() || !Scopes.ALL.containsAll(scopes)) {
			return Future.failedFuture(new OneRosterException(422, "client.scopes.invalid"));
		}
		final String owner = body.getString("default_owner");
		return Future.succeededFuture(new JsonObject()
				.put("name", name.trim())
				.put("structures", new JsonArray(new java.util.ArrayList<Object>(structures)))
				.put("scopes", new JsonArray(new java.util.ArrayList<Object>(scopes)))
				.put("default_owner", owner == null || owner.trim().isEmpty() ? null : owner.trim())
				.put("enabled", body.getBoolean("enabled", true)));
	}

	// ================================================================= export CSV

	/**
	 * Archive CSV OneRoster 1.2.1 de l'établissement. Paramètre include : liste parmi rostering,
	 * gradebook, resources (tout par défaut).
	 */
	@Get("/admin/structures/:structureId/export")
	@ResourceFilter(AdmlOfStructure.class)
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	public void export(HttpServerRequest request) {
		final String structureId = request.params().get("structureId");
		final String include = request.params().get("include");
		final List<String> parts = include == null || include.trim().isEmpty()
				? java.util.Arrays.asList("rostering", "gradebook", "resources")
				: java.util.Arrays.asList(include.split(","));
		UserUtils.getUserInfos(eb, request, user -> {
			final Client client = Client.admin(user != null ? user.getUserId() : "unknown",
					Collections.singletonList(structureId));
			final CsvExport export = new CsvExport();
			ctx.roster.dataset(client.getStructures()).compose(ds -> {
				Future<Void> chain = Future.succeededFuture();
				if (parts.contains("rostering")) {
					export.roster(ds);
				}
				if (parts.contains("gradebook")) {
					chain = chain.compose(v -> ctx.gradebook.lineItems(client).compose(items ->
							ctx.gradebook.results(client).compose(results -> ctx.gradebook.categories(client)
									.map(categories -> {
										export.gradebook(items, results, categories);
										return (Void) null;
									}))));
				}
				if (parts.contains("resources")) {
					chain = chain.compose(v -> ctx.resources.linked(client).map(linked -> {
						export.resources(ds, linked);
						return (Void) null;
					}));
				}
				return chain.compose(v -> {
					try {
						return Future.succeededFuture(export.zip("Open ENT"));
					} catch (java.io.IOException e) {
						return Future.failedFuture(e);
					}
				});
			}).onSuccess(zip -> request.response()
					.putHeader("Content-Type", "application/zip")
					.putHeader("Content-Disposition", "attachment; filename=\"oneroster-" + LocalDate.now() + ".zip\"")
					.putHeader("Cache-Control", "no-store")
					.end(Buffer.buffer(zip)))
					.onFailure(e -> Http.error(request, e));
		});
	}

	// ================================================================= imports

	/**
	 * Import d'une archive CSV OneRoster (multipart, champ « file »). Paramètres : mode
	 * (validate par défaut, ou import) et orgSourcedId (si l'archive contient plusieurs écoles).
	 */
	@Post("/admin/structures/:structureId/import")
	@ResourceFilter(AdmlOfStructure.class)
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	public void importCsv(HttpServerRequest request) {
		final String structureId = request.params().get("structureId");
		final String mode = mode(request.params().get("mode"));
		request.pause();
		UserUtils.getUserInfos(eb, request, user -> upload(request).compose(zip -> {
			try {
				return Future.succeededFuture(OneRosterSource.fromZip(zip));
			} catch (OneRosterException e) {
				return Future.<OneRosterSource>failedFuture(e);
			}
		}).compose(source -> imports.start(structureId, "csv", source, request.params().get("orgSourcedId"), mode,
				user != null ? user.getUserId() : null, admlStructures(user)))
				.onSuccess(id -> renderJson(request, new JsonObject().put("id", id), 202))
				.onFailure(e -> Http.error(request, e)));
	}

	/**
	 * Import depuis un fournisseur OneRoster REST autorisé en configuration. Corps :
	 * {baseUrl, tokenUrl, clientId, clientSecret, orgSourcedId?, mode?}. Les identifiants ne
	 * sont pas conservés.
	 */
	@Post("/admin/structures/:structureId/import/remote")
	@ResourceFilter(AdmlOfStructure.class)
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	public void importRemote(HttpServerRequest request) {
		final String structureId = request.params().get("structureId");
		request.pause();
		UserUtils.getUserInfos(eb, request, user -> Http.json(request).compose(body -> {
			if (!remote.allowed(body.getString("baseUrl")) || !remote.allowed(body.getString("tokenUrl"))) {
				return Future.<String>failedFuture(new OneRosterException(403, "remote.provider.not.allowed"));
			}
			return remote.fetch(body.getString("baseUrl"), body.getString("tokenUrl"), body.getString("clientId"),
					body.getString("clientSecret"))
					.compose(source -> imports.start(structureId, "rest", source, body.getString("orgSourcedId"),
							mode(body.getString("mode")), user != null ? user.getUserId() : null, admlStructures(user)));
		}).onSuccess(id -> renderJson(request, new JsonObject().put("id", id), 202))
				.onFailure(e -> Http.error(request, e)));
	}

	@Get("/admin/structures/:structureId/imports")
	@ResourceFilter(AdmlOfStructure.class)
	@SecuredAction(value = "", type = ActionType.RESOURCE)
	public void listImports(HttpServerRequest request) {
		imports.list(request.params().get("structureId"))
				.onSuccess(list -> renderJson(request, list))
				.onFailure(e -> Http.error(request, e));
	}

	private static String mode(String mode) {
		return ImportService.IMPORT.equals(mode) ? ImportService.IMPORT : ImportService.VALIDATE;
	}

	/** Établissements administrés (contrôle du feeder sur les comptes déjà présents ailleurs) ; null pour un ADMC. */
	private static JsonArray admlStructures(UserInfos user) {
		if (user == null || user.isSuperAdmin() || user.getFunctions() == null) {
			return null;
		}
		final UserInfos.Function adml = user.getFunctions().get(DefaultFunctions.ADMIN_LOCAL);
		return adml != null && adml.getScope() != null ? new JsonArray(new java.util.ArrayList<Object>(adml.getScope()))
				: new JsonArray();
	}

	private Future<byte[]> upload(HttpServerRequest request) {
		final Promise<byte[]> promise = Promise.promise();
		request.setExpectMultipart(true);
		request.uploadHandler(upload -> {
			final Buffer buffer = Buffer.buffer();
			upload.handler(chunk -> {
				if (buffer.length() + chunk.length() > MAX_UPLOAD) {
					promise.tryFail(new OneRosterException(413, "upload.too.large"));
				} else {
					buffer.appendBuffer(chunk);
				}
			});
			upload.endHandler(v -> promise.tryComplete(buffer.getBytes()));
			upload.exceptionHandler(promise::tryFail);
		});
		request.endHandler(v -> promise.tryFail(new OneRosterException("missing.file")));
		request.exceptionHandler(promise::tryFail);
		request.resume();
		return promise.future();
	}
}
