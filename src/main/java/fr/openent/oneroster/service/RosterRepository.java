package fr.openent.oneroster.service;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * Lectures brutes de l'annuaire (Neo4j 3.1 : paramètres {x}, pas de « pattern comprehension »)
 * et des réglages de vie scolaire (PostgreSQL), toujours restreintes à une liste d'établissements.
 */
public class RosterRepository {

	public Future<JsonArray> structures(List<String> ids) {
		return Db.neo4j(
				"MATCH (s:Structure) WHERE s.id IN {ids} " +
				"OPTIONAL MATCH (s)-[:HAS_ATTACHMENT]->(p:Structure) " +
				"RETURN s.id AS id, s.name AS name, s.UAI AS uai, s.externalId AS externalId, " +
				"s.modified AS modified, s.created AS created, collect(DISTINCT p.id) AS parents",
				params(ids));
	}

	/** Établissements de rattachement (collectivités, DSDEN…) exposés comme orgs « district ». */
	public Future<JsonArray> parentStructures(List<String> parentIds) {
		return Db.neo4j(
				"MATCH (p:Structure) WHERE p.id IN {ids} " +
				"RETURN p.id AS id, p.name AS name, p.UAI AS uai, p.externalId AS externalId, " +
				"p.modified AS modified, p.created AS created",
				params(parentIds));
	}

	public Future<JsonArray> users(List<String> ids) {
		return Db.neo4j(
				"MATCH (s:Structure)<-[:DEPENDS]-(:ProfileGroup)<-[:IN]-(u:User) WHERE s.id IN {ids} " +
				"WITH u, collect(DISTINCT s.id) AS structureIds " +
				"OPTIONAL MATCH (u)-[:RELATED]->(r:User) " +
				"WITH u, structureIds, collect(DISTINCT r.id) AS relatives " +
				"OPTIONAL MATCH (c:User)-[:RELATED]->(u) " +
				"WITH u, structureIds, relatives, collect(DISTINCT c.id) AS children " +
				"OPTIONAL MATCH (u)-[rf:HAS_FUNCTION]->(:Function {externalId: 'ADMIN_LOCAL'}) " +
				"RETURN u.id AS id, u.externalId AS externalId, u.login AS login, u.firstName AS firstName, " +
				"u.lastName AS lastName, u.email AS email, u.emailAcademy AS emailAcademy, u.mobile AS mobile, " +
				"u.homePhone AS homePhone, head(u.profiles) AS profile, u.birthDate AS birthDate, u.ine AS ine, " +
				"u.level AS level, u.modified AS modified, u.created AS created, " +
				"u.disappearanceDate AS disappearanceDate, u.deleteDate AS deleteDate, u.blocked AS blocked, " +
				"structureIds, relatives, children, coalesce(head(collect(rf.scope)), []) AS admlScope",
				params(ids));
	}

	public Future<JsonArray> classes(List<String> ids) {
		return Db.neo4j(
				"MATCH (s:Structure)<-[:BELONGS]-(c:Class) WHERE s.id IN {ids} " +
				"RETURN c.id AS id, c.name AS name, c.externalId AS externalId, c.level AS level, s.id AS structureId",
				params(ids));
	}

	/** Membres des classes, avec le profil et l'indicateur de professeur principal. */
	public Future<JsonArray> classMembers(List<String> ids) {
		return Db.neo4j(
				"MATCH (s:Structure)<-[:BELONGS]-(c:Class)<-[:DEPENDS]-(:ProfileGroup)<-[:IN]-(u:User) " +
				"WHERE s.id IN {ids} " +
				"RETURN DISTINCT c.id AS classId, u.id AS userId, head(u.profiles) AS profile, " +
				"(c.externalId IN coalesce(u.headTeacher, []) OR c.externalId IN coalesce(u.headTeacherManual, [])) " +
				"AS headTeacher",
				params(ids));
	}

	/** Groupes de profil des classes (élèves, enseignants…) : cibles des partages de ressources. */
	public Future<JsonArray> classProfileGroups(List<String> ids) {
		return Db.neo4j(
				"MATCH (s:Structure)<-[:BELONGS]-(c:Class)<-[:DEPENDS]-(pg:ProfileGroup) WHERE s.id IN {ids} " +
				"RETURN pg.id AS groupId, c.id AS classId",
				params(ids));
	}

	public Future<JsonArray> functionalGroups(List<String> ids) {
		return Db.neo4j(
				"MATCH (s:Structure)<-[:DEPENDS]-(g:FunctionalGroup) WHERE s.id IN {ids} " +
				"RETURN g.id AS id, g.name AS name, g.externalId AS externalId, s.id AS structureId",
				params(ids));
	}

	public Future<JsonArray> functionalGroupMembers(List<String> ids) {
		return Db.neo4j(
				"MATCH (s:Structure)<-[:DEPENDS]-(g:FunctionalGroup)<-[:IN]-(u:User) WHERE s.id IN {ids} " +
				"RETURN DISTINCT g.id AS groupId, u.id AS userId, head(u.profiles) AS profile",
				params(ids));
	}

	public Future<JsonArray> subjects(List<String> ids) {
		return Db.neo4j(
				"MATCH (s:Structure)<-[:SUBJECT]-(sub:Subject) WHERE s.id IN {ids} " +
				"RETURN sub.id AS id, sub.code AS code, sub.label AS label, s.id AS structureId, " +
				"sub.lastUpdated AS modified",
				params(ids));
	}

	/** Enseignements issus de l'annuaire (AAF, emploi du temps) : classes et groupes en externalId. */
	public Future<JsonArray> teaches(List<String> ids) {
		return Db.neo4j(
				"MATCH (t:User)-[r:TEACHES]->(sub:Subject)-[:SUBJECT]->(s:Structure) WHERE s.id IN {ids} " +
				"RETURN t.id AS teacherId, sub.id AS subjectId, s.id AS structureId, " +
				"coalesce(r.classes, []) + coalesce(r.groups, []) AS groupExternalIds",
				params(ids));
	}

	/** Services d'enseignement saisis dans la vie scolaire (enseignant × matière × classe ou groupe). */
	public Future<JsonArray> services(List<String> ids) {
		return Db.sqlOptional(
				"SELECT id_etablissement AS \"structureId\", id_enseignant AS \"teacherId\", " +
				"id_matiere AS \"subjectId\", id_groupe AS \"groupId\" FROM viesco.services " +
				"WHERE is_visible AND id_etablissement IN (" + Db.placeholders(ids.size()) + ")",
				new JsonArray(new java.util.ArrayList<Object>(ids)));
	}

	/** Années scolaires paramétrées (viesco.setting_period, code YEAR). */
	public Future<JsonArray> schoolYears(List<String> ids) {
		return Db.sqlOptional(
				"SELECT id_structure AS \"structureId\", start_date AS \"startDate\", end_date AS \"endDate\", " +
				"description FROM viesco.setting_period WHERE code = 'YEAR' " +
				"AND id_structure IN (" + Db.placeholders(ids.size()) + ") ORDER BY start_date",
				new JsonArray(new java.util.ArrayList<Object>(ids)));
	}

	/**
	 * Périodes de notation (trimestres, semestres) : une ligne par établissement et type de période,
	 * avec les bornes les plus larges parmi les classes, et la liste des classes concernées.
	 */
	public Future<JsonArray> periods(List<String> ids) {
		return Db.sqlOptional(
				"SELECT p.id_etablissement AS \"structureId\", p.id_type AS \"periodTypeId\", r.type, r.ordre, " +
				"min(p.timestamp_dt) AS \"startDate\", max(p.timestamp_fn) AS \"endDate\", " +
				"json_agg(DISTINCT p.id_classe) AS classes " +
				"FROM viesco.periode p JOIN viesco.rel_type_periode r ON r.id = p.id_type " +
				"WHERE p.id_etablissement IN (" + Db.placeholders(ids.size()) + ") " +
				"GROUP BY p.id_etablissement, p.id_type, r.type, r.ordre ORDER BY r.type, r.ordre",
				new JsonArray(new java.util.ArrayList<Object>(ids)));
	}

	private static JsonObject params(List<String> ids) {
		return new JsonObject().put("ids", new JsonArray(new java.util.ArrayList<Object>(ids)));
	}
}
