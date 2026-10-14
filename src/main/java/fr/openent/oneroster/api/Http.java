package fr.openent.oneroster.api;

import fr.openent.oneroster.core.OneRosterException;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Réponses HTTP de l'API OneRoster : enveloppes JSON, en-têtes de pagination, erreurs 1EdTech. */
public final class Http {

	private static final Logger log = LoggerFactory.getLogger(Http.class);
	private static final int MAX_BODY = 4 * 1024 * 1024;

	private Http() {
	}

	public static Future<MultiMap> form(HttpServerRequest request) {
		final Promise<MultiMap> promise = Promise.promise();
		request.setExpectMultipart(true);
		request.exceptionHandler(promise::tryFail);
		request.endHandler(v -> promise.tryComplete(request.formAttributes()));
		resume(request);
		return promise.future();
	}

	public static Future<JsonObject> json(HttpServerRequest request) {
		final Promise<JsonObject> promise = Promise.promise();
		request.exceptionHandler(promise::tryFail);
		request.bodyHandler(buffer -> {
			if (buffer.length() > MAX_BODY) {
				promise.tryFail(new OneRosterException(413, "body.too.large"));
				return;
			}
			try {
				promise.tryComplete(new JsonObject(buffer.toString("UTF-8")));
			} catch (RuntimeException e) {
				promise.tryFail(new OneRosterException("invalid.json"));
			}
		});
		resume(request);
		return promise.future();
	}

	public static void json(HttpServerRequest request, int status, Object body) {
		final String payload = body instanceof JsonArray ? ((JsonArray) body).encode() : ((JsonObject) body).encode();
		request.response()
				.setStatusCode(status)
				.putHeader("Content-Type", "application/json; charset=utf-8")
				.putHeader("Cache-Control", "no-store")
				.end(payload);
	}

	public static void noContent(HttpServerRequest request) {
		request.response().setStatusCode(204).end();
	}

	/** Collection : {@code {"<clé>": [...]}}, avec X-Total-Count et Link (first, prev, next, last). */
	public static void page(HttpServerRequest request, String publicBase, String key, Query.Page page) {
		final JsonArray items = new JsonArray();
		for (JsonObject o : page.items) {
			items.add(o);
		}
		request.response().putHeader("X-Total-Count", String.valueOf(page.total));
		final String links = links(publicBase + request.path(), request.params(), page);
		if (!links.isEmpty()) {
			request.response().putHeader("Link", links);
		}
		json(request, 200, new JsonObject().put(key, items));
	}

	static String links(String url, MultiMap params, Query.Page page) {
		final List<String> links = new ArrayList<>();
		if (page.total == 0 || page.limit <= 0) {
			return "";
		}
		final int lastOffset = ((page.total - 1) / page.limit) * page.limit;
		links.add(link(url, params, page.limit, 0, "first"));
		if (page.offset > 0) {
			links.add(link(url, params, page.limit, Math.max(0, page.offset - page.limit), "prev"));
		}
		if ((long) page.offset + page.limit < page.total) {
			links.add(link(url, params, page.limit, page.offset + page.limit, "next"));
		}
		links.add(link(url, params, page.limit, lastOffset, "last"));
		return String.join(",", links);
	}

	private static String link(String url, MultiMap params, int limit, int offset, String rel) {
		final StringBuilder sb = new StringBuilder("<").append(url).append("?limit=").append(limit).append("&offset=").append(offset);
		for (Map.Entry<String, String> e : params) {
			if (!"limit".equals(e.getKey()) && !"offset".equals(e.getKey())) {
				sb.append('&').append(encode(e.getKey())).append('=').append(encode(e.getValue()));
			}
		}
		return sb.append(">; rel=\"").append(rel).append('"').toString();
	}

	private static String encode(String s) {
		try {
			return URLEncoder.encode(s, StandardCharsets.UTF_8.name()).replace("+", "%20");
		} catch (java.io.UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Erreur au format imsx_StatusInfo (binding REST/JSON OneRoster 1.2). */
	public static void error(HttpServerRequest request, Throwable error) {
		final OneRosterException e;
		if (error instanceof OneRosterException) {
			e = (OneRosterException) error;
		} else {
			log.error("[OneRoster] Unexpected error", error);
			e = new OneRosterException(500, "server_error");
		}
		if (e.getStatus() == 401) {
			request.response().putHeader("WWW-Authenticate", "Bearer");
		}
		json(request, e.getStatus(), statusInfo(e));
	}

	public static JsonObject statusInfo(OneRosterException e) {
		return new JsonObject()
				.put("imsx_codeMajor", "failure")
				.put("imsx_severity", "error")
				.put("imsx_description", e.getMessage())
				.put("imsx_CodeMinor", new JsonObject().put("imsx_codeMinorField", new JsonArray().add(new JsonObject()
						.put("imsx_codeMinorFieldName", "TargetEndSystem")
						.put("imsx_codeMinorFieldValue", e.getCodeMinor()))));
	}

	private static void resume(HttpServerRequest request) {
		try {
			request.resume();
		} catch (Exception ignored) {
			// déjà consommé
		}
	}
}
