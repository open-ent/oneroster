package fr.openent.oneroster.controller;

import fr.openent.oneroster.OneRosterContext;
import fr.openent.oneroster.api.Http;
import fr.openent.oneroster.api.Query;
import fr.openent.oneroster.core.OneRosterException;
import fr.openent.oneroster.security.Client;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Enchaînement commun des routes de l'API : authentification, lecture, filtre / pagination, réponse. */
final class Api {

	private final OneRosterContext ctx;

	Api(OneRosterContext ctx) {
		this.ctx = ctx;
	}

	/** Collection : {@code {"<key>": [...]}} filtrée, triée et paginée selon la requête. */
	void list(HttpServerRequest request, String key, Set<String> fields, String[] scopes,
			Function<Client, Future<List<JsonObject>>> loader) {
		final Query query;
		try {
			query = Query.parse(request.params(), ctx.maxLimit);
		} catch (OneRosterException e) {
			Http.error(request, e);
			return;
		}
		ctx.auth.authenticate(request, scopes)
				.compose(loader)
				.compose(items -> {
					try {
						return Future.succeededFuture(query.apply(items, fields));
					} catch (OneRosterException e) {
						return Future.<Query.Page>failedFuture(e);
					}
				})
				.onSuccess(page -> Http.page(request, ctx.publicBase, key, page))
				.onFailure(e -> Http.error(request, e));
	}

	/** Entité seule : {@code {"<key>": {...}}}, 404 si absente du périmètre du client. */
	void one(HttpServerRequest request, String key, String what, Set<String> fields, String[] scopes,
			Function<Client, Future<JsonObject>> loader) {
		final Query query;
		try {
			query = Query.parse(request.params(), ctx.maxLimit);
		} catch (OneRosterException e) {
			Http.error(request, e);
			return;
		}
		ctx.auth.authenticate(request, scopes)
				.compose(loader)
				.compose(entity -> entity == null
						? Future.<JsonObject>failedFuture(OneRosterException.notFound(what))
						: Future.succeededFuture(entity))
				.onSuccess(entity -> Http.json(request, 200, new JsonObject().put(key, query.project(entity, fields))))
				.onFailure(e -> Http.error(request, e));
	}

	static String[] scopes(String... scopes) {
		return scopes;
	}

	/** Échec 404 si l'entité parente d'une route imbriquée n'existe pas. */
	static <T> Future<T> require(Object parent, String what, T value) {
		return parent == null ? Future.failedFuture(OneRosterException.notFound(what)) : Future.succeededFuture(value);
	}
}
