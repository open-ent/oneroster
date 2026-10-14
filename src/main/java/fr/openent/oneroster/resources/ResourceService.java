package fr.openent.oneroster.resources;

import fr.openent.oneroster.core.Entities;
import fr.openent.oneroster.core.Refs;
import fr.openent.oneroster.rostering.RosterDataset;
import fr.openent.oneroster.rostering.RosterService;
import fr.openent.oneroster.security.Client;
import fr.openent.oneroster.service.Db;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Service Resources : signets du médiacentre (mediacentre.signet). Une ressource est rattachée
 * aux classes et aux utilisateurs avec lesquels le signet est partagé ; les signets publiés
 * sont visibles de tous les clients.
 */
public class ResourceService {

	private final Refs refs;
	private final RosterService roster;

	public ResourceService(Refs refs, RosterService roster) {
		this.refs = refs;
		this.roster = roster;
	}

	/** Ressources du périmètre, avec leurs rattachements (classes, utilisateurs). */
	public Future<Linked> linked(Client client) {
		return roster.dataset(client.getStructures()).compose(ds -> Db.sqlOptional(
				// signet.id n'est pas une clé primaire (simple contrainte d'unicité) : pas de GROUP BY possible.
				"SELECT s.id, s.resource_id AS \"resourceId\", s.title, s.url, s.imageurl, s.owner_id AS \"ownerId\", " +
				"s.published, to_json(s.discipline_label) AS disciplines, to_json(s.level_label) AS levels, " +
				"coalesce(s.date_modification, s.date_creation) AS modified, " +
				"(SELECT coalesce(json_agg(DISTINCT sh.member_id), '[]') FROM mediacentre.signet_shares sh " +
				"WHERE sh.resource_id = s.id) AS members " +
				"FROM mediacentre.signet s WHERE NOT s.archived ORDER BY s.id", new JsonArray()).map(rows -> link(ds, rows)));
	}

	Linked link(RosterDataset ds, JsonArray rows) {
		final Linked linked = new Linked();
		for (Object o : rows) {
			final JsonObject row = (JsonObject) o;
			final Set<String> classes = new LinkedHashSet<>();
			final Set<String> users = new LinkedHashSet<>();
			for (Object m : Db.jsonArray(row, "members")) {
				final String member = (String) m;
				if (ds.user(member) != null) {
					users.add(member);
				}
				final String homeroom = ds.classOfProfileGroup(member);
				if (homeroom != null) {
					classes.add(homeroom);
				}
				if (ds.structureOfGroup(member) != null) {
					for (JsonObject c : ds.classesWhere(c -> c.getString("sourcedId").equals(member)
							|| c.getString("sourcedId").endsWith("." + member))) {
						classes.add(c.getString("sourcedId"));
					}
				}
			}
			final boolean visible = Boolean.TRUE.equals(row.getBoolean("published"))
					|| ds.user(row.getString("ownerId")) != null || !classes.isEmpty() || !users.isEmpty();
			if (!visible) {
				continue;
			}
			final String id = "signet." + row.getValue("id");
			linked.resources.put(id, resource(id, row));
			linked.classes.put(id, new ArrayList<>(classes));
			linked.users.put(id, new ArrayList<>(users));
		}
		return linked;
	}

	private JsonObject resource(String id, JsonObject row) {
		final JsonObject metadata = new JsonObject();
		if (row.getString("url") != null) {
			metadata.put("url", row.getString("url"));
		}
		if (row.getString("imageurl") != null) {
			metadata.put("imageUrl", row.getString("imageurl"));
		}
		metadata.put("disciplines", Db.jsonArray(row, "disciplines")).put("levels", Db.jsonArray(row, "levels"));
		return Entities.base(id, Entities.ACTIVE, row.getString("modified"))
				.put("title", row.getString("title"))
				.put("roles", new JsonArray().add("student").add("teacher"))
				.put("importance", "primary")
				.put("vendorResourceId", row.getString("resourceId") != null ? row.getString("resourceId") : row.getString("url"))
				.put("vendorId", "mediacentre")
				.put("metadata", metadata);
	}

	/** Ressources et rattachements, indexés par sourcedId de ressource. */
	public static final class Linked {
		final Map<String, JsonObject> resources = new LinkedHashMap<>();
		final Map<String, List<String>> classes = new LinkedHashMap<>();
		final Map<String, List<String>> users = new LinkedHashMap<>();

		public List<JsonObject> all() {
			return new ArrayList<>(resources.values());
		}

		public JsonObject get(String id) {
			return resources.get(id);
		}

		public List<JsonObject> ofClass(String classId) {
			return where(classes, classId);
		}

		public List<JsonObject> ofUser(String userId) {
			return where(users, userId);
		}

		/** Rattachements classe → ressource (export CSV classResources). */
		public Map<String, List<String>> classLinks() {
			return classes;
		}

		/** Rattachements utilisateur → ressource (export CSV userResources). */
		public Map<String, List<String>> userLinks() {
			return users;
		}

		private List<JsonObject> where(Map<String, List<String>> links, String target) {
			final List<JsonObject> list = new ArrayList<>();
			for (Map.Entry<String, List<String>> e : links.entrySet()) {
				if (e.getValue().contains(target)) {
					list.add(resources.get(e.getKey()));
				}
			}
			return list;
		}
	}

	/** Ressources des classes d'un cours. */
	public static List<JsonObject> ofCourse(RosterDataset ds, Linked linked, String courseId) {
		final Map<String, JsonObject> result = new LinkedHashMap<>();
		for (JsonObject c : ds.classesWhere(c -> courseId.equals(RosterDataset.refId(c, "course")))) {
			for (JsonObject r : linked.ofClass(c.getString("sourcedId"))) {
				result.put(r.getString("sourcedId"), r);
			}
		}
		return new ArrayList<>(result.values());
	}

	public RosterService roster() {
		return roster;
	}
}
