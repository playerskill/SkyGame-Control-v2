package ru.playerskill.control;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import ru.playerskill.control.modules.EatFixModule;
import ru.playerskill.control.modules.EasyBansModule;

import java.util.ArrayList;
import java.util.List;

public class ControlPlugin extends JavaPlugin {

    private final List<Module> modules = new ArrayList<Module>();
    private final CommandHandler commandHandler = new CommandHandler(this);

    @Override
    public void onEnable() {
        // Пишет config.yml только если файла ещё нет — комментарии не затираются.
        // saveConfig() в коде не вызывается нигде по той же причине.
        saveDefaultConfig();

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
    }

    /** Полный цикл перезагрузки: снять старые модули, перечитать конфиг, поднять новые. */
    public void reloadModules() {
        reloadConfig();
        enableModules();
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
