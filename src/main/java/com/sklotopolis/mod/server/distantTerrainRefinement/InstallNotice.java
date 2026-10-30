package com.sklotopolis.mod.server.distantTerrainRefinement;

import com.wurmonline.server.Players;
import com.wurmonline.server.players.Player;
import com.wurmonline.server.questions.InstallNoticeQuestion;
import org.gotti.wurmunlimited.modcomm.Channel;
import org.gotti.wurmunlimited.modcomm.IChannelListener;
import org.gotti.wurmunlimited.modcomm.ModComm;

import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tells players without the SkloGraphics client mod to install it (InstallNoticeQuestion window).
 *
 * The server can't see which client mods a player has, only which ModComm channels are active for
 * them. SkloGraphics always registers the {@link #CHANNEL_NAME} presence channel (nothing is ever
 * sent over it), so "channel not active for this player" is "not running SkloGraphics" - whether
 * that is an unmodified client or a modded one without it. ModComm's handshake runs a moment after
 * login, so a player is only judged DELAY_MILLIS after logging in.
 */
final class InstallNotice {
    private static final Logger logger = Logger.getLogger(InstallNotice.class.getName());

    // Must match the client's ModPresence.CHANNEL_NAME exactly.
    static final String CHANNEL_NAME = "SkloGraphics:installed";

    // PLACEHOLDER picture of what the player gets: replace with the real screenshot's address. The
    // client only loads plain http:// addresses here (WurmImage); other schemes show an empty frame.
    static final String IMAGE_URL = "http://patreon.game.sklotopolis.com/skloGraphics-preview.jpg";
    static final String DOWNLOAD_URL = "https://gitlab.com/Dominikk/SkloGraphics/-/releases";

    private static final long DELAY_MILLIS = 20_000L;

    private static volatile Channel channel;
    private static final Map<Long, Long> waiting = new ConcurrentHashMap<>();

    private InstallNotice() {
    }

    static void registerChannel() {
        try {
            channel = ModComm.registerChannel(CHANNEL_NAME, new IChannelListener() {
                @Override
                public void handleMessage(Player player, ByteBuffer message) {
                }
            });
            logger.info("InstallNotice: registered ModComm channel " + CHANNEL_NAME);
        } catch (RuntimeException e) {
            channel = null;
            logger.log(Level.WARNING, "InstallNotice: could not register ModComm channel - nobody is told to install the mod", e);
        }
    }

    static void loggedIn(Player player) {
        if (channel != null) waiting.put(player.getWurmId(), System.currentTimeMillis());
    }

    static void forget(long wurmId) {
        waiting.remove(wurmId);
    }

    /** Server poll. */
    static void poll() {
        final Channel ch = channel;
        if (ch == null || waiting.isEmpty()) return;
        final long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<Long, Long>> it = waiting.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<Long, Long> entry = it.next();
            if (now - entry.getValue() < DELAY_MILLIS) continue;
            it.remove();
            try {
                final Player player = Players.getInstance().getPlayerOrNull(entry.getKey());
                if (player == null || !player.hasLink() || ch.isActiveForPlayer(player)) continue;
                new InstallNoticeQuestion(player, IMAGE_URL, DOWNLOAD_URL).sendQuestion();
                logger.info("InstallNotice: told " + player.getName() + " to install SkloGraphics");
            } catch (Throwable t) {
                logger.log(Level.WARNING, "InstallNotice: could not show the window", t);
            }
        }
    }
}
