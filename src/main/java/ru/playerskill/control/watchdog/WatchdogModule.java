package ru.playerskill.control.watchdog;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

import javax.sql.DataSource;

/**
 * Модуль SkyWatchdog: следит за доступностью общей базы данных.
 * При затяжном сбое предупреждает игроков за 10 секунд, кикает всех
 * и запрещает новые подключения. Вайтлист сервера не изменяется.
 * Экономика не блокируется: защита строится на частых проверках и быстром кике.
 */
public final class WatchdogModule implements Listener {

    /** Право подкоманды /control watchdog. */
    public static final String PERMISSION_SUBCOMMAND = "control.watchdog";

    /** За сколько секунд до кика отправлять предупреждение в чат. */
    private static final long KICK_WARNING_SECONDS = 10L;

    private enum State {
        NORMAL,   // база доступна, фоновые проверки
        OUTAGE,   // база недоступна, ожидание kick-after-seconds
        LOCKED    // игроки кикнуты, вход запрещён
    }

    private final JavaPlugin plugin;
    private final Path logsDirectory;

    private volatile DataSource dataSource;

    private volatile State state = State.NORMAL;
    private volatile long outageStartedAt;
    private volatile long recoveryStartedAt;
    private volatile boolean commandForcedOutage;
    private volatile boolean kicked; // кик уже выполнялся в текущем сбое

    private volatile boolean enabled;
    private volatile long normalCheckSeconds;
    private volatile long outageCheckSeconds;
    private volatile long kickAfterSeconds;
    private volatile long recoveryConfirmSeconds;

    /** Ники из bypass-players в нижнем регистре: им вход разрешён при сбое. */
    private volatile Set<String> bypassPlayers = new HashSet<String>();

    private BukkitTask scheduledTask;
    private BukkitTask kickWarningTask;
    private volatile boolean kickWarningSent; // предупреждение о кике уже показано в текущем сбое

    public WatchdogModule(JavaPlugin plugin, DataSource sharedDataSource) {
        this.plugin = plugin;
        this.dataSource = sharedDataSource;
        this.logsDirectory = plugin.getDataFolder().toPath().resolve("logs");
    }

    /** Control может подсунуть пересозданный пул после reload. */
    public void setDataSource(DataSource newDataSource) {
        this.dataSource = newDataSource;
    }

    // ---------- Жизненный цикл ----------

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        applyConfig();
        if (enabled) {
            scheduleCheck(normalCheckSeconds);
            plugin.getLogger().info("[Watchdog] Мониторинг запущен; первая проверка через "
                    + normalCheckSeconds + " сек.");
        } else {
            plugin.getLogger().info("[Watchdog] Выключен в конфиге (watchdog.enabled: false).");
        }
    }

    public void reload() {
        boolean wasEnabled = enabled;
        applyConfig();

        if (!enabled) {
            // Выключили в конфиге: останавливаем проверки, снимаем блокировку входа.
            cancelScheduled();
            cancelKickWarning();
            if (state != State.NORMAL) {
                state = State.NORMAL;
                recoveryStartedAt = 0L;
                kicked = false;
                kickWarningSent = false;
            }
            if (wasEnabled) {
                plugin.getLogger().info("[Watchdog] Выключен после перезагрузки конфига.");
            }
            return;
        }

        scheduleCheck(state == State.NORMAL ? normalCheckSeconds : outageCheckSeconds);
    }

    public void shutdown() {
        cancelScheduled();
        cancelKickWarning();
    }

    private void applyConfig() {
        enabled = plugin.getConfig().getBoolean("watchdog.enabled", true);
        normalCheckSeconds = Math.max(1L, plugin.getConfig().getLong("watchdog.checks.check-interval-seconds", 30L));
        outageCheckSeconds = Math.max(1L, plugin.getConfig().getLong("watchdog.checks.outage-check-interval-seconds", 10L));
        kickAfterSeconds = Math.max(1L, plugin.getConfig().getLong("watchdog.checks.kick-after-seconds", 300L));
        recoveryConfirmSeconds = Math.max(0L, plugin.getConfig().getLong("watchdog.checks.recovery-confirm-seconds", 120L));

        Set<String> bypass = new HashSet<String>();
        for (String name : plugin.getConfig().getStringList("watchdog.bypass-players")) {
            if (name != null && !name.trim().isEmpty()) {
                bypass.add(name.trim().toLowerCase());
            }
        }
        bypassPlayers = bypass;
    }

    private void cancelScheduled() {
        if (scheduledTask != null) {
            scheduledTask.cancel();
            scheduledTask = null;
        }
    }

    // ---------- Команды (вызываются через WatchdogSubcommand) ----------

    /** args — аргументы после "watchdog": test, test-outage или пусто (статус). */
    public void handleSubcommand(CommandSender sender, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        if ("test".equals(sub)) {
            testConnection(sender);
        } else if ("test-outage".equals(sub)) {
            toggleForcedOutage(sender);
        } else {
            sendStatus(sender);
        }
    }

    public List<String> tabComplete(String[] args) {
        List<String> result = new ArrayList<String>();
        if (args.length == 1) {
            for (String option : Arrays.asList("test", "test-outage")) {
                if (option.startsWith(args[0].toLowerCase())) {
                    result.add(option);
                }
            }
        }
        return result;
    }

    private void sendStatus(CommandSender sender) {
        String stateName;
        switch (state) {
            case NORMAL:  stateName = "норма"; break;
            case OUTAGE:  stateName = "сбой БД (ожидание кика)"; break;
            case LOCKED:  stateName = "кик выполнен, вход запрещён"; break;
            default:      stateName = state.name();
        }
        sender.sendMessage(ChatColor.GOLD + "==== SkyWatchdog ====");
        sender.sendMessage(ChatColor.YELLOW + "Состояние: " + ChatColor.WHITE + stateName);
        if (commandForcedOutage) {
            sender.sendMessage(ChatColor.RED + "Активен тестовый режим сбоя (watchdog test-outage).");
        }
        sender.sendMessage(ChatColor.YELLOW + "Пул соединений: " + ChatColor.WHITE
                + (dataSource != null ? "общий (от Control)" : "не передан — проверки будут проваливаться"));
        sender.sendMessage(ChatColor.YELLOW + "Интервалы (сек): фоновая проверка " + ChatColor.WHITE + normalCheckSeconds
                + ChatColor.YELLOW + ", при сбое " + ChatColor.WHITE + outageCheckSeconds
                + ChatColor.YELLOW + ", до кика " + ChatColor.WHITE + kickAfterSeconds
                + ChatColor.YELLOW + ", подтверждение восстановления " + ChatColor.WHITE + recoveryConfirmSeconds);
        sender.sendMessage(ChatColor.YELLOW + "Подкоманды: " + ChatColor.WHITE
                + "/control watchdog test, /control watchdog test-outage");
    }

    private void testConnection(final CommandSender sender) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                final SQLException failure = testDatabaseConnection();
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        if (!plugin.isEnabled()) return;
                        if (failure == null) {
                            sender.sendMessage(ChatColor.GREEN + "Подключение к базе данных работает.");
                        } else {
                            sender.sendMessage(ChatColor.RED + "Ошибка подключения к базе данных: "
                                    + failure.getMessage());
                        }
                    }
                });
            }
        });
    }

    private void toggleForcedOutage(CommandSender sender) {
        if (!commandForcedOutage) {
            commandForcedOutage = true;
            sender.sendMessage(ChatColor.YELLOW + "Тестовый режим сбоя включён: модуль ведёт себя так, будто база недоступна.");
            sender.sendMessage(ChatColor.YELLOW + "Если в течение " + kickAfterSeconds
                    + " сек. не выключить — будет выполнен кик игроков (как при реальном сбое).");
            if (state == State.NORMAL) {
                beginOutage();
            }
            scheduleCheck(outageCheckSeconds);
            return;
        }
        commandForcedOutage = false;
        sender.sendMessage(ChatColor.GREEN + "Тестовый режим сбоя выключен.");
        if (state != State.NORMAL) {
            recoverFromOutage();
        }
        scheduleCheck(normalCheckSeconds);
    }

    // ---------- Проверка базы ----------

    private void scheduleCheck(long delaySeconds) {
        cancelScheduled();
        if (!enabled || !plugin.isEnabled()) {
            return;
        }
        scheduledTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin,
                this::checkDatabase, delaySeconds * 20L);
    }

    private void checkDatabase() {
        final SQLException checkFailure = testDatabaseConnection();
        final boolean connected = checkFailure == null;
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                handleCheckResult(connected, checkFailure);
            }
        });
    }

    private SQLException testDatabaseConnection() {
        try {
            DataSource source = dataSource;
            if (source == null) {
                throw new SQLException("Общий пул соединений недоступен.");
            }
            try (Connection connection = source.getConnection();
                 PreparedStatement statement = connection.prepareStatement("SELECT 1");
                 ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("База данных вернула пустой результат.");
                }
            }
            return null;
        } catch (SQLException exception) {
            return exception;
        }
    }

    // ---------- Конечный автомат ----------

    private void handleCheckResult(boolean connected, SQLException failure) {
        if (!enabled || !plugin.isEnabled()) {
            return;
        }
        if (commandForcedOutage) {
            connected = false; // тестовый режим: реальный результат игнорируется
        }

        if (state == State.NORMAL) {
            if (connected) {
                scheduleCheck(normalCheckSeconds);
            } else {
                plugin.getLogger().warning("[Watchdog] База данных недоступна: "
                        + (failure == null ? "(тестовый режим)" : failure.getMessage()));
                beginOutage();
                scheduleCheck(outageCheckSeconds);
            }
            return;
        }

        if (connected) {
            // Кика не было — сбой оказался коротким, восстанавливаемся сразу.
            if (state == State.OUTAGE && !kicked) {
                recoverFromOutage();
                scheduleCheck(normalCheckSeconds);
                return;
            }
            // Кик был — ждём устойчивого окна восстановления.
            if (recoveryStartedAt == 0L) {
                recoveryStartedAt = System.currentTimeMillis();
                plugin.getLogger().info("[Watchdog] База доступна; наблюдаю устойчивость "
                        + recoveryConfirmSeconds + " сек. перед снятием блокировки.");
            }
            if (System.currentTimeMillis() - recoveryStartedAt
                    >= recoveryConfirmSeconds * 1000L) {
                recoverFromOutage();
                scheduleCheck(normalCheckSeconds);
            } else {
                scheduleCheck(outageCheckSeconds);
            }
            return;
        }

        // База снова недоступна: сбрасываем окно восстановления, считаем кик.
        if (recoveryStartedAt != 0L) {
            plugin.getLogger().warning("[Watchdog] База снова недоступна во время окна восстановления; отсчёт сброшен.");
            recoveryStartedAt = 0L;
        }
        if (state == State.OUTAGE
                && System.currentTimeMillis() - outageStartedAt >= kickAfterSeconds * 1000L) {
            enterLockedState();
        }
        scheduleCheck(outageCheckSeconds);
    }

    private void beginOutage() {
        state = State.OUTAGE;
        outageStartedAt = System.currentTimeMillis();
        recoveryStartedAt = 0L;
        kicked = false;
        kickWarningSent = false;
        scheduleKickWarning();
        logOutagePlayers();
    }

    private void enterLockedState() {
        state = State.LOCKED;
        cancelKickWarning();

        String reason = colorize(plugin.getConfig().getString("messages.watchdog-kick",
                "&cОшибка базы данных, срочно сообщите об этом в группе тг - t.me/skygame001"));
        for (Player player : new ArrayList<Player>(Bukkit.getOnlinePlayers())) {
            if (isBypassed(player)) {
                continue;
            }
            player.kickPlayer(reason);
        }

        plugin.getLogger().severe("[Watchdog] Сбой базы превысил " + kickAfterSeconds
                + " сек.: игроки кикнуты, новые подключения запрещены.");
    }

    private void recoverFromOutage() {
        state = State.NORMAL;
        outageStartedAt = 0L;
        recoveryStartedAt = 0L;
        kicked = false;
        cancelKickWarning();
        // Сообщение о восстановлении показываем только тем, кто видел предупреждение о кике:
        // иначе после тихого сбоя оно всплывёт из ниоткуда.
        if (kickWarningSent) {
            broadcast("watchdog-recovered",
                    "&a[Сервер] База данных восстановлена. Вход открыт.");
        }
        kickWarningSent = false;
        plugin.getLogger().info("[Watchdog] База данных восстановлена; блокировка входа снята.");
    }

    // ---------- Предупреждение перед киком ----------

    /** Планирует объявление за KICK_WARNING_SECONDS до кика. */
    private void scheduleKickWarning() {
        cancelKickWarning();
        long delaySeconds = kickAfterSeconds - KICK_WARNING_SECONDS;
        if (delaySeconds <= 0L) {
            // Кик через 10 сек. или раньше — предупреждение сразу.
            broadcastKickWarning();
            return;
        }
        // runTaskLater — главный поток: broadcastMessage обязан идти в нём.
        kickWarningTask = Bukkit.getScheduler().runTaskLater(plugin,
                this::broadcastKickWarning, delaySeconds * 20L);
    }

    private void broadcastKickWarning() {
        kickWarningTask = null;
        if (!enabled || !plugin.isEnabled() || state != State.OUTAGE || kickWarningSent) {
            return;
        }
        kickWarningSent = true;
        broadcast("watchdog-kick-warning",
                "&c[Сервер] База данных не работает. Через 10 секунд все игроки будут отключены от сервера.");
        plugin.getLogger().info("[Watchdog] Отправлено предупреждение о кике за "
                + KICK_WARNING_SECONDS + " сек.");
    }

    private void cancelKickWarning() {
        if (kickWarningTask != null) {
            kickWarningTask.cancel();
            kickWarningTask = null;
        }
    }

    private boolean isBypassed(Player player) {
        return bypassPlayers.contains(player.getName().toLowerCase());
    }

    // ---------- Сообщения и логи ----------

    private String colorize(String raw) {
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    private void broadcast(String key, String fallback) {
        String prefix = plugin.getConfig().getString("messages.prefix", "");
        String text = prefix + plugin.getConfig().getString("messages." + key, fallback);
        for (String line : colorize(text).split("\n")) {
            if (!line.trim().isEmpty()) {
                Bukkit.broadcastMessage(line);
            }
        }
    }

    private void logOutagePlayers() {
        final String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        final List<String> playerNames = new ArrayList<String>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            playerNames.add(player.getName());
        }
        final String line = timestamp + " | players: "
                + (playerNames.isEmpty() ? "none" : String.join(", ", playerNames))
                + System.lineSeparator();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                try {
                    Files.createDirectories(logsDirectory);
                    Files.write(logsDirectory.resolve("outages.log"),
                            line.getBytes(StandardCharsets.UTF_8),
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException exception) {
                    plugin.getLogger().log(Level.WARNING, "[Watchdog] Не удалось записать лог сбоя.", exception);
                }
            }
        });
    }

    // ---------- Запрет входа при сбое ----------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerLoginEvent event) {
        if (state == State.NORMAL) {
            return;
        }
        if (!isBypassed(event.getPlayer())) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER,
                    colorize(plugin.getConfig().getString("messages.watchdog-join-blocked",
                            "&cСервер временно закрыт: сбой базы данных. Попробуйте позже.")));
        }
    }
}
