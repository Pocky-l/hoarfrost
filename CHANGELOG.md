# Changelog

All notable changes to this mod are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.3.0+1.20.1] - 2026-10-27
Brings the 1.20.1 version up to date with 1.3.0 (everything of 1.1.2, 1.2.0 and 1.3.0).

### Added
- Compatibility with [Living Wind](https://www.curseforge.com/minecraft/mc-mods/living-wind): the snow flies the way
  its wind blows, and with the mod on the server the snowdrifts pile up in its lee too. Each kind of snow keeps its
  character - light snow still drifts down, a blizzard still drives it sideways - but blows harder in Living Wind's
  gusty spells and softer when it calms down. Client option "Living Wind integration" lets the falling snow keep its
  own wind.
- In snowy land it now snows under a clear sky too, as in real snowy country: mostly light snow, now and then a
  steady snowfall, and once in a while a spell of a few minutes without any. Storms still come with the weather.
  Server options `fairWeatherSnow` and `clearSpells` (percent of spells without snow, 15 by default).
- Footprints depend on what lies under the snow: on soil a boot-shaped dent with a trodden floor and a rim of pushed-up
  snow, in deep snow a deeper hole with a furrow dragged behind, on stone and planks a crisp shallow print, and on ice
  the foot slips - a long scuff that sweeps the snow off down to the ice. Left and right feet take turns.
- Snow comes off snowy leaves: walking over a crown, landing on it, bumping your head on leaves, pushing into a hedge or
  breaking leaves shakes down a shower of clumps and powder, and strong gusts shake the crowns now and then.
- Livelier snow: steps puff a little powder even in a thin dusting, and a moderate breeze sets grains creeping over
  fresh snow.

### Changed
- Leaves in snowy biomes keep their green and carry a cap of snow on the top of the crowns and over their edges, instead
  of turning white. With [Distant Horizons](https://www.curseforge.com/minecraft/mc-mods/distant-horizons) the distant
  crowns are paled instead.

### Fixed
- The haze of heavy snow and blizzards now covers the distant land of
  [Distant Horizons](https://www.curseforge.com/minecraft/mc-mods/distant-horizons) too, and closes in over the near
  land as it should even before a storm is at full strength: the near and the far land fade into the storm alike.
- With [Embeddium](https://www.curseforge.com/minecraft/mc-mods/embeddium), a snowstorm left holes in the land: it
  skips the chunks hidden behind the fog, and with the haze of a storm only a few dozen blocks deep it dropped the land
  beyond it, so caves, the sky and the snow on the ground showed through. The land is now drawn out to the render
  distance in any snowfall, and only the haze hides it.
- In heavy snow and blizzards the sky overhead no longer shows through the haze.
- With mods that add moving lights (torches in hand, glowing mobs), the lying snow is lit by them like the ground around
  it, instead of staying dark.

## [1.1.1+1.20.1] - 2026-10-26
### Changed
- Hoarfrost comes to Minecraft 1.20.1 with [Forge](https://files.minecraftforge.net), with everything of 1.1.1: seven
  kinds of snowfall and blizzard, a living wind, soft snow settling on the land with drifts, caps and footprints, snow on
  distant land, powder blown up by explosions, gusts and feet, clouds of snow and whiteouts, a bright snowy sky and haze,
  white leaves in snowy biomes, real snow drifts built by the server, the winter soundscape and the `/hoarfrost`
  commands. It works with [Petrichor](https://www.curseforge.com/minecraft/mc-mods/petrichor-rain-storms),
  [Serene Seasons](https://www.curseforge.com/minecraft/mc-mods/serene-seasons),
  [Distant Horizons](https://www.curseforge.com/minecraft/mc-mods/distant-horizons),
  [Particle Rain](https://www.curseforge.com/minecraft/mc-mods/particle-rain),
  [Oculus](https://www.curseforge.com/minecraft/mc-mods/oculus) shader packs,
  [Embeddium](https://www.curseforge.com/minecraft/mc-mods/embeddium) and
  [Sound Physics Remastered](https://www.curseforge.com/minecraft/mc-mods/sound-physics-remastered). Everything below up
  to 1.1.1 is included, except the [Sable](https://www.curseforge.com/minecraft/mc-mods/sable) support: Sable has no
  Minecraft 1.20.1 version.

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
