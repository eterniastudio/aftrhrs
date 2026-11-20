# Aftr Hrs

The **Aftr Hrs** Minecraft server as one Fabric mod: Feather's systems (claims, homes, pets, quests, the bank, storage
networks, fishing, farming and more), the Arcana creatures, every custom item and piece of furniture, and the client
screens and HUD that go with them. The same jar runs on the server and on every player's game, so the two always match.

This repository holds the releases only.

## Install

Minecraft **26.3** with **Fabric Loader 0.20.1** or newer (use the Fabric installer, or a Fabric profile in your
launcher). Put these into your `mods` folder:

| File | Where |
|---|---|
| `aftrhrs-<version>.jar` | the [latest release](../../releases/latest) |
| Fabric API | [Modrinth](https://modrinth.com/mod/fabric-api) (the version the release page lists) |
| GeckoLib for Fabric 26.3 | [Modrinth](https://modrinth.com/mod/geckolib) (the version the release page lists) |

**Coming from the Aftr Hrs Companion?** Remove the old Companion jar (`aftrhrs-fabric-<version>.jar`) from your
`mods` folder (in some launchers, such as NoRisk, that is `custom_mods`). It uses the same mod id, so with both
installed the game won't start. The Companion's features are part of this mod now.

JEI is optional: the mod adds the server's recipes to it. Every key can be changed under Options → Controls → Key
Binds → Aftr Hrs.

## Automatic updates

Once installed, the mod keeps itself up to date, along with the GeckoLib and Fabric API versions it needs. It checks
this repository's latest release when the game starts and every few hours after. A newer version is downloaded in the
background; you get a small notice ("Update 1.0.1 downloaded") and it is put in place when you close the game, so
the next start runs it. Change it under **HUD & Display → Updates**: *Automatic*, *Notify only* or *Off* (with *Off*
the mod never contacts GitHub on its own).

If you join the server with a different version, you are told which one it runs ("This server runs Aftr Hrs 1.0.1 —
restart to update") instead of a confusing error, and the update starts downloading right away.

Updates are safe to trust:

- Every release has a `manifest.json` listing each jar's mod id, version, SHA-256 and size, and `manifest.json.sig`,
  an Ed25519 signature over that file. The mod only accepts a manifest signed by the Aftr Hrs release key, whose
  public half is built into the mod: `pTOmhfKsBFTF7NDz1kFMtW6ssXf3aa7MAhUObr+OaHU=`
- The mod jar comes only from this repository's releases, the dependencies from Modrinth's CDN (or GitHub), and each file is
  checked against the signed size and SHA-256 before it is used.
- It only ever moves to a higher version, and only touches its own jar and older copies of GeckoLib and Fabric API in
  the folder the mod was loaded from. Your other mods are never changed. A dependency your launcher manages in another
  folder is left alone.
- Nothing changes while the game runs: the new jars are swapped in only after all of them are downloaded and checked,
  so the mods folder never ends up with two copies or a half-written file. The jars it replaced are kept in
  `config/aftrhrs/updates/rollback/` (with a `ROLLBACK.txt` saying how to put them back).

If your launcher manages mod versions for you, you can set updates to *Off* and let the launcher handle it.

## Servers

Server owners use the same jar with the same Fabric API and GeckoLib. A dedicated server doesn't update itself unless
you ask it to: set `updater.server: stage` in `aftrhrs/config.yml` to download new releases in the background and
install them at the next restart, or run `feather update stage` from the console when you want to. `feather update`
shows what the updater knows.
