# Sun Wukong: boss fight redesign (2026-11-20)

The fight follows Journey to the West's Havoc in Heaven arc, from the Monkey King's cocky staff play to Buddha's palm.
Code: `entity/ability/WukongSkills.java` (moves + brain), `entity/ability/WukongArena.java` (arena mechanics: shrines,
pillars, furnace, cloud, staff throw), `feature/bossfx/BossFx.shake` + client `BossShake` (camera shake).
Clips: `tools/clips/wukong.py`. Model props: `tools/model_overrides/arcana_wukong.bbmodel` (`peach` bone in the left hand).

## Phases (health of the possibly party-scaled max)

| # | Name | Health | Stance / walk | Boss bar |
|---|------|--------|---------------|----------|
| 1 | Handsome Monkey King | 100-66 % | `stance` / `run` | yellow |
| 2 | Great Sage Equal to Heaven | 66-33 % | `idle_rage` / `walk_rage` | yellow, gold aura |
| 3 | Havoc in Heaven | 33-12 % | `idle_havoc` / `walk_rage` | red |
| 4 | Buddha's Palm (finale) | <12 % | `idle_final` / `walk_rage` | purple |

Transitions are invulnerable set pieces:
* **1 -> 2 `ascend`**: he springs into the air, lands in a superhero crouch (shockwave) and the circlet blazes.
* **2 -> 3 Peach Feast**: `havoc` roar, then he sits down to devour Peaches of Immortality (`peach_eat` loop). Every
  second heals 2 % max health; dealing 6 % of his max health during the feast makes him choke (`peach_choke`, stunned,
  takes +30 % damage for 4 s). The feast is also a repeatable phase-3 move.
* **3 -> 4 Buddha's Palm**: `palm_look` (he looks up in shock), five golden pillars (Buddha's fingers) rise around the
  arena. Wukong is untouchable and harries the players with leaps and staff throws. Standing at a pillar's base claims
  it (2.5 s, golden progress ring; it drains slowly when you step off). All five claimed in 38 s: the palm closes, a
  mountain of stone falls on him (Five Elements Mountain): `sealed` loop, stunned and taking double damage for 12 s,
  then `break_free`. Not claimed in time: he cackles (`laugh`), heals 6 % and the pillars collapse on the players; he
  can be worn down to 5 % but not killed, and the pillars come back 20 s later. A second miss and the palm closes on
  its own. Until the finale he can't be pushed below 0.1 % health (no skipping it).

## Moves

Global cooldown + per-move cooldowns; the brain picks a weighted move every second it is free, never the same twice
in a row. Ticks below are from the start of the move.

| Move | Phases | Clip (impact) | What happens |
|------|--------|---------------|--------------|
| Swing / Smash / Thrust | all | `attack` (8) / `attack2` (11) / `attack3` (7) | replace the base melee |
| Combo | all | `combo` (8, 15, 32) | three hits, last one a ground quake |
| Stomp | all | `stomp` (14) | quake; phase 2+ stomps twice |
| Leap | all | `leap` (8 jump, 33 land) | ballistic leap onto the target |
| Ruyi Extend | all | `extend` (15) | the staff shoots 12 blocks in a line |
| Pole Vault | all | `vault` (6 plant, 20 kick) | vaults over his staff onto the target, double kick |
| Staff Throw | all | `staff_throw` (8 release) + `staff_catch` | the staff spins out to the target and boomerangs back, hitting both ways; he is unarmed meanwhile |
| Somersault Cloud | all | `somersault` (15 land) | flips onto his cloud and lands behind the target |
| Hair Clones | all | `clone` (26) | 2 / 3 / 4 clones |
| Taunt | all | `taunt` | mocks a far target, then somersaults |
| Fiery Golden Eyes | 1 | `fire_eyes` (12) | burning beam locked on the target |
| Eye Sweep | 2+ | `eye_sweep` (12-40) | a low beam sweeps 120 deg across the arena at knee height: jump it |
| Fissure | 2+ | `attack2` (11) | earth spikes toward every player |
| Staff Spin | 2+ | `staff_spin` | vortex pull then blast |
| Ruyi Grow (pillar) | 2+ | `pillar` (43) | giant staffs slam on every player |
| Seventy-Two Transformations | 2+ | `transform` (24 vanish) | 5 shrines appear; he hides in one, its flagpole (his tail) sways. Hit the real one: `reveal` + `dizzy` 3 s (+30 % damage taken). Hit a fake: it bursts in golden fire. 14 s timeout: every fake bursts and he smashes the nearest player |
| Cloud Barrage | 2+ | `cloud_ride` loop, `cloud_strike` (7), `cloud_dive` (18) | rides his cloud 7 blocks up, staff strikes on gold-ringed spots, then dives |
| Faxiang Tiandi | 3+ | `giant_grow`, `giant_slam` (26), `giant_sweep` (18-28) | grows 2.4x for ~13 s: three telegraphed giant slams and a sweep, camera shake |
| Eight Trigrams Furnace | 3+ | `furnace` loop | eight trigram sectors; fire erupts in seven, the safe one turns each wave (3 waves) |
| Hair Army | 3+ | `clone` (26) | a ring of 5 clones that charge through the arena one after another |
| Peach Feast | 3 | `peach_eat` loop / `peach_choke` | see above |
| Sky Check | all | `fire_eyes` (12) | drags a target that is perched out of reach back down |

## Clip table (what tools/clips/wukong.py must provide)

Blockbench convention, 20 fps. "once" clips return to the stance pose at the end.

| Clip | Len s | Loop | Key times | Pose notes |
|------|-------|------|-----------|------------|
| `ascend` | 3.0 | once | land 1.6, roar 2.0 | crouch, spring up (body +y 22), forward flip, superhero landing, rise with staff held overhead horizontal, head back roar |
| `havoc` | 3.2 | once | slam 2.4 | beats his chest, roars at the sky, staff overhead, slams it down |
| `peach_eat` | 1.6 | loop | bites 0.5, 1.3 | sitting cross-legged on the ground, staff across lap / tucked, left hand brings the peach to the mouth, chewing jaw, happy tail |
| `peach_choke` | 1.2 | once | cough 0.3 | hand to throat, hunched, stagger |
| `transform` | 1.4 | once | seal 0.4, vanish 1.2 | hand seal at the chest, spins (whole turn) and shrinks to nothing |
| `reveal` | 0.8 | once | pop 0.1 | pops out (scale overshoot), arms flailing |
| `dizzy` | 1.2 | loop | - | dazed: sways, head circles, staff drooping |
| `cloud_ride` | 1.6 | loop | - | surfer stance on the cloud (cloud bone animated), staff twirling overhead, ribbons/plumes/tail streaming |
| `cloud_strike` | 0.8 | once | strike 0.35 | stabs the staff straight down from the cloud |
| `cloud_dive` | 1.4 | once | land 0.9 | leaps off, two-handed overhead staff, crouched landing |
| `giant_grow` | 1.8 | once | - | arms wide, chest out, roar, flex |
| `giant_slam` | 2.0 | once | slam 1.3 | slow huge overhead two-handed slam, lean back windup |
| `giant_sweep` | 2.2 | once | sweep 0.9-1.4 | horizontal 270 deg staff sweep |
| `staff_throw` | 1.0 | once | release 0.4 | wind up, hurl the staff forward |
| `staff_catch` | 0.7 | once | catch 0.1 | catches and twirls it back into stance |
| `vault` | 1.6 | once | plant 0.3, kick 1.0, land 1.2 | plants staff ahead, swings up over it, double kick |
| `eye_sweep` | 2.4 | once | sweep 0.6-2.0 | shades brow, then the head turns from his right (+60 y) to his left (-60 y), crouched |
| `furnace` | 3.0 | loop | - | staff planted vertically in front, both hands on top, head bowed then up, plumes/ribbons whipping in heat, tremble |
| `palm_look` | 1.6 | once | - | looks up in shock, recoils |
| `sealed` | 2.0 | loop | - | pinned low (body down), arms pushing up, straining, struggle pulses |
| `break_free` | 1.6 | once | burst 0.6 | crouch then bursts up, arms wide |
| `laugh` | 2.0 | once | - | holds his belly, rocks back, slaps a knee, jaw flapping |
| `idle_final` | 1.6 | loop | - | desperate: low, heavy breathing, staff dragging |
| `idle_shoulder` | 6.0 | loop | - | calm idle: staff across his shoulders, wrists draped over it, tail swaying |
