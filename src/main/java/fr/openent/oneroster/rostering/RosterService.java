package fr.openent.oneroster.rostering;

import fr.openent.oneroster.core.Refs;
import fr.openent.oneroster.service.RosterRepository;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fournit le {@link RosterDataset} d'un périmètre d'établissements. Le jeu est construit en
 * une passe (une dizaine de requêtes) puis gardé en cache quelques instants : un client qui
 * parcourt les pages d'une collection, ou enchaîne /classes puis /classes/{id}/students,
 * ne relance pas la lecture de l'annuaire à chaque appel.
 */
public class RosterService {

	private final RosterRepository repository;
	private final Refs refs;
	private final long cacheMillis;
	private final Map<String, Cached> cache = new ConcurrentHashMap<>();

	public RosterService(RosterRepository repository, Refs refs, long cacheSeconds) {
		this.repository = repository;
		this.refs = refs;
		this.cacheMillis = cacheSeconds * 1000L;
	}

	public Future<RosterDataset> dataset(List<String> structures) {
		final List<String> scope = new ArrayList<>(structures);
		Collections.sort(scope);
		final String key = String.join(",", scope);
		final long now = System.currentTimeMillis();
		cache.values().removeIf(c -> c.expires < now);
		final Cached cached = cache.get(key);
		if (cached != null) {
			return cached.future;
		}
		final Future<RosterDataset> future = load(scope);
		cache.put(key, new Cached(future, now + cacheMillis));
		future.onFailure(e -> cache.remove(key));
		return future;
	}

	/** Vide le cache : après un import, la prochaine lecture reflète l'annuaire à jour. */
	public void invalidate() {
		cache.clear();
	}

	private Future<RosterDataset> load(List<String> scope) {
		if (scope.isEmpty()) {
			return Future.succeededFuture(new RosterBuilder(refs, scope, LocalDate.now()).build(new RosterBuilder.Raw()));
		}
		final List<Future> futures = Arrays.<Future>asList(
				repository.structures(scope),
				repository.users(scope),
				repository.classes(scope),
				repository.classMembers(scope),
				repository.classProfileGroups(scope),
				repository.functionalGroups(scope),
				repository.functionalGroupMembers(scope),
				repository.subjects(scope),
				repository.teaches(scope),
				repository.services(scope),
				repository.schoolYears(scope),
				repository.periods(scope));
		return CompositeFuture.all(futures).compose(all -> {
			final RosterBuilder.Raw raw = new RosterBuilder.Raw();
			raw.structures = all.resultAt(0);
			raw.users = all.resultAt(1);
			raw.classes = all.resultAt(2);
			raw.classMembers = all.resultAt(3);
			raw.classProfileGroups = all.resultAt(4);
			raw.functionalGroups = all.resultAt(5);
			raw.functionalGroupMembers = all.resultAt(6);
			raw.subjects = all.resultAt(7);
			raw.teaches = all.resultAt(8);
			raw.services = all.resultAt(9);
			raw.schoolYears = all.resultAt(10);
			raw.periods = all.resultAt(11);
			final List<String> parentIds = new ArrayList<>();
			for (Object o : raw.structures) {
				final JsonArray parents = ((JsonObject) o).getJsonArray("parents", new JsonArray());
				for (Object p : parents) {
					if (p instanceof String && !parentIds.contains(p)) {
						parentIds.add((String) p);
					}
				}
			}
			final Future<JsonArray> parentsFuture = parentIds.isEmpty()
					? Future.succeededFuture(new JsonArray())
					: repository.parentStructures(parentIds);
			return parentsFuture.map(parents -> {
				raw.parents = parents;
				return new RosterBuilder(refs, scope, LocalDate.now()).build(raw);
			});
		});
	}

	private static final class Cached {
		final Future<RosterDataset> future;
		final long expires;

		Cached(Future<RosterDataset> future, long expires) {
			this.future = future;
			this.expires = expires;
		}
	}
}
