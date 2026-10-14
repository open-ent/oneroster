package fr.openent.oneroster.security;

import fr.openent.oneroster.core.OneRosterException;
import fr.openent.oneroster.service.ClientRepository;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonObject;

/** Authentifie un appel d'API : jeton Bearer valide, client toujours actif, scope requis présent. */
public class Authenticator {

	private final AccessTokens tokens;
	private final ClientRepository clients;

	public Authenticator(AccessTokens tokens, ClientRepository clients) {
		this.tokens = tokens;
		this.clients = clients;
	}

	/**
	 * @param acceptedScopes l'un de ces scopes suffit (ex. roster-core.readonly ou roster.readonly)
	 */
	public Future<Client> authenticate(HttpServerRequest request, String... acceptedScopes) {
		final JsonObject claims;
		try {
			claims = tokens.verify(request.getHeader("Authorization"), System.currentTimeMillis() / 1000L);
		} catch (OneRosterException e) {
			return Future.failedFuture(e);
		}
		return clients.findEnabled(claims.getString("sub")).compose(row -> {
			if (row == null) {
				return Future.failedFuture(new OneRosterException(401, "unknown.client"));
			}
			// Les scopes retirés au client depuis l'émission du jeton cessent d'être acceptés.
			final java.util.List<String> scopes = Scopes.split(claims.getString("scope"));
			scopes.retainAll(Client.strings(fr.openent.oneroster.service.Db.jsonArray(row, "scopes")));
			final Client client = Client.fromRow(row, scopes);
			if (!client.hasAny(acceptedScopes)) {
				return Future.failedFuture(new OneRosterException(403, "insufficient.scope"));
			}
			return Future.succeededFuture(client);
		});
	}
}
