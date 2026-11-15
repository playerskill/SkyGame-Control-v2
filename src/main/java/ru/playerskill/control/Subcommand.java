package ru.playerskill.control;

import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.List;

/** Подкоманда, которую модуль регистрирует в /control. */
public interface Subcommand {

    String getName();

    /** null — доступна всем, у кого есть control.use. */
    String getPermission();

    void execute(CommandSender sender, String[] args);

    /** Аргументы уже без имени подкоманды. */
    default List<String> tabComplete(String[] args) {
        return Collections.emptyList();
    }
}
