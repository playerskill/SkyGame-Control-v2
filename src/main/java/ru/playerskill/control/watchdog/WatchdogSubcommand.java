package ru.playerskill.control.watchdog;

import org.bukkit.command.CommandSender;
import ru.playerskill.control.Subcommand;

import java.util.List;

/**
 * Подкоманда /control watchdog для модуля сторожа базы данных.
 * Регистрируется в CommandHandler вместе с подкомандами остальных модулей.
 */
public class WatchdogSubcommand implements Subcommand {

    private final WatchdogModule watchdog;

    public WatchdogSubcommand(WatchdogModule watchdog) {
        this.watchdog = watchdog;
    }

    @Override
    public String getName() {
        return "watchdog";
    }

    @Override
    public String getPermission() {
        return WatchdogModule.PERMISSION_SUBCOMMAND;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        watchdog.handleSubcommand(sender, args);
    }

    @Override
    public List<String> tabComplete(String[] args) {
        return watchdog.tabComplete(args);
    }
}
