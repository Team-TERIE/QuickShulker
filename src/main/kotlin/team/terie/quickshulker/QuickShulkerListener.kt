package team.terie.quickshulker

import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.EquipmentSlot

class QuickShulkerListener(
    private val sessions: QuickShulkerSessionService
) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        if (!event.player.isSneaking) return
        if (!sessions.isShulker(event.item)) return

        event.isCancelled = true
        event.setUseInteractedBlock(Event.Result.DENY)
        event.setUseItemInHand(Event.Result.DENY)
        sessions.open(event.player)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun guardClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (!sessions.owns(player, event.view.topInventory)) return
        val sourceSlot = sessions.sourceSlot(player) ?: return

        val sourceClicked = event.clickedInventory === player.inventory && event.slot == sourceSlot
        val sourceUsedAsHotbarSwap = event.hotbarButton == sourceSlot
        val topClicked = event.rawSlot in 0 until event.view.topInventory.size
        val shulkerInsertedFromCursor = topClicked && sessions.isShulker(event.cursor)
        val shulkerInsertedFromHotbar = topClicked && event.hotbarButton >= 0 &&
            sessions.isShulker(player.inventory.getItem(event.hotbarButton))
        val shulkerInsertedFromOffhand = topClicked && event.click == ClickType.SWAP_OFFHAND &&
            sessions.isShulker(player.inventory.itemInOffHand)
        val shulkerShiftInserted = event.isShiftClick && event.clickedInventory === player.inventory &&
            sessions.isShulker(event.currentItem)

        if (sourceClicked || sourceUsedAsHotbarSwap || shulkerInsertedFromCursor ||
            shulkerInsertedFromHotbar || shulkerInsertedFromOffhand || shulkerShiftInserted
        ) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun syncAfterClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (sessions.owns(player, event.view.topInventory)) sessions.scheduleSync(player)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun guardDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        if (!sessions.owns(player, event.view.topInventory)) return
        val sourceSlot = sessions.sourceSlot(player) ?: return
        val touchesSource = event.rawSlots.any { rawSlot ->
            rawSlot >= event.view.topInventory.size && event.view.convertSlot(rawSlot) == sourceSlot
        }
        val insertsShulker = sessions.isShulker(event.oldCursor) &&
            event.rawSlots.any { it in 0 until event.view.topInventory.size }
        if (touchesSource || insertsShulker) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun syncAfterDrag(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        if (sessions.owns(player, event.view.topInventory)) sessions.scheduleSync(player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        sessions.finish(player, event.inventory, playCloseSound = true)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onHeldItemChange(event: PlayerItemHeldEvent) {
        val sourceSlot = sessions.sourceSlot(event.player) ?: return
        if (event.newSlot != sourceSlot) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSwapHands(event: PlayerSwapHandItemsEvent) {
        if (sessions.sourceSlot(event.player) != null) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        if (sessions.isSourceItem(event.player, event.itemDrop.itemStack)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onDeath(event: PlayerDeathEvent) {
        sessions.handleDeath(event)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        sessions.finish(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        sessions.clearStaleTokens(event.player)
    }
}
