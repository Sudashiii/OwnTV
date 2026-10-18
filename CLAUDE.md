# OwnTV

Android TV (Google TV) launcher in Kotlin + Jetpack Compose for TV. The whole home screen is one JSON document ("layout") that people or an AI write; the app validates and renders it natively.

## Modules

- `layout-model/` – pure Kotlin/JVM. Layout model (`model/`), `LayoutCodec` (decode never throws; returns `ValidationIssue`s with JSON paths), `StructureValidator` (walks raw JSON against serial descriptors), `LayoutValidator` (references, ids, colors, focusability), `JsonSchemaGenerator`, `AiPromptBuilder`.
- `app/` – the launcher. `ui/render/` turns a `LayoutDocument` into Compose; `importexport/` has the phone paste server (Ktor CIO), URL and file import; `data/` has installed apps, app images and layout storage with history.
- `schema/owntv-layout.v2.schema.json` – generated, checked in. `docs/layout-spec.md` and `docs/ai-prompt.md` are bundled into the APK for the "Copy AI prompt" feature. `examples/*.json` are bundled as templates; `default.json` is the first-run layout. `looks/*.json` are the built-in looks (Google TV, Apple TV, ...) shown in Settings > Looks.
- Node JSON is flat, but in Kotlin the properties every node shares live in `NodeCommon` (`node.common`); `NodeSerializer` splits and joins them. `StyleResolver` applies `defaults` and `styles` before rendering and validation; the stored document keeps the style references.

## Adding or changing a layout property or node type

1. Edit the model in `layout-model/src/main/kotlin/dev/owntv/layout/model/` with `@Doc` (and `@Range` / `@ColorValue` where it applies). New node types also go into `NodeRegistry`.
2. Render it in `app/.../ui/render/RenderNode.kt`.
3. `./gradlew :layout-model:generateSchema`, then update `docs/layout-spec.md`.
4. For breaking changes, bump `CURRENT_SCHEMA_VERSION` and add a step to `codec/Migrations.kt` (see the 1 to 2 step), rename the schema file and update examples and looks.
5. Every file in `examples/` and `looks/` must stay valid and warning free (tests check this).

## Commands

```bash
./gradlew :layout-model:test          # model, validation, schema-up-to-date, examples, spec example
./gradlew :layout-model:generateSchema
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

Emulator: AVD `owntv_gtv` (Google TV, API 34, 1080p = 960x540 dp). Start with `~/Library/Android/sdk/emulator/emulator -avd owntv_gtv`. Google TV ignores third-party Home apps; on the emulator the stock launcher is disabled with `adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx` (undo: `pm enable`).

Fastest way to try a layout on the emulator (debug build): `cat my.json | adb shell run-as dev.owntv.launcher sh -c 'cat > files/layouts/current.json'`, then `adb shell am force-stop dev.owntv.launcher` and `adb shell input keyevent KEYCODE_HOME`.

Paste server for testing from the Mac: open Import on the TV, then `adb forward tcp:8765 tcp:8765` and `curl -X POST --data-binary @examples/kids.json "http://localhost:8765/api/layout?t=<token shown on TV>"`.

## Toolchain notes

AGP 9.4 (built-in Kotlin, so no `kotlin-android` plugin), Kotlin 2.4, Gradle 9.8 on JDK 25. Current Compose and Coil require `compileSdk = 37`.
