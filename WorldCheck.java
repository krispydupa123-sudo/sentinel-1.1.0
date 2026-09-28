package pl.sentinel;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.util.RayTraceResult;

final class WorldCheck implements Listener {
    private final Sentinel pl;

    WorldCheck(Sentinel pl) { this.pl = pl; }

    private boolean skip(Player p) {
        GameMode gm = p.getGameMode();
        return pl.bypass(p) || gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (skip(p)) return;
        PlayerData d = pl.data(p);
        long now = System.currentTimeMillis();

        double dist = p.getEyeLocation().distance(e.getBlock().getLocation().add(0.5, 0.5, 0.5));
        double maxReach = pl.getConfig().getDouble("checks.nuker.max-block-reach", 6.5);
        if (dist > maxReach) {
            e.setCancelled(true);
            pl.flag(p, "nuker", 2, "reach " + Util.f(dist));
            return;
        }

        PlayerData.prune(d.breaks, now, 1000);
        d.breaks.addLast(now);
        int max = pl.getConfig().getInt("checks.nuker.max-breaks-per-sec", 15);
        if (d.breaks.size() > max) {
            e.setCancelled(true);
            pl.flag(p, "nuker", 2, "breaks/s " + d.breaks.size());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        if (skip(p)) return;
        PlayerData d = pl.data(p);
        long now = System.currentTimeMillis();

        PlayerData.prune(d.places, now, 1000);
        d.places.addLast(now);
        int max = pl.getConfig().getInt("checks.fastplace.max-places-per-sec", 20);
        if (d.places.size() > max) {
            e.setCancelled(true);
            pl.flag(p, "fastplace", 2, "places/s " + d.places.size());
            return;
        }

        // Scaffold: gracz stawia blok na ścianie, na którą w ogóle nie patrzy
        RayTraceResult r = p.rayTraceBlocks(6.0);
        if (r == null || r.getHitBlock() == null || !r.getHitBlock().equals(e.getBlockAgainst())) {
            d.scaffoldBuf++;
            if (d.scaffoldBuf >= 6) pl.flag(p, "scaffold", 1, "rotacja nie pasuje do bloku");
        } else {
            d.scaffoldBuf = Math.max(0, d.scaffoldBuf - 0.5);
        }
    }
}
