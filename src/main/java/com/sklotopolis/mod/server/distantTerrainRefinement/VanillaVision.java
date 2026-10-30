package com.sklotopolis.mod.server.distantTerrainRefinement;

import com.wurmonline.server.creatures.VisionArea;
import com.wurmonline.server.players.Player;

import java.lang.reflect.Field;
import java.util.logging.Level;
import java.util.logging.Logger;

final class VanillaVision {
    private static final Logger logger = Logger.getLogger(VanillaVision.class.getName());

    static final int NEAR_BACKLOG_FACTOR = 16;

    private static volatile Field sentCloseStrips;
    private static volatile boolean unavailable;

    private VanillaVision() {
    }

    static boolean nearLoaded(Player player) {
        if (unavailable || player == null) return true;
        VisionArea area = player.getVisionArea();
        if (area == null) return false;
        try {
            Field f = sentCloseStrips;
            if (f == null) {
                f = VisionArea.class.getDeclaredField("sentCloseStrips");
                f.setAccessible(true);
                sentCloseStrips = f;
            }
            return f.getBoolean(area);
        } catch (ReflectiveOperationException | RuntimeException e) {
            unavailable = true;
            logger.log(Level.WARNING, "VanillaVision: cannot read VisionArea.sentCloseStrips, mod traffic no longer waits for vanilla loading", e);
            return true;
        }
    }
}
