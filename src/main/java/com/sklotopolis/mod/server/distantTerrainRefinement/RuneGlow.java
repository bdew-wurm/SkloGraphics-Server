package com.sklotopolis.mod.server.distantTerrainRefinement;

import com.wurmonline.server.items.Item;
import com.wurmonline.server.items.ItemSpellEffects;
import com.wurmonline.server.items.RuneUtilities;

public final class RuneGlow {
    static final int RADIUS_MIN = -118;
    static final int RADIUS_MAX = -80;
    private static final int RADIUS_BASE = -110;
    private static final float RADIUS_PER_GLOW = 50.0f;
    private static final float COLOR_SCALE = 0.35f;

    private RuneGlow() {
    }

    public static boolean applies(Item item) {
        if (item == null || !item.isLightOverride() || item.getTemplate().isLight()) return false;
        ItemSpellEffects effects = item.getSpellEffects();
        return effects != null && effects.getRuneEffect(RuneUtilities.ModifierEffect.ENCH_GLOW) > 1.0f;
    }

    public static byte radius(Item item) {
        float glow = item.getSpellEffects().getRuneEffect(RuneUtilities.ModifierEffect.ENCH_GLOW) - 1.0f;
        int r = RADIUS_BASE + Math.round(glow * RADIUS_PER_GLOW);
        return (byte) Math.max(RADIUS_MIN, Math.min(RADIUS_MAX, r));
    }

    public static boolean isRuneGlowLight(byte effectType, byte radius, boolean creature) {
        return effectType == (creature ? 0 : 4) && radius >= RADIUS_MIN && radius <= RADIUS_MAX;
    }

    public static byte dim(byte channel) {
        return (byte) Math.round((channel & 0xFF) * COLOR_SCALE);
    }
}
