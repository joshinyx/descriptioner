package dev.joshiny.descriptioner

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.event.player.*
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.scheduler.BukkitTask
import org.bukkit.configuration.ConfigurationSection
import java.util.UUID
import java.util.Locale

class TooltipListener(private val plugin: Descriptioner) : Listener {

    companion object {
        private const val FORMAT_VERSION = 5
    }

    private val processedKey = NamespacedKey(plugin, "has_descriptions")
    private val baseLoreSizeKey = NamespacedKey(plugin, "base_lore_size")
    private val legacyOriginalLoreSizeKey = NamespacedKey(plugin, "original_lore_size")
    private val originalHideEnchantsKey = NamespacedKey(plugin, "original_hide_enchants")
    private val originalHideStoredEnchantsKey = NamespacedKey(plugin, "original_hide_stored_enchants")
    private val appliedLocaleKey = NamespacedKey(plugin, "applied_locale")
    private val enchantSignatureKey = NamespacedKey(plugin, "enchant_signature")
    private val formatVersionKey = NamespacedKey(plugin, "format_version")
    private val styleFingerprintKey = NamespacedKey(plugin, "style_fingerprint")
    private val refreshTasks = mutableMapOf<UUID, BukkitTask>()
    private val enchantNameStyle = EnchantNameStyle.fromConfig(plugin)
    private val styleFingerprint = buildStyleFingerprint()

    // Inject on join so player inventory items are immediately prepared.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        forEachPlayerInventoryItem(player) { injectDescriptions(it) }
    }

    // Stop background refresh task when player disconnects.
    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        stopAutoRefresh(event.player.uniqueId)
    }

    // Inject currently selected held item.
    @EventHandler(ignoreCancelled = true)
    fun onItemHeld(event: PlayerItemHeldEvent) {
        event.player.inventory.getItem(event.newSlot)?.let { injectDescriptions(it) }
    }

    // Keep open views synced while players interact with dynamic inventories.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onInventoryOpen(event: InventoryOpenEvent) {
        val player = event.player as? Player ?: return
        startAutoRefresh(player)
    }

    // Inject directly into anvil preview output so upgraded levels are visible immediately.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPrepareAnvil(event: PrepareAnvilEvent) {
        val result = event.result ?: return
        if (result.type.isAir) return

        val updated = result.clone()
        if (injectDescriptions(updated)) {
            event.result = updated
        }
    }

    // Stop open-view sync when inventory closes.
    @EventHandler
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        stopAutoRefresh(player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        plugin.server.scheduler.runTask(plugin, Runnable {
            injectForOpenView(player)
        })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        plugin.server.scheduler.runTask(plugin, Runnable {
            injectForOpenView(player)
        })
    }

    // Ensure dropped items also carry permanent descriptions.
    @EventHandler(ignoreCancelled = true)
    fun onPlayerDropItem(event: PlayerDropItemEvent) {
        injectDescriptions(event.itemDrop.itemStack)
    }

    // Ensure death drops carry permanent descriptions.
    @EventHandler
    fun onPlayerDeath(event: PlayerDeathEvent) {
        event.drops.forEach { injectDescriptions(it) }
    }

    @EventHandler(ignoreCancelled = true)
    fun onSwapHands(event: PlayerSwapHandItemsEvent) {
        injectDescriptions(event.mainHandItem)
        injectDescriptions(event.offHandItem)
    }

    private fun injectDescriptions(item: ItemStack): Boolean {
        if (item.type.isAir) return false
        val meta = item.itemMeta ?: return false
        val pdc = meta.persistentDataContainer
        val currentLore = meta.lore() ?: emptyList()

        val enchantments = collectEnchantments(item, meta)
        val wasProcessed = pdc.has(processedKey, PersistentDataType.BYTE)
        val appliedVersion = pdc.get(formatVersionKey, PersistentDataType.INTEGER)
        val baseLore = if (wasProcessed) {
            val baseLoreSize =
                pdc.get(legacyOriginalLoreSizeKey, PersistentDataType.INTEGER)
                    ?: pdc.get(baseLoreSizeKey, PersistentDataType.INTEGER)
                    ?: currentLore.size
            currentLore.take(minOf(baseLoreSize, currentLore.size)).toMutableList()
        } else {
            currentLore.toMutableList()
        }
        val normalizedBaseLore = if (!wasProcessed || appliedVersion == null || appliedVersion < FORMAT_VERSION) {
            sanitizeLegacyInjectedLore(baseLore, enchantments)
        } else {
            baseLore
        }

        val originalHideEnchants = if (wasProcessed) {
            pdc.get(originalHideEnchantsKey, PersistentDataType.BYTE)?.toInt() == 1
        } else {
            meta.hasItemFlag(ItemFlag.HIDE_ENCHANTS)
        }
        val originalHideStoredEnchants = if (wasProcessed) {
            pdc.get(originalHideStoredEnchantsKey, PersistentDataType.BYTE)?.toInt() == 1
        } else {
            meta.hasItemFlag(ItemFlag.HIDE_STORED_ENCHANTS)
        }

        if (enchantments.isEmpty()) {
            if (!wasProcessed) return false

            clearProcessingKeys(pdc)
            if (originalHideEnchants) {
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
            } else {
                meta.removeItemFlags(ItemFlag.HIDE_ENCHANTS)
            }
            if (originalHideStoredEnchants) {
                meta.addItemFlags(ItemFlag.HIDE_STORED_ENCHANTS)
            } else {
                meta.removeItemFlags(ItemFlag.HIDE_STORED_ENCHANTS)
            }
            meta.lore(normalizedBaseLore.ifEmpty { null })
            item.itemMeta = meta
            return true
        }

        val activeLocale = plugin.descriptionManager.getActiveLocale()
        val signature = buildEnchantSignature(enchantments)
        val appliedLocale = pdc.get(appliedLocaleKey, PersistentDataType.STRING)
        val appliedSignature = pdc.get(enchantSignatureKey, PersistentDataType.STRING)
        val appliedStyleFingerprint = pdc.get(styleFingerprintKey, PersistentDataType.STRING)

        if (wasProcessed &&
            appliedLocale == activeLocale &&
            appliedSignature == signature &&
            appliedVersion == FORMAT_VERSION &&
            appliedStyleFingerprint == styleFingerprint
        ) {
            return false
        }

        val injectedLines = mutableListOf<Component>()
        for ((enchantment, level) in enchantments) {
            injectedLines.add(buildEnchantLine(enchantment, level))
            plugin.descriptionManager.getDescriptionLine(enchantment.key.key)?.let { descLine ->
                injectedLines.add(descLine)
            }
        }

        if (injectedLines.isEmpty()) return false

        val hadSpacer = normalizedBaseLore.isNotEmpty()
        pdc.set(processedKey, PersistentDataType.BYTE, 1)
        pdc.set(baseLoreSizeKey, PersistentDataType.INTEGER, normalizedBaseLore.size)
        pdc.remove(legacyOriginalLoreSizeKey)
        pdc.set(
            originalHideEnchantsKey,
            PersistentDataType.BYTE,
            if (originalHideEnchants) 1 else 0
        )
        pdc.set(
            originalHideStoredEnchantsKey,
            PersistentDataType.BYTE,
            if (originalHideStoredEnchants) 1 else 0
        )
        pdc.set(appliedLocaleKey, PersistentDataType.STRING, activeLocale)
        pdc.set(enchantSignatureKey, PersistentDataType.STRING, signature)
        pdc.set(formatVersionKey, PersistentDataType.INTEGER, FORMAT_VERSION)
        pdc.set(styleFingerprintKey, PersistentDataType.STRING, styleFingerprint)

        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_STORED_ENCHANTS)

        val newLore = normalizedBaseLore.toMutableList()
        if (hadSpacer) {
            newLore.add(Component.empty())
        }
        newLore.addAll(injectedLines)
        meta.lore(newLore)
        item.itemMeta = meta
        return true
    }

    private fun collectEnchantments(item: ItemStack, meta: org.bukkit.inventory.meta.ItemMeta): List<Pair<Enchantment, Int>> {
        val enchantments = LinkedHashMap<NamespacedKey, Pair<Enchantment, Int>>()

        for ((enchantment, level) in item.enchantments) {
            enchantments.putIfAbsent(enchantment.key, enchantment to level)
        }

        if (meta is EnchantmentStorageMeta) {
            for ((enchantment, level) in meta.storedEnchants) {
                enchantments.putIfAbsent(enchantment.key, enchantment to level)
            }
        }

        return enchantments.values.toList()
    }

    private fun buildEnchantSignature(enchantments: List<Pair<Enchantment, Int>>): String {
        return enchantments
            .map { (enchantment, level) -> "${enchantment.key.asString()}:$level" }
            .sorted()
            .joinToString(";")
    }

    private fun sanitizeLegacyInjectedLore(
        baseLore: MutableList<Component>,
        enchantments: List<Pair<Enchantment, Int>>
    ): MutableList<Component> {
        if (baseLore.isEmpty()) return baseLore

        val enchantLines = enchantments.map { (enchantment, level) -> buildEnchantLine(enchantment, level) }.toSet()
        val descriptionLines = enchantments.mapNotNull { (enchantment, _) ->
            plugin.descriptionManager.getDescriptionLine(enchantment.key.key)
        }.toSet()

        var removed = 0
        while (baseLore.isNotEmpty()) {
            val last = baseLore.last()
            if (last in enchantLines || last in descriptionLines) {
                baseLore.removeAt(baseLore.lastIndex)
                removed++
                continue
            }
            break
        }

        if (removed > 0 && baseLore.isNotEmpty() && baseLore.last() == Component.empty()) {
            baseLore.removeAt(baseLore.lastIndex)
        }

        return baseLore
    }

    private fun buildEnchantLine(enchantment: Enchantment, level: Int): Component {
        val enchantmentKey = enchantment.key
        val isCurse = enchantmentKey.key.contains("curse")
        val styleActive = enchantNameStyle.enabled

        val nameColor = when {
            !styleActive && isCurse -> NamedTextColor.RED
            !styleActive -> NamedTextColor.GRAY
            isCurse -> enchantNameStyle.curseNameColor
            else -> enchantNameStyle.normalNameColor
        }
        val levelColor = when {
            !styleActive && isCurse -> NamedTextColor.RED
            !styleActive -> NamedTextColor.GRAY
            isCurse -> enchantNameStyle.curseLevelColor
            else -> enchantNameStyle.normalLevelColor
        }

        val override = if (styleActive) {
            enchantNameStyle.findOverride(enchantment.key, level)
        } else {
            null
        }
        val showLevel = enchantNameStyle.alwaysShowLevel || level > 1 || enchantment.maxLevel > 1
        val roman = toRoman(level)
        val arabic = level.toString()
        val fallbackName = prettifyEnchantmentKey(enchantment.key.key)
        val literalName = override?.literalName ?: fallbackName

        if (styleActive && override != null && (override.nameSegments.isNotEmpty() || override.levelSegments.isNotEmpty())) {
            var line = Component.empty()

            val nameSegments = if (override.nameSegments.isNotEmpty()) {
                override.nameSegments
            } else {
                listOf(TextSegment("{name}", override.nameColor))
            }

            for (segment in nameSegments) {
                line = line.append(
                    Component.text(
                        applyTemplate(segment.text, literalName, roman, arabic)
                    ).color(segment.color)
                        .decoration(TextDecoration.ITALIC, false)
                )
            }

            if (showLevel) {
                val levelSegments = if (override.levelSegments.isNotEmpty()) {
                    override.levelSegments
                } else {
                    emptyList()
                }

                if (levelSegments.isNotEmpty()) {
                    for (segment in levelSegments) {
                        line = line.append(
                            Component.text(
                                applyTemplate(segment.text, literalName, roman, arabic)
                            ).color(segment.color)
                                .decoration(TextDecoration.ITALIC, false)
                        )
                    }
                } else {
                    val effectiveLevelColor = override.levelColor
                    line = line.append(
                        Component.text(" ")
                            .color(effectiveLevelColor)
                            .decoration(TextDecoration.ITALIC, false)
                    ).append(
                        buildRomanComponent(roman, effectiveLevelColor, false)
                    )
                }
            }

            return line
        }

        val useTranslatableName = override?.useTranslatableName ?: true

        var line = if (useTranslatableName) {
            Component.translatable("enchantment.${enchantmentKey.namespace}.${enchantmentKey.key}")
        } else {
            Component.text(literalName)
        }

        line = line.color(override?.nameColor ?: nameColor)
            .decoration(TextDecoration.ITALIC, false)

        if (showLevel) {
            val effectiveLevelColor = override?.levelColor ?: levelColor
            line = line.append(
                Component.text(" ")
                    .color(effectiveLevelColor)
                    .decoration(TextDecoration.ITALIC, false)
            ).append(
                buildRomanComponent(
                    roman,
                    effectiveLevelColor,
                    styleActive && enchantNameStyle.romanCharColorsEnabled
                )
            )
        }

        return line
    }

    private fun toRoman(value: Int): String {
        if (value <= 0) return value.toString()

        val numerals = arrayOf(
            1000 to "M",
            900 to "CM",
            500 to "D",
            400 to "CD",
            100 to "C",
            90 to "XC",
            50 to "L",
            40 to "XL",
            10 to "X",
            9 to "IX",
            5 to "V",
            4 to "IV",
            1 to "I"
        )

        var remaining = value
        val result = StringBuilder()
        for ((arabic, roman) in numerals) {
            while (remaining >= arabic) {
                result.append(roman)
                remaining -= arabic
            }
        }

        return result.toString()
    }

    private inline fun forEachPlayerInventoryItem(player: Player, action: (ItemStack) -> Unit) {
        for (item in player.inventory.contents) {
            if (item != null && !item.type.isAir) {
                action(item)
            }
        }
    }

    private fun injectForOpenView(player: Player) {
        val view = player.openInventory
        val handled = HashSet<Inventory>()

        processInventory(view.topInventory)
        handled.add(view.topInventory)

        if (handled.add(view.bottomInventory)) {
            processInventory(view.bottomInventory)
        }
    }

    private fun processInventory(inventory: Inventory) {
        val contents = inventory.contents
        for (slot in contents.indices) {
            val item = contents[slot] ?: continue
            if (item.type.isAir) continue
            if (injectDescriptions(item)) {
                inventory.setItem(slot, item)
            }
        }
    }

    private fun startAutoRefresh(player: Player) {
        stopAutoRefresh(player.uniqueId)
        val task = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            if (!player.isOnline) {
                stopAutoRefresh(player.uniqueId)
                return@Runnable
            }
            try {
                injectForOpenView(player)
            } catch (ex: Exception) {
                plugin.logger.warning("Stopping tooltip refresh for ${player.name}: ${ex.message}")
                stopAutoRefresh(player.uniqueId)
            }
        }, 1L, plugin.config.getLong("refresh-period-ticks", 2L).coerceAtLeast(1L))
        refreshTasks[player.uniqueId] = task
    }

    private fun stopAutoRefresh(playerId: UUID) {
        refreshTasks.remove(playerId)?.cancel()
    }

    private fun clearProcessingKeys(pdc: org.bukkit.persistence.PersistentDataContainer) {
        pdc.remove(processedKey)
        pdc.remove(baseLoreSizeKey)
        pdc.remove(legacyOriginalLoreSizeKey)
        pdc.remove(originalHideEnchantsKey)
        pdc.remove(originalHideStoredEnchantsKey)
        pdc.remove(appliedLocaleKey)
        pdc.remove(enchantSignatureKey)
        pdc.remove(formatVersionKey)
        pdc.remove(styleFingerprintKey)
    }

    private fun buildStyleFingerprint(): String {
        val parts = mutableListOf<String>()
        parts += "enabled=${enchantNameStyle.enabled}"
        parts += "always-show-level=${enchantNameStyle.alwaysShowLevel}"
        parts += "normal-name=${enchantNameStyle.normalNameColor.asHexString().lowercase(Locale.ROOT)}"
        parts += "normal-level=${enchantNameStyle.normalLevelColor.asHexString().lowercase(Locale.ROOT)}"
        parts += "curse-name=${enchantNameStyle.curseNameColor.asHexString().lowercase(Locale.ROOT)}"
        parts += "curse-level=${enchantNameStyle.curseLevelColor.asHexString().lowercase(Locale.ROOT)}"
        parts += "roman-enabled=${enchantNameStyle.romanCharColorsEnabled}"

        for ((char, color) in enchantNameStyle.romanCharColors.toSortedMap()) {
            parts += "roman-$char=${color.asHexString().lowercase(Locale.ROOT)}"
        }

        for ((enchantKey, rule) in enchantNameStyle.enchantRules.toSortedMap()) {
            parts += "rule-$enchantKey-default=${serializeOverride(rule.defaultOverride)}"
            for ((level, levelOverride) in rule.levelOverrides.toSortedMap()) {
                parts += "rule-$enchantKey-level-$level=${serializeOverride(levelOverride)}"
            }
        }

        return parts.joinToString("|")
    }

    private fun serializeOverride(override: EnchantOverride?): String {
        if (override == null) return "null"

        val nameSegments = override.nameSegments.joinToString("~") {
            "${it.text}@${it.color.asHexString().lowercase(Locale.ROOT)}"
        }
        val levelSegments = override.levelSegments.joinToString("~") {
            "${it.text}@${it.color.asHexString().lowercase(Locale.ROOT)}"
        }

        return listOf(
            "use-translatable=${override.useTranslatableName}",
            "literal=${override.literalName ?: ""}",
            "name-color=${override.nameColor.asHexString().lowercase(Locale.ROOT)}",
            "level-color=${override.levelColor.asHexString().lowercase(Locale.ROOT)}",
            "name-segments=$nameSegments",
            "level-segments=$levelSegments"
        ).joinToString(",")
    }

    private fun applyTemplate(template: String, name: String, levelRoman: String, levelArabic: String): String {
        return template
            .replace("{name}", name)
            .replace("{level_roman}", levelRoman)
            .replace("{level_arabic}", levelArabic)
    }

    private fun buildRomanComponent(roman: String, fallbackColor: TextColor, perCharEnabled: Boolean): Component {
        if (!perCharEnabled) {
            return Component.text(roman)
                .color(fallbackColor)
                .decoration(TextDecoration.ITALIC, false)
        }

        var result = Component.empty()
        for (char in roman) {
            val color = enchantNameStyle.romanCharColors[char] ?: fallbackColor
            result = result.append(
                Component.text(char.toString())
                    .color(color)
                    .decoration(TextDecoration.ITALIC, false)
            )
        }
        return result
    }

    private fun prettifyEnchantmentKey(key: String): String {
        return key.split('_').joinToString(" ") { token ->
            token.replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase(Locale.ROOT) else ch.toString()
            }
        }
    }

    private data class TextSegment(
        val text: String,
        val color: TextColor
    )

    private data class EnchantNameStyle(
        val enabled: Boolean,
        val normalNameColor: TextColor,
        val normalLevelColor: TextColor,
        val curseNameColor: TextColor,
        val curseLevelColor: TextColor,
        val alwaysShowLevel: Boolean,
        val enchantRules: Map<String, ManualEnchantRule>,
        val romanCharColorsEnabled: Boolean,
        val romanCharColors: Map<Char, TextColor>
    ) {
        fun findOverride(key: NamespacedKey, level: Int): EnchantOverride? {
            val rule = enchantRules[key.asString()] ?: enchantRules[key.key]
            return rule?.forLevel(level)
        }

        companion object {
            fun fromConfig(plugin: Descriptioner): EnchantNameStyle {
                val root = "enchant-name-styling"
                val enabled = plugin.config.getBoolean("$root.enabled", false)
                return EnchantNameStyle(
                    enabled = enabled,
                    normalNameColor = parseColor(
                        plugin.config.getString("$root.normal-name-color"),
                        NamedTextColor.GRAY
                    ),
                    normalLevelColor = parseColor(
                        plugin.config.getString("$root.normal-level-color"),
                        NamedTextColor.GRAY
                    ),
                    curseNameColor = parseColor(
                        plugin.config.getString("$root.curse-name-color"),
                        NamedTextColor.RED
                    ),
                    curseLevelColor = parseColor(
                        plugin.config.getString("$root.curse-level-color"),
                        NamedTextColor.RED
                    ),
                    alwaysShowLevel = plugin.config.getBoolean("$root.always-show-level", false),
                    enchantRules = parseManualRules(
                        plugin.config.getConfigurationSection("$root.enchant-overrides")
                            ?: plugin.config.getConfigurationSection("$root.vanilla-manual-overrides")
                            ?: plugin.config.getConfigurationSection("$root.overrides")
                    ),
                    romanCharColorsEnabled = plugin.config.getBoolean("$root.roman-char-colors.enabled", false),
                    romanCharColors = parseRomanCharColors(
                        plugin.config.getConfigurationSection("$root.roman-char-colors")
                    )
                )
            }

            private fun parseRomanCharColors(section: ConfigurationSection?): Map<Char, TextColor> {
                if (section == null) return emptyMap()

                val result = mutableMapOf<Char, TextColor>()
                val supported = charArrayOf('I', 'V', 'X', 'L', 'C', 'D', 'M')
                for (romanChar in supported) {
                    val value = section.getString(romanChar.lowercaseChar().toString())
                    result[romanChar] = parseColor(value, NamedTextColor.GRAY)
                }

                return result
            }

            private fun parseManualRules(section: ConfigurationSection?): Map<String, ManualEnchantRule> {
                if (section == null) return emptyMap()

                val result = mutableMapOf<String, ManualEnchantRule>()
                for (key in section.getKeys(false)) {
                    val base = section.getConfigurationSection(key) ?: continue

                    val defaultOverride = parseOverrideSection(base)
                    val levelOverrides = mutableMapOf<Int, EnchantOverride>()
                    val levelsSection = base.getConfigurationSection("levels")
                    if (levelsSection != null) {
                        for (levelKey in levelsSection.getKeys(false)) {
                            val level = levelKey.toIntOrNull() ?: continue
                            val levelSection = levelsSection.getConfigurationSection(levelKey) ?: continue
                            val levelOverride = parseOverrideSection(levelSection) ?: continue
                            levelOverrides[level] = levelOverride
                        }
                    }

                    if (defaultOverride != null || levelOverrides.isNotEmpty()) {
                        result[key] = ManualEnchantRule(defaultOverride, levelOverrides)
                    }
                }

                return result
            }

            private fun parseOverrideSection(section: ConfigurationSection): EnchantOverride? {
                val hasDirectSettings = section.contains("use-translatable-name") ||
                    section.contains("literal-name") ||
                    section.contains("name-color") ||
                    section.contains("level-color") ||
                    section.contains("name-segments") ||
                    section.contains("level-segments")

                if (!hasDirectSettings) {
                    return null
                }

                val nameSegments = parseSegments(section.getMapList("name-segments"))
                val levelSegments = parseSegments(section.getMapList("level-segments"))
                return EnchantOverride(
                    useTranslatableName = section.getBoolean("use-translatable-name", true),
                    literalName = section.getString("literal-name"),
                    nameSegments = nameSegments,
                    levelSegments = levelSegments,
                    nameColor = parseColor(section.getString("name-color"), NamedTextColor.GRAY),
                    levelColor = parseColor(section.getString("level-color"), NamedTextColor.GRAY)
                )
            }

            private fun parseSegments(raw: List<Map<*, *>>): List<TextSegment> {
                if (raw.isEmpty()) return emptyList()

                val result = mutableListOf<TextSegment>()
                for (entry in raw) {
                    val text = entry["text"]?.toString() ?: continue
                    val colorText = entry["color"]?.toString()
                    val color = parseColor(colorText, NamedTextColor.GRAY)
                    result.add(TextSegment(text, color))
                }

                return result
            }

            private fun parseColor(value: String?, fallback: TextColor): TextColor {
                if (value.isNullOrBlank()) return fallback

                val cleaned = value.trim().lowercase()
                if (cleaned.startsWith("#") && cleaned.length == 7) {
                    TextColor.fromHexString(cleaned)?.let { return it }
                }

                NamedTextColor.NAMES.value(cleaned)?.let { return it }
                return fallback
            }
        }
    }

    private data class EnchantOverride(
        val useTranslatableName: Boolean,
        val literalName: String?,
        val nameSegments: List<TextSegment>,
        val levelSegments: List<TextSegment>,
        val nameColor: TextColor,
        val levelColor: TextColor
    )

    private data class ManualEnchantRule(
        val defaultOverride: EnchantOverride?,
        val levelOverrides: Map<Int, EnchantOverride>
    ) {
        fun forLevel(level: Int): EnchantOverride? {
            return levelOverrides[level] ?: defaultOverride
        }
    }
}
