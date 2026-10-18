# OwnTV

A highly customizable launcher for Google TV / Android TV, written natively in Kotlin with Jetpack Compose for TV.

The entire home screen is described by one JSON document: rows, columns, grids and stacks containing app rows, app grids, tiles, shortcuts, text, clocks and images, plus a theme and focus style. Because the format is plain, documented JSON with a JSON Schema, you can write layouts by hand or ask any AI chat to design one.

## Looks

**Launcher settings → Looks** has recreations of popular TV home screens: Google TV, Android TV classic, Apple TV (Top Shelf hero over a dock and app grid), Fire TV, Roku and LG webOS. Use a whole look, or keep your layout and take only its colors, fonts, focus style and tile defaults. They live in [looks/](looks/) and are ordinary layouts, so they are good starting points for your own.

## Getting a layout onto the TV

Open **Launcher settings → Import layout** (long-press OK on any tile, or use a Settings shortcut):

- **Paste from your phone**: scan the QR code. The page lets you copy an **AI prompt** that already contains the format, your installed apps and your current layout. Paste the AI's answer back and tap Send. If something is wrong, the page lists the problems with JSON paths and can copy them for the AI.
- **URL**: load a raw JSON link, for example a GitHub gist.
- **File**: `adb push my-layout.json /sdcard/Android/data/dev.owntv.launcher/files/layouts/`

Every import shows a preview first. Applied layouts go into a history, so **Revert** always gets you back.

## Format

- Reference: [docs/layout-spec.md](docs/layout-spec.md)
- JSON Schema: [schema/owntv-layout.v2.schema.json](schema/owntv-layout.v2.schema.json)
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

## Building

```bash
./gradlew :layout-model:test :app:assembleDebug
```

See [CLAUDE.md](CLAUDE.md) for the project layout and development notes.
