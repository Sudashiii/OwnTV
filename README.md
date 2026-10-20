# OwnTV

A highly customizable launcher for Google TV / Android TV, written natively in Kotlin with Jetpack Compose for TV.

The entire home screen is described by one JSON document: rows, columns, grids and stacks containing app rows, app grids, tiles, shortcuts, text, clocks and images, plus a theme and focus style. Because the format is plain, documented JSON with a JSON Schema, you can write layouts by hand or ask any AI chat to design one.

## Looks

**Launcher settings → Looks** has recreations of popular TV home screens: Google TV, Android TV classic, Apple TV (tvOS 26/27 Liquid Glass: full-bleed Top Shelf behind a glass dock, then the app grid), Fire TV, Roku, LG webOS and Projectivy Launcher (category sections over a wallpaper, inputs, the focused row bright and the rest dimmed). Use a whole look, or keep your layout and take only its colors, fonts, focus style and tile defaults. They live in [looks/](looks/) and are ordinary layouts, so they are good starting points for your own.

## Editing on the TV

Open **OwnTV Settings** in the app list to reach the editor and built-in looks. It also appears in rows and grids that show all apps, including the Apple TV dock. It is a shortcut provided by OwnTV itself; the Home button still opens your layout.

Open **Launcher settings → Edit layout**, or long-press a tile and choose **Edit layout** to select its row or widget. Use the outline to select an item; the preview outlines it in pink and scrolls it into view. Press OK to edit its properties, add items, move them, duplicate them or delete them.

Changes stay in the editor until you choose **Save**. **Undo** and **Redo** work while editing; Back returns through the panels and asks whether to save or discard unsaved changes. Validation errors must be fixed before saving. Saved edits go into layout history like imports.

## Getting a layout onto the TV

Open **Launcher settings → Import layout** (long-press OK on any tile, or use a Settings shortcut):

- **Paste from your phone**: scan the QR code. The page lets you copy an **AI prompt** that already contains the format, your installed apps and your current layout. Paste the AI's answer back and tap Send. If something is wrong, the page lists the problems with JSON paths and can copy them for the AI.
- **URL**: load a raw JSON link, for example a GitHub gist.
- **File**: `adb push my-layout.json /sdcard/Android/data/dev.owntv.launcher/files/layouts/`

Every import shows a preview first. Applied layouts go into a history, so **Revert** always gets you back.

## Previewing layouts in a browser

The `web` module compiles the launcher's own renderer to WebAssembly, so a layout looks the same as on the TV without a TV. The page shows the layout on the left. On the right you pick an example or look, edit the JSON (autocomplete and hover docs come from the JSON Schema), and see the same validation errors the TV shows. If the JSON is broken, the last valid layout stays on screen. The **Metrics** toggle shows frame times, recompositions and how long parsing and validation took.

```bash
just web
```

Then open http://localhost:8088. `just web-build` only builds, and `just web-serve 9000` serves the last build on another port. Without [just](https://github.com/casey/just), run `./gradlew :web:wasmJsBrowserDistribution`, then `python3 -m http.server 8088 --directory web/build/dist/wasmJs/productionExecutable`. The preview uses made-up demo apps with drawn banners instead of your installed apps. Like the previews on the TV, it does not take focus. Fonts and the editor load from jsDelivr. Clock patterns support the common letters (`y M d E H h k K m s a`) with English names.

## Format

- Reference: [docs/layout-spec.md](docs/layout-spec.md)
- How the engine works: [docs/layout-engine.md](docs/layout-engine.md)
- JSON Schema: [schema/owntv-layout.v3.schema.json](schema/owntv-layout.v3.schema.json)
- Examples: [examples/](examples/)

## Making OwnTV the Home app on Google TV

Google TV sends the Home button to its own launcher. With adb:

```bash
adb shell cmd package set-home-activity dev.owntv.launcher/.MainActivity
```

If Home still opens the stock launcher, disable it (undo with `pm enable`):

```bash
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
```

## Performance metrics

**Launcher settings → Performance metrics** measures the launcher to find what makes it slow. **Background** measures quietly while OwnTV is in front; **Live overlay** also shows a small see-through panel (frames per second, frame times, the last slow frame, remote response, memory) that never takes focus. Off measures nothing.

It records every frame of the main window with Android's FrameMetrics, split into phases (main thread busy, composition, layout, draw, bitmap upload, GPU), per screen and page, plus remote presses until the screen changes, layout node recompositions, image loads (memory, disk or network, size), memory and start-up. The Settings screen turns that into findings, such as which phase slow frames spend their time in. The same report is written to a file every 30 seconds and when OwnTV goes to the background (the previous session's goes to `previous-report.txt`):

```bash
adb shell run-as dev.owntv.launcher cat files/metrics/report.txt
```

Frame reports come from Android, so dialogs (tile menus) are not included, and emulator GPU timings are much slower than a real TV's.

## Building

```bash
./gradlew :layout-model:test :app:testDebugUnitTest :app:assembleDebug :web:wasmJsBrowserDistribution
```

See [CLAUDE.md](CLAUDE.md) for the project layout and development notes.

## Releases

Every merge to `main` with a `feat`, `fix` or `perf` commit becomes a GitHub release ([release.yml](.github/workflows/release.yml)). The version comes from the [conventional commits](https://www.conventionalcommits.org/) since the last tag: a breaking change (`feat!:` or a `BREAKING CHANGE:` footer) bumps the major version (the minor one before 1.0), `feat` the minor version, `fix` and `perf` the patch. The release notes list the breaking changes, features and fixes, so commit messages, and pull request titles when you squash-merge, need the conventional format to show up. Pull requests show the notes their merge would release in the job summary. To release a chosen version, like 1.0.0, run the workflow by hand and enter it.

Each release has the signed `owntv-<version>.apk` and an `update.json` for the in-app updater, always at `releases/latest/download/update.json`:

```json
{ "versionName": "0.2.0", "versionCode": 2000, "apk": "https://github.com/…/owntv-0.2.0.apk", "sha256": "…", "notes": "### Features …" }
```

`versionCode` is `major * 1000000 + minor * 1000 + patch`. Preview the next version and notes locally with `python3 scripts/release.py --notes -`.

### Updating on the TV

Settings > Updates reads that `update.json` (Settings checks it when it opens, at most once an hour), shows the release notes and installs the new APK: it downloads it, checks the SHA-256 and the signing key, and hands it to Android's package installer. The first time, Android asks to allow OwnTV to "install unknown apps" and to confirm the update; on Android 12 and later, updates after that can install without the confirmation. The TV downloads without signing in, so the releases have to be public. Check again stays available while an update is waiting.

The right side lists every release with its notes, newest first (up and down scroll the list and long notes; OK on a newer version installs it). The list comes from GitHub's releases API for the same repository, and each version's own `update.json` supplies its APK link and checksum. Older versions show their notes only: Android refuses to install an older version over a newer one, so the updater never downgrades.

Builds read the URLs from `-Powntv.updateUrl` and `-Powntv.releasesUrl` (the release workflow passes its own repository's; the release list URL is derived from the update URL when only that is set), which also makes it easy to try the updater against a local server that serves `update.json`, a GitHub-shaped `releases.json` and the APKs:

```bash
./gradlew :app:installDebug -Powntv.updateUrl=http://10.0.2.2:8799/update.json -Powntv.releasesUrl=http://10.0.2.2:8799/releases.json
```

### Release key

Android only installs an update that is signed with the same key, so every release is signed with one key kept in repository secrets. Create it once and keep a copy of the file and the password outside GitHub; without it, released apps can no longer be updated.

```bash
keytool -genkeypair -keystore owntv-release.jks -alias owntv -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=OwnTV"
```

Then store it in the repository (the key password is the keystore password, keytool uses one for both):

```bash
base64 -i owntv-release.jks | gh secret set OWNTV_KEYSTORE_BASE64
```

```bash
gh secret set OWNTV_KEYSTORE_PASSWORD
```

```bash
gh secret set OWNTV_KEY_PASSWORD
```

```bash
gh secret set OWNTV_KEY_ALIAS --body owntv
```

Debug builds use the debug key, so uninstall a debug build before installing a release APK on the same device.
