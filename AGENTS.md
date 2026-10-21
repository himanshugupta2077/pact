# Agents

This repo is public. Do not put this machine's hosts, device ids, SSH targets, timezone, or home paths into committed files.

Private values for this checkout are in `pact.local.json`. Notes that should stay off GitHub are in `pact.local.md`. Both are gitignored. Read them before changing deployment, collectors, the phone upload, or the menu bar. If they are missing, copy `pact.local.json.example` and fill it in. The code then uses generic defaults.

Do not quote those files in commits, docs, tests, or logs that you commit. After changing `pact.local.json`, reinstall the pieces that bake it in:

- Android: the next `./gradlew` build reads it into `BuildConfig`
- Collectors: `python3 collector/aw_ship.py --install`
- Menu bar: `menubar/build.sh` writes `~/Library/Application Support/PactScreen/site.json`
