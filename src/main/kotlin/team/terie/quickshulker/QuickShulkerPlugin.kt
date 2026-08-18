package team.terie.quickshulker

import org.bukkit.plugin.java.JavaPlugin

class QuickShulkerPlugin : JavaPlugin() {
    private lateinit var sessions: QuickShulkerSessionService

    override fun onEnable() {
        sessions = QuickShulkerSessionService(this)
        server.onlinePlayers.forEach(sessions::clearStaleTokens)
        server.pluginManager.registerEvents(QuickShulkerListener(sessions), this)
    }

    override fun onDisable() {
        if (::sessions.isInitialized) sessions.shutdown()
    }
}
