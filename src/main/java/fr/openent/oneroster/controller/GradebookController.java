package fr.openent.oneroster.controller;

import fr.openent.oneroster.OneRosterContext;
import fr.openent.oneroster.api.Http;
import fr.openent.oneroster.core.Entities;
import fr.openent.oneroster.core.OneRosterException;
import fr.openent.oneroster.core.Refs;
import fr.openent.oneroster.gradebook.GradebookService;
import fr.openent.oneroster.rostering.RosterDataset;
import fr.openent.oneroster.security.Client;
import fr.openent.oneroster.security.Scopes;
import fr.wseduc.rs.Delete;
import fr.wseduc.rs.Get;
import fr.wseduc.rs.Post;
import fr.wseduc.rs.Put;
import fr.wseduc.webutils.http.BaseController;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import static fr.openent.oneroster.rostering.RosterDataset.refId;

/**
 * Service Gradebook OneRoster 1.2, sous /ims/oneroster/gradebook/v1p2 : devoirs (lineItems),
 * notes (results) et types d'évaluation (categories) du module Compétences ; barèmes et
 * évaluations hors classe conservés pour le client.
 */
public class GradebookController extends BaseController {

	private static final String[] CORE = Api.scopes(Scopes.GRADEBOOK_CORE_READONLY, Scopes.GRADEBOOK_READONLY);
	private static final String[] FULL = Api.scopes(Scopes.GRADEBOOK_READONLY);
	private static final String[] PUT = Api.scopes(Scopes.GRADEBOOK_CREATEPUT);
	private static final String[] POST = Api.scopes(Scopes.GRADEBOOK_CREATEPOST);
	private static final String[] DELETE = Api.scopes(Scopes.GRADEBOOK_DELETE);
	private static final String[] ASSESSMENT_READ = Api.scopes(Scopes.ASSESSMENT_READONLY);
	private static final String[] ASSESSMENT_PUT = Api.scopes(Scopes.ASSESSMENT_CREATEPUT);
	private static final String[] ASSESSMENT_DELETE = Api.scopes(Scopes.ASSESSMENT_DELETE);

	private final OneRosterContext ctx;
	private final Api api;
	private final GradebookService gradebook;

	public GradebookController(OneRosterContext ctx) {
		this.ctx = ctx;
		this.api = new Api(ctx);
		this.gradebook = ctx.gradebook;
		this.pathPrefix = Refs.GRADEBOOK;
	}

	private static String id(HttpServerRequest request) {
		return request.params().get("id");
	}

	private static Function<Client, Future<List<JsonObject>>> filtered(
			Function<Client, Future<List<JsonObject>>> loader, Predicate<JsonObject> predicate) {
		return client -> loader.apply(client).map(list -> {
			final List<JsonObject> result = new ArrayList<>();
			for (JsonObject o : list) {
				if (predicate.test(o)) {
					result.add(o);
				}
			}
			return result;
		});
	}

	/** Vérifie que la classe d'une route imbriquée est dans le périmètre du client. */
	private Function<Client, Future<List<JsonObject>>> inClass(String classId,
			Function<Client, Future<List<JsonObject>>> loader) {
		return client -> ctx.roster.dataset(client.getStructures()).compose(ds ->
				ds.clazz(classId) == null && !sectionInScope(ds, classId)
						? Future.<List<JsonObject>>failedFuture(OneRosterException.notFound("class"))
						: loader.apply(client));
	}

	/** Les devoirs peuvent viser une section sans service déclaré : elle reste valide si matière et groupe sont visibles. */
	private static boolean sectionInScope(RosterDataset ds, String classId) {
		final int dot = classId.indexOf('.');
		return dot > 0 && ds.course(classId.substring(0, dot)) != null && ds.structureOfGroup(classId.substring(dot + 1)) != null;
	}

	// ================================================================= lineItems

	@Get("/lineItems")
	public void lineItems(HttpServerRequest request) {
		api.list(request, "lineItems", Entities.LINE_ITEM, CORE, gradebook::lineItems);
	}

	@Get("/lineItems/:id")
	public void lineItem(HttpServerRequest request) {
		api.one(request, "lineItem", "lineItem", Entities.LINE_ITEM, CORE, client -> gradebook.lineItem(client, id(request)));
	}

	@Get("/classes/:id/lineItems")
	public void classLineItems(HttpServerRequest request) {
		final String classId = id(request);
		api.list(request, "lineItems", Entities.LINE_ITEM, FULL,
				inClass(classId, filtered(gradebook::lineItems, li -> classId.equals(refId(li, "class")))));
	}

	@Put("/lineItems/:id")
	public void putLineItem(HttpServerRequest request) {
		final String sourcedId = id(request);
		Http.json(request).compose(body -> ctx.auth.authenticate(request, PUT)
				.compose(client -> gradebook.putLineItem(client, sourcedId, body.getJsonObject("lineItem"))))
				.onSuccess(r -> Http.json(request, r.getBoolean("created") ? 201 : 200,
						new JsonObject().put("lineItem", r.getJsonObject("lineItem"))))
				.onFailure(e -> Http.error(request, e));
	}

	@Delete("/lineItems/:id")
	public void deleteLineItem(HttpServerRequest request) {
		final String sourcedId = id(request);
		ctx.auth.authenticate(request, DELETE)
				.compose(client -> gradebook.deleteLineItem(client, sourcedId))
				.onSuccess(v -> Http.noContent(request))
				.onFailure(e -> Http.error(request, e));
	}

	/** POST /classes/{id}/lineItems : la classe de la route s'impose à chaque lineItem. */
	@Post("/classes/:id/lineItems")
	public void postClassLineItems(HttpServerRequest request) {
		final String classId = id(request);
		postMany(request, "lineItems", (client, item) -> gradebook.putLineItem(client, item.getString("sourcedId"),
				item.put("class", ctx.refs.ref("class", classId))).map(r -> r.getJsonObject("lineItem")));
	}

	/** POST /schools/{id}/lineItems : chaque lineItem doit viser une classe de l'établissement. */
	@Post("/schools/:id/lineItems")
	public void postSchoolLineItems(HttpServerRequest request) {
		final String schoolId = id(request);
		postMany(request, "lineItems", (client, item) -> {
			final String classId = Refs.sourcedId(item.getValue("class"));
			return ctx.roster.dataset(client.getStructures()).compose(ds -> {
				final int dot = classId == null ? -1 : classId.indexOf('.');
				final String groupId = dot > 0 ? classId.substring(dot + 1) : classId;
				if (groupId == null || !schoolId.equals(ds.structureOfGroup(groupId))) {
					return Future.failedFuture(new OneRosterException(422, "lineItem.class.not.in.school"));
				}
				return gradebook.putLineItem(client, item.getString("sourcedId"), item).map(r -> r.getJsonObject("lineItem"));
			});
		});
	}

	// ================================================================= results

	@Get("/results")
	public void results(HttpServerRequest request) {
		api.list(request, "results", Entities.RESULT, CORE, gradebook::results);
	}

	@Get("/results/:id")
	public void result(HttpServerRequest request) {
		api.one(request, "result", "result", Entities.RESULT, CORE, client -> gradebook.result(client, id(request)));
	}

	@Get("/classes/:id/results")
	public void classResults(HttpServerRequest request) {
		final String classId = id(request);
		api.list(request, "results", Entities.RESULT, FULL,
				inClass(classId, filtered(gradebook::results, r -> classId.equals(refId(r, "class")))));
	}

	@Get("/classes/:id/lineItems/:lineItemId/results")
	public void classLineItemResults(HttpServerRequest request) {
		final String classId = id(request);
		final String lineItemId = request.params().get("lineItemId");
		api.list(request, "results", Entities.RESULT, FULL, inClass(classId, filtered(gradebook::results,
				r -> classId.equals(refId(r, "class")) && lineItemId.equals(refId(r, "lineItem")))));
	}

	@Get("/classes/:id/students/:studentId/results")
	public void classStudentResults(HttpServerRequest request) {
		final String classId = id(request);
		final String studentId = request.params().get("studentId");
		api.list(request, "results", Entities.RESULT, FULL, inClass(classId, filtered(gradebook::results,
				r -> classId.equals(refId(r, "class")) && studentId.equals(refId(r, "student")))));
	}

	@Put("/results/:id")
	public void putResult(HttpServerRequest request) {
		final String sourcedId = id(request);
		Http.json(request).compose(body -> ctx.auth.authenticate(request, PUT)
				.compose(client -> gradebook.putResult(client, sourcedId, body.getJsonObject("result"), null)))
				.onSuccess(result -> {
					if (result == null) {
						Http.noContent(request);
					} else {
						Http.json(request, 200, new JsonObject().put("result", result));
					}
				})
				.onFailure(e -> Http.error(request, e));
	}

	@Delete("/results/:id")
	public void deleteResult(HttpServerRequest request) {
		final String sourcedId = id(request);
		ctx.auth.authenticate(request, DELETE)
				.compose(client -> gradebook.deleteResult(client, sourcedId))
				.onSuccess(v -> Http.noContent(request))
				.onFailure(e -> Http.error(request, e));
	}

	@Post("/lineItems/:id/results")
	public void postLineItemResults(HttpServerRequest request) {
		final String lineItemId = id(request);
		postMany(request, "results", (client, item) ->
				gradebook.putResult(client, item.getString("sourcedId"), item, lineItemId));
	}

	@Post("/classes/:id/academicSessions/:sessionId/results")
	public void postClassResults(HttpServerRequest request) {
		final String classId = id(request);
		postMany(request, "results", (client, item) -> gradebook.lineItem(client, Refs.sourcedId(item.getValue("lineItem")))
				.compose(li -> li == null || !classId.equals(refId(li, "class"))
						? Future.failedFuture(new OneRosterException(422, "result.lineItem.not.in.class"))
						: gradebook.putResult(client, item.getString("sourcedId"), item, null)));
	}

	/**
	 * Création en lot (POST) : chaque objet porte son sourcedId, conservé tel quel. Réponse 201
	 * avec les paires {suppliedSourcedId, allocatedSourcedId} ; le premier échec interrompt le lot.
	 */
	private void postMany(HttpServerRequest request, String key, BiFunction<Client, JsonObject, Future<JsonObject>> create) {
		Http.json(request).compose(body -> ctx.auth.authenticate(request, POST).compose(client -> {
			final JsonArray items = body.getJsonArray(key);
			if (items == null || items.isEmpty()) {
				return Future.<JsonArray>failedFuture(new OneRosterException("missing." + key));
			}
			Future<JsonArray> chain = Future.succeededFuture(new JsonArray());
			for (Object o : items) {
				if (!(o instanceof JsonObject) || ((JsonObject) o).getString("sourcedId") == null) {
					return Future.<JsonArray>failedFuture(new OneRosterException(422, key + ".sourcedId.required"));
				}
				final JsonObject item = (JsonObject) o;
				chain = chain.compose(pairs -> create.apply(client, item).map(created -> pairs.add(new JsonObject()
						.put("suppliedSourcedId", item.getString("sourcedId"))
						.put("allocatedSourcedId", item.getString("sourcedId")))));
			}
			return chain;
		})).onSuccess(pairs -> Http.json(request, 201, new JsonObject().put("sourcedIdPairs", pairs)))
				.onFailure(e -> Http.error(request, e));
	}

	// ================================================================= categories

	@Get("/categories")
	public void categories(HttpServerRequest request) {
		api.list(request, "categories", Entities.CATEGORY, CORE, gradebook::categories);
	}

	@Get("/categories/:id")
	public void category(HttpServerRequest request) {
		api.one(request, "category", "category", Entities.CATEGORY, CORE, client -> gradebook.category(client, id(request)));
	}

	/** Catégories d'une classe : types d'évaluation de son établissement et catégories du client. */
	@Get("/classes/:id/categories")
	public void classCategories(HttpServerRequest request) {
		final String classId = id(request);
		api.list(request, "categories", Entities.CATEGORY, FULL, client -> ctx.roster.dataset(client.getStructures())
				.compose(ds -> {
					final JsonObject c = ds.clazz(classId);
					if (c == null && !sectionInScope(ds, classId)) {
						return Future.failedFuture(OneRosterException.notFound("class"));
					}
					final String school = ds.structureOfGroup(classId.substring(classId.indexOf('.') + 1));
					return gradebook.categories(client).map(list -> {
						final List<JsonObject> result = new ArrayList<>();
						for (JsonObject cat : list) {
							final JsonObject meta = cat.getJsonObject("metadata");
							if (meta == null || meta.getString("school") == null || meta.getString("school").equals(school)) {
								result.add(cat);
							}
						}
						return result;
					});
				}));
	}

	@Put("/categories/:id")
	public void putCategory(HttpServerRequest request) {
		putObject(request, GradebookService.CATEGORY, "category", PUT);
	}

	@Delete("/categories/:id")
	public void deleteCategory(HttpServerRequest request) {
		deleteObject(request, GradebookService.CATEGORY, DELETE);
	}

	// ================================================================= scoreScales

	@Get("/scoreScales")
	public void scoreScales(HttpServerRequest request) {
		api.list(request, "scoreScales", Entities.SCORE_SCALE, CORE, own(GradebookService.SCORE_SCALE));
	}

	@Get("/scoreScales/:id")
	public void scoreScale(HttpServerRequest request) {
		api.one(request, "scoreScale", "scoreScale", Entities.SCORE_SCALE, CORE,
				client -> gradebook.objects().get(client.getId(), GradebookService.SCORE_SCALE, id(request)));
	}

	@Get("/classes/:id/scoreScales")
	public void classScoreScales(HttpServerRequest request) {
		final String classId = id(request);
		api.list(request, "scoreScales", Entities.SCORE_SCALE, FULL,
				inClass(classId, filtered(own(GradebookService.SCORE_SCALE), s -> classId.equals(refId(s, "class")))));
	}

	@Get("/schools/:id/scoreScales")
	public void schoolScoreScales(HttpServerRequest request) {
		final String schoolId = id(request);
		api.list(request, "scoreScales", Entities.SCORE_SCALE, FULL, client -> ctx.roster.dataset(client.getStructures())
				.compose(ds -> ds.school(schoolId) == null
						? Future.<List<JsonObject>>failedFuture(OneRosterException.notFound("school"))
						: filtered(own(GradebookService.SCORE_SCALE), s -> schoolId.equals(schoolOf(ds, s))).apply(client)));
	}

	private static String schoolOf(RosterDataset ds, JsonObject scoreScale) {
		final String classId = refId(scoreScale, "class");
		if (classId != null) {
			return ds.structureOfGroup(classId.substring(classId.indexOf('.') + 1));
		}
		final JsonObject course = ds.course(refId(scoreScale, "course"));
		return course != null ? refId(course, "org") : null;
	}

	@Put("/scoreScales/:id")
	public void putScoreScale(HttpServerRequest request) {
		putObject(request, GradebookService.SCORE_SCALE, "scoreScale", PUT);
	}

	@Delete("/scoreScales/:id")
	public void deleteScoreScale(HttpServerRequest request) {
		deleteObject(request, GradebookService.SCORE_SCALE, DELETE);
	}

	// ================================================================= assessments

	@Get("/assessmentLineItems")
	public void assessmentLineItems(HttpServerRequest request) {
		api.list(request, "assessmentLineItems", Entities.ASSESSMENT_LINE_ITEM, ASSESSMENT_READ,
				own(GradebookService.ASSESSMENT_LINE_ITEM));
	}

	@Get("/assessmentLineItems/:id")
	public void assessmentLineItem(HttpServerRequest request) {
		api.one(request, "assessmentLineItem", "assessmentLineItem", Entities.ASSESSMENT_LINE_ITEM, ASSESSMENT_READ,
				client -> gradebook.objects().get(client.getId(), GradebookService.ASSESSMENT_LINE_ITEM, id(request)));
	}

	@Put("/assessmentLineItems/:id")
	public void putAssessmentLineItem(HttpServerRequest request) {
		putObject(request, GradebookService.ASSESSMENT_LINE_ITEM, "assessmentLineItem", ASSESSMENT_PUT);
	}

	@Delete("/assessmentLineItems/:id")
	public void deleteAssessmentLineItem(HttpServerRequest request) {
		deleteObject(request, GradebookService.ASSESSMENT_LINE_ITEM, ASSESSMENT_DELETE);
	}

	@Get("/assessmentResults")
	public void assessmentResults(HttpServerRequest request) {
		api.list(request, "assessmentResults", Entities.ASSESSMENT_RESULT, ASSESSMENT_READ,
				own(GradebookService.ASSESSMENT_RESULT));
	}

	@Get("/assessmentResults/:id")
	public void assessmentResult(HttpServerRequest request) {
		api.one(request, "assessmentResult", "assessmentResult", Entities.ASSESSMENT_RESULT, ASSESSMENT_READ,
				client -> gradebook.objects().get(client.getId(), GradebookService.ASSESSMENT_RESULT, id(request)));
	}

	@Put("/assessmentResults/:id")
	public void putAssessmentResult(HttpServerRequest request) {
		putObject(request, GradebookService.ASSESSMENT_RESULT, "assessmentResult", ASSESSMENT_PUT);
	}

	@Delete("/assessmentResults/:id")
	public void deleteAssessmentResult(HttpServerRequest request) {
		deleteObject(request, GradebookService.ASSESSMENT_RESULT, ASSESSMENT_DELETE);
	}

	// ================================================================= objets propres au client

	private Function<Client, Future<List<JsonObject>>> own(String kind) {
		return client -> gradebook.objects().list(client.getId(), kind);
	}

	private void putObject(HttpServerRequest request, String kind, String key, String[] scopes) {
		final String sourcedId = id(request);
		Http.json(request).compose(body -> ctx.auth.authenticate(request, scopes).compose(client ->
				gradebook.putObject(client, kind, sourcedId, body.getJsonObject(key)).compose(created ->
						gradebook.objects().get(client.getId(), kind, sourcedId)
								.map(o -> new JsonObject().put("created", created).put(key, o)))))
				.onSuccess(r -> Http.json(request, r.getBoolean("created") ? 201 : 200,
						new JsonObject().put(key, r.getJsonObject(key))))
				.onFailure(e -> Http.error(request, e));
	}

	private void deleteObject(HttpServerRequest request, String kind, String[] scopes) {
		final String sourcedId = id(request);
		ctx.auth.authenticate(request, scopes)
				.compose(client -> gradebook.deleteObject(client, kind, sourcedId))
				.onSuccess(v -> Http.noContent(request))
				.onFailure(e -> Http.error(request, e));
	}
}
