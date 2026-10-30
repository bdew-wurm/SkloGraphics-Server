package com.sklotopolis.mod.server.distantTerrainRefinement;

import com.wurmonline.communication.SocketConnection;
import com.wurmonline.server.Players;
import com.wurmonline.server.behaviours.ActionEntry;
import com.wurmonline.server.behaviours.Actions;
import com.wurmonline.server.creatures.Communicator;
import com.wurmonline.server.creatures.CreatureTemplate;
import com.wurmonline.server.creatures.CreatureTemplateFactory;
import com.wurmonline.server.items.ItemTemplate;
import com.wurmonline.server.items.ItemTemplateFactory;
import com.wurmonline.server.players.Player;
import org.gotti.wurmunlimited.modcomm.Channel;
import org.gotti.wurmunlimited.modcomm.IChannelListener;
import org.gotti.wurmunlimited.modcomm.ModComm;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Sends each SkloGraphics player, once per login, everything their client needs to pick the E-key
 * default action without asking: every action id and name (server-mod actions included), the tool
 * capabilities of every item template, and default-action candidates computed from this server's
 * own ItemTemplate/CreatureTemplate flags - so modded items and creatures are covered too. The
 * client keeps it for the session and only falls back to asking for a menu when neither this nor
 * its built-in table knows a target.
 *
 * Owners can override or extend any of it in mods/distantTerrainRefinement.properties:
 *   defaultAction.item.<templateId>=<candidates>
 *   defaultAction.creature.<templateId>=<candidates>
 *   defaultAction.tile.<tileTypeId>=<candidates>
 * where <candidates> is "none" or a comma list, best first, of
 *   <actionId>[@<tool>[+<tool>...]][!][?]
 * (tool: dig mine slash axe saw sickle scythe field cultivate butcher fish scissors liquid groom
 * lead seed sharp bucket; "!" = only when such a tool is already active; "?" = availability
 * depends on permissions or state, so the client confirms it against a server menu). Example:
 *   defaultAction.item.852=3        large crates open
 *   defaultAction.creature.64=106@lead?
 *
 * The wire format (message types, the version byte, tool bits, flags) is duplicated in the
 * client's DefaultActionCatalog and DefaultActionTable - change them together.
 */
public final class DefaultActionCatalog {
    private static final Logger logger = Logger.getLogger(DefaultActionCatalog.class.getName());

    // Must match the client's DefaultActionCatalog.CHANNEL_NAME exactly.
    static final String CHANNEL_NAME = "SkloGraphics:defaultActions";
    // 2: actions carry their range (metres) and whether they ignore range.
    static final byte VERSION = 2;
    static final byte MSG_BEGIN = 1;
    static final byte MSG_ACTIONS = 2;
    static final byte MSG_TOOLS = 3;
    static final byte MSG_ITEMS = 4;
    static final byte MSG_CREATURES = 5;
    static final byte MSG_TILES = 6;
    static final byte MSG_END = 7;

    static final int T_DIG = 1;
    static final int T_MINE = 1 << 1;
    static final int T_SLASH = 1 << 2;
    static final int T_AXE = 1 << 3;
    static final int T_SAW = 1 << 4;
    static final int T_SICKLE = 1 << 5;
    static final int T_SCYTHE = 1 << 6;
    static final int T_FIELD = 1 << 7;
    static final int T_CULTIVATE = 1 << 8;
    static final int T_BUTCHER = 1 << 9;
    static final int T_FISH = 1 << 10;
    static final int T_SCISSORS = 1 << 11;
    static final int T_LIQUID = 1 << 12;
    static final int T_GROOM = 1 << 13;
    static final int T_LEAD = 1 << 14;
    static final int T_SEED = 1 << 15;
    static final int T_SHARP = 1 << 16;
    static final int T_BUCKET = 1 << 17;

    static final int F_ACTIVE_ONLY = 1;
    static final int F_VERIFY = 2;
    static final int F_CONFIRMED = 4;
    static final int F_TOGGLE_OPEN = 8;

    private static final short OPEN = 3;
    private static final short TAKE = 6;
    private static final short TRADE = 63;
    private static final short CHOP_UP = 97;
    private static final short LEAD = 106;
    private static final short STOP_LEADING = 107;
    private static final short BUTCHER = 120;
    private static final short EMBARK_COMMANDER = 331;
    private static final short EMBARK_PASSENGER = 332;
    private static final short MILK = 345;
    private static final short GROOM = 398;
    private static final short SHEAR = 646;

    private static final int CORPSE = 272;
    private static final int FELLED_TREE = 385;
    private static final int MAX_MESSAGE_BYTES = 12000;
    private static final int SEND_LOW_WATER_BYTES = 8192;
    private static final int MAX_BYTES_PER_POLL = 32768;

    private static final String[] TOOL_NAMES = {"dig", "mine", "slash", "axe", "saw", "sickle", "scythe", "field", "cultivate",
            "butcher", "fish", "scissors", "liquid", "groom", "lead", "seed", "sharp", "bucket"};

    private static final Map<String, String> overrides = new HashMap<String, String>();
    private static final Map<Long, Integer> queue = new ConcurrentHashMap<Long, Integer>();
    private static volatile Channel channel;
    private static volatile List<byte[]> messages;

    private static final class Cand {
        final short action;
        final int tools;
        final int prefer;
        final int flags;

        Cand(short action, int tools, int prefer, int flags) {
            this.action = action;
            this.tools = tools;
            this.prefer = prefer;
            this.flags = flags;
        }
    }

    private DefaultActionCatalog() {
    }

    static void configure(Properties properties) {
        overrides.clear();
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith("defaultAction.")) continue;
            String value = properties.getProperty(key).trim();
            try {
                parse(value);
                overrides.put(key.substring("defaultAction.".length()).trim(), value);
            } catch (IllegalArgumentException e) {
                logger.warning("DefaultActionCatalog: ignoring " + key + "=" + value + " - " + e.getMessage());
            }
        }
        if (!overrides.isEmpty()) logger.info("DefaultActionCatalog: " + overrides.size() + " default action overrides");
    }

    static void registerChannel() {
        try {
            channel = ModComm.registerChannel(CHANNEL_NAME, new IChannelListener() {
                @Override
                public void onPlayerConnected(Player player) {
                    queue.put(player.getWurmId(), 0);
                }

                @Override
                public void handleMessage(Player player, ByteBuffer message) {
                }
            });
            logger.info("DefaultActionCatalog: registered ModComm channel " + CHANNEL_NAME);
        } catch (RuntimeException e) {
            channel = null;
            logger.log(Level.SEVERE, "DefaultActionCatalog: could not register ModComm channel - clients use their built-in table", e);
        }
    }

    static void forget(long playerId) {
        queue.remove(playerId);
    }

    /** Paced like the terrain sender: never more than a few messages ahead of what the socket flushed. */
    static void poll() {
        Channel ch = channel;
        if (ch == null || queue.isEmpty()) return;
        List<byte[]> all = messages();
        if (all == null) return;
        for (Iterator<Map.Entry<Long, Integer>> it = queue.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Integer> entry = it.next();
            Player player = Players.getInstance().getPlayerOrNull(entry.getKey());
            if (player == null || !player.hasLink()) {
                it.remove();
                continue;
            }
            if (!ch.isActiveForPlayer(player)) continue;
            Communicator communicator = player.getCommunicator();
            SocketConnection connection = communicator == null ? null : communicator.getConnection();
            if (connection == null || !VanillaVision.nearLoaded(player)) continue;
            int next = entry.getValue();
            int budget = MAX_BYTES_PER_POLL;
            try {
                while (next < all.size() && budget > 0 && connection.getUnflushed() < SEND_LOW_WATER_BYTES) {
                    byte[] m = all.get(next++);
                    budget -= m.length;
                    ch.sendMessage(player, ByteBuffer.wrap(m));
                }
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "DefaultActionCatalog: could not send to " + player.getName(), e);
                it.remove();
                continue;
            }
            if (next >= all.size()) {
                it.remove();
            } else {
                entry.setValue(next);
            }
        }
    }

    /** Built on first use, once every template (modded ones included) exists. */
    private static List<byte[]> messages() {
        List<byte[]> m = messages;
        if (m != null) return m;
        try {
            m = build();
            messages = m;
            int bytes = 0;
            for (byte[] b : m) bytes += b.length;
            logger.info("DefaultActionCatalog: catalogue built - " + m.size() + " messages, " + (bytes + 1023) / 1024 + " KB per player");
            return m;
        } catch (IOException | RuntimeException e) {
            logger.log(Level.SEVERE, "DefaultActionCatalog: could not build the catalogue", e);
            messages = Collections.emptyList();
            return messages;
        }
    }

    private static List<byte[]> build() throws IOException {
        List<byte[]> out = new ArrayList<byte[]>();
        int id = (int) (System.currentTimeMillis() / 1000L);

        out.add(single(MSG_BEGIN, id));

        Chunker actions = new Chunker(MSG_ACTIONS, out);
        for (ActionEntry e : Actions.actionEntrys) {
            if (e == null || e.getActionString() == null) continue;
            DataOutputStream d = actions.record();
            d.writeShort(e.getNumber());
            d.writeUTF(e.getActionString());
            d.writeShort(Math.max(0, Math.min(0xFFFF, e.getRange())));
            d.writeBoolean(ignoresRange(e));
            actions.commit();
        }
        actions.flush();

        ItemTemplate[] templates = ItemTemplateFactory.getInstance().getTemplates();
        Chunker tools = new Chunker(MSG_TOOLS, out);
        for (ItemTemplate t : templates) {
            int bits = toolBits(t);
            if (bits == 0 || t.getName() == null) continue;
            DataOutputStream d = tools.record();
            d.writeUTF(t.getName());
            d.writeInt(bits);
            tools.commit();
        }
        tools.flush();

        Chunker items = new Chunker(MSG_ITEMS, out);
        for (ItemTemplate t : templates) {
            String model = t.getTemplateId() == FELLED_TREE ? "model.fallen." : t.getModelName();
            if (model == null || model.isEmpty()) continue;
            List<Cand> cands = override("item." + t.getTemplateId());
            if (cands == null) cands = forItem(t);
            DataOutputStream d = items.record();
            d.writeInt(t.getTemplateId());
            d.writeUTF(model);
            d.writeUTF(t.getName() == null ? "" : t.getName());
            write(d, cands);
            items.commit();
        }
        items.flush();

        Chunker creatures = new Chunker(MSG_CREATURES, out);
        for (CreatureTemplate t : CreatureTemplateFactory.getInstance().getTemplates()) {
            String model = t.getModelName();
            if (model == null || model.isEmpty()) continue;
            List<Cand> cands = override("creature." + t.getTemplateId());
            if (cands == null) cands = forCreature(t);
            DataOutputStream d = creatures.record();
            d.writeInt(t.getTemplateId());
            d.writeUTF(model);
            d.writeUTF(t.getName() == null ? "" : t.getName());
            write(d, cands);
            creatures.commit();
        }
        creatures.flush();

        Chunker tiles = new Chunker(MSG_TILES, out);
        for (Map.Entry<String, String> e : overrides.entrySet()) {
            if (!e.getKey().startsWith("tile.")) continue;
            int type;
            try {
                type = Integer.parseInt(e.getKey().substring("tile.".length()));
            } catch (NumberFormatException ex) {
                logger.warning("DefaultActionCatalog: bad tile override key " + e.getKey());
                continue;
            }
            DataOutputStream d = tiles.record();
            d.writeByte(type);
            write(d, parse(e.getValue()));
            tiles.commit();
        }
        tiles.flush();

        out.add(single(MSG_END, id));
        return out;
    }

    /** ItemTemplate flags -> the client's tool bits; the same tests the server's menus run. */
    private static int toolBits(ItemTemplate t) {
        int id = t.getTemplateId();
        int bits = 0;
        if (t.isDiggingtool()) bits |= T_DIG;
        if (t.isMiningtool()) bits |= T_MINE;
        if (t.isWeaponSlash()) bits |= T_SLASH;
        if (t.isWeaponAxe()) bits |= T_AXE;
        if (id == 24) bits |= T_SAW;
        if (id == 267) bits |= T_SICKLE;
        if (id == 268) bits |= T_SCYTHE;
        if (t.isFieldTool()) bits |= T_FIELD;
        if (id == 25 || id == 27) bits |= T_CULTIVATE;
        if (id == 93) bits |= T_BUTCHER;
        if (id == 1343 || id == 705 || id == 707 || id == 1344 || id == 1346) bits |= T_FISH;
        if (id == 394) bits |= T_SCISSORS;
        if (t.isContainerLiquid()) bits |= T_LIQUID;
        if (id == 647) bits |= T_GROOM;
        if (t.isLeadCreature()) bits |= T_LEAD;
        if (t.isSeed()) bits |= T_SEED;
        if (t.isSharp()) bits |= T_SHARP;
        if (id == 421) bits |= T_BUCKET;
        return bits;
    }

    /** Mirrors ItemBehaviour/CorpseBehaviour/HugeLogBehaviour/VehicleBehaviour for a ground item. */
    private static List<Cand> forItem(ItemTemplate t) {
        List<Cand> out = new ArrayList<Cand>(2);
        int id = t.getTemplateId();
        if (id == CORPSE) {
            // The client drops Butcher itself once the model name says "butchered.".
            out.add(new Cand(BUTCHER, T_BUTCHER, 0, 0));
            out.add(new Cand(OPEN, 0, 0, F_TOGGLE_OPEN));
            return out;
        }
        if (id == FELLED_TREE) {
            out.add(new Cand(CHOP_UP, T_AXE | T_SAW, T_AXE, 0));
            return out;
        }
        if ((t.isBoat() || t.isVehicle()) && !t.isChair && !t.isTent()) {
            out.add(new Cand(EMBARK_COMMANDER, 0, 0, F_VERIFY));
            out.add(new Cand(EMBARK_PASSENGER, 0, 0, F_VERIFY));
            return out;
        }
        if (t.isHollow() && (!t.hasViewableSubItems() || t.isContainerWithSubItems())) {
            out.add(new Cand(OPEN, 0, 0, F_TOGGLE_OPEN));
            return out;
        }
        if (!t.isNoTake() && !t.isOutsideOnly() && !t.isLiquid()) out.add(new Cand(TAKE, 0, 0, 0));
        return out;
    }

    private static Field ignoresRangeField;

    /** ActionEntry.isIgnoresRange() is package-private; the field behind it isn't going anywhere. */
    private static boolean ignoresRange(ActionEntry e) {
        try {
            if (ignoresRangeField == null) {
                Field f = ActionEntry.class.getDeclaredField("ignoresRange");
                f.setAccessible(true);
                ignoresRangeField = f;
            }
            return ignoresRangeField.getBoolean(e);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }

    private static Field leadableField;

    private static boolean leadable(CreatureTemplate t) {
        try {
            if (leadableField == null) {
                Field f = CreatureTemplate.class.getDeclaredField("leadable");
                f.setAccessible(true);
                leadableField = f;
            }
            return leadableField.getBoolean(t);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** Mirrors CreatureBehaviour: trade with traders; lead, milk, shear and groom herd animals. */
    private static List<Cand> forCreature(CreatureTemplate t) {
        List<Cand> out = new ArrayList<Cand>(5);
        if (t.isTrader()) {
            out.add(new Cand(TRADE, 0, 0, F_VERIFY));
            return out;
        }
        if (!leadable(t)) return out;
        out.add(new Cand(STOP_LEADING, 0, 0, F_CONFIRMED));
        out.add(new Cand(LEAD, T_LEAD, 0, F_VERIFY));
        if (t.isMilkable()) out.add(new Cand(MILK, T_LIQUID, 0, F_ACTIVE_ONLY));
        if (t.isWoolProducer()) out.add(new Cand(SHEAR, T_SCISSORS, 0, F_ACTIVE_ONLY));
        out.add(new Cand(GROOM, T_GROOM, 0, F_ACTIVE_ONLY));
        return out;
    }

    private static List<Cand> override(String key) {
        String value = overrides.get(key);
        return value == null ? null : parse(value);
    }

    private static List<Cand> parse(String value) {
        List<Cand> out = new ArrayList<Cand>();
        if (value.equalsIgnoreCase("none") || value.isEmpty()) return out;
        for (String part : value.split(",")) {
            String p = part.trim();
            int flags = 0;
            while (p.endsWith("!") || p.endsWith("?")) {
                flags |= p.endsWith("!") ? F_ACTIVE_ONLY : F_VERIFY;
                p = p.substring(0, p.length() - 1).trim();
            }
            int tools = 0;
            int at = p.indexOf('@');
            if (at >= 0) {
                for (String tool : p.substring(at + 1).split("\\+")) {
                    int bit = toolBit(tool.trim().toLowerCase(Locale.ROOT));
                    if (bit == 0) throw new IllegalArgumentException("unknown tool '" + tool + "'");
                    tools |= bit;
                }
                p = p.substring(0, at).trim();
            }
            short action;
            try {
                action = Short.parseShort(p);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("'" + p + "' is not an action id");
            }
            if (action <= 0) throw new IllegalArgumentException("action id must be positive");
            if ((flags & F_ACTIVE_ONLY) != 0 && tools == 0) throw new IllegalArgumentException("'!' needs a tool");
            out.add(new Cand(action, tools, 0, flags));
        }
        if (out.size() > 255) throw new IllegalArgumentException("too many candidates");
        return out;
    }

    private static int toolBit(String name) {
        for (int i = 0; i < TOOL_NAMES.length; i++) {
            if (TOOL_NAMES[i].equals(name)) return 1 << i;
        }
        return 0;
    }

    private static void write(DataOutputStream d, List<Cand> cands) throws IOException {
        d.writeByte(cands.size());
        for (Cand c : cands) {
            d.writeShort(c.action);
            d.writeUTF("");
            d.writeInt(c.tools);
            d.writeInt(c.prefer);
            d.writeByte(c.flags);
        }
    }

    private static byte[] single(byte type, int id) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(16);
        DataOutputStream d = new DataOutputStream(bytes);
        d.writeByte(type);
        d.writeByte(VERSION);
        d.writeInt(id);
        d.flush();
        return bytes.toByteArray();
    }

    /** Packs records of one type into messages of at most MAX_MESSAGE_BYTES, each "type, version, count, records". */
    private static final class Chunker {
        private final byte type;
        private final List<byte[]> out;
        private final ByteArrayOutputStream body = new ByteArrayOutputStream(MAX_MESSAGE_BYTES);
        private final ByteArrayOutputStream rec = new ByteArrayOutputStream(256);
        private final DataOutputStream recData = new DataOutputStream(rec);
        private int count;

        Chunker(byte type, List<byte[]> out) {
            this.type = type;
            this.out = out;
        }

        DataOutputStream record() {
            rec.reset();
            return recData;
        }

        void commit() throws IOException {
            recData.flush();
            if (count > 0 && (4 + body.size() + rec.size() > MAX_MESSAGE_BYTES || count == 0xFFFF)) flush();
            rec.writeTo(body);
            count++;
        }

        void flush() throws IOException {
            if (count == 0) return;
            ByteArrayOutputStream msg = new ByteArrayOutputStream(4 + body.size());
            DataOutputStream d = new DataOutputStream(msg);
            d.writeByte(type);
            d.writeByte(VERSION);
            d.writeShort(count);
            body.writeTo(d);
            d.flush();
            out.add(msg.toByteArray());
            body.reset();
            count = 0;
        }
    }
}
