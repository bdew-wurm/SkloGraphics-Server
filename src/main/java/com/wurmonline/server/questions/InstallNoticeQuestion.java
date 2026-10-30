package com.wurmonline.server.questions;

import com.wurmonline.server.creatures.Creature;

import java.util.Properties;

/**
 * The "please install SkloGraphics" window (see InstallNotice in the mod's own package). Lives in
 * the game's package on purpose: Question's constructor is package-private, and the mod runs in the
 * server's class loader (sharedClassLoader=true in distantTerrainRefinement.properties).
 *
 * Purely informational - the Ok button just closes it, answer() has nothing to do.
 */
public final class InstallNoticeQuestion extends Question {
    private final String imageUrl;
    private final String downloadUrl;

    public InstallNoticeQuestion(Creature responder, String imageUrl, String downloadUrl) {
        super(responder, "SkloGraphics", "This server looks best with the free SkloGraphics mod",
                QuestionTypes.MESSAGE_POPUP, responder.getWurmId());
        this.imageUrl = imageUrl;
        this.downloadUrl = downloadUrl;
    }

    @Override
    public void answer(Properties answers) {
    }

    @Override
    public void sendQuestion() {
        final StringBuilder b = new StringBuilder(getBmlHeader());
        // The client downloads the picture itself (BML image src, plain http:// only) and shows an
        // empty frame until it arrives or if it can't.
        b.append("center{image{src=\"").append(imageUrl).append("\";id=\"skloPreview\";text=\"SkloGraphics\";size=\"400,225\"}};");
        b.append("label{text=\" \"};");
        b.append("text{text=\"Your client is running the unmodified game. SkloGraphics is a free client mod that gives the world a modern look: real sky with moving clouds, sun rays, soft shadows over the whole landscape, living water, 3D grass and distant forests. It only changes what you see, other players do not need it.\"};");
        b.append("label{text=\" \"};");
        b.append("text{type=\"bold\";text=\"How to install it\"};");
        b.append("text{text=\"1. Download SkloGraphics.jar and skloGraphics.properties from the releases page:\"};");
        b.append("text{color=\"120,190,255\";text=\"").append(downloadUrl).append("\"};");
        b.append("text{text=\"2. Install the Wurm Unlimited client modlauncher if you do not have it yet.\"};");
        b.append("text{text=\"3. Put SkloGraphics.jar into the folder  WurmLauncher/mods/skloGraphics/  and skloGraphics.properties into  WurmLauncher/mods/  (inside your Wurm Unlimited folder).\"};");
        b.append("text{text=\"4. Start the game and set the renderer to Modern in the graphics settings.\"};");
        b.append("label{text=\" \"};");
        b.append("text{text=\"After the first install it updates itself. This window shows at every login until the mod is installed.\"};");
        b.append(createOkAnswerButton());
        getResponder().getCommunicator().sendBml(440, 620, true, true, b.toString(), 200, 200, 200, title);
    }
}
