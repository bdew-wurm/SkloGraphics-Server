package com.sklotopolis.mod.server.distantTerrainRefinement;

import com.wurmonline.communication.SocketConnection;
import com.wurmonline.mesh.Tiles;
import com.wurmonline.server.Constants;
import com.wurmonline.server.Players;
import com.wurmonline.server.Server;
import com.wurmonline.server.creatures.Communicator;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.creatures.VisionArea;
import com.wurmonline.server.players.Player;
import com.wurmonline.server.zones.Zones;
import org.gotti.wurmunlimited.modcomm.Channel;
import org.gotti.wurmunlimited.modcomm.IChannelListener;
import org.gotti.wurmunlimited.modcomm.ModComm;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class CaveVision {
    private static final Logger logger = Logger.getLogger(CaveVision.class.getName());

    static final String CHANNEL_NAME = "SkloGraphics:caveVision";
    static final byte MSG_REQUEST = 1;
    static final byte MSG_GRANT = 2;
    static final byte PROTOCOL_VERSION = 1;

    static final int VANILLA_RADIUS = 24;
    static final int MIN_RADIUS = 32;
    static final int HARD_MAX_RADIUS = 96;
    private static final int MAX_TILES_PER_MESSAGE = 2048;
    private static final int MAX_DIRECT_TILES = 4096;
    private static final int SEND_LOW_WATER_BYTES = 4096;
    private static final int MAX_ROWS_PER_POLL = 8;

    private static volatile Channel channel;
    private static volatile int maxRadius = HARD_MAX_RADIUS;
    private static final Map<Long, State> players = new ConcurrentHashMap<>();
    private static final ThreadLocal<Strip> strips = new ThreadLocal<Strip>() {
        @Override
        protected Strip initialValue() {
            return new Strip();
        }
    };
    private static Field visionX;
    private static Field visionY;
    private static boolean loggedFailure;

    private static final class State {
        final int radius;
        boolean pending;
        int next;

        State(int radius) {
            this.radius = radius;
        }
    }

    private static final class Strip {
        boolean mask;
        int x0;
        int y0;
        int height;
        int index;
        int centerX;
        int centerY;
    }

    private CaveVision() {
    }

    static void configure(Properties properties) {
        int value = HARD_MAX_RADIUS;
        try {
            value = Integer.parseInt(properties.getProperty("caveViewDistanceMax", Integer.toString(HARD_MAX_RADIUS)).trim());
        } catch (NumberFormatException e) {
            logger.warning("CaveVision: caveViewDistanceMax is not a number, using " + HARD_MAX_RADIUS);
        }
        maxRadius = value < MIN_RADIUS ? 0 : Math.min(value, HARD_MAX_RADIUS);
        logger.info("CaveVision: cave view distance for SkloGraphics players up to " + maxRadius + " tiles"
                + (maxRadius == 0 ? " (off, vanilla 24)" : ""));
    }

    static void registerChannel() {
        try {
            channel = ModComm.registerChannel(CHANNEL_NAME, new IChannelListener() {
                @Override
                public void handleMessage(Player player, ByteBuffer message) {
                    CaveVision.handleMessage(player, message);
                }
            });
            logger.info("CaveVision: registered ModComm channel " + CHANNEL_NAME);
        } catch (RuntimeException e) {
            channel = null;
            logger.log(Level.WARNING, "CaveVision: could not register ModComm channel - caves stay at the vanilla distance", e);
        }
    }

    static void forget(long wurmId) {
        players.remove(wurmId);
    }

    private static void handleMessage(Player player, ByteBuffer message) {
        try {
            if (message.get() != MSG_REQUEST) return;
            if (message.get() < 1) return;
            int requested = message.getShort();
            int max = maxRadius;
            int granted = max >= MIN_RADIUS && requested >= MIN_RADIUS ? Math.min(requested, max) : 0;
            if (granted > 0) players.put(player.getWurmId(), new State(granted));
            else players.remove(player.getWurmId());
            Channel ch = channel;
            if (ch != null) {
                ByteBuffer reply = ByteBuffer.allocate(4);
                reply.put(MSG_GRANT);
                reply.put(PROTOCOL_VERSION);
                reply.putShort((short) granted);
                reply.flip();
                ch.sendMessage(player, reply);
            }
            logger.info("CaveVision: " + player.getName() + " asked for " + requested + " tiles of cave view, granted " + granted);
            if (granted > 0) scheduleFull(player);
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "CaveVision: bad message from " + player.getName(), e);
        }
    }

    public static int radius(Creature creature) {
        if (creature == null || players.isEmpty()) return 0;
        State state = players.get(creature.getWurmId());
        if (state == null || creature.isOnSurface()) return 0;
        return state.radius;
    }

    public static void scheduleFull(Creature creature) {
        if (radius(creature) <= 0) return;
        State state = players.get(creature.getWurmId());
        if (state == null) return;
        state.pending = true;
        state.next = 0;
    }

    public static boolean horizontalStrip(Creature owner, int xPos, int yPos, boolean positive) {
        int r = radius(owner);
        if (r <= 0) return false;
        Communicator comm = owner.getCommunicator();
        if (comm == null) return true;
        comm.sendCaveStrip((short) (xPos - r), (short) (positive ? yPos + r - 1 : yPos - r), 2 * r, 1);
        return true;
    }

    public static boolean verticalStrip(Creature owner, int xPos, int yPos, boolean positive) {
        int r = radius(owner);
        if (r <= 0) return false;
        Communicator comm = owner.getCommunicator();
        if (comm == null) return true;
        comm.sendCaveStrip((short) (positive ? xPos + r - 1 : xPos - r), (short) (yPos - r), 1, 2 * r);
        return true;
    }

    public static void beginStrip(Player player, int x0, int y0, int height) {
        Strip s = strips.get();
        s.mask = radius(player) > 0;
        if (!s.mask) return;
        s.x0 = x0;
        s.y0 = y0;
        s.height = Math.max(1, height);
        s.index = 0;
        s.centerX = player.getTileX();
        s.centerY = player.getTileY();
    }

    public static void endStrip() {
        strips.get().mask = false;
    }

    public static int tile(int value) {
        Strip s = strips.get();
        if (!s.mask) return value;
        int i = s.index++;
        int x = s.x0 + i / s.height;
        int y = s.y0 + i % s.height;
        if (Math.max(Math.abs(x - s.centerX), Math.abs(y - s.centerY)) <= VANILLA_RADIUS + 1) return value;
        if (!hidden(x, y)) return value;
        return Tiles.encode(Tiles.decodeHeight(value), Tiles.Tile.TILE_CAVE_WALL.id, Tiles.decodeData(value));
    }

    private static boolean hidden(int x, int y) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (!solid(x + dx, y + dy)) return false;
            }
        }
        return true;
    }

    private static boolean solid(int x, int y) {
        if (x < 0 || y < 0 || x >= Zones.worldTileSizeX || y >= Zones.worldTileSizeY) return true;
        return Tiles.isSolidCave(Tiles.decodeType(Server.caveMesh.data[x | y << Constants.meshSize]));
    }

    public static void tilesChanged(int x0, int y0, int width, int height) {
        if (players.isEmpty()) return;
        for (Map.Entry<Long, State> entry : players.entrySet()) {
            Player player = Players.getInstance().getPlayerOrNull(entry.getKey());
            if (player == null || !player.hasLink()) continue;
            int r = radius(player);
            if (r <= 0) continue;
            VisionArea area = player.getVisionArea();
            Communicator comm = player.getCommunicator();
            if (area == null || comm == null) continue;
            int[] pos = visionPosition(area);
            if (pos == null) continue;
            int sx0 = Math.max(x0 - 1, pos[0] - r);
            int sy0 = Math.max(y0 - 1, pos[1] - r);
            int sx1 = Math.min(x0 + width + 1, pos[0] + r);
            int sy1 = Math.min(y0 + height + 1, pos[1] + r);
            if (sx0 >= sx1 || sy0 >= sy1) continue;
            if (sx0 >= pos[0] - VANILLA_RADIUS && sx1 <= pos[0] + VANILLA_RADIUS - 1
                    && sy0 >= pos[1] - VANILLA_RADIUS && sy1 <= pos[1] + VANILLA_RADIUS - 1) continue;
            int stripWidth = sx1 - sx0;
            if (stripWidth * (sy1 - sy0) > MAX_DIRECT_TILES) {
                State state = entry.getValue();
                state.pending = true;
                state.next = 0;
                continue;
            }
            int rows = Math.max(1, MAX_TILES_PER_MESSAGE / stripWidth);
            for (int y = sy0; y < sy1; y += rows) {
                comm.sendCaveStrip((short) sx0, (short) y, stripWidth, Math.min(rows, sy1 - y));
            }
        }
    }

    static void poll() {
        if (players.isEmpty()) return;
        for (Map.Entry<Long, State> entry : players.entrySet()) {
            State state = entry.getValue();
            if (!state.pending) continue;
            try {
                sendPending(entry.getKey(), state);
            } catch (RuntimeException e) {
                state.pending = false;
                if (!loggedFailure) {
                    loggedFailure = true;
                    logger.log(Level.WARNING, "CaveVision: sending the far cave area failed", e);
                }
            }
        }
    }

    private static void sendPending(long wurmId, State state) {
        Player player = Players.getInstance().getPlayerOrNull(wurmId);
        if (player == null || !player.hasLink()) return;
        int r = radius(player);
        if (r <= 0) {
            state.pending = false;
            return;
        }
        VisionArea area = player.getVisionArea();
        Communicator comm = player.getCommunicator();
        SocketConnection connection = comm == null ? null : comm.getConnection();
        if (area == null || connection == null || !VanillaVision.nearLoaded(player)) return;
        int[] pos = visionPosition(area);
        if (pos == null) {
            state.pending = false;
            return;
        }
        int sent = 0;
        while (sent < MAX_ROWS_PER_POLL && state.next < 2 * r && connection.getUnflushed() < SEND_LOW_WATER_BYTES) {
            int n = state.next++;
            int offset = (n & 1) == 0 ? n / 2 : -(n + 1) / 2;
            int y = pos[1] + offset;
            if (offset >= -VANILLA_RADIUS && offset < VANILLA_RADIUS) {
                comm.sendCaveStrip((short) (pos[0] - r), (short) y, r - VANILLA_RADIUS, 1);
                comm.sendCaveStrip((short) (pos[0] + VANILLA_RADIUS), (short) y, r - VANILLA_RADIUS, 1);
            } else {
                comm.sendCaveStrip((short) (pos[0] - r), (short) y, 2 * r, 1);
            }
            sent++;
        }
        if (state.next >= 2 * r) state.pending = false;
    }

    private static int[] visionPosition(VisionArea area) {
        try {
            if (visionX == null) {
                Field fx = VisionArea.class.getDeclaredField("xPos");
                Field fy = VisionArea.class.getDeclaredField("yPos");
                fx.setAccessible(true);
                fy.setAccessible(true);
                visionX = fx;
                visionY = fy;
            }
            return new int[]{visionX.getInt(area), visionY.getInt(area)};
        } catch (ReflectiveOperationException e) {
            if (!loggedFailure) {
                loggedFailure = true;
                logger.log(Level.WARNING, "CaveVision: vision area position unreadable", e);
            }
            return null;
        }
    }
}
