package ru.playerskill.control.lightning;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Модуль SkyLightning: команда /lightning (алиас /mylightning) для донатеров.
 * Вызывает безопасную молнию — только эффект (Bukkit.strikeLightningEffect),
 * без урона, поджога и разрушений. Приоритет над Essentials обеспечивается
 * перехватом на PlayerCommandPreprocessEvent в приоритете LOWEST:
 * мы отменяем событие раньше, чем команда дойдёт до Essentials.
 */
public final class LightningModule implements Listener {

    /** Право на использование команды (выдаётся донатерам). */
    public static final String PERMISSION_LIGHTNING = "control.lightning";

    private final JavaPlugin plugin;

    private volatile boolean enabled;
    private volatile long cooldownSeconds;
    private volatile String permissionMessage;
    private volatile String cooldownMessage;

    /** Кулдаун на игрока: момент последнего использования в миллисекундах. */
    private final Map<UUID, Long> lastUsed = new HashMap<UUID, Long>();

    public LightningModule(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------- Жизненный цикл ----------

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        applyConfig();
        plugin.getLogger().info(enabled
                ? "[Lightning] Команда /lightning включена (алиас /mylightning)."
                : "[Lightning] Выключена в конфиге (lightning.enabled: false).");
    }

    public void reload() {
        applyConfig();
    }

    public void shutdown() {
        lastUsed.clear();
    }

    private void applyConfig() {
        enabled = plugin.getConfig().getBoolean("lightning.enabled", true);
        cooldownSeconds = Math.max(0L, plugin.getConfig().getLong("lightning.cooldown-seconds", 5L));
        permissionMessage = plugin.getConfig().getString("lightning.messages.no-permission",
                "&cКоманда доступна только донатерам.");
        cooldownMessage = plugin.getConfig().getString("lightning.messages.cooldown",
                "&cМолния будет доступна через %seconds% сек.");
    }

    // ---------- Перехват команды ----------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!enabled || event.isCancelled()) {
            return;
        }

        String command = event.getMessage().toLowerCase();
        if (!command.startsWith("/lightning") && !command.startsWith("/mylightning")) {
            return;
        }

        // Границы имени: "/lightning" и "/lightning " — наша команда,
        // "/lightningx" и "/lightning-strike" — чужие, не трогаем.
        int nameEnd = command.indexOf(' ');
        String name = (nameEnd == -1) ? command : command.substring(0, nameEnd);
        if (!"/lightning".equals(name) && !"/mylightning".equals(name)) {
            return;
        }

        event.setCancelled(true);
        Player player = event.getPlayer();

        if (!player.hasPermission(PERMISSION_LIGHTNING)) {
            player.sendMessage(colorize(permissionMessage));
            return;
        }

        long now = System.currentTimeMillis();
        if (cooldownSeconds > 0L) {
            Long previous = lastUsed.get(player.getUniqueId());
            if (previous != null) {
                long elapsed = (now - previous) / 1000L;
                if (elapsed < cooldownSeconds) {
                    long remain = cooldownSeconds - elapsed;
                    player.sendMessage(colorize(cooldownMessage.replace("%seconds%", String.valueOf(remain))));
                    return;
                }
            }
            lastUsed.put(player.getUniqueId(), now);
        }

        // strikeLightningEffect: визуал и звук, без урона, поджога и разрушений.
        Location location = player.getLocation();
        World world = location.getWorld();
        if (world != null) {
            world.strikeLightningEffect(location);
        }

        String broadcastText = plugin.getConfig().getString("lightning.messages.bolt",
                "&6⚡ &e%player% &6вызвал молнию!");
        String text = colorize(broadcastText.replace("%player%", player.getDisplayName()));
        for (CommandSender recipient : Bukkit.getOnlinePlayers()) {
            recipient.sendMessage(text);
        }
        plugin.getLogger().info("[Lightning] Игрок " + player.getName() + " вызвал молнию.");
    }

    private String colorize(String raw) {
        return ChatColor.translateAlternateColorCodes('&', raw);
    }
}
