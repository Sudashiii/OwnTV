# OwnTV

A highly customizable launcher for Google TV / Android TV, written natively in Kotlin with Jetpack Compose for TV.

The entire home screen is described by one JSON document: rows, columns, grids and stacks containing app rows, app grids, tiles, shortcuts, text, clocks and images, plus a theme and focus style. Because the format is plain, documented JSON with a JSON Schema, you can write layouts by hand or ask any AI chat to design one.

## Looks

**Launcher settings → Looks** has recreations of popular TV home screens: Google TV, Android TV classic, Apple TV (tvOS 26/27 Liquid Glass: full-bleed Top Shelf behind a glass dock, then the app grid), Fire TV, Roku and LG webOS. Use a whole look, or keep your layout and take only its colors, fonts, focus style and tile defaults. They live in [looks/](looks/) and are ordinary layouts, so they are good starting points for your own.

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
./gradlew :layout-model:test :app:testDebugUnitTest :app:assembleDebug :web:wasmJsBrowserDistribution
```

See [CLAUDE.md](CLAUDE.md) for the project layout and development notes.
