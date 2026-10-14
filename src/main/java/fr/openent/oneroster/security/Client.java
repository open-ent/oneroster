package fr.openent.oneroster.security;

import fr.openent.oneroster.service.Db;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Client OneRoster authentifié : son identifiant, les établissements de son périmètre et
 * les scopes du jeton présenté.
 */
public final class Client {

	private final String id;
	private final List<String> structures;
	private final List<String> scopes;
	private final String defaultOwner;

	public Client(String id, List<String> structures, List<String> scopes, String defaultOwner) {
		this.id = id;
		this.structures = Collections.unmodifiableList(new ArrayList<>(structures));
		this.scopes = Collections.unmodifiableList(new ArrayList<>(scopes));
		this.defaultOwner = defaultOwner;
	}

	/** Client construit depuis une ligne oneroster.client, avec les scopes du jeton. */
	public static Client fromRow(JsonObject row, List<String> tokenScopes) {
		return new Client(row.getString("id"), strings(Db.jsonArray(row, "structures")), tokenScopes,
				row.getString("default_owner"));
	}

	/** Périmètre d'un administrateur (export CSV) : tous les scopes sur les établissements donnés. */
	public static Client admin(String userId, List<String> structures) {
		return new Client("admin:" + userId, structures, Scopes.ALL, null);
	}

	public String getId() {
		return id;
	}

	public List<String> getStructures() {
		return structures;
	}

	public JsonArray structuresJson() {
		return new JsonArray(new ArrayList<Object>(structures));
	}

	public String getDefaultOwner() {
		return defaultOwner;
	}

	public boolean canSee(String structureId) {
		return structureId != null && structures.contains(structureId);
	}

	/** Vrai si le jeton porte au moins un des scopes acceptés. */
	public boolean hasAny(String... accepted) {
		for (String s : accepted) {
			if (scopes.contains(s)) {
				return true;
			}
		}
		return false;
	}

	public static List<String> strings(JsonArray array) {
		final List<String> list = new ArrayList<>();
		if (array != null) {
			for (Object o : array) {
				if (o instanceof String && !((String) o).isEmpty() && !list.contains(o)) {
					list.add((String) o);
				}
			}
		}
		return list;
	}
}
