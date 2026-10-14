package fr.openent.oneroster.csv;

import fr.openent.oneroster.core.Entities;
import fr.openent.oneroster.core.OneRosterException;
import fr.openent.oneroster.rostering.Fixtures;
import fr.openent.oneroster.rostering.RosterDataset;
import io.vertx.core.json.JsonObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CsvRoundTripTest {

	private final RosterDataset ds = Fixtures.dataset();

	private Map<String, String> exportFiles() {
		final JsonObject category = Entities.base("cat.1", Entities.ACTIVE, null).put("title", "Évaluation");
		return new CsvExport().roster(ds).gradebook(Collections.<JsonObject>emptyList(),
				Collections.<JsonObject>emptyList(), Collections.singletonList(category)).files("Open ENT");
	}

	@Test
	public void exportFollowsCsvBinding() {
		final Map<String, String> files = exportFiles();
		assertTrue(files.get("manifest.csv").contains("file.users,bulk"));
		assertTrue(files.get("manifest.csv").contains("file.lineItems,absent"));
		assertFalse("pas de fichier vide", files.containsKey("lineItems.csv"));
		assertTrue(files.get("users.csv").startsWith("sourcedId,status,dateLastModified,enabledUser,username,userIds,"
				+ "givenName,familyName,middleName,identifier,email,sms,phone,agentSourcedIds,grades,password,"
				+ "userMasterIdentifier,preferredGivenName,preferredMiddleName,preferredFamilyName,primaryOrgSourcedId,pronouns\r\n"));
		assertTrue(files.get("classes.csv").startsWith("sourcedId,status,dateLastModified,title,grades,courseSourcedId,"
				+ "classCode,classType,location,schoolSourcedId,termSourcedIds,subjects,subjectCodes,periods\r\n"));
		// mode bulk : status et dateLastModified vides, entités « tobedeleted » omises
		assertTrue(files.get("users.csv").contains("\r\nstu1,,,true,"));
		assertFalse(files.get("users.csv").contains("gone"));
		assertTrue(files.get("users.csv").contains("\"{externalId:EXT-stu1},{INE:123456789AB}\""));
		assertTrue(files.get("roles.csv").contains("stu1,primary,student,,,s1,"));
		assertTrue(files.get("classes.csv").contains("\"term.s1.3,term.s1.4,term.s1.5\""));
	}

	@Test
	public void exportIsReimportableIntoTheDirectory() throws Exception {
		final OneRosterSource source = OneRosterSource.fromZip(new CsvExport().roster(ds).zip("Open ENT"));
		assertEquals("s1", EntCsvConverter.chooseOrg(source, null));
		final Map<String, String> files = new EntCsvConverter(source, "s1").convert();
		assertEquals(Arrays.asList("Student", "Teacher", "Personnel", "Relative"), new java.util.ArrayList<>(files.keySet()));

		final List<List<String>> students = Csv.parse(files.get("Student"), ';');
		// La section « MATHEMATIQUES - 3ème-1 » recouvre la classe : seul le groupe de latin reste un regroupement.
		assertEquals(Arrays.asList("externalid", "nom", "prenom", "email", "datedenaissance", "ine", "classe",
				"regroupement"), students.get(0));
		final List<String> ines = row(students, "EXT-stu1");
		assertEquals("Baron", ines.get(1));
		assertEquals("20/05/2013", ines.get(4));
		assertEquals("123456789AB", ines.get(5));
		assertEquals("3ème-1", ines.get(6));
		assertFalse(ines.contains("MATHEMATIQUES - 3ème-1"));
		assertTrue(row(students, "EXT-stu2").contains("LATIN - 3 LATIN"));

		final List<List<String>> relatives = Csv.parse(files.get("Relative"), ';');
		assertEquals("idenfant", relatives.get(0).get(4));
		assertEquals("EXT-stu1", row(relatives, "EXT-par1").get(4));

		final List<List<String>> personnel = Csv.parse(files.get("Personnel"), ';');
		assertEquals("Leroy", row(personnel, "EXT-adm1").get(1));
		assertTrue(files.get("Student").startsWith("﻿"));
	}

	@Test
	public void severalSchoolsRequireAChoice() throws Exception {
		final OneRosterSource source = new OneRosterSource();
		for (String id : new String[] {"a", "b"}) {
			final OneRosterSource.Org org = new OneRosterSource.Org();
			org.sourcedId = id;
			org.type = "school";
			source.orgs.put(id, org);
		}
		try {
			EntCsvConverter.chooseOrg(source, null);
			fail();
		} catch (OneRosterException e) {
			assertEquals("several.schools.choose.orgSourcedId", e.getMessage());
		}
		assertEquals("b", EntCsvConverter.chooseOrg(source, "b"));
	}

	@Test
	public void oneRoster11UsersCsvIsAccepted() throws Exception {
		final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
			zip.putNextEntry(new java.util.zip.ZipEntry("export/users.csv"));
			zip.write(("sourcedId,status,dateLastModified,enabledUser,orgSourcedIds,role,username,userIds,givenName,familyName\n"
					+ "u1,,,true,org1,student,jdoe,{SIS:42},John,Doe\n"
					+ "u2,tobedeleted,2020-01-01,true,org1,student,old,,Old,Student\n").getBytes("UTF-8"));
			zip.closeEntry();
		}
		final OneRosterSource source = OneRosterSource.fromZip(out.toByteArray());
		assertEquals(1, source.users.size());
		final Map<String, String> files = new EntCsvConverter(source, "org1").convert();
		assertEquals("Doe", row(Csv.parse(files.get("Student"), ';'), "u1").get(1));
	}

	private static List<String> row(List<List<String>> rows, String id) {
		for (List<String> r : rows) {
			if (r.get(0).replace("﻿", "").equals(id)) {
				return r;
			}
		}
		throw new AssertionError("row " + id);
	}
}
