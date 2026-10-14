package fr.openent.oneroster.csv;

import fr.openent.oneroster.core.OneRosterException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Lecture d'un fournisseur OneRoster REST distant (Rostering 1.2, ou 1.1 compatible) :
 * jeton client credentials, puis orgs, users, classes, enrollments et demographics, page par page.
 * Seuls les fournisseurs listés en configuration (oneroster.import.remote-providers) sont autorisés.
 */
public class RemoteProvider {

	private static final int PAGE = 1000;
	private static final int MAX_PAGES = 500;

	private final Vertx vertx;
	private final List<String> allowedPrefixes;

	public RemoteProvider(Vertx vertx, List<String> allowedPrefixes) {
		this.vertx = vertx;
		this.allowedPrefixes = allowedPrefixes;
	}

	/**
	 * Vrai si l'URL est en https, sur le même hôte et le même port qu'un fournisseur autorisé, et
	 * sous son chemin. On compare des URI analysées et non des chaînes : « https://sis.example.org »
	 * n'autorise pas « https://sis.example.org.attaquant.net ».
	 */
	public boolean allowed(String url) {
		final URI target = uri(url);
		if (target == null || !"https".equals(target.getScheme()) || target.getHost() == null) {
			return false;
		}
		for (String prefix : allowedPrefixes) {
			final URI allowed = uri(prefix);
			if (allowed != null && "https".equals(allowed.getScheme())
					&& target.getHost().equalsIgnoreCase(allowed.getHost())
					&& target.getPort() == allowed.getPort()
					&& normalizedPath(target).startsWith(normalizedPath(allowed))) {
				return true;
			}
		}
		return false;
	}

	private static URI uri(String s) {
		try {
			return s == null ? null : new URI(s).normalize();
		} catch (URISyntaxException e) {
			return null;
		}
	}

	private static String normalizedPath(URI uri) {
		final String path = uri.getPath() == null ? "" : uri.getPath();
		return path.endsWith("/") ? path : path + "/";
	}

	/**
	 * @param baseUrl URL du service Rostering, ex. https://sis.example.org/ims/oneroster/rostering/v1p2
	 */
	public Future<OneRosterSource> fetch(String baseUrl, String tokenUrl, String clientId, String clientSecret) {
		if (!allowed(baseUrl) || !allowed(tokenUrl)) {
			return Future.failedFuture(new OneRosterException(403, "remote.provider.not.allowed"));
		}
		final HttpClient client = vertx.createHttpClient(new HttpClientOptions()
				.setConnectTimeout(10_000).setIdleTimeout(120));
		final String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		final OneRosterSource source = new OneRosterSource();
		return token(client, tokenUrl, clientId, clientSecret)
				.compose(token -> all(client, base + "/orgs", "orgs", token).map(a -> { source.addOrgs(a); return token; }))
				.compose(token -> all(client, base + "/users", "users", token).map(a -> { source.addUsers(a); return token; }))
				.compose(token -> all(client, base + "/classes", "classes", token).map(a -> { source.addClasses(a); return token; }))
				.compose(token -> all(client, base + "/enrollments", "enrollments", token)
						.map(a -> { source.addEnrollments(a); return token; }))
				// Les dates de naissance demandent un scope distinct : leur absence n'empêche pas l'import.
				.compose(token -> all(client, base + "/demographics", "demographics", token)
						.recover(e -> Future.succeededFuture(new JsonArray()))
						.map(a -> { source.addDemographics(a); return source; }))
				.onComplete(ar -> client.close());
	}

	private Future<String> token(HttpClient client, String tokenUrl, String clientId, String clientSecret) {
		final String basic = Base64.getEncoder().encodeToString(
				(encode(clientId) + ":" + encode(clientSecret)).getBytes(StandardCharsets.UTF_8));
		return client.request(new RequestOptions().setMethod(HttpMethod.POST).setAbsoluteURI(tokenUrl)
						.putHeader("Authorization", "Basic " + basic)
						.putHeader("Content-Type", "application/x-www-form-urlencoded")
						.putHeader("Accept", "application/json"))
				.compose(req -> req.send(Buffer.buffer("grant_type=client_credentials")))
				.compose(resp -> resp.body().compose(body -> {
					if (resp.statusCode() != 200) {
						return Future.failedFuture(new OneRosterException(502, "remote.token.refused." + resp.statusCode()));
					}
					final String token = new JsonObject(body).getString("access_token");
					return token == null ? Future.failedFuture(new OneRosterException(502, "remote.token.missing"))
							: Future.succeededFuture(token);
				}));
	}

	private Future<JsonArray> all(HttpClient client, String url, String key, String token) {
		return page(client, url, key, token, 0, new JsonArray());
	}

	private Future<JsonArray> page(HttpClient client, String url, String key, String token, int index, JsonArray acc) {
		if (index >= MAX_PAGES) {
			return Future.failedFuture(new OneRosterException(502, "remote.too.many.pages"));
		}
		return client.request(new RequestOptions().setMethod(HttpMethod.GET)
						.setAbsoluteURI(url + "?limit=" + PAGE + "&offset=" + (index * PAGE))
						.putHeader("Authorization", "Bearer " + token)
						.putHeader("Accept", "application/json"))
				.compose(req -> req.send())
				.compose(resp -> resp.body().compose(body -> {
					if (resp.statusCode() != 200) {
						return Future.failedFuture(new OneRosterException(502, "remote." + key + ".status." + resp.statusCode()));
					}
					final JsonArray items = new JsonObject(body).getJsonArray(key, new JsonArray());
					acc.addAll(items);
					final String total = resp.getHeader("X-Total-Count");
					final boolean more = total != null ? acc.size() < Integer.parseInt(total.trim()) : items.size() == PAGE;
					return more && !items.isEmpty() ? page(client, url, key, token, index + 1, acc) : Future.succeededFuture(acc);
				}));
	}

	private static String encode(String s) {
		try {
			return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
		} catch (java.io.UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}
}
