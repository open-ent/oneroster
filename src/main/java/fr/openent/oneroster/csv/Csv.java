package fr.openent.oneroster.csv;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lecture et écriture CSV (RFC 4180), avec séparateur paramétrable. */
public final class Csv {

	private Csv() {
	}

	/** Une ligne CSV ; les champs contenant séparateur, guillemet ou saut de ligne sont mis entre guillemets. */
	public static String line(List<String> values, char separator, boolean quoteAll) {
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < values.size(); i++) {
			if (i > 0) {
				sb.append(separator);
			}
			final String v = values.get(i) == null ? "" : values.get(i);
			if (quoteAll || v.indexOf(separator) >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
				sb.append('"').append(v.replace("\"", "\"\"")).append('"');
			} else {
				sb.append(v);
			}
		}
		return sb.append("\r\n").toString();
	}

	/** Lignes d'un document CSV (BOM UTF-8 ignoré, lignes vides ignorées). */
	public static List<List<String>> parse(String content, char separator) {
		final List<List<String>> rows = new ArrayList<>();
		String s = content;
		if (s.startsWith("﻿")) {
			s = s.substring(1);
		}
		List<String> row = new ArrayList<>();
		final StringBuilder field = new StringBuilder();
		boolean quoted = false;
		boolean fieldStarted = false;
		for (int i = 0; i < s.length(); i++) {
			final char c = s.charAt(i);
			if (quoted) {
				if (c == '"') {
					if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
						field.append('"');
						i++;
					} else {
						quoted = false;
					}
				} else {
					field.append(c);
				}
			} else if (c == '"' && !fieldStarted) {
				quoted = true;
				fieldStarted = true;
			} else if (c == separator) {
				row.add(field.toString());
				field.setLength(0);
				fieldStarted = false;
			} else if (c == '\n' || c == '\r') {
				if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
					i++;
				}
				row.add(field.toString());
				field.setLength(0);
				fieldStarted = false;
				addRow(rows, row);
				row = new ArrayList<>();
			} else {
				field.append(c);
				fieldStarted = true;
			}
		}
		if (fieldStarted || field.length() > 0 || !row.isEmpty()) {
			row.add(field.toString());
			addRow(rows, row);
		}
		return rows;
	}

	/** Lignes sous forme d'objets indexés par en-tête (première ligne). */
	public static List<Map<String, String>> records(String content) {
		final List<List<String>> rows = parse(content, ',');
		final List<Map<String, String>> records = new ArrayList<>();
		if (rows.isEmpty()) {
			return records;
		}
		final List<String> header = rows.get(0);
		for (int r = 1; r < rows.size(); r++) {
			final Map<String, String> record = new LinkedHashMap<>();
			final List<String> row = rows.get(r);
			for (int i = 0; i < header.size(); i++) {
				record.put(header.get(i).trim(), i < row.size() ? row.get(i) : "");
			}
			records.add(record);
		}
		return records;
	}

	/** Valeur multiple OneRoster (« a,b,c ») → liste, éléments vides ignorés. */
	public static List<String> list(String value) {
		final List<String> list = new ArrayList<>();
		if (value != null) {
			for (String v : value.split(",")) {
				if (!v.trim().isEmpty()) {
					list.add(v.trim());
				}
			}
		}
		return list;
	}

	private static void addRow(List<List<String>> rows, List<String> row) {
		for (String v : row) {
			if (!v.isEmpty()) {
				rows.add(row);
				return;
			}
		}
	}
}
