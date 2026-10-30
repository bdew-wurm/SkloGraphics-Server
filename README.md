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

SkloGraphics sends each player, once per login, a catalogue of the default action for every item and creature template on your
server, worked out from the templates themselves, so modded things are covered too. To change what **E** does on something, add
lines like:

```
defaultAction.item.<templateId>=<actionId>[@tool[+tool]][!][?]
defaultAction.creature.<templateId>=...
defaultAction.tile.<tileTypeId>=...
```

- The value is `none`, or a comma list of candidates, best first.
- `@tool` limits a candidate to players carrying such a tool. Tools: `dig` `mine` `slash` `axe` `saw` `sickle` `scythe` `field`
  `cultivate` `butcher` `fish` `scissors` `liquid` `groom` `lead` `seed` `sharp` `bucket`. Join several with `+`.
- `!` means only while such a tool is already active in the player's hand.
- `?` means the client checks the action against a real server menu first, because whether it works depends on permissions or
  state.

Example: large crates open on **E**, and a creature template is led when the player carries a lead rope:

```
defaultAction.item.852=3
defaultAction.creature.64=106@lead?
```

The **E** key only ever *asks*. The server still decides whether the action happens, exactly as for a click in the menu.

---

## What changes for everyone

Most of the mod is invisible to players without SkloGraphics. These parts are not:

- **Weather** is nudged towards fair days for every player (see above). Switch it off with both bias values at `0`.
- **Glow runes** give a faint glow instead of lamp light for every player.
- **Faster terrain on login and teleport.** The mod lets the server stream the near terrain after a login or teleport with a 64 KB
  send backlog instead of vanilla's 4 KB, so the world appears sooner for every player.
- **A "please install SkloGraphics" window.** About 20 seconds after login, a player whose client does not report SkloGraphics
  gets a small window with a picture, what the mod does and where to download it. It shows at every login until they install the
  mod. There is deliberately no setting to switch it off; if you do not want it, tell us.
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
