package dev.joshiny.descriptioner

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class Descriptioner : JavaPlugin() {

    lateinit var descriptionManager: DescriptionManager
    lateinit var tooltipListener: TooltipListener

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
        tooltipListener = TooltipListener(this)
        server.pluginManager.registerEvents(tooltipListener, this)

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

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        if (!command.name.equals("descriptioner", ignoreCase = true)) {
            return false
        }

        if (args.isEmpty() || !args[0].equals("normalize", ignoreCase = true)) {
            sender.sendMessage("Usage: /$label normalize [batchSize]")
            return true
        }

        if (!sender.hasPermission("descriptioner.admin")) {
            sender.sendMessage("You do not have permission to use this command.")
            return true
        }

        val batchSize = if (args.size >= 2) {
            args[1].toIntOrNull()?.coerceIn(1, 5000)
        } else {
            200
        }

        if (batchSize == null) {
            sender.sendMessage("Invalid batch size. Use a number between 1 and 5000.")
            return true
        }

        if (tooltipListener.isNormalizeRunning()) {
            sender.sendMessage("Descriptioner normalize is already running.")
            return true
        }

        val started = tooltipListener.normalizeLoadedItemsBatched(batchSize) { summary ->
            sender.sendMessage(
                "Descriptioner normalize complete: scanned=${summary.scanned}, " +
                    "updated=${summary.updated}, players=${summary.players}, " +
                    "world-drops=${summary.worldDrops}, container-slots=${summary.containerSlots}"
            )
        }

        if (started) {
            sender.sendMessage("Descriptioner normalize started in background (batchSize=$batchSize).")
        } else {
            sender.sendMessage("Descriptioner normalize could not start right now.")
        }

        return true
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
