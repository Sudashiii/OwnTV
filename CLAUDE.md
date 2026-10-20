# OwnTV

Android TV (Google TV) launcher in Kotlin + Jetpack Compose for TV. The whole home screen is one JSON document ("layout") that people or an AI write; the app validates and renders it natively.

## Working in this repo

Don't use git worktrees: no `git worktree add`, no isolated worktree sessions. Work in this checkout, on a branch when the change needs one. Exception: when the user says in the request that a worktree may be used, use one.

Never install or run debug builds on a physical TV (a device in `adb devices` with an IP address). Test on the emulator (`emulator-5554`, AVD `owntv_gtv`) and pass `-s emulator-5554` to `adb` when more than one device is connected.

## Modules

- `layout-model/` – pure Kotlin, multiplatform (jvm for the app, wasmJs for the web preview). Layout model (`model/`), `LayoutCodec` (decode never throws; returns `ValidationIssue`s with JSON paths), `StructureValidator` (walks raw JSON against serial descriptors), `LayoutValidator` (references, ids, colors, focusability), `JsonSchemaGenerator`, `AiPromptBuilder`. `time/Clocks` formats clock patterns (java.time on the JVM, `TimePattern` on the web).
- `layout-render/` – the renderer (`ui/render/`), Compose Multiplatform for android and wasmJs: turns a `LayoutDocument` into Compose. What differs per platform sits behind `Platform.kt`: `AppInfo`, `AppImages`, `InteractiveSurfaces` (focusable tiles and page tabs; the app's `TvSurfaces` uses tv-material), `FontFamilies`, `assetUrl`, `RenderMetrics`.
- `app/` – the launcher. `importexport/` has the phone paste server (Ktor CIO), URL and file import; `data/` has installed apps, app images and layout storage with history; `update/` is the updater behind Settings > Updates (reads the latest GitHub release's `update.json`, lists all releases with their notes from GitHub's releases API, installs newer versions only, with `PackageInstaller`).
- `web/` – browser preview: the same renderer compiled to Wasm on the left, a Monaco editor (with the JSON Schema), sample picker, issue list and metrics overlay on the right. Demo apps with drawn stand-in banners; fonts and Monaco load from jsDelivr at pinned versions.
- `schema/owntv-layout.v3.schema.json` – generated, checked in. `docs/layout-spec.md` and `docs/ai-prompt.md` are bundled into the APK for the "Copy AI prompt" feature. `examples/*.json` are bundled as templates; `default.json` is the first-run layout. `looks/*.json` are the built-in looks (Google TV, Apple TV, ...) shown in Settings > Looks.
- Apps are not part of the layout: rows, grids and folders use `{"type": "list", "list": "favorites"}` and the TV stores the lists, the custom all-apps order and hidden apps as `AppLists` (`model/AppLists.kt`, pure edits in `apps/AppListOps.kt`, stored by the app's `AppListsRepository` in `files/app-lists.json`; first-run lists in `app/src/main/assets/app-lists.json`). The v2 to v3 migration hands old package lists and `hidden` flags over through `DecodeResult.extractedLists`. Long-press on a tile offers Move (move mode via `AppArranger` in the renderer), Add to…, Remove and Hide (`ui/AppMenus.kt`).
- Node JSON is flat, but in Kotlin the properties every node shares live in `NodeCommon` (`node.common`); `NodeSerializer` splits and joins them. `StyleResolver` applies `defaults` and `styles` before rendering and validation; the stored document keeps the style references.

## Adding or changing a layout property or node type

1. Edit the model in `layout-model/src/commonMain/kotlin/dev/owntv/layout/model/` with `@Doc` (and `@Range` / `@ColorValue` where it applies). New node types also go into `NodeRegistry`.
2. Render it in `layout-render/src/commonMain/.../ui/render/RenderNode.kt`. The TV and the web preview both use it. Keep `android.*`, `androidx.tv.*` and `java.*` out of the commonMain source sets (they would break the web build); add to the interfaces in `Platform.kt` instead.
3. `./gradlew :layout-model:generateSchema`, then update `docs/layout-spec.md`.
4. For breaking changes, bump `CURRENT_SCHEMA_VERSION` and add a step to `codec/Migrations.kt` (see the 1 to 2 step), rename the schema file and update examples and looks.
5. Every file in `examples/` and `looks/` must stay valid and warning free (tests check this).
6. Keep the on-TV editor working with the change (rules below).

## Editor rules

The editor (`app/.../ui/editor/`) builds its forms from the serial descriptors (`layout-model/.../edit/Fields.kt`), so most new properties show up without editor code. Check these when the model changes:

- **New node type:** add a valid palette template in `NodeTemplates.create` and a branch in `NodeTemplates.withId`. Containers also need a branch in `LayoutOps.withChildren`. `EditTest` checks that every registered type has a valid template.
- **New property that points at a node id** (focus link, target, ...): add its name to `nodeRefNames` in `Fields.kt` and handle it in `LayoutOps.retarget`, so rename and duplicate keep the link. Add a case to the rename/duplicate tests. Page ids work the same way (`PageRef`).
- **New kind of value** (anything that is not text, number, bool, enum, color, size, padding, background, nested object, sealed type or list): add a `FieldKind`, detect it in `Fields.kindOf` and give it a control in `PropertyPanel.kt`. The `every editor field has a control` test fails for fields that end up `Unsupported`. Only add to its allow list on purpose (maps are the current exception).
- **Colors, ranges, docs:** use `@ColorValue`, `@Range` and `@Doc`. The editor shows a color picker, clamps steppers to the range and uses the doc text as help.
- **New sealed subtype** (source, action, background): it appears automatically. `Fields.newSealed` must build a value that decodes (see `new sealed values decode`).
- **Edits stay pure:** tree changes go through `LayoutOps` (returns a new document or null, with a unit test). Never mutate state in the UI. That keeps undo and redo working.
- **Remote first:** every control must work with D-pad and OK. Disabled controls also get `focusProperties { canFocus = false }`.
- Run `./gradlew :layout-model:test :app:testDebugUnitTest :app:assembleDebug`. For UI changes, also try the change in the editor on the emulator.

## Commands

```bash
./gradlew :layout-model:test          # model, validation, schema-up-to-date, examples, spec example
./gradlew :layout-model:generateSchema
./gradlew :app:assembleDebug
./gradlew :app:installDebug
./gradlew :web:wasmJsBrowserDistribution   # static site in web/build/dist/wasmJs/productionExecutable
```

Web preview: `just web` builds it and serves it on http://localhost:8088 (`just web-build`, `just web-serve [port]` for the single steps; the `owntv-web` entry in `.claude/launch.json` serves the last build too). `#looks/roku.json` in the URL opens that sample. The page calls Kotlin through `window.owntv` (`render`, `setPage`, `setMetrics`) and gets results as `owntv-result` events (`web/.../Browser.kt`, `resources/editor.js`).

Emulator: AVD `owntv_gtv` (Google TV, API 34, 1080p = 960x540 dp). Start with `~/Library/Android/sdk/emulator/emulator -avd owntv_gtv`. Google TV ignores third-party Home apps; on the emulator the stock launcher is disabled with `adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx` (undo: `pm enable`).

Fastest way to try a layout on the emulator (debug build): `cat my.json | adb shell run-as dev.owntv.launcher sh -c 'cat > files/layouts/current.json'`, then `adb shell am force-stop dev.owntv.launcher` and `adb shell input keyevent KEYCODE_HOME`.

Paste server for testing from the Mac: open Import on the TV, then `adb forward tcp:8765 tcp:8765` and `curl -X POST --data-binary @examples/kids.json "http://localhost:8765/api/layout?t=<token shown on TV>"`.

## Releases

Merges to `main` release automatically (`.github/workflows/release.yml`, README "Releases"). `scripts/release.py` works out the version and notes from the conventional commits since the last `vX.Y.Z` tag, so write commit messages (and squash-merge PR titles) as `feat(scope): ...`, `fix(scope): ...` or `perf: ...`, and mark breaking changes with `!` plus a `BREAKING CHANGE:` footer that says what breaks. Other types (`chore`, `test`, `docs`, `refactor`, ...) stay out of the notes. Preview with `python3 scripts/release.py --notes -`. The workflow builds with `-Powntv.version=X.Y.Z`, which sets `versionName` and `versionCode` (`app/build.gradle.kts`), and signs with the key from the `OWNTV_KEY*` secrets.

## Toolchain notes

AGP 9.4 (built-in Kotlin, so no `kotlin-android` plugin), Kotlin 2.4, Gradle 9.8 on JDK 25. Current Compose and Coil require `compileSdk = 37`.

`layout-render` uses `com.android.kotlin.multiplatform.library` and Compose Multiplatform 1.12 (`composeMultiplatform` in the catalog; keep it on the same Compose version as `composeBom`). The `web` module also needs the `org.jetbrains.compose` plugin, which unpacks Skia's Wasm runtime. Project repositories are off, so Node.js, Yarn and Binaryen for the Wasm build are declared in `settings.gradle.kts`, and the root and `web` build files tell the Kotlin plugin not to add its own.

The `glass` background uses Haze (`dev.chrisbanes.haze`, `layout-render/.../ui/render/Glass.kt`, android and wasmJs). Glass only blurs content marked with `glassSource` (page background, heroes, images); add it to new widgets that should show through glass.
