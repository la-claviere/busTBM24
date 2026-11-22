# Prochain bus 24 (TBM) — Pessac, arrêt Fauré → Romainville

Application Android (Kotlin natif, aucun framework tiers) qui affiche l'heure du prochain
passage du **bus 24** du réseau TBM à l'arrêt **Fauré** (Pessac, quai A) en direction de
**Pessac Romainville**, depuis l'application **et** sous forme de **widget (vignette)**
sur l'écran d'accueil.

## Règles d'affichage

| État | Affichage | Couleur |
|---|---|---|
| Passage temps réel (heure estimée GTFS-RT) | `14:32 — dans 6 min` | **Bleu** |
| Passage planifié (horaire théorique GTFS, pas de temps réel disponible) | `14:32 — dans 6 min` | **Noir** |
| Passage annulé (GTFS-RT `CANCELED` / arrêt sauté) | `14:32` + « annulé » | **Rouge clignotant** |
| Arrivée imminente (≤ 5 min) | `dans 3 min` | **Rouge clignotant** |

Le widget se rafraîchit automatiquement (AlarmManager) toutes les ~60 s quand un passage
est proche, sinon toutes les 5 min. Le widget s'appuie sur un décodeur GTFS-RT Protobuf
minimaliste écrit à la main (aucune dépendance externe).

## Sources de données (open data, sans clé API)

| Flux | URL | Usage |
|---|---|---|
| GTFS statique | `https://transport.data.gouv.fr/resources/83024/download` | horaires planifiés (`routes.txt`, `trips.txt`, `stop_times.txt`, `calendar*.txt`) |
| GTFS-RT TripUpdates | `https://transport.data.gouv.fr/resources/83025/download` | retards + annulations en temps réel (protobuf) |

- Producteur : **Bordeaux Métropole** (réseau TBM), révision 02/11/2026 → 02/07/2027.
- Licence : **Licence Ouverte 1.0** (LO-1.0 / ODbL selon jeu).
- Point d'accès national : [transport.data.gouv.fr — Réseau urbain et scolaire TBM](https://transport.data.gouv.fr/datasets/offres-de-services-bus-et-tramway-gtfs)
- DataHub Bordeaux Métropole (jeu d'origine) : [opendata.bordeaux-metropole.fr](https://opendata.bordeaux-metropole.fr/explore/dataset/offres-de-services-bus-tramway-gtfs/)
- Doc SIRI-Lite du producteur : `Documentation Bordeaux KB2M SIRI-lite.pdf` (sur la même page).

Paramètres codés en dur dans `fr.laclaviere.bus24.data.NetworkSources` :
`ROUTE_ID = "24"`, `STOP_ID = "815"` (quai **`FAU47A`**, arrêt `BFAUR47`, commune Pessac
INSEE 33318), direction *Pessac Romainville* (headsign GTFS `PESSAC Romainville`).

## Architecture

```
app/src/main/java/fr/laclaviere/bus24/
├── MainActivity.kt                 # liste lisible des prochains passages (couleurs)
├── widget/
│   ├── Bus24WidgetProvider.kt      # RemoteViews + clignotement ViewFlipper
│   └── Bus24UpdateReceiver.kt      # AlarmManager ~60 s / 5 min + mise à jour
└── data/                           # 100 % Kotlin pur (testable sans Android)
    ├── NetworkSources.kt           # constantes de configuration
    ├── ProtobufReader.kt           # lecture wire-format Protobuf (varint, length-delimited…)
    ├── GtfsRt.kt                   # décode GTFS-RT TripUpdates minimal (FeedMessage → TripUpdate)
    ├── GtfsParser.kt               # CSV GTFS (calendar, trips, stop_times) + filtrage
    ├── GtfsStore.kt                # téléchargement/décompression/cache du GTFS
    └── NextBusService.kt           # fusion planifié + temps réel, gestion des ce jours >24 h
```

Le téléchargement du GTFS statique (`≈ 20 Mo`) se fait au premier lancement, puis une fois
par jour d'ancienneté ; le flux TripUpdates (~3 Mo) est interrogé à chaque rafraîchissement
du widget. Les `trip_id` du flux temps réel ont été vérifiés 100 % compatibles avec le
GTFS courant le 20/11/2026.

## Page web (sans application)

`web/index.html` est une page autonome (HTML + JS vanilla, sans dépendance) affichant
les deux sens de la ligne en deux colonnes (Romainville = quai A / 815, Bordeaux centre
= quai R / 281) avec les mêmes règles d'affichage que l'application : horaire planifié embarqué (`web/schedule24.json`,
généré par `web/tools/generate_schedule.py`), temps réel GTFS-RT interrogé directement par le
navigateur, en interrogeant l'URL finale directe du flux GTFS-RT (`bdx.mecatran.com`, clé
`apiKey` publique publiée dans les métadonnées du portail open data) : la redirection
302 de `transport.data.gouv.fr` est envoyée sans en-tête CORS, ce qui fait avorter tout
fetch navigateur cross-origin, alors que le serveur mecatran répond avec
`Access-Control-Allow-Origin` en écho de l'Origin. Publiez `index.html` dans un dossier
(et idéalement aussi `icon-192.png`, `icon-512.png`, `favicon-32.png` et
`manifest24.webmanifest`, référencés par la page). Un favicon 32/192 est aussi
embarqué en data URI dans la page : le raccourci Android (Chrome « Ajouter à
l'écran d'accueil ») affiche l'icône « 24 » en dégradé rose→bleu sur fond blanc
(palette TBM : rose, bleu, vert) même si cette page n'est pas installée comme
application. Icônes régénérables par
`web/tools/generate_icons.py` (Pillow requis).
La logique pure (débogage/test possible avec node) se trouve dans le bloc
`<script id="pure">` de la page ; voir BACKLOG.md pour les évolutions prévues.

## Sécurité et frugalité de la page web

- **Pas de dépendance externe** : aucun script, style ou police tiers, une seule
  requête réseau (le flux temps réel), hors données embarquées.
- **Pas de surface XSS** : tout l'affichage est construit en `textContent`, jamais
  `innerHTML`/`eval` ; les données externes (protobuf GTFS-RT) ne sont utilisées
  que comme heures et identifiants, jamais injectées en HTML/attributs.
- **CSP stricte** en en-tête de page (`default-src 'none'`, `connect-src` limité
  au serveur du flux, `base-uri 'none'`, `form-action 'none'`) : même en cas de
  script injecté, le navigateur refuserait d'exécuter quoi que ce soit d'externe.
- **`referrer: no-referrer`** : votre adresse de site n'est pas transmise au serveur
  du flux.
- **Frugalité** : page unique (~137 ko, gzip réduit fortement ce poids ; pensez à
  activer gzip sur OVH), aucune animation coûteuse, rafraîchissement adaptatif
  (60 s quand un passage est proche, 5 min sinon), **pause complète du
  téléchargement quand l'onglet est masqué** (reprise au retour, ou toutes les
  30 s). Point restant (backlog) : le flux GTFS-RT fait ~2,5 Mo — un relais avec
  cache partagé le réduirait ; tracé dans BACKLOG.md.
- **Vérification hebdomadaire automatique** : `.github/workflows/weekly-check.yml`
  (cron lundi 06:00 UTC) lance `tools/weekly_check.mjs` qui contrôle sécurité
  statique de la page, syntaxe JS, joignabilité + CORS + décodage du flux temps
  réel et validité de l'horaire embarqué. Tout échec déclenche une notification
  GitHub (e-mail). Lancable à la main : `node tools/weekly_check.mjs`.
  Note : GitHub désactive les crons après 60 jours sans activité dans le dépôt —
  les commits de maintenance suffisent à le maintenir actif.

La page et cette application ont été générées avec l'assistance de l'IA
(Mistral AI, agent Vibe), mention reprise en pied de page (`web/index.html`).

## Compiler

```bash
./gradlew :app:assembleDebug   # APK dans app/build/outputs/apk/debug/
./gradlew :app:test            # tests unitaires (décodeur GTFS-RT, horloges GTFS…)
```

Prérequis : Android Studio ou SDK Android 35 (variable `ANDROID_HOME`).
Voir [SPEC.md](SPEC.md) pour les user stories complètes.

## Rafraîchissement du widget et batterie

Le widget se met à jour par alarme (~60 s si un passage est à moins de 45 min, sinon
~5 min), re-programmée après chaque tick, après un redémarrage du téléphone et via le
heartbeat système du widget. Sur Android 12+ l'alarme est exacte ; si le réglage
« Alarmes et rappels » est refusé, le repli se fait sur des alarmes approchées qui
peuvent être différées de quelques minutes.

Si le widget reste figé sur certains téléphones (optimisations batterie agressives de
certains constructeurs) : désactivez l'optimisation de batterie pour BusTBM24
(*Paramètres → Applications → BusTBM24 → Batterie → Sans restriction*), et accordez
« Alarmes et rappels » si le téléphone le propose.

Seuls les horaires à venir sont affichés : un bus déjà passé à l'arrêt disparaît ; un
bus retardé reste visible jusqu'à son nouveau passage réel.
