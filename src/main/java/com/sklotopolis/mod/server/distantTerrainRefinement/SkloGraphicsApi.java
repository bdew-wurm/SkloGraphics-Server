package com.sklotopolis.mod.server.distantTerrainRefinement;

public final class SkloGraphicsApi {
    private SkloGraphicsApi() {
    }

    public static void setDefaultAction(String target, short actionId) {
        DefaultActionRules.set(target, Short.toString(actionId));
    }

    public static void setDefaultAction(String target, String action) {
        DefaultActionRules.set(target, action);
    }

    public static void setDefaultAction(String target, String activatedItem, short actionId) {
        DefaultActionRules.set(key(target, activatedItem), Short.toString(actionId));
    }

    public static void setDefaultAction(String target, String activatedItem, String action) {
        DefaultActionRules.set(key(target, activatedItem), action);
    }

    public static void setDefaultAction(String target, int activatedTemplateId, short actionId) {
        setDefaultAction(target, Integer.toString(activatedTemplateId), actionId);
    }

    public static void setDefaultAction(String target, int activatedTemplateId, String action) {
        setDefaultAction(target, Integer.toString(activatedTemplateId), action);
    }

    private static String key(String target, String activatedItem) {
        return activatedItem == null ? target : target + "@" + activatedItem;
    }
}
