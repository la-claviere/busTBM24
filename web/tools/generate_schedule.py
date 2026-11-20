#!/usr/bin/env python3
"""Régénère web/schedule24.json depuis le GTFS courant de Bordeaux Métropole.

Usage :
    python3 generate_schedule.py

Télécharge le GTFS statique (transport.data.gouv.fr ressource 83024), filtre la
ligne 24 aux deux quais de l'arrêt Fauré à Pessac (815 = quai A, direction Pessac
Romainville ; 281 = quai R, direction Bordeaux centre) et écrit l'horaire compact
embarqué par web/index.html. À relancer à chaque révision de l'offre (voir
BACKLOG.md — offre courante valable jusqu'au 02/07/2027).
"""
import csv
import io
import json
import os
import tempfile
import urllib.request
import zipfile

GTFS_URL = "https://transport.data.gouv.fr/resources/83024/download"
ROUTE_SHORT_NAME = "24"
# slot 1 = quai A (815, sens Romainville) ; slot 2 = quai R (281, sens Bordeaux centre)
STOP_SLOTS = {"815": 1, "281": 2}
DAYS = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(HERE, "..", "schedule24.json"))


def read_csv(name: str):
    with io.open(name, encoding="utf-8-sig", errors="replace") as f:
        return list(csv.DictReader(f))


def main() -> None:
    data = urllib.request.urlopen(
        urllib.request.Request(GTFS_URL, headers={"User-Agent": "busTBM24-web/1.0"}),
        timeout=300,
    ).read()
    with tempfile.TemporaryDirectory() as tmp:
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            z.extractall(tmp)
        os.chdir(tmp)

        routes = read_csv("routes.txt")
        route_ids = {r["route_id"] for r in routes if r["route_short_name"] == ROUTE_SHORT_NAME}
        trips = {t["trip_id"]: t for t in read_csv("trips.txt") if t["route_id"] in route_ids}

        sched = {}
        with io.open("stop_times.txt", encoding="utf-8-sig", errors="replace") as f:
            for row in csv.DictReader(f):
                slot = STOP_SLOTS.get(row["stop_id"])
                if row["trip_id"] not in trips or slot is None:
                    continue
                entry = sched.setdefault(
                    row["trip_id"],
                    [trips[row["trip_id"]]["service_id"], None, None],
                )
                if entry[slot] is None:
                    h, m, s = row["departure_time"].split(":")
                    entry[slot] = int(h) * 3600 + int(m) * 60 + int(s)

        calendar = []
        for row in read_csv("calendar.txt"):
            mask = "".join("1" if row[d] == "1" else "0" for d in DAYS)
            calendar.append([row["service_id"], mask, row["start_date"], row["end_date"]])

        exceptions = []
        try:
            for row in read_csv("calendar_dates.txt"):
                exceptions.append([row["date"], row["service_id"], int(row["exception_type"])])
        except FileNotFoundError:
            pass

    out = {
        "calendar": calendar,
        "exceptions": exceptions,
        "trips": dict(sorted(sched.items(),
                             key=lambda kv: (min(x for x in kv[1][1:] if x is not None),
                                             kv[0]))),
        "valid_from": min(c[2] for c in calendar),
        "valid_until": max(c[3] for c in calendar),
    }
    with io.open(OUT, "w", encoding="utf-8") as f:
        json.dump(out, f, separators=(",", ":"))
    print(f"{len(sched)} trajets, {len(calendar)} services, {len(exceptions)} exceptions "
          f"→ {os.path.abspath(OUT)} (validité {out['valid_from']} → {out['valid_until']})")


if __name__ == "__main__":
    main()
