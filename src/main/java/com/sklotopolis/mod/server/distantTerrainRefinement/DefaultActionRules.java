package com.sklotopolis.mod.server.distantTerrainRefinement;

import org.gotti.wurmunlimited.modsupport.IdFactory;
import org.gotti.wurmunlimited.modsupport.IdType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

final class DefaultActionRules {
    private static final Logger logger = Logger.getLogger(DefaultActionRules.class.getName());

    private static final String PREFIX = "defaultAction.";
    private static final int ITEM = 2;
    private static final int CREATURE = 1;
    private static final int TILE = 3;
    private static final Map<String, Integer> GENERAL = new HashMap<String, Integer>();

    private static final Map<String, String> apiRules = new ConcurrentHashMap<String, String>();
    private static final Map<String, String> configRules = new ConcurrentHashMap<String, String>();
    private static volatile boolean changed;

    static {
        GENERAL.put("player", 0);
        GENERAL.put("wall", 5);
        GENERAL.put("fence", 7);
        GENERAL.put("border", 12);
        GENERAL.put("cave", 17);
        GENERAL.put("floor", 23);
        GENERAL.put("corner", 27);
        GENERAL.put("bridge", 28);
        GENERAL.put("any", 255);
    }

    static final class Rule {
        final int kind;
        final int target;
        final int active;
        final short action;

        Rule(int kind, int target, int active, short action) {
            this.kind = kind;
            this.target = target;
            this.active = active;
            this.action = action;
        }
    }

    private DefaultActionRules() {
    }

    static void configure(Properties properties) {
        configRules.clear();
        for (String name : properties.stringPropertyNames()) {
            if (!name.startsWith(PREFIX)) continue;
            String key = name.substring(PREFIX.length()).trim();
            String value = properties.getProperty(name).trim();
            try {
                validate(key, value);
                configRules.put(key, value);
            } catch (IllegalArgumentException e) {
                logger.warning("DefaultActionRules: ignoring " + name + "=" + value + " - " + e.getMessage());
            }
        }
        if (!configRules.isEmpty()) logger.info("DefaultActionRules: " + configRules.size() + " default action rules");
        changed = true;
    }

    static void set(String key, String action) {
        if (key == null) throw new IllegalArgumentException("no key");
        String k = key.trim();
        if (action == null) {
            apiRules.remove(k);
        } else {
            validate(k, action.trim());
            apiRules.put(k, action.trim());
        }
        changed = true;
    }

    static boolean takeChanged() {
        boolean was = changed;
        changed = false;
        return was;
    }

    private static void validate(String key, String value) {
        if (value.isEmpty()) throw new IllegalArgumentException("no action");
        String target = targetOf(key);
        String active = activeOf(key);
        if (active != null && active.isEmpty()) throw new IllegalArgumentException("no item after @");
        int dot = target.indexOf('.');
        String word = dot < 0 ? target : target.substring(0, dot);
        if (word.equals("any") && active == null) throw new IllegalArgumentException("any needs an @item");
        if (GENERAL.containsKey(word)) {
            if (dot >= 0) throw new IllegalArgumentException(word + " takes no id");
            return;
        }
        if (!word.equals("item") && !word.equals("creature") && !word.equals("tile")) {
            throw new IllegalArgumentException("unknown target '" + word + "'");
        }
        if (dot < 0) return;
        if (dot == target.length() - 1) throw new IllegalArgumentException(word + " needs an id after the dot");
        if (word.equals("tile") && !isNumber(target.substring(dot + 1))) throw new IllegalArgumentException("tile needs a number");
    }

    private static String targetOf(String key) {
        int at = key.indexOf('@');
        return (at < 0 ? key : key.substring(0, at)).trim();
    }

    private static String activeOf(String key) {
        int at = key.indexOf('@');
        return at < 0 ? null : key.substring(at + 1).trim();
    }

    /** API rules first, so that a line in the owner's properties file wins over a mod's rule. */
    static List<Rule> resolve(Map<String, List<Short>> actionIds) {
        Map<String, String> merged = new LinkedHashMap<String, String>(apiRules);
        merged.putAll(configRules);
        Map<String, Rule> out = new LinkedHashMap<String, Rule>();
        if (merged.isEmpty()) return new ArrayList<Rule>();
        Map<String, Integer> items = templateIds(IdType.ITEMTEMPLATE);
        Map<String, Integer> creatures = templateIds(IdType.CREATURETEMPLATE);
        for (Map.Entry<String, String> e : merged.entrySet()) {
            Rule rule = resolve(e.getKey(), e.getValue(), items, creatures, actionIds);
            if (rule != null) out.put(rule.kind + ":" + rule.target + ":" + rule.active, rule);
        }
        return new ArrayList<Rule>(out.values());
    }

    private static Rule resolve(String key, String value, Map<String, Integer> items, Map<String, Integer> creatures, Map<String, List<Short>> actionIds) {
        String target = targetOf(key);
        int dot = target.indexOf('.');
        String word = dot < 0 ? target : target.substring(0, dot);
        int kind;
        int targetId = -1;
        if (GENERAL.containsKey(word)) {
            kind = GENERAL.get(word);
        } else {
            kind = word.equals("item") ? ITEM : word.equals("creature") ? CREATURE : TILE;
            if (dot >= 0) {
                Integer id = id(target.substring(dot + 1), kind == CREATURE ? creatures : items);
                if (id == null) {
                    logger.warning("DefaultActionRules: no " + target + " - ignoring " + key);
                    return null;
                }
                targetId = id;
            }
        }
        int activeId = -1;
        String active = activeOf(key);
        if (active != null) {
            Integer id = id(active, items);
            if (id == null) {
                logger.warning("DefaultActionRules: no item " + active + " - ignoring " + key);
                return null;
            }
            activeId = id;
        }
        short action = action(value, actionIds, key);
        return action < 0 ? null : new Rule(kind, targetId, activeId, action);
    }

    private static short action(String value, Map<String, List<Short>> actionIds, String key) {
        if (value.equalsIgnoreCase("none")) return 0;
        if (isNumber(value)) {
            try {
                short id = Short.parseShort(value);
                if (id > 0) return id;
            } catch (NumberFormatException e) {
                logger.log(Level.FINE, "DefaultActionRules: action id out of range", e);
            }
            logger.warning("DefaultActionRules: bad action id " + value + " - ignoring " + key);
            return -1;
        }
        List<Short> ids = actionIds.get(value.toLowerCase(Locale.ROOT));
        if (ids == null) {
            logger.warning("DefaultActionRules: no action named '" + value + "' - ignoring " + key);
            return -1;
        }
        if (ids.size() > 1) logger.warning("DefaultActionRules: " + ids.size() + " actions are named '" + value + "', using id " + ids.get(0) + " for " + key);
        return ids.get(0);
    }

    private static Map<String, Integer> templateIds(IdType type) {
        Map<String, Integer> out = new HashMap<String, Integer>();
        try {
            for (Map.Entry<String, Integer> e : IdFactory.getIdsFor(type)) out.put(e.getKey(), e.getValue());
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "DefaultActionRules: could not read the registered " + type + " ids", e);
        }
        return out;
    }

    private static Integer id(String key, Map<String, Integer> ids) {
        if (!isNumber(key)) return ids.get(key);
        try {
            return Integer.valueOf(key);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isNumber(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }
}
