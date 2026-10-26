# Changelog

All notable changes to this mod are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.2] - 2026-10-26
### Fixed
- The haze of heavy snow and blizzards now covers the distant land of
  [Distant Horizons](https://www.curseforge.com/minecraft/mc-mods/distant-horizons) too. It used to end at the vanilla
  render distance, leaving the far land clear and green behind a hard edge; now the near and the far land fade into the
  storm alike.
- With [LambDynamicLights](https://modrinth.com/mod/lambdynamiclights) the lying snow is lit by torches in hand, glowing
  mobs and other moving lights like the ground around it, instead of staying dark.

## [1.1.1] - 2026-10-13
### Fixed
- Settled snow no longer hides rails, redstone dust, tripwire and pressure plates: the ground under them stays clear,
  as with vanilla snow layers. Placing one on snowy ground clears the snow under it.

## [1.1.0] - 2026-10-12
### Added
- Integration with [Particle Rain](https://www.curseforge.com/minecraft/mc-mods/particle-rain): Hoarfrost draws the snowfall, Particle Rain the rain, sandstorms, dust, fog
  and the rest of its particles, all blown by the same wind. Particle Rain's own snow and snowfall sound step aside
  where Hoarfrost's snow falls. With [Serene Seasons](https://www.curseforge.com/minecraft/mc-mods/serene-seasons) both
  mods agree where it snows. Can be switched off in the config (Snowfall > Particle Rain integration).

## [1.0.3] - 2026-10-12
### Fixed
- Compatibility with [Sable](https://www.curseforge.com/minecraft/mc-mods/sable): snow no longer falls inside flying houses, airships and other moving
  structures. Flakes land on their roofs and decks and stop at their walls.

## [1.0.2] - 2026-10-12
### Fixed
- Distant trees of [Distant Horizons](https://www.curseforge.com/minecraft/mc-mods/distant-horizons) no longer turn
  dark blue in snowy biomes or black elsewhere: snowy leaves on the distant land are white like the near ones.

## [1.0.1] - 2026-10-11
### Fixed
- Breaking a trapdoor, a carpet, a slab or another low block no longer leaves snow behind where it stood. Snow that lay
  on the block keeps its depth and drops to the ground below instead of filling the space the block took.

## [1.0.0] - 2026-10-10
### Added
- Seven kinds of snowy weather - light snow, snow, heavy snow, heavy snow with large flakes, light blizzard, blizzard and
  heavy blizzard - each with its own flakes, fall speed, wind, haze and sound, blending into each other.
- A wind that turns and wanders, with gusts and lulls rolling over the land, a boundary layer near the ground, rising
  over and calm behind obstacles with a back eddy, flowing around them and barely reaching under cover; shared course
  with [Petrichor](https://www.curseforge.com/minecraft/mc-mods/petrichor-rain-storms)'s rain.
- Simulated flakes (crystals, clumps, grains, streaks in a gale), snow in the middle distance and a stream of flakes past
  the face in storms.
- Snow on the ground as a smooth surface: drifts in the lee, hollows, swept ridges, even cover under crowns, caps on
  posts, soft rims, a dusting where little falls, snow blown a few blocks into cave mouths; melts near lamps and in
  warm land; saved per world.
- Powder blown up by wind charges, explosions, gusts, running and landings, settling slowly.
- Clouds of snow and driving snow dust in heavy snow and blizzards; whiteouts in heavy blizzards.
- Snow on distant land out to the render distance; snow on crowns.
- White leaves in snowy biomes, as in Bedrock Edition.
- No lightning or thunder over snowy land.
- Footprints as soft dents, packed paths.
- Real snow layer drifts built by the server in windy snowfall.
- Light snow keeps the sky its colour; heavy snow and blizzards bring a bright overcast and a cool blue-grey haze, gated
  to the outdoors.
- Soundscape: silent light snow, breeze, wind, storm roar, howl, passing gusts, muffled indoors; hush of falling snow;
  snow footsteps.
- Optional [Serene Seasons](https://www.curseforge.com/minecraft/mc-mods/serene-seasons) integration.
- The snowy haze works with [Distant Horizons](https://www.curseforge.com/minecraft/mc-mods/distant-horizons) and covers its distant land too.
- `/hoarfrost weather <kind> [seconds]`, `/hoarfrost weather clear`, `/hoarfrost status`.
