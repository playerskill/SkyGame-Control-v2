package ru.playerskill.control.watchdog;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.event.server.ServiceUnregisterEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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
 * При сбое блокирует экономику (Vault), через kick-after-seconds кикает
 * всех игроков и запрещает новые подключения. Вайтлист сервера не изменяется.
 */
public final class WatchdogModule implements Listener {

    /** Право подкоманды /control watchdog. */
    public static final String PERMISSION_SUBCOMMAND = "control.watchdog";

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
    private volatile String economyBlockedMessage;

    /** Ники из bypass-players в нижнем регистре: им вход разрешён при сбое. */
    private volatile Set<String> bypassPlayers = new HashSet<String>();

    private BukkitTask scheduledTask;

    private Class<?> vaultEconomyClass;
    private volatile Object vaultEconomyProvider;
    private volatile Object vaultEconomyProxy;
    private volatile boolean updatingVaultEconomy;

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
        hookVaultEconomy();
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
            // Выключили в конфиге: останавливаем проверки и защиту, снимаем блокировку входа.
            cancelScheduled();
            if (state != State.NORMAL) {
                state = State.NORMAL;
                recoveryStartedAt = 0L;
                kicked = false;
            }
            unregisterVaultEconomyProxy();
            if (wasEnabled) {
                plugin.getLogger().info("[Watchdog] Выключен после перезагрузки конфига.");
            }
            return;
        }

        // Включены: если были выключены — заново подключаем защиту экономики.
        if (!wasEnabled) {
            hookVaultEconomy();
        }
        scheduleCheck(state == State.NORMAL ? normalCheckSeconds : outageCheckSeconds);
    }

    public void shutdown() {
        cancelScheduled();
        unregisterVaultEconomyProxy();
    }

    private void applyConfig() {
        enabled = plugin.getConfig().getBoolean("watchdog.enabled", true);
        normalCheckSeconds = Math.max(1L, plugin.getConfig().getLong("watchdog.checks.check-interval-seconds", 30L));
        outageCheckSeconds = Math.max(1L, plugin.getConfig().getLong("watchdog.checks.outage-check-interval-seconds", 10L));
        kickAfterSeconds = Math.max(1L, plugin.getConfig().getLong("watchdog.checks.kick-after-seconds", 300L));
        recoveryConfirmSeconds = Math.max(0L, plugin.getConfig().getLong("watchdog.checks.recovery-confirm-seconds", 120L));

        economyBlockedMessage = plugin.getConfig().getString("messages.watchdog-economy-blocked",
                "&cОперации с валютой временно недоступны: сбой базы данных.");

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
        sender.sendMessage(ChatColor.YELLOW + "Защита экономики: " + ChatColor.WHITE
                + (vaultEconomyProxy != null ? "активна" : "выключена"));
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
        broadcast("watchdog-outage",
                "&c[Сервер] Обнаружен сбой базы данных. Операции с валютой приостановлены.");
        logOutagePlayers();
    }

    private void enterLockedState() {
        state = State.LOCKED;

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
        broadcast("watchdog-recovered",
                "&a[Сервер] База данных восстановлена. Вход открыт.");
        plugin.getLogger().info("[Watchdog] База данных восстановлена; блокировка входа снята.");
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

    // ---------- Защита Vault Economy ----------

    private void hookVaultEconomy() {
        org.bukkit.plugin.Plugin vault = Bukkit.getPluginManager().getPlugin("Vault");
        if (vault == null) {
            plugin.getLogger().info("[Watchdog] Vault не установлен; защита экономики недоступна.");
            return;
        }
        try {
            vaultEconomyClass = Class.forName("net.milkbowl.vault.economy.Economy",
                    true, vault.getClass().getClassLoader());
            refreshVaultEconomyProvider();
        } catch (ClassNotFoundException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "[Watchdog] Не удалось загрузить API экономики Vault; защита недоступна.", exception);
        }
    }

    @EventHandler
    public void onEconomyServiceRegistered(ServiceRegisterEvent event) {
        refreshVaultEconomyForEvent(event.getProvider().getService(), event.getProvider().getProvider());
    }

    @EventHandler
    public void onEconomyServiceUnregistered(ServiceUnregisterEvent event) {
        refreshVaultEconomyForEvent(event.getProvider().getService(), event.getProvider().getProvider());
    }

    private void refreshVaultEconomyForEvent(Class<?> service, Object changedProvider) {
        if (vaultEconomyClass != null && service == vaultEconomyClass
                && changedProvider != vaultEconomyProxy && !updatingVaultEconomy) {
            refreshVaultEconomyProvider();
        }
    }

    private void refreshVaultEconomyProvider() {
        Class<?> economyClass = vaultEconomyClass;
        if (economyClass == null || updatingVaultEconomy || !enabled) {
            return;
        }

        Object selectedProvider = null;
        int selectedPriority = -1;
        for (Object registrationObject : Bukkit.getServicesManager().getRegistrations(economyClass)) {
            RegisteredServiceProvider<?> registration = (RegisteredServiceProvider<?>) registrationObject;
            Object provider = registration.getProvider();
            if (provider == vaultEconomyProxy) {
                continue;
            }
            if (registration.getPriority().ordinal() > selectedPriority) {
                selectedProvider = provider;
                selectedPriority = registration.getPriority().ordinal();
            }
        }

        if (selectedProvider == null) {
            unregisterVaultEconomyProxy();
            return;
        }
        if (selectedProvider == vaultEconomyProvider && vaultEconomyProxy != null) {
            return;
        }

        updatingVaultEconomy = true;
        try {
            if (vaultEconomyProxy != null) {
                Bukkit.getServicesManager().unregister(economyClass, vaultEconomyProxy);
            }
            vaultEconomyProvider = selectedProvider;
            vaultEconomyProxy = Proxy.newProxyInstance(economyClass.getClassLoader(),
                    new Class<?>[]{economyClass}, new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
                            return invokeVaultEconomy(method, arguments);
                        }
                    });
            Bukkit.getServicesManager().register(economyClass, vaultEconomyProxy, plugin, ServicePriority.Highest);
            plugin.getLogger().info("[Watchdog] Защита экономики активна для провайдера "
                    + selectedProvider.getClass().getName() + ".");
        } finally {
            updatingVaultEconomy = false;
        }
    }

    private Object invokeVaultEconomy(Method method, Object[] arguments) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            if ("toString".equals(method.getName())) {
                return "SkyWatchdog Vault economy guard";
            }
            if ("hashCode".equals(method.getName())) {
                return System.identityHashCode(vaultEconomyProxy);
            }
            if ("equals".equals(method.getName())) {
                return vaultEconomyProxy == (arguments == null ? null : arguments[0]);
            }
        }

        if (state != State.NORMAL && isEconomyMutation(method.getName())) {
            return blockedEconomyResult(method, arguments);
        }
        if (vaultEconomyProvider == null) {
            return defaultEconomyResult(method.getReturnType());
        }
        try {
            return method.invoke(vaultEconomyProvider, arguments);
        } catch (java.lang.reflect.InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private boolean isEconomyMutation(String methodName) {
        return "withdrawPlayer".equals(methodName) || "depositPlayer".equals(methodName)
                || "createPlayerAccount".equals(methodName) || "bankWithdraw".equals(methodName)
                || "bankDeposit".equals(methodName) || "createBank".equals(methodName)
                || "deleteBank".equals(methodName);
    }

    private Object blockedEconomyResult(Method method, Object[] arguments) throws Exception {
        if (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class) {
            return false;
        }
        if (!"net.milkbowl.vault.economy.EconomyResponse".equals(method.getReturnType().getName())) {
            return defaultEconomyResult(method.getReturnType());
        }

        ClassLoader loader = vaultEconomyClass.getClassLoader();
        Class<?> responseClass = Class.forName("net.milkbowl.vault.economy.EconomyResponse", true, loader);
        Class<?> responseTypeClass = Class.forName(
                "net.milkbowl.vault.economy.EconomyResponse$ResponseType", true, loader);
        Object failureType = null;
        for (Object constant : responseTypeClass.getEnumConstants()) {
            if ("FAILURE".equals(constant.toString())) {
                failureType = constant;
                break;
            }
        }
        double amount = 0.0D;
        if (arguments != null) {
            for (Object argument : arguments) {
                if (argument instanceof Number) {
                    amount = ((Number) argument).doubleValue();
                }
            }
        }
        Constructor<?> constructor = responseClass.getConstructor(double.class, double.class,
                responseTypeClass, String.class);
        return constructor.newInstance(amount, 0.0D, failureType, economyBlockedMessage);
    }

    private Object defaultEconomyResult(Class<?> returnType) {
        if (!returnType.isPrimitive() || returnType == void.class) {
            return null;
        }
        if (returnType == boolean.class) return false;
        if (returnType == char.class) return '\0';
        if (returnType == byte.class) return (byte) 0;
        if (returnType == short.class) return (short) 0;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == float.class) return 0.0F;
        if (returnType == double.class) return 0.0D;
        return null;
    }

    private void unregisterVaultEconomyProxy() {
        Object proxy = vaultEconomyProxy;
        if (vaultEconomyClass == null || proxy == null) {
            return;
        }
        updatingVaultEconomy = true;
        try {
            Bukkit.getServicesManager().unregister(vaultEconomyClass, proxy);
            vaultEconomyProxy = null;
        } finally {
            updatingVaultEconomy = false;
        }
    }
}
