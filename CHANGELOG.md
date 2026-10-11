# Changelog

All notable changes to this mod are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.1] - Unreleased
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
