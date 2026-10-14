package fr.openent.oneroster;

import fr.openent.oneroster.core.Refs;
import fr.openent.oneroster.gradebook.GradebookService;
import fr.openent.oneroster.resources.ResourceService;
import fr.openent.oneroster.rostering.RosterService;
import fr.openent.oneroster.security.AccessTokens;
import fr.openent.oneroster.security.Authenticator;
import fr.openent.oneroster.security.KeyManager;
import fr.openent.oneroster.service.ClientRepository;
import fr.openent.oneroster.service.RosterRepository;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonObject;
import org.entcore.common.storage.Storage;

/** Dépendances partagées par les contrôleurs du module. */
public class OneRosterContext {

	public final Vertx vertx;
	public final EventBus eb;
	public final JsonObject config;
	/** Origine publique de l'ENT, ex. https://ent.example.org */
	public final String publicBase;
	public final long accessTokenTtl;
	public final int maxLimit;
	public final Refs refs;
	public final AccessTokens tokens;
	public final ClientRepository clients = new ClientRepository();
	public final Authenticator auth;
	public final RosterService roster;
	public final GradebookService gradebook;
	public final ResourceService resources;
	public final Storage storage;

	public OneRosterContext(Vertx vertx, EventBus eb, JsonObject config, Storage storage) {
		final JsonObject oneroster = config.getJsonObject("oneroster", new JsonObject());
		this.vertx = vertx;
		this.eb = eb;
		this.config = oneroster;
		this.storage = storage;
		this.publicBase = stripTrailingSlash(oneroster.getString("issuer", config.getString("host", "http://localhost:8090")));
		this.accessTokenTtl = oneroster.getLong("access-token-ttl-seconds", 3600L);
		this.maxLimit = oneroster.getInteger("max-limit", 10000);
		this.refs = new Refs(publicBase);
		this.tokens = new AccessTokens(KeyManager.fromConfig(oneroster), publicBase);
		this.auth = new Authenticator(tokens, clients);
		this.roster = new RosterService(new RosterRepository(), refs, oneroster.getLong("cache-seconds", 120L));
		this.gradebook = new GradebookService(refs, roster);
		this.resources = new ResourceService(refs, roster);
	}

	public String tokenEndpoint() {
		return publicBase + "/oneroster/oauth/token";
	}

	private static String stripTrailingSlash(String s) {
		return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
	}
}
