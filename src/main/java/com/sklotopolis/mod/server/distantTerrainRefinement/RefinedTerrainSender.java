package com.sklotopolis.mod.server.distantTerrainRefinement;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Wire format, tier geometry and message building for the distant-terrain refinement channel.
 * Everything here is a pure function of its arguments (the live map is reached only through a
 * MapSource), so it has no server state of its own.
 *
 * Wire format (big-endian, after ModComm's own channel header). Must match the client's
 * src/main/java/com/sklotopolis/mod/client/graphics/distant/DistantTerrainRefinement.java.
 *   client -> server  MSG_SUBSCRIBE (v1): byte type=1, byte version=1, byte tierMask
 *                     (bit 0 = 2x2, bit 1 = 8x8) - heights only
 *   client -> server  MSG_SUBSCRIBE (v2): byte type=1, byte version=2, byte tierMask
 *                     (bit 2 = 16x16 added), byte contentMask (CONTENT_HEIGHTS | CONTENT_TYPES |
 *                     CONTENT_TEXTURES)
 *   server -> client  MSG_HEIGHTS (v1 clients): byte type=1, byte cellSizeTiles, int xStartCell,
 *                     int yStartCell, short width, short height, width*height shorts
 *   server -> client  MSG_CELLS (v2 clients): byte type=3, byte cellSizeTiles, byte contentFlags,
 *                     int xStartCell, int yStartCell, short width, short height, then
 *                     width*height shorts if CONTENT_HEIGHTS, then width*height bytes if CONTENT_TYPES,
 *                     then width*height bytes if CONTENT_TEXTURES
 *   server -> client  MSG_WINDOW:  byte type=2, byte cellSizeTiles, int x0, int y0, int x1, int y1
 *                     (cells, inclusive) - commits the window the preceding data belongs to
 * All per-cell arrays are column-major (index x * height + y). Heights are raw Tiles.decodeHeight()
 * units (tenths), the same vanilla's opcode 103 sends.
 *
 * Cell (cx, cy) of a tier holds:
 *  - height: of tile (cx * cellSize, cy * cellSize) - the cell's corner, the same corner-sample
 *    convention vanilla uses for its own 16x16 distant data;
 *  - type: one tile type for the tiles cx*cellSize .. cx*cellSize+cellSize-1 (same for y), picked
 *    by vegetationType() below - NOT vanilla's plain majority vote, which is what made forests with
 *    gaps between their trees vanish from the distant tree rendering;
 *  - texture: one tile type for the same tiles, picked by textureType() below - the most common
 *    ground TEXTURE (plurality), ties going to the tile with the lowest coordinates. This is what
 *    the client paints the distant terrain with.
 */
final class RefinedTerrainSender {
    static final byte MSG_HEIGHTS = 1;
    static final byte MSG_WINDOW = 2;
    static final byte MSG_CELLS = 3;
    static final byte MSG_SUBSCRIBE = 1;
    // Highest subscription version this server understands. v1 clients still get heights exactly as
    // before (MSG_HEIGHTS, 2x2 and 8x8 only); v2 clients get no tree detail (CONTENT_TREES).
    static final byte PROTOCOL_VERSION = 3;
    static final int CONTENT_HEIGHTS = 1;
    static final int CONTENT_TYPES = 2;
    static final int CONTENT_TEXTURES = 4;
    static final int CONTENT_STRUCTURES = 8;
    // v3: one tree-detail byte per cell (see treeDetail()), so the client places the real trees.
    static final int CONTENT_TREES = 16;
    static final int ALL_CONTENT = CONTENT_HEIGHTS | CONTENT_TYPES | CONTENT_TEXTURES | CONTENT_STRUCTURES
            | CONTENT_TREES;

    private static final int CELLS_HEADER_BYTES = 1 + 1 + 1 + 4 + 4 + 2 + 2;
    private static final int HEIGHTS_HEADER_BYTES = 1 + 1 + 4 + 4 + 2 + 2;
    private static final int WINDOW_BYTES = 1 + 1 + 4 * 4;
    // Cells per data message: 8192 cells = at most 40 KB of payload (heights + types + textures + tree
    // detail). ModComm's
    // single message tops out at ~65 KB (SocketConnection's 65534-byte write buffer), and smaller
    // messages interleave better with the game's own traffic under the send pacing.
    static final int MAX_CELLS_PER_MESSAGE = 8192;

    /**
     * One resolution tier: 2x2 out to about 1000 tiles, 8x8 out to about 2000, and a types-only
     * 16x16 tier out to vanilla's own distant range (heights beyond 8x8 are vanilla's own 16x16
     * data, which needs no replacing - only its majority-vote tree placement does).
     *
     * Radius is the window's half-width in tiles. The player may drift up to the resend threshold
     * from the window's centre before a new window goes out, and the outer blend margin is where
     * the client fades a height tier into the next coarser one, so the radius a height tier covers
     * at FULL detail around the player is at least
     *     radius - resend threshold - one cell (centre rounding) - blend margin
     *   2x2: 1020 - 32 - 2 - 48 = 938 tiles (typically ~970)
     *   8x8: 2040 - 96 - 8 - 64 = 1872 tiles (typically ~1920)
     * The blend margin must be at least the client's for the same tier (RefinedTerrainTier
     * blendMarginTiles in the client mod). A window is 2 * radius / cellSize + 1 cells a side and
     * must fit the client's ring for that tier (1024 cells for 2x2, 512 otherwise) - checked below,
     * so a bad edit fails at server start instead of silently corrupting clients' terrain.
     *
     * Vegetation threshold: how many of a cell's tiles must be trees/bushes for the cell to be
     * sent as a tree type (see vegetationType()). The client's distant tree renderer turns a tree
     * cell into a forest of roughly 25% density (RandomTreeDecorator: 75% of a tree cell's tiles
     * become grass), so a cell should count as forest once its real density is closer to 25% than
     * to 0% - i.e. from 12.5% up. That break-even, rounded up to whole tiles:
     *   2x2:   1 of 4 tiles - any tree at all, as the user asked; one rendered tree per cell on average
     *   8x8:   9 of 64 (14%)
     *   16x16: 33 of 256 (13%)
     * Vanilla's majority vote is effectively 50%, which drops every forest whose trees stand with
     * gaps between them (Wurm trees usually do) - the "many distant cells show no trees" problem.
     *
     * Bandwidth per subscribed player, heights + tree types + textures: a first full window is
     * ~4.2 MB (2x2, 1021 x 1021 cells x 4 bytes) + ~1.0 MB (8x8) + ~0.2 MB (16x16, types and
     * textures), paced; after that only the newly uncovered strips (~65 KB per 32 tiles of travel for
     * 2x2, ~25 KB per 96 tiles for 8x8, ~10 KB per 256 tiles for 16x16, per axis moved).
     */
    enum Tier {
        TIER_2X2(0, 2, 1020, 32, 48, 1024, true, 1),
        // v3, tree detail only: every tile's own type and data byte (species, growth stage, centred
        // flag) out to the client's whole distant-tree range, so its billboards are exactly the trees
        // vanilla's own billboard renderer would draw. Declared after 2x2 so heights still go first.
        TIER_1X1(3, 1, 500, 32, 0, 1024, false, 1),
        TIER_8X8(1, 8, 2040, 96, 64, 512, true, 9),
        TIER_16X16(2, 16, 2400, 256, 0, 512, false, 33);

        final int bit;
        final int cellSizeTiles;
        final int radiusTiles;
        final int resendThresholdTiles;
        final int blendMarginTiles;
        final int clientRingCells;
        final boolean carriesHeights;
        final int vegetationThreshold;

        Tier(int index, int cellSizeTiles, int radiusTiles, int resendThresholdTiles, int blendMarginTiles,
             int clientRingCells, boolean carriesHeights, int vegetationThreshold) {
            this.bit = 1 << index;
            this.cellSizeTiles = cellSizeTiles;
            this.radiusTiles = radiusTiles;
            this.resendThresholdTiles = resendThresholdTiles;
            this.blendMarginTiles = blendMarginTiles;
            this.clientRingCells = clientRingCells;
            this.carriesHeights = carriesHeights;
            this.vegetationThreshold = vegetationThreshold;
            final int side = 2 * (radiusTiles / cellSizeTiles) + 1;
            if (side > clientRingCells) {
                throw new IllegalStateException(this.name() + " window is " + side + " cells wide, client ring holds "
                        + clientRingCells);
            }
            if (vegetationThreshold < 1 || vegetationThreshold > cellSizeTiles * cellSizeTiles) {
                throw new IllegalStateException(this.name() + " vegetation threshold out of range");
            }
        }

        /**
         * What this tier sends to a subscriber (CONTENT_* flags), or 0 if nothing - v1 clients only
         * ever get heights, and the 16x16 tier has no heights to give.
         */
        int contentFor(int subscriptionVersion, int requestedContent) {
            if (this.cellSizeTiles == 1) {
                // Exact tree tier: tile types + tile data, and only for clients that ask for trees.
                return subscriptionVersion >= 3 && (requestedContent & CONTENT_TREES) != 0
                        ? CONTENT_TYPES | CONTENT_TREES : 0;
            }
            final int requested = subscriptionVersion >= 2 ? requestedContent : CONTENT_HEIGHTS;
            int content = 0;
            if ((requested & CONTENT_HEIGHTS) != 0 && this.carriesHeights) {
                content |= CONTENT_HEIGHTS;
            }
            if ((requested & CONTENT_TYPES) != 0 && subscriptionVersion >= 2) {
                content |= CONTENT_TYPES;
            }
            if ((requested & CONTENT_TEXTURES) != 0 && subscriptionVersion >= 2) {
                content |= CONTENT_TEXTURES;
            }
            if ((requested & CONTENT_TREES) != 0 && subscriptionVersion >= 3) {
                content |= CONTENT_TREES;
            }
            return content;
        }
    }

    static final int ALL_TIER_BITS = Tier.TIER_2X2.bit | Tier.TIER_1X1.bit | Tier.TIER_8X8.bit | Tier.TIER_16X16.bit;

    /** Where map data comes from - the live surface mesh in the mod, a synthetic map in tests. */
    interface MapSource {
        /** Map size in tiles (a power of two). */
        int mapSizeTiles();

        /** Raw height (Tiles.decodeHeight units) of a tile inside the map. */
        short height(int tileX, int tileY);

        /** Raw tile type id (Tiles.decodeType) of a tile inside the map. */
        byte type(int tileX, int tileY);

        /** Whether a tile type is a tree or bush. */
        boolean isVegetation(byte type);

        /** Growth stage (FoliageAge, 0..15) of the tree or bush on a tile inside the map. */
        int vegetationAge(int tileX, int tileY);

        /** Raw tile data byte (Tiles.decodeData) of a tile inside the map. */
        byte tileData(int tileX, int tileY);

        /**
         * Which ground texture a tile type is drawn with, as a small key (0..1023): tile types
         * sharing a texture share a key. The texture majority counts textures, not tile ids - an
         * oak and a birch tile look the same from a kilometre away.
         */
        int textureKey(byte type);
    }

    /** Inclusive cell rectangle. */
    static final class Rect {
        final int x0;
        final int y0;
        final int x1;
        final int y1;

        Rect(int x0, int y0, int x1, int y1) {
            this.x0 = x0;
            this.y0 = y0;
            this.x1 = x1;
            this.y1 = y1;
        }

        int width() {
            return this.x1 - this.x0 + 1;
        }

        int height() {
            return this.y1 - this.y0 + 1;
        }

        boolean overlaps(Rect o) {
            return this.x0 <= o.x1 && o.x0 <= this.x1 && this.y0 <= o.y1 && o.y0 <= this.y1;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Rect)) {
                return false;
            }
            final Rect r = (Rect) o;
            return r.x0 == this.x0 && r.y0 == this.y0 && r.x1 == this.x1 && r.y1 == this.y1;
        }

        @Override
        public int hashCode() {
            return ((this.x0 * 31 + this.y0) * 31 + this.x1) * 31 + this.y1;
        }

        @Override
        public String toString() {
            return this.x0 + "," + this.y0 + ".." + this.x1 + "," + this.y1;
        }
    }

    private RefinedTerrainSender() {}

    /**
     * The window a tier wants for a player standing on (tileX, tileY): radiusTiles around the
     * player's cell, clamped to the map. The last cell index is mapSize / cellSize, whose corner is
     * the map's far edge (its height is read from the last real tile), so coverage reaches the edge.
     * Null if the player is off the map.
     */
    static Rect windowFor(Tier tier, int tileX, int tileY, int mapSizeTiles) {
        if (tileX < 0 || tileY < 0 || tileX >= mapSizeTiles || tileY >= mapSizeTiles) {
            return null;
        }
        final int c = tier.cellSizeTiles;
        final int half = tier.radiusTiles / c;
        final int maxCell = mapSizeTiles / c;
        final int cx = tileX / c;
        final int cy = tileY / c;
        return new Rect(Math.max(0, cx - half), Math.max(0, cy - half),
                Math.min(maxCell, cx + half), Math.min(maxCell, cy + half));
    }

    /** Cells of a that are not in b, as up to four disjoint rectangles. */
    static List<Rect> subtract(Rect a, Rect b) {
        final List<Rect> out = new ArrayList<>(4);
        if (b == null || !a.overlaps(b)) {
            out.add(a);
            return out;
        }
        if (a.y0 < b.y0) {
            out.add(new Rect(a.x0, a.y0, a.x1, b.y0 - 1));
        }
        if (a.y1 > b.y1) {
            out.add(new Rect(a.x0, b.y1 + 1, a.x1, a.y1));
        }
        final int midY0 = Math.max(a.y0, b.y0);
        final int midY1 = Math.min(a.y1, b.y1);
        if (a.x0 < b.x0) {
            out.add(new Rect(a.x0, midY0, b.x0 - 1, midY1));
        }
        if (a.x1 > b.x1) {
            out.add(new Rect(b.x1 + 1, midY0, a.x1, midY1));
        }
        return out;
    }

    /**
     * Splits a rectangle into column bands of at most MAX_CELLS_PER_MESSAGE cells each - column
     * bands because the payload is column-major, so every chunk is itself a valid rectangle.
     */
    static List<Rect> chunk(Rect r) {
        final List<Rect> out = new ArrayList<>();
        final int columnsPerChunk = Math.max(1, MAX_CELLS_PER_MESSAGE / r.height());
        for (int x = r.x0; x <= r.x1; x += columnsPerChunk) {
            out.add(new Rect(x, r.y0, Math.min(r.x1, x + columnsPerChunk - 1), r.y1));
        }
        return out;
    }

    /**
     * The tile type a cell is sent as. If at least tier.vegetationThreshold of its tiles are trees or
     * bushes, the most common tree/bush type among them (keeps the species, mycelium/enchanted
     * variants included); otherwise the most common of its other tile types. Tiles past the map edge
     * (only in the last, corner-only cell) read as the nearest edge tile. counts is scratch space of
     * 256 ints, cleared here.
     */
    static byte vegetationType(Tier tier, int cellX, int cellY, MapSource source, int[] counts) {
        final int c = tier.cellSizeTiles;
        final int last = source.mapSizeTiles() - 1;
        java.util.Arrays.fill(counts, 0);
        int vegetation = 0;
        for (int x = cellX * c; x < cellX * c + c; ++x) {
            final int tileX = Math.min(x, last);
            for (int y = cellY * c; y < cellY * c + c; ++y) {
                final byte type = source.type(tileX, Math.min(y, last));
                counts[type & 0xFF]++;
                if (source.isVegetation(type)) {
                    ++vegetation;
                }
            }
        }
        final boolean forest = vegetation >= tier.vegetationThreshold;
        int best = -1;
        for (int t = 0; t < 256; ++t) {
            if (counts[t] == 0 || source.isVegetation((byte) t) != forest) {
                continue;
            }
            if (best < 0 || counts[t] > counts[best]) {
                best = t;
            }
        }
        return (byte) best; // best >= 0: a forest cell has vegetation tiles, any other cell non-vegetation ones
    }

    /**
     * The tree-detail byte of a cell (CONTENT_TREES, v3). High nibble: the average growth stage
     * (0..15) of its trees and bushes, rounded. Low nibble:
     *  - 2x2 tier: which of the cell's four tiles hold a tree or bush, bit (dx + 2 * dy) for the tile
     *    at (cellX * 2 + dx, cellY * 2 + dy) - the client places exactly those;
     *  - coarser tiers: the share of the cell's tiles holding one, in fifteenths (rounded, at least
     *    1 if any does) - the client fills the cell to that density instead of a fixed guess.
     * 0 for a cell without vegetation. Tiles past the map edge read as the nearest edge tile.
     * The 1x1 tier sends the tile's raw data byte instead (its type goes with CONTENT_TYPES), which
     * holds everything vanilla's own tree placement reads.
     */
    static byte treeDetail(Tier tier, int cellX, int cellY, MapSource source) {
        final int c = tier.cellSizeTiles;
        final int last = source.mapSizeTiles() - 1;
        if (c == 1) {
            return source.tileData(Math.min(cellX, last), Math.min(cellY, last));
        }
        int count = 0;
        int ageSum = 0;
        int mask = 0;
        for (int dx = 0; dx < c; ++dx) {
            final int tileX = Math.min(cellX * c + dx, last);
            for (int dy = 0; dy < c; ++dy) {
                final int tileY = Math.min(cellY * c + dy, last);
                if (!source.isVegetation(source.type(tileX, tileY))) {
                    continue;
                }
                ++count;
                ageSum += source.vegetationAge(tileX, tileY);
                if (c == 2) {
                    mask |= 1 << (dx + 2 * dy);
                }
            }
        }
        if (count == 0) {
            return 0;
        }
        final int age = Math.min(15, (ageSum + count / 2) / count);
        final int low = c == 2 ? mask : Math.max(1, Math.min(15, (count * 15 + c * c / 2) / (c * c)));
        return (byte) (age << 4 | low);
    }

    /** Reusable counting space for textureType(), one per message being built. */
    static final class TextureScratch {
        final int[] counts = new int[1024];
        final int[] firstOrder = new int[1024];
        final byte[] firstType = new byte[1024];
        final int[] touched = new int[256]; // at most one entry per tile of a 16x16 cell
    }

    /**
     * The tile type a cell is PAINTED as: its most common ground texture (plurality - with 4 tiles,
     * 2 sand + 1 grass + 1 dirt is sand). If several textures tie for most common - including "no
     * majority at all", e.g. four different textures in a 2x2 - the tie goes to the texture of the
     * tile with the lowest coordinates, scanning x first, then y (the cell's corner tile first). The
     * type sent is the first such tile's own type, so e.g. its road/cobble variant is kept.
     * Tiles past the map edge (only in the last, corner-only cell) read as the nearest edge tile.
     */
    static byte textureType(Tier tier, int cellX, int cellY, MapSource source, TextureScratch s) {
        final int c = tier.cellSizeTiles;
        final int last = source.mapSizeTiles() - 1;
        int touchedCount = 0;
        int order = 0;
        for (int x = cellX * c; x < cellX * c + c; ++x) {
            final int tileX = Math.min(x, last);
            for (int y = cellY * c; y < cellY * c + c; ++y) {
                final byte type = source.type(tileX, Math.min(y, last));
                final int key = source.textureKey(type);
                if (s.counts[key] == 0) {
                    s.firstOrder[key] = order;
                    s.firstType[key] = type;
                    s.touched[touchedCount++] = key;
                }
                s.counts[key]++;
                ++order;
            }
        }
        int best = s.touched[0];
        for (int i = 1; i < touchedCount; ++i) {
            final int key = s.touched[i];
            if (s.counts[key] > s.counts[best]
                    || (s.counts[key] == s.counts[best] && s.firstOrder[key] < s.firstOrder[best])) {
                best = key;
            }
        }
        final byte result = s.firstType[best];
        for (int i = 0; i < touchedCount; ++i) {
            s.counts[s.touched[i]] = 0;
        }
        return result;
    }

    /**
     * v2/v3 data message: for a rectangle of cells, in this order and each only if in content -
     * heights (shorts), tree types, texture types, tree detail (bytes), all column-major.
     */
    static ByteBuffer encodeCells(Tier tier, Rect r, int content, MapSource source) {
        final int w = r.width();
        final int h = r.height();
        final int c = tier.cellSizeTiles;
        final int last = source.mapSizeTiles() - 1;
        final boolean heights = (content & CONTENT_HEIGHTS) != 0;
        final boolean types = (content & CONTENT_TYPES) != 0;
        final boolean textures = (content & CONTENT_TEXTURES) != 0;
        final boolean trees = (content & CONTENT_TREES) != 0;
        final ByteBuffer bb = ByteBuffer.allocate(CELLS_HEADER_BYTES
                + w * h * ((heights ? 2 : 0) + (types ? 1 : 0) + (textures ? 1 : 0) + (trees ? 1 : 0)));
        bb.put(MSG_CELLS);
        bb.put((byte) c);
        bb.put((byte) content);
        bb.putInt(r.x0);
        bb.putInt(r.y0);
        bb.putShort((short) w);
        bb.putShort((short) h);
        if (heights) {
            for (int x = r.x0; x <= r.x1; ++x) {
                final int tileX = Math.min(x * c, last);
                for (int y = r.y0; y <= r.y1; ++y) {
                    bb.putShort(source.height(tileX, Math.min(y * c, last)));
                }
            }
        }
        if (types) {
            final int[] counts = new int[256];
            for (int x = r.x0; x <= r.x1; ++x) {
                for (int y = r.y0; y <= r.y1; ++y) {
                    bb.put(vegetationType(tier, x, y, source, counts));
                }
            }
        }
        if (textures) {
            final TextureScratch scratch = new TextureScratch();
            for (int x = r.x0; x <= r.x1; ++x) {
                for (int y = r.y0; y <= r.y1; ++y) {
                    bb.put(textureType(tier, x, y, source, scratch));
                }
            }
        }
        if (trees) {
            for (int x = r.x0; x <= r.x1; ++x) {
                for (int y = r.y0; y <= r.y1; ++y) {
                    bb.put(treeDetail(tier, x, y, source));
                }
            }
        }
        bb.flip();
        return bb;
    }

    /** v1 data message: heights only. */
    static ByteBuffer encodeHeights(Tier tier, Rect r, MapSource source) {
        final int w = r.width();
        final int h = r.height();
        final int c = tier.cellSizeTiles;
        final int last = source.mapSizeTiles() - 1;
        final ByteBuffer bb = ByteBuffer.allocate(HEIGHTS_HEADER_BYTES + w * h * 2);
        bb.put(MSG_HEIGHTS);
        bb.put((byte) c);
        bb.putInt(r.x0);
        bb.putInt(r.y0);
        bb.putShort((short) w);
        bb.putShort((short) h);
        for (int x = r.x0; x <= r.x1; ++x) {
            final int tileX = Math.min(x * c, last);
            for (int y = r.y0; y <= r.y1; ++y) {
                bb.putShort(source.height(tileX, Math.min(y * c, last)));
            }
        }
        bb.flip();
        return bb;
    }

    static ByteBuffer encodeWindow(Tier tier, Rect r) {
        final ByteBuffer bb = ByteBuffer.allocate(WINDOW_BYTES);
        bb.put(MSG_WINDOW);
        bb.put((byte) tier.cellSizeTiles);
        bb.putInt(r.x0);
        bb.putInt(r.y0);
        bb.putInt(r.x1);
        bb.putInt(r.y1);
        bb.flip();
        return bb;
    }
}
