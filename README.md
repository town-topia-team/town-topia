# TownTopia

Turn quiet villages into living towns. Villagers have names, jobs, skills, moods, homes and daily
routines. They farm, mine, cook, trade, play music and fight off raids, while you work with the Mayor
to help the town grow.

A mod for **Minecraft 1.21.1** on **NeoForge**, with **GeckoLib 4.8.4**.

- Download: [CurseForge](https://www.curseforge.com/minecraft/mc-mods/towntopia)
- Website and wiki: [town-topia.com](https://town-topia.com)

## What is in this repository

- `src/main/java`: the mod's code.
- `src/main/resources`: the mixin settings, the game data (recipes, loot tables, tags, advancements,
  farm crops) and the language files.
- `jar/`: the compiled mod, ready to put in your `mods` folder.

Textures, models, animations, sounds and music are **not** in this repository. They are only in the
compiled jar.

## Building

```
gradlew build
```

This compiles the code, but the jar it makes has no textures, models or sounds. To play, use the jar
in `jar/` or a download from CurseForge.

## License

TownTopia is All Rights Reserved, with permissions: you may play it, put it in modpacks, make videos
and write addons. Read [LICENSE](LICENSE) for the details. Bug reports, ideas and translations are
welcome as GitHub issues. Pull requests are not accepted.

Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.
