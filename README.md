# SkloGraphics Server Mod – server owner guide

The SkloGraphics server mod is the free companion to the **SkloGraphics** client mod for **Wurm Unlimited**. Install it on your
dedicated server and your players who run SkloGraphics get the **complete** mod instead of most of it: caves they can see far
into, right-click menus with every tool, exact default actions, refined distant terrain and structures, and the exact colours of
creatures and mount gear.

Nothing is required from your players. A player without SkloGraphics plays exactly as before, and an unmodified client costs the
server one map lookup per poll and nothing on the wire. The few things that do reach everybody are listed under
[What changes for everyone](#what-changes-for-everyone).

Client mod and player guide: https://gitlab.com/Dominikk/SkloGraphics

---

## What your players get

These features need this mod **and** the SkloGraphics client mod. Each one has its own switch (see [Settings](#settings)).

| Feature | What a SkloGraphics player sees |
|---|---|
| **Far caves** | They see up to 96 tiles far underground instead of vanilla's 24. Solid rock the player could not see anyway is sent as plain wall, so ore veins behind it are **not** revealed. |
| **Refined distant terrain** | Distant hills are smooth instead of stepped in 16x16-tile blocks, the ground type far away is exact, and distant forests only draw where trees really are. |
| **Distant structures** | Houses, bridges and items far outside vanilla's view range stay visible as simple shapes. |
| **Tool menus** | The right-click menu lists every action the player could do with **any** tool they carry, each with the tool's icon. Clicking one takes that tool in hand first. |
| **Exact default actions** | The **E** key (default action) picks the right action on any item, creature or tile of **your** server, including modded ones. |
| **Creature and mount colours** | Creatures, barding, saddles, bridles and bags are shown in the exact colours the server has, not the rounded ones vanilla's packets can carry. |
| **Rune glow** | Items with a glow rune give off a faint glow instead of lamp light. |

---

## Requirements

- A Wurm Unlimited **dedicated server** with the **server modlauncher** by Ago (version 0.45 or newer). It includes ModComm,
  which this mod uses to talk to the client. https://github.com/ago1024/WurmServerModLauncher
- Java 8 or newer (whatever your server already runs on).
- Players need the SkloGraphics client mod from https://gitlab.com/Dominikk/SkloGraphics/-/releases to see anything new.

---

## Installing

1. Stop the server.
2. Download `SkloGraphics-Server-<version>.zip` from https://gitlab.com/Dominikk/SkloGraphics-Server/-/releases.
3. Extract it into the folder of your dedicated server (the one that contains `mods/`). You get:
   - `mods/distantTerrainRefinement.properties`
   - `mods/distantTerrainRefinement/DistantTerrainRefinement.jar`
4. Start the server.

### Check that it works

Open the server log and look for these lines (the last one only appears when a player with SkloGraphics logs in):

```
DistantTerrainRefinement: registered ModComm channel GraphicsPlusPlus:distantTerrainRefinement
CaveVision: hooked cave strips (...), far cave view up to 96 tiles
ToolMenu: hooked right-click menus
...
DistantTerrainRefinement: <player> subscribed (protocol ..., tiers ..., content ...)
```

In the game, a player with SkloGraphics also sees `[ModCommV1] This is a modded server...` in their event window when they log
in. If that line is missing, no server mod registered a channel; check that the jar is in `mods/distantTerrainRefinement/` and
that `mods/distantTerrainRefinement.properties` is next to the folder, not inside it.

The mod also puts `version=1.9.1.7;` into the Steam server list entry instead of the real game version tag, so the server stays
listed for every client version. The game only compares the major version, so this changes nothing else.

### Updating

1. Stop the server.
2. Replace `mods/distantTerrainRefinement/DistantTerrainRefinement.jar` with the new one from the zip.
3. Keep your own `mods/distantTerrainRefinement.properties`. Every setting in it is commented out and has a working default, so
   a new version never needs new lines. Compare with the properties file in the zip only if you want to see new settings.
4. Start the server.

Players do not have to do anything. An older client talking to a newer server mod (or the other way round) speaks the older
protocol of the two.

### Removing

Stop the server and delete `mods/distantTerrainRefinement.properties` and the `mods/distantTerrainRefinement` folder. The mod
adds nothing to your world files, so nothing is left behind. Individual creature colours that other mods set are kept in
`mods/distantTerrainRefinement/creatureColors.properties`; they go with the folder.

---

## Settings

All settings live in `mods/distantTerrainRefinement.properties`. Every line starts with `#`, so the defaults apply until you
remove the `#` in front of one. Restart the server after changing it.

| Setting | Default | What it does |
|---|---|---|
| `caveViewDistanceMax` | `96` | The farthest a player may see in caves, in tiles (32 to 96). Players ask for their own distance (their quality preset decides) and get at most this. `0` turns far caves off and everyone has the vanilla 24 tiles. |
| `weatherRainBias` | `0.15` | Pulls the rain target down after each weather tick (about every 70 seconds). `0` = vanilla. |
| `weatherCloudBias` | `0.007` | Pulls the cloud target down. This is by far the stronger of the two, because rain can never exceed cloudiness. `0` = vanilla. |
| `toolMenu` | `true` | Right-click menus listing the actions of every carried tool. `false` = vanilla menus for everyone. |
| `toolMenuMaxTools` | `150` | How many different kinds of items a player's carried stuff is scanned for when building a menu (tools and weapons first). Lower it if the log warns about slow menu builds. |
| `installNotice` | `true` | Shows the "please install SkloGraphics" window to players whose client does not report the mod. `false` = never. |
| `defaultAction.*` | none | Overrides what the **E** key does on something; see below. |

### Weather

Out of the box the weather rains about half as often and is sunny almost twice as often, while storms still happen:

| | Vanilla | Defaults |
|---|---|---|
| Raining | 30% of the time | 15% |
| Heavy rain | 5.4% | 1.0% |
| Sunny | 26% | 48% |
| Overcast | 22% | 8% |

Weather still changes as gradually as before; it just spends more time clear. Set both values to `0` to get vanilla weather back.
With `weatherCloudBias=0.02` or more, heavy rain and overcast skies almost vanish and you never get real storms.

This affects **all** players, not just those with SkloGraphics.

### Default actions (E key)

SkloGraphics sends each player, once per login, the default action of every item and creature template on your server, worked
out from the templates themselves, so modded things are covered too. **E** only uses the item that is activated right now: a rake on
a field farms, a rake on a forge opens it, and nothing is equipped for the player. To change what **E** does, add rules:

```
defaultAction.<target>[@<activated item>]=<action>
```

- **Target:** `item.<id>`, `creature.<id>`, `tile.<id>`, or `item`, `creature`, `tile` for all of them, `wall`, `fence`, `floor` (also
  roofs), `bridge`, `cave`, `corner`, `border`, `player`, or `any` for every target, which needs an `@activated item`. Walls, fences
  and the rest do nothing on **E** until you add a rule.
- **Ids and the activated item:** a template id, or the string id a mod registered its template under (`ItemTemplateBuilder`,
  `CreatureTemplateBuilder`), which stays the same across servers.
- **Action:** an id, the action's name ignoring case, or `none`. Mod actions get a new id at every server start, so use the name for
  those. If several actions share a name, the lowest id is used and the log warns.

This works the same for custom items and custom actions: a custom item can be the target, the activated item, or both, and any item
can be given any action. **E** looks in this order, and a player's own starred default beats all of it:

1. The activated item on this exact target, then on its kind (`creature@item`), then on `any`.
2. This exact target, then its kind.
3. The automatic choice.

So an activated item's rule beats a rule for the target alone.

Rules go on their own line. A comment must sit on a line of its own too, because anything after the `=` counts as the action.

#### Which id to use

Item and creature ids come from `ItemList` and `CreatureTemplateIds` in the server code, or from the template string a mod registered.
Tile ids come from `Tiles.Tile`. Common ones:

| Items | | Creatures | | Tiles | |
|---|---|---|---|---|---|
| hatchet | 7 | cow | 3 | sand | 1 |
| pickaxe | 20 | bear | 12 | grass | 2 |
| saw | 24 | pig | 44 | rock | 4 |
| shovel | 25 | dog | 51 | dirt | 5 |
| rake | 27 | horse | 64 | clay | 6 |
| metal hammer | 62 | bison | 82 | field | 7 |
| mallet | 63 | sheep | 96 | moss | 20 |
| flint and steel | 143 | | | steppe | 22 |
| forge | 180 | | | reed | 33 |
| large chest | 184 | | | lawn | 38 |
| small cart | 186 | | | | |
| sickle | 267 | | | | |
| scythe | 268 | | | | |
| rope | 319 | | | | |
| wooden altar | 322 | | | | |
| scissors | 394 | | | | |
| small bucket | 421 | | | | |
| grooming brush | 647 | | | | |
| large crate | 852 | | | | |

Some action names belong to more than one action, and a name always picks the lowest id, so use the id for these:

| Name | Ids |
|---|---|
| Open | 3 for items, gates and doors, 568 for containers in the inventory |
| Repair | 162 for items, 193 for walls, fences, floors and bridges |
| Dig | 144 for normal digging, 921 for digging to a pile |
| Flatten | 150 for a tile, 533 for a border |
| Level | 532 for a tile, 865 for a border |
| Cut down | 96 |

#### Items as the target

```
# E opens large crates, and does nothing on large chests
defaultAction.item.852=3
defaultAction.item.184=none

# E on any wooden altar prays
defaultAction.item.322=Pray

# With flint and steel activated, E on a forge lights it. Without it, E still opens the forge
defaultAction.item.180@143=Light

# E on a small cart pulls it instead of embarking
defaultAction.item.186=Pull

# E does nothing on any item without its own rule. This also switches off the automatic choice for items
defaultAction.item=none
```

#### Creatures as the target

```
# E mounts horses instead of leading them
defaultAction.creature.64=Ride

# ...but with a grooming brush activated it grooms them
defaultAction.creature.64@647=Groom

# Cows are milked with a small bucket, sheep are sheared with scissors
defaultAction.creature.3@421=Milk
defaultAction.creature.96@394=Shear

# E tames dogs, and never does anything on bears
defaultAction.creature.51=Tame
defaultAction.creature.12=none

# A rope activated: every creature is led. Nothing activated: the normal choice
defaultAction.creature@319=Lead

# E never does anything on a creature that has no rule of its own
defaultAction.creature=none
```

#### Terrain

```
# With a rake activated, fields are farmed
defaultAction.tile.7@27=Farm

# With a shovel activated, E flattens dirt and digs every other tile
defaultAction.tile.5@25=150
defaultAction.tile@25=144

# E does nothing on rock unless a pickaxe is activated
defaultAction.tile.4=none
defaultAction.tile.4@20=Mine

# Underground, a pickaxe mines. Rules for a tile id apply there too
defaultAction.cave@20=Mine
```

#### Walls, fences and other structures

These do nothing on **E** until you add a rule. A rule covers all walls or all fences; the kind of wall or fence can't be told apart.
A plain fence with `fence=3` still shows Open, and the server says no on execution.

```
# E opens gates
defaultAction.fence=3

# E opens doors
defaultAction.wall=3

# With a mallet activated, walls, fences, floors, roofs and bridges are repaired
defaultAction.wall@63=193
defaultAction.fence@63=193
defaultAction.floor@63=193
defaultAction.bridge@63=193

# E on another player opens a trade
defaultAction.player=Trade
```

#### Custom items and custom actions

Mod actions get a new id at every server start, so use their name here, or `SkloGraphicsApi` from code. Custom templates are best named
by their string id, which is what the mod passed to `ItemTemplateBuilder` or `CreatureTemplateBuilder`.

```
# A custom item as the target: E unhitches at a hitching post
defaultAction.item.ago.hitchingpost=Unhitch

# Vanilla action on a custom item, and a custom action on a vanilla item
defaultAction.item.mymod.bigchest=3
defaultAction.item.180=Temper Blade

# A custom item needed for a custom action on one target
defaultAction.item.mymod.vault@mymod.goldenkey=Unlock Vault

# A custom tool that works on every creature, every tile or every wall
defaultAction.creature@mymod.whip=Tame Beast
defaultAction.tile@mymod.seedbag=Plant Catseye
defaultAction.wall@mymod.chisel=Carve Wall

# A custom creature, with and without a tool
defaultAction.creature.mymod.drake=Rename Creature
defaultAction.creature.mymod.drake@mymod.saddle=Saddle Drake

# A custom tool used on a vanilla target
defaultAction.item.180@mymod.bellows=Stoke Fire

# A vanilla tool used on a custom target
defaultAction.item.mymod.oredeposit@20=Mine

# Template ids work too, if you know them
defaultAction.item.22045@22046=Unlock Vault
```

#### One item, everything

`any` covers every target and always needs an activated item. More exact rules for the same item still win:

```
# The wand casts Spark on everything...
defaultAction.any@mymod.wand=Cast Spark

# ...except fences, where it mends them...
defaultAction.fence@mymod.wand=Mend Fence

# ...on every creature it charms...
defaultAction.creature@mymod.wand=Charm

# ...on horses it calms them...
defaultAction.creature.64@mymod.wand=Calm

# ...and on sheep it does nothing
defaultAction.creature.96@mymod.wand=none
```

#### How the rules add up

With these rules:

```
defaultAction.creature=none
defaultAction.creature.64=Ride
defaultAction.creature@319=Lead
defaultAction.creature.64@647=Groom
defaultAction.any@mymod.wand=Cast Spark
```

**E** does this:

| Target | Activated | E does | Why |
|---|---|---|---|
| Horse | nothing | Ride | exact target |
| Horse | rake | Ride | no rule for a rake, so the exact target |
| Horse | grooming brush | Groom | activated item on the exact target |
| Horse | rope | Lead | activated item on the kind |
| Horse | wand | Cast Spark | activated item on `any` |
| Cow | nothing | nothing | the kind says `none` |
| Cow | rope | Lead | activated item on the kind |
| Forge | nothing | Open | automatic choice |
| Forge | wand | Cast Spark | activated item on `any` |

The **E** key only ever *asks*. The server still decides whether the action happens, exactly as for a click in the menu.

### For mod authors

Other server mods can set the same rules from code with `SkloGraphicsApi`. This is the safest way to use a custom action, because you
pass its id directly and it can't be mixed up with another action of the same name.

Compile against `DistantTerrainRefinement.jar` as a provided dependency and call it from `onServerStarted()`, by which time every mod
is loaded and has its action ids. Your mod needs no `depend.*` setting to see the class. Rules can change at any later time too; the
catalogue is then sent again to everyone online.

```java
try {
    short tame = tameAction.getActionId();
    short spark = sparkAction.getActionId();

    // On a target, with nothing special activated
    SkloGraphicsApi.setDefaultAction("creature.mymod.drake", tame);
    SkloGraphicsApi.setDefaultAction("item.ago.hitchingpost", "Unhitch");
    SkloGraphicsApi.setDefaultAction("fence", (short) 3);

    // On a target, only with this item activated: string id, or template id
    SkloGraphicsApi.setDefaultAction("creature.mymod.drake", "mymod.whip", tame);
    SkloGraphicsApi.setDefaultAction("creature.64", whipTemplateId, tame);
    SkloGraphicsApi.setDefaultAction("item." + vaultTemplateId, keyTemplateId, "Unlock Vault");

    // One custom item on a whole kind of target, or on everything
    SkloGraphicsApi.setDefaultAction("wall", "mymod.chisel", spark);
    SkloGraphicsApi.setDefaultAction("creature", "mymod.whip", tame);
    SkloGraphicsApi.setDefaultAction("any", "mymod.wand", spark);

    // Nothing on E with this item, or take a rule away again
    SkloGraphicsApi.setDefaultAction("creature.96", "mymod.wand", "none");
    SkloGraphicsApi.setDefaultAction("creature.64", (String) null);
} catch (NoClassDefFoundError e) {
    // SkloGraphics server mod not installed
}
```

| Argument | Meaning |
|---|---|
| target | The part of a rule before `@`: `item.<id>`, `creature.<id>`, `tile.<id>`, `item`, `creature`, `tile`, `wall`, `fence`, `floor`, `bridge`, `cave`, `corner`, `border`, `player`, `any`. Ids are template ids or string ids. |
| activated item (optional) | A string id or an `int` template id. Left out or `null` means the rule applies without a specific item. `any` needs one. |
| action | A `short` action id, or a `String`: a name, an id or `none`. A `null` String removes the rule. |

The two-argument form also takes the full key, like `setDefaultAction("creature.2@3", id)`. A bad target throws
`IllegalArgumentException`. A template or action that doesn't exist logs a warning when the catalogue is built, and a line in the
properties file wins over a rule set from code.

---

## What changes for everyone

Most of the mod is invisible to players without SkloGraphics. These parts are not:

- **Weather** is nudged towards fair days for every player (see above). Switch it off with both bias values at `0`.
- **Glow runes** give a faint glow instead of lamp light for every player.
- **Faster terrain on login and teleport.** The mod lets the server stream the near terrain after a login or teleport with a 64 KB
  send backlog instead of vanilla's 4 KB, so the world appears sooner for every player.
- **A "please install SkloGraphics" window.** About 20 seconds after login, a player whose client does not report SkloGraphics
  gets a small window with a picture, what the mod does and where to download it. It shows at every login until they install the
  mod. Switch it off with `installNotice=false`.
- **Creature colours** reach vanilla clients through the game's normal repaint packet, as a close approximation.

---

## What it costs

- **Distant terrain:** the first full set of data for a player is about 5.4 MB (4.2 MB for the 2x2 tier, 1.0 MB for the 8x8
  tier, 0.2 MB for the 16x16 tier). It is paced to the player's connection, so it streams in after login and never overflows
  the send buffer. After that, walking costs roughly 65 KB per 32 tiles for the finest tier, 25 KB per 96 tiles for the middle
  one and 10 KB per 256 tiles for the coarsest.
- **Far caves:** each cave tile is sent once per player, paced a few rows per server poll. A player only gets far cave data while
  underground. Lower `caveViewDistanceMax` if you run a very large number of players underground at once.
- **Everything else** is small: the default-action catalogue and mount colours are sent once per login, tool menus only when a
  player opens a right-click menu.

The mod does no work for a player until their client has said it wants it, so it scales with the number of SkloGraphics players,
not with your total player count.

---

## If something looks wrong

- **No `registered ModComm channel` line in the log**: the mod is not loading. The jar must be at
  `mods/distantTerrainRefinement/DistantTerrainRefinement.jar` and the `.properties` file at `mods/distantTerrainRefinement.properties`.
  Check that the server modlauncher itself loads (its own startup lines are in the log).
- **Players with SkloGraphics see vanilla caves**: check that `caveViewDistanceMax` is not `0` and the log has
  `CaveVision: <player> asked for ... tiles of cave view, granted ...`. A grant of `0` means the setting is off or the player's
  client is not asking.
- **A line starting `... could not hook ...`**: the server is a game version the mod does not know. That one feature stays
  vanilla and the rest keeps working. Please send us the line.
- **The "install SkloGraphics" window shows for a player who has the mod**: their client mod did not load or is not
  SkloGraphics at all. Ask them for their `client.log` and look for `SkloGraphics`.
- **Menus feel slow**: the log prints a warning when a menu build is slow. Lower `toolMenuMaxTools`.

The mod logs under the `com.sklotopolis.mod.server.distantTerrainRefinement` logger. Per-move terrain windows are only logged at
`FINE`.

---

## For mod authors

Other server mods can give creatures a custom colour that SkloGraphics players see exactly. See
[CREATURE_COLORS.md](CREATURE_COLORS.md).

---

## Building from source

Requirements: JDK 8+, Maven, and network access to `https://gotti.no-ip.org/maven/repository` the first time (it provides
`server-modlauncher` and the game's server classes). After that, offline builds work.

```
mvn package                  # -> target/DistantTerrainRefinement.jar
mvn package -Pdeploy-server  # also copies jar + properties into a dedicated server install
```

The install path is the `wurm.server.install.dir` property in `pom.xml`; override it with `-Dwurm.server.install.dir=...`.
Stop the server first: Windows locks a jar that is in use.

The source is in `src/main/java/com/sklotopolis/mod/server/distantTerrainRefinement/`. The mod talks to the client over ModComm
channels whose names and wire formats are duplicated in the client mod, so change them on both sides together. The launcher
only calls `init()` on mods that implement `Initable`; this one does.

---

## License and contact

The server mod is free software under the GNU Lesser General Public License, version 3 (see [LICENSE](LICENSE)). The
SkloGraphics client mod has its own license. Wurm Unlimited and its classes belong to Code Club AB and are not covered by this
license; Wurm Unlimited is not endorsed by or connected to Sklotopolis.

Questions and bug reports: open an issue at https://gitlab.com/Dominikk/SkloGraphics-Server/-/issues or write to
office@sklotopolis.com.

SkloGraphics is made by the Sklotopolis team and keeps going on supporters' donations. See the player guide for how to help.
