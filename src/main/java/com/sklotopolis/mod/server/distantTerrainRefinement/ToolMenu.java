package com.sklotopolis.mod.server.distantTerrainRefinement;

import com.wurmonline.server.Items;
import com.wurmonline.server.behaviours.ActionEntry;
import com.wurmonline.server.behaviours.BehaviourDispatcher;
import com.wurmonline.server.creatures.Communicator;
import com.wurmonline.server.creatures.Creature;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.players.Player;
import org.gotti.wurmunlimited.modcomm.Channel;
import org.gotti.wurmunlimited.modcomm.IChannelListener;
import org.gotti.wurmunlimited.modcomm.ModComm;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ToolMenu {
    private static final Logger logger = Logger.getLogger(ToolMenu.class.getName());

    static final String CHANNEL_NAME = "SkloGraphics:toolMenu";
    static final byte VERSION = 1;
    static final byte MSG_WANT = 1;
    static final byte MSG_MENU = 2;

    private static final long WANT_TTL_MS = 10_000L;
    private static final int MAX_ENTRIES = 255;
    private static final int MAX_DEPTH = 6;

    private static volatile Channel channel;
    private static volatile boolean enabled = true;
    private static volatile int maxTools = 150;
    private static final Map<Long, Want> wants = new ConcurrentHashMap<Long, Want>();

    private static Ctx ctx;
    private static boolean capturing;
    private static List<ActionEntry> captured;
    private static long slowWarnAt;

    private static final class Want {
        final byte requestId;
        final long target;
        final long at;

        Want(byte requestId, long target, long at) {
            this.requestId = requestId;
            this.target = target;
            this.at = at;
        }
    }

    private static final class Ctx {
        final Player player;
        final Communicator comm;
        final byte requestId;
        final long subject;
        final long target;

        Ctx(Player player, Communicator comm, byte requestId, long subject, long target) {
            this.player = player;
            this.comm = comm;
            this.requestId = requestId;
            this.subject = subject;
            this.target = target;
        }
    }

    private static final class Leaf {
        final short id;
        final String name;
        final List<String> path;

        Leaf(short id, String name, List<String> path) {
            this.id = id;
            this.name = name;
            this.path = path;
        }
    }

    private static final class Offer {
        final Leaf leaf;
        Item tool;
        boolean active;

        Offer(Leaf leaf, Item tool, boolean active) {
            this.leaf = leaf;
            this.tool = tool;
            this.active = active;
        }
    }

    private ToolMenu() {
    }

    static void configure(Properties properties) {
        enabled = Boolean.parseBoolean(properties.getProperty("toolMenu", "true").trim());
        try {
            maxTools = Math.max(1, Math.min(1000, Integer.parseInt(properties.getProperty("toolMenuMaxTools", "150").trim())));
        } catch (NumberFormatException e) {
            maxTools = 150;
        }
        logger.info("ToolMenu: " + (enabled ? "on, up to " + maxTools + " tools per menu" : "off"));
    }

    static void registerChannel() {
        try {
            channel = ModComm.registerChannel(CHANNEL_NAME, new IChannelListener() {
                @Override
                public void handleMessage(Player player, ByteBuffer message) {
                    want(player, message);
                }
            });
            logger.info("ToolMenu: registered ModComm channel " + CHANNEL_NAME);
        } catch (RuntimeException e) {
            channel = null;
            logger.log(Level.SEVERE, "ToolMenu: could not register ModComm channel", e);
        }
    }

    static void forget(long playerId) {
        wants.remove(playerId);
    }

    private static void want(Player player, ByteBuffer message) {
        try {
            if (message.remaining() < 11 || message.get() != MSG_WANT || message.get() != VERSION) return;
            byte requestId = message.get();
            long target = message.getLong();
            wants.put(player.getWurmId(), new Want(requestId, target, System.currentTimeMillis()));
        } catch (RuntimeException e) {
            logger.log(Level.FINE, "ToolMenu: bad request from " + player.getName(), e);
        }
    }

    public static void requestStart(Creature creature, Communicator comm, byte requestId, long subject, long target) {
        if (capturing) return;
        ctx = null;
        if (!enabled || channel == null || !(creature instanceof Player)) return;
        Player player = (Player) creature;
        Want w = wants.get(player.getWurmId());
        if (w == null || w.requestId != requestId || w.target != target) return;
        wants.remove(player.getWurmId());
        if (System.currentTimeMillis() - w.at > WANT_TTL_MS) return;
        ctx = new Ctx(player, comm, requestId, subject, target);
    }

    public static void requestEnd() {
        if (!capturing) ctx = null;
    }

    public static boolean onSend(Communicator comm, byte requestId, List<ActionEntry> actions) {
        if (capturing) {
            captured = actions == null ? Collections.<ActionEntry>emptyList() : new ArrayList<ActionEntry>(actions);
            return true;
        }
        Ctx c = ctx;
        if (c == null || c.requestId != requestId || c.comm != comm) return false;
        ctx = null;
        try {
            send(c, actions);
        } catch (Throwable t) {
            logger.log(Level.WARNING, "ToolMenu: could not build the tool menu for " + c.player.getName(), t);
        }
        return false;
    }

    private static List<ActionEntry> capture(Ctx c, long subject) {
        capturing = true;
        captured = null;
        try {
            BehaviourDispatcher.requestActions(c.player, c.comm, c.requestId, subject, c.target);
        } catch (Exception e) {
            return null;
        } finally {
            capturing = false;
        }
        List<ActionEntry> out = captured;
        captured = null;
        return out;
    }

    private static void send(Ctx c, List<ActionEntry> vanilla) throws IOException {
        Channel ch = channel;
        if (ch == null || !ch.isActiveForPlayer(c.player)) return;
        long started = System.nanoTime();

        Item active = owned(c.player, c.subject);
        List<ActionEntry> bare = c.subject == -1L || c.subject == -10L ? vanilla : capture(c, -1L);
        if (bare == null) return;
        Map<Short, Boolean> base = new HashMap<Short, Boolean>();
        for (Leaf l : leaves(bare)) base.put(l.id, Boolean.TRUE);

        Map<Short, Offer> offers = new LinkedHashMap<Short, Offer>();
        if (active != null) {
            Map<Short, Boolean> shown = new HashMap<Short, Boolean>();
            for (Leaf l : leaves(vanilla)) {
                shown.put(l.id, Boolean.TRUE);
                if (!base.containsKey(l.id) && !offers.containsKey(l.id)) offers.put(l.id, new Offer(l, active, true));
            }
            for (Leaf l : leaves(bare)) {
                if (!shown.containsKey(l.id) && !offers.containsKey(l.id)) offers.put(l.id, new Offer(l, null, false));
            }
        }

        int tried = 0;
        for (Item tool : candidates(c.player, active, c.target)) {
            if (tried++ >= maxTools) break;
            List<ActionEntry> list = capture(c, tool.getWurmId());
            if (list == null) continue;
            float ql = tool.getCurrentQualityLevel();
            for (Leaf l : leaves(list)) {
                if (base.containsKey(l.id)) continue;
                Offer o = offers.get(l.id);
                if (o == null) {
                    offers.put(l.id, new Offer(l, tool, false));
                } else if (!o.active && ql > o.tool.getCurrentQualityLevel()) {
                    o.tool = tool;
                }
            }
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1024);
        DataOutputStream d = new DataOutputStream(bytes);
        d.writeByte(MSG_MENU);
        d.writeByte(VERSION);
        d.writeByte(c.requestId);
        d.writeLong(c.target);
        int n = Math.min(MAX_ENTRIES, offers.size());
        d.writeShort(n);
        int i = 0;
        for (Offer o : offers.values()) {
            if (i++ >= n) break;
            d.writeShort(o.leaf.id);
            d.writeUTF(o.leaf.name);
            d.writeLong(o.tool == null ? -1L : o.tool.getWurmId());
            d.writeByte(o.leaf.path.size());
            for (String p : o.leaf.path) d.writeUTF(p);
        }
        d.flush();
        ch.sendMessage(c.player, ByteBuffer.wrap(bytes.toByteArray()));

        long ms = (System.nanoTime() - started) / 1_000_000L;
        if (ms > 50 && System.currentTimeMillis() - slowWarnAt > 60_000L) {
            slowWarnAt = System.currentTimeMillis();
            logger.info("ToolMenu: " + tried + " tools for " + c.player.getName() + " took " + ms + " ms");
        }
    }

    private static Item owned(Player player, long subject) {
        if (subject == -1L || subject == -10L) return null;
        try {
            Item item = Items.getItem(subject);
            return item.getOwnerId() == player.getWurmId() ? item : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Item> candidates(Player player, Item active, long target) {
        Map<String, Item> best = new LinkedHashMap<String, Item>();
        collect(best, player.getInventory().getAllItems(false), active, target);
        try {
            collect(best, player.getBody().getAllItems(), active, target);
        } catch (Exception e) {
            logger.log(Level.FINE, "ToolMenu: could not read the body of " + player.getName(), e);
        }
        List<Item> out = new ArrayList<Item>(best.values());
        Collections.sort(out, new java.util.Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                int ta = a.isTool() || a.isWeapon() ? 0 : 1;
                int tb = b.isTool() || b.isWeapon() ? 0 : 1;
                if (ta != tb) return ta - tb;
                return Float.compare(b.getCurrentQualityLevel(), a.getCurrentQualityLevel());
            }
        });
        return out;
    }

    private static void collect(Map<String, Item> best, Item[] items, Item active, long target) {
        if (items == null) return;
        String activeKey = active != null ? key(active) : null;
        for (Item it : items) {
            if (it == null || it == active || it.getWurmId() == target) continue;
            if (it.isBodyPart() || it.isInventory() || it.isInventoryGroup() || it.isCoin()) continue;
            String key = key(it);
            if (key.equals(activeKey)) continue;
            Item old = best.get(key);
            if (old == null || it.getCurrentQualityLevel() > old.getCurrentQualityLevel()) best.put(key, it);
        }
    }

    private static String key(Item it) {
        StringBuilder k = new StringBuilder()
                .append(it.getTemplateId()).append(':').append(it.getRealTemplateId())
                .append(':').append(it.getMaterial()).append(':').append(it.getRarity())
                .append(':').append(it.getAuxData())
                .append(it.isUnfinished() ? 'u' : '-')
                .append(it.isWrapped() ? 'w' : '-')
                .append(it.isSealedByPlayer() ? 's' : '-')
                .append(it.isLocked() ? 'l' : '-')
                .append(it.isOnFire() ? 'f' : '-')
                .append(it.getTemperature() >= 3500 ? 'g' : it.getTemperature() >= 1000 ? 'h' : '-')
                .append(it.getCurrentQualityLevel() <= 0f ? 'z' : '-');
        if (it.isHollow()) {
            Item[] inside = it.getItemsAsArray();
            if (inside.length == 0) {
                k.append(":empty");
            } else {
                java.util.TreeSet<String> kinds = new java.util.TreeSet<String>();
                for (Item c : inside) {
                    if (kinds.size() >= 8) break;
                    kinds.add(c.getTemplateId() + "/" + c.getMaterial() + (c.isLiquid() ? "l" : ""));
                }
                k.append(':').append(kinds);
            }
        }
        return k.toString();
    }

    private static List<Leaf> leaves(List<ActionEntry> list) {
        List<Leaf> out = new ArrayList<Leaf>();
        if (list == null) return out;
        Iterator<ActionEntry> it = list.iterator();
        while (it.hasNext()) walk(it, it.next(), Collections.<String>emptyList(), out);
        return out;
    }

    private static void walk(Iterator<ActionEntry> it, ActionEntry e, List<String> path, List<Leaf> out) {
        if (e == null) return;
        short n = e.getNumber();
        String name = e.getActionString() == null ? "" : e.getActionString();
        if (n < 0) {
            List<String> sub = path;
            if (path.size() < MAX_DEPTH) {
                sub = new ArrayList<String>(path);
                sub.add(name);
            }
            for (int k = 0; k < -n && it.hasNext(); k++) walk(it, it.next(), sub, out);
        } else if (n > 0 && !name.isEmpty()) {
            out.add(new Leaf(n, name, path));
        }
    }
}
