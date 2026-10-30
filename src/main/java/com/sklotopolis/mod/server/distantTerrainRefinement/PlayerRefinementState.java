package com.sklotopolis.mod.server.distantTerrainRefinement;

import com.sklotopolis.mod.server.distantTerrainRefinement.RefinedTerrainSender.MapSource;
import com.sklotopolis.mod.server.distantTerrainRefinement.RefinedTerrainSender.Rect;
import com.sklotopolis.mod.server.distantTerrainRefinement.RefinedTerrainSender.Tier;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/**
 * What one subscribed player's client holds, per tier, and what is still queued for it.
 *
 * Deliberately only one window per tier is ever in flight. The client keeps each tier in a
 * 512-cell ring, and a window's cells never alias each other (a window is under 512 cells wide),
 * but cells of two different windows can, so the server must know exactly which window the client
 * holds when it computes the next delta. Holding the next window back until the current one's
 * commit has gone out makes that trivially true:
 *   - clientWindow is only replaced when its successor's MSG_WINDOW is actually handed to ModComm,
 *     after all of that window's data (same channel, same order);
 *   - a pending window that has sent NOTHING yet is simply dropped when the player has already
 *     moved on (nothing of it reached the client, so the delta base is still clientWindow);
 *   - a pending window that has started is finished first, then the next delta is computed from
 *     it - never from a half-delivered window.
 * Each resend is only the delta against the window the client already holds, so steady-state cost
 * is the newly uncovered strip, not the whole window. Freshness is "snapshot": a cell the client
 * holds is not re-sent while it stays inside the window (terrain edits show up once the player
 * has moved away and back, or relogs) - the same freshness vanilla's own distant data has.
 */
final class PlayerRefinementState {

    private static final class TierState {
        final Tier tier;
        // CONTENT_* flags this tier sends to this player; 0 = tier not in use.
        int content;
        Rect clientWindow;
        int clientCenterX;
        int clientCenterY;
        Rect pendingWindow;
        int pendingCenterX;
        int pendingCenterY;
        boolean pendingStarted;
        // Rect = a height chunk of pendingWindow; the final null entry stands for its MSG_WINDOW.
        final ArrayDeque<Rect> queue = new ArrayDeque<>();
        boolean commitQueued;

        TierState(Tier tier) {
            this.tier = tier;
        }

        void reset() {
            this.clientWindow = null;
            this.pendingWindow = null;
            this.pendingStarted = false;
            this.queue.clear();
            this.commitQueued = false;
        }

        boolean hasWork() {
            return !this.queue.isEmpty() || this.commitQueued;
        }

        private static boolean movedFrom(int tileX, int tileY, int centerX, int centerY, int threshold) {
            return Math.max(Math.abs(tileX - centerX), Math.abs(tileY - centerY)) >= threshold;
        }

        /** Decides whether a new window is due and, if so, queues its delta. */
        void update(int tileX, int tileY, int mapSizeTiles) {
            final int threshold = this.tier.resendThresholdTiles;
            if (this.pendingWindow != null) {
                if (this.pendingStarted
                        || !movedFrom(tileX, tileY, this.pendingCenterX, this.pendingCenterY, threshold)) {
                    return;
                }
                // Nothing of it went out yet and it's already stale - replace it.
                this.pendingWindow = null;
                this.queue.clear();
                this.commitQueued = false;
            }
            if (this.clientWindow != null
                    && !movedFrom(tileX, tileY, this.clientCenterX, this.clientCenterY, threshold)) {
                return;
            }
            final Rect next = RefinedTerrainSender.windowFor(this.tier, tileX, tileY, mapSizeTiles);
            if (next == null || next.equals(this.clientWindow)) {
                // Off the map, or clamped against the map edge so nothing actually changes; just
                // re-anchor so the threshold check doesn't fire again every poll.
                this.clientCenterX = tileX;
                this.clientCenterY = tileY;
                return;
            }
            for (Rect part : RefinedTerrainSender.subtract(next, this.clientWindow)) {
                this.queue.addAll(RefinedTerrainSender.chunk(part));
            }
            this.pendingWindow = next;
            this.pendingCenterX = tileX;
            this.pendingCenterY = tileY;
            this.pendingStarted = false;
            this.commitQueued = true;
        }

        /** Builds the next message for this tier, or null if it has nothing queued. */
        ByteBuffer nextMessage(MapSource source, int subscriptionVersion) {
            final Rect chunk = this.queue.poll();
            if (chunk != null) {
                this.pendingStarted = true;
                return subscriptionVersion >= 2
                        ? RefinedTerrainSender.encodeCells(this.tier, chunk, this.content, source)
                        : RefinedTerrainSender.encodeHeights(this.tier, chunk, source);
            }
            if (this.commitQueued) {
                this.commitQueued = false;
                final ByteBuffer message = RefinedTerrainSender.encodeWindow(this.tier, this.pendingWindow);
                this.clientWindow = this.pendingWindow;
                this.clientCenterX = this.pendingCenterX;
                this.clientCenterY = this.pendingCenterY;
                this.pendingWindow = null;
                this.pendingStarted = false;
                return message;
            }
            return null;
        }
    }

    final long wurmId;
    private int subscriptionVersion;
    // Send accounting, for the server log only (see DistantTerrainRefinementServerMod.poll).
    private long bytesSinceCommit;
    private int windowsSent;
    private final TierState[] tiers;

    PlayerRefinementState(long wurmId) {
        this.wurmId = wurmId;
        final Tier[] all = Tier.values();
        this.tiers = new TierState[all.length];
        for (int i = 0; i < all.length; ++i) {
            this.tiers[i] = new TierState(all[i]);
        }
    }

    /** Records a message handed to ModComm; returns the bytes since the last commit if it was one, else -1. */
    synchronized long recordSent(ByteBuffer message, int bytes) {
        this.bytesSinceCommit += bytes;
        if (message.get(0) != RefinedTerrainSender.MSG_WINDOW) {
            return -1;
        }
        ++this.windowsSent;
        final long total = this.bytesSinceCommit;
        this.bytesSinceCommit = 0;
        return total;
    }

    synchronized int windowsSent() {
        return this.windowsSent;
    }

    /** Whether any tier currently sends this player anything. */
    synchronized boolean isSubscribed() {
        for (TierState t : this.tiers) {
            if (t.content != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Applies a (re)subscription. A tier whose content changes - added, dropped, or switched
     * between heights/types - forgets everything and starts from scratch, since the client's slots
     * for it hold the old content.
     */
    synchronized void setSubscription(int version, int tierMask, int requestedContent) {
        this.subscriptionVersion = version;
        for (TierState t : this.tiers) {
            final int content = (tierMask & t.tier.bit) != 0 ? t.tier.contentFor(version, requestedContent) : 0;
            if (content != t.content) {
                t.reset();
                t.content = content;
            }
        }
    }

    synchronized void update(int tileX, int tileY, int mapSizeTiles) {
        for (TierState t : this.tiers) {
            if (t.content != 0) {
                t.update(tileX, tileY, mapSizeTiles);
            }
        }
    }

    synchronized boolean hasWork() {
        for (TierState t : this.tiers) {
            if (t.content != 0 && t.hasWork()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Next message to send, finer tier first (it covers the closest, most visible terrain). The
     * caller must actually send it: a returned MSG_WINDOW has already been recorded as the client's
     * window.
     */
    synchronized ByteBuffer nextMessage(MapSource source) {
        for (TierState t : this.tiers) {
            if (t.content != 0) {
                final ByteBuffer message = t.nextMessage(source, this.subscriptionVersion);
                if (message != null) {
                    return message;
                }
            }
        }
        return null;
    }
}
