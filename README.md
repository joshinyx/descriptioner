```
 ██████╗ ███████╗███████╗ ██████╗██████╗ ██╗██████╗ ████████╗██╗ ██████╗ ███╗   ██╗███████╗██████╗ 
 ██╔══██╗██╔════╝██╔════╝██╔════╝██╔══██╗██║██╔══██╗╚══██╔══╝██║██╔═══██╗████╗  ██║██╔════╝██╔══██╗
 ██║  ██║█████╗  ███████╗██║     ██████╔╝██║██████╔╝   ██║   ██║██║   ██║██╔██╗ ██║█████╗  ██████╔╝
 ██║  ██║██╔══╝  ╚════██║██║     ██╔══██╗██║██╔═══╝    ██║   ██║██║   ██║██║╚██╗██║██╔══╝  ██╔══██╗
 ██████╔╝███████╗███████║╚██████╗██║  ██║██║██║        ██║   ██║╚██████╔╝██║ ╚████║███████╗██║  ██║
 ╚═════╝ ╚══════╝╚══════╝ ╚═════╝╚═╝  ╚═╝╚═╝╚═╝        ╚═╝   ╚═╝ ╚═════╝ ╚═╝  ╚═══╝╚══════╝╚═╝  ╚═╝
```

# Descriptioner

A lightweight Paper plugin for Minecraft 1.21.4 that injects readable enchantment descriptions directly into item tooltips.

Descriptioner keeps vanilla enchant visuals while adding short lore lines under each enchantment, with configurable language files and optional advanced styling.

## Highlights

- Supports Paper/Purpur 1.21.4
- Kotlin-based, modern and fast implementation
- Uses Adventure components (no legacy ChatColor formatting)
- Language files are editable YAML files
- Built-in `en_US` and `es_ES`
- Auto fallback locale support
- Handles regular enchanted items and enchanted books
- Marker-based tooltip management for deterministic, idempotent rebuilds
- Includes `/descriptioner normalize [batchSize]` admin command for bulk normalization
- Safe behavior when a description is missing (silently skipped)
- Optional per-enchant/per-level styling rules

## Compatibility

- Java: 21+
- Server software:
  - Paper 1.21.4
  - Purpur 1.21.4 (supported)

## Installation

1. Download or build the plugin jar.
2. Put the jar in your server `plugins/` folder.
3. Start the server once to generate default files.
4. Configure `plugins/Descriptioner/config.yml` if needed.
5. Restart the server.

## Build From Source

```bash
./gradlew shadowJar
```

Generated artifact:

- `build/libs/Descriptioner-1.0.2.jar`

## Configuration

Main file: `plugins/Descriptioner/config.yml`

Key settings:

- `language`: active locale file name (without extension)
- `fallback-language`: fallback locale if the main one is missing
- `refresh-period-ticks`: inventory sync interval
- `enchant-name-styling.enabled`: enable advanced enchant title styling
- `enchant-name-styling.enchant-overrides`: per-enchant/per-level visual overrides

### Placeholder Support (segments)

Available in `name-segments` and `level-segments`:

- `{name}`
- `{level_roman}`
- `{level_arabic}`

## Language Files

Directory:

- `plugins/Descriptioner/languages/`

Bundled files:

- `en_US.yml`
- `es_ES.yml`

Each YAML key must match the vanilla enchantment key (for example `sharpness`, `unbreaking`, `mending`).

## How It Works

For each enchanted item tooltip:

1. Descriptioner reads the enchant list.
2. It appends one line for the enchant name/level.
3. It appends one line for the localized description (if configured).
4. If no description exists for an enchant, that enchant is left without a description line.

## Troubleshooting

### Descriptions are not showing

- Verify server is Paper/Purpur 1.21.4.
- Verify Java 21 is being used.
- Check if your selected locale file exists.
- Check server logs for config YAML errors.

### Colors or style changes do not apply

- Restart the server after config edits.
- Reopen inventories/items to force tooltip refresh.

### YAML parsing errors

- Use spaces for indentation, not tabs.
- Keep valid YAML block structure (avoid mixed inline map + nested block).

## Notes

This project is intentionally focused on vanilla enchantment descriptions only.
No custom enchant system, GUI, or command layer is included by design.