package fr.openent.oneroster.gradebook;

import fr.openent.oneroster.core.Entities;
import fr.openent.oneroster.core.OneRosterException;
import fr.openent.oneroster.core.Refs;
import fr.openent.oneroster.rostering.RosterBuilder;
import fr.openent.oneroster.rostering.RosterDataset;
import fr.openent.oneroster.rostering.RosterService;
import fr.openent.oneroster.security.Client;
import fr.openent.oneroster.service.Db;
import fr.openent.oneroster.service.GradebookRepository;
import fr.openent.oneroster.service.ObjectStore;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service Gradebook adossé au module Compétences : lineItems = devoirs (notes.devoirs),
 * results = notes (notes.notes, appréciation dans notes.appreciations), categories = types
 * d'évaluation (notes.type). Un client ne modifie que les devoirs et notes qu'il a créés.
 */
public class GradebookService {

	public static final String CATEGORY = "category";
	public static final String SCORE_SCALE = "scoreScale";
	public static final String ASSESSMENT_LINE_ITEM = "assessmentLineItem";
	public static final String ASSESSMENT_RESULT = "assessmentResult";

	private static final Pattern LOCAL_LINE_ITEM = Pattern.compile("^li\\.(\\d+)$");
	private static final Pattern LOCAL_RESULT = Pattern.compile("^res\\.(\\d+)$");
	private static final Pattern LOCAL_CATEGORY = Pattern.compile("^cat\\.(\\d+)$");
	private static final Pattern GRADING_PERIOD = Pattern.compile("^gp\\.([^.]+)\\.(\\d+)$");
	/** Champs OneRoster sans colonne dans notes.devoirs / notes.notes, conservés dans la correspondance. */
	private static final List<String> LINE_ITEM_EXTRA = Arrays.asList("category", "scoreScale", "learningObjectiveSet",
			"metadata", "academicSession");
	private static final List<String> RESULT_EXTRA = Arrays.asList("scoreScale", "textScore", "learningObjectiveSet",
			"metadata", "inProgress", "incomplete", "late", "missing", "scoreStatus");

	private final Refs refs;
	private final RosterService roster;
	private final GradebookRepository repository = new GradebookRepository();
	private final ObjectStore objects = new ObjectStore();

	public GradebookService(Refs refs, RosterService roster) {
		this.refs = refs;
		this.roster = roster;
	}

	public ObjectStore objects() {
		return objects;
	}

	// ================================================================= lecture

	public Future<List<JsonObject>> lineItems(Client client) {
		return repository.lineItems(client.getStructures()).map(rows -> {
			final List<JsonObject> list = new ArrayList<>();
			for (Object o : rows) {
				list.add(lineItem((JsonObject) o));
			}
			return list;
		});
	}

	public Future<JsonObject> lineItem(Client client, String sourcedId) {
		return resolveLineItem(sourcedId).compose(id -> id == null ? Future.succeededFuture((JsonObject) null)
				: repository.lineItem(id).map(row -> row != null && client.canSee(row.getString("structureId"))
						? lineItem(row) : null));
	}

	public Future<List<JsonObject>> results(Client client) {
		return repository.results(client.getStructures()).map(rows -> {
			final List<JsonObject> list = new ArrayList<>();
			for (Object o : rows) {
				list.add(result((JsonObject) o));
			}
			return list;
		});
	}

	public Future<JsonObject> result(Client client, String sourcedId) {
		return resolveResult(sourcedId).compose(id -> id == null ? Future.succeededFuture((JsonObject) null)
				: repository.result(id).map(row -> row != null && client.canSee(row.getString("structureId"))
						? result(row) : null));
	}

	/** Types d'évaluation des établissements du client, puis catégories déposées par le client. */
	public Future<List<JsonObject>> categories(Client client) {
		return repository.categories(client.getStructures()).compose(rows -> {
			final List<JsonObject> list = new ArrayList<>();
			for (Object o : rows) {
				final JsonObject row = (JsonObject) o;
				list.add(Entities.base("cat." + row.getValue("id"), Entities.ACTIVE, null)
						.put("title", row.getString("nom"))
						.put("metadata", new JsonObject().put("school", row.getString("structureId"))));
			}
			return objects.list(client.getId(), CATEGORY).map(own -> {
				list.addAll(own);
				return list;
			});
		});
	}

	public Future<JsonObject> category(Client client, String sourcedId) {
		return categories(client).map(list -> find(list, sourcedId));
	}

	private JsonObject lineItem(JsonObject row) {
		final String structureId = row.getString("structureId");
		final JsonObject data = mapData(row);
		final String sourcedId = row.getString("suppliedId") != null ? row.getString("suppliedId") : "li." + row.getValue("id");
		final String date = row.getString("date");
		final String due = row.getString("publicationDate") != null ? row.getString("publicationDate") : date;
		final JsonObject li = Entities.base(sourcedId, Entities.ACTIVE, row.getString("modified"))
				.put("title", row.getString("name"))
				.put("description", row.getString("libelle"))
				// Colonnes « date » : la journée seule compte, sans décalage horaire du serveur.
				.put("assignDate", date != null ? Entities.dateTime(Entities.date(date)) : null)
				.put("dueDate", due != null ? Entities.dateTime(Entities.date(due)) : null)
				.put("class", refs.ref("class", classOf(row)))
				.put("school", refs.ref("org", structureId))
				.put("category", refs.ref("category", "cat." + row.getValue("typeId")))
				.put("gradingPeriod", refs.ref("academicSession",
						RosterBuilder.gradingPeriodId(structureId, row.getValue("periodTypeId"))))
				.put("academicSession", refs.ref("academicSession", RosterBuilder.schoolYearId(structureId)))
				.put("resultValueMin", 0.0)
				.put("resultValueMax", number(row.getValue("diviseur")));
		for (String f : LINE_ITEM_EXTRA) {
			if (data.containsKey(f)) {
				li.put(f, data.getValue(f));
			}
		}
		return li;
	}

	private JsonObject result(JsonObject row) {
		final JsonObject data = mapData(row);
		final String sourcedId = row.getString("suppliedId") != null ? row.getString("suppliedId") : "res." + row.getValue("id");
		final String lineItemId = row.getString("lineItemSuppliedId") != null
				? row.getString("lineItemSuppliedId") : "li." + row.getValue("lineItemId");
		final String modified = row.getString("modified") != null ? row.getString("modified") : row.getString("created");
		final JsonObject result = Entities.base(sourcedId, Entities.ACTIVE, modified)
				.put("lineItem", refs.ref("lineItem", lineItemId))
				.put("student", refs.ref("user", row.getString("studentId")))
				.put("class", refs.ref("class", classOf(row)))
				.put("scoreStatus", "fully graded")
				.put("score", number(row.getValue("valeur")))
				.put("scoreDate", Entities.dateTime(modified));
		if (row.getString("comment") != null) {
			result.put("comment", row.getString("comment"));
		}
		for (String f : RESULT_EXTRA) {
			if (data.containsKey(f)) {
				result.put(f, data.getValue(f));
			}
		}
		return result;
	}

	/** Classe OneRoster d'un devoir : la section matière × groupe, ou le groupe seul sans matière. */
	private static String classOf(JsonObject row) {
		final String groupId = row.getString("groupId");
		final String subjectId = row.getString("subjectId");
		if (groupId == null) {
			return null;
		}
		return subjectId != null ? RosterBuilder.sectionId(subjectId, groupId) : groupId;
	}

	// ================================================================= écriture : lineItems

	/**
	 * Crée ou met à jour un lineItem (PUT /lineItems/{sourcedId}).
	 * @return {"created": bool, "lineItem": {...}}
	 */
	public Future<JsonObject> putLineItem(Client client, String sourcedId, JsonObject body) {
		if (body == null) {
			return fail(400, "missing.lineItem");
		}
		return repository.mapping(GradebookRepository.LINE_ITEM, sourcedId).compose(mapping -> {
			if (mapping != null) {
				if (!client.getId().equals(mapping.getString("client_id"))) {
					return fail(403, "lineItem.owned.by.another.client");
				}
				return updateLineItem(client, Long.parseLong(mapping.getString("local_id")), sourcedId, body)
						.map(li -> new JsonObject().put("created", false).put("lineItem", li));
			}
			if (LOCAL_LINE_ITEM.matcher(sourcedId).matches() || LOCAL_RESULT.matcher(sourcedId).matches()) {
				return fail(403, "lineItem.not.created.by.client");
			}
			return createLineItem(client, sourcedId, body)
					.map(li -> new JsonObject().put("created", true).put("lineItem", li));
		});
	}

	private Future<JsonObject> createLineItem(Client client, String sourcedId, JsonObject body) {
		return roster.dataset(client.getStructures()).compose(ds -> {
			final String classId = Refs.sourcedId(body.getValue("class"));
			final Section section = section(ds, classId);
			if (section == null) {
				return fail(422, "lineItem.class.must.be.a.subject.section");
			}
			final String owner = owner(ds, client, classId);
			if (owner == null) {
				return fail(422, "no.teacher.for.class");
			}
			return devoir(ds, section, body).compose(devoir -> {
				devoir.put("owner", owner);
				final JsonObject ownerUser = ds.user(owner);
				final String ownerName = ownerUser == null ? owner
						: ownerUser.getString("givenName", "") + " " + ownerUser.getString("familyName", "");
				return repository.nextLineItemId().compose(id -> repository.createLineItem(id, devoir,
						section.groupId, ds.clazz(section.groupId) != null ? 0 : 1, ownerName.trim(),
						client.getId(), sourcedId, extra(body, LINE_ITEM_EXTRA)).compose(v -> repository.lineItem(id)));
			});
		}).map(this::lineItem);
	}

	private Future<JsonObject> updateLineItem(Client client, long id, String sourcedId, JsonObject body) {
		return repository.lineItem(id).compose(row -> {
			if (row == null) {
				return fail(404, "lineItem.not.found");
			}
			final String classId = Refs.sourcedId(body.getValue("class"));
			if (classId != null && !classId.equals(classOf(row))) {
				return fail(422, "lineItem.class.cannot.change");
			}
			return roster.dataset(client.getStructures()).compose(ds -> {
				final Section section = new Section(row.getString("subjectId"), row.getString("groupId"),
						row.getString("structureId"));
				return devoir(ds, section, body).compose(devoir ->
						repository.updateLineItem(id, devoir, sourcedId, extra(body, LINE_ITEM_EXTRA)));
			}).compose(v -> repository.lineItem(id));
		}).map(this::lineItem);
	}

	/** Colonnes de notes.devoirs déduites d'un lineItem. */
	private Future<JsonObject> devoir(RosterDataset ds, Section section, JsonObject body) {
		final String title = body.getString("title");
		if (title == null || title.trim().isEmpty()) {
			return fail(422, "lineItem.title.required");
		}
		final Number max = body.getValue("resultValueMax") instanceof Number ? body.getNumber("resultValueMax") : 20;
		if (max.doubleValue() <= 0) {
			return fail(422, "lineItem.resultValueMax.invalid");
		}
		final String assign = Entities.date(body.getString("assignDate"));
		final String due = Entities.date(body.getString("dueDate"));
		final String date = assign != null ? assign : LocalDate.now().toString();
		final Long periodTypeId = periodType(ds, section, Refs.sourcedId(body.getValue("gradingPeriod")), date);
		if (periodTypeId == null) {
			return fail(422, "no.grading.period.for.date");
		}
		final String description = body.getString("description");
		final JsonObject devoir = new JsonObject()
				.put("name", truncate(title.trim(), 255))
				.put("libelle", description != null ? truncate(description, 255) : null)
				.put("id_periode", periodTypeId)
				.put("id_etablissement", section.structureId)
				.put("diviseur", max)
				.put("id_matiere", section.subjectId)
				.put("date", date)
				.put("date_publication", due != null ? due : date);
		final Matcher category = LOCAL_CATEGORY.matcher(String.valueOf(Refs.sourcedId(body.getValue("category"))));
		if (category.matches()) {
			return Future.succeededFuture(devoir.put("id_type", Long.valueOf(category.group(1))));
		}
		return repository.defaultCategory(section.structureId).compose(type -> type == null
				? fail(422, "no.evaluation.type.for.school")
				: Future.succeededFuture(devoir.put("id_type", type.getLong("id"))));
	}

	/**
	 * Type de période (viesco.rel_type_periode.id) : celui de la période de notation désignée,
	 * sinon la période de la classe (ou de l'établissement) qui contient la date du devoir.
	 */
	private static Long periodType(RosterDataset ds, Section section, String gradingPeriodId, String date) {
		if (gradingPeriodId != null) {
			final Matcher m = GRADING_PERIOD.matcher(gradingPeriodId);
			return m.matches() && m.group(1).equals(section.structureId) && ds.academicSession(gradingPeriodId) != null
					? Long.valueOf(m.group(2)) : null;
		}
		final JsonObject group = ds.clazz(section.groupId);
		final List<String> candidates = new ArrayList<>();
		if (group != null) {
			for (Object t : group.getJsonArray("terms", new JsonArray())) {
				candidates.add(((JsonObject) t).getString("sourcedId"));
			}
		}
		if (candidates.isEmpty()) {
			for (JsonObject s : ds.sessionsOfType("term")) {
				if (section.structureId.equals(RosterDataset.refId(s, "org"))) {
					candidates.add(s.getString("sourcedId"));
				}
			}
		}
		for (String termId : candidates) {
			final JsonObject term = ds.academicSession(termId);
			if (term != null && "term".equals(term.getString("type"))
					&& date.compareTo(term.getString("startDate", "")) >= 0
					&& date.compareTo(term.getString("endDate", "")) <= 0) {
				return Long.valueOf(termId.substring(termId.lastIndexOf('.') + 1));
			}
		}
		return null;
	}

	/** Enseignant propriétaire : un enseignant inscrit dans la section, sinon le propriétaire par défaut du client. */
	private static String owner(RosterDataset ds, Client client, String classId) {
		for (JsonObject e : ds.enrollmentsWhere(e -> classId.equals(RosterDataset.refId(e, "class"))
				&& "teacher".equals(e.getString("role")))) {
			return RosterDataset.refId(e, "user");
		}
		return client.getDefaultOwner();
	}

	public Future<Void> deleteLineItem(Client client, String sourcedId) {
		return ownedMapping(client, GradebookRepository.LINE_ITEM, sourcedId)
				.compose(m -> repository.deleteLineItem(Long.parseLong(m.getString("local_id")), sourcedId))
				.mapEmpty();
	}

	// ================================================================= écriture : results

	/**
	 * Crée ou met à jour une note (PUT /results/{sourcedId}). Le lineItem doit avoir été créé
	 * par le client ; l'élève doit appartenir à la classe du devoir. Retourne null quand le
	 * statut (exempt, not submitted) ne laisse pas de note.
	 */
	public Future<JsonObject> putResult(Client client, String sourcedId, JsonObject body, String forcedLineItem) {
		if (body == null) {
			return fail(400, "missing.result");
		}
		final String lineItemRef = forcedLineItem != null ? forcedLineItem : Refs.sourcedId(body.getValue("lineItem"));
		final String studentId = Refs.sourcedId(body.getValue("student"));
		if (lineItemRef == null || studentId == null) {
			return fail(422, "result.lineItem.and.student.required");
		}
		if (LOCAL_RESULT.matcher(sourcedId).matches()) {
			return fail(403, "result.not.created.by.client");
		}
		return ownedMapping(client, GradebookRepository.LINE_ITEM, lineItemRef).compose(lineItemMap ->
				repository.mapping(GradebookRepository.RESULT, sourcedId).compose(resultMap -> {
			if (resultMap != null && !client.getId().equals(resultMap.getString("client_id"))) {
				return fail(403, "result.owned.by.another.client");
			}
			final long devoirId = Long.parseLong(lineItemMap.getString("local_id"));
			return repository.lineItem(devoirId).compose(devoir -> roster.dataset(client.getStructures()).compose(ds -> {
				final String groupId = devoir.getString("groupId");
				if (!enrolledStudent(ds, devoir.getString("subjectId"), groupId, studentId)) {
					return fail(422, "student.not.in.class");
				}
				return checkOpen(ds, groupId, devoir.getLong("periodTypeId"))
						.compose(v -> repository.noteOf(devoirId, studentId))
						.compose(note -> save(client, sourcedId, body, devoir, studentId, note, resultMap));
			}));
		}));
	}

	private Future<JsonObject> save(Client client, String sourcedId, JsonObject body, JsonObject devoir,
			String studentId, JsonObject note, JsonObject resultMap) {
		final long devoirId = devoir.getLong("id");
		final Long noteId = note != null ? note.getLong("id") : null;
		if (resultMap != null && noteId != null && !String.valueOf(noteId).equals(resultMap.getString("local_id"))) {
			return fail(422, "result.sourcedId.points.to.another.student");
		}
		final String status = body.getString("scoreStatus", "fully graded");
		final Object score = body.getValue("score");
		final String comment = body.getString("comment");
		if (!(score instanceof Number)) {
			if ("exempt".equals(status) || "not submitted".equals(status)) {
				return repository.clearResult(noteId, devoirId, studentId, devoir.getString("owner"), comment)
						.map(v -> (JsonObject) null);
			}
			return fail(422, "result.score.required");
		}
		final double value = ((Number) score).doubleValue();
		final Number max = number(devoir.getValue("diviseur"));
		if (value < 0 || (max != null && value > max.doubleValue())) {
			return fail(422, "result.score.out.of.range");
		}
		return repository.saveResult(noteId, devoirId, studentId, (Number) score, devoir.getString("owner"), comment,
				client.getId(), sourcedId, extra(body, RESULT_EXTRA), resultMap != null)
				.compose(repository::result).map(this::result);
	}

	/** Saisie fermée sur la période (date de fin de saisie dépassée) : refus. */
	private Future<Void> checkOpen(RosterDataset ds, String groupId, Long periodTypeId) {
		if (periodTypeId == null || ds.clazz(groupId) == null) {
			return Future.succeededFuture();
		}
		return repository.gradingClosed(groupId, periodTypeId)
				.compose(closed -> closed ? fail(422, "grading.period.closed") : Future.<Void>succeededFuture());
	}

	private static boolean enrolledStudent(RosterDataset ds, String subjectId, String groupId, String studentId) {
		for (String classId : new String[] {groupId, subjectId != null ? RosterBuilder.sectionId(subjectId, groupId) : null}) {
			if (classId == null) {
				continue;
			}
			final JsonObject e = ds.enrollment(classId + "." + studentId);
			if (e != null && "student".equals(e.getString("role"))) {
				return true;
			}
		}
		return false;
	}

	public Future<Void> deleteResult(Client client, String sourcedId) {
		return ownedMapping(client, GradebookRepository.RESULT, sourcedId)
				.compose(m -> repository.result(Long.parseLong(m.getString("local_id"))).compose(row -> row == null
						? fail(404, "result.not.found")
						: repository.deleteResult(row.getLong("id"), row.getLong("lineItemId"), row.getString("studentId"),
								sourcedId)))
				.mapEmpty();
	}

	// ================================================================= objets propres au client

	public Future<Boolean> putObject(Client client, String kind, String sourcedId, JsonObject body) {
		if (body == null) {
			return fail(400, "missing." + kind);
		}
		if (CATEGORY.equals(kind) && LOCAL_CATEGORY.matcher(sourcedId).matches()) {
			return fail(403, "category.not.created.by.client");
		}
		final JsonObject data = body.copy()
				.put("sourcedId", sourcedId)
				.put("status", body.getString("status", Entities.ACTIVE))
				.put("dateLastModified", Entities.nowIso());
		return objects.put(client.getId(), kind, sourcedId, data);
	}

	public Future<Void> deleteObject(Client client, String kind, String sourcedId) {
		return objects.delete(client.getId(), kind, sourcedId)
				.compose(deleted -> deleted ? Future.<Void>succeededFuture() : fail(404, kind + ".not.found"));
	}

	// ================================================================= utilitaires

	private Future<Long> resolveLineItem(String sourcedId) {
		final Matcher m = LOCAL_LINE_ITEM.matcher(sourcedId);
		if (m.matches()) {
			return Future.succeededFuture(Long.valueOf(m.group(1)));
		}
		return repository.mapping(GradebookRepository.LINE_ITEM, sourcedId)
				.map(row -> row == null ? null : Long.valueOf(row.getString("local_id")));
	}

	private Future<Long> resolveResult(String sourcedId) {
		final Matcher m = LOCAL_RESULT.matcher(sourcedId);
		if (m.matches()) {
			return Future.succeededFuture(Long.valueOf(m.group(1)));
		}
		return repository.mapping(GradebookRepository.RESULT, sourcedId)
				.map(row -> row == null ? null : Long.valueOf(row.getString("local_id")));
	}

	/** Correspondance créée par ce client ; 404 si inconnue, 403 si créée par un autre client ou par l'ENT. */
	private Future<JsonObject> ownedMapping(Client client, String kind, String sourcedId) {
		return repository.mapping(kind, sourcedId).compose(m -> {
			if (m == null) {
				final boolean local = LOCAL_LINE_ITEM.matcher(sourcedId).matches() || LOCAL_RESULT.matcher(sourcedId).matches();
				return local ? fail(403, kind + ".not.created.by.client") : fail(404, kind + ".not.found");
			}
			if (!client.getId().equals(m.getString("client_id"))) {
				return fail(403, kind + ".owned.by.another.client");
			}
			return Future.succeededFuture(m);
		});
	}

	/** Section (matière × groupe) désignée par un sourcedId de classe, si elle est dans le périmètre. */
	static Section section(RosterDataset ds, String classId) {
		if (classId == null) {
			return null;
		}
		final int dot = classId.indexOf('.');
		if (dot <= 0) {
			return null;
		}
		final String subjectId = classId.substring(0, dot);
		final String groupId = classId.substring(dot + 1);
		final String structureId = ds.structureOfGroup(groupId);
		final JsonObject course = ds.course(subjectId);
		if (structureId == null || course == null || !structureId.equals(RosterDataset.refId(course, "org"))) {
			return null;
		}
		return new Section(subjectId, groupId, structureId);
	}

	private static JsonObject mapData(JsonObject row) {
		final JsonObject data = Db.jsonObject(row, "mapData");
		return data != null ? data : new JsonObject();
	}

	private static JsonObject extra(JsonObject body, List<String> fields) {
		final JsonObject data = new JsonObject();
		for (String f : fields) {
			if (body.containsKey(f) && body.getValue(f) != null) {
				data.put(f, body.getValue(f));
			}
		}
		return data;
	}

	private static JsonObject find(List<JsonObject> list, String sourcedId) {
		for (JsonObject o : list) {
			if (sourcedId.equals(o.getString("sourcedId"))) {
				return o;
			}
		}
		return null;
	}

	private static Number number(Object value) {
		if (value instanceof Number) {
			return ((Number) value).doubleValue();
		}
		if (value instanceof String) {
			try {
				return Double.valueOf((String) value);
			} catch (NumberFormatException e) {
				return null;
			}
		}
		return null;
	}

	private static String truncate(String s, int max) {
		return s.length() <= max ? s : s.substring(0, max);
	}

	private static <T> Future<T> fail(int status, String message) {
		return Future.failedFuture(new OneRosterException(status, message));
	}

	static final class Section {
		final String subjectId;
		final String groupId;
		final String structureId;

		Section(String subjectId, String groupId, String structureId) {
			this.subjectId = subjectId;
			this.groupId = groupId;
			this.structureId = structureId;
		}
	}
}
