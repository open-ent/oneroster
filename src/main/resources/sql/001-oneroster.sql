CREATE SCHEMA IF NOT EXISTS oneroster;

CREATE TABLE oneroster.scripts (
	filename VARCHAR(255) NOT NULL PRIMARY KEY,
	passed TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Clients OAuth 2.0 (client credentials) de l'API OneRoster ; id = client_id.
-- Seule l'empreinte SHA-256 du secret est conservée.
CREATE TABLE oneroster.client (
	id VARCHAR(64) NOT NULL PRIMARY KEY,
	name VARCHAR(255) NOT NULL,
	secret_hash VARCHAR(128) NOT NULL,
	-- établissements (id Neo4j) visibles par le client
	structures JSONB NOT NULL DEFAULT '[]',
	-- scopes OneRoster autorisés
	scopes JSONB NOT NULL DEFAULT '[]',
	-- enseignant propriétaire des devoirs créés par le client quand aucun enseignant de la section n'est trouvé
	default_owner VARCHAR(36),
	enabled BOOLEAN NOT NULL DEFAULT TRUE,
	created_by VARCHAR(36),
	created_at TIMESTAMP NOT NULL DEFAULT NOW(),
	updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Identifiants fournis par un client (sourcedId) pour les objets qu'il crée dans l'ENT
-- (devoirs, notes) ; data garde les champs OneRoster sans équivalent dans le module Compétences.
CREATE TABLE oneroster.sourced_id_map (
	kind VARCHAR(32) NOT NULL,
	sourced_id VARCHAR(255) NOT NULL,
	client_id VARCHAR(64) NOT NULL REFERENCES oneroster.client(id) ON DELETE CASCADE,
	local_id VARCHAR(64) NOT NULL,
	data JSONB NOT NULL DEFAULT '{}',
	created_at TIMESTAMP NOT NULL DEFAULT NOW(),
	updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
	PRIMARY KEY (kind, sourced_id)
);
CREATE UNIQUE INDEX sourced_id_map_local ON oneroster.sourced_id_map (kind, local_id);

-- Objets Gradebook sans équivalent dans l'ENT (catégories, barèmes, évaluations hors classe),
-- conservés tels quels et visibles du seul client qui les a déposés.
CREATE TABLE oneroster.object (
	client_id VARCHAR(64) NOT NULL REFERENCES oneroster.client(id) ON DELETE CASCADE,
	kind VARCHAR(32) NOT NULL,
	sourced_id VARCHAR(255) NOT NULL,
	data JSONB NOT NULL,
	updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
	PRIMARY KEY (client_id, kind, sourced_id)
);

-- Imports OneRoster (CSV ou fournisseur REST) vers l'annuaire.
CREATE TABLE oneroster.import (
	id VARCHAR(36) NOT NULL PRIMARY KEY,
	structure_id VARCHAR(36) NOT NULL,
	source VARCHAR(16) NOT NULL,
	status VARCHAR(16) NOT NULL,
	report JSONB NOT NULL DEFAULT '{}',
	created_by VARCHAR(36),
	created_at TIMESTAMP NOT NULL DEFAULT NOW(),
	finished_at TIMESTAMP
);
CREATE INDEX import_structure ON oneroster.import (structure_id, created_at DESC);
