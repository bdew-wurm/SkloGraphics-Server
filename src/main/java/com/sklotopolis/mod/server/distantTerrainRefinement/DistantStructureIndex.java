package com.sklotopolis.mod.server.distantTerrainRefinement;

import com.wurmonline.server.Constants;
import com.wurmonline.server.Items;
import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemTemplate;
import com.wurmonline.server.structures.BridgePart;
import com.wurmonline.server.structures.Fence;
import com.wurmonline.server.structures.Floor;
import com.wurmonline.server.structures.Structure;
import com.wurmonline.server.structures.Structures;
import com.wurmonline.server.structures.Wall;
import com.wurmonline.server.zones.VolaTile;
import com.wurmonline.server.zones.Zone;
import com.wurmonline.server.zones.Zones;
import com.wurmonline.shared.constants.BridgeConstants;
import com.wurmonline.shared.constants.StructureConstants;
import com.wurmonline.shared.constants.StructureConstantsEnum;
import com.wurmonline.shared.constants.StructureMaterialEnum;
import com.wurmonline.shared.constants.StructureTypeEnum;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

final class DistantStructureIndex {
    private static final Logger logger = Logger.getLogger(DistantStructureIndex.class.getName());

    static final int SECTOR_TILES = 64;
    static final int ELEMENT_BYTES = 18;
    private static final long REBUILD_MILLIS = 60_000L;

    static final byte KIND_WALL = 0;
    static final byte KIND_FENCE = 1;
    static final byte KIND_ROOF = 2;
    static final byte KIND_BRIDGE = 3;

    static final byte STYLE_SOLID = 0;
    static final byte STYLE_WINDOW = 1;
    static final byte STYLE_NARROW_WINDOW = 2;
    static final byte STYLE_WIDE_WINDOW = 3;
    static final byte STYLE_DOOR = 4;
    static final byte STYLE_DOUBLE_DOOR = 5;
    static final byte STYLE_OPENING = 6;
    static final byte STYLE_ARCH_SIDE = 7;
    static final byte STYLE_FENCE_BOARDS = 20;
    static final byte STYLE_FENCE_PALISADE = 21;
    static final byte STYLE_FENCE_STONE = 22;
    static final byte STYLE_FENCE_BARS = 24;
    static final byte STYLE_FENCE_ROPE = 25;
    static final byte STYLE_FENCE_ROUNDPOLE = 26;
    static final byte STYLE_FENCE_HEDGE = 27;
    static final byte STYLE_FENCE_CURB = 28;
    static final byte STYLE_FENCE_FLOWERBED = 29;
    static final byte STYLE_FENCE_PARAPET = 30;
    static final byte STYLE_FENCE_WOVEN = 31;
    static final byte STYLE_FENCE_CHAIN = 32;
    static final byte STYLE_ROOF = 40;
    static final byte STYLE_BRIDGE = 50;

    static final byte MAT_WOOD = 0;
    static final byte MAT_STONE = 1;
    static final byte MAT_PLAIN_STONE = 2;
    static final byte MAT_TIMBER_FRAMED = 3;
    static final byte MAT_SLATE = 4;
    static final byte MAT_ROUNDED_STONE = 5;
    static final byte MAT_POTTERY = 6;
    static final byte MAT_SANDSTONE = 7;
    static final byte MAT_RENDERED = 8;
    static final byte MAT_MARBLE = 9;
    static final byte MAT_METAL = 10;
    static final byte MAT_LOG = 11;
    static final byte MAT_CRUDE_WOOD = 12;
    static final byte MAT_THATCH = 13;
    static final byte MAT_HEDGE = 14;
    static final byte MAT_ROPE = 15;
    static final byte MAT_COPPER = 16;
    static final byte MAT_GOLD = 17;
    static final byte MAT_SILVER = 18;

    static final class Sector {
        final int sectorX;
        final int sectorY;
        final byte[] data;
        final int count;
        final int hash;

        Sector(int sectorX, int sectorY, byte[] data, int count, int hash) {
            this.sectorX = sectorX;
            this.sectorY = sectorY;
            this.data = data;
            this.count = count;
            this.hash = hash;
        }
    }

    private static final class Builder {
        ByteBuffer buffer = ByteBuffer.allocate(ELEMENT_BYTES * 64);
        int count;
        int hash;

        void add(byte kind, byte style, byte material, byte flags, int tileX, int tileY, int z0, int z1, int height, int group) {
            if (this.buffer.remaining() < ELEMENT_BYTES) {
                final ByteBuffer grown = ByteBuffer.allocate(this.buffer.capacity() * 2);
                this.buffer.flip();
                grown.put(this.buffer);
                this.buffer = grown;
            }
            this.buffer.put(kind).put(style).put(material).put(flags);
            this.buffer.putShort((short) tileX).putShort((short) tileY);
            this.buffer.putShort(clampShort(z0)).putShort(clampShort(z1)).putShort(clampShort(height));
            this.buffer.putInt(group);
            int h = kind * 31 + style;
            h = h * 31 + material;
            h = h * 31 + flags;
            h = h * 31 + tileX;
            h = h * 31 + tileY;
            h = h * 31 + z0;
            h = h * 31 + z1;
            h = h * 31 + height;
            h = h * 31 + group;
            h ^= h >>> 16;
            h *= 0x85ebca6b;
            h ^= h >>> 13;
            this.hash += h;
            this.count++;
        }
    }

    private volatile Map<Long, Sector> sectors = Collections.emptyMap();
    private volatile Item[] bigItems = new Item[0];
    private volatile int generation;
    private volatile long builtAt;
    private final java.util.concurrent.atomic.AtomicBoolean rebuilding = new java.util.concurrent.atomic.AtomicBoolean();
    private Field zoneTilesField;
    private boolean zoneTilesFailed;

    static long key(int sectorX, int sectorY) {
        return ((long) sectorX << 32) | (sectorY & 0xffffffffL);
    }

    Sector get(int sectorX, int sectorY) {
        return this.sectors.get(key(sectorX, sectorY));
    }

    int generation() {
        return this.generation;
    }

    void rebuildIfDue(long nowMillis) {
        if (this.rebuilding.get() || (this.builtAt != 0L && nowMillis - this.builtAt < REBUILD_MILLIS)) {
            return;
        }
        if (!this.rebuilding.compareAndSet(false, true)) {
            return;
        }
        this.builtAt = nowMillis;
        final Thread worker = new Thread(this::rebuildNow, "DistantStructureIndex");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    private void rebuildNow() {
        try {
            final long start = System.nanoTime();
            rebuild();
            final long millis = (System.nanoTime() - start) / 1_000_000L;
            int total = 0;
            for (Sector s : this.sectors.values()) total += s.count;
            logger.log(this.generation <= 1 ? Level.INFO : Level.FINE, "DistantStructures: indexed " + total + " elements in "
                    + this.sectors.size() + " sectors (" + millis + " ms)");
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "DistantStructures: index rebuild failed, keeping the previous one", e);
        } finally {
            this.rebuilding.set(false);
        }
    }

    private void rebuild() {
        final Map<Long, Builder> builders = new HashMap<>();
        final Structure[] structures = Structures.getAllStructures();
        for (Structure s : structures) {
            if (s == null) continue;
            try {
                addStructure(builders, s);
            } catch (RuntimeException e) {
                logger.log(Level.FINE, "DistantStructures: skipped a structure", e);
            }
        }
        final List<Item> items = new ArrayList<>();
        addFences(builders, items);
        final Map<Long, Sector> built = new HashMap<>(builders.size() * 2);
        for (Map.Entry<Long, Builder> e : builders.entrySet()) {
            final Builder b = e.getValue();
            if (b.count == 0) continue;
            final byte[] data = new byte[b.count * ELEMENT_BYTES];
            b.buffer.flip();
            b.buffer.get(data);
            final long k = e.getKey();
            built.put(k, new Sector((int) (k >> 32), (int) k, data, b.count, b.hash));
        }
        this.sectors = built;
        this.bigItems = items.toArray(new Item[0]);
        this.generation++;
    }

    private static Builder builderFor(Map<Long, Builder> builders, int tileX, int tileY) {
        final long k = key(Math.floorDiv(tileX, SECTOR_TILES), Math.floorDiv(tileY, SECTOR_TILES));
        Builder b = builders.get(k);
        if (b == null) {
            b = new Builder();
            builders.put(k, b);
        }
        return b;
    }

    private static void addStructure(Map<Long, Builder> builders, Structure s) {
        if (!s.isOnSurface()) return;
        final long wurmId = s.getWurmId();
        int group = (int) (wurmId ^ (wurmId >>> 32));
        if (group == 0) group = 1;
        final Builder b = builderFor(builders, s.getMinX(), s.getMinY());
        if (s.isTypeBridge()) {
            for (BridgePart bp : s.getBridgeParts()) {
                if (bp == null || bp.getBridgePartState() != BridgeConstants.BridgeState.COMPLETED) continue;
                final int nw = bp.getHeightOffset();
                final int se = nw + bp.getSlope();
                final byte dir = bp.getDir();
                final byte flags = (byte) (dir == 0 || dir == 4 ? 0 : 1);
                b.add(KIND_BRIDGE, STYLE_BRIDGE, bridgeMaterial(bp.getMaterial()), flags,
                        bp.getTileX(), bp.getTileY(), nw, se, 0, group);
            }
            return;
        }
        for (Wall w : s.getWalls()) {
            if (w == null || w.isWallPlan()) continue;
            final byte style = wallStyle(w.getType());
            if (style < 0) continue;
            final int sx = w.getStartX();
            final int sy = w.getStartY();
            final int ex = w.getEndX();
            final int ey = w.getEndY();
            final int offset = w.getHeight();
            final int z0 = nodeHeightDm(sx, sy) + offset;
            final int z1 = nodeHeightDm(ex, ey) + offset;
            final byte flags = (byte) (ex != sx ? 1 : 0);
            b.add(KIND_WALL, style, wallMaterial(w.getMaterial()), flags, Math.min(sx, ex), Math.min(sy, ey),
                    ex >= sx && ey >= sy ? z0 : z1, ex >= sx && ey >= sy ? z1 : z0, 30, group);
        }
        for (Floor f : s.getFloors()) {
            if (f == null || !f.isRoof() || !f.isFinished()) continue;
            final int z = nodeHeightDm(f.getTileX(), f.getTileY()) + f.getHeightOffset();
            b.add(KIND_ROOF, STYLE_ROOF, roofMaterial(f.getMaterial()), (byte) 0, f.getTileX(), f.getTileY(),
                    z, f.getFloorLevel(), 0, group);
        }
    }

    Item[] bigItems() {
        return this.bigItems;
    }

    private static final Set<Integer> BIG_ITEM_TEMPLATES = new HashSet<>(java.util.Arrays.asList(
            518, 869, 870, 907, 916, 939, 996, 540, 541, 542, 543, 732, 680, 678, 1016, 937));

    static boolean isBigItem(Item item) {
        final ItemTemplate template = item.getTemplate();
        if (template == null) return false;
        final String model = template.getModelName();
        if (model == null || model.startsWith("model.container.") || model.startsWith("model.part.")) return false;
        boolean big = BIG_ITEM_TEMPLATES.contains(template.getTemplateId()) || template.isGuardTower()
                || model.startsWith("model.structure.rift.") || isVehicleModel(model);
        if (!big) {
            final int z = template.getSizeZ();
            final int footprint = Math.max(template.getSizeX(), template.getSizeY());
            big = z >= 1000 && footprint >= 200
                    && (model.startsWith("model.structure.") || model.startsWith("model.decoration.statue."));
        }
        return big && isStandingOnGround(item);
    }

    private static final String[] VEHICLE_MODELS = {
            "model.structure.boat.", "model.structure.small.boat.", "model.structure.small.cart.",
            "model.structure.large.cart.", "model.transports.", "model.structure.small.catapult.",
            "model.structure.small.ballista.", "model.structure.small.trebuchet.", "model.structure.small.batteringram."};

    static boolean isVehicleModel(String model) {
        for (String prefix : VEHICLE_MODELS) {
            if (model.startsWith(prefix)) return true;
        }
        return false;
    }

    static boolean isStandingOnGround(Item item) {
        return item.getParentId() == -10L && item.getZoneId() != -10 && item.isOnSurface()
                && !item.isTraded() && !item.isBanked() && !item.isMailed() && Items.exists(item);
    }

    private void addFences(Map<Long, Builder> builders, List<Item> items) {
        final int max = (1 << Constants.meshSize) - 1;
        final Zone[] zones = Zones.getZonesCoveredBy(0, 0, max, max, true);
        if (zones == null) return;
        for (Zone zone : zones) {
            if (zone == null) continue;
            final Map<?, ?> tiles = zoneTiles(zone);
            if (tiles == null) continue;
            for (Object o : tiles.values()) {
                if (!(o instanceof VolaTile)) continue;
                for (Item item : ((VolaTile) o).getItems()) {
                    try {
                        if (item != null && isBigItem(item)) items.add(item);
                    } catch (RuntimeException e) {
                        logger.log(Level.FINE, "DistantStructures: skipped an item", e);
                    }
                }
                final Fence[] fences = ((VolaTile) o).getFences();
                for (Fence f : fences) {
                    if (f == null) continue;
                    try {
                        addFence(builders, f);
                    } catch (RuntimeException e) {
                        logger.log(Level.FINE, "DistantStructures: skipped a fence", e);
                    }
                }
            }
        }
    }

    private static void addFence(Map<Long, Builder> builders, Fence f) {
        if (!f.isOnSurface() || f.isTemporary() || !f.isFinished()) return;
        final StructureConstantsEnum type = f.getType();
        if (type == null) return;
        final StructureTypeEnum t = type.type;
        final byte style = fenceStyle(t);
        if (style < 0) return;
        final int heightDm = fenceHeightDm(t);
        final int sx = f.getStartX();
        final int sy = f.getStartY();
        final int ex = f.getEndX();
        final int ey = f.getEndY();
        final int offset = f.getHeightOffset();
        final int z0 = nodeHeightDm(sx, sy) + offset;
        final int z1 = nodeHeightDm(ex, ey) + offset;
        final byte flags = (byte) (ex != sx ? 1 : 0);
        final boolean forward = ex >= sx && ey >= sy;
        final Builder b = builderFor(builders, Math.min(sx, ex), Math.min(sy, ey));
        b.add(KIND_FENCE, style, fenceMaterial(t, type.material), flags, Math.min(sx, ex), Math.min(sy, ey),
                forward ? z0 : z1, forward ? z1 : z0, heightDm, 0);
    }

    private Map<?, ?> zoneTiles(Zone zone) {
        if (this.zoneTilesFailed) return null;
        try {
            if (this.zoneTilesField == null) {
                final Field f = Zone.class.getDeclaredField("tiles");
                f.setAccessible(true);
                this.zoneTilesField = f;
            }
            final Object v = this.zoneTilesField.get(zone);
            return v instanceof Map ? (Map<?, ?>) v : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            this.zoneTilesFailed = true;
            logger.log(Level.WARNING, "DistantStructures: cannot read zone tiles - fences will not be sent", e);
            return null;
        }
    }

    private static int nodeHeightDm(int x, int y) {
        return Math.round(Zones.getHeightForNode(x, y, 0) * 10.0f);
    }

    private static short clampShort(int v) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
    }

    private static byte wallStyle(StructureTypeEnum t) {
        if (t == null) return STYLE_SOLID;
        switch (t) {
            case NO_WALL:
            case RUBBLE:
            case SCAFFOLDING:
                return -1;
            case WINDOW:
            case BALCONY:
            case JETTY:
            case ORIEL:
                return STYLE_WINDOW;
            case NARROW_WINDOW:
                return STYLE_NARROW_WINDOW;
            case WIDE_WINDOW:
                return STYLE_WIDE_WINDOW;
            case DOOR:
            case CANOPY_DOOR:
                return STYLE_DOOR;
            case DOUBLE_DOOR:
                return STYLE_DOUBLE_DOOR;
            case ARCHED:
            case PORTCULLIS:
            case BARRED:
                return STYLE_OPENING;
            case ARCHED_LEFT:
            case ARCHED_RIGHT:
            case ARCHED_T:
                return STYLE_ARCH_SIDE;
            default:
                return STYLE_SOLID;
        }
    }

    private static byte wallMaterial(StructureMaterialEnum m) {
        if (m == null) return MAT_WOOD;
        switch (m) {
            case STONE: return MAT_STONE;
            case PLAIN_STONE: return MAT_PLAIN_STONE;
            case TIMBER_FRAMED: return MAT_TIMBER_FRAMED;
            case SLATE: return MAT_SLATE;
            case ROUNDED_STONE: return MAT_ROUNDED_STONE;
            case POTTERY: return MAT_POTTERY;
            case SANDSTONE: return MAT_SANDSTONE;
            case RENDERED: return MAT_RENDERED;
            case MARBLE: return MAT_MARBLE;
            case METAL:
            case IRON: return MAT_METAL;
            case LOG: return MAT_LOG;
            case CRUDE_WOOD: return MAT_CRUDE_WOOD;
            default: return MAT_WOOD;
        }
    }

    private static byte roofMaterial(StructureConstants.FloorMaterial m) {
        if (m == null) return MAT_WOOD;
        switch (m) {
            case STONE_BRICK:
            case STONE_SLAB: return MAT_PLAIN_STONE;
            case SANDSTONE_SLAB: return MAT_SANDSTONE;
            case SLATE_SLAB: return MAT_SLATE;
            case THATCH: return MAT_THATCH;
            case METAL_IRON:
            case METAL_STEEL: return MAT_METAL;
            case METAL_COPPER: return MAT_COPPER;
            case CLAY_BRICK: return MAT_POTTERY;
            case METAL_GOLD: return MAT_GOLD;
            case METAL_SILVER: return MAT_SILVER;
            case MARBLE_SLAB: return MAT_MARBLE;
            default: return MAT_WOOD;
        }
    }

    private static byte bridgeMaterial(BridgeConstants.BridgeMaterial m) {
        if (m == null) return MAT_WOOD;
        switch (m) {
            case ROPE: return MAT_ROPE;
            case BRICK: return MAT_STONE;
            case MARBLE: return MAT_MARBLE;
            case SLATE: return MAT_SLATE;
            case ROUNDED_STONE: return MAT_ROUNDED_STONE;
            case POTTERY: return MAT_POTTERY;
            case SANDSTONE: return MAT_SANDSTONE;
            case RENDERED: return MAT_RENDERED;
            default: return MAT_WOOD;
        }
    }

    private static byte fenceStyle(StructureTypeEnum t) {
        final String n = t.name();
        if (n.startsWith("FENCE_PLAN") || n.equals("MAGIC_FENCE") || n.equals("NO_WALL")) return -1;
        if (n.startsWith("HEDGE")) return STYLE_FENCE_HEDGE;
        if (n.startsWith("FENCE_IRON_BARS")) return STYLE_FENCE_BARS;
        if (n.startsWith("ROPE")) return STYLE_FENCE_ROPE;
        if (n.startsWith("GARDESGARD")) return STYLE_FENCE_ROUNDPOLE;
        switch (t) {
            case PALISADE:
            case SIEGWALL:
                return STYLE_FENCE_PALISADE;
            case FENCE_WALL:
            case FENCE_TALL:
                return STYLE_FENCE_STONE;
            case PARAPET:
                return STYLE_FENCE_PARAPET;
            case CURB:
                return STYLE_FENCE_CURB;
            case FLOWERBED:
                return STYLE_FENCE_FLOWERBED;
            case WOVEN:
                return STYLE_FENCE_WOVEN;
            case MEDIUM_CHAIN:
                return STYLE_FENCE_CHAIN;
            default:
                return STYLE_FENCE_BOARDS;
        }
    }

    private static int fenceHeightDm(StructureTypeEnum t) {
        switch (t) {
            case HEDGE_LOW: return 8;
            case HEDGE_MEDIUM: return 16;
            case HEDGE_HIGH: return 26;
            case FENCE_IRON_BARS_TALL:
            case FENCE_IRON_BARS_TALL_GATE: return 26;
            case FENCE_IRON_BARS:
            case FENCE_IRON_BARS_GATE: return 16;
            case ROPE_LOW: return 9;
            case ROPE_HIGH: return 15;
            case GARDESGARD_LOW: return 10;
            case GARDESGARD_HIGH:
            case GARDESGARD_GATE: return 16;
            case PALISADE: return 26;
            case SIEGWALL: return 30;
            case FENCE_TALL: return 30;
            case FENCE_WALL: return 15;
            case PARAPET: return 10;
            case CURB: return 3;
            case FLOWERBED: return 4;
            case MEDIUM_CHAIN: return 12;
            case WOVEN: return 12;
            case GATE: return 15;
            default: return 13;
        }
    }

    private static byte fenceMaterial(StructureTypeEnum t, StructureMaterialEnum m) {
        if (t.name().startsWith("HEDGE")) return MAT_HEDGE;
        if (t == StructureTypeEnum.FLOWERBED) return MAT_HEDGE;
        if (t.name().startsWith("ROPE")) return MAT_ROPE;
        if (t.name().startsWith("FENCE_IRON_BARS") || t == StructureTypeEnum.MEDIUM_CHAIN) return MAT_METAL;
        return wallMaterial(m);
    }

    static ByteBuffer encodeSector(Sector s, int part, int parts, int firstElement, int elementCount) {
        final ByteBuffer bb = ByteBuffer.allocate(1 + 2 + 2 + 4 + 2 + 2 + 2 + elementCount * ELEMENT_BYTES);
        bb.put(DistantStructureSender.MSG_STRUCT_SECTOR);
        bb.putShort((short) s.sectorX).putShort((short) s.sectorY);
        bb.putInt(s.hash);
        bb.putShort((short) part).putShort((short) parts).putShort((short) elementCount);
        bb.put(s.data, firstElement * ELEMENT_BYTES, elementCount * ELEMENT_BYTES);
        bb.flip();
        return bb;
    }
}
