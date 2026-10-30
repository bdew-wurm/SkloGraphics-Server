package com.sklotopolis.mod.server.distantTerrainRefinement;

import com.wurmonline.server.creatures.Communicator;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.creatures.CreatureTemplate;
import com.wurmonline.server.creatures.Creatures;
import com.wurmonline.server.players.Player;
import com.wurmonline.server.zones.VirtualZone;
import com.wurmonline.server.zones.VolaTile;
import org.gotti.wurmunlimited.modcomm.Channel;
import org.gotti.wurmunlimited.modcomm.IChannelListener;
import org.gotti.wurmunlimited.modcomm.ModComm;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class CreatureColors {
    private static final Logger logger = Logger.getLogger(CreatureColors.class.getName());

    static final String CHANNEL_NAME = "SkloGraphics:creatureColors";
    static final byte MSG_COLOR = 1;

    private static final File STORE = new File("mods" + File.separator + "distantTerrainRefinement" + File.separator + "creatureColors.properties");
    private static final long WAIT_FOR_CHANNEL_MS = 60000L;

    private static final Map<Long, Integer> colors = new ConcurrentHashMap<Long, Integer>();
    private static final Map<Integer, Integer> templateColors = new ConcurrentHashMap<Integer, Integer>();
    private static final Set<Integer> dirtyTemplates = Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
    private static final Map<Player, Map<Long, Long>> pending = new IdentityHashMap<Player, Map<Long, Long>>();
    private static volatile Channel channel;
    private static volatile boolean serverPolling;

    private CreatureColors() {
    }

    public static void setTemplateColor(int templateId, int red, int green, int blue, int alpha) {
        if (alpha <= 0) {
            clearTemplateColor(templateId);
            return;
        }
        Integer packed = pack(red, green, blue, alpha);
        if (packed.equals(templateColors.put(templateId, packed))) return;
        dirtyTemplates.add(templateId);
    }

    public static void setTemplateColor(CreatureTemplate template, int red, int green, int blue, int alpha) {
        if (template != null) setTemplateColor(template.getTemplateId(), red, green, blue, alpha);
    }

    public static void clearTemplateColor(int templateId) {
        if (templateColors.remove(templateId) == null) return;
        dirtyTemplates.add(templateId);
    }

    public static void clearTemplateColor(CreatureTemplate template) {
        if (template != null) clearTemplateColor(template.getTemplateId());
    }

    public static int[] getTemplateColor(int templateId) {
        return unpack(templateColors.get(templateId));
    }

    public static int[] getCreatureColor(long creatureId) {
        return unpack(colors.get(creatureId));
    }

    private static Integer resolve(Creature creature) {
        if (creature == null) return null;
        if (!colors.isEmpty()) {
            Integer own = colors.get(creature.getWurmId());
            if (own != null) return own;
        }
        if (templateColors.isEmpty() || creature.isPlayer()) return null;
        return templateColors.get(creature.getTemplateId());
    }

    private static Integer resolve(long creatureId) {
        if (!colors.isEmpty()) {
            Integer own = colors.get(creatureId);
            if (own != null) return own;
        }
        if (templateColors.isEmpty() || !serverPolling) return null;
        return resolve(Creatures.getInstance().getCreatureOrNull(creatureId));
    }

    private static int[] unpack(Integer packed) {
        if (packed == null) return null;
        int p = packed;
        return new int[]{p >>> 24, (p >>> 16) & 0xFF, (p >>> 8) & 0xFF, p & 0xFF};
    }

    private static void broadcastTemplate(int templateId) {
        Creatures all = Creatures.getInstance();
        if (all == null) return;
        Creature[] creatures = all.getCreatures();
        if (creatures == null) return;
        int repainted = 0;
        for (Creature creature : creatures) {
            if (creature == null) continue;
            try {
                if (creature.isPlayer() || creature.getTemplateId() != templateId) continue;
                broadcast(creature);
                repainted++;
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "CreatureColors: could not repaint creature " + creature.getWurmId(), e);
            }
        }
        int[] c = getTemplateColor(templateId);
        logger.info("CreatureColors: template " + templateId + (c == null ? " colour cleared" : " coloured r=" + c[0] + " g=" + c[1] + " b=" + c[2] + " a=" + c[3])
                + ", repainted " + repainted + " creatures");
    }

    public static void setColor(Creature creature, int red, int green, int blue, int alpha) {
        if (creature == null) return;
        if (alpha <= 0) {
            clearColor(creature);
            return;
        }
        colors.put(creature.getWurmId(), pack(red, green, blue, alpha));
        save();
        broadcast(creature);
    }

    public static void clearColor(Creature creature) {
        if (creature == null) return;
        if (colors.remove(creature.getWurmId()) == null) return;
        save();
        broadcast(creature);
    }

    public static int[] getColor(long creatureId) {
        return unpack(resolve(creatureId));
    }

    public static int[] getColor(Creature creature) {
        return unpack(resolve(creature));
    }

    public static boolean has(long creatureId) {
        return resolve(creatureId) != null;
    }

    public static boolean has(Creature creature) {
        return resolve(creature) != null;
    }

    public static byte[] wire(long creatureId) {
        if (colors.isEmpty() && templateColors.isEmpty()) return null;
        Integer packed = resolve(creatureId);
        if (packed == null) return null;
        int p = packed;
        return vanillaBytes(p >>> 24, (p >>> 16) & 0xFF, (p >>> 8) & 0xFF, p & 0xFF);
    }

    static byte[] vanillaBytes(int red, int green, int blue, int alpha) {
        float a = alpha / 255.0f;
        float tr = 1.0f - a + a * red / 255.0f;
        float tg = 1.0f - a + a * green / 255.0f;
        float tb = 1.0f - a + a * blue / 255.0f;
        int[] add = {toByte(tr - 0.11f), toByte(tg - 0.11f), toByte(tb - 0.11f)};
        if (add[0] == add[1] && add[0] == add[2]) add[2] += add[2] < 255 ? 1 : -1;
        float addError = Math.max(Math.abs(Math.min(1.0f, add[0] / 255.0f + 0.11f) - tr),
                Math.max(Math.abs(Math.min(1.0f, add[1] / 255.0f + 0.11f) - tg), Math.abs(Math.min(1.0f, add[2] / 255.0f + 0.11f) - tb)));
        float mean = (tr + tg + tb) / 3.0f;
        int grey = toByte((mean - 0.1f) / 0.8f);
        float greyShown = grey / 255.0f * 0.8f + 0.1f;
        float greyError = Math.max(Math.abs(greyShown - tr), Math.max(Math.abs(greyShown - tg), Math.abs(greyShown - tb)));
        if (greyError < addError) return new byte[]{(byte) grey, (byte) grey, (byte) grey, (byte) 255};
        return new byte[]{(byte) add[0], (byte) add[1], (byte) add[2], (byte) 255};
    }

    private static int toByte(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255.0f)));
    }

    private static int pack(int red, int green, int blue, int alpha) {
        return (clamp(red) << 24) | (clamp(green) << 16) | (clamp(blue) << 8) | clamp(alpha);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static void broadcast(Creature creature) {
        VolaTile tile = creature.getCurrentTile();
        if (tile == null) return;
        VirtualZone[] watchers = tile.getWatchers();
        if (watchers == null) return;
        long id = creature.getWurmId();
        boolean custom = has(creature);
        boolean vanillaPaint = creature.hasCustomColor() && !custom;
        for (VirtualZone zone : watchers) {
            if (zone == null || !zone.containsCreature(creature)) continue;
            Creature watcher = zone.getWatcher();
            if (!(watcher instanceof Player)) continue;
            Communicator communicator = watcher.getCommunicator();
            if (communicator == null) continue;
            if (custom) {
                communicator.sendRepaint(id, (byte) 0, (byte) 0, (byte) 0, (byte) 255, (byte) 0);
            } else {
                communicator.sendRepaint(id, (byte) -1, (byte) -1, (byte) -1, (byte) 0, (byte) 0);
                if (vanillaPaint) {
                    communicator.sendRepaint(id, creature.getColorRed(), creature.getColorGreen(), creature.getColorBlue(), (byte) -1, (byte) creature.getPaintMode());
                }
            }
            queue((Player) watcher, id);
        }
    }

    static void registerChannel() {
        try {
            channel = ModComm.registerChannel(CHANNEL_NAME, new IChannelListener() {
                @Override
                public void handleMessage(Player player, ByteBuffer message) {
                }
            });
            logger.info("CreatureColors: registered ModComm channel " + CHANNEL_NAME);
        } catch (RuntimeException e) {
            channel = null;
            logger.log(Level.SEVERE, "CreatureColors: could not register ModComm channel - exact colours only for vanilla rendering", e);
        }
    }

    public static void queue(Player watcher, long creatureId) {
        if (watcher == null || channel == null) return;
        synchronized (pending) {
            Map<Long, Long> creatures = pending.get(watcher);
            if (creatures == null) {
                creatures = new HashMap<Long, Long>();
                pending.put(watcher, creatures);
            }
            if (!creatures.containsKey(creatureId)) creatures.put(creatureId, System.currentTimeMillis());
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
        serverPolling = true;
        if (!dirtyTemplates.isEmpty()) {
            for (Iterator<Integer> it = dirtyTemplates.iterator(); it.hasNext(); ) {
                int templateId = it.next();
                it.remove();
                broadcastTemplate(templateId);
            }
        }
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
                        ch.sendMessage(player, message(id));
                    } catch (RuntimeException e) {
                        logger.log(Level.WARNING, "CreatureColors: could not send colour of creature " + id + " to " + player.getName(), e);
                    }
                }
                it.remove();
            }
        }
    }

    private static ByteBuffer message(long creatureId) {
        int[] c = getColor(creatureId);
        ByteBuffer bb = ByteBuffer.allocate(1 + 8 + 1 + 4);
        bb.put(MSG_COLOR);
        bb.putLong(creatureId);
        bb.put((byte) (c == null ? 0 : 1));
        for (int i = 0; i < 4; i++) bb.put((byte) (c == null ? 0 : c[i]));
        bb.flip();
        return bb;
    }

    static void load() {
        if (!STORE.isFile()) return;
        Properties props = new Properties();
        InputStream in = null;
        try {
            in = new FileInputStream(STORE);
            props.load(in);
            for (String key : props.stringPropertyNames()) {
                try {
                    long id = Long.parseLong(key.trim());
                    long value = Long.parseLong(props.getProperty(key).trim(), 16);
                    if ((value & 0xFF) > 0) colors.put(id, (int) value);
                } catch (NumberFormatException e) {
                    logger.warning("CreatureColors: skipping bad entry " + key);
                }
            }
            logger.info("CreatureColors: loaded " + colors.size() + " creature colours");
        } catch (IOException e) {
            logger.log(Level.WARNING, "CreatureColors: could not read " + STORE, e);
        } finally {
            closeQuietly(in);
        }
    }

    private static synchronized void save() {
        Map<Long, Integer> sorted = new TreeMap<Long, Integer>(colors);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Long, Integer> e : sorted.entrySet()) {
            sb.append(e.getKey()).append('=').append(String.format("%08X", e.getValue())).append('\n');
        }
        File tmp = new File(STORE.getPath() + ".tmp");
        OutputStream out = null;
        try {
            File dir = STORE.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
            out = new FileOutputStream(tmp);
            out.write(sb.toString().getBytes("ISO-8859-1"));
            out.close();
            out = null;
            if (STORE.exists() && !STORE.delete()) throw new IOException("cannot replace " + STORE);
            if (!tmp.renameTo(STORE)) throw new IOException("cannot rename " + tmp);
        } catch (IOException e) {
            logger.log(Level.WARNING, "CreatureColors: could not save " + STORE, e);
        } finally {
            closeQuietly(out);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }
}
