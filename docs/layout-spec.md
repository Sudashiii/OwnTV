# OwnTV layout format (version 3)

An OwnTV layout is one JSON document. The launcher renders it natively; nothing in it is code. The machine-readable definition is `schema/owntv-layout.v3.schema.json` (JSON Schema 2020-12), generated from the app's Kotlin model.

## Screen and units

- The screen is **960 × 540 dp** (a 1080p TV). All sizes, paddings and gaps are in dp; font sizes are in sp.
- People use a remote: Up/Down/Left/Right move focus, OK clicks, a long press on OK opens a menu (Open, App info, Launcher settings), Back goes back.
- Focusable widgets: `app_tile`, `app_row`, `app_grid`, `watch_next_row`, `folder`, `shortcut`, and a `hero` whose slides have actions. Focus moves to the nearest focusable widget in the pressed direction unless `focus` links say otherwise. A focused tile grows and gets the border and glow from `theme.focus`. Scrolling rows and columns always keep room at their edges for that, so a focused tile is never cut off.

## Document

```json
{
  "schemaVersion": 3,
  "name": "Living room",
  "description": "Streaming apps on top, games below.",
  "theme": { "colors": { "primary": "#7AA2F7" } },
  "background": { "type": "gradient", "colors": ["#0E0F13", "#16203A"], "angle": 160 },
  "styles": { "big_tiles": { "tile": { "size": "large" }, "spacing": 24 } },
  "defaults": { "app_row": { "tile": { "shape": "card" } } },
  "apps": { "com.netflix.ninja": { "description": "Movies and series", "backdrop": "https://example.com/netflix.jpg" } },
  "pageTabs": false,
  "screensaver": { "page": "night", "after": 300 },
  "pages": [ { "id": "home", "initialFocus": "favorites", "root": { "type": "column", "id": "root", "children": [] } } ]
}
```

| Property | Required | Meaning |
|---|---|---|
| `schemaVersion` | yes | Always `3`. Older layouts are upgraded automatically; their package lists move into the TV's app lists. |
| `name`, `description` | | Shown in settings, templates and history. |
| `theme` | | See Theme. |
| `background` | | Background of all pages. Default: theme background color. |
| `styles` | | Named sets of node properties; see Styles. |
| `defaults` | | Default properties per node type, e.g. `{ "app_row": { "spacing": 24 } }`. |
| `apps` | | Per-app settings keyed by package name; see Apps. |
| `pageTabs` | | `true` shows page titles as tabs at the top (only with 2+ pages). Default `false`. |
| `screensaver` | | Shows one of the pages as a screensaver after the remote has been idle on Home; see Screensaver. Default: none. |
| `pages` | yes | List of pages. The first page is the home page; Back returns to it. |

**Page**: `id` (required, unique), `title` (tab label and `{page}`), `initialFocus` (id of the node focused when the page opens; default: first focusable widget), `background` (overrides the layout background), `root` (required node; it fills the screen).

## Theme

```json
"theme": {
  "colors": {
    "background": "#0E0F13", "surface": "#1F2229", "primary": "#7AA2F7",
    "text": "#ECEDEF", "textMuted": "#9AA0AA", "focus": "#FFFFFF",
    "custom": { "accent2": "#FF8800" }
  },
  "cornerRadius": 12,
  "fontFamily": "sans",
  "fontScale": 1.0,
  "focus": { "scale": 1.08, "borderWidth": 3, "borderColor": "@focus", "glow": true, "glowColor": "#99000000", "glowSize": 10 }
}
```

- Token colors: `@background`, `@surface` (tiles), `@primary`, `@text`, `@textMuted`, `@focus`, plus every key in `custom` (`@accent2`). Theme colors themselves must be hex values. `#AARRGGBB` gives transparency.
- `fontFamily`: `sans`, `serif`, `mono` or `cursive`. `cornerRadius` 0–64, `fontScale` 0.5–2.
- `scrollMotion`: how scrolling rows and columns move to the focused item, `{ "curve": "ease_out", "duration": 350 }`. `curve` is `spring` (default: quick, keeps up with a held remote), `ease_out` (starts fast, glides to a stop), `ease_in_out` (gentle start and stop) or `linear`; `duration` is about how long one scroll takes in ms (50–1500, default 120). `column`, `row` and `app_row` can set their own `scrollMotion`, e.g. soft vertical scrolling between sections and quick rows. Keep rows quick (`spring`, 150 ms or less): when a row trails behind a held remote, every press has to build the next tile first.
- `dynamicColors`: the theme colors follow the wallpaper, like Material You and Projectivy, `{ "source": "wallpaper", "style": "tonal_spot" }`. OwnTV finds the image's main color and turns every theme color (the six tokens and `custom`) to its hue: each keeps its lightness and opacity, so contrast and translucent surfaces stay as designed; colorful tokens keep their hue distance to `primary`, near-grays get a tint. `primary` and `focus` become clearly colorful (a white `focus` turns into a light accent). `source`: `wallpaper` (default: the page background, else the layout background; color and gradient backgrounds work too) or `focused` (the focused tile's `backdrop` or app banner, falling back to the wallpaper; applied once focus rests for a moment). `style`: `neutral` (light tint), `tonal_spot` (default, calm) or `vibrant`; colors that are already more colorful keep it. `exclude`: token names that keep their own color, e.g. `["text", "tile"]`. Images that are black, white or gray keep the layout's colors. Only token references follow: write `"@primary"`, not its hex value, wherever a color should change with the wallpaper (glow colors and gradient stops included; add translucent variants to `custom`).
- `focus`: `scale` 1.0–1.5, `borderWidth` 0–12 (0 = no outline), `glow` on/off, `glowColor` (a dark color such as `#99000000` makes a drop shadow; default is the border color), `glowSize` 0–48, `highlight` (a light sheen moves across the focused tile, default false), `motion` (`none` default, or `sway`: the focused tile tilts gently in 3D like tvOS parallax).

## Backgrounds

Used by the document, pages, any node (`background`) and hero slides.

- `"#101010"` or `"@surface"` — a solid color (shorthand for `{ "type": "color", "color": "#101010" }`).
- `{ "type": "gradient", "colors": ["#000000", "#203040"], "angle": 180 }` — 2+ colors; angle like CSS: 0 = bottom→top, 90 = left→right, 180 = top→bottom (default).
- `{ "type": "image", "src": "https://…/bg.jpg", "dim": 0.3, "blur": 0, "fit": "cover", "adjust": { "brightness": 0, "contrast": 1, "saturation": 1, "hue": 0 } }` — `dim` 0–1 darkens, `blur` 0–60 dp (Android 12+), `fit` is `cover`, `contain` or `fill`. Animated GIFs and WebPs play. `adjust` (optional) changes the picture like a photo editor: `brightness` −1–1 (0 = unchanged), `contrast` 0–2 (1 = unchanged, 0 = flat gray), `saturation` 0–2 (1 = unchanged, 0 = black and white), `hue` −180–180 degrees around the color wheel. Image links (`https://…`) work everywhere an image is accepted (backgrounds, heroes, `image` nodes, tiles, app artwork). The TV downloads each one once and caches it locally (about 30 days, up to 256 MB), so it loads instantly afterwards and works offline; to replace an image, change its URL.
- `{ "type": "video", "src": "https://…/aquarium.mp4", "dim": 0.3, "fit": "cover" }` — a silent video that loops (MP4, WebM, MKV or an HLS `.m3u8` stream, or `asset:`), with `dim`, `blur`, `fit` and `adjust` like an image. It pauses while the launcher is in the background or covered by its settings. Videos are cached on the TV too (up to 512 MB), so a loop is downloaded once. The web preview shows a placeholder.
- `{ "type": "slideshow", … }` — wallpapers that take turns, like Projectivy's:

  ```json
  "background": {
    "type": "slideshow",
    "images": [
      { "src": "https://…/mountains.jpg" },
      { "src": "https://…/city.jpg", "dim": 0.5, "adjust": { "saturation": 0.6 } },
      { "src": "https://…/fireplace.mp4" }
    ],
    "folder": "Pictures/OwnTV",
    "randomUrl": "https://picsum.photos/1920/1080",
    "interval": 600,
    "order": "shuffle",
    "transition": 1000,
    "dim": 0.3
  }
  ```

  Its wallpapers come from up to three sources: `images` (each with an optional `dim`, `blur`, `fit` and `adjust` of its own; sources ending in `.mp4`, `.webm`, `.mkv`, `.mov` or `.m3u8` play as videos), `folder` (every picture and video in that folder of the TV's storage and its subfolders, on USB drives too, e.g. `Pictures/OwnTV`; OwnTV asks once for access to photos and videos) and `randomUrl` (an address that returns a different picture on every request; it takes one turn and brings a new picture each time). At least one source is needed. `interval`: seconds between changes (0–86400, default 300; 0 changes only when the launcher starts and with `next_wallpaper`); while the launcher is in the background the timer waits, so coming back after a while brings a new wallpaper. `order`: `shuffle` (default, never the same wallpaper twice in a row) or `sequential` (images, then the folder, then the random address). `transition`: crossfade in ms (0–5000, default 1000); pictures are downloaded before they fade in. `dim`, `blur`, `fit` and `adjust` apply to every wallpaper that does not set its own. With `dynamicColors`, the theme follows the picture shown now. A shortcut with the `next_wallpaper` action changes the screen's slideshow (the page's, else the layout's) at once.
- `{ "type": "glass", "tint": "#1FFFFFFF", "variant": "regular" }` — Liquid Glass (tvOS 26 style): blurs and refracts the page background, heroes and images behind the node, with a light rim. `variant` is `regular` (frosted) or `clear` (more see-through). Use it on a node above a hero, e.g. a dock row in a `stack`, and give the node a `cornerRadius`. Android 12+; older devices show only the tint.

## Screensaver

```json
"screensaver": { "page": "night", "after": 300, "drift": true },
"pages": [
  { "id": "home", "root": { … } },
  {
    "id": "night",
    "background": { "type": "slideshow", "folder": "Pictures/OwnTV", "randomUrl": "https://picsum.photos/1920/1080", "interval": 60, "dim": 0.2 },
    "root": {
      "type": "column", "id": "night_root", "padding": [48, 40], "arrange": "end",
      "children": [
        { "type": "clock", "id": "night_clock", "format": "HH:mm", "textStyle": "display" },
        { "type": "text", "id": "night_date", "text": "{date:EEEE, d MMMM}", "textStyle": "title" }
      ]
    }
  }
]
```

When nobody has pressed a key on the home screen for `after` seconds (30–7200, default 300), the page `page` fades in over it, full screen and without page tabs. Any key ends it, and that key does nothing else. Give the screensaver a page of its own: not the first page (the home page), and with nothing focusable on it (clocks, text, images and heroes that follow their slides fit; tiles and shortcuts cannot be selected there). Page tabs leave it out, and a `go_to_page` action to it starts the screensaver at once (e.g. a shortcut with the `wallpaper` icon). `drift` (default `true`) moves the page a few dp every minute, so a clock does not stay on the same pixels for hours. The home screen's videos pause while it is covered; the screensaver's own background (a `slideshow` or a `video` fits well) keeps running. It only runs while OwnTV is in front: the TV's own screen saver and sleep timer still apply and win when they are shorter.

## Common node properties

Every node has `type` and `id` (both required) and may use:

| Property | Values |
|---|---|
| `width`, `height` | `"fill"`, `"wrap"` or a number (dp). Default depends on the widget. |
| `weight` | Share of the remaining space in a row or column, like flex-grow. `{ "type": "spacer", "id": "gap", "weight": 1 }` pushes neighbours apart. |
| `padding` | `16`, `[horizontal, vertical]` or `[start, top, end, bottom]`. |
| `background` | A background (see above). |
| `border` | `{ "width": 2, "color": "@textMuted" }`. |
| `cornerRadius` | Radius of background, border and shadow, 0–200. |
| `opacity` | 0–1. |
| `unfocusedOpacity` | 0–1: opacity while focus is not inside the node; it fades to full opacity when focus enters. `0.5` on every section dims all but the focused one (Projectivy). |
| `shadow` | Drop shadow size in dp, 0–48. |
| `align` | `top_start`, `top`, `top_end`, `start`, `center`, `end`, `bottom_start`, `bottom`, `bottom_end`. In a stack all apply; in a column only the horizontal part; in a row only the vertical part. |
| `span` | Inside a `grid`: how many columns the node covers (default 1). |
| `style` | Name of an entry in `styles`. |
| `focus` | `{ "up": "id", "down": "id", "left": "id", "right": "id" }` — where the D-pad goes. On a tile it applies when that tile is focused; on a container or row it applies when focus leaves it in that direction. Ids must be on the same page. |
| `revealOnFocus` | `true`: when something inside gets focus, scroll so the whole node is visible (e.g. a hero plus the row under it). |
| `showWhenFocused` | Id of a node on the same page: this node is only shown, and only takes space, while focus is inside that node. It slides open and closed. E.g. a `{focused}` text under a row that appears only while the row has focus. |
| `keepSpace` | With `showWhenFocused`: `true` keeps the node's space while it is hidden and only fades it, so nothing below moves when focus changes. When the node it watches has nothing to focus (an empty row), it takes no space. Give it a fixed `height` when its text can change length. Default `false`. |
| `visible` | `false` hides the node. |

## Containers

- **`column`**: children top to bottom. `children`, `spacing` (gap, default 0), `contentAlign` (`start` / `center` / `end`, horizontal), `arrange` (`start`, `center`, `end`, `space_between`, `space_around`, `space_evenly`, vertical), `scroll` (`true` scrolls vertically; needed when content is taller than the screen), `scrollAlign` (`nearest` default, `start` = focused child scrolls to the top minus `scrollMargin` (default 32), `center`), `scrollMotion` (see Theme).
- **`row`**: children left to right. `children`, `spacing`, `contentAlign` (`top` / `center` / `bottom`, default `center`), `arrange` (horizontal), `scroll` (horizontal), `scrollAlign`, `scrollMargin`, `scrollMotion`.
- **`grid`**: `columns` (required, 1–12), `children` (filled row by row), `spacing` (default 16). Children stretch to their cell width; tiles keep their aspect ratio unless they set `height`.
- **`stack`**: layers children; later ones on top. `contentAlign` (9 positions, default `top_start`) and each child's `align`.

The page `root` fills the screen. Other containers wrap their content unless you set `width`/`height` (`"fill"` is common for rows that hold a spacer).

## App widgets (focusable)

**Tile style** (`tile`): `{ "shape": "banner" | "icon" | "card", "size": "small" | "medium" | "large", "showLabel": "always" | "focused" | "never", "width": 148, "aspectRatio": 1.67, "cornerRadius": 14, "rim": false }`

- `banner`: the app's TV banner, 16:9 by default; widths 128 / 160 / 208 dp. Label shown on focus by default.
- `icon`: square app icon (56 / 72 / 96 dp) with the label below.
- `card`: banner with the label always below.
- `width` (dp) overrides `size`; `aspectRatio` is width ÷ height (1.78 = 16:9, 1.67 = Apple TV 5:3, 1 = square, 0.67 = poster); `cornerRadius` overrides the theme; `rim` draws a thin light edge like tvOS Liquid Glass icons.

**App sources** (`source`):

- `{ "type": "list", "list": "favorites" }` — a named app list (see "App lists" below). The layout only names the list; the TV stores its apps.
- `{ "type": "all", "sort": "custom" | "alphabetical" | "recently_installed", "exclude": ["…"], "includeNonTvApps": false }` — every TV app. `custom` (default) is the order arranged on the TV; apps never moved follow alphabetically.
- `{ "type": "category", "category": "games" | "apps" | "mobile", "sort": "alphabetical", "exclude": [] }` — games, everything that is not a game, or `mobile`: phone apps sideloaded onto the TV (no TV launcher entry).
- `{ "type": "recent", "exclude": [] }` — most recently used first. Starts empty and fills as apps are opened.

Widgets:

- **`app_row`**: horizontally scrolling tiles. `source` (required), `title`, `titleIcon` (icon before the title, see `shortcut`), `tile`, `spacing` (16), `skip` (leave out the first N apps), `limit`, `missing` (`hide` default, or `placeholder` for apps in a list that are not installed), `scrollAlign` (`start` keeps the focused tile at the left like Google TV and Projectivy; `center`; `nearest` default), `scrollMargin` (with `start`: distance of the focused tile from the row's left edge, default 0, so it lines up with the title), `scrollMotion` (see Theme). Width fills by default. A row on an empty list shows an "Add apps" tile; other rows with no apps are hidden.
- **`app_grid`**: tiles in `columns` (default 5) that wrap onto new rows. Same properties as `app_row` except scrolling. Put it in a scrolling column if it may be taller than the screen. `skip` lets a grid continue where a row above stopped: a row with `"limit": 5` and a grid with `"skip": 5` on the same source show every app once.
- **`watch_next_row`**: a row of what the TV's apps offer to continue watching (Android TV's Watch Next, the data behind Google TV's "Continue watching" row): a poster, a progress bar along the bottom for entries that report how far they are watched, and the entry's title as the tile label. OK resumes the entry in its app. Properties: `title`, `titleIcon`, `tile` (default `banner`; the TV banner is not used, the entry's own artwork is), `spacing` (16), `skip`, `limit`, `exclude` (package names of apps whose entries are left out), `showProgress` (default true), `scrollAlign`, `scrollMargin`, `scrollMotion` (as `app_row`). Entries come most recently watched first. For `{focused}` the entry's title is used, for `{description}` its app and the time left, e.g. `Nuvio · 1h 18min left`. The TV asks for the permission to read these entries (it is called "TV listings") the first time: until it is granted the row shows one "Allow access to continue watching" tile, afterwards a row with no entries is hidden and takes no space, so a `showWhenFocused` details column below it with `keepSpace` stays out of the way too. Previews (editor, looks, web) show sample entries. OwnTV only reads the entries: an app removes its own entries when you finish watching.
- **`app_tile`**: one app. `package` (required), `label`, `tile`, `missing` (`placeholder` default, or `hide`).
- **`folder`**: a tile with small app images (a 3 × 3 grid of mini banners, or six icons with an `icon` tile) that opens a panel with its apps. `label` and `source` (both required), `tile`, `itemTile` (tiles in the panel), `columns` (default 4), `color` (tile color; a translucent color such as `#33FFFFFF` looks like tvOS glass).
- **`shortcut`**: a custom tile. `label` and `action` (both required), `icon`, `image` (URL or `asset:`), `color` (tile color), `iconColor` (icon and label on the tile, default `@text`), `focusColor` and `focusIconColor` (the same while focused, e.g. a light tile that turns dark), `tile` (default `card`), and `description` / `backdrop` for a hero or the `{description}` placeholder. Icons: `settings`, `home`, `search`, `star`, `info`, `info_outline`, `play`, `favorite`, `apps`, `add`, `add_circle`, `refresh`, `notifications`, `person`, `lock`, `menu`, `tv`, `live_tv`, `input`, `hdmi`, `smartphone`, `display`, `system`, `accessibility`, `android`, `wallpaper`, `wifi`, `wifi_off`, `ethernet`, or any emoji such as `"🎬"`. `card`/`banner` tiles show icon and label inside (`"showLabel": "never"` leaves only the icon); `icon` tiles show the label below.

## Hero (Top Shelf / carousel)

**`hero`**: a large banner area; width fills, default height 280.

- `"follow": "focus"` (default): shows the focused app or shortcut — its `backdrop` image and `description` from `apps` (or the shortcut), with its name as the title. Apps without a backdrop show a blurred version of their banner. `target` limits it to focus inside one node (e.g. the dock row); while focus is elsewhere the last item stays. `slides` are shown until something is focused.
- `"follow": "slides"`: rotates through `slides` every `interval` seconds (default 8). If any slide has an `action`, the hero is focusable: OK runs the current slide's action and Left/Right switch slides.
- A slide: `{ "title": "…", "description": "…", "background": <background>, "action": <action> }`. Titles and descriptions may use placeholders.
- `showText` (default true), `scrim` 0–1 (darkness behind the text, default 0.6), `contentAlign` (where the text sits, default `bottom_start`), `fade` 0–1 (share of the height at the bottom that fades out into what is behind, default 0), `cornerRadius` via the common property.

Apple TV pattern (tvOS 26/27): a column with `"scroll": true, "scrollAlign": "start"` holding a `stack` with `"revealOnFocus": true` and a fixed height. In the stack, a hero fills everything (`"target": "dock"`, `"fade": 0.3`), and the `dock` app_row (`"limit": 5`, a `glass` background and a `cornerRadius`) sits at the bottom, so the Top Shelf runs full-bleed behind the glass dock. Give the dock `"tile": { "showLabel": "never" }`: the Top Shelf already shows the focused app's name, and a label would take room below the tiles, so they would not sit centered in the glass. An `app_grid` with `"skip": 5` follows the stack. See the built-in Apple TV look.

Projectivy pattern: a column with `"scroll": true, "scrollAlign": "start"` and a large `scrollMargin` (about 240) holds one column per category ("section"), each with `"unfocusedOpacity": 0.5` and `"revealOnFocus": true`. A section holds a title (an `app_row` or `watch_next_row` `title` with `titleIcon`, or a `text` with `icon`), the row, and a details column with `"showWhenFocused": "<section id>"`, `"keepSpace": true` and a fixed `height` holding `{focused}` and `{description}` texts, so moving between sections only scrolls and nothing gets pushed around. The focused section then sits at the same height every time, bright, with the name and description of the focused tile under it. Shortcut sections use `card` tiles with `"showLabel": "never"` and `focusColor` / `focusIconColor` so the focused tile turns dark. Give the page `root` a translucent gradient `background` to tint a wallpaper image. See the built-in Projectivy look.

## Content widgets (not focusable)

- **`text`**: `text` (required), `textStyle` (`display` 56, `headline` 34, `title` 22, `body` 16, `caption` 12), `fontSize` (sp, overrides the preset), `font` (`sans` / `serif` / `mono` / `cursive`), `color`, `bold`, `uppercase`, `letterSpacing` (sp), `textAlign` (`start` / `center` / `end`), `maxLines`.
  `icon`: an icon before the text in its size and color (names as for `shortcut`, or an emoji).
  Placeholders: `{greeting}` (Good morning/afternoon/evening/night), `{time}` (HH:mm) or `{time:h:mm a}`, `{date}` (e.g. "Sunday, 18 October") or `{date:d MMM}`, `{focused}` (name of the focused tile), `{description}` (its `description`, from the shortcut or `apps`), `{page}` (page title). `{{` and `}}` write literal braces.
- **`clock`**: `format` (java.time pattern; `"HH:mm"` default, `"h:mm a"`, `"EEEE, d MMMM"` for a date), `textStyle` (default `display`), `fontSize`, `font`, `color`, `bold`, `letterSpacing`, `textAlign`.
- **`network`**: a Wi-Fi, Ethernet or offline icon that follows the TV's connection. `size` (dp, default 24), `color`.
- **`image`**: `src` (required; `https://…` or `asset:<path>`), `fit` (`contain` default, `cover`, `fill`), `alpha` 0–1. Give it a `width` and `height`.
- **`spacer`**: empty space; use `width`/`height` or `weight`.

## Styles and defaults

`defaults` applies to every node of a type; `styles` apply to nodes that name them with `"style"`. Precedence, lowest first: type default → the style's own base style (a style may set `"style"` to build on another) → the style → the node itself. Objects such as `tile` merge key by key; `source`, `action` and `background` are replaced whole. Keys a node type does not have are skipped, so one style can serve several types. Styles cannot set `type`, `id` or `children`. A node property only overrides a style when it differs from that property's default.

```json
"styles": {
  "pill": { "color": "#00000000", "tile": { "shape": "icon", "size": "small", "cornerRadius": 28 } },
  "pill_label": { "style": "pill", "tile": { "showLabel": "always" } }
}
```

## Apps

Per-app settings, used everywhere the app appears:

```json
"apps": {
  "com.netflix.ninja": {
    "label": "Netflix",
    "banner": "https://…/netflix-wide.png",
    "icon": "https://…/netflix-square.png",
    "backdrop": "https://…/netflix-hero.jpg",
    "description": "Series and films",
    "color": "#141414"
  }
}
```

`banner` replaces the artwork on banner and card tiles, `icon` on icon tiles, `color` is the tile background, `backdrop` and `description` feed heroes. Hiding apps is done on the TV (long press, Hide), not in the layout.

## App lists

Which apps a row, grid or folder shows is not part of the layout. A `list` source names a list, and the TV keeps the apps of every list, the custom order of all apps and the hidden apps. Switching layouts or looks keeps them, and lists with the same name share their apps across layouts. Common names: `favorites`, `games`, `kids`, `music`. A list the TV does not know yet starts empty and shows an "Add apps" tile.

On the TV, long-press an app tile for:

- **Move**: Left/Right (and Up/Down in grids) slide the tile, OK keeps the new place, Back cancels. Works in lists, folders and `all` sources sorted `custom`.
- **Add to…**: put the app into any list the layout uses, including folders.
- **Remove from …**: take it out of the list it was pressed in.
- **Hide**: leave the app out of every row, grid and folder. Hidden apps stay in the app drawer and under Settings > Hidden apps.

## Actions (for `shortcut.action` and slides)

- `{ "type": "launch_app", "package": "com.netflix.ninja" }`
- `{ "type": "open_url", "url": "https://example.com" }` (also deep links)
- `{ "type": "intent", "action": "android.intent.action.VIEW", "data": "…", "package": "…", "component": "pkg/.Activity", "categories": [], "extras": { "key": "value" } }` — needs at least one of action, package, component.
- `{ "type": "open_input", "input": "hdmi1" }` — switches the TV to an input: `hdmi1` to `hdmi4` or `av`. Only TVs have inputs; other devices show a message.
- `{ "type": "go_to_page", "page": "apps" }`
- `{ "type": "open_app_drawer" }` — built-in list of all apps.
- `{ "type": "next_wallpaper" }` — shows the next wallpaper of the screen's `slideshow` background at once (the page's, else the layout's), e.g. from a shortcut with the `wallpaper` icon.
- `{ "type": "open_system_settings", "screen": "main" | "network" | "bluetooth" | "display" | "sound" | "apps" | "date_time" | "accessibility" }`
- `{ "type": "open_launcher_settings" }` — OwnTV's own settings: import, looks, templates, history, the layout editor and an entry that opens the Android settings. **Every layout needs at least one visible, focusable link to it** (a `shortcut` with this action, or a hero slide with it when `follow` is `slides`); see Validation.

## Complete example

```json
{
  "schemaVersion": 3,
  "name": "Movie night",
  "theme": { "colors": { "primary": "#E50914", "custom": { "hero": "#2A0E12" } } },
  "background": { "type": "gradient", "colors": ["#2A0E12", "#0B0D12"], "angle": 180 },
  "styles": { "small_icon": { "tile": { "shape": "icon", "size": "small" } } },
  "apps": { "com.netflix.ninja": { "description": "Series and films" } },
  "pages": [
    {
      "id": "home",
      "initialFocus": "streaming",
      "root": {
        "type": "column", "id": "root", "padding": [48, 28], "spacing": 20, "scroll": true,
        "children": [
          {
            "type": "row", "id": "header", "width": "fill",
            "children": [
              { "type": "text", "id": "title", "text": "{greeting}", "textStyle": "headline", "bold": true },
              { "type": "spacer", "id": "gap", "weight": 1 },
              { "type": "clock", "id": "clock", "format": "HH:mm", "textStyle": "title" }
            ]
          },
          { "type": "hero", "id": "spotlight", "target": "streaming", "height": 200, "cornerRadius": 16 },
          {
            "type": "app_row", "id": "streaming", "title": "Streaming",
            "source": { "type": "list", "list": "favorites" },
            "tile": { "shape": "card", "size": "large" }
          },
          {
            "type": "row", "id": "actions", "spacing": 16,
            "children": [
              { "type": "shortcut", "id": "all", "label": "All apps", "icon": "apps", "style": "small_icon", "action": { "type": "open_app_drawer" } },
              { "type": "shortcut", "id": "settings", "label": "Settings", "icon": "settings", "style": "small_icon", "action": { "type": "open_launcher_settings" } }
            ]
          },
          { "type": "app_grid", "id": "everything", "title": "All apps", "columns": 6, "source": { "type": "all" }, "tile": { "shape": "banner", "size": "small" } }
        ]
      }
    }
  ]
}
```

## Validation

OwnTV checks every layout before it can be applied and reports problems with a JSON path, for example `ERROR at $.pages[0].root.children[1].source: Missing required property "list".` Errors block the layout; a layout without a visible `open_launcher_settings` link is an error, because the TV could not reach OwnTV's settings (and through them the Android settings) any more. Warnings (unknown properties or placeholders, apps that are not installed, pages with nothing to focus, focusable widgets on the screensaver page) do not. Text around a ```json code block, comments and trailing commas are tolerated.
