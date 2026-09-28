package pl.sentinel;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;

/** Panel admina otwierany komendą /sentinel (tylko OP). */
final class SentinelMenu implements InventoryHolder {
    private static final int SLOT_ALERTS = 1, SLOT_MODE = 2, SLOT_INFO = 4, SLOT_RELOAD = 6, SLOT_CLOSE = 7;

    private final Sentinel pl;
    private final Inventory inv;
    private final Map<Integer, UUID> heads = new HashMap<>();
    private UUID viewer;

    SentinelMenu(Sentinel pl) {
        this.pl = pl;
        this.inv = Bukkit.createInventory(this, 54, Util.txt("§c§lSentinel §8» §7Panel anty-cheata"));
    }

    @Override
    public Inventory getInventory() { return inv; }

    void open(Player p) {
        viewer = p.getUniqueId();
        render();
        p.openInventory(inv);
    }

    void render() {
        inv.clear();
        heads.clear();

        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 18; i++) inv.setItem(i, pane);

        int days = pl.getConfig().getInt("ban.duration-days", 7);
        boolean live = !pl.getConfig().getBoolean("dry-run", true);
        boolean alerts = viewer != null && pl.alertsEnabled(viewer);

        inv.setItem(SLOT_ALERTS, item(alerts ? Material.BELL : Material.GRAY_DYE,
                alerts ? "§aAlerty: WŁĄCZONE" : "§cAlerty: WYŁĄCZONE", "§7Kliknij, aby przełączyć"));

        inv.setItem(SLOT_MODE, item(live ? Material.RED_CONCRETE : Material.LIME_CONCRETE,
                live ? "§c§lTRYB: BANY WŁĄCZONE" : "§a§lTRYB: TESTOWY",
                live ? "§7Cheaterzy dostają bana na §f" + days + "d" : "§7Tylko alerty, bez banowania",
                "", "§eKliknij, aby przełączyć"));

        int flagged = 0;
        for (Player t : Bukkit.getOnlinePlayers()) if (pl.data(t).totalVl() > 0.5) flagged++;
        double tps = Math.min(20.0, Bukkit.getTPS()[0]);
        inv.setItem(SLOT_INFO, item(Material.COMPASS, "§bStatus",
                "§7TPS: §f" + String.format(Locale.US, "%.1f", tps),
                "§7Graczy online: §f" + Bukkit.getOnlinePlayers().size(),
                "§7Z podejrzeniami: §f" + flagged,
                "§7Ban: §f" + days + " dni"));

        inv.setItem(SLOT_RELOAD, item(Material.WRITABLE_BOOK, "§ePrzeładuj config", "§7Wczytaj config.yml od nowa"));
        inv.setItem(SLOT_CLOSE, item(Material.BARRIER, "§cZamknij"));

        List<Player> list = new ArrayList<>(Bukkit.getOnlinePlayers());
        list.sort(Comparator.comparingDouble((Player x) -> -pl.data(x).totalVl()).thenComparing(Player::getName));
        int slot = 18;
        for (Player t : list) {
            if (slot >= 54) break;
            inv.setItem(slot, head(t));
            heads.put(slot, t.getUniqueId());
            slot++;
        }
    }

    void click(Player p, int slot, ClickType type) {
        switch (slot) {
            case SLOT_ALERTS -> { pl.toggleAlerts(p.getUniqueId()); render(); }
            case SLOT_MODE -> {
                boolean dry = pl.getConfig().getBoolean("dry-run", true);
                pl.getConfig().set("dry-run", !dry);
                pl.saveConfig();
                render();
            }
            case SLOT_RELOAD -> { pl.reloadConfig(); render(); p.sendMessage("§aPrzeładowano config."); }
            case SLOT_CLOSE -> p.closeInventory();
            default -> {
                UUID id = heads.get(slot);
                if (id == null) return;
                Player t = Bukkit.getPlayer(id);
                if (t == null) return;
                if (type.isLeftClick()) {
                    p.closeInventory();
                    p.teleport(t.getLocation());
                    p.sendMessage("§8[§cSentinel§8] §7Teleport do §f" + t.getName());
                } else if (type.isRightClick()) {
                    pl.data(t).vl.clear();
                    render();
                }
            }
        }
    }

    private ItemStack head(Player t) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta m = (SkullMeta) it.getItemMeta();
        m.setOwningPlayer(t);
        PlayerData d = pl.data(t);
        double total = d.totalVl();
        String color = total <= 0.05 ? "§a" : total < 8 ? "§e" : "§c";
        m.displayName(Util.txt(color + t.getName()));
        List<Component> lore = new ArrayList<>();
        lore.add(Util.txt("§7VL łącznie: §f" + Util.f(total)));
        d.vl.forEach((k, v) -> { if (v > 0.05) lore.add(Util.txt("  §8- §7" + k + ": §f" + Util.f(v))); });
        lore.add(Util.txt("§7Ping: §f" + t.getPing() + "ms  §7Tryb: §f" + t.getGameMode().name().toLowerCase()));
        lore.add(Util.txt(""));
        lore.add(Util.txt("§eLPM: §7teleport do gracza"));
        lore.add(Util.txt("§ePPM: §7wyzeruj VL"));
        m.lore(lore);
        it.setItemMeta(m);
        return it;
    }

    private static ItemStack item(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.displayName(Util.txt(name));
        if (lore.length > 0) {
            List<Component> l = new ArrayList<>();
            for (String s : lore) l.add(Util.txt(s));
            m.lore(l);
        }
        it.setItemMeta(m);
        return it;
    }
}
