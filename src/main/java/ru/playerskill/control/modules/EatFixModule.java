package ru.playerskill.control.modules;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.inventory.ItemStack;
import ru.playerskill.control.ControlPlugin;
import ru.playerskill.control.Module;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Фикс MC-849 и связанного с ним поведения клиента. Два независимых механизма
 * с отдельными тумблерами в конфиге:
 *
 * 1. Боевая часть (block-on-attack): при ударе клиент сам отменяет использование
 *    еды или зелья, а сервер нет — через 1,6 секунды предмет расходуется без
 *    анимации. Ловим замах и сбрасываем активный предмет на сервере.
 *
 * 2. Пакетная часть (block-on-npc-click, требует ProtocolLib): при правом клике
 *    по сущности, которую «не приняла» игра (Citizens, Shopkeepers), клиент
 *    откатывается к использованию предмета — еда начинается заново уже после
 *    нашего сброса. Citizens перехватывает пакеты до событий Bukkit, поэтому
 *    клик ловим пакетом USE_ENTITY (раньше Citizens), а следующий пакет
 *    USE_ITEM в коротком окне отменяем. Пока правая кнопка удерживается на НПС,
 *    клиент шлёт USE_ENTITY повторно — окно обновляется, утечки нет.
 *
 * Имена полей и методов NMS в Spigot обфусцированы, поэтому:
 *  - поле активного предмета ищем по типу (единственный нестатический,
 *    нефинальный ItemStack в EntityLiving), а не по имени;
 *  - тип предмета определяем через CraftItemStack.asBukkitCopy и Material.
 */
public class EatFixModule implements Module, Listener {

    /** Окно по умолчанию: перекрывает спам пакетов при удержании кнопки (~200 мс). */
    private static final long DEFAULT_WINDOW_MS = 400L;

    private final ControlPlugin plugin;
    private final Field activeItemStackField;
    private final Method asBukkitCopy;
    private final Method getHandleMethod;
    private final boolean blockOnAttack;
    private final boolean blockOnNpcClick;
    private final long windowMs;
    private final Map<UUID, Long> entityClickMarks = new ConcurrentHashMap<UUID, Long>();
    private ProtocolManager protocolManager;
    private boolean warned = false;

    public EatFixModule(ControlPlugin plugin) throws Exception {
        this.plugin = plugin;

        Class<?> entityLiving = Class.forName("net.minecraft.server.v1_12_R1.EntityLiving");
        Class<?> nmsItemStack = Class.forName("net.minecraft.server.v1_12_R1.ItemStack");

        activeItemStackField = findActiveItemStackField(entityLiving, nmsItemStack);
        if (activeItemStackField == null) {
            throw new NoSuchFieldException("не удалось однозначно найти активный предмет в EntityLiving");
        }
        activeItemStackField.setAccessible(true);

        // CraftItemStack.asBukkitCopy(nms) — статический, имя метода не обфусцировано.
        Class<?> craftItemStack = Class.forName("org.bukkit.craftbukkit.v1_12_R1.inventory.CraftItemStack");
        asBukkitCopy = craftItemStack.getMethod("asBukkitCopy", nmsItemStack);

        // CraftPlayer.getHandle() — кешируем один раз, метод не обфусцирован.
        Class<?> craftPlayer = Class.forName("org.bukkit.craftbukkit.v1_12_R1.entity.CraftPlayer");
        getHandleMethod = craftPlayer.getMethod("getHandle");

        blockOnAttack = plugin.getConfig().getBoolean("eat-fix.block-on-attack", true);
        blockOnNpcClick = plugin.getConfig().getBoolean("eat-fix.block-on-npc-click", true);
        windowMs = plugin.getConfig().getLong("eat-fix.entity-click-window-ms", DEFAULT_WINDOW_MS);

        if (blockOnNpcClick) {
            if (plugin.getServer().getPluginManager().getPlugin("ProtocolLib") == null) {
                plugin.getLogger().warning("EatFix: block-on-npc-click=true, но ProtocolLib не найден. "
                        + "Фикс НПС отключён, защита при ударе остаётся активной.");
            } else {
                protocolManager = ProtocolLibrary.getProtocolManager();
            }
        }

        plugin.getLogger().info("EatFix v4: защита при ударе "
                + (blockOnAttack ? "вкл" : "выкл")
                + ", фикс НПС "
                + (protocolManager != null ? "вкл (окно " + windowMs + " мс)" : "выкл"));
    }

    @Override
    public String getName() {
        return "EatFix";
    }

    @Override
    public void enable() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);

        if (protocolManager != null) {
            // Клик по сущности на уровне пакетов — раньше Citizens.
            protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.LOW,
                    PacketType.Play.Client.USE_ENTITY) {
                @Override
                public void onPacketReceiving(PacketEvent event) {
                    Player player = event.getPlayer();
                    if (player == null) {
                        return;
                    }
                    entityClickMarks.put(player.getUniqueId(), System.currentTimeMillis());
                    clearActiveItem(player);
                }
            });

            // Использование предмета: гасим «откат» после клика по сущности.
            protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.LOW,
                    PacketType.Play.Client.USE_ITEM) {
                @Override
                public void onPacketReceiving(PacketEvent event) {
                    Player player = event.getPlayer();
                    if (player == null) {
                        return;
                    }
                    Long mark = entityClickMarks.get(player.getUniqueId());
                    if (mark == null || System.currentTimeMillis() - mark > windowMs) {
                        return;
                    }
                    if (!holdsConsumable(player)) {
                        return;
                    }
                    event.setCancelled(true);
                    clearActiveItem(player);
                }
            });
        }
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        if (protocolManager != null) {
            protocolManager.removePacketListeners(plugin);
        }
        entityClickMarks.clear();
    }

    /**
     * Сначала пробуем человекочитаемое имя (форки вроде Paper его оставляют),
     * затем — поиск по типу: в EntityLiving ровно один нестатический,
     * нефинальный ItemStack. Если таких несколько — не рискуем и отключаемся.
     */
    private Field findActiveItemStackField(Class<?> entityLiving, Class<?> nmsItemStack) {
        try {
            return entityLiving.getDeclaredField("activeItemStack");
        } catch (NoSuchFieldException ignored) {
        }

        Field found = null;
        for (Field field : entityLiving.getDeclaredFields()) {
            int mods = field.getModifiers();
            if (field.getType() == nmsItemStack
                    && !Modifier.isStatic(mods)
                    && !Modifier.isFinal(mods)) {
                if (found != null) {
                    return null; // больше одного кандидата — неоднозначно
                }
                found = field;
            }
        }
        return found;
    }

    /**
     * Любой PlayerAnimationEvent в 1.12.2 — это замах. Фильтр по ARM_SWING
     * не ставим: он рискует пропустить замах второй рукой.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent event) {
        if (!blockOnAttack) {
            return;
        }
        clearActiveItem(event.getPlayer());
    }

    /**
     * Читаем и пишем NMS-поле напрямую: это работа с памятью, без вызовов
     * Bukkit API, поэтому допустимо на потоке ProtocolLib (Netty).
     */
    private void clearActiveItem(Player player) {
        try {
            Object handle = getHandleMethod.invoke(player);
            Object active = activeItemStackField.get(handle);
            if (active == null) {
                return;
            }

            Material material = ((ItemStack) asBukkitCopy.invoke(null, active)).getType();
            if (isConsumable(material)) {
                activeItemStackField.set(handle, null);
            }
        } catch (Exception ex) {
            if (!warned) {
                warned = true;
                plugin.getLogger().warning("EatFix: потерян доступ к активному предмету: " + ex.getMessage());
            }
        }
    }

    /** Есть ли в руках (основной или второй) еда или выпиваемое зелье. */
    private boolean holdsConsumable(Player player) {
        return isConsumable(player.getInventory().getItemInMainHand().getType())
                || isConsumable(player.getInventory().getItemInOffHand().getType());
    }

    /** Еда + выпиваемые зелья. Молоко не трогаем — осознанное решение. */
    private boolean isConsumable(Material material) {
        if (material == Material.MILK_BUCKET) {
            return false;
        }
        return material.isEdible() || material == Material.POTION;
    }
}
