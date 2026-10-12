# DualFiles – Projektkontext für Claude

## Projekt
- Android Dual-Pane-Dateimanager, Repo `regepower/DualFiles`, Branch `main` (direkt pushen, keine PRs ohne Auftrag)
- Kotlin, nur Framework-Views (kein AndroidX/keine Libs), AGP 8.9.1, Kotlin 2.1.10, minSdk 30, targetSdk 36, R8 an
- NDK-Lib `dualfiles` (arm64): `listDir`, `copyFile`, `treeSize`, 7z/ZIP (`app/src/main/cpp/`)
- Kein lokales SDK: Compiler nur CI (`.github/workflows/build.yml`: Lint, Debug, signiertes Release)
- Release: `gh workflow run build.yml --repo regepower/DualFiles --ref main -f release=true`
- Lizenz GPL-3.0

## Arbeitsweise
- Deutsch, extrem kurz, Stichpunkte
- Immer prüfen: XML-Parse, Klammer-Bilanz, C lokal mit gcc; danach CI abwarten
- Bei jeder Feature-Änderung im selben Commit: Hilfe `res/raw/help.txt` + `res/raw-de/help.txt` und Strings EN/DE aktualisieren
- Commit-Footer:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: <aktuelle Session-URL>
  ```

## Wichtige Dateien (`app/src/main/java/de/regepower/dualfiles/`)
- `MainActivity.kt` – Panes, Bäume, Listen, Menü-Sheet, Filter/Sortierung, Einstellungen
- `ViewerActivity.kt` + `TextGrid.kt` – Text/Hex-Viewer (Zoom, Wortumbruch, Syntax, Markdown)
- `Syntax.kt` (eigener Scanner, kein Regex), `Markdown.kt`, `Natural.kt`, `NameMatch.kt` (Wildcards * ?, unscharf)
- `Icons.kt` (App-Badges), `Archive.kt` (ZIP/7z/XAPK/APKS/APKM), `SnapScroll.kt` (Pager)

## Zuletzt erledigt (CI #84–#92 grün)
- Pull-to-Refresh, Entpack-Dialog mit vollem Zielpfad, XAPK als Archiv
- Lua-Syntax, Viewer-Umbruch an Leerzeichen
- Filter: Zeile „Enthält:“ mit Text + ✕, Wildcards, unscharfe Suche
- Sortierung wie Windows (Ordner oben, beide Gruppen sortiert, auch im Archiv); Sort/Filter nur über Quelle/Ziel

## Offen / gemerkt (nur auf Nachfrage bauen)
- XAPK direkt installieren (PackageInstaller-Session mit Split-APKs) – angeboten, keine Antwort
- SMB (libsmb2), Eigenschaften, Papierkorb, Ordnervergleich
- Play-Protect-Einspruch (Text liegt vor); ggf. REQUEST_INSTALL_PACKAGES entfernen, falls Sperre bleibt
