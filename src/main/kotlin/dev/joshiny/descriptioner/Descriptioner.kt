package dev.joshiny.descriptioner

import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class Descriptioner : JavaPlugin() {

    lateinit var descriptionManager: DescriptionManager

    override fun onEnable() {
        normalizeConfigTabsIfNeeded()
        saveDefaultConfig()

        logger.info(" ██████╗ ███████╗███████╗ ██████╗██████╗ ██╗██████╗ ████████╗██╗ ██████╗ ███╗   ██╗███████╗██████╗ ")
        logger.info(" ██╔══██╗██╔════╝██╔════╝██╔════╝██╔══██╗██║██╔══██╗╚══██╔══╝██║██╔═══██╗████╗  ██║██╔════╝██╔══██╗")
        logger.info(" ██║  ██║█████╗  ███████╗██║     ██████╔╝██║██████╔╝   ██║   ██║██║   ██║██╔██╗ ██║█████╗  ██████╔╝")
        logger.info(" ██║  ██║██╔══╝  ╚════██║██║     ██╔══██╗██║██╔═══╝    ██║   ██║██║   ██║██║╚██╗██║██╔══╝  ██╔══██╗")
        logger.info(" ██████╔╝███████╗███████║╚██████╗██║  ██║██║██║        ██║   ██║╚██████╔╝██║ ╚████║███████╗██║  ██║")
        logger.info(" ╚═════╝ ╚══════╝╚══════╝ ╚═════╝╚═╝  ╚═╝╚═╝╚═╝        ╚═╝   ╚═╝ ╚═════╝ ╚═╝  ╚═══╝╚══════╝╚═╝  ╚═╝")

        descriptionManager = DescriptionManager(this)
        descriptionManager.load()
        server.pluginManager.registerEvents(TooltipListener(this), this)

        if (!config.getBoolean("enchant-name-styling.enabled", false)) {
            logger.info("Custom enchant name styling is disabled.")
            logger.info("Set enchant-name-styling.enabled: true in plugins/Descriptioner/config.yml and restart.")
            logger.info("Declare per-enchant/per-level rules in enchant-name-styling.enchant-overrides.")
        }

        logger.info("Descriptioner enabled")
    }

    override fun onDisable() {
        logger.info("Descriptioner disabled")
    }

    private fun normalizeConfigTabsIfNeeded() {
        val configFile = File(dataFolder, "config.yml")
        if (!configFile.exists()) return

        val raw = configFile.readText(Charsets.UTF_8)
        if (!raw.contains('\t')) return

        val backup = File(dataFolder, "config.yml.bak-tabs")
        configFile.copyTo(backup, overwrite = true)
        configFile.writeText(raw.replace("\t", "  "), Charsets.UTF_8)

        logger.warning("Detected TAB indentation in config.yml. Replaced tabs with spaces automatically.")
        logger.warning("Backup created at ${backup.path}")
    }
}
