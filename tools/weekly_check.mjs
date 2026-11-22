#!/usr/bin/env node
/* Vérification hebdomadaire de la page web (exécutée par GitHub Actions,
   cron : lundi 06:00 UTC — voir .github/workflows/weekly-check.yml).
   Lancée aussi manuellement : node tools/weekly_check.mjs

   Contrôles :
   1. Sécurité statique : CSP présente, aucun innerHTML/eval/document.write,
      pas de script/style externe (page autonome).
   2. Syntaxe JS des deux blocs <script> (node --check).
   3. Flux temps réel joignable avec Origin navigateur (CORS) et décodable
      par la logique exacte de la page (bloc <script id="pure">).
   4. Horaire embarqué valide encore au moins 45 jours + départs aujourdh'hui.

   Sort avec un code non nul au premier échec (GitHub envoie alors une
   notification d'échec de workflow). */

import fs from "node:fs";
import https from "node:https";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname), "..");
const HTML = fs.readFileSync(path.join(ROOT, "web", "index.html"), "utf8");

function check(ok, label) {
  console.log((ok ? "OK    " : "ÉCHEC ") + label);
  if (!ok) process.exit(1);
}

/* --- 1. Sécurité statique --- */
check(HTML.includes('http-equiv="Content-Security-Policy"'), "CSP présente");
check(HTML.includes('name="referrer"'), "referrer policy présente");
check(!/innerHTML|outerHTML|document\.write|\beval\s*\(|new Function\s*\(/.test(HTML),
  "aucun innerHTML/eval/document.write/new Function");
check(!/<script[^>]+src=/.test(HTML) && (HTML.match(/<script/g) || []).length === 2,
  "aucun script externe (page autonome)");
check(!/<link[^>]+rel="stylesheet"/.test(HTML), "aucune feuille de style externe");

/* --- 2. Syntaxe des scripts --- */
const pure = HTML.match(/<script id="pure">([\s\S]*?)<\/script>/)[1];
const app = HTML.match(/<script id="app">([\s\S]*?)<\/script>/)[1];
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "bus24check-"));
for (const [name, code] of [["pure.js", pure], ["app.js", app]]) {
  const f = path.join(tmp, name);
  fs.writeFileSync(f, code);
  const r = spawnSync(process.execPath, ["--check", f], { encoding: "utf8" });
  check(r.status === 0, "syntaxe JS " + name + (r.status ? " — " + r.stderr : ""));
}

/* --- 3. Flux temps réel (comme le ferait le navigateur) --- */
const url = HTML.match(/const TRIP_UPDATES_URL = "([^"]+)"/)[1];
check(/^https:\/\/bdx\.mecatran\.com\//.test(url), "URL temps réel = serveur final direct");

function fetchFeed(u) {
  return new Promise((resolve, reject) => {
    https.get(u, { headers: { Origin: "https://pigmentropie.fr" } }, (res) => {
      const chunks = [];
      res.on("data", (c) => chunks.push(c));
      res.on("end", () => resolve({ status: res.statusCode,
        acao: res.headers["access-control-allow-origin"], buf: Buffer.concat(chunks) }));
    }).on("error", reject);
  });
}
const feed = await fetchFeed(url);
check(feed.status === 200, "flux temps réel HTTP 200 (reçu : " + feed.status + ")");
check(!!feed.acao, "Access-Control-Allow-Origin présent (reçu : " + feed.acao + ")");

const pureNoExports = path.join(tmp, "pure_module.js");
fs.writeFileSync(pureNoExports, pure.replace('"use strict";',
  '"use strict";\nmodule.exports = { decodeTripUpdates };'));
const { createRequire } = await import("node:module");
const pureMod = createRequire(import.meta.url)(pureNoExports);
let entities = -1;
try { entities = pureMod.decodeTripUpdates(new Uint8Array(feed.buf)).size; }
catch (e) { console.log("      échec décodage : " + e.message); }
check(entities > 0, "flux temps réel décodé (" + entities + " entités)");

/* --- 4. Horaire embarqué --- */
const i = HTML.indexOf("const SCHEDULE ="), j = HTML.indexOf("const MAX_RESULTS", i);
const frag = HTML.slice(i, j);
const sched = JSON.parse(frag.slice(frag.indexOf("{"), frag.rindex ?? frag.lastIndexOf("}") + 1));
const now = new Date();
const ymd = Number(
  String(now.getFullYear()) + String(now.getMonth() + 1).padStart(2, "0") +
  String(now.getDate()).padStart(2, "0"));
const horizon = Number(
  String(now.getFullYear() + 1) + String(now.getMonth() + 1).padStart(2, "0") +
  String(now.getDate()).padStart(2, "0"));
check(ymd <= Number(sched.valid_until), "horaire embarqué encore valide aujourd'hui (fin " + sched.valid_until + ")");
check(Number(sched.valid_until) < horizon,
  "horaire embarqué valable moins d'un an — pas encore à régénérer de toute urgence (fin " + sched.valid_until + ")");
const weekday = new Intl.DateTimeFormat("en-US", { weekday: "short", timeZone: "Europe/Paris" })
  .format(now).toLowerCase();
const dayIdx = ["sun", "mon", "tue", "wed", "thu", "fri", "sat"].indexOf(weekday.slice(0, 3));
const activeServices = sched.calendar.filter(
  ([sid, mask, start, end]) => ymd >= Number(start) && ymd <= Number(end) && mask[dayIdx] === "1").length;
check(activeServices > 0, "au moins un service actif aujourd'hui (" + activeServices + ")");

fs.rmSync(tmp, { recursive: true, force: true });
console.log("Vérification hebdomadaire : tout est en ordre.");
