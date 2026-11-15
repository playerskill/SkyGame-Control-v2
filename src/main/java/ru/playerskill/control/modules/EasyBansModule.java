package ru.playerskill.control.modules;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import ru.playerskill.control.ControlPlugin;
import ru.playerskill.control.Module;
import ru.playerskill.control.Subcommand;
import ru.playerskill.control.SubcommandProvider;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Модуль очистки истёкших банов из MySQL-таблицы EasyBans.
 *
 * На игру не влияет: ни слушателей, ни задач планировщика.
 * Единственный эффект — админская подкоманда
 * /control easybans deleteexpirybans,
 * поэтому отдельный выключатель модулю не нужен.
 *
 * Подключение к базе берётся из общего раздела database
 * (база общая для нескольких плагинов), а таблица и SQL-запросы —
 * из раздела easybans.
 *
 * Логика удаления: строка считается истёкшей, только если ОБЕ
 * временные метки (time и expiry) уже в прошлом. Это защищает
 * активные баны даже в случае багов плагина с перепутанными датами.
 * Вечные баны (0 или -1) не трогаются условием > 0.
 */
public class EasyBansModule implements Module, SubcommandProvider {

    private final ControlPlugin plugin;

    public EasyBansModule(ControlPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getName() {
        return "EasyBans";
    }

    @Override
    public void enable() {
        // Нечего включать: команда регистрируется через SubcommandProvider.
    }

    @Override
    public void disable() {
        // Нечего закрывать: соединение живёт только внутри одного запроса.
    }

    @Override
    public List<Subcommand> getSubcommands() {
        List<Subcommand> list = new ArrayList<Subcommand>();
        list.add(new Subcommand() {
            @Override
            public String getName() {
                return "easybans";
            }

            @Override
            public String getPermission() {
                return "control.easybans";
            }

            @Override
            public void execute(CommandSender sender, String[] args) {
                if (args.length == 1 && args[0].equalsIgnoreCase("deleteexpirybans")) {
                    cleanExpired(sender);
                    return;
                }
                replyKey(sender, "easybans-usage");
            }

            @Override
            public List<String> tabComplete(String[] args) {
                List<String> result = new ArrayList<String>();
                if (args.length == 1 && "deleteexpirybans".startsWith(args[0].toLowerCase())) {
                    result.add("deleteexpirybans");
                }
                return result;
            }
        });
        return list;
    }

    /** Подсчёт попадающих под условие строк и (если не dry-run) их удаление. */
    private void cleanExpired(final CommandSender sender) {
        final boolean dryRun = plugin.getConfig().getBoolean("easybans.dry-run", true);
        final String countSql = fillTemplate(plugin.getConfig().getString(
                "easybans.count-query", "SELECT COUNT(*) FROM {table} WHERE {where}"));
        final String deleteSql = fillTemplate(plugin.getConfig().getString(
                "easybans.delete-query", "DELETE FROM {table} WHERE {where}"));

        replyKey(sender, "easybans-started");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                Connection conn = null;
                try {
                    conn = openConnection();

                    // 1. Сколько строк попадает под условие.
                    int count = 0;
                    Statement st = conn.createStatement();
                    ResultSet rs = null;
                    try {
                        rs = st.executeQuery(countSql);
                        if (rs.next()) {
                            count = rs.getInt(1);
                        }
                    } finally {
                        close(rs);
                        close(st);
                    }

                    if (count == 0) {
                        replyLater(sender, "easybans-none");
                        return;
                    }

                    if (dryRun) {
                        replyLater(sender, "easybans-dry-run",
                                "{count}", String.valueOf(count));
                        return;
                    }

                    // 2. Само удаление — одним запросом.
                    PreparedStatement ps = conn.prepareStatement(deleteSql);
                    try {
                        int deleted = ps.executeUpdate();
                        plugin.getLogger().info("[EasyBans] Удалено истёкших записей: " + deleted);
                        replyLater(sender, "easybans-done",
                                "{count}", String.valueOf(deleted));
                    } finally {
                        close(ps);
                    }
                } catch (final Exception ex) {
                    String error = ex.getMessage();
                    replyLater(sender, "easybans-error",
                            "{error}", error == null ? ex.toString() : error);
                } finally {
                    close(conn);
                }
            }
        });
    }

    /** Подстановка плейсхолдеров {table} и {where} из конфига в шаблон запроса. */
    private String fillTemplate(String template) {
        String table = plugin.getConfig().getString("easybans.table", "banlist");
        String where = plugin.getConfig().getString("easybans.where", "1");
        return template.replace("{table}", table).replace("{where}", where);
    }

    /** Соединение из общего раздела database. */
    private Connection openConnection() throws Exception {
        String host = plugin.getConfig().getString("database.host", "localhost");
        int port = plugin.getConfig().getInt("database.port", 3306);
        String db = plugin.getConfig().getString("database.database", "bans");
        String user = plugin.getConfig().getString("database.user", "root");
        String password = plugin.getConfig().getString("database.password", "");
        boolean utf8 = plugin.getConfig().getBoolean("database.use-utf8", true);
        String url = "jdbc:mysql://" + host + ":" + port + "/" + db
                + (utf8 ? "?useUnicode=true&characterEncoding=utf8" : "");
        return DriverManager.getConnection(url, user, password);
    }

    /** Готовое сообщение с префиксом и цветами, прямо в текущем потоке. */
    private void reply(CommandSender sender, String message) {
        String prefix = plugin.getConfig().getString("messages.prefix", "");
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', prefix + message));
    }

    /** Сообщение по ключу из секции messages. */
    private void replyKey(CommandSender sender, String key) {
        reply(sender, plugin.getConfig().getString("messages." + key, key));
    }

    /** Ответ из асинхронного потока — безопасно пересылаем на главный. */
    private void replyLater(final CommandSender sender, final String key, final String... placeholders) {
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                if (sender == null) {
                    return;
                }
                String raw = plugin.getConfig().getString("messages." + key, key);
                for (int i = 0; i + 1 < placeholders.length; i += 2) {
                    raw = raw.replace(placeholders[i], placeholders[i + 1]);
                }
                reply(sender, raw);
            }
        });
    }

    private void close(AutoCloseable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }
}
