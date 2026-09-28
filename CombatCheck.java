package pl.sentinel;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

final class CombatCheck implements Listener {
    private final Sentinel pl;

    CombatCheck(Sentinel pl) { this.pl = pl; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        GameMode gm = p.getGameMode();
        if (pl.bypass(p) || gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return;

        PlayerData d = pl.data(p);
        long now = System.currentTimeMillis();
        Entity t = e.getEntity();

        Location eye = p.getEyeLocation();
        BoundingBox bb = t.getBoundingBox();
        double cx = clamp(eye.getX(), bb.getMinX(), bb.getMaxX());
        double cy = clamp(eye.getY(), bb.getMinY(), bb.getMaxY());
        double cz = clamp(eye.getZ(), bb.getMinZ(), bb.getMaxZ());
        Vector toPoint = new Vector(cx - eye.getX(), cy - eye.getY(), cz - eye.getZ());
        double dist = toPoint.length();

        // ---- REACH ----
        double allow = pl.getConfig().getDouble("checks.reach.max-reach", 3.0)
                + 0.45 + Math.min(p.getPing(), 300) / 300.0 * 0.6;
        if (dist > allow) {
            pl.flag(p, "reach", 1 + (dist - allow) * 4, Util.f(dist) + ">" + Util.f(allow));
            e.setCancelled(true);
            return;
        }

        // ---- KILLAURA: kąt patrzenia ----
        if (dist > 1.0) {
            double angle = Math.toDegrees(eye.getDirection().angle(toPoint));
            if (angle > 70) pl.flag(p, "killaura", 2, "angle " + Util.f(angle));
        }

        // ---- KILLAURA: tempo ataków ----
        PlayerData.prune(d.hits, now, 1000);
        d.hits.addLast(now);
        if (d.hits.size() > 20) pl.flag(p, "killaura", 2, "hits/s " + d.hits.size());

        // ---- MULTIAURA ----
        if (d.lastTarget != null && !d.lastTarget.equals(t.getUniqueId()) && now - d.lastHit < 40) {
            d.multiBuf++;
            if (d.multiBuf >= 3) pl.flag(p, "multiaura", 3, "multi-target");
        } else {
            d.multiBuf = Math.max(0, d.multiBuf - 0.5);
        }
        d.lastTarget = t.getUniqueId();
        d.lastHit = now;
    }

    @EventHandler(ignoreCancelled = false)
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player p = e.getPlayer();
        if (pl.bypass(p)) return;
        PlayerData d = pl.data(p);
        long now = System.currentTimeMillis();

        PlayerData.prune(d.clicks, now, 1500);
        d.clicks.addLast(now);

        int cps = 0;
        for (long c : d.clicks) if (now - c <= 1000) cps++;
        double max = pl.getConfig().getDouble("checks.autoclicker.max-cps", 22);
        if (cps > max) pl.flag(p, "autoclicker", 1 + (cps - max) * 0.5, "cps " + cps);

        // zbyt równe odstępy między kliknięciami (macro)
        if (d.clicks.size() >= 16) {
            Long[] a = d.clicks.toArray(new Long[0]);
            double sum = 0, sumSq = 0;
            int n = a.length - 1;
            for (int i = 1; i < a.length; i++) {
                double iv = a[i] - a[i - 1];
                sum += iv; sumSq += iv * iv;
            }
            double mean = sum / n;
            double std = Math.sqrt(Math.max(0, sumSq / n - mean * mean));
            if (std < 4.0 && mean < 100) pl.flag(p, "autoclicker", 2, "std " + Util.f(std) + "ms");
        }
    }

    private static double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }
}
