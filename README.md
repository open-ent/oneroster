# Open ENT — module `oneroster` (OneRoster 1.2)

Module Vert.x qui implémente la norme [1EdTech OneRoster 1.2](https://standards.1edtech.org/oneroster/?view=category)
pour Open ENT, dans les deux sens :

- **fournisseur** : un outil tiers (LMS, éditeur, logiciel de vie scolaire) lit l'annuaire de l'ENT
  (établissements, classes, élèves, enseignants, inscriptions), les notes du module Compétences et
  les ressources du médiacentre, et peut déposer ses propres devoirs et notes ;
- **consommateur** : une archive CSV OneRoster, ou un fournisseur OneRoster REST, alimente
  l'annuaire d'un établissement en passant par le feeder, comme un import CSV de la console
  d'administration.

| Spécification | Support |
|---|---|
| Rostering Service 1.2, binding REST/JSON (41 routes GET) | ✅ lecture |
| Gradebook Service 1.2, binding REST/JSON (35 routes) | ✅ lecture et écriture |
| Resources Service 1.2, binding REST/JSON (5 routes) | ✅ lecture |
| CSV Binding 1.2.1 | ✅ export (bulk), import (1.2 et 1.1) |
| OAuth 2.0 client credentials (`client_secret_basic`, `client_secret_post`), scopes 1.2 | ✅ |
| Pagination `limit`/`offset`, `X-Total-Count`, `Link` ; `filter`, `sort`/`orderBy`, `fields` | ✅ |
| Erreurs `imsx_StatusInfo` (`imsx_codeMajor`, `imsx_CodeMinor`) | ✅ |

## Architecture

```
fr.openent.oneroster
├── OneRoster.java              BaseServer : schéma SQL, stockage, contrôleurs
├── OneRosterContext.java       configuration et dépendances partagées
├── api/                        Query (pagination, tri, projection), Filter (grammaire filter), Http (réponses, Link, erreurs)
├── core/                       Entities (champs, dates, niveaux CEDS), Refs (GUIDRef), OneRosterException
├── security/                   jetons RS256 (JDK seul), secrets clients, scopes, authentification Bearer
├── rostering/                  RosterBuilder (conversion pure, testée) → RosterDataset, RosterService (cache)
├── gradebook/                  GradebookService : devoirs, notes, appréciations, types d'évaluation
├── resources/                  ResourceService : signets du médiacentre et leurs partages
├── csv/                        CsvExport, OneRosterSource (ZIP/REST), EntCsvConverter, ImportService, RemoteProvider
├── service/                    accès Neo4j / PostgreSQL (Db, RosterRepository, GradebookRepository, ClientRepository, ObjectStore)
└── controller/                 Token, Rostering, Gradebook, Resources, Admin
```

Aucune dépendance ajoutée au-delà de `org.entcore:common`. Les routes de l'API sont servies sous
les préfixes imposés par la norme (`/ims/oneroster/rostering/v1p2`, `/ims/oneroster/gradebook/v1p2`,
`/ims/oneroster/resources/v1p2`) ; le jeton et l'administration sont sous `/oneroster`. Les deux
préfixes doivent être routés vers le port du module (8171).

## Authentification

1. Un super administrateur crée un **client** (`POST /oneroster/admin/clients`) : nom,
   établissements visibles, scopes autorisés, enseignant propriétaire par défaut des devoirs
   (facultatif). Le secret n'est affiché qu'une fois ; seule son empreinte SHA-256 est conservée.
2. L'outil demande un jeton :

   ```
   POST /oneroster/oauth/token
   Authorization: Basic base64(client_id:client_secret)
   Content-Type: application/x-www-form-urlencoded

   grant_type=client_credentials&scope=https://purl.imsglobal.org/spec/or/v1p2/scope/roster.readonly
   ```

   Sans `scope`, le jeton porte tous les scopes autorisés du client.
3. Il appelle l'API avec `Authorization: Bearer <jeton>`. Le jeton est un JWT RS256 signé par
   le module (`oneroster.private-key`). À chaque appel, le client doit être toujours actif ; un
   scope retiré depuis l'émission du jeton cesse d'être accepté.

| Scope (`https://purl.imsglobal.org/spec/or/v1p2/scope/…`) | Donne accès à |
|---|---|
| `roster-core.readonly` | collections et lectures simples du Rostering (hors demographics) |
| `roster.readonly` | tout le Rostering, routes imbriquées comprises (hors demographics) |
| `roster-demographics.readonly` | `/demographics` (date de naissance) |
| `gradebook-core.readonly`, `gradebook.readonly` | lecture du Gradebook (core : lineItems, results, categories, scoreScales) |
| `gradebook.createput`, `gradebook.createpost`, `gradebook.delete` | écriture du Gradebook |
| `assessment.readonly`, `assessment.createput`, `assessment.delete` | assessmentLineItems / assessmentResults |
| `resource-core.readonly`, `resource.readonly` | Resources |

Un client ne voit que les établissements de son périmètre : toute entité hors périmètre répond 404.

## Correspondances

| OneRoster | Open ENT |
|---|---|
| org `school` | `Structure` (identifier = UAI) |
| org `district` | structure de rattachement (`HAS_ATTACHMENT` : DSDEN, collectivité…) |
| academicSession `schoolYear` (`sy.<structure>`) | `viesco.setting_period` (code YEAR), sinon étendue des périodes, sinon 1er septembre – 31 août |
| `term` (`term.<structure>.<type>`) / `gradingPeriod` (`gp.<structure>.<type>`) | trimestres et semestres (`viesco.periode`, `viesco.rel_type_periode`) |
| course | `Subject` de l'établissement ; cours « Vie de classe » (`course.homeroom.<structure>`) pour les classes |
| class `homeroom` | `Class` |
| class `scheduled` (`<matière>.<groupe>`) | enseignement d'une matière à une classe ou un groupe : `TEACHES` (annuaire) ∪ `viesco.services` |
| user | `User` : Student → `student`, Teacher → `teacher`, Relative → `parent`, Personnel → `aide` (ADML : `siteAdministrator`) ; invités non exposés |
| user.agents | liens responsable ↔ élève (`RELATED`) |
| user.grades, class.grades | niveau français → code CEDS (6e = 06 … Terminale = 12, CP = 01 … CM2 = 05, GS = KG) |
| enrollment (`<classe>.<utilisateur>`) | appartenance à la classe ; enseignants des sections (`primary` = professeur principal pour une classe) |
| demographics | date de naissance seule (l'annuaire ne porte pas le sexe) |
| status `tobedeleted` | utilisateur sorti de l'annuaire (`disappearanceDate` / `deleteDate`) |
| lineItem (`li.<id>` ou sourcedId du client) | `notes.devoirs` (+ `notes.rel_devoirs_groupes`) |
| result (`res.<id>` ou sourcedId du client) | `notes.notes` (+ appréciation `notes.appreciations` → `comment`) |
| category (`cat.<id>`) | `notes.type` |
| resource (`signet.<id>`) | `mediacentre.signet`, rattaché aux classes et utilisateurs avec lesquels il est partagé |

Les téléphones ne sont pas exposés. Les données de vie scolaire, de compétences et du médiacentre
sont lues si ces modules sont installés ; sinon les collections correspondantes sont vides.

## Gradebook en écriture

- `PUT /lineItems/{sourcedId}` crée un devoir dans le module Compétences. La classe doit être une
  section (`<matière>.<groupe>`) ; le propriétaire est un enseignant de la section, sinon
  l'enseignant par défaut du client. La période est celle désignée (`gradingPeriod`) ou celle qui
  contient `assignDate` ; le type est la catégorie `cat.<id>` désignée ou le type par défaut de
  l'établissement ; `resultValueMax` donne le barème (20 par défaut).
- `PUT /results/{sourcedId}` enregistre la note d'un élève de la classe (0 ≤ score ≤ barème) et,
  si `comment` est fourni, l'appréciation. `scoreStatus` `exempt` ou `not submitted` sans score
  retire la note. Refus (422) si la date de fin de saisie de la période est passée.
- `POST /classes/{id}/lineItems`, `/schools/{id}/lineItems`, `/lineItems/{id}/results`,
  `/classes/{id}/academicSessions/{id}/results` : création en lot, réponse `sourcedIdPairs`.
- Un client ne modifie ni ne supprime que ce qu'il a créé ; les devoirs saisis dans l'ENT
  (`li.<id>`) sont en lecture seule (403).
- Barèmes (`scoreScales`), catégories propres au client et évaluations hors classe
  (`assessmentLineItems`, `assessmentResults`) sont conservés tels quels pour le seul client qui
  les a déposés (`oneroster.object`).

## CSV et import

- **Export** : `GET /oneroster/admin/structures/{structureId}/export[?include=rostering,gradebook,resources]`
  (administrateur local de l'établissement) produit une archive CSV 1.2.1 en mode bulk.
- **Import d'archive** : `POST /oneroster/admin/structures/{structureId}/import?mode=validate|import[&orgSourcedId=…]`
  (multipart, champ `file`). L'établissement choisi dans l'archive est converti en fichiers d'import
  CSV de l'annuaire (`Student`, `Teacher`, `Personnel`, `Relative`) puis confié au feeder
  (`entcore.feeder`, actions `validate` / `import`). Les classes `homeroom` deviennent des classes,
  les classes `scheduled` des groupes ; l'identifiant externe est le sourcedId (ou l'externalId
  d'origine pour une archive issue d'Open ENT), ce qui rend l'import rejouable.
- **Import REST** : `POST /oneroster/admin/structures/{structureId}/import/remote`
  (`{baseUrl, tokenUrl, clientId, clientSecret, orgSourcedId?, mode?}`), limité aux fournisseurs
  listés dans `oneroster.import.remote-providers` (https, même hôte et port, sous le chemin).
- **Rapports** : `GET /oneroster/admin/structures/{structureId}/imports`.

## Administration (`/oneroster/admin`)

| Méthode | Chemin | Accès |
|---|---|---|
| GET | `/configuration` | super admin |
| GET, POST | `/clients` | super admin |
| PUT, DELETE | `/clients/{id}` | super admin |
| POST | `/clients/{id}/secret` (nouveau secret) | super admin |
| GET | `/structures/{structureId}/export` | ADML de l'établissement |
| POST | `/structures/{structureId}/import`, `/import/remote` | ADML de l'établissement |
| GET | `/structures/{structureId}/imports` | ADML de l'établissement |

## Configuration

```yaml
oneroster:
  issuer: https://ent.example.org        # origine publique (href des références, iss des jetons)
  private-key: "<PEM PKCS#8>"            # vide : clé éphémère (développement seulement)
  kid: openent-oneroster-1
  access-token-ttl-seconds: 3600
  cache-seconds: 120                     # cache Rostering par périmètre de client
  max-limit: 10000                       # plafond du paramètre limit
  import:
    path: /tmp/oneroster-import          # dossier de travail des imports
    remote-providers: []                 # fournisseurs REST autorisés
```

Le Rostering d'un périmètre est lu en une passe (une douzaine de requêtes) puis filtré, trié et
paginé en mémoire : l'API vise des clients rattachés à quelques établissements. Pour un
périmètre régional, préférer l'export CSV.

## Développement

```bash
mvn test              # filtres, pagination, niveaux, conversion Rostering, CSV aller-retour, jetons
mvn package           # target/fr.openent~oneroster~<version>-fat.jar
```

CI : `.github/workflows/build-and-publish.yml` (tests à chaque construction, publication sur
rct-nexus et GitHub Packages pour les tags) et `publish-dev-tag.yml` (instantané de `develop`).
