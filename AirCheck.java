package pl.sentinel;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Sprawdza grawitację co 5 ticków, niezależnie od pakietów ruchu.
 * Łapie latanie w miejscu (bez ruchu), powolne opadanie i wznoszenie się.
 */
final class AirCheck implements Runnable {
    private final Sentinel pl;

    AirCheck(Sentinel pl) { this.pl = pl; }

    @Override
    public void run() {
        boolean tpsOk = pl.tpsOk();
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (pl.bypass(p)) continue;
            PlayerData d = pl.data(p);
            if (d.punished) continue;
            if (!tpsOk || p.getPing() > 350) { d.resetAir(); continue; }

            Location loc = p.getLocation();
            if (Util.moveExempt(p, d, now) || Util.envSpecial(loc) || Util.nearGround(loc)) {
                d.resetAir();
                continue;
            }

            d.serverAir += 5;
            d.ySamples.addLast(loc.getY());
            if (d.ySamples.size() > 5) d.ySamples.pollFirst();

            // 2 sekundy w powietrzu, a w ostatniej sekundzie spadł mniej niż 3 bloki = łamie grawitację
            if (d.serverAir >= 40 && d.ySamples.size() == 5) {
                double drop = d.ySamples.peekFirst() - loc.getY();
                if (drop < 3.0) {
                    d.airViolations++;
                    if (d.airViolations >= 3) {
                        pl.flag(p, "fly", 8, "grawitacja: spadek " + Util.f(drop) + " bl/s");
                        d.resetAir();
                        Location back = d.lastGround;
                        if (!d.punished && back != null && back.getWorld().equals(loc.getWorld())
                                && pl.getConfig().getBoolean("setback", true)) {
                            p.teleport(back, PlayerTeleportEvent.TeleportCause.UNKNOWN);
                        }
                    }
                } else {
                    d.airViolations = 0;
                }
            }
        }
    }
}
