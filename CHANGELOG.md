# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2026.11.06-2] - 2026-11-06

### 🐛 Bug Fixes

- [`9929887`](https://github.com/Project-Outlands/Outlands/commit/9929887202f9422229cdaddc349cdcb716cbceb8) - *(installer)* Emit artifact paths for vanilla libraries *(PR [#52](https://github.com/Project-Outlands/Outlands/pull/52) by [@H2Sxxa](https://github.com/H2Sxxa))*
- [`1ce55ea`](https://github.com/Project-Outlands/Outlands/commit/1ce55ea83f16995546434f98134d2cf64f780f69) - Narrator broken in installer *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*


## [2026.11.06] - 2026-11-06

### 🚀 Features

- [`0b36b11`](https://github.com/Project-Outlands/Outlands/commit/0b36b118f0a2288d17c6983ecb593fdc6bc85c54) - *(render)* Add render takeover seams and the shim retarget table *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*

### 🐛 Bug Fixes

- [`3139a6f`](https://github.com/Project-Outlands/Outlands/commit/3139a6f28cdf54e8cc043d0d0e840e4a3897bc69) - Use regular full screen on Linux, fix #50 *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`1070ad1`](https://github.com/Project-Outlands/Outlands/commit/1070ad178a86208f2ec9bfaa220382060d267d99) - Fix serveral issue on installer crafting and publish *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*

### ⚙️ Miscellaneous Tasks

- [`f339d98`](https://github.com/Project-Outlands/Outlands/commit/f339d98d5fc998f127dacbf491162483c60c480e) - Merge branch 'main' of github.com:Project-Outlands/Outlands *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`ccf333d`](https://github.com/Project-Outlands/Outlands/commit/ccf333dbd2131897d73970a32de55e5004535cac) - Prefer wayland instead of X11 when possible *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*


## [2026.11.05-2] - 2026-11-05

### 🚀 Features

- [`adc95cf`](https://github.com/Project-Outlands/Outlands/commit/adc95cf57d89767430249d399da926c57cbd7f82) - New crash report enhance interface *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*

### 🐛 Bug Fixes

- [`18754c8`](https://github.com/Project-Outlands/Outlands/commit/18754c8f3b00fd047418f0c81e0f93e822a2be6e) - *(display)* Release the GL provider when a display create fails *(PR [#49](https://github.com/Project-Outlands/Outlands/pull/49) by [@DHJComical](https://github.com/DHJComical))*

### 🚜 Refactor

- [`8f37529`](https://github.com/Project-Outlands/Outlands/commit/8f375290fbaaa0f8a7cfe560a939bb1bb4d8d0b3) - *(brand)* Simply replaced idea project icon with outlands icon *(PR [#48](https://github.com/Project-Outlands/Outlands/pull/48) by [@Gebardensprache](https://github.com/Gebardensprache))*


## [2026.11.05] - 2026-11-05
### New Features
- [`ab5ad7d`](https://github.com/Project-Outlands/Outlands/commit/ab5ad7da937a7e9c02d868cd58b07802e311d91f) - add entity roll axis, mouse turn event and frame clock *(PR [#46](https://github.com/Project-Outlands/Outlands/pull/46) by [@DHJComical](https://github.com/DHJComical))*

### Bug Fixes
- [`6ca2d2e`](https://github.com/Project-Outlands/Outlands/commit/6ca2d2ea546ade9be63de5f054ad26e5096f316a) - **config**: do not force the loader to load from the config directory lookup *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`9485511`](https://github.com/Project-Outlands/Outlands/commit/948551137b0e9a512a081746bce9c1166fb65243) - **transformers**: close two gaps in the bytecode transform layer *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`f114e92`](https://github.com/Project-Outlands/Outlands/commit/f114e929ea7d1b6cf9f20a081b0672bd5d38645b) - **windows**: make the Mica window style reach the window *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*

### Refactors
- [`4a3a584`](https://github.com/Project-Outlands/Outlands/commit/4a3a5849101580e128cbd9c63b012338684ed80a) - **taskbar**: drop the COM taskbar API for SDL's *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`bf7259f`](https://github.com/Project-Outlands/Outlands/commit/bf7259f321c6f25a579e830567f5601098bf50f8) - Remove legacy class for Fugue remap target *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`5251ecd`](https://github.com/Project-Outlands/Outlands/commit/5251ecdc57e4471c8efa8da19e34bdea11c87c6a) - **fml**: Merge more duplicated code from old era *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`285191a`](https://github.com/Project-Outlands/Outlands/commit/285191ab5e3993dbe5dcb9d6ac7cdb5ea39c82c0) - **config**: Move ForgeEarlyConfig into one forge.cfg *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`aab7be1`](https://github.com/Project-Outlands/Outlands/commit/aab7be1cbb8f089caf99c4893e400e3789001fd6) - **splash**: Remove Fugue/Scalar uninstalled warning *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*

### Tests
- [`7deb3bf`](https://github.com/Project-Outlands/Outlands/commit/7deb3bfed8de7523f1121ae0e414985f46928fd9) - **install**: test both record kinds the patch format has *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*

### Chores
- [`a102079`](https://github.com/Project-Outlands/Outlands/commit/a1020798e0f615a871727962c791079e26a41dc4) - Update Foundation *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*


## [2026.11.03-2] - 2026-11-03

### 🚀 Features

- [`ab5ad7d`](https://github.com/Project-Outlands/Outlands/commit/ab5ad7da937a7e9c02d868cd58b07802e311d91f) - Add entity roll axis, mouse turn event and frame clock *(PR [#46](https://github.com/Project-Outlands/Outlands/pull/46) by [@DHJComical](https://github.com/DHJComical))*

### 🚜 Refactor

- [`aab7be1`](https://github.com/Project-Outlands/Outlands/commit/aab7be1cbb8f089caf99c4893e400e3789001fd6) - *(splash)* Remove Fugue/Scalar uninstalled warning *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`285191a`](https://github.com/Project-Outlands/Outlands/commit/285191ab5e3993dbe5dcb9d6ac7cdb5ea39c82c0) - *(config)* Move ForgeEarlyConfig into one forge.cfg *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`5251ecd`](https://github.com/Project-Outlands/Outlands/commit/5251ecdc57e4471c8efa8da19e34bdea11c87c6a) - *(fml)* Merge more duplicated code from old era *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*


## [2026.11.03] - 2026-11-03
### New Features
- [`04a90a4`](https://github.com/Project-Outlands/Outlands/commit/04a90a4e332f2f87b6d61cc8beb8d9220f72986c) - declare mixin configurations in the jar section *(PR [#47](https://github.com/Project-Outlands/Outlands/pull/47) by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`c0a2b1a`](https://github.com/Project-Outlands/Outlands/commit/c0a2b1a2df5c795c66eb6eb95477405fc1da013a) - declare dependencies in a mod entry as one object per dependency *(PR [#45](https://github.com/Project-Outlands/Outlands/pull/45) by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`dc77147`](https://github.com/Project-Outlands/Outlands/commit/dc771473a68c8b30bbd0602a3bc84c59df08ff70) - read dependency lists from the entry whenever the jar section is declared *(PR [#44](https://github.com/Project-Outlands/Outlands/pull/44) by [@kappa-maintainer](https://github.com/kappa-maintainer))*

### Bug Fixes
- [`691fab8`](https://github.com/Project-Outlands/Outlands/commit/691fab8b70cfd656001d7504095f930d9b959fc0) - read the jar section with the types it declares *(PR [#43](https://github.com/Project-Outlands/Outlands/pull/43) by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`ceba6d2`](https://github.com/Project-Outlands/Outlands/commit/ceba6d2a2374c0282a0a021c3893bc7afb50f3db) - extract the bundled jars of mods that belong to no mod list *(PR [#42](https://github.com/Project-Outlands/Outlands/pull/42) by [@kappa-maintainer](https://github.com/kappa-maintainer))*


## [2026.11.02-2] - 2026-11-02

### 🚀 Features

- [`dc77147`](https://github.com/Project-Outlands/Outlands/commit/dc771473a68c8b30bbd0602a3bc84c59df08ff70) - Read dependency lists from the entry whenever the jar section is declared *(PR [#44](https://github.com/Project-Outlands/Outlands/pull/44) by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`c0a2b1a`](https://github.com/Project-Outlands/Outlands/commit/c0a2b1a2df5c795c66eb6eb95477405fc1da013a) - Declare dependencies in a mod entry as one object per dependency *(PR [#45](https://github.com/Project-Outlands/Outlands/pull/45) by [@kappa-maintainer](https://github.com/kappa-maintainer))*

### 🐛 Bug Fixes

- [`ceba6d2`](https://github.com/Project-Outlands/Outlands/commit/ceba6d2a2374c0282a0a021c3893bc7afb50f3db) - Extract the bundled jars of mods that belong to no mod list *(PR [#42](https://github.com/Project-Outlands/Outlands/pull/42) by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`691fab8`](https://github.com/Project-Outlands/Outlands/commit/691fab8b70cfd656001d7504095f930d9b959fc0) - Read the jar section with the types it declares *(PR [#43](https://github.com/Project-Outlands/Outlands/pull/43) by [@kappa-maintainer](https://github.com/kappa-maintainer))*


## [2026.11.02] - 2026-11-02
### New Features
- [`40bcfbb`](https://github.com/Project-Outlands/Outlands/commit/40bcfbb593b2fb639f505245447db8d78e5a4a3c) - move the coremod keys into the jar section and read mcmod.info once *(PR [#41](https://github.com/Project-Outlands/Outlands/pull/41) by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`5f7d5e0`](https://github.com/Project-Outlands/Outlands/commit/5f7d5e005c461dc6cb2afc1ad5db5189c75e136b) - move ModSide and FMLAT into the mcmod.info jar section *(PR [#40](https://github.com/Project-Outlands/Outlands/pull/40) by [@kappa-maintainer](https://github.com/kappa-maintainer))*

### Bug Fixes
- [`6a4f380`](https://github.com/Project-Outlands/Outlands/commit/6a4f380d6a1ae37bc1e3365260900334bb7416bc) - let jar.containedDeps drive extraction on its own *(PR [#39](https://github.com/Project-Outlands/Outlands/pull/39) by [@kappa-maintainer](https://github.com/kappa-maintainer))*


## [2026.11.01] - 2026-11-01
### BREAKING CHANGES
- due to [`54df574`](https://github.com/Project-Outlands/Outlands/commit/54df574b281be65f4ae7ac8fa6c3877fd1e1a6fc) - drop Bansoukou support *(PR [#15](https://github.com/Project-Outlands/Outlands/pull/15) by [@kappa-maintainer](https://github.com/kappa-maintainer))*:

  the `Bansoukou` manifest attribute is no longer honoured. A jar  
  declaring it still loads, but its class replacements are not applied. Packs that  
  relied on it should move to mixins. Each affected jar gets one explicit warning,  
  emitted from `ModDiscoverer.scan` where the manifest is already in hand rather  
  than from `getCandidates`, which would have to open every jar just to look.  
  `getCandidates()` is now a pure function - reads directories, sorts, returns an  
  immutable list - so it no longer touches the classloader at all.


### New Features
- [`21d3c7b`](https://github.com/Project-Outlands/Outlands/commit/21d3c7b9de4c136d428307f7cf8cde7c58542357) - decide the LWJGL2 rewrite from the mcmod schema, not from ModType *(PR [#38](https://github.com/Project-Outlands/Outlands/pull/38) by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`63d78b1`](https://github.com/Project-Outlands/Outlands/commit/63d78b18165deccc093cca56779f29d39ab8508a) - **loader**: the mod list, its assets and the loader id move to outlands *(PR [#34](https://github.com/Project-Outlands/Outlands/pull/34) by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`99beb8c`](https://github.com/Project-Outlands/Outlands/commit/99beb8ca49277ae038b6c2984a2f310b26efab34) - **config**: formats behind one model + ConfigEnvironment *(PR [#29](https://github.com/Project-Outlands/Outlands/pull/29) by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`b6dd8b7`](https://github.com/Project-Outlands/Outlands/commit/b6dd8b7d4a8f81db22e13a8a4b60228d705d55e8) - **loader**: let a jar declare mods without mod classes (B10) *(PR [#25](https://github.com/Project-Outlands/Outlands/pull/25) by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`c51f3db`](https://github.com/Project-Outlands/Outlands/commit/c51f3dbe451f05dc9e5153164d656c7cef4f0a65) - **loader**: allow one level of subdirectories in the mods folder (B12) *(PR [#24](https://github.com/Project-Outlands/Outlands/pull/24) by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`01a4dc6`](https://github.com/Project-Outlands/Outlands/commit/01a4dc6a2f2869f441b5cc7266cd5a2914240664) - **metadata**: declare bundled libraries with an identity, and keep them out of mods/ *(PR [#23](https://github.com/Project-Outlands/Outlands/pull/23) by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`05b83f5`](https://github.com/Project-Outlands/Outlands/commit/05b83f5a745e2056ff5c02b80711d6d44f52092b) - **metadata**: check modLanguageVersion against what a provider supports *(PR [#20](https://github.com/Project-Outlands/Outlands/pull/20) by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`a1d3d35`](https://github.com/Project-Outlands/Outlands/commit/a1d3d358258d80f991bdaa28a931c1273129ec4b) - **metadata**: let a jar declare the language providers it ships *(PR [#18](https://github.com/Project-Outlands/Outlands/pull/18) by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`8dc9112`](https://github.com/Project-Outlands/Outlands/commit/8dc9112cff327234296ae85452f0c96f82142af7) - **metadata**: let a mod declare its language in mcmod.info (#B8) *(PR [#17](https://github.com/Project-Outlands/Outlands/pull/17) by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`cfffa65`](https://github.com/Project-Outlands/Outlands/commit/cfffa65d0c35b533f7da73b4556a450bb0a94309) - **metadata**: let a jar declare where it loads among coremods *(PR [#14](https://github.com/Project-Outlands/Outlands/pull/14) by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`4c47a11`](https://github.com/Project-Outlands/Outlands/commit/4c47a11f41e77c5a577da3b86ea594e4d8afd984) - **metadata**: read a jar-level declaration from mcmod.info *(PR [#13](https://github.com/Project-Outlands/Outlands/pull/13) by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`860b37c`](https://github.com/Project-Outlands/Outlands/commit/860b37ce310b3a383dfa217756041306bc6bc709) - **event**: let the Forge event bus take listeners without an annotated method *(PR [#11](https://github.com/Project-Outlands/Outlands/pull/11) by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`8a700e0`](https://github.com/Project-Outlands/Outlands/commit/8a700e0b2cbbeb336d785102b67e703b5cd68adb) - **loader**: add frozen Registry and move language adapters onto it *(PR [#10](https://github.com/Project-Outlands/Outlands/pull/10) by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`6b07719`](https://github.com/Project-Outlands/Outlands/commit/6b077199ba19f744b4bff4d1810c357dd04b01c0) - **commands**: make modern completion opt-in *(PR [#6](https://github.com/Project-Outlands/Outlands/pull/6) by [@DHJComical](https://github.com/DHJComical))*

### Bug Fixes
- [`b0243ed`](https://github.com/Project-Outlands/Outlands/commit/b0243ed456ab83dc64a6385328aa416a33ad0c4d) - **loader**: bundled jars stay next to the mods, marked per entry *(PR [#26](https://github.com/Project-Outlands/Outlands/pull/26) by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`588fde8`](https://github.com/Project-Outlands/Outlands/commit/588fde8739d2deb390c8986f7768b9ea7ed36467) - **window**: continue when SDL window icons are unsupported *(PR [#7](https://github.com/Project-Outlands/Outlands/pull/7) by [@DHJComical](https://github.com/DHJComical))*
- [`137cc78`](https://github.com/Project-Outlands/Outlands/commit/137cc78b4c0ebcacb40472806380add8f6cbb560) - **opengl**: expose GL46 in LWJGL2 capabilities *(PR [#8](https://github.com/Project-Outlands/Outlands/pull/8) by [@DHJComical](https://github.com/DHJComical))*
- [`ff30ecd`](https://github.com/Project-Outlands/Outlands/commit/ff30ecdea1d1e2acd42270f9da1e3b85be15393f) - **metadata**: handle empty, non-container and modList-less mcmod.info *(PR [#5](https://github.com/Project-Outlands/Outlands/pull/5) by [@kappa-maintainer](https://github.com/kappa-maintainer))*

### Performance Improvements
- [`d3f29b4`](https://github.com/Project-Outlands/Outlands/commit/d3f29b4abdd6cd02a634c154560be849d7bc1d2b) - **event**: derive listener lists and flags from ClassValue, drop the transformer *(PR [#16](https://github.com/Project-Outlands/Outlands/pull/16) by [@kappa-maintainer](https://github.com/kappa-maintainer))*

### Refactors
- [`d8c9aa6`](https://github.com/Project-Outlands/Outlands/commit/d8c9aa6e55fa782d6424b16e6c718602d844ed0c) - **loader**: one registration entry point for language adapters *(PR [#19](https://github.com/Project-Outlands/Outlands/pull/19) by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`18c1b29`](https://github.com/Project-Outlands/Outlands/commit/18c1b296e238a1cabb593833c9be4afdaf66934b) - **loader**: separate "no mod object" from "failed to construct" *(PR [#9](https://github.com/Project-Outlands/Outlands/pull/9) by [@kappa-maintainer](https://github.com/kappa-maintainer))*

### Tests
- [`4651772`](https://github.com/Project-Outlands/Outlands/commit/4651772f4669c73ea8270d6eb67a5e8a9be8e022) - **discovery**: cover annotations shared by mods from one jar *(PR [#4](https://github.com/Project-Outlands/Outlands/pull/4) by [@kappa-maintainer](https://github.com/kappa-maintainer))*

### Chores
- [`3074dbb`](https://github.com/Project-Outlands/Outlands/commit/3074dbb2f18e4e7ff2a36112f70a0f729e6d92a9) - drop the Bansoukou warning *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`ae1beeb`](https://github.com/Project-Outlands/Outlands/commit/ae1beebf7f42fdf9c08a5c01d8c4639b65b109ae) - replace the loader artwork with the Outlands mark *(PR [#37](https://github.com/Project-Outlands/Outlands/pull/37) by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`871c6b2`](https://github.com/Project-Outlands/Outlands/commit/871c6b22e18b8bbdb07faf1a95222fff72cf886f) - relicense the two visible-source headers as Outlands *(PR [#36](https://github.com/Project-Outlands/Outlands/pull/36) by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`99bbea5`](https://github.com/Project-Outlands/Outlands/commit/99bbea577028091e561499f370b5100e4726c589) - **loader**: stop pointing the loader at another project, and rebrand the repo metadata *(PR [#35](https://github.com/Project-Outlands/Outlands/pull/35) by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`f339f80`](https://github.com/Project-Outlands/Outlands/commit/f339f803853b70e25c037cb3f0183db8dd48c93f) - drop the Cleanroom name from code that no contract depends on *(PR [#33](https://github.com/Project-Outlands/Outlands/pull/33) by [@kappa-maintainer](https://github.com/kappa-maintainer))*


## [2026.10.29] - 2026-10-29
### New Features
- [`b782a32`](https://github.com/Project-Outlands/Outlands/commit/b782a32e850ef5e0f30f52b9c987bfc2360c9033) - **input**: support arbitrary key combinations *(commit by [@DHJComical](https://github.com/DHJComical))*

### Chores
- [`4d7fc53`](https://github.com/Project-Outlands/Outlands/commit/4d7fc536fb850989cb2b2d85691e8825ddf52f9b) - Merge branch 'main' of github.com:Project-Outlands/Outlands *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*

### Other Changes
- [`410e83d`](https://github.com/Project-Outlands/Outlands/commit/410e83df9d6b9d4f9d5f4d99af104ab6291a9f6b) - Merge branch 'feat/key-combinations'

The branch and the work here solved the same problem in different places:
it models a binding as one trigger key plus the keys that must be held, and
snapshots the entire keyboard and mouse at the moment an event is queued, so
a combination can be checked against the state that produced it rather than
the state that happens to be current when the game looks. That model is
strictly more general than the one here - the modifiers stay logical, so Ctrl
means either side of it, and any key or mouse button can be part of a
combination - so it replaces it.

What it brings that was missing here: recording a combination in the controls
screen (press keys, release them all, that is the combination), the controls
list showing the short form with the full one in the tooltip, conflict
severity told apart from a plain clash, the Windows case where Space never
reaches the game because the input method ate it, and the queue keeping the
newest release when it fills instead of dropping events silently.

Three things from here are kept on top of it:

- a key event names a key by the layout's key code, not by its physical
  position. The physical key travels with the event for anyone who wants it,
  but a binding means the key the player sees on their keycaps, and that is
  what every mod written against LWJGL2 expects.
- repeat events are off unless a screen asks for them, which is what LWJGL2
  did. The branch handles repeats where they reach key bindings; this keeps
  them from being produced in the first place.
- firing every equally specific binding stayed a switch, because a mod that
  binds a key another mod already uses can depend on exactly one of them
  running.

The input method key folds into the branch's version: it is a real
combination now, so the controls screen names it, and the options file
migrates the old half-a-combination form to a full one. *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`9f2dd32`](https://github.com/Project-Outlands/Outlands/commit/9f2dd3254eb75373f86df86c8998c826c7922e1c) - hackery, util: finish the move of the remaining loader helpers

The deletions of the old paths that the earlier commits left behind: hackery,
the two compatibility shims and CleanroomLog now live under
net.minecraftforge.* only. *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*


## [2026.10.26] - 2026-10-26
### New Features
- [`7b50ba1`](https://github.com/Project-Outlands/Outlands/commit/7b50ba1e6ab699e4ce16cd70fff5bf70029f0cfe) - Switch to SDL from GLFW *(PR [#3](https://github.com/Project-Outlands/Outlands/pull/3) by [@kappa-maintainer](https://github.com/kappa-maintainer))*

### Bug Fixes
- [`c62d4da`](https://github.com/Project-Outlands/Outlands/commit/c62d4da3e40e0f691cc54a22c7d428bea280559c) - **release**: do not read a token that cannot see releases as 'release missing' *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*

### Chores
- [`1d3c4d3`](https://github.com/Project-Outlands/Outlands/commit/1d3c4d3d0d08369a578a88efe1944c377edcf290) - Merge branch 'main' of github.com:Project-Outlands/Outlands *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*


## [2026.10.25-3] - 2026-10-25

### 💼 Other

- [`bde07a3`](https://github.com/Project-Outlands/Outlands/commit/bde07a3ee2cf7f244c9702b79015c094387fc635) - Point the forge slot template at the real main class *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`fc7ab14`](https://github.com/Project-Outlands/Outlands/commit/fc7ab1465f4ef7e84a44c5236b4a9e4e2f123b4a) - Patch the game once, into an overlay jar, instead of on every launch *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`0ad85f1`](https://github.com/Project-Outlands/Outlands/commit/0ad85f1ad13243944bf172ab252eaa70e0415dc1) - Give the renamer the loader's name space *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`7e90778`](https://github.com/Project-Outlands/Outlands/commit/7e90778f0cc100b82e99cd126223b99a51a4aa7f) - Pin the foundation release that carries the install step *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*


## [2026.10.25-2] - 2026-10-25

### 🐛 Bug Fixes

- [`c62d4da`](https://github.com/Project-Outlands/Outlands/commit/c62d4da3e40e0f691cc54a22c7d428bea280559c) - *(release)* Do not read a token that cannot see releases as 'release missing' *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*

### ⚙️ Miscellaneous Tasks

- [`1d3c4d3`](https://github.com/Project-Outlands/Outlands/commit/1d3c4d3d0d08369a578a88efe1944c377edcf290) - Merge branch 'main' of github.com:Project-Outlands/Outlands *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*


## [2026.10.25] - 2026-10-25
### New Features
- [`4c84b73`](https://github.com/Project-Outlands/Outlands/commit/4c84b73631921d0fc7125b3d46c61e4078d2a95a) - read outlands.dev.extrapath, keep crl.dev.extrapath working *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*

### Bug Fixes
- [`03949b6`](https://github.com/Project-Outlands/Outlands/commit/03949b686063f55a14f8e8e017aa72c56bc7cc4b) - **build**: patch Minecraft out of the vanilla jar, not out of our own copy *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*

### Refactors
- [`9ce9d42`](https://github.com/Project-Outlands/Outlands/commit/9ce9d42400ca9baafb36b60a35aa2bec9de1f7cb) - drop the crl.dev.extrapath fallback *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*


## [0.6.13-alpha] - 2026-09-12
### Performance Improvements
- [`381edb2`](https://github.com/CleanroomMC/Cleanroom/commit/381edb20f7c7c86596a3efe129dc9927da67b7f1) - **asm**: Precheck annotation in SideTransformer *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*

### Refactors
- [`6bc8dd7`](https://github.com/CleanroomMC/Cleanroom/commit/6bc8dd7bf8d896fc715bf86770cc6b095f9212ad) - **mod-list**: clean up impl mess *(PR [#630](https://github.com/CleanroomMC/Cleanroom/pull/630) by [@RuiXuqi](https://github.com/RuiXuqi))*


## [0.6.12-alpha] - 2026-08-26
### Bug Fixes
- [`fe78db8`](https://github.com/CleanroomMC/Cleanroom/commit/fe78db8dc4fcee47df7549230858f4c064208d73) - **startup**: query finish call `onGuiClosed` *(PR [#615](https://github.com/CleanroomMC/Cleanroom/pull/615) by [@Ecdcaeb](https://github.com/Ecdcaeb))*
  - *fixes issue [#613](https://github.com/CleanroomMC/Cleanroom/issues/613) opened by [@ukmojb](https://github.com/ukmojb)*
- [`ba3d776`](https://github.com/CleanroomMC/Cleanroom/commit/ba3d7766504dbace768b19e8872b80a3ce6eb194) - **startup**: update confirm method to handle null results *(PR [#614](https://github.com/CleanroomMC/Cleanroom/pull/614) by [@Ecdcaeb](https://github.com/Ecdcaeb))*
- [`f9dd525`](https://github.com/CleanroomMC/Cleanroom/commit/f9dd525e1d06c66a92b163b8d106a5669d326bd5) - **discovery**: resolve [#620](https://github.com/CleanroomMC/Cleanroom/pull/620) by filtering null/blank mod ids *(commit by [@Rongmario](https://github.com/Rongmario))*


## [0.6.11-alpha] - 2026-08-17
### New Features
- [`4ba1434`](https://github.com/CleanroomMC/Cleanroom/commit/4ba1434f0b5110d8a55f08c88d92f237ed0b9ba7) - Add side annotation to Config *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`37281f3`](https://github.com/CleanroomMC/Cleanroom/commit/37281f3960318d4beb626108d5d8dda609988289) - add debounce for progress *(commit by [@baka-gourd](https://github.com/baka-gourd))*

### Bug Fixes
- [`1e33d2f`](https://github.com/CleanroomMC/Cleanroom/commit/1e33d2f7792c6bbacb20b43ea2283e580fcd03ee) - **mod-list**: query crashing when trying to find icons for mods via creative tabs *(commit by [@Rongmario](https://github.com/Rongmario))*
- [`f8f27e4`](https://github.com/CleanroomMC/Cleanroom/commit/f8f27e444a1020889b957ef4484c110144a954d0) - **mod-list**: compatible with OptiFine once again, resolves [#600](https://github.com/CleanroomMC/Cleanroom/pull/600) *(commit by [@Rongmario](https://github.com/Rongmario))*
- [`35bac1a`](https://github.com/CleanroomMC/Cleanroom/commit/35bac1a06d9cfdf70f6781de5af4d4d2d5e259c9) - **discovery**: add explicit support `Mod Director` and `JSON Paintings` *(PR [#610](https://github.com/CleanroomMC/Cleanroom/pull/610) by [@Ecdcaeb](https://github.com/Ecdcaeb))*
- [`80d028f`](https://github.com/CleanroomMC/Cleanroom/commit/80d028f6da1f4afca1da905a4481bd4d678516cc) - cleanroomClient crash on macOS due to an unexpected exclusion of java-objc-bridge-1.2 *(PR [#605](https://github.com/CleanroomMC/Cleanroom/pull/605) by [@kappa-maintainer](https://github.com/kappa-maintainer))*

### Refactors
- [`aa85df8`](https://github.com/CleanroomMC/Cleanroom/commit/aa85df87ab81e477022d81295899552e4127909a) - **ui**: unify `openFile` calls *(PR [#601](https://github.com/CleanroomMC/Cleanroom/pull/601) by [@RuiXuqi](https://github.com/RuiXuqi))*
- [`e6ef031`](https://github.com/CleanroomMC/Cleanroom/commit/e6ef0313b38f3ab73c1ef7eee2e7eaffb279e3d4) - **discovery**: use `MetadataCollection::from` to parse `mcmod.info` files *(PR [#595](https://github.com/CleanroomMC/Cleanroom/pull/595) by [@Ecdcaeb](https://github.com/Ecdcaeb))*


## [0.6.10-alpha] - 2026-08-10
### New Features
- [`8d4d83a`](https://github.com/CleanroomMC/Cleanroom/commit/8d4d83a081ed99249cbbb81cf953d356c3d8c9c7) - **commands**: modern suggestions, completions, argument highlighting *(PR [#557](https://github.com/CleanroomMC/Cleanroom/pull/557) by [@Rongmario](https://github.com/Rongmario))*

### Bug Fixes
- [`90669ae`](https://github.com/CleanroomMC/Cleanroom/commit/90669ae4790d03d715e2c21d0560f6050e8740c8) - **commands**: only display visible amount of command suggestions *(commit by [@Rongmario](https://github.com/Rongmario))*
- [`628ab32`](https://github.com/CleanroomMC/Cleanroom/commit/628ab3278ecfebe3ad29114e0ef34ebbf81e2cc4) - **cleanmix**: update to 0.7.2 to fix [#603](https://github.com/CleanroomMC/Cleanroom/pull/603) *(commit by [@Rongmario](https://github.com/Rongmario))*
- [`67b5788`](https://github.com/CleanroomMC/Cleanroom/commit/67b57883dc0249ec7d473c273616191fbd1179dd) - **discovery**: duplicate sites on loading crl.dev.mixin configs *(commit by [@Rongmario](https://github.com/Rongmario))*

### Chores
- [`6687ae9`](https://github.com/CleanroomMC/Cleanroom/commit/6687ae94d66342a9f2195859918ccbb467da79ec) - Update Gradle to 9.7.0 *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*


## [0.6.9-alpha] - 2026-08-08
### Bug Fixes
- [`7874a07`](https://github.com/CleanroomMC/Cleanroom/commit/7874a07403d2188347720f775cfb051da62ac04e) - **os**: open files the intended way *(commit by [@Rongmario](https://github.com/Rongmario))*
- [`f5dd105`](https://github.com/CleanroomMC/Cleanroom/commit/f5dd1055ab49173078f470e287ebd69c8851c7d5) - **discovery**: queues crl.dev.mixin configurations later, resolves [#599](https://github.com/CleanroomMC/Cleanroom/pull/599) *(commit by [@Rongmario](https://github.com/Rongmario))*
- [`75f2065`](https://github.com/CleanroomMC/Cleanroom/commit/75f20656a99091f9f591ca1b47fe4487c2cd7244) - **console**: guard console appender from writing to a closed terminal during shutdown *(PR [#579](https://github.com/CleanroomMC/Cleanroom/pull/579) by [@mvanhorn](https://github.com/mvanhorn))*
  - *fixes issue [#566](https://github.com/CleanroomMC/Cleanroom/issues/566) opened by [@NoDMoon](https://github.com/NoDMoon)*
- [`41047ab`](https://github.com/CleanroomMC/Cleanroom/commit/41047ab1c3aa21b5002792b8b8461e98b6fb9f53) - **discovery**: always scan Mod annotation *(PR [#598](https://github.com/CleanroomMC/Cleanroom/pull/598) by [@friendlyhj](https://github.com/friendlyhj))*
- [`fd78c16`](https://github.com/CleanroomMC/Cleanroom/commit/fd78c16fee1841d1765f80d738b527f3f57f7993) - patchy jar may crash cleanroomClient in some case *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`388e90f`](https://github.com/CleanroomMC/Cleanroom/commit/388e90fd856d50edfde6deec798ebda06b6ce14b) - Should fix [#576](https://github.com/CleanroomMC/Cleanroom/pull/576) can't place block agains modded grass *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`2e756de`](https://github.com/CleanroomMC/Cleanroom/commit/2e756de88838c767665cf05f1749832619040887) - **mod-list**: issues fixed pulled in due to author nagging over and over and over again *(PR [#592](https://github.com/CleanroomMC/Cleanroom/pull/592) by [@RuiXuqi](https://github.com/RuiXuqi))*

### Chores
- [`9d9ea47`](https://github.com/CleanroomMC/Cleanroom/commit/9d9ea47b03dcb9798656a092ffa6e88a78e0e694) - **cleanmix**: update to 0.7.1 *(commit by [@Rongmario](https://github.com/Rongmario))*
- [`5fba066`](https://github.com/CleanroomMC/Cleanroom/commit/5fba06625520d52ec7248edd5dcc861eefffbb57) - Update lwjglxx to fix macos ctrl keybind *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*


## [0.6.8-alpha] - 2026-07-28
### Bug Fixes
- [`9946eb1`](https://github.com/CleanroomMC/Cleanroom/commit/9946eb1f17a66a72d518e5e4a92d45c62c6d33fc) - **mixinextras**: allows late ME environments to register with no issue *(commit by [@Rongmario](https://github.com/Rongmario))*

### Performance Improvements
- [`5df027d`](https://github.com/CleanroomMC/Cleanroom/commit/5df027d89e6ffcab116d271a3c1329dfc67811da) - Rewrite EventSubscriptionTransformer in core API *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`6956d37`](https://github.com/CleanroomMC/Cleanroom/commit/6956d37ecd1b4397de97e226272b290549239e1a) - Optimized EventSubscriptionTransformer by reading super w/ ClassReader *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*

### Chores
- [`bdc1436`](https://github.com/CleanroomMC/Cleanroom/commit/bdc14364564ae1fd280804e5e075a3b77c2af9a2) - Update foundation for new loading rules *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*


## [0.6.7-alpha] - 2026-07-26
### Bug Fixes
- [`2d83b07`](https://github.com/CleanroomMC/Cleanroom/commit/2d83b071fc959a4885eb1049258bb6900d7ff4d5) - **cleanmix**: update to 0.7.0, fixes variety of issues *(commit by [@Rongmario](https://github.com/Rongmario))*

### Chores
- [`74e8f02`](https://github.com/CleanroomMC/Cleanroom/commit/74e8f02b561f41bc2adaf45dfa58e3c3e03641e4) - **discovery**: more expressive logging when failing to read metadata *(commit by [@Rongmario](https://github.com/Rongmario))*


## [0.6.6-alpha] - 2026-07-24
### Chores
- [`75e899b`](https://github.com/CleanroomMC/Cleanroom/commit/75e899b8a56ad5f1d97dc2e9e2586bb8c69022d0) - **foundation**: update to include `com.mojang.` to classloader *(commit by [@Rongmario](https://github.com/Rongmario))*
- [`57ded78`](https://github.com/CleanroomMC/Cleanroom/commit/57ded78b9067913c8cdcb91d890cab521c10b070) - **cleanmix**: update mixinbooter's version *(commit by [@Rongmario](https://github.com/Rongmario))*


## [0.6.5-alpha] - 2026-07-24
### New Features
- [`25f1c0f`](https://github.com/CleanroomMC/Cleanroom/commit/25f1c0f0640f9feb0fe6ca58b7ad85aca0489fbe) - **cleanmix**: update to 0.6.0, more effort for backwards compatibility *(commit by [@Rongmario](https://github.com/Rongmario))*

### Bug Fixes
- [`dfd2bca`](https://github.com/CleanroomMC/Cleanroom/commit/dfd2bcaf4154fc660d053dfac38cc86ba7dd1f30) - **discovery**: removed unused mixin configs parameter *(commit by [@Rongmario](https://github.com/Rongmario))*
- [`a4bc796`](https://github.com/CleanroomMC/Cleanroom/commit/a4bc796c0074168f0f72ae8680af251fab716c77) - **discovery**: unable to grab mod ids from certain metadata files *(commit by [@Rongmario](https://github.com/Rongmario))*

### Performance Improvements
- [`044356b`](https://github.com/CleanroomMC/Cleanroom/commit/044356b4d7c2693cf1c97b6c7b74e97885308c6e) - **discovery**: remove additional candidate deduplication check *(commit by [@Rongmario](https://github.com/Rongmario))*
- [`3ac1213`](https://github.com/CleanroomMC/Cleanroom/commit/3ac121329bc156367fc14093bd06f2d5c785afbb) - **discovery**: optimized deduplication checks for mod candidates *(commit by [@Rongmario](https://github.com/Rongmario))*
- [`f35a674`](https://github.com/CleanroomMC/Cleanroom/commit/f35a674d6cb6b24686e9a8e0981f79cef11197e8) - **discovery**: faster deduplication checks when gathering mods *(commit by [@Rongmario](https://github.com/Rongmario))*
- [`6fe1114`](https://github.com/CleanroomMC/Cleanroom/commit/6fe1114f410c22f86e2ad9cdaab9cca2d7d04f19) - **discovery**: walk directory once to find derped installations *(commit by [@Rongmario](https://github.com/Rongmario))*


## [0.6.4-alpha] - 2026-07-23
### Bug Fixes
- [`c001d07`](https://github.com/CleanroomMC/Cleanroom/commit/c001d07f9c41fe71347d297e5e6ee0553f4143e4) - **cleanmix**: updated to fix accessors to eagerly load their targets *(commit by [@Rongmario](https://github.com/Rongmario))*


## [0.6.3-alpha] - 2026-07-22
### New Features
- [`f0ef797`](https://github.com/CleanroomMC/Cleanroom/commit/f0ef79765c2dc044e07226be41dd527e2c1408ab) - **version check**: add header of cleanroom *(commit by [@baka-gourd](https://github.com/baka-gourd))*

### Bug Fixes
- [`daee859`](https://github.com/CleanroomMC/Cleanroom/commit/daee859456fc067caa1cc0beef1b4827e19e124d) - **cleanmix**: updated to fix various mixin loading bugs *(commit by [@Rongmario](https://github.com/Rongmario))*
- [`64af19c`](https://github.com/CleanroomMC/Cleanroom/commit/64af19c3008c2533aca71260b862d85975baf6dd) - **discovery**: handle directory sources *(commit by [@Rongmario](https://github.com/Rongmario))*


## [0.6.2-alpha] - 2026-07-20
### Bug Fixes
- [`7e8bc27`](https://github.com/CleanroomMC/Cleanroom/commit/7e8bc270a3b0fb31161ee64976185fd3d0a0c1a0) - **discovery**: don't parse built-in mods as discovered files *(commit by [@Rongmario](https://github.com/Rongmario))*


## [0.6.1-alpha] - 2026-07-20
### Bug Fixes
- [`dcffd58`](https://github.com/CleanroomMC/Cleanroom/commit/dcffd580f565e0c0c310b5204f2241111c9c037e) - **mod-list**: scrollbar sensitive list width *(commit by [@Rongmario](https://github.com/Rongmario))*
- [`568757b`](https://github.com/CleanroomMC/Cleanroom/commit/568757bd70e0813ca983534c2da2e486d1ec11ae) - **mod-list**: consider scrollbar width when trimming text *(commit by [@Rongmario](https://github.com/Rongmario))*
- [`26bc611`](https://github.com/CleanroomMC/Cleanroom/commit/26bc6115d0cc2c41071443c4308a107bad8de7d4) - **mod-list**: set parent via `GuiModList`'s parent *(commit by [@Rongmario](https://github.com/Rongmario))*

### Chores
- [`d7e73ca`](https://github.com/CleanroomMC/Cleanroom/commit/d7e73ca03bb3a41666766552b066de76bd5ee21b) - **cleanmix**: update MixinBooter from 11.2 to 11.5 *(commit by [@Rongmario](https://github.com/Rongmario))*


## [0.6.0-alpha] - 2026-07-19
### New Features
- [`ed8e356`](https://github.com/CleanroomMC/Cleanroom/commit/ed8e3564f75eeefa698766eb25ff50c136836960) - **cleanmix**: achieve parity with MixinBooter 11 *(PR [#583](https://github.com/CleanroomMC/Cleanroom/pull/583) by [@Rongmario](https://github.com/Rongmario))*
- [`bd4f552`](https://github.com/CleanroomMC/Cleanroom/commit/bd4f552b80d3089f9e8909a323261da0b1e09aad) - **mod-list**: implemented modern mod list UI *(PR [#589](https://github.com/CleanroomMC/Cleanroom/pull/589) by [@Rongmario](https://github.com/Rongmario))*

### Bug Fixes
- [`69f118c`](https://github.com/CleanroomMC/Cleanroom/commit/69f118cb4abe123d5e3f0a3365623474ff8e751a) - **mod-list**: add `cleanmix` to default catalogue libraries *(commit by [@Rongmario](https://github.com/Rongmario))*
- [`fbf9fc2`](https://github.com/CleanroomMC/Cleanroom/commit/fbf9fc255ed3940d0801d16b8416bd4ed9b6daaa) - **patch-mods**: check if patch mods are present outside of dev *(commit by [@Rongmario](https://github.com/Rongmario))*
- [`e01a298`](https://github.com/CleanroomMC/Cleanroom/commit/e01a29831376f15fa8f993605a81cf135514dacd) - vanilla json using broken objc-bridge *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*

### Performance Improvements
- [`6b8c126`](https://github.com/CleanroomMC/Cleanroom/commit/6b8c1260fec2af78aa1ed353e720ef8e3a61511e) - **discovery**: speed up `ASMDataTable::getAnnotationsFor` (O(n^2) -> O(n)) *(PR [#582](https://github.com/CleanroomMC/Cleanroom/pull/582) by [@romanfedyniak](https://github.com/romanfedyniak))*

### Refactors
- [`a476dbe`](https://github.com/CleanroomMC/Cleanroom/commit/a476dbe805853de6af764e2d85cd66beb2303532) - **mod-list**: re-situate related event listeners *(commit by [@Rongmario](https://github.com/Rongmario))*


## [0.5.17-alpha] - 2026-07-09
### Bug Fixes
- [`a436b61`](https://github.com/CleanroomMC/Cleanroom/commit/a436b61ad97099497ac8ebda48819bb66d833d86) - installer can't install server *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*


## [0.5.16-alpha] - 2026-07-09
### New Features
- [`db96747`](https://github.com/CleanroomMC/Cleanroom/commit/db967479f558d66f3121259b82056a6a044b3258) - New installer and client.json compat w/ official launcher *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`0fff847`](https://github.com/CleanroomMC/Cleanroom/commit/0fff847600fcca3a3fd5d1a95e9aa57230233000) - allow flash can be disabled *(commit by [@baka-gourd](https://github.com/baka-gourd))*
- [`83ea6a4`](https://github.com/CleanroomMC/Cleanroom/commit/83ea6a4b820d732f5aa6fde122a03a74002fa1c4) - early config now available in mod config menu *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*

### Bug Fixes
- [`c0e60dc`](https://github.com/CleanroomMC/Cleanroom/commit/c0e60dcebd3960fb871a147779d82ed78bbdf591) - fugue warning sometimes hang the game after pressed any key *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`b027c41`](https://github.com/CleanroomMC/Cleanroom/commit/b027c41715dedad0f5d7e407aafbaa2478cd1bef) - ignore relation of all built-in mods *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*

### Chores
- [`0d9caab`](https://github.com/CleanroomMC/Cleanroom/commit/0d9caab6d5547d86feb397872e1b39328fdcfd37) - actually call translationkey funcs in Fluid *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*


## [0.5.15-alpha] - 2026-06-29
### New Features
- [`b5abee9`](https://github.com/CleanroomMC/Cleanroom/commit/b5abee957b17fa405f99217ff9303ce60387bde1) - Warn users if Fugue or Scalar not installed *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`e545c03`](https://github.com/CleanroomMC/Cleanroom/commit/e545c037760efb34ed029834262348749810ce52) - Warn if Fugue or Scalar not installed *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*

### Bug Fixes
- [`8789256`](https://github.com/CleanroomMC/Cleanroom/commit/87892564cb7763f3e512d450ed2ed5c5bf914eb7) - Not wrans right after coremod *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*

### Chores
- [`c7f8e4c`](https://github.com/CleanroomMC/Cleanroom/commit/c7f8e4c206495c342b087a49103161507d628750) - Improve warning *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`6c09515`](https://github.com/CleanroomMC/Cleanroom/commit/6c09515e472be5583e418a614ac8874001b2ff3b) - update early config about framebuffer *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`e4aa30a`](https://github.com/CleanroomMC/Cleanroom/commit/e4aa30a8dcad341825ab4de90b03e3acf70f9dde) - Correct colon in zh_cn.lang *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`ddefbb7`](https://github.com/CleanroomMC/Cleanroom/commit/ddefbb72171261f5c7bb725cfc1d702e092c033a) - Fluid method names that fit latest mcp *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*


## [0.5.14-alpha] - 2026-06-10
### Bug Fixes
- [`43bcf47`](https://github.com/CleanroomMC/Cleanroom/commit/43bcf475eb350be2ea6b9b8e10153d8ecbabe29e) - userdev jar is broken *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*


## [0.5.13-alpha] - 2026-06-10
### Bug Fixes
- [`8843e7b`](https://github.com/CleanroomMC/Cleanroom/commit/8843e7b991f3ce08a555d116469fe51fdcc08ec0) - Can't discover in-dev mod when multi-langs *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*


## [0.5.12-alpha] - 2026-05-09
### Bug Fixes
- [`0058f2f`](https://github.com/CleanroomMC/Cleanroom/commit/0058f2fb9092c6909ca5409da1ca88dd11059f92) - lwjglxx and OpenGlHelper not using new oshi path *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*


## [0.5.11-alpha] - 2026-05-09
### Bug Fixes
- [`ecdd87b`](https://github.com/CleanroomMC/Cleanroom/commit/ecdd87bd33e2eb870bc264dd0407c4533450051e) - oshi now divided into two packages *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*

### Other Changes
- [`3d5d7df`](https://github.com/CleanroomMC/Cleanroom/commit/3d5d7dfb4d6bfb7fb679702eaf18b2903d0b0419) - Merge branch 'main' of github.com:CleanroomMC/Cleanroom *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*


## [0.5.10-alpha] - 2026-05-09
### Bug Fixes
- [`06824ad`](https://github.com/CleanroomMC/Cleanroom/commit/06824ad4e9761d60b52b2c25b3345d4947130a5c) - typesetting in FontRenderer *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*


## [0.5.9-alpha] - 2026-04-30
### New Features
- [`4a76d2f`](https://github.com/CleanroomMC/Cleanroom/commit/4a76d2f5b9ad89d3a786a95d2b56f263b9df6716) - aggresive loading tracker *(PR [#553](https://github.com/CleanroomMC/Cleanroom/pull/553) by [@baka-gourd](https://github.com/baka-gourd))*

### Other Changes
- [`e238bed`](https://github.com/CleanroomMC/Cleanroom/commit/e238bed837942573424ab12c02210afdec32ef59) - Update mixin extra *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`266851d`](https://github.com/CleanroomMC/Cleanroom/commit/266851da7d04c77e7f4373d2b4300fd78be66de7) - Merge branch 'main' of github.com:CleanroomMC/Cleanroom *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`7be307d`](https://github.com/CleanroomMC/Cleanroom/commit/7be307dc2ad50c2f8bb1e91a1360d3728dd4aea6) - Uncomment objc bridge *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*


## [0.5.8-alpha] - 2026-04-08
### Other Changes
- [`2a723e2`](https://github.com/CleanroomMC/Cleanroom/commit/2a723e2d47b6607942c3f209de364e716dadb4c6) - Update foundation *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`d027e4d`](https://github.com/CleanroomMC/Cleanroom/commit/d027e4d6a54eb33d779ebf13be8a5861525c16f1) - Update jline *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`1ebbe41`](https://github.com/CleanroomMC/Cleanroom/commit/1ebbe4193afb953350e1d231741f93c18241d60c) - Switch out from imaginebreaker *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`989612d`](https://github.com/CleanroomMC/Cleanroom/commit/989612dfa7333f16180cd7310c53b2b46cc3dc69) - Update deps *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`5ef9cf9`](https://github.com/CleanroomMC/Cleanroom/commit/5ef9cf9b093bea2934a3b6f5dfa432d5be952df1) - Update Foundation *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`173a914`](https://github.com/CleanroomMC/Cleanroom/commit/173a914ff4fad253d392c26d2d4253b5860c2a08) - Add dep for Reflect *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*
- [`f100bc8`](https://github.com/CleanroomMC/Cleanroom/commit/f100bc88b83daca1337c753a2a0038e48bb3a8a0) - Update Reflect, add vararg enum test *(commit by [@kappa-maintainer](https://github.com/kappa-maintainer))*


## [0.5.7-alpha] - 2026-03-31
### New Features
- [`7646e15`](https://github.com/CleanroomMC/Cleanroom/commit/7646e157d6937272d9374a0c4c90dc222e5d4002) - add progressbar config *(commit by [@baka-gourd](https://github.com/baka-gourd))*
- [`8acf305`](https://github.com/CleanroomMC/Cleanroom/commit/8acf305102d8ed7435b223a11f36dee93c9a6065) - add subprogress *(commit by [@baka-gourd](https://github.com/baka-gourd))*
- [`bc98b18`](https://github.com/CleanroomMC/Cleanroom/commit/bc98b1889a06ca43413281d51b0c7335338e57c2) - truly progress counting *(commit by [@baka-gourd](https://github.com/baka-gourd))*
- [`be42de4`](https://github.com/CleanroomMC/Cleanroom/commit/be42de4b6565f2c713d88c962f430dd92cec9d91) - more defensive programming, and add more log *(commit by [@baka-gourd](https://github.com/baka-gourd))*

[0.5.7-alpha]: https://github.com/CleanroomMC/Cleanroom/compare/0.5.6-alpha...0.5.7-alpha
[0.5.8-alpha]: https://github.com/CleanroomMC/Cleanroom/compare/0.5.7-alpha...0.5.8-alpha
[0.5.9-alpha]: https://github.com/CleanroomMC/Cleanroom/compare/0.5.8-alpha...0.5.9-alpha
[0.5.10-alpha]: https://github.com/CleanroomMC/Cleanroom/compare/0.5.9-alpha...0.5.10-alpha
[0.5.11-alpha]: https://github.com/CleanroomMC/Cleanroom/compare/0.5.10-alpha...0.5.11-alpha
[0.5.12-alpha]: https://github.com/CleanroomMC/Cleanroom/compare/0.5.11-alpha...0.5.12-alpha
[0.5.13-alpha]: https://github.com/CleanroomMC/Cleanroom/compare/0.5.12-alpha...0.5.13-alpha
[0.5.14-alpha]: https://github.com/CleanroomMC/Cleanroom/compare/0.5.13-alpha...0.5.14-alpha
[0.5.15-alpha]: https://github.com/CleanroomMC/Cleanroom/compare/0.5.14-alpha...0.5.15-alpha
[0.5.16-alpha]: https://github.com/CleanroomMC/Cleanroom/compare/0.5.15-alpha...0.5.16-alpha
[0.5.17-alpha]: https://github.com/CleanroomMC/Cleanroom/compare/0.5.16-alpha...0.5.17-alpha
[0.6.0-alpha]: https://github.com/CleanroomMC/Cleanroom/compare/0.5.17-alpha...0.6.0-alpha
[0.6.1-alpha]: https://github.com/CleanroomMC/Cleanroom/compare/0.6.0-alpha...0.6.1-alpha
[0.6.2-alpha]: https://github.com/CleanroomMC/Cleanroom/compare/0.6.1-alpha...0.6.2-alpha
[0.6.3-alpha]: https://github.com/CleanroomMC/Cleanroom/compare/0.6.2-alpha...0.6.3-alpha
[0.6.4-alpha]: https://github.com/CleanroomMC/Cleanroom/compare/0.6.3-alpha...0.6.4-alpha
[0.6.5-alpha]: https://github.com/CleanroomMC/Cleanroom/compare/0.6.4-alpha...0.6.5-alpha
[0.6.6-alpha]: https://github.com/CleanroomMC/Cleanroom/compare/0.6.5-alpha...0.6.6-alpha
[0.6.7-alpha]: https://github.com/CleanroomMC/Cleanroom/compare/0.6.6-alpha...0.6.7-alpha
[0.6.8-alpha]: https://github.com/CleanroomMC/Cleanroom/compare/0.6.7-alpha...0.6.8-alpha
[0.6.9-alpha]: https://github.com/CleanroomMC/Cleanroom/compare/0.6.8-alpha...0.6.9-alpha
[0.6.10-alpha]: https://github.com/CleanroomMC/Cleanroom/compare/0.6.9-alpha...0.6.10-alpha
[0.6.11-alpha]: https://github.com/CleanroomMC/Cleanroom/compare/0.6.10-alpha...0.6.11-alpha
[0.6.12-alpha]: https://github.com/CleanroomMC/Cleanroom/compare/0.6.11-alpha...0.6.12-alpha
[0.6.13-alpha]: https://github.com/CleanroomMC/Cleanroom/compare/0.6.12-alpha...0.6.13-alpha
[2026.10.25]: https://github.com/Project-Outlands/Outlands/compare/2026.10.23...2026.10.25
[2026.10.26]: https://github.com/Project-Outlands/Outlands/compare/2026.10.25...2026.10.26
[2026.10.29]: https://github.com/Project-Outlands/Outlands/compare/2026.10.26...2026.10.29
[2026.11.01]: https://github.com/Project-Outlands/Outlands/compare/2026.10.29...2026.11.01
[2026.11.02]: https://github.com/Project-Outlands/Outlands/compare/2026.11.01...2026.11.02
[2026.11.03]: https://github.com/Project-Outlands/Outlands/compare/2026.11.02...2026.11.03
[2026.11.05]: https://github.com/Project-Outlands/Outlands/compare/2026.11.03...2026.11.05
