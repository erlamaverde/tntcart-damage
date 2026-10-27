# TNT Cart Damage

A small client-side Fabric mod that shows how much damage a TNT minecart deals to a player, as a floating number above their head.

## Features

- **Damage numbers:** a red `-X` (in HP) appears on the player hit by a TNT minecart, then fades out after 2 seconds.
- **Absorption shown separately:** damage taken by absorption (yellow) hearts appears below in yellow, e.g. `(-4)`.
- **Instant prediction:** the damage is calculated the moment the explosion happens, using the same formula as the game (distance, blocks in the way, difficulty, armor, Protection / Blast Protection, Resistance). It is shown slightly faded and replaced by the real value when the server sends it.
- **Totem pops:** a green `pop!` appears when a player's totem of undying activates.
- **Stacking:** if another cart hits before the previous number disappears, the new number appears above it.
- **Fake-health servers:** some servers send fake health values for other players. The mod detects this and relies only on the prediction and on absorption hearts, which stay accurate.

## Requirements

- Minecraft 26.2
- Fabric Loader 0.19.5 or newer
- Fabric API

The mod is **client-side only**: it does not need to be installed on the server.

## Commands

- `/tntcartdamage test` — shows a sample damage number on yourself (use third person, F5).
- `/tntcartdamage debug` — prints in chat what the mod sees (explosions, damage, predicted vs. real values).

## Building

```
./gradlew build
```

The mod jar is created in `build/libs/`.

## License

[MIT](LICENSE)
