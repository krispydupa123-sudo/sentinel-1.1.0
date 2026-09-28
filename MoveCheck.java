package pl.sentinel;

import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

@SuppressWarnings("deprecation")
final class MoveCheck implements Listener {
    private final Sentinel pl;

    MoveCheck(Sentinel pl) { this.pl = pl; }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom(), to = e.getTo();
        if (!e.hasChangedPosition() || !from.getWorld().equals(to.getWorld())) return;
        Player p = e.getPlayer();
        if (pl.bypass(p)) return;
        PlayerData d = pl.data(p);
        long now = System.currentTimeMillis();

        if (Util.bouncy(to) || Util.bouncy(from)) d.lastSpecial = now;
        if (Util.icy(to)) d.lastIce = now;

        boolean exempt = Util.moveExempt(p, d, now);
        boolean violation = false;

        // ---- TIMER (za dużo pakietów ruchu = przyspieszanie gry) ----
        long delta = d.lastMove == 0 ? 50 : now - d.lastMove;
        d.lastMove = now;
        if (delta > 800) {
            d.timerBalance = 0;
            d.timerGrace = now + 2000;
        } else {
            d.timerBalance = Math.min(1000, d.timerBalance + delta - 50);
        }
        if (d.timerBalance < -400 && !exempt && now > d.timerGrace && pl.tpsOk()) {
            d.timerBalance = 0;
            pl.flag(p, "timer", 3, "za dużo pakietów ruchu");
            violation = true;
        }

        if (exempt) { d.resetMove(); return; }

        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double h = Math.hypot(dx, dz);

        // ---- JESUS (chodzenie po wodzie) ----
        if (!p.isSwimming() && Util.onLiquidSurface(to) && Math.abs(dy) < 0.003 && h > 0.05) {
            d.jesusBuf++;
            if (d.jesusBuf >= 8) {
                d.jesusBuf = 0;
                pl.flag(p, "jesus", 3, "chodzenie po wodzie");
                violation = true;
            }
        } else {
            d.jesusBuf = Math.max(0, d.jesusBuf - 1);
        }

        if (Util.envSpecial(to) || Util.envSpecial(from)) {
            d.resetMove();
            if (violation) setback(e, d, from, to);
            return;
        }

        boolean ground = Util.nearGround(to);
        if (ground) {
            d.airTicks = 0;
            d.airRise = 0;
            if (Util.solidGround(to)) d.lastGround = to.clone();
        } else {
            d.airTicks++;
            if (dy > 0) d.airRise += dy;
        }

        // ---- SPEED ----
        double ratio = 1.0;
        AttributeInstance attr = p.getAttribute(Attribute.MOVEMENT_SPEED);
        if (attr != null) ratio = Math.max(1.0, attr.getValue() / 0.13);
        double limit = pl.getConfig().getDouble("checks.speed.max-speed", 0.68) * ratio;
        if (now - d.lastIce < 1200) limit *= 1.8;
        if (h > limit) {
            d.speedBuf++;
            if (d.speedBuf >= 3) {
                pl.flag(p, "speed", 2 + (h - limit) * 4, Util.f(h) + ">" + Util.f(limit));
                violation = true;
            }
        } else {
            d.speedBuf = Math.max(0, d.speedBuf - 0.3);
        }

        // ---- FLY ----
        if (!ground) {
            PotionEffect jb = p.getPotionEffect(PotionEffectType.JUMP_BOOST);
            double maxRise = 1.7 + (jb == null ? 0 : (jb.getAmplifier() + 1) * 0.6);
            if (d.airRise > maxRise) {
                pl.flag(p, "fly", 4, "rise " + Util.f(d.airRise));
                violation = true;
            } else if (jb == null && d.airTicks > 12 && dy > -0.25) {
                pl.flag(p, "fly", 3, "hover dy=" + Util.f(dy) + " air=" + d.airTicks);
                violation = true;
            }
        }

        // ---- NOFALL ----
        if (p.isOnGround() && !ground && dy < -0.6) {
            d.noFallBuf++;
            if (d.noFallBuf >= 2) {
                pl.flag(p, "nofall", 2, "fałszywy onGround dy=" + Util.f(dy));
                violation = true;
            }
        } else {
            d.noFallBuf = Math.max(0, d.noFallBuf - 0.25);
        }

        if (violation) setback(e, d, from, to);
    }

    /** Cofa cheatera na ostatnie bezpieczne miejsce. */
    private void setback(PlayerMoveEvent e, PlayerData d, Location from, Location to) {
        if (!pl.getConfig().getBoolean("setback", true)) return;
        Location back = (d.lastGround != null && d.lastGround.getWorld().equals(to.getWorld()))
                ? d.lastGround.clone() : from.clone();
        back.setYaw(to.getYaw());
        back.setPitch(to.getPitch());
        e.setTo(back);
        d.resetMove();
        d.resetAir();
    }
}
