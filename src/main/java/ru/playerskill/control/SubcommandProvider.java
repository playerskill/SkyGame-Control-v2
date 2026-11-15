package ru.playerskill.control;

import java.util.List;

/** Модуль, добавляющий подкоманды в /control. */
public interface SubcommandProvider {
    List<Subcommand> getSubcommands();
}
