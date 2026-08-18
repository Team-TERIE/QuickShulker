package team.terie.quickshulker

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.block.ShulkerBox
import org.bukkit.entity.Player
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.logging.Level

class QuickShulkerSessionService(
    private val plugin: JavaPlugin
) {
    private val sessionKey = NamespacedKey(plugin, "session")
    private val sessions = mutableMapOf<UUID, Session>()
    private val pendingSyncs = mutableSetOf<UUID>()

    fun open(player: Player): Boolean {
        finish(player, closeView = true)

        val sourceSlot = player.inventory.heldItemSlot
        val sourceItem = player.inventory.getItem(sourceSlot) ?: return false
        if (!isShulker(sourceItem) || sourceItem.amount != 1) return false

        val sourceMeta = sourceItem.itemMeta as? BlockStateMeta ?: return false
        val shulkerState = sourceMeta.blockState as? ShulkerBox ?: return false
        val sessionId = UUID.randomUUID()
        val holder = QuickShulkerInventoryHolder(player.uniqueId, sessionId)
        val openedInventory = Bukkit.createInventory(
            holder,
            InventoryType.SHULKER_BOX,
            Component.text("셜커 상자")
        )
        holder.backingInventory = openedInventory
        openedInventory.contents = copyContents(shulkerState.inventory.contents)

        sourceMeta.persistentDataContainer.set(sessionKey, PersistentDataType.STRING, sessionId.toString())
        sourceItem.itemMeta = sourceMeta
        player.inventory.setItem(sourceSlot, sourceItem)

        sessions[player.uniqueId] = Session(
            id = sessionId,
            sourceSlot = sourceSlot,
            inventory = openedInventory
        )

        return try {
            player.openInventory(openedInventory)
            if (owns(player, player.openInventory.topInventory)) {
                playOpenSound(player)
                true
            } else {
                discardSession(player, sessionId)
                false
            }
        } catch (exception: RuntimeException) {
            discardSession(player, sessionId)
            plugin.logger.log(Level.SEVERE, "${player.name}님의 셜커 인벤토리를 열지 못했습니다.", exception)
            false
        }
    }

    fun owns(player: Player, inventory: Inventory): Boolean {
        val session = sessions[player.uniqueId] ?: return false
        val holder = inventory.holder as? QuickShulkerInventoryHolder ?: return false
        return holder.playerId == player.uniqueId && holder.sessionId == session.id
    }

    fun sourceSlot(player: Player): Int? = sessions[player.uniqueId]?.sourceSlot

    fun scheduleSync(player: Player) {
        val playerId = player.uniqueId
        if (sessions[playerId] == null || !pendingSyncs.add(playerId)) return
        plugin.server.scheduler.runTask(plugin, Runnable {
            pendingSyncs.remove(playerId)
            val onlinePlayer = plugin.server.getPlayer(playerId) ?: return@Runnable
            sync(onlinePlayer)
        })
    }

    fun finish(
        player: Player,
        expectedInventory: Inventory? = null,
        closeView: Boolean = false,
        playCloseSound: Boolean = false
    ) {
        val session = sessions[player.uniqueId] ?: return
        if (expectedInventory != null && session.inventory !== expectedInventory) return

        pendingSyncs.remove(player.uniqueId)
        val located = findSource(player, session)
        if (located != null) {
            writePlayerSource(player, located, session.inventory.contents, keepSessionToken = false, session.id)
        } else {
            plugin.logger.warning("${player.name}님의 열린 셜커 원본을 찾지 못했습니다.")
        }
        sessions.remove(player.uniqueId)
        if (playCloseSound) playCloseSound(player)

        if (closeView && ownsView(player, session)) player.closeInventory()
    }

    fun handleDeath(event: PlayerDeathEvent) {
        val player = event.entity
        val session = sessions.remove(player.uniqueId) ?: return
        pendingSyncs.remove(player.uniqueId)

        val droppedSource = event.drops.firstOrNull { hasSessionToken(it, session.id) }
        if (droppedSource != null) {
            writeContents(droppedSource, session.inventory.contents, keepSessionToken = false, session.id)
            return
        }

        val located = findSource(player, session)
        if (located != null) {
            writePlayerSource(player, located, session.inventory.contents, keepSessionToken = false, session.id)
        } else {
            plugin.logger.warning("사망한 ${player.name}님의 열린 셜커 원본을 찾지 못했습니다.")
        }
    }

    fun clearStaleTokens(player: Player) {
        for (slot in 0 until player.inventory.size) {
            val item = player.inventory.getItem(slot) ?: continue
            val meta = item.itemMeta ?: continue
            if (!meta.persistentDataContainer.has(sessionKey)) continue
            meta.persistentDataContainer.remove(sessionKey)
            item.itemMeta = meta
            player.inventory.setItem(slot, item)
        }
    }

    fun shutdown() {
        sessions.keys.toList().forEach { playerId ->
            plugin.server.getPlayer(playerId)?.let { finish(it, closeView = true) }
        }
        pendingSyncs.clear()
    }

    fun isShulker(item: ItemStack?): Boolean {
        val name = item?.type?.name ?: return false
        return name == "SHULKER_BOX" || name.endsWith("_SHULKER_BOX")
    }

    fun isSourceItem(player: Player, item: ItemStack?): Boolean {
        val session = sessions[player.uniqueId] ?: return false
        return hasSessionToken(item, session.id)
    }

    private fun sync(player: Player) {
        val session = sessions[player.uniqueId] ?: return
        val located = findSource(player, session)
        if (located == null) {
            abortMissingSource(player, session)
            return
        }

        session.sourceSlot = located.slot
        if (!writePlayerSource(player, located, session.inventory.contents, keepSessionToken = true, session.id)) {
            abortMissingSource(player, session)
        }
    }

    private fun writePlayerSource(
        player: Player,
        located: LocatedItem,
        contents: Array<ItemStack?>,
        keepSessionToken: Boolean,
        sessionId: UUID
    ): Boolean {
        if (!writeContents(located.item, contents, keepSessionToken, sessionId)) return false
        player.inventory.setItem(located.slot, located.item)
        return true
    }

    private fun writeContents(
        item: ItemStack,
        contents: Array<ItemStack?>,
        keepSessionToken: Boolean,
        sessionId: UUID
    ): Boolean {
        if (!isShulker(item)) return false
        val meta = item.itemMeta as? BlockStateMeta ?: return false
        val state = meta.blockState as? ShulkerBox ?: return false
        state.inventory.contents = copyContents(contents)
        meta.setBlockState(state)
        if (keepSessionToken) {
            meta.persistentDataContainer.set(sessionKey, PersistentDataType.STRING, sessionId.toString())
        } else {
            meta.persistentDataContainer.remove(sessionKey)
        }
        item.itemMeta = meta
        return true
    }

    private fun findSource(player: Player, session: Session): LocatedItem? {
        val expected = player.inventory.getItem(session.sourceSlot)
        if (hasSessionToken(expected, session.id)) return LocatedItem(session.sourceSlot, expected!!)

        for (slot in 0 until player.inventory.size) {
            val item = player.inventory.getItem(slot)
            if (hasSessionToken(item, session.id)) return LocatedItem(slot, item!!)
        }
        return null
    }

    private fun hasSessionToken(item: ItemStack?, sessionId: UUID): Boolean {
        val meta = item?.itemMeta ?: return false
        return meta.persistentDataContainer.get(sessionKey, PersistentDataType.STRING) == sessionId.toString()
    }

    private fun abortMissingSource(player: Player, session: Session) {
        sessions.remove(player.uniqueId)
        pendingSyncs.remove(player.uniqueId)
        if (ownsView(player, session)) player.closeInventory()
        playCloseSound(player)
        player.sendMessage(
            Component.text("원본 셜커 상자를 찾지 못해 창을 닫았습니다.", NamedTextColor.RED)
        )
        plugin.logger.warning("${player.name}님의 셜커 동기화를 중단했습니다: 원본 아이템을 찾지 못했습니다.")
    }

    private fun discardSession(player: Player, sessionId: UUID) {
        val session = sessions[player.uniqueId]
        if (session?.id == sessionId) sessions.remove(player.uniqueId)
        pendingSyncs.remove(player.uniqueId)

        for (slot in 0 until player.inventory.size) {
            val item = player.inventory.getItem(slot) ?: continue
            if (!hasSessionToken(item, sessionId)) continue
            val meta = item.itemMeta ?: continue
            meta.persistentDataContainer.remove(sessionKey)
            item.itemMeta = meta
            player.inventory.setItem(slot, item)
            break
        }
    }

    private fun ownsView(player: Player, session: Session): Boolean {
        val holder = player.openInventory.topInventory.holder as? QuickShulkerInventoryHolder ?: return false
        return holder.playerId == player.uniqueId && holder.sessionId == session.id
    }

    private fun copyContents(contents: Array<ItemStack?>): Array<ItemStack?> =
        Array(contents.size) { index -> contents[index]?.clone() }

    private fun playOpenSound(player: Player) {
        player.playSound(player.location, Sound.BLOCK_SHULKER_BOX_OPEN, SoundCategory.BLOCKS, 1.0f, 1.0f)
    }

    private fun playCloseSound(player: Player) {
        player.playSound(player.location, Sound.BLOCK_SHULKER_BOX_CLOSE, SoundCategory.BLOCKS, 1.0f, 1.0f)
    }

    private data class Session(
        val id: UUID,
        var sourceSlot: Int,
        val inventory: Inventory
    )

    private data class LocatedItem(
        val slot: Int,
        val item: ItemStack
    )
}
