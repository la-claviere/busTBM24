# Prochain bus 24 (TBM) — Spécifications

## User stories validées

**US1 — Consulter les prochains passages (application)**
En tant qu'usager, j'ouvre l'app et je vois le prochain passage du bus 24 à l'arrêt
Fauré (Pessac) vers Pessac Romainville, puis les passages suivants.

**US2 — Widget (vignette) sur l'écran d'accueil**
En tant qu'usager, j'ajoute le widget « Prochain bus 24 » ; il affiche
`14:32 — dans 6 min` sans ouvrir l'application, et se met à jour automatiquement.

**US3 — Distinction temps réel / planifié**
- Bleu : heure issue du temps réel GTFS-RT (`TripUpdate` → heure estimée à l'arrêt).
- Noir : heure planifiée du GTFS (aucune donnée temps réel pour ce trajet).

**US4 — Alerte rouge clignotant**
Rouge clignotant si : bus **annulé** (`TripDescriptor.schedule_relationship = CANCELED(3)`
ou `StopTimeUpdate.schedule_relationship = SKIPPED(1)` à l'arrêt)
**ou** arrivée à **≤ 5 minutes**. Dans ce dernier cas l'heure est accompagnée du compte
à rebours « dans X min ».

**US5 — Robustesse**
- Hors service / dernière course passée : message « Prochain passage : demain à HH:MM »
  ou « Service terminé », pas d'heure trompeuse.
- Échec réseau : la dernière valeur connue reste affichée avec l'âge des données.
- GTFS statique re-téléchargé si plus de ~12 h d'ancienneté (les `trip_id` changent à
  chaque révision de l'offre, valable jusqu'au 02/07/2027 puis anniversaire glissant).

## Périmètre v1

- Ligne / arrêt / direction codés en dur (config centralisée dans `NetworkSources.kt`).
- Pas de notifications push, pas d'itinéraires, pas de carte.

## Données vérifiées le 20/11/2026

| Objet | Valeur GTFS |
|---|---|
| Ligne | `24` « Principale 24 » (#00A98B) |
| Arrêt commercial | `BFAUR47` « Fauré » (Pessac, INSEE 33318) |
| Quai direction Romainville | `815` / code `FAU47A` — 289 passages vers Romainville par semaine |
| Quai opposé | `281` / `FAU47R` — vers Bouliac (non utilisé) |
| Headsign direction voulue | `PESSAC Romainville` |

Validations effectuées en amont du développement :

- GTFS statique 83024 téléchargé et filtré (571 trips route 24 analysed).
- Flux GTFS-RT TripUpdates 83025 décodé à blanc : 2 215 entités, **100 %** des
  `trip_id` reconnus dans le GTFS courant, 31 entités ligne 24, 51 trajets annulés
  (`CANCELED=3` confirmé dans les données réelles).
- Structure Protobuf du flux TBM vérifiée champ par champ (FeedMessage=1/2,
  header.timestamp=3, entity.id=1/trip_update=3, TripDescriptor trip_id=1/start_date=3/
  schedule_relationship=4/route_id=5/direction_id=6, StopTimeUpdate stop_sequence=1/
  arrival=2/departure=3/stop_id=4/schedule_relationship=5, StopTimeEvent delay=1/time=2).
  Le flux TBM place la répétition des `StopTimeUpdate` sur le champ 2 de `TripUpdate`
  (et non 3) ; le décodeur de l'app gère les deux positions.

## Validations du 20/11/2026 (harnais sur données réelles)

- **Extension propriétaire TBM identifiée** : ~18 % des entités transportent en champ 3
  de `TripUpdate` un message non standard `{1 = "ineo-bus:<réf. véhicule>", 2 = "<destination>"}`
  (ex. `{1: "ineo-bus:1074", 2: "GRADIGNAN CENTRE"}`) qui entre en conflit avec le champ
  canonique `stop_time_update = 3`. Le décodeur gère champ 2 (flux TBM) et champ 3
  (canonique), et ignore silencieusement les sous-messages qui ne respectent pas la forme
  d'un `StopTimeUpdate` (entités finales décodées : **2 215/2 215**, 0 échec).
- **End-to-end sur données réelles** (GTFS 83024 + TripUpdates 83025) : 289 départs
  planifiés au quai `815` sens « Pessac Romainville », 15 trips couverts par le temps
  réel, rendu attendu conforme (bleu = temps réel, noir = planifié, annulé ROUGE).
- **Build Android réel** : `./gradlew assembleDebug` → `app-debug.apk` (3,2 Mo) ;
  `./gradlew testDebugUnitTest` → **11/11 tests OK** (décodeur GTFS-RT + NextBusService).
