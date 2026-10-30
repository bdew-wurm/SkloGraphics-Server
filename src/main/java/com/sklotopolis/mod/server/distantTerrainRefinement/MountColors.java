package com.sklotopolis.mod.server.distantTerrainRefinement;

import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.creatures.Creatures;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemTemplate;
import com.wurmonline.server.items.WurmColor;
import com.wurmonline.server.players.Player;
import com.wurmonline.server.zones.VirtualZone;
import com.wurmonline.server.zones.VolaTile;
import org.gotti.wurmunlimited.modcomm.Channel;
import org.gotti.wurmunlimited.modcomm.IChannelListener;
import org.gotti.wurmunlimited.modcomm.ModComm;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class MountColors {
    private static final Logger logger = Logger.getLogger(MountColors.class.getName());

    static final String CHANNEL_NAME = "SkloGraphics:mountColors";
    static final byte MSG_SNAPSHOT = 1;

    static final int PART_BARDING = 0;
    static final int PART_SADDLE = 1;
    static final int PART_BRIDLE = 2;
    static final int PART_BAGS = 3;
    static final int PARTS = 4;

    private static final long WAIT_FOR_CHANNEL_MS = 60000L;

    private static volatile Channel channel;
    private static final Map<Player, Map<Long, Long>> pending = new IdentityHashMap<Player, Map<Long, Long>>();
    private static int loggedSends;

    private MountColors() {
    }

    static void registerChannel() {
        try {
            channel = ModComm.registerChannel(CHANNEL_NAME, new IChannelListener() {
                @Override
                public void handleMessage(Player player, ByteBuffer message) {
                }
            });
            logger.info("MountColors: registered ModComm channel " + CHANNEL_NAME);
        } catch (RuntimeException e) {
            channel = null;
            logger.log(Level.SEVERE, "MountColors: could not register ModComm channel - mount gear colours inactive", e);
        }
    }

    public static void queue(Player watcher, long creatureId) {
        try {
            if (watcher == null || channel == null) return;
            synchronized (pending) {
                Map<Long, Long> creatures = pending.get(watcher);
                if (creatures == null) {
                    creatures = new HashMap<Long, Long>();
                    pending.put(watcher, creatures);
                }
                if (!creatures.containsKey(creatureId)) creatures.put(creatureId, System.currentTimeMillis());
            }
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "MountColors: queue failed", e);
        }
    }

    public static void colorChanged(Item item) {
        try {
            if (channel == null || item == null) return;
            long ownerId = item.getOwnerId();
            if (ownerId == -10L) return;
            Creature owner = Creatures.getInstance().getCreatureOrNull(ownerId);
            if (owner == null || !owner.isAnimal() || !owner.isVehicle()) return;
            VolaTile tile = owner.getCurrentTile();
            if (tile == null) return;
            VirtualZone[] watchers = tile.getWatchers();
            if (watchers == null) return;
            for (VirtualZone zone : watchers) {
                Creature watcher = zone == null ? null : zone.getWatcher();
                if (watcher instanceof Player) queue((Player) watcher, owner.getWurmId());
            }
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "MountColors: colour change for item " + item.getWurmId() + " failed", e);
        }
    }

    static void forget(long playerId) {
        synchronized (pending) {
            for (Iterator<Player> it = pending.keySet().iterator(); it.hasNext(); ) {
                if (it.next().getWurmId() == playerId) it.remove();
            }
        }
    }

    static void flush() {
        Channel ch = channel;
        if (ch == null) return;
        synchronized (pending) {
            if (pending.isEmpty()) return;
            long now = System.currentTimeMillis();
            for (Iterator<Map.Entry<Player, Map<Long, Long>>> it = pending.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Player, Map<Long, Long>> entry = it.next();
                Player player = entry.getKey();
                if (!player.hasLink()) {
                    it.remove();
                    continue;
                }
                Map<Long, Long> creatures = entry.getValue();
                if (!ch.isActiveForPlayer(player)) {
                    for (Iterator<Long> times = creatures.values().iterator(); times.hasNext(); ) {
                        if (now - times.next() > WAIT_FOR_CHANNEL_MS) times.remove();
                    }
                    if (creatures.isEmpty()) it.remove();
                    continue;
                }
                for (Long id : creatures.keySet()) {
                    try {
                        ByteBuffer message = snapshot(id);
                        if (message != null) ch.sendMessage(player, message);
                    } catch (RuntimeException e) {
                        logger.log(Level.WARNING, "MountColors: could not send colours of creature " + id + " to " + player.getName(), e);
                    }
                }
                it.remove();
            }
        }
    }

    private static ByteBuffer snapshot(long creatureId) {
        Creature creature = Creatures.getInstance().getCreatureOrNull(creatureId);
        if (creature == null || creature.getBody() == null) return null;
        int[] colors = {-1, -1, -1, -1};
        Item[] worn = creature.getBody().getContainersAndWornItems();
        if (worn != null) {
            for (Item item : worn) {
                if (item == null) continue;
                int part = partOf(item);
                if (part < 0) continue;
                colors[part] = item.getColor();
                if (part == PART_SADDLE) {
                    for (Item inside : item.getItems()) {
                        if (inside != null && inside.isSaddleBags()) {
                            colors[PART_BAGS] = inside.getColor();
                            break;
                        }
                    }
                }
            }
        }
        ByteBuffer bb = ByteBuffer.allocate(1 + 8 + 1 + PARTS * 4);
        bb.put(MSG_SNAPSHOT);
        bb.putLong(creatureId);
        bb.put((byte) PARTS);
        for (int color : colors) {
            bb.put((byte) (color == -1 ? 0 : 1));
            bb.put((byte) (color == -1 ? 0 : WurmColor.getColorRed(color)));
            bb.put((byte) (color == -1 ? 0 : WurmColor.getColorGreen(color)));
            bb.put((byte) (color == -1 ? 0 : WurmColor.getColorBlue(color)));
        }
        bb.flip();
        if (loggedSends < 20) {
            loggedSends++;
            logger.info("MountColors: " + creature.getName() + " (" + creatureId + ") barding=" + colors[PART_BARDING]
                    + " saddle=" + colors[PART_SADDLE] + " bridle=" + colors[PART_BRIDLE] + " bags=" + colors[PART_BAGS]);
        }
        return bb;
    }

    private static int partOf(Item item) {
        ItemTemplate template = item.getTemplate();
        String model = template == null ? null : template.getModelName();
        if (model == null) return -1;
        if (model.contains("barding")) return PART_BARDING;
        if (model.contains("saddle")) return PART_SADDLE;
        if (model.contains("bridle")) return PART_BRIDLE;
        return -1;
    }
}
