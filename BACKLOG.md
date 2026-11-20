# Backlog

## À faire (demandes utilisateur)

- [ ] **Limiter la liste des horaires affichés à 3-4 occurrences** — la liste actuelle
  affiche 6 passages dans l'app et 4 dans le widget ; se limiter à 3-4 suffirait
  (demandé le 20/11/2026). Concerné : `MainActivity` (8 → 3-4),
  `Bus24UpdateReceiver`/widget (4 → 3-4), page web.

## À considérer

- [ ] **Résoudre dynamiquement les URLs des ressources transport.data.gouv.fr** via
  l'API (`https://transport.data.gouv.fr/api/datasets`) au lieu des IDs numériques
  figés (`resources/83024/download`, `resources/83025/download`) : les IDs peuvent
  changer d'une révision du jeu de données à l'autre.
- [ ] **Régénérer l'horaire embarqué de la page web** à chaque révision du GTFS :
  l'offre courante est valable jusqu'au 02/07/2027 ; au-delà, régénérer le fichier
  (script : `web/tools/generate_schedule.py`).
- [ ] Page web : cache du flux TripUpdates côté navigateur (évite 2-3 Mo/min par
  visiteur), ou serveur relais léger avec mise en cache partagée.
