package com.sklotopolis.mod.server.distantTerrainRefinement;

import com.wurmonline.communication.SocketConnection;
import com.wurmonline.mesh.MeshIO;
import com.wurmonline.mesh.Tiles;
import com.wurmonline.server.Constants;
import com.wurmonline.server.Server;
import com.wurmonline.server.creatures.Communicator;
import com.wurmonline.server.players.Player;
import com.sklotopolis.mod.server.distantTerrainRefinement.RefinedTerrainSender.MapSource;
import org.gotti.wurmunlimited.modcomm.Channel;
import org.gotti.wurmunlimited.modcomm.IChannelListener;
import org.gotti.wurmunlimited.modcomm.ModComm;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.interfaces.Configurable;
import org.gotti.wurmunlimited.modloader.interfaces.Initable;
import org.gotti.wurmunlimited.modloader.interfaces.PlayerLoginListener;
import org.gotti.wurmunlimited.modloader.interfaces.PreInitable;
import org.gotti.wurmunlimited.modloader.interfaces.ServerPollListener;
import org.gotti.wurmunlimited.modloader.interfaces.ServerStartedListener;
import org.gotti.wurmunlimited.modloader.interfaces.WurmServerMod;

import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.NotFoundException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Server half of SkloGraphics's distant terrain refinement: sends 2x2- and 8x8-tile height samples
 * around each subscribed player over a ModComm channel, so the client can draw distant hills
 * smoother than vanilla's one-sample-per-16x16-tiles data allows. See distantTerrain.md.
 *
 * Purely additive, and invisible to anyone who doesn't ask for it:
 *  - vanilla's own terrain packets (opcodes 73 and 103) are untouched - terrain uses only the mod
 *    launcher's listener interfaces (the one bytecode hook, in preInit, only changes the version
 *    tag the server advertises to Steam);
 *  - a player is only ever sent anything after ModComm has activated this channel for them
 *    (Channel.isActiveForPlayer) AND their client has explicitly subscribed - an unmodified
 *    client can do neither, so it costs one map lookup per poll and nothing on the wire.
 *
 * Sending is paced from onServerPoll() on the connection's own backlog, the way vanilla paces its
 * distant-terrain strips (it waits for getUnflushed() < 4096): a whole first window is a few
 * hundred KB, and a player connection throws (and drops the player) once its unsent data
 * reaches 1 MB.
 *
 * No config file: every tunable is a code constant (see RefinedTerrainSender.Tier).
 */
public class DistantTerrainRefinementServerMod implements WurmServerMod, Configurable, PreInitable, Initable, ServerStartedListener, ServerPollListener, PlayerLoginListener {
    private static final Logger logger = Logger.getLogger(DistantTerrainRefinementServerMod.class.getName());

    // Must match the client's DistantTerrainRefinement.CHANNEL_NAME exactly.
    static final String CHANNEL_NAME = "GraphicsPlusPlus:distantTerrainRefinement";

    private static final String ADVERTISED_VERSION_TAG = "version=1.9.1.7;";

    // Only hand ModComm another message while the connection's unsent backlog is below this. Vanilla
    // uses 4096 for its own distant strips; a little more headroom keeps our 16 KB chunks flowing
    // without ever holding more than ~25 KB of the player's (786 KB, drop-at-1-MB) send buffer.
    private static final int SEND_LOW_WATER_BYTES = 8192;
    // Upper bound per player per poll, so one player's first window can't monopolise a poll.
    private static final int MAX_BYTES_PER_POLL = 32768;

    private final Map<Long, PlayerRefinementState> states = new ConcurrentHashMap<>();
    private final Map<Long, DistantStructureSender> structureStates = new ConcurrentHashMap<>();
    private DistantStructureIndex structureIndex;
    private volatile Channel channel;
    private volatile Properties config;

    private final MapSource liveMesh = new MapSource() {
        // Tree/bush lookup per raw type id, built once - Tiles.Tile is fixed at server start.
        private final boolean[] vegetation = buildVegetationTable();
        // Texture key per raw type id: types drawn with the same ground texture share a key.
        private final int[] textureKeys = buildTextureKeyTable();

        @Override
        public int mapSizeTiles() {
            return 1 << Constants.meshSize;
        }

        @Override
        public short height(int tileX, int tileY) {
            // Exactly how vanilla's sendTileStripFar reads a height for opcode 103.
            return Tiles.decodeHeight(Server.surfaceMesh.data[tileX | tileY << Constants.meshSize]);
        }

        @Override
        public byte type(int tileX, int tileY) {
            // Exactly how vanilla's MeshIO.calcDistantTerrain reads a type for its 16x16 majority vote.
            return Tiles.decodeType(Server.surfaceMesh.data[tileX | tileY << Constants.meshSize]);
        }

        @Override
        public boolean isVegetation(byte type) {
            return this.vegetation[type & 0xFF];
        }

        @Override
        public byte tileData(int tileX, int tileY) {
            return Tiles.decodeData(Server.surfaceMesh.data[tileX | tileY << Constants.meshSize]);
        }

        @Override
        public int vegetationAge(int tileX, int tileY) {
            return com.wurmonline.mesh.FoliageAge.getAgeAsByte(
                    Tiles.decodeData(Server.surfaceMesh.data[tileX | tileY << Constants.meshSize])) & 15;
        }

        @Override
        public int textureKey(byte type) {
            return this.textureKeys[type & 0xFF];
        }
    };

    /**
     * Groups tile ids by Tiles.Tile.textureResource (0..255 = one key per distinct texture, in id
     * order). Ids with no Tile, or a Tile without a texture, get a key of their own (256 + id), so
     * they still count - just never together with anything else.
     */
    private static int[] buildTextureKeyTable() {
        final int[] keys = new int[256];
        final java.util.Map<String, Integer> byTexture = new java.util.HashMap<>();
        for (int id = 0; id < 256; ++id) {
            final Tiles.Tile tile = Tiles.getTile(id);
            final String texture = tile == null ? null : tile.textureResource;
            if (texture == null) {
                keys[id] = 256 + id;
            } else {
                Integer key = byTexture.get(texture);
                if (key == null) {
                    key = byTexture.size();
                    byTexture.put(texture, key);
                }
                keys[id] = key;
            }
        }
        return keys;
    }

    private static boolean[] buildVegetationTable() {
        final boolean[] table = new boolean[256];
        for (int id = 0; id < 256; ++id) {
            final Tiles.Tile tile = Tiles.getTile(id);
            table[id] = tile != null && (tile.isTree() || tile.isBush());
        }
        return table;
    }

    @Override
    public void preInit() {
        try {
            CtClass steamHandler = HookManager.getInstance().getClassPool().get("com.wurmonline.server.steam.SteamHandler");
            steamHandler.getDeclaredMethod("createServer").instrument(new ExprEditor() {
                @Override
                public void edit(MethodCall m) throws CannotCompileException {
                    if ("com.wurmonline.shared.constants.SteamVersion".equals(m.getClassName()) && "getTag".equals(m.getMethodName())) {
                        m.replace("$_ = \"" + ADVERTISED_VERSION_TAG + "\";");
                    }
                }
            });
            logger.info("Advertising " + ADVERTISED_VERSION_TAG + " to the Steam server list");
        } catch (NotFoundException | CannotCompileException e) {
            logger.log(Level.WARNING, "Could not change the advertised server version", e);
        }
        try {
            final javassist.ClassPool pool = HookManager.getInstance().getClassPool();
            final CtClass communicator = pool.get("com.wurmonline.server.creatures.Communicator");
            communicator.getDeclaredMethod("sendHorseWear").insertAfter(
                    "try { com.sklotopolis.mod.server.distantTerrainRefinement.MountColors.queue(this.player, $1); } catch (Throwable _mcT) { }");
            communicator.getDeclaredMethod("sendRemoveHorseWear").insertAfter(
                    "try { com.sklotopolis.mod.server.distantTerrainRefinement.MountColors.queue(this.player, $1); } catch (Throwable _mcT) { }");
            pool.get("com.wurmonline.server.items.DbItem").getDeclaredMethod("setColors").insertAfter(
                    "try { com.sklotopolis.mod.server.distantTerrainRefinement.MountColors.colorChanged(this); } catch (Throwable _mcT) { }");
            logger.info("MountColors: hooked horse wear packets and item dyeing");
        } catch (NotFoundException | CannotCompileException e) {
            logger.log(Level.WARNING, "MountColors: could not hook horse wear - mount gear colours inactive", e);
        }
        try {
            final javassist.ClassPool pool = HookManager.getInstance().getClassPool();
            pool.get("com.wurmonline.server.creatures.Communicator").getDeclaredMethod("sendRepaint").insertBefore(
                    "try { byte[] _ccW = com.sklotopolis.mod.server.distantTerrainRefinement.CreatureColors.wire($1);"
                    + " if (_ccW != null) { $2 = _ccW[0]; $3 = _ccW[1]; $4 = _ccW[2]; $5 = _ccW[3]; $6 = (byte) 0;"
                    + " com.sklotopolis.mod.server.distantTerrainRefinement.CreatureColors.queue(this.player, $1); } } catch (Throwable _ccT) { }");
            pool.get("com.wurmonline.server.creatures.Creature").getDeclaredMethod("hasCustomColor").insertAfter(
                    "try { if (!$_ && com.sklotopolis.mod.server.distantTerrainRefinement.CreatureColors.has(this)) $_ = true; } catch (Throwable _ccT) { }");
            logger.info("CreatureColors: hooked creature repaint packets");
        } catch (NotFoundException | CannotCompileException e) {
            logger.log(Level.WARNING, "CreatureColors: could not hook creature repaint - custom creature colours inactive", e);
        }
        try {
            final javassist.ClassPool pool = HookManager.getInstance().getClassPool();
            final String cv = "com.sklotopolis.mod.server.distantTerrainRefinement.CaveVision";
            final CtClass vision = pool.get("com.wurmonline.server.creatures.VisionArea");
            vision.getDeclaredMethod("sendHorisontalCaveStrip").insertBefore(
                    "try { if (" + cv + ".horizontalStrip(this.owner, this.xPos, this.yPos, $1)) return; } catch (Throwable _cvT) { }");
            vision.getDeclaredMethod("sendVerticalCaveStrip").insertBefore(
                    "try { if (" + cv + ".verticalStrip(this.owner, this.xPos, this.yPos, $1)) return; } catch (Throwable _cvT) { }");
            vision.getDeclaredMethod("initializeCaves").insertAfter(
                    "try { " + cv + ".scheduleFull(this.owner); } catch (Throwable _cvT) { }");
            pool.get("com.wurmonline.server.creatures.Creature").getDeclaredMethod("createVisionArea").insertAfter(
                    "try { if (this.isPlayer()) " + cv + ".scheduleFull(this); } catch (Throwable _cvT) { }");
            final CtClass players = pool.get("com.wurmonline.server.Players");
            players.getMethod("sendChangedTile", "(IIZZ)V").insertAfter(
                    "try { if (!$3) " + cv + ".tilesChanged($1, $2, 1, 1); } catch (Throwable _cvT) { }");
            players.getDeclaredMethod("sendChangedTiles").insertAfter(
                    "try { if (!$5) " + cv + ".tilesChanged($1, $2, $3, $4); } catch (Throwable _cvT) { }");
            final int[] masked = {0};
            for (String name : new String[]{"com.wurmonline.server.creatures.Communicator", "com.wurmonline.server.players.PlayerCommunicatorQueued"}) {
                final javassist.CtMethod strip = pool.get(name).getDeclaredMethod("sendCaveStrip");
                strip.instrument(new ExprEditor() {
                    @Override
                    public void edit(MethodCall m) throws CannotCompileException {
                        if ("java.nio.ByteBuffer".equals(m.getClassName()) && "putInt".equals(m.getMethodName())) {
                            masked[0]++;
                            m.replace("$_ = $proceed(" + cv + ".tile($1));");
                        }
                    }
                });
                strip.insertBefore("try { if (this.player != null) " + cv + ".beginStrip(this.player, $1, $2, $4); } catch (Throwable _cvT) { }");
                strip.insertAfter(cv + ".endStrip();", true);
            }
            logger.info("CaveVision: hooked cave strips (" + masked[0] + " tile writes), far cave view up to " + CaveVision.HARD_MAX_RADIUS + " tiles");
        } catch (NotFoundException | CannotCompileException e) {
            logger.log(Level.WARNING, "CaveVision: could not hook cave strips - caves stay at the vanilla distance", e);
        }
        try {
            final int[] nearChecks = {0};
            HookManager.getInstance().getClassPool().get("com.wurmonline.server.creatures.VisionArea").getDeclaredMethod("sendNextStrip")
                    .instrument(new ExprEditor() {
                        @Override
                        public void edit(MethodCall m) throws CannotCompileException {
                            if ("getUnflushed".equals(m.getMethodName())) {
                                nearChecks[0]++;
                                m.replace("$_ = $proceed($$) / " + VanillaVision.NEAR_BACKLOG_FACTOR + ";");
                            }
                        }
                    });
            if (nearChecks[0] == 1)
                logger.info("VanillaVision: near terrain after login/teleport streams with a " + (4 * VanillaVision.NEAR_BACKLOG_FACTOR) + " KB backlog (vanilla 4 KB), far terrain unchanged");
            else
                logger.warning("VanillaVision: expected 1 backlog check in VisionArea.sendNextStrip, found " + nearChecks[0]);
        } catch (NotFoundException | CannotCompileException e) {
            logger.log(Level.WARNING, "VanillaVision: could not speed up near terrain loading", e);
        }
        try {
            final javassist.ClassPool pool = HookManager.getInstance().getClassPool();
            final String rg = "com.sklotopolis.mod.server.distantTerrainRefinement.RuneGlow";
            pool.get("com.wurmonline.server.items.Item").getDeclaredMethod("getRadius").insertBefore(
                    "try { if (" + rg + ".applies(this)) return " + rg + ".radius(this); } catch (Throwable _rgT) { }");
            final CtClass zone = pool.get("com.wurmonline.server.zones.VirtualZone");
            zone.getDeclaredMethod("sendAttachItemEffect").insertBefore(
                    "if (" + rg + ".isRuneGlowLight($2, $6, false)) { $3 = " + rg + ".dim($3); $4 = " + rg + ".dim($4); $5 = " + rg + ".dim($5); }");
            zone.getDeclaredMethod("sendAttachCreatureEffect").insertBefore(
                    "if (" + rg + ".isRuneGlowLight($2, $6, true)) { $3 = " + rg + ".dim($3); $4 = " + rg + ".dim($4); $5 = " + rg + ".dim($5); }");
            logger.info("RuneGlow: glow runes give a faint glow instead of lamp light");
        } catch (NotFoundException | CannotCompileException e) {
            logger.log(Level.WARNING, "RuneGlow: could not hook item light radius - glow runes keep the vanilla light", e);
        }
        try {
            HookManager.getInstance().getClassPool().get("com.wurmonline.server.weather.Weather").getDeclaredMethod("tick").insertAfter(
                    "try { com.sklotopolis.mod.server.distantTerrainRefinement.SunnyWeather.afterTick(this); } catch (Throwable _swT) { }");
            logger.info("SunnyWeather: hooked Weather.tick");
        } catch (NotFoundException | CannotCompileException e) {
            logger.log(Level.WARNING, "SunnyWeather: could not hook Weather.tick - weather stays vanilla", e);
        }
        try {
            final javassist.ClassPool pool = HookManager.getInstance().getClassPool();
            final String tm = "com.sklotopolis.mod.server.distantTerrainRefinement.ToolMenu";
            final javassist.CtMethod request = pool.get("com.wurmonline.server.behaviours.BehaviourDispatcher").getDeclaredMethod("requestActions");
            request.insertBefore("try { " + tm + ".requestStart($1, $2, $3, $4, $5); } catch (Throwable _tmT) { }");
            request.insertAfter(tm + ".requestEnd();", true);
            pool.get("com.wurmonline.server.creatures.Communicator").getDeclaredMethod("sendAvailableActions").insertBefore(
                    "if (" + tm + ".onSend(this, $1, $2)) return;");
            logger.info("ToolMenu: hooked right-click menus");
        } catch (NotFoundException | CannotCompileException e) {
            logger.log(Level.WARNING, "ToolMenu: could not hook right-click menus", e);
        }
    }

    // Initable is required, not decorative: the mod launcher only ever calls init() through
    // Initable (ModLoaderShared: "instanceof Initable"), never WurmServerMod's own default init().
    // Without it the channel is never registered, ModComm never announces itself to clients, and
    // every client silently stays on vanilla 16x16 terrain.
    @Override
    public void init() {
        try {
            this.channel = ModComm.registerChannel(CHANNEL_NAME, new IChannelListener() {
                @Override
                public void onPlayerConnected(Player player) {
                    // Channel just became active for this player (fresh login handshake): start clean.
                    DistantTerrainRefinementServerMod.this.states.put(player.getWurmId(),
                            new PlayerRefinementState(player.getWurmId()));
                    DistantTerrainRefinementServerMod.this.structureStates.remove(player.getWurmId());
                }

                @Override
                public void handleMessage(Player player, ByteBuffer message) {
                    DistantTerrainRefinementServerMod.this.handleMessage(player, message);
                }
            });
            logger.info("DistantTerrainRefinement: registered ModComm channel " + CHANNEL_NAME);
        } catch (RuntimeException e) {
            this.channel = null;
            logger.log(Level.SEVERE, "DistantTerrainRefinement: could not register ModComm channel - mod inactive", e);
        }
    }

    @Override
    public void onServerStarted() {
        Properties properties = this.config;
        if (properties != null) {
            try {
                DefaultActionCatalog.configure(properties);
            } catch (Throwable e) {
                logger.log(Level.WARNING, "DefaultActionCatalog: could not read the defaultAction.* settings", e);
            }
        }
        try {
            InstallNotice.registerChannel();
        } catch (Throwable e) {
            logger.log(Level.WARNING, "InstallNotice: could not register its channel", e);
        }
        try {
            MountColors.registerChannel();
        } catch (Throwable e) {
            logger.log(Level.WARNING, "MountColors: could not register its channel", e);
        }
        try {
            CreatureColors.load();
            CreatureColors.registerChannel();
        } catch (Throwable e) {
            logger.log(Level.WARNING, "CreatureColors: could not start", e);
        }
        if (properties != null) {
            try {
                CaveVision.configure(properties);
            } catch (Throwable e) {
                logger.log(Level.WARNING, "CaveVision: could not read caveViewDistanceMax", e);
            }
        }
        try {
            CaveVision.registerChannel();
        } catch (Throwable e) {
            logger.log(Level.WARNING, "CaveVision: could not register its channel", e);
        }
        try {
            DefaultActionCatalog.registerChannel();
        } catch (Throwable e) {
            logger.log(Level.WARNING, "DefaultActionCatalog: could not register its channel", e);
        }
        try {
            if (properties != null) ToolMenu.configure(properties);
            ToolMenu.registerChannel();
        } catch (Throwable e) {
            logger.log(Level.WARNING, "ToolMenu: could not start", e);
        }
    }

    // The E-key default action overrides (defaultAction.*) are read later; see DefaultActionCatalog.
    @Override
    public void configure(Properties properties) {
        Properties copy = new Properties();
        copy.putAll(properties);
        this.config = copy;
        // Read now rather than in onServerStarted: Weather ticks from the start, and this needs nothing else.
        SunnyWeather.configure(copy);
    }

    private void handleMessage(Player player, ByteBuffer message) {
        try {
            final byte type = message.get();
            if (type != RefinedTerrainSender.MSG_SUBSCRIBE) {
                return; // unknown client message types are ignored, for forward compatibility
            }
            final int version = message.get();
            if (version < 1) {
                return;
            }
            // Speak the lower of the two versions: a newer client talking to this server gets this
            // server's version, an older client its own.
            final int effectiveVersion = Math.min(version, RefinedTerrainSender.PROTOCOL_VERSION);
            final int tierMask = message.get() & RefinedTerrainSender.ALL_TIER_BITS;
            final int content = effectiveVersion >= 2 && message.hasRemaining()
                    ? message.get() & RefinedTerrainSender.ALL_CONTENT
                    : RefinedTerrainSender.CONTENT_HEIGHTS;
            PlayerRefinementState state = this.states.get(player.getWurmId());
            if (state == null) {
                state = new PlayerRefinementState(player.getWurmId());
                this.states.put(player.getWurmId(), state);
            }
            state.setSubscription(effectiveVersion, tierMask, content);
            if ((content & RefinedTerrainSender.CONTENT_STRUCTURES) != 0) {
                this.structureStates.put(player.getWurmId(), new DistantStructureSender());
            } else {
                this.structureStates.remove(player.getWurmId());
            }
            logger.info("DistantTerrainRefinement: " + player.getName() + " subscribed (protocol " + effectiveVersion
                    + ", tiers " + tierMask + ", content " + content + ")");
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "DistantTerrainRefinement: bad message from " + player.getName(), e);
        }
    }

    @Override
    public void onPlayerLogin(Player player) {
        // Anything left over belongs to a previous session; ModComm's handshake re-creates the
        // state (onPlayerConnected) once this login activates the channel.
        this.states.remove(player.getWurmId());
        this.structureStates.remove(player.getWurmId());
        CaveVision.forget(player.getWurmId());
        InstallNotice.loggedIn(player);
    }

    @Override
    public void onPlayerLogout(Player player) {
        InstallNotice.forget(player.getWurmId());
        MountColors.forget(player.getWurmId());
        CreatureColors.forget(player.getWurmId());
        DefaultActionCatalog.forget(player.getWurmId());
        ToolMenu.forget(player.getWurmId());
        CaveVision.forget(player.getWurmId());
        this.states.remove(player.getWurmId());
        this.structureStates.remove(player.getWurmId());
    }

    @Override
    public void onServerPoll() {
        try {
            MountColors.flush();
        } catch (Throwable e) {
            logger.log(Level.WARNING, "MountColors: flush failed", e);
        }
        try {
            CreatureColors.flush();
        } catch (Throwable e) {
            logger.log(Level.WARNING, "CreatureColors: flush failed", e);
        }
        try {
            DefaultActionCatalog.poll();
        } catch (Throwable e) {
            logger.log(Level.WARNING, "DefaultActionCatalog: poll failed", e);
        }
        InstallNotice.poll();
        try {
            CaveVision.poll();
        } catch (Throwable e) {
            logger.log(Level.WARNING, "CaveVision: poll failed", e);
        }
        final Channel ch = this.channel;
        if (ch == null || this.states.isEmpty() || Server.surfaceMesh == null) {
            return;
        }
        final MeshIO mesh = Server.surfaceMesh;
        if (mesh.data == null || mesh.data.length < (1 << (2 * Constants.meshSize))) {
            return;
        }
        if (!this.structureStates.isEmpty()) {
            if (this.structureIndex == null) {
                this.structureIndex = new DistantStructureIndex();
            }
            this.structureIndex.rebuildIfDue(System.currentTimeMillis());
        }
        for (PlayerRefinementState state : this.states.values()) {
            final DistantStructureSender structures = this.structureStates.get(state.wurmId);
            if (!state.isSubscribed() && structures == null) {
                continue;
            }
            try {
                this.poll(ch, state, structures);
            } catch (RuntimeException e) {
                // One player's trouble must never break the poll for everyone else.
                logger.log(Level.WARNING, "DistantTerrainRefinement: poll failed for player " + state.wurmId, e);
                this.states.remove(state.wurmId);
                this.structureStates.remove(state.wurmId);
            }
        }
    }

    private void poll(Channel ch, PlayerRefinementState state, DistantStructureSender structures) {
        final Player player = findPlayer(state.wurmId);
        if (player == null || !player.hasLink() || !ch.isActiveForPlayer(player)) {
            return;
        }
        final Communicator communicator = player.getCommunicator();
        final SocketConnection connection = communicator == null ? null : communicator.getConnection();
        if (connection == null || !VanillaVision.nearLoaded(player)) {
            return;
        }
        int budget = MAX_BYTES_PER_POLL;
        if (structures != null) {
            structures.update(this.structureIndex, player.getTileX(), player.getTileY(),
                    Math.max(1, (int) player.getTemplate().getVision()));
            while (budget > MAX_BYTES_PER_POLL / 2 && structures.hasWork() && connection.getUnflushed() < SEND_LOW_WATER_BYTES) {
                final ByteBuffer message = structures.next();
                budget -= message.remaining();
                ch.sendMessage(player, message);
            }
        }
        if (!state.isSubscribed()) {
            return;
        }
        state.update(player.getTileX(), player.getTileY(), this.liveMesh.mapSizeTiles());
        if (!state.hasWork()) {
            return;
        }
        while (budget > 0 && connection.getUnflushed() < SEND_LOW_WATER_BYTES) {
            final ByteBuffer message = state.nextMessage(this.liveMesh);
            if (message == null) {
                break;
            }
            final int bytes = message.remaining();
            final int cellSize = message.get(1);
            budget -= bytes;
            ch.sendMessage(player, message);
            final long windowBytes = state.recordSent(message, bytes);
            if (windowBytes >= 0) {
                // A player's first window of each tier is the big one; later ones are the small
                // per-move deltas, logged only at FINE so walking around doesn't flood the log.
                final Level level = state.windowsSent() <= 2 ? Level.INFO : Level.FINE;
                if (logger.isLoggable(level)) {
                    logger.log(level, "DistantTerrainRefinement: " + player.getName() + " got a " + cellSize + "x" + cellSize
                            + " window (" + (windowBytes + 1023) / 1024 + " KB since the previous one, window #" + state.windowsSent() + ")");
                }
            }
        }
    }

    private static Player findPlayer(long wurmId) {
        try {
            return com.wurmonline.server.Players.getInstance().getPlayerOrNull(wurmId);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
