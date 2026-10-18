package dev.owntv.layout.model

/** Layouts that exist in code, so the launcher is usable even when every file is broken. */
object BuiltInLayouts {
    fun fallback(): LayoutDocument = LayoutDocument(
        name = "Fallback",
        pages = listOf(
            Page(
                id = "home",
                root = ColumnNode(
                    id = "root",
                    padding = Padding.symmetric(48, 32),
                    spacing = 24,
                    scroll = true,
                    children = listOf(
                        RowNode(
                            id = "header",
                            width = Size.Fill,
                            children = listOf(
                                ClockNode(id = "clock"),
                                SpacerNode(id = "header_gap", weight = 1f),
                                ShortcutNode(
                                    id = "settings",
                                    label = "Launcher settings",
                                    icon = "settings",
                                    action = OpenLauncherSettingsAction,
                                    tile = TileStyle(style = TileShape.Icon, size = TileSize.Small),
                                ),
                            ),
                        ),
                        AppGridNode(
                            id = "all_apps",
                            title = "All apps",
                            source = AllAppsSource(),
                            columns = 5,
                        ),
                    ),
                ),
            ),
        ),
    )
}
