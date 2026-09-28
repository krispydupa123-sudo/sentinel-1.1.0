package pl.sentinel;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class Sentinel extends JavaPlugin implements Listener {
    private static final int CONFIG_VERSION = 2;

    private final Map<UUID, PlayerData> data = new ConcurrentHashMap<>();
    private final Set<UUID> alertsOff = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        upgradeConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(new MoveCheck(this), this);
        Bukkit.getPluginManager().registerEvents(new CombatCheck(this), this);
        Bukkit.getPluginManager().registerEvents(new WorldCheck(this), this);
        Bukkit.getScheduler().runTaskTimer(this, this::decay, 200L, 200L);
        Bukkit.getScheduler().runTaskTimer(this, new AirCheck(this), 100L, 5L);
        Bukkit.getScheduler().runTaskTimer(this, this::refreshMenus, 20L, 20L);
        getLogger().info("Sentinel " + getPluginMeta().getVersion() + " włączony. dry-run=" + getConfig().getBoolean("dry-run"));
    }

    /** Stary config (1.0.0) jest zapisywany jako config-old-v1.yml, a tworzony jest nowy. */
    private void upgradeConfig() {
        if (getConfig().getInt("config-version", 1) >= CONFIG_VERSION) return;
        try {
            File cfg = new File(getDataFolder(), "config.yml");
            File old = new File(getDataFolder(), "config-old-v1.yml");
            Files.move(cfg.toPath(), old.toPath(), StandardCopyOption.REPLACE_EXISTING);
            saveDefaultConfig();
            reloadConfig();
            getLogger().warning("Zaktualizowano config do wersji 2 (stary zapisano jako config-old-v1.yml).");
        } catch (IOException ex) {
            getLogger().warning("Nie udało się zaktualizować configu: " + ex.getMessage());
        }
    }

    PlayerData data(Player p) {
        return data.computeIfAbsent(p.getUniqueId(), k -> new PlayerData());
    }

    boolean bypass(Player p) { return p.hasPermission("sentinel.bypass"); }

    boolean tpsOk() { return Bukkit.getTPS()[0] >= getConfig().getDouble("min-tps", 18.0); }

    boolean alertsEnabled(UUID id) { return !alertsOff.contains(id); }

    void toggleAlerts(UUID id) { if (!alertsOff.remove(id)) alertsOff.add(id); }

    private void decay() {
        double dec = getConfig().getDouble("vl-decay", 0.5);
        for (PlayerData d : data.values()) d.vl.replaceAll((k, v) -> Math.max(0, v - dec));
    }

    private void refreshMenus() {
        for (Player v : Bukkit.getOnlinePlayers()) {
            if (v.getOpenInventory().getTopInventory().getHolder() instanceof SentinelMenu m) m.render();
        }
    }

    void flag(Player p, String check, double add, String info) {
        if (!getConfig().getBoolean("checks." + check + ".enabled", true)) return;
        PlayerData d = data(p);
        if (d.punished) return;

        double vl = d.vl.merge(check, add, Double::sum);
        double limit = getConfig().getDouble("checks." + check + ".ban-vl", 15);

        long now = System.currentTimeMillis();
        if (now - d.lastAlert.getOrDefault(check, 0L) > 500) {
            d.lastAlert.put(check, now);
            String msg = "§8[§cSentinel§8] §f" + p.getName() + " §7» §c" + check + " §7(" + info
                    + ") §8VL §f" + Util.f(vl) + "§7/" + (int) limit;
            getLogger().info(ChatColor.stripColor(msg));
            Component comp = LegacyComponentSerializer.legacySection().deserialize(msg)
                    .clickEvent(ClickEvent.runCommand("/sentinel tp " + p.getName()))
                    .hoverEvent(HoverEvent.showText(Component.text("Kliknij, aby się teleportować")));
            for (Player s : Bukkit.getOnlinePlayers()) {
                if (s.isOp() && !alertsOff.contains(s.getUniqueId())) s.sendMessage(comp);
            }
        }
        if (vl >= limit) punish(p, check);
    }

    @SuppressWarnings("deprecation")
    private void punish(Player p, String check) {
        PlayerData d = data(p);
        if (getConfig().getBoolean("dry-run", false)) {
            d.vl.put(check, 0.0);
            getLogger().warning("[DRY-RUN] " + p.getName() + " dostałby bana za " + check);
            return;
        }
        d.punished = true;

        int days = getConfig().getInt("ban.duration-days", 7);
        String id = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String reason = String.join("\n", getConfig().getStringList("ban.kick-message"))
                .replace("{player}", p.getName())
                .replace("{check}", check)
                .replace("{duration}", days + "d")
                .replace("{id}", id);
        reason = ChatColor.translateAlternateColorCodes('&', reason);

        logBan(p, check, id, days, d.vl.getOrDefault(check, 0.0));

        Date expires = new Date(System.currentTimeMillis() + days * 86_400_000L);
        BanList<PlayerProfile> list = Bukkit.getBanList(BanList.Type.PROFILE);
        list.addBan(p.getPlayerProfile(), reason, expires, "Sentinel");

        if (getConfig().getBoolean("ban.lightning", true)) p.getWorld().strikeLightningEffect(p.getLocation());
        p.kick(LegacyComponentSerializer.legacySection().deserialize(reason));
        getLogger().warning("BAN " + p.getName() + " (" + days + "d) za " + check + " id=" + id);

        if (getConfig().getBoolean("ban.broadcast", true)) {
            String bc = ChatColor.translateAlternateColorCodes('&',
                    getConfig().getString("ban.broadcast-message", "").replace("{player}", p.getName()));
            if (!bc.isEmpty()) Bukkit.broadcastMessage(bc);
        }
    }

    private void logBan(Player p, String check, String id, int days, double vl) {
        try {
            getDataFolder().mkdirs();
            String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())
                    + " | " + p.getName() + " | " + p.getUniqueId() + " | " + check
                    + " | vl=" + Util.f(vl) + " | " + days + "d | id=" + id + System.lineSeparator();
            Files.writeString(new File(getDataFolder(), "bans.log").toPath(), line,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            getLogger().warning("Nie można zapisać bans.log: " + ex.getMessage());
        }
    }

    // ---- zdarzenia pomocnicze ----
    @EventHandler public void onJoin(PlayerJoinEvent e) { data(e.getPlayer()).joinTime = System.currentTimeMillis(); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { data.remove(e.getPlayer().getUniqueId()); }
    @EventHandler public void onVelocity(PlayerVelocityEvent e) { data(e.getPlayer()).lastVelocity = System.currentTimeMillis(); }

    @EventHandler
    public void onTp(PlayerTeleportEvent e) {
        PlayerData d = data(e.getPlayer());
        d.timerBalance = 0;
        d.lastMove = 0;
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.UNKNOWN) return; // nasz własny setback
        d.lastTeleport = System.currentTimeMillis();
        d.lastGround = null;
        d.resetAir();
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        PlayerData d = data(e.getPlayer());
        d.lastTeleport = System.currentTimeMillis();
        d.lastGround = null;
        d.resetAir();
    }

    // ---- panel GUI ----
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof SentinelMenu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!p.isOp()) { p.closeInventory(); return; }
        if (e.getClickedInventory() != e.getInventory()) return;
        menu.click(p, e.getSlot(), e.getClick());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof SentinelMenu) e.setCancelled(true);
    }

    // ---- komendy (tylko operatorzy) ----
    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.isOp()) { s.sendMessage("§cTa komenda jest tylko dla operatorów serwera."); return true; }
        if (a.length == 0) {
            if (s instanceof Player p) new SentinelMenu(this).open(p);
            else help(s);
            return true;
        }
        switch (a[0].toLowerCase()) {
            case "menu", "panel" -> {
                if (s instanceof Player p) new SentinelMenu(this).open(p);
                else s.sendMessage("Panel działa tylko w grze.");
            }
            case "reload" -> { reloadConfig(); s.sendMessage("§aPrzeładowano config."); }
            case "alerts" -> {
                if (s instanceof Player p) {
                    toggleAlerts(p.getUniqueId());
                    s.sendMessage(alertsEnabled(p.getUniqueId()) ? "§7Alerty: §aON" : "§7Alerty: §cOFF");
                }
            }
            case "vl" -> {
                if (a.length < 2) { s.sendMessage("§cPodaj gracza."); return true; }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { s.sendMessage("§cGracz offline."); return true; }
                s.sendMessage("§7VL " + t.getName() + ": §f" + data(t).vl);
            }
            case "tp" -> {
                if (!(s instanceof Player p) || a.length < 2) return true;
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t == null) { s.sendMessage("§cGracz offline."); return true; }
                p.teleport(t.getLocation());
                s.sendMessage("§8[§cSentinel§8] §7Teleport do §f" + t.getName());
            }
            default -> help(s);
        }
        return true;
    }

    private void help(CommandSender s) {
        s.sendMessage("§c/sentinel §7- otwórz panel (w grze)");
        s.sendMessage("§c/sentinel reload §7- przeładuj config");
        s.sendMessage("§c/sentinel alerts §7- włącz/wyłącz alerty");
        s.sendMessage("§c/sentinel vl <gracz> §7- pokaż VL gracza");
        s.sendMessage("§c/sentinel tp <gracz> §7- teleport do gracza");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        if (!s.isOp()) return Collections.emptyList();
        if (a.length == 1) return filter(List.of("menu", "reload", "alerts", "vl", "tp", "help"), a[0]);
        if (a.length == 2 && (a[0].equalsIgnoreCase("vl") || a[0].equalsIgnoreCase("tp"))) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return filter(names, a[1]);
        }
        return Collections.emptyList();
    }

    private static List<String> filter(List<String> src, String prefix) {
        List<String> out = new ArrayList<>();
        for (String x : src) if (x.toLowerCase().startsWith(prefix.toLowerCase())) out.add(x);
        return out;
    }
}
