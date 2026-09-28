package pl.sentinel;

import org.bukkit.Location;

import java.util.*;

final class PlayerData {
    long joinTime = System.currentTimeMillis();
    long lastTeleport, lastVelocity, lastSpecial, lastIce, lastHit, lastMove, timerGrace;
    int airTicks, serverAir, airViolations;
    double airRise, speedBuf, noFallBuf, scaffoldBuf, multiBuf, jesusBuf, timerBalance;
    UUID lastTarget;
    Location lastGround;
    boolean punished;

    final Map<String, Double> vl = new HashMap<>();
    final Map<String, Long> lastAlert = new HashMap<>();
    final ArrayDeque<Long> clicks = new ArrayDeque<>();
    final ArrayDeque<Long> breaks = new ArrayDeque<>();
    final ArrayDeque<Long> places = new ArrayDeque<>();
    final ArrayDeque<Long> hits = new ArrayDeque<>();
    final ArrayDeque<Double> ySamples = new ArrayDeque<>();

    void resetMove() { airTicks = 0; airRise = 0; speedBuf = 0; noFallBuf = 0; }
    void resetAir() { serverAir = 0; airViolations = 0; ySamples.clear(); }

    double totalVl() {
        double s = 0;
        for (double v : vl.values()) s += v;
        return s;
    }

    static int prune(ArrayDeque<Long> q, long now, long window) {
        while (!q.isEmpty() && now - q.peekFirst() > window) q.pollFirst();
        return q.size();
    }
}
