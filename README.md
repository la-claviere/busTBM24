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

## Compiler

```bash
./gradlew :app:assembleDebug   # APK dans app/build/outputs/apk/debug/
./gradlew :app:test            # tests unitaires (décodeur GTFS-RT, horloges GTFS…)
```

Prérequis : Android Studio ou SDK Android 35 (variable `ANDROID_HOME`).
Voir [SPEC.md](SPEC.md) pour les user stories complètes.
