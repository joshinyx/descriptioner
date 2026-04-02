package dev.joshiny.descriptioner

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

class DescriptionManager(private val plugin: Descriptioner) {

    private val bundledLocales = listOf("en_US", "es_ES")
    private val localeDescriptions = mutableMapOf<String, Map<String, String>>()
    private val localeDescriptionLines = mutableMapOf<String, Map<String, Component>>()
    private var activeLocale = "es_ES"

    fun load() {
        ensureBundledLanguageFiles()

        localeDescriptions.clear()
        localeDescriptionLines.clear()

        val languagesDir = File(plugin.dataFolder, "languages")
        if (!languagesDir.exists()) {
            languagesDir.mkdirs()
        }

        val languageFiles = languagesDir.listFiles { file ->
            file.isFile && file.extension.equals("yml", ignoreCase = true)
        }?.sortedBy { it.name } ?: emptyList()

        for (file in languageFiles) {
            val locale = file.nameWithoutExtension
            val config = YamlConfiguration.loadConfiguration(file)
            val descriptions = mutableMapOf<String, String>()
            for (key in config.getKeys(false)) {
                config.getString(key)?.let { descriptions[key] = it }
            }

            localeDescriptions[locale] = descriptions
            localeDescriptionLines[locale] = descriptions.mapValues { (_, description) ->
                Component.text(description)
                    .color(NamedTextColor.DARK_GRAY)
                    .decoration(TextDecoration.ITALIC, false)
            }

            plugin.logger.info("Loaded ${descriptions.size} enchantment descriptions for $locale")
        }

        if (localeDescriptions.isEmpty()) {
            plugin.logger.warning("No language files found in plugins/Descriptioner/languages")
        }

        activeLocale = resolveConfiguredLocale()
        plugin.logger.info("Active description locale: $activeLocale")
    }

    fun getDescriptionLine(enchantmentKey: String): Component? {
        return localeDescriptionLines[activeLocale]?.get(enchantmentKey)
    }

    fun getActiveLocale(): String {
        return activeLocale
    }

    private fun ensureBundledLanguageFiles() {
        for (locale in bundledLocales) {
            val resourcePath = "languages/$locale.yml"
            val file = File(plugin.dataFolder, resourcePath)
            if (!file.exists()) {
                plugin.saveResource(resourcePath, false)
            }
        }
    }

    private fun resolveConfiguredLocale(): String {
        val configured = plugin.config.getString("language", "es_ES") ?: "es_ES"
        val fallback = plugin.config.getString("fallback-language", "en_US") ?: "en_US"

        return when {
            localeDescriptionLines.containsKey(configured) -> configured
            localeDescriptionLines.containsKey(fallback) -> fallback
            localeDescriptionLines.containsKey("en_US") -> "en_US"
            localeDescriptionLines.isNotEmpty() -> localeDescriptionLines.keys.first()
            else -> "es_ES"
        }
    }
}
