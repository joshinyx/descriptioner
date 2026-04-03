package dev.joshiny.descriptioner

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.enchantment.EnchantItemEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.event.inventory.PrepareGrindstoneEvent
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
        private const val PLUGIN_LINE_MARKER = "descriptioner:managed-line"
        private const val LEGACY_TEXT_MARKER = "\u2063\u2063\u2060\u200B\u200C\u200D\u2060\u2064\u2064\u200B\u2060\u200D\u200C\u200B\u2063"
    }

    private val originalHideEnchantsKey = NamespacedKey(plugin, "original_hide_enchants")
    private val originalHideStoredEnchantsKey = NamespacedKey(plugin, "original_hide_stored_enchants")
    private val refreshTasks = mutableMapOf<UUID, BukkitTask>()
    private var normalizeTask: BukkitTask? = null
    private val enchantNameStyle = EnchantNameStyle.fromConfig(plugin)
    private val plainTextSerializer = PlainTextComponentSerializer.plainText()

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

    // Inject/cleanup directly in grindstone preview output so enchant removal is reflected immediately.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPrepareGrindstone(event: PrepareGrindstoneEvent) {
        val result = event.result ?: return
        if (result.type.isAir) return

        val updated = result.clone()
        if (injectDescriptions(updated)) {
            event.result = updated
        }
    }

    // Re-apply tooltip lines immediately after enchanting table applies enchants.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEnchantItem(event: EnchantItemEvent) {
        plugin.server.scheduler.runTask(plugin, Runnable {
            injectDescriptions(event.item)
            injectForOpenView(event.enchanter)
        })
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

    // Force quick re-sync after command-based enchant/lore changes.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onCommandPostProcess(event: PlayerCommandPreprocessEvent) {
        if (event.isCancelled) return

        val message = event.message.trim().lowercase(Locale.ROOT)
        val isEnchantCommand = message.startsWith("/enchant ") ||
            message.startsWith("/minecraft:enchant ") ||
            message.startsWith("/essentials:enchant ")
        val isLoreCommand = message.startsWith("/lore ") ||
            message.startsWith("/essentials:lore ")

        if (!isEnchantCommand && !isLoreCommand) return

        schedulePostCommandRefresh(event.player)
    }

    private fun injectDescriptions(item: ItemStack): Boolean {
        if (item.type.isAir) return false
        val meta = item.itemMeta ?: return false
        val pdc = meta.persistentDataContainer
        val currentLore = meta.lore() ?: emptyList()

        val enchantments = collectEnchantments(item, meta)
        val hadPluginLines = currentLore.any { isPluginLine(it) }
        val originalHideEnchantsSnapshot = pdc.get(originalHideEnchantsKey, PersistentDataType.BYTE)
        val originalHideStoredEnchantsSnapshot = pdc.get(originalHideStoredEnchantsKey, PersistentDataType.BYTE)
        val hasStoredFlagSnapshot =
            originalHideEnchantsSnapshot != null ||
                originalHideStoredEnchantsSnapshot != null
        val retainedLore = buildRetainedLore(currentLore)

        if (enchantments.isEmpty()) {
            if (!hadPluginLines && !hasStoredFlagSnapshot) return false

            clearFlagKeys(pdc)
            if (originalHideEnchantsSnapshot != null) {
                if (originalHideEnchantsSnapshot.toInt() == 1) {
                    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
                } else {
                    meta.removeItemFlags(ItemFlag.HIDE_ENCHANTS)
                }
            }
            if (originalHideStoredEnchantsSnapshot != null) {
                if (originalHideStoredEnchantsSnapshot.toInt() == 1) {
                    meta.addItemFlags(ItemFlag.HIDE_STORED_ENCHANTS)
                } else {
                    meta.removeItemFlags(ItemFlag.HIDE_STORED_ENCHANTS)
                }
            }
            meta.lore(retainedLore.ifEmpty { null })
            item.itemMeta = meta
            return true
        }

        if (!pdc.has(originalHideEnchantsKey, PersistentDataType.BYTE)) {
            pdc.set(
                originalHideEnchantsKey,
                PersistentDataType.BYTE,
                if (meta.hasItemFlag(ItemFlag.HIDE_ENCHANTS)) 1 else 0
            )
        }
        if (!pdc.has(originalHideStoredEnchantsKey, PersistentDataType.BYTE)) {
            pdc.set(
                originalHideStoredEnchantsKey,
                PersistentDataType.BYTE,
                if (meta.hasItemFlag(ItemFlag.HIDE_STORED_ENCHANTS)) 1 else 0
            )
        }

        val injectedLines = mutableListOf<Component>()
        for ((enchantment, level) in enchantments) {
            injectedLines.add(markPluginLine(buildEnchantLine(enchantment, level)))
            plugin.descriptionManager.getDescriptionLine(enchantment.key.key)?.let { descLine ->
                injectedLines.add(markPluginLine(descLine))
            }
        }

        if (injectedLines.isEmpty()) return false

        val expectedLore = buildFinalLore(retainedLore, injectedLines)
        val hasHiddenFlags =
            meta.hasItemFlag(ItemFlag.HIDE_ENCHANTS) &&
                meta.hasItemFlag(ItemFlag.HIDE_STORED_ENCHANTS)

        if (currentLore == expectedLore && hasHiddenFlags) {
            return false
        }

        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_STORED_ENCHANTS)

        meta.lore(expectedLore)
        item.itemMeta = meta
        return true
    }

    private fun buildRetainedLore(currentLore: List<Component>): MutableList<Component> {
        return currentLore.filterNot { isPluginLine(it) }.toMutableList()
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

        return enchantments.values
            .sortedBy { (enchantment, _) -> enchantment.key.asString() }
    }

    private fun buildFinalLore(retainedLore: List<Component>, injectedLines: List<Component>): MutableList<Component> {
        val finalLore = injectedLines.toMutableList()
        if (retainedLore.isNotEmpty()) {
            finalLore.add(buildPluginSpacerLine())
            finalLore.addAll(retainedLore)
        }
        return finalLore
    }

    private fun buildPluginSpacerLine(): Component {
        return markPluginLine(Component.empty())
    }

    private fun markPluginLine(line: Component): Component {
        return line.insertion(PLUGIN_LINE_MARKER)
    }

    private fun isPluginLine(line: Component): Boolean {
        if (hasPluginInsertionMarker(line)) {
            return true
        }

        // Clean up lines generated by older builds that appended the marker as text.
        val plain = plainTextSerializer.serialize(line)
        return plain.contains(LEGACY_TEXT_MARKER)
    }

    private fun hasPluginInsertionMarker(component: Component): Boolean {
        if (component.style().insertion() == PLUGIN_LINE_MARKER) {
            return true
        }

        for (child in component.children()) {
            if (hasPluginInsertionMarker(child)) {
                return true
            }
        }

        return false
    }

    private fun schedulePostCommandRefresh(player: Player) {
        val delays = longArrayOf(1L, 2L, 4L)
        for ((index, delay) in delays.withIndex()) {
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                if (!player.isOnline) return@Runnable

                forEachPlayerInventoryItem(player) { injectDescriptions(it) }
                injectForOpenView(player)

                if (index == delays.lastIndex) {
                    player.updateInventory()
                }
            }, delay)
        }
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
            val effectiveNameColor = override.nameColor ?: nameColor
            val effectiveLevelColor = override.levelColor ?: levelColor

            val nameSegments = if (override.nameSegments.isNotEmpty()) {
                override.nameSegments
            } else {
                listOf(TextSegment("{name}", effectiveNameColor))
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
        normalizeInventory(inventory)
    }

    fun isNormalizeRunning(): Boolean {
        return normalizeTask != null
    }

    fun normalizeLoadedItemsBatched(
        batchSize: Int = 200,
        onComplete: (NormalizeSummary) -> Unit
    ): Boolean {
        if (normalizeTask != null) return false

        val targets = collectNormalizeTargets()
        var scanned = 0
        var updated = 0
        var slotIndex = 0
        var dropIndex = 0

        val normalizedBatchSize = batchSize.coerceIn(1, 5000)

        val task = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            try {
                var budget = normalizedBatchSize

                while (budget > 0 && slotIndex < targets.inventorySlots.size) {
                    val target = targets.inventorySlots[slotIndex++]
                    budget--

                    val item = target.inventory.getItem(target.slot) ?: continue
                    if (item.type.isAir) continue

                    scanned++
                    if (injectDescriptions(item)) {
                        target.inventory.setItem(target.slot, item)
                        updated++
                    }
                }

                while (budget > 0 && dropIndex < targets.worldDrops.size) {
                    val dropped = targets.worldDrops[dropIndex++]
                    budget--

                    if (!dropped.isValid) continue
                    val item = dropped.itemStack
                    if (item.type.isAir) continue

                    scanned++
                    if (injectDescriptions(item)) {
                        dropped.itemStack = item
                        updated++
                    }
                }

                if (slotIndex >= targets.inventorySlots.size && dropIndex >= targets.worldDrops.size) {
                    normalizeTask?.cancel()
                    normalizeTask = null

                    for (player in targets.players) {
                        if (player.isOnline) {
                            player.updateInventory()
                        }
                    }

                    onComplete(
                        NormalizeSummary(
                            scanned = scanned,
                            updated = updated,
                            players = targets.players.size,
                            worldDrops = targets.worldDrops.size,
                            containerSlots = targets.containerSlots
                        )
                    )
                }
            } catch (ex: Exception) {
                normalizeTask?.cancel()
                normalizeTask = null
                plugin.logger.warning("Descriptioner normalize failed: ${ex.message}")
                onComplete(
                    NormalizeSummary(
                        scanned = scanned,
                        updated = updated,
                        players = targets.players.size,
                        worldDrops = targets.worldDrops.size,
                        containerSlots = targets.containerSlots
                    )
                )
            }
        }, 1L, 1L)

        normalizeTask = task
        return true
    }

    private fun collectNormalizeTargets(): NormalizeTargets {
        val players = plugin.server.onlinePlayers.toList()
        val inventories = LinkedHashSet<Inventory>()
        val containerInventories = LinkedHashSet<Inventory>()

        for (player in players) {
            inventories.add(player.inventory)

            val view = player.openInventory
            inventories.add(view.topInventory)
            inventories.add(view.bottomInventory)
        }

        for (world in plugin.server.worlds) {
            for (chunk in world.loadedChunks) {
                for (state in chunk.tileEntities) {
                    val holder = state as? org.bukkit.inventory.InventoryHolder ?: continue
                    if (inventories.add(holder.inventory)) {
                        containerInventories.add(holder.inventory)
                    }
                }
            }
        }

        val inventorySlots = mutableListOf<InventorySlotTarget>()
        var containerSlots = 0

        for (inventory in inventories) {
            val isContainerInventory = inventory in containerInventories
            val contents = inventory.contents
            for (slot in contents.indices) {
                val item = contents[slot] ?: continue
                if (item.type.isAir) continue

                inventorySlots.add(InventorySlotTarget(inventory, slot))
                if (isContainerInventory) {
                    containerSlots++
                }
            }
        }

        val worldDrops = mutableListOf<org.bukkit.entity.Item>()
        for (world in plugin.server.worlds) {
            for (entity in world.entities) {
                val dropped = entity as? org.bukkit.entity.Item ?: continue
                worldDrops.add(dropped)
            }
        }

        return NormalizeTargets(
            players = players,
            inventorySlots = inventorySlots,
            worldDrops = worldDrops,
            containerSlots = containerSlots
        )
    }

    private fun normalizeInventory(inventory: Inventory): Pair<Int, Int> {
        val contents = inventory.contents
        var scanned = 0
        var updated = 0

        for (slot in contents.indices) {
            val item = contents[slot] ?: continue
            if (item.type.isAir) continue

            scanned++
            if (injectDescriptions(item)) {
                inventory.setItem(slot, item)
                updated++
            }
        }

        return scanned to updated
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

    private fun clearFlagKeys(pdc: org.bukkit.persistence.PersistentDataContainer) {
        pdc.remove(originalHideEnchantsKey)
        pdc.remove(originalHideStoredEnchantsKey)
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

    data class NormalizeSummary(
        val scanned: Int,
        val updated: Int,
        val players: Int,
        val worldDrops: Int,
        val containerSlots: Int
    )

    private data class InventorySlotTarget(
        val inventory: Inventory,
        val slot: Int
    )

    private data class NormalizeTargets(
        val players: List<Player>,
        val inventorySlots: List<InventorySlotTarget>,
        val worldDrops: List<org.bukkit.entity.Item>,
        val containerSlots: Int
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
                val nameColor = if (section.contains("name-color")) {
                    parseColor(section.getString("name-color"), NamedTextColor.GRAY)
                } else {
                    null
                }
                val levelColor = if (section.contains("level-color")) {
                    parseColor(section.getString("level-color"), NamedTextColor.GRAY)
                } else {
                    null
                }
                return EnchantOverride(
                    useTranslatableName = section.getBoolean("use-translatable-name", true),
                    literalName = section.getString("literal-name"),
                    nameSegments = nameSegments,
                    levelSegments = levelSegments,
                    nameColor = nameColor,
                    levelColor = levelColor
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
        val nameColor: TextColor?,
        val levelColor: TextColor?
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
