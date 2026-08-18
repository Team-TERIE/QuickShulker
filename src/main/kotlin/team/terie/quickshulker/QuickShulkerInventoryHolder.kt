package team.terie.quickshulker

import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import java.util.UUID

class QuickShulkerInventoryHolder(
    val playerId: UUID,
    val sessionId: UUID
) : InventoryHolder {
    lateinit var backingInventory: Inventory

    override fun getInventory(): Inventory = backingInventory
}
