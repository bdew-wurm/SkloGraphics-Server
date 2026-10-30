# Creature colours for other server mods

`com.sklotopolis.mod.server.distantTerrainRefinement.CreatureColors` lets any server mod give creatures a custom colour.
Players running the SkloGraphics client see the exact colour; vanilla clients see a close approximation through the
game's normal repaint packet.

## Colour a whole creature template (the normal case)

```java
import com.sklotopolis.mod.server.distantTerrainRefinement.CreatureColors;

CreatureColors.setTemplateColor(templateId, 240, 200, 20, 255);
CreatureColors.setTemplateColor(template, 128, 0, 160, 255);
CreatureColors.clearTemplateColor(templateId);
int[] rgba = CreatureColors.getTemplateColor(templateId);
```

- Every creature of that template is coloured: creatures that spawn later and ones already in the world (they are repainted
  for everyone watching on the next server poll).
- Values are 0-255 and clamped. Alpha is the tint strength: 255 = the colour exactly, lower blends toward the creature's normal
  look, 0 removes the colour.
- Template colours are not saved to disk. Set them every server start, e.g. from your mod's `init()`, `onServerStarted()` or
  right after you create your template. Removing your mod therefore removes its colours.
- It is safe to call before the server has started; nothing is looked up until the server runs.
- Players are never coloured through a template (the human template is shared with players).

## Colour a single creature (optional)

```java
CreatureColors.setColor(creature, 255, 255, 255, 255);
CreatureColors.clearColor(creature);
```

A creature's own colour wins over its template colour. Individual colours are saved in
`mods/distantTerrainRefinement/creatureColors.properties` and survive restarts.

## Reading colours

- `getColor(creature)` / `getColor(creatureId)`: the colour actually shown (own colour, else template colour), or `null`.
- `getCreatureColor(creatureId)`: only the individual colour, or `null`.

## Setting up your mod

1. Compile against `DistantTerrainRefinement.jar` (provided scope; do not ship it inside your jar).
2. In your mod's `.properties`:
   ```
   sharedClassLoader=true
   depend.requires=distantTerrainRefinement
   ```
   `sharedClassLoader=true` lets your mod see the classes of this mod (which also uses the shared class loader);
   `depend.requires` makes the mod launcher load this mod before yours and refuse to start without it.
