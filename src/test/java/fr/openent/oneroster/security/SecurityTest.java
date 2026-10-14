package fr.openent.oneroster.security;

import fr.openent.oneroster.core.OneRosterException;
import fr.openent.oneroster.csv.RemoteProvider;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SecurityTest {

	private final AccessTokens tokens = new AccessTokens(KeyManager.fromConfig(new JsonObject()), "https://ent.example.org");

	@Test
	public void tokenRoundTrip() throws Exception {
		final long now = System.currentTimeMillis() / 1000L;
		final String token = tokens.issue("client-1", Arrays.asList(Scopes.ROSTER_READONLY, Scopes.GRADEBOOK_READONLY), now, 3600);
		final JsonObject claims = tokens.verify("Bearer " + token, now);
		assertEquals("client-1", claims.getString("sub"));
		assertEquals(Arrays.asList(Scopes.ROSTER_READONLY, Scopes.GRADEBOOK_READONLY), Scopes.split(claims.getString("scope")));
	}

	@Test
	public void expiredOrForeignTokensAreRejected() throws Exception {
		final long now = System.currentTimeMillis() / 1000L;
		final String token = tokens.issue("client-1", Collections.singletonList(Scopes.ROSTER_READONLY), now - 7200, 3600);
		expect401(() -> tokens.verify("Bearer " + token, now));
		final AccessTokens other = new AccessTokens(KeyManager.fromConfig(new JsonObject()), "https://ent.example.org");
		final String foreign = other.issue("client-1", Collections.singletonList(Scopes.ROSTER_READONLY), now, 3600);
		expect401(() -> tokens.verify("Bearer " + foreign, now));
		expect401(() -> tokens.verify(null, now));
		expect401(() -> tokens.verify("Basic abc", now));
	}

	@Test
	public void grantedScopesAreTheIntersection() {
		final JsonArray allowed = new JsonArray().add(Scopes.ROSTER_CORE_READONLY).add(Scopes.GRADEBOOK_READONLY);
		assertEquals(Collections.singletonList(Scopes.ROSTER_CORE_READONLY),
				Scopes.grant(Scopes.ROSTER_CORE_READONLY + " " + Scopes.ROSTER_READONLY, allowed));
		assertEquals(Arrays.asList(Scopes.ROSTER_CORE_READONLY, Scopes.GRADEBOOK_READONLY), Scopes.grant(null, allowed));
		assertTrue(Scopes.grant("unknown", allowed).isEmpty());
	}

	@Test
	public void secretsAreHashedAndComparedSafely() {
		final String secret = Secrets.random(32);
		final String hash = Secrets.hash(secret);
		assertTrue(Secrets.matches(secret, hash));
		assertFalse(Secrets.matches(secret + "x", hash));
		assertFalse(Secrets.matches(null, hash));
	}

	@Test
	public void clientScopeChecks() {
		final Client client = new Client("c", Collections.singletonList("s1"), Collections.singletonList(Scopes.ROSTER_READONLY), null);
		assertTrue(client.hasAny(Scopes.ROSTER_CORE_READONLY, Scopes.ROSTER_READONLY));
		assertFalse(client.hasAny(Scopes.ROSTER_DEMOGRAPHICS_READONLY));
		assertTrue(client.canSee("s1"));
		assertFalse(client.canSee("s2"));
	}

	@Test
	public void remoteProvidersAreRestrictedToTheAllowList() {
		final List<String> allowed = Collections.singletonList("https://sis.example.org/oneroster/");
		final RemoteProvider remote = new RemoteProvider(null, allowed);
		assertTrue(remote.allowed("https://sis.example.org/oneroster/ims/oneroster/rostering/v1p2"));
		assertFalse(remote.allowed("https://sis.example.org.evil.net/oneroster/x"));
		assertFalse(remote.allowed("http://sis.example.org/oneroster/x"));
		assertFalse(remote.allowed("https://sis.example.org/other"));
		assertFalse(remote.allowed("https://sis.example.org/oneroster/../admin"));
		assertFalse(remote.allowed("https://sis.example.org:8443/oneroster/x"));
		assertFalse(new RemoteProvider(null, Collections.<String>emptyList()).allowed("https://sis.example.org/oneroster/x"));
	}

	private interface Call {
		void run() throws OneRosterException;
	}

	private static void expect401(Call call) {
		try {
			call.run();
			fail();
		} catch (OneRosterException e) {
			assertEquals(401, e.getStatus());
		}
	}
}
