package fr.openent.oneroster.controller;

import fr.openent.oneroster.OneRosterContext;
import fr.openent.oneroster.core.Entities;
import fr.openent.oneroster.core.OneRosterException;
import fr.openent.oneroster.core.Refs;
import fr.openent.oneroster.resources.ResourceService;
import fr.openent.oneroster.security.Scopes;
import fr.wseduc.rs.Get;
import fr.wseduc.webutils.http.BaseController;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;

import java.util.List;

/** Service Resources OneRoster 1.2, sous /ims/oneroster/resources/v1p2. */
public class ResourcesController extends BaseController {

	private static final String[] CORE = Api.scopes(Scopes.RESOURCE_CORE_READONLY, Scopes.RESOURCE_READONLY);
	private static final String[] FULL = Api.scopes(Scopes.RESOURCE_READONLY);

	private final OneRosterContext ctx;
	private final Api api;

	public ResourcesController(OneRosterContext ctx) {
		this.ctx = ctx;
		this.api = new Api(ctx);
		this.pathPrefix = Refs.RESOURCES;
	}

	private static String id(HttpServerRequest request) {
		return request.params().get("id");
	}

	@Get("/resources")
	public void resources(HttpServerRequest request) {
		api.list(request, "resources", Entities.RESOURCE, CORE,
				client -> ctx.resources.linked(client).map(ResourceService.Linked::all));
	}

	@Get("/resources/:id")
	public void resource(HttpServerRequest request) {
		final String resourceId = id(request);
		api.one(request, "resource", "resource", Entities.RESOURCE, CORE,
				client -> ctx.resources.linked(client).map(linked -> linked.get(resourceId)));
	}

	@Get("/classes/:id/resources")
	public void classResources(HttpServerRequest request) {
		final String classId = id(request);
		api.list(request, "resources", Entities.RESOURCE, FULL, client -> ctx.roster.dataset(client.getStructures())
				.compose(ds -> ds.clazz(classId) == null
						? Future.<List<JsonObject>>failedFuture(OneRosterException.notFound("class"))
						: ctx.resources.linked(client).map(linked -> linked.ofClass(classId))));
	}

	@Get("/courses/:id/resources")
	public void courseResources(HttpServerRequest request) {
		final String courseId = id(request);
		api.list(request, "resources", Entities.RESOURCE, FULL, client -> ctx.roster.dataset(client.getStructures())
				.compose(ds -> ds.course(courseId) == null
						? Future.<List<JsonObject>>failedFuture(OneRosterException.notFound("course"))
						: ctx.resources.linked(client).map(linked -> ResourceService.ofCourse(ds, linked, courseId))));
	}

	@Get("/users/:id/resources")
	public void userResources(HttpServerRequest request) {
		final String userId = id(request);
		api.list(request, "resources", Entities.RESOURCE, FULL, client -> ctx.roster.dataset(client.getStructures())
				.compose(ds -> ds.user(userId) == null
						? Future.<List<JsonObject>>failedFuture(OneRosterException.notFound("user"))
						: ctx.resources.linked(client).map(linked -> {
							// Ressources partagées avec l'utilisateur ou avec l'une de ses classes.
							final java.util.Map<String, JsonObject> result = new java.util.LinkedHashMap<>();
							for (JsonObject r : linked.ofUser(userId)) {
								result.put(r.getString("sourcedId"), r);
							}
							for (JsonObject c : ds.classesOfUser(userId)) {
								for (JsonObject r : linked.ofClass(c.getString("sourcedId"))) {
									result.put(r.getString("sourcedId"), r);
								}
							}
							return new java.util.ArrayList<>(result.values());
						})));
	}
}
