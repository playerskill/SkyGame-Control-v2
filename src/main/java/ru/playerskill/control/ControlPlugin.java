package ru.playerskill.control;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import ru.playerskill.control.modules.EatFixModule;
import ru.playerskill.control.modules.EasyBansModule;
import ru.playerskill.control.watchdog.WatchdogModule;
import ru.playerskill.control.watchdog.WatchdogSubcommand;

import java.util.ArrayList;
import java.util.List;

public class ControlPlugin extends JavaPlugin {

    private final List<Module> modules = new ArrayList<Module>();
    private final CommandHandler commandHandler = new CommandHandler(this);

    /** Общий пул соединений — из секции database. Используется модулями. */
    private HikariDataSource dataSource;

    /**
     * Сторож живёт вне списка modules: его состояние (сбой, блокировка
     * экономики, отсчёт до кика) должно переживать /control reload,
     * поэтому экземпляр создаётся один раз и не пересоздаётся.
     */
    private WatchdogModule watchdog;

    @Override
    public void onEnable() {
        // Пишет config.yml только если файла ещё нет — комментарии не затираются.
        // saveConfig() в коде не вызывается нигде по той же причине.
        saveDefaultConfig();

        initDatabase();
        enableModules();

        PluginCommand control = getCommand("control");
        if (control != null) {
            control.setExecutor(commandHandler);
            control.setTabCompleter(commandHandler);
        }
    }

    @Override
    public void onDisable() {
        disableModules();
        if (watchdog != null) {
            watchdog.shutdown();
        }
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }

    /** Полный цикл перезагрузки: снять старые модули, перечитать конфиг, поднять новые. */
    public void reloadModules() {
        disableModules();
        commandHandler.unregisterAll();
        if (watchdog != null) {
            watchdog.shutdown();
        }
        reloadConfig();

        // Параметры подключения могли измениться — пересоздаём пул.
        rebuildDataSource();
        if (watchdog != null) {
            watchdog.setDataSource(dataSource);
        }

        enableModules();
    }

    /**
     * Создаёт общий пул соединений из секции database.
     * initializationFailTimeout(-1): пул поднимается даже если база сейчас недоступна —
     * проверки сторожа провалятся и переведут его в режим сбоя, как и задумано.
     */
    private void initDatabase() {
        if (dataSource != null && !dataSource.isClosed()) {
            return;
        }
        try {
            dataSource = createDataSource();
        } catch (Exception ex) {
            getLogger().severe("Не удалось создать пул соединений: " + ex.getMessage()
                    + " — модули, работающие с базой, будут недоступны до /control reload.");
            dataSource = null;
        }
    }

    private HikariDataSource createDataSource() {
        String host = getConfig().getString("database.host", "localhost").trim();
        int port = getConfig().getInt("database.port", 3306);
        String database = getConfig().getString("database.database", "bans").trim();
        String user = getConfig().getString("database.user", "root");
        String password = getConfig().getString("database.password", "");
        boolean utf8 = getConfig().getBoolean("database.use-utf8", true);

        if (host.isEmpty() || database.isEmpty() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Некорректные параметры database в config.yml");
        }

        StringBuilder url = new StringBuilder("jdbc:mysql://").append(host).append(':')
                .append(port).append('/').append(database)
                .append("?connectTimeout=5000&socketTimeout=5000&useSSL=false&serverTimezone=UTC");
        if (utf8) {
            url.append("&useUnicode=true&characterEncoding=UTF-8");
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url.toString());
        config.setUsername(user);
        config.setPassword(password);
        config.setPoolName("SkyGame-Control");
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5000L);
        config.setValidationTimeout(3000L);
        config.setInitializationFailTimeout(-1L);
        return new HikariDataSource(config);
    }

    /** Пересоздаёт пул после перезагрузки конфига. */
    private void rebuildDataSource() {
        HikariDataSource old = dataSource;
        dataSource = null;
        if (old != null) {
            old.close();
        }
        initDatabase();
    }

    /** Поднимает модули согласно конфигу. Сначала гарантированно выключает старые. */
    private void enableModules() {
        disableModules();
        // Подкоманды модулей живут в реестре CommandHandler — при перезагрузке
        // реестр тоже чистим, чтобы старые экземпляры не остались висеть.
        commandHandler.unregisterAll();

        // --- Регистрация модулей. Каждый — в своём try-catch,
        // чтобы сбой одного не уронил весь плагин. ---
        if (getConfig().getBoolean("eat-fix.enabled", true)) {
            try {
                register(new EatFixModule(this));
            } catch (NoSuchFieldException ex) {
                getLogger().warning("EatFix: поле activeItemStack не найдено — версия сервера несовместима. Модуль отключён.");
            } catch (Exception ex) {
                getLogger().warning("EatFix: не удалось загрузить модуль: " + ex.getMessage());
            }
        }

        // EasyBans: только админская команда, на игру не влияет —
        // поэтому без выключателя, модуль активен всегда.
        try {
            register(new EasyBansModule(this));
        } catch (Exception ex) {
            getLogger().warning("EasyBans: не удалось загрузить модуль: " + ex.getMessage());
        }

        // Watchdog: создаётся один раз, при последующих reload — только
        // перечитывает конфиг и получает свежий пул. Его выключатель
        // (watchdog.enabled) он обрабатывает сам внутри reload().
        try {
            if (watchdog == null) {
                watchdog = new WatchdogModule(this, dataSource);
                watchdog.start();
            } else {
                watchdog.setDataSource(dataSource);
                watchdog.reload();
            }
            commandHandler.register(new WatchdogSubcommand(watchdog));
        } catch (Exception ex) {
            getLogger().warning("Watchdog: не удалось загрузить модуль: " + ex.getMessage());
        }

        // Новые модули добавляйте здесь по той же схеме:
        // try { register(new ...); } catch (...) {...}
        // (для модулей, вмешивающихся в игру, — ещё и if с enabled-ключом)

        for (Module module : modules) {
            try {
                module.enable();
            } catch (Exception ex) {
                getLogger().warning("Модуль " + module.getName() + " не запустился: " + ex.getMessage());
            }
        }

        getLogger().info("Активных модулей: " + modules.size());
    }

    private void disableModules() {
        for (Module module : modules) {
            try {
                module.disable();
            } catch (Exception ex) {
                getLogger().warning("Модуль " + module.getName() + " завершился с ошибкой: " + ex.getMessage());
            }
        }
        modules.clear();
    }

    /** Регистрирует модуль и его подкоманды. */
    private void register(Module module) {
        modules.add(module);
        if (module instanceof SubcommandProvider) {
            for (Subcommand sub : ((SubcommandProvider) module).getSubcommands()) {
                commandHandler.register(sub);
            }
        }
    }

    public List<Module> getModules() {
        return modules;
    }
}
