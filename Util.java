package pl.sentinel;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.entity.Vehicle;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;

@SuppressWarnings("deprecation")
final class Util {
    private Util() {}

    private static final double[] XZ = {-0.31, 0, 0.31};
    private static final double[] Y = {-0.1, -0.3};

    static String f(double v) { return String.format(Locale.US, "%.2f", v); }

    /** Tekst z kolorami § bez domyślnej kursywy (do GUI). */
    static Component txt(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    private static Block at(Location l, double ox, double oy, double oz) {
        return l.getWorld().getBlockAt(Location.locToBlock(l.getX() + ox),
                Location.locToBlock(l.getY() + oy), Location.locToBlock(l.getZ() + oz));
    }

    private static boolean ground(Location l, boolean liquidCounts) {
        for (double x : XZ) for (double z : XZ) for (double y : Y) {
            Block b = at(l, x, y, z);
            if (!b.isPassable() || (liquidCounts && b.isLiquid())) return true;
        }
        return entityUnder(l);
    }

    /** Pod nogami jest cokolwiek stałego (blok, ciecz, łódka, shulker). */
    static boolean nearGround(Location l) { return ground(l, true); }

    /** Pod nogami jest twardy blok (ciecz się nie liczy). */
    static boolean solidGround(Location l) { return ground(l, false); }

    static boolean entityUnder(Location l) {
        for (Entity e : l.getWorld().getNearbyEntities(l, 0.9, 0.6, 0.9)) {
            if (e instanceof Vehicle || e instanceof Shulker || e instanceof ArmorStand) return true;
        }
        return false;
    }

    /** Gracz stoi w powietrzu nad wodą/lawą (nic stałego pod nogami). */
    static boolean onLiquidSurface(Location l) {
        Block feet = at(l, 0, 0, 0);
        if (feet.isLiquid() || !feet.isPassable()) return false;
        boolean liquid = false;
        for (double x : XZ) for (double z : XZ) {
            Block b = at(l, x, -0.1, z);
            if (b.isLiquid()) { liquid = true; continue; }
            if (!b.isPassable()) return false;
        }
        return liquid && !entityUnder(l);
    }

    /** Miejsca, gdzie normalna fizyka nie obowiązuje (woda, drabiny, pajęczyna...). */
    static boolean envSpecial(Location l) {
        for (double y : new double[]{0.0, 0.9, 1.6}) {
            Block b = at(l, 0, y, 0);
            Material m = b.getType();
            if (b.isLiquid() || Tag.CLIMBABLE.isTagged(m)) return true;
            switch (m) {
                case COBWEB, POWDER_SNOW, SWEET_BERRY_BUSH, HONEY_BLOCK, BUBBLE_COLUMN, SCAFFOLDING -> { return true; }
                default -> { }
            }
        }
        return at(l, 0, -0.5, 0).getType() == Material.HONEY_BLOCK;
    }

    static boolean bouncy(Location l) {
        Material m = at(l, 0, -0.5, 0).getType();
        return m == Material.SLIME_BLOCK || Tag.BEDS.isTagged(m);
    }

    static boolean icy(Location l) {
        for (double y : new double[]{-0.5, -1.2}) {
            Material m = at(l, 0, y, 0).getType();
            if (m == Material.ICE || m == Material.PACKED_ICE || m == Material.BLUE_ICE || m == Material.FROSTED_ICE) return true;
        }
        return false;
    }

    /** Stany, w których nie sprawdzamy ruchu (creative, elytra, wehikuł, knockback, teleport...). */
    static boolean moveExempt(Player p, PlayerData d, long now) {
        GameMode gm = p.getGameMode();
        if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || p.getAllowFlight() || p.isFlying() || p.isDead()) return true;
        if (p.isGliding() || p.isRiptiding() || p.isInsideVehicle()) { d.lastSpecial = now; return true; }
        if (p.hasPotionEffect(PotionEffectType.LEVITATION) || p.hasPotionEffect(PotionEffectType.SLOW_FALLING)) return true;
        return now - d.joinTime < 3000 || now - d.lastTeleport < 1500
                || now - d.lastVelocity < 1500 || now - d.lastSpecial < 1000;
    }
}
