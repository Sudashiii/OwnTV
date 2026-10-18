# OwnTV

Android TV (Google TV) launcher in Kotlin + Jetpack Compose for TV. The whole home screen is one JSON document ("layout") that people or an AI write; the app validates and renders it natively.

## Modules

- `layout-model/` – pure Kotlin, multiplatform (jvm for the app, wasmJs for the web preview). Layout model (`model/`), `LayoutCodec` (decode never throws; returns `ValidationIssue`s with JSON paths), `StructureValidator` (walks raw JSON against serial descriptors), `LayoutValidator` (references, ids, colors, focusability), `JsonSchemaGenerator`, `AiPromptBuilder`. `time/Clocks` formats clock patterns (java.time on the JVM, `TimePattern` on the web).
- `layout-render/` – the renderer (`ui/render/`), Compose Multiplatform for android and wasmJs: turns a `LayoutDocument` into Compose. What differs per platform sits behind `Platform.kt`: `AppInfo`, `AppImages`, `InteractiveSurfaces` (focusable tiles and page tabs; the app's `TvSurfaces` uses tv-material), `FontFamilies`, `assetUrl`, `RenderMetrics`.
- `app/` – the launcher. `importexport/` has the phone paste server (Ktor CIO), URL and file import; `data/` has installed apps, app images and layout storage with history.
- `web/` – browser preview: the same renderer compiled to Wasm on the left, a Monaco editor (with the JSON Schema), sample picker, issue list and metrics overlay on the right. Demo apps with drawn stand-in banners; fonts and Monaco load from jsDelivr at pinned versions.
- `schema/owntv-layout.v2.schema.json` – generated, checked in. `docs/layout-spec.md` and `docs/ai-prompt.md` are bundled into the APK for the "Copy AI prompt" feature. `examples/*.json` are bundled as templates; `default.json` is the first-run layout. `looks/*.json` are the built-in looks (Google TV, Apple TV, ...) shown in Settings > Looks.
- Node JSON is flat, but in Kotlin the properties every node shares live in `NodeCommon` (`node.common`); `NodeSerializer` splits and joins them. `StyleResolver` applies `defaults` and `styles` before rendering and validation; the stored document keeps the style references.

## Adding or changing a layout property or node type

1. Edit the model in `layout-model/src/commonMain/kotlin/dev/owntv/layout/model/` with `@Doc` (and `@Range` / `@ColorValue` where it applies). New node types also go into `NodeRegistry`.
2. Render it in `layout-render/src/commonMain/.../ui/render/RenderNode.kt`. The TV and the web preview both use it. Keep `android.*`, `androidx.tv.*` and `java.*` out of the commonMain source sets (they would break the web build); add to the interfaces in `Platform.kt` instead.
3. `./gradlew :layout-model:generateSchema`, then update `docs/layout-spec.md`.
4. For breaking changes, bump `CURRENT_SCHEMA_VERSION` and add a step to `codec/Migrations.kt` (see the 1 to 2 step), rename the schema file and update examples and looks.
5. Every file in `examples/` and `looks/` must stay valid and warning free (tests check this).

## Commands

```bash
./gradlew :layout-model:test          # model, validation, schema-up-to-date, examples, spec example
./gradlew :layout-model:generateSchema
./gradlew :app:assembleDebug
./gradlew :app:installDebug
./gradlew :web:wasmJsBrowserDistribution   # static site in web/build/dist/wasmJs/productionExecutable
```

Web preview: build the distribution, then serve it, e.g. `python3 -m http.server 8088 --directory web/build/dist/wasmJs/productionExecutable` (or the `owntv-web` entry in `.claude/launch.json`). `#looks/roku.json` in the URL opens that sample. The page calls Kotlin through `window.owntv` (`render`, `setPage`, `setMetrics`) and gets results as `owntv-result` events (`web/.../Browser.kt`, `resources/editor.js`).

Emulator: AVD `owntv_gtv` (Google TV, API 34, 1080p = 960x540 dp). Start with `~/Library/Android/sdk/emulator/emulator -avd owntv_gtv`. Google TV ignores third-party Home apps; on the emulator the stock launcher is disabled with `adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx` (undo: `pm enable`).

Fastest way to try a layout on the emulator (debug build): `cat my.json | adb shell run-as dev.owntv.launcher sh -c 'cat > files/layouts/current.json'`, then `adb shell am force-stop dev.owntv.launcher` and `adb shell input keyevent KEYCODE_HOME`.

Paste server for testing from the Mac: open Import on the TV, then `adb forward tcp:8765 tcp:8765` and `curl -X POST --data-binary @examples/kids.json "http://localhost:8765/api/layout?t=<token shown on TV>"`.

## Toolchain notes

AGP 9.4 (built-in Kotlin, so no `kotlin-android` plugin), Kotlin 2.4, Gradle 9.8 on JDK 25. Current Compose and Coil require `compileSdk = 37`.

`layout-render` uses `com.android.kotlin.multiplatform.library` and Compose Multiplatform 1.12 (`composeMultiplatform` in the catalog; keep it on the same Compose version as `composeBom`). The `web` module also needs the `org.jetbrains.compose` plugin, which unpacks Skia's Wasm runtime. Project repositories are off, so Node.js, Yarn and Binaryen for the Wasm build are declared in `settings.gradle.kts`, and the root and `web` build files tell the Kotlin plugin not to add its own.

The `glass` background uses Haze (`dev.chrisbanes.haze`, `layout-render/.../ui/render/Glass.kt`, android and wasmJs). Glass only blurs content marked with `glassSource` (page background, heroes, images); add it to new widgets that should show through glass.
