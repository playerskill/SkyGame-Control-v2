package ru.playerskill.control;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CommandHandler implements CommandExecutor, TabCompleter {

    private final ControlPlugin plugin;
    private final Map<String, Subcommand> subcommands = new LinkedHashMap<String, Subcommand>();

    public CommandHandler(ControlPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Регистрация подкоманды модуля. Вызывается из ControlPlugin.register().
     * Модуль watchdog регистрирует здесь WatchdogSubcommand ("/control watchdog").
     */
    public void register(Subcommand sub) {
        String name = sub.getName().toLowerCase();
        if (subcommands.containsKey(name)) {
            plugin.getLogger().warning("Подкоманда '" + name + "' уже зарегистрирована — перезаписываю.");
        }
        subcommands.put(name, sub);
    }

    /** Полная очистка реестра — при перезагрузке модулей. */
    public void unregisterAll() {
        subcommands.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("control.use")) {
            sendKey(sender, "no-permission");
            return true;
        }

        if (args.length == 0) {
            sendKey(sender, "usage");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            plugin.reloadModules();
            String message = plugin.getConfig()
                    .getString("messages.reloaded", "&aПерезагружено.")
                    .replace("{count}", String.valueOf(plugin.getModules().size()));
            sendRaw(sender, message);
            return true;
        }

        Subcommand sub = subcommands.get(args[0].toLowerCase());
        if (sub == null) {
            sendKey(sender, "unknown-subcommand");
            return true;
        }
        if (sub.getPermission() != null && !sender.hasPermission(sub.getPermission())) {
            sendKey(sender, "no-permission");
            return true;
        }
        sub.execute(sender, Arrays.copyOfRange(args, 1, args.length));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<String>();
        if (!sender.hasPermission("control.use")) {
            return result;
        }

        if (args.length == 1) {
            String typed = args[0].toLowerCase();
            if ("reload".startsWith(typed)) {
                result.add("reload");
            }
            for (String name : subcommands.keySet()) {
                if (name.startsWith(typed)) {
                    result.add(name);
                }
            }
            return result;
        }

        Subcommand sub = subcommands.get(args[0].toLowerCase());
        if (sub != null) {
            result.addAll(sub.tabComplete(Arrays.copyOfRange(args, 1, args.length)));
        }
        return result;
    }

    /** Отправка сообщения по ключу из секции messages. */
    private void sendKey(CommandSender sender, String key) {
        sendRaw(sender, plugin.getConfig().getString("messages." + key, key));
    }

    /** Отправка готовой строки с префиксом и цветами. */
    private void sendRaw(CommandSender sender, String message) {
        String prefix = plugin.getConfig().getString("messages.prefix", "");
        sender.sendMessage(color(prefix + message));
    }

    private String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }
}
