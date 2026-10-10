<p align="center">
  <img src="src/main/resources/logo.png" alt="Hoarfrost: Snow & Blizzards" width="160">
</p>

<h1 align="center">Hoarfrost: Snow & Blizzards</h1>

<p align="center">
  Cosy, realistic snowy weather: seven kinds of snowfall and blizzard, flakes carried by a living wind, soft snow settling on the land and a quiet winter soundscape.
</p>

<p align="center">
  <img alt="Minecraft 1.21.1" src="https://img.shields.io/badge/Minecraft-1.21.1-62B47A">
  <img alt="NeoForge" src="https://img.shields.io/badge/Loader-NeoForge-F16436">
  <img alt="License MIT" src="https://img.shields.io/badge/License-MIT-blue">
</p>

## Features

**Seven kinds of snowy weather**, each with its own flakes, fall, wind, haze and sound, gliding into each other as a
snowfall changes character:

| Kind | What it is like |
|---|---|
| Light snow | A few slow crystals tumbling through still air. Complete silence - only the hush of falling snow. |
| Snow | A steady, quiet snowfall with a soft breeze. |
| Heavy snow | The air full of flakes, the land fading into white, a breeze with soft gusts. |
| Heavy snow with large flakes | Big fluffy clumps of crystals drifting down slowly in calm air, the deepest hush. |
| Light blizzard | Fine snow driven by a fresh wind; gusts roll over the land. |
| Blizzard | A howling storm, snow streaming past the face, squalls swallowing the world for a moment. |
| Heavy blizzard | A whiteout: roaring, howling wind and gusts of snow tearing past. |

- **Volume in storms**: in heavy snow and blizzards, clouds of snow roll low over the land with the wind, the air is
  full of driving snow dust, and squalls swallow the view; in a heavy blizzard one sees only a few blocks.
- **A living wind.** It turns slowly and wanders, swells in gusts and drops in lulls; gusts are fronts that roll over the
  land from upwind. Near the ground it slows down, it rises over hills and walls, is calm behind them with an eddy rolling
  back, flows around obstacles and only reaches a little way under crowns, roofs and into caves. Rain and snow blow the
  same way (shared with Petrichor).
- **Flakes moved by that air**: specks, soft round flakes, six-armed crystals that tumble and glint, fluffy clumps,
  hard grains in storms, streaks in a gale. Snow falls in the middle distance too, and in heavy snow and blizzards a
  dense stream of light flakes rushes past the face.
- **Soft snow on the ground.** Snow settles on the land, roofs, crowns and posts as one smooth surface - no grid, no
  steps: rounded drifts in the lee of walls, trees and hills, fuller hollows, thinner wind-swept ridges and roofs, an even
  layer under crowns, little rounded caps on fence posts, a soft rim at the edge of a roof. Where little snow falls it is
  a dusting with the ground showing through. Crowns of trees hold snow on top and their leaves turn white (as in Bedrock Edition), and distant land and
  trees stay white out to the render distance. The colour follows the vanilla snow texture (and resource packs). Snow blown into a cave mouth or under an overhang settles a few blocks deep
  and fades - it never runs down a tunnel. It sits on top of vanilla snow layers, melts near lamps and in warm land, and
  stays where it lay when you come back.
- **Snow has physics.** A wind charge or an explosion bursts the air outwards, flinging falling flakes aside, and blows the
  snow off the ground into a cloud of powder that rises, drifts with the wind and settles slowly. Strong gusts lift powder
  off exposed snow and send it skimming along the ground; running feet kick it up; a landing throws up a puff.
- **Footprints** as plain soft dents; trodden paths get packed. Fresh snow fills them again.
- **Real drifts** (server): in windy snowfall, snow layers pile up in the lee of walls and trees.
- **No thunderstorms in snow**: no lightning, flashes or thunder over snowy land.
- **Sky and haze**: a snowy sky is soft and bright instead of the dark grey of rain (thunder does not turn it dirty grey);
  falling snow closes in the view with a cool blue-grey haze of the sky's brightness, thicker in gusts. None of it
  reaches indoors or underground.
- **Sound**: light snow is silent; snow brings a soft breeze; storms grow from a fresh wind to a roar with a howl around
  every edge, and single gusts rush past from upwind. Indoors the storm is muffled; underground there is nothing. Falling
  snow hushes the world outdoors, and footsteps crunch in powder and squeak on trodden snow.
- **[Serene Seasons](https://www.curseforge.com/minecraft/mc-mods/serene-seasons)** (optional): snow falls and lies where
  the season makes it cold - temperate land in winter, melting in spring, none in a warm summer - and deep winter brings
  heavier snow and blizzards.
- Works with Petrichor, Sodium, Iris and shader packs, and Sound Physics Remastered. Everything visual works on any
  server; with the mod on the server too, all players share the same kind of snow and the server builds drifts.

## Controls

No key bindings. Operators can force the weather for testing:

| Command | Effect |
|---|---|
| `/hoarfrost weather <kind> [seconds]` | Let it snow with a kind (`light_snow`, `snow`, `heavy_snow`, `heavy_large_flakes`, `light_blizzard`, `blizzard`, `heavy_blizzard`), 600 seconds by default. Snow falls where the land is cold; elsewhere it rains. |
| `/hoarfrost weather clear` | Clear weather. |
| `/hoarfrost status` | The kind of snow, the wind, the season and whether it snows where you stand. |

The F3 screen shows the snow, wind and flake counts on the right.

## Crafting

Nothing to craft: the mod has no items.

## Configuration

In the mod list (Mods > Hoarfrost > Config) or in the config files:

- `hoarfrost-client.toml`: quality preset (flake counts, how far snow is simulated and drawn), snowfall on/off,
  flake density, snow in the distance, clouds of snow in storms, the stream of flakes in storms, snow on the ground, snow
  on distant land, snowy leaves, footprints, powder, haze, bright snowy sky, wind volume, hush, snow footsteps.
- `hoarfrost-server.toml` (per world): chances of each kind of snow, thunderstorms as blizzards, following Serene Seasons,
  drifts (on/off, highest drift, growth speed).

## Installation

1. Install [NeoForge](https://neoforged.net) for Minecraft 1.21.1.
2. Put this mod into the `mods` folder (client; on a server too for shared weather and drifts).
3. Optional: [Serene Seasons](https://www.curseforge.com/minecraft/mc-mods/serene-seasons) for seasonal snow.

## Building

```sh
./gradlew build
```

The jar is written to `build/libs/`. `./gradlew runGameTestServer` runs the game tests of the snow simulation.

## Credits

- Author: **Pocky**.
- Sounds: field recordings from [Freesound](https://freesound.org), all [CC0](https://creativecommons.org/publicdomain/zero/1.0/),
  cut and mixed by `tools/prepare_sounds.py`:
  - breeze: [a gentle breeze, wind 3](https://freesound.org/s/181252/) by mario1298,
    [Looping Gentle Wind Ambience on an Open Desert Plain](https://freesound.org/s/697217/) by dhallcomposer;
  - wind: [Wind Artic Cold](https://freesound.org/s/117136/) by cobratronik, [Polar Wind](https://freesound.org/s/521820/) by Fission9;
  - storm and gusts: [Wind during snow storm #1](https://freesound.org/s/473815/) by guyburns,
    [Winter storm wind lashing snow](https://freesound.org/s/574552/) by TRP, [gust of wind](https://freesound.org/s/80937/) by Tony B kksm;
  - howl: [Howling winter storm ambient sounds](https://freesound.org/s/505999/) and
    [Howling Wind Ambience](https://freesound.org/s/405601/) by DBlover;
  - footsteps: [snow footsteps](https://freesound.org/s/432285/) by Nimlos, [footsteps in fresh snow](https://freesound.org/s/453168/)
    by florianreichelt, [sfx_snow_footstep-01](https://freesound.org/s/378056/) by bajko,
    [Snow Walking Quiet](https://freesound.org/s/333422/) by MDRivet, [Footsteps in Hard Crunchy Snow](https://freesound.org/s/559459/) by mshahen.
- Textures: drawn by `tools/make_textures.py`.

<!-- more-mods:start -->
<!-- more-mods:end -->

## License

[MIT](LICENSE)
