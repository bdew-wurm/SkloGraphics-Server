package com.sklotopolis.mod.server.distantTerrainRefinement;

import com.wurmonline.server.items.Item;
import com.sklotopolis.mod.server.distantTerrainRefinement.DistantStructureIndex.Sector;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DistantStructureSender {
    static final byte MSG_STRUCT_SECTOR = 4;
    static final byte MSG_STRUCT_DROP = 5;
    static final byte MSG_STRUCT_CONFIG = 6;

    static final int RADIUS_TILES = 250;
    static final int MAX_ELEMENTS = 60000;
    private static final int MAX_ELEMENTS_PER_MESSAGE = 3000;
    private static final int RECHECK_MOVE_TILES = 16;
    static final byte MSG_ITEMS = 7;
    static final int MAX_ITEMS = 48;
    private static final long ITEM_REFRESH_MILLIS = 3000L;

    private final Map<Long, Integer> sent = new HashMap<>();
    private final ArrayDeque<ByteBuffer> queue = new ArrayDeque<>();
    private boolean configSent;
    private int lastTileX = Integer.MIN_VALUE;
    private int lastTileY = Integer.MIN_VALUE;
    private int lastGeneration = -1;
    private long lastItemUpdate;
    private int sentItemsHash = Integer.MIN_VALUE;

    private void updateItems(DistantStructureIndex index, int tileX, int tileY) {
        final Item[] all = index.bigItems();
        final List<Item> chosen = new ArrayList<>();
        final List<Float> scores = new ArrayList<>();
        final float radius = RADIUS_TILES * 4.0f;
        final float px = tileX * 4.0f + 2.0f;
        final float py = tileY * 4.0f + 2.0f;
        for (Item item : all) {
            try {
                if (!DistantStructureIndex.isStandingOnGround(item)) continue;
                final float dx = item.getPosX() - px;
                final float dy = item.getPosY() - py;
                final float d = (float) Math.sqrt(dx * dx + dy * dy);
                if (d > radius) continue;
                final float size = item.getTemplate().getSizeZ() * Math.max(0.1f, item.getSizeMod())
                        * (DistantStructureIndex.isVehicleModel(item.getTemplate().getModelName()) ? 0.25f : 1.0f);
                chosen.add(item);
                scores.add(size / Math.max(d, 40.0f));
            } catch (RuntimeException ignored) {
            }
        }
        final Integer[] order = new Integer[chosen.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Float.compare(scores.get(b), scores.get(a)));
        final int n = Math.min(order.length, MAX_ITEMS);
        final ByteBuffer bb = ByteBuffer.allocate(3 + n * 128);
        bb.put(MSG_ITEMS);
        bb.putShort((short) 0);
        int written = 0;
        int hash = 17;
        for (int i = 0; i < n; i++) {
            final Item item = chosen.get(order[i]);
            final byte[] model;
            try {
                model = item.getModelName().getBytes("UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                continue;
            }
            if (model.length > 96) continue;
            float z = item.getPosZ();
            if (item.isFloating() && z < 0.0f) z = 0.0f;
            final float x = item.getPosX();
            final float y = item.getPosY();
            final float rot = item.getRotation();
            final float scale = item.getSizeMod();
            bb.putLong(item.getWurmId());
            bb.putFloat(x).putFloat(y).putFloat(z).putFloat(rot).putFloat(scale);
            bb.putShort((short) Math.min(Short.MAX_VALUE, item.getTemplate().getSizeZ()));
            bb.put((byte) model.length);
            bb.put(model);
            written++;
            hash = hash * 31 + Long.hashCode(item.getWurmId());
            hash = hash * 31 + Math.round(x * 4.0f);
            hash = hash * 31 + Math.round(y * 4.0f);
            hash = hash * 31 + Math.round(z * 4.0f);
            hash = hash * 31 + Math.round(rot);
            hash = hash * 31 + Math.round(scale * 100.0f);
            hash = hash * 31 + java.util.Arrays.hashCode(model);
        }
        if (hash == this.sentItemsHash) return;
        this.sentItemsHash = hash;
        bb.putShort(1, (short) written);
        bb.flip();
        this.queue.add(bb);
    }

    boolean hasWork() {
        return !this.queue.isEmpty();
    }

    ByteBuffer next() {
        return this.queue.poll();
    }

    void update(DistantStructureIndex index, int tileX, int tileY, int visionTiles) {
        if (!this.configSent) {
            final ByteBuffer bb = ByteBuffer.allocate(1 + 2 + 2 + 2 + 4);
            bb.put(MSG_STRUCT_CONFIG);
            bb.putShort((short) visionTiles).putShort((short) DistantStructureIndex.SECTOR_TILES)
                    .putShort((short) RADIUS_TILES).putInt(MAX_ELEMENTS);
            bb.flip();
            this.queue.add(bb);
            this.configSent = true;
        }
        final long now = System.currentTimeMillis();
        if (now - this.lastItemUpdate >= ITEM_REFRESH_MILLIS) {
            this.lastItemUpdate = now;
            updateItems(index, tileX, tileY);
        }
        final boolean moved = Math.max(Math.abs(tileX - this.lastTileX), Math.abs(tileY - this.lastTileY)) >= RECHECK_MOVE_TILES;
        final int generation = index.generation();
        if (!moved && generation == this.lastGeneration) {
            return;
        }
        this.lastTileX = tileX;
        this.lastTileY = tileY;
        this.lastGeneration = generation;

        final int s = DistantStructureIndex.SECTOR_TILES;
        final int sx0 = Math.floorDiv(tileX - RADIUS_TILES, s);
        final int sx1 = Math.floorDiv(tileX + RADIUS_TILES, s);
        final int sy0 = Math.floorDiv(tileY - RADIUS_TILES, s);
        final int sy1 = Math.floorDiv(tileY + RADIUS_TILES, s);
        final List<long[]> candidates = new ArrayList<>();
        for (int sy = sy0; sy <= sy1; sy++) {
            for (int sx = sx0; sx <= sx1; sx++) {
                final long nx = Math.max(Math.max((long) sx * s - tileX, tileX - ((long) sx * s + s - 1)), 0L);
                final long ny = Math.max(Math.max((long) sy * s - tileY, tileY - ((long) sy * s + s - 1)), 0L);
                if (nx * nx + ny * ny > (long) RADIUS_TILES * RADIUS_TILES) continue;
                candidates.add(new long[]{nx * nx + ny * ny, sx, sy});
            }
        }
        Collections.sort(candidates, (a, b) -> Long.compare(a[0], b[0]));
        final Map<Long, Sector> desired = new LinkedHashMap<>();
        int budget = MAX_ELEMENTS;
        for (long[] c : candidates) {
            final Sector sector = index.get((int) c[1], (int) c[2]);
            if (sector == null || sector.count > budget) continue;
            budget -= sector.count;
            desired.put(DistantStructureIndex.key(sector.sectorX, sector.sectorY), sector);
        }
        for (Iterator<Map.Entry<Long, Integer>> it = this.sent.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<Long, Integer> e = it.next();
            if (desired.containsKey(e.getKey())) continue;
            final long k = e.getKey();
            final ByteBuffer bb = ByteBuffer.allocate(5);
            bb.put(MSG_STRUCT_DROP).putShort((short) (int) (k >> 32)).putShort((short) (int) k);
            bb.flip();
            this.queue.add(bb);
            it.remove();
        }
        for (Map.Entry<Long, Sector> e : desired.entrySet()) {
            final Sector sector = e.getValue();
            final Integer held = this.sent.get(e.getKey());
            if (held != null && held == sector.hash) continue;
            final int parts = Math.max(1, (sector.count + MAX_ELEMENTS_PER_MESSAGE - 1) / MAX_ELEMENTS_PER_MESSAGE);
            for (int p = 0; p < parts; p++) {
                final int first = p * MAX_ELEMENTS_PER_MESSAGE;
                final int n = Math.min(MAX_ELEMENTS_PER_MESSAGE, sector.count - first);
                this.queue.add(DistantStructureIndex.encodeSector(sector, p, parts, first, n));
            }
            this.sent.put(e.getKey(), sector.hash);
        }
    }
}
