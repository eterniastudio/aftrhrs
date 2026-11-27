"""Hand-written sound profiles for every Arcana creature (the source of truth; gen_sfx.py turns them into
assets/aftrhrs/sounds.json, the species tables' "sfx" block and the subtitles).

Building blocks (all vanilla sounds, re-pitched / re-weighted / layered; the generator checks every file exists):
  V(event, p, v, w)   every file of a vanilla sound event (its own volume / pitch kept and multiplied by p / v)
  F(path, p, v, w)    one vanilla sound file, or a glob such as 'mob/fox/sleep*'
  E(sounds, layer=[...], delay=N, jitter=J, dist=D, sub='...')
                      an event with a second sound played N ticks later (layer), pitch jitter (+- J, applied by
                      the server per play), range D blocks and its own subtitle text
An event is a list of sounds (shorthand for E(list)) or an E(...).

Events: ambient (+ ambient_happy / ambient_sleepy / ambient_scared / ambient_angry mood variants), snore, sleep,
wake, pet, happy, bond_up, bond_level, tame, tame_fail, eat, heal, hurt, death, step, sit, stand, arrive, revive,
ability (+ ability_<skill> for one skill), attack, and for mounts saddle, jump, land, flap, gallop, splash.
Meta keys: _every (ambient interval in ticks), _snore_every, _particle (petting particle), _dist (default range),
_jitter (default pitch jitter), _subs (subtitle verbs, '{n}' = creature name).

Rules of thumb: the creature's own voice for vocal events, the material it is made of for touch events (pet, step,
hurt, death), small pitch shifts per size (smaller = higher), sleeping pets only breathe or snore, never call.
"""
import copy


def V(event, p=1.0, v=1.0, w=1):
    return {'event': event, 'p': p, 'v': v, 'w': w}


def F(path, p=1.0, v=1.0, w=1):
    return {'file': path, 'p': p, 'v': v, 'w': w}


def E(sounds, layer=None, delay=0, jitter=None, dist=None, sub=None):
    e = {'sounds': list(sounds)}
    if layer:
        e['layer'] = list(layer)
        e['delay'] = delay
    if jitter is not None:
        e['jitter'] = jitter
    if dist is not None:
        e['dist'] = dist
    if sub:
        e['sub'] = sub
    return e


def merge(*parts, **over):
    out = {}
    for p in parts:
        for k, val in p.items():
            if k == '_subs' and '_subs' in out:
                out['_subs'] = dict(out['_subs'], **val)
            else:
                out[k] = copy.deepcopy(val)
    for k, val in over.items():
        if k == '_subs' and '_subs' in out:
            out['_subs'] = dict(out['_subs'], **val)
        else:
            out[k] = val
    return {k: v for k, v in out.items() if v is not None}


# ---------------------------------------------------------------------------------------------------------- shared
# aftrhrs:shared.<event>: layered under every pet's own sound by the server (CreatureSfx)
SHARED = {
    'bond_up': E([F('block/amethyst/shimmer', 1.7, 0.7), F('random/orb', 1.9, 0.18)], sub='Bond grows'),
    'fanfare_note': E([F('note/bell', 1.0, 0.55), F('note/icechime', 1.0, 0.35)], jitter=0.0, sub='Bond fanfare plays'),
    'fanfare_end': E([F('random/levelup', 1.3, 0.45)], layer=[F('block/amethyst/resonate*', 1.4, 0.8)], delay=4,
                     jitter=0.0, sub='Bond level up'),
    'tame': E([F('random/levelup', 1.6, 0.5)], layer=[F('block/amethyst/shimmer', 1.5, 0.9)], delay=3, jitter=0.0,
              sub='Creature tamed'),
    'revive': E([F('item/totem/use_totem', 1.6, 0.3)], layer=[F('block/respawn_anchor/charge*', 1.5, 0.35)], delay=6,
                jitter=0.0, sub='Pet returns'),
    'whistle': E([F('block/amethyst/shimmer', 1.9, 0.6)], jitter=0.05, sub='Pet hears the whistle'),
}

# ---------------------------------------------------------------------------------------------------------- archetypes


def dog(p, variant='classic', size='medium'):
    """Wolf variants per breed (the 26.x wolf sound sets: classic, cute, puglin, big, grumpy, sad, angry). Small
    breeds are pitched up, giants down; the bark is the breed's voice for everything vocal."""
    pre = {'classic': 'entity.wolf', 'cute': 'entity.wolf_cute', 'puglin': 'entity.wolf_puglin', 'big': 'entity.wolf_big',
           'grumpy': 'entity.wolf_grumpy', 'sad': 'entity.wolf_sad', 'angry': 'entity.wolf_angry'}[variant]
    step_p = {'small': 1.35, 'medium': 1.1, 'large': 0.85}[size]
    return {
        '_every': 260, '_particle': 'minecraft:heart',
        '_subs': {'ambient': '{n} barks', 'ambient_happy': '{n} pants', 'ambient_sleepy': '{n} sighs',
                  'ambient_scared': '{n} whines', 'pet': '{n} pants happily', 'hurt': '{n} yelps', 'snore': '{n} snores',
                  'attack': '{n} growls', 'arrive': '{n} barks hello', 'tame_fail': '{n} huffs'},
        'ambient': E([V(pre + '.ambient', p)], jitter=0.08),
        'ambient_happy': E([V(pre + '.pant', p, 0.9)], layer=[V(pre + '.ambient', p * 1.08, 0.6)], delay=14, jitter=0.06),
        'ambient_sleepy': E([V(pre + '.whine', p * 0.92, 0.45)], jitter=0.05),
        'ambient_scared': E([V(pre + '.whine', p * 1.05, 0.8)], jitter=0.06),
        'ambient_angry': E([V(pre + '.growl', p, 0.9)], jitter=0.06),
        'snore': E([V(pre + '.pant', p * 0.6, 0.28)], jitter=0.04),
        'sleep': E([V(pre + '.whine', p * 0.85, 0.4)], layer=[V(pre + '.pant', p * 0.6, 0.3)], delay=12),
        'wake': E([V('entity.wolf.shake', 1.1, 0.5)], layer=[V(pre + '.ambient', p * 1.1, 0.5)], delay=10),
        'pet': E([V(pre + '.pant', p * 1.05, 0.85)], layer=[V(pre + '.ambient', p * 1.15, 0.45)], delay=10, jitter=0.05),
        'happy': E([V(pre + '.ambient', p * 1.12, 0.8)], layer=[V(pre + '.ambient', p * 1.2, 0.7)], delay=5),
        'bond_up': E([V(pre + '.ambient', p * 1.2, 0.45)], jitter=0.05),
        'bond_level': E([V(pre + '.ambient', p * 1.1, 0.9)],
                        layer=[V(pre + '.ambient', p * 1.25, 0.9), V(pre + '.pant', p * 1.1, 0.7)], delay=6, jitter=0.0),
        'tame': E([V(pre + '.ambient', p * 1.1, 0.9)], layer=[V(pre + '.pant', p, 0.8)], delay=8),
        'tame_fail': E([V(pre + '.growl', p * 1.1, 0.4)]),
        'eat': E([V('entity.generic.eat', p * 1.05, 0.7)], layer=[V(pre + '.pant', p, 0.5)], delay=12),
        'heal': E([V(pre + '.ambient', p * 1.15, 0.45)]),
        'hurt': E([V(pre + '.hurt', p)], jitter=0.07),
        'death': E([V(pre + '.death', p)], layer=[V(pre + '.whine', p * 0.85, 0.7)], delay=8),
        'step': E([V('entity.wolf.step', step_p, 0.9)], jitter=0.08),
        'sit': E([V(pre + '.pant', p * 0.9, 0.5)], jitter=0.05),
        'stand': E([V('entity.wolf.shake', 1.2, 0.35)], layer=[V(pre + '.ambient', p * 1.1, 0.5)], delay=6),
        'arrive': E([V(pre + '.ambient', p * 1.1, 0.9)], layer=[V(pre + '.ambient', p * 1.18, 0.8)], delay=5),
        'revive': E([V(pre + '.whine', p * 1.1, 0.7)], layer=[V(pre + '.ambient', p * 1.15, 0.8)], delay=14),
        'ability': E([V(pre + '.ambient', p * 1.05, 0.7)], jitter=0.05),
        'attack': E([V(pre + '.growl', p, 0.9)], jitter=0.06),
    }


def cat(p, family='cat'):
    """Cats only ever sound like cats: meows, trills (purreow), purrs, hisses. family 'baby_cat' for kittens,
    'cat_royal' for the 26.x royal set."""
    pre = 'entity.' + family
    return {
        '_every': 300, '_snore_every': 90, '_particle': 'minecraft:heart',
        '_subs': {'ambient': '{n} meows', 'ambient_happy': '{n} trills', 'ambient_sleepy': '{n} mews sleepily',
                  'ambient_scared': '{n} hisses', 'pet': '{n} purrs', 'hurt': '{n} yowls', 'snore': '{n} purrs in its sleep',
                  'attack': '{n} hisses', 'tame_fail': '{n} flicks its tail'},
        'ambient': E([V(pre + '.ambient', p, 0.7)], jitter=0.08),
        'ambient_happy': E([V(pre + '.purreow', p, 0.7)], jitter=0.07),
        'ambient_sleepy': E([V(pre + '.ambient', p * 0.9, 0.35)], jitter=0.05),
        'ambient_scared': E([V(pre + '.hiss', p, 0.6)], jitter=0.06),
        'ambient_angry': E([V(pre + '.hiss', p * 0.95, 0.8)], jitter=0.06),
        'snore': E([V(pre + '.purr', p * 0.85, 0.35)], jitter=0.04),
        'sleep': E([V(pre + '.purreow', p * 0.9, 0.4)], layer=[V(pre + '.purr', p * 0.85, 0.4)], delay=14),
        'wake': E([V(pre + '.ambient', p * 0.95, 0.45)]),
        'pet': E([V(pre + '.purr', p, 0.85)], layer=[V(pre + '.purreow', p * 1.05, 0.35)], delay=24, jitter=0.04),
        'happy': E([V(pre + '.purreow', p * 1.05, 0.7)]),
        'bond_up': E([V(pre + '.purreow', p * 1.1, 0.4)], jitter=0.05),
        'bond_level': E([V(pre + '.purreow', p * 1.1, 0.8)], layer=[V(pre + '.purr', p, 0.9)], delay=10, jitter=0.0),
        'tame': E([V(pre + '.purreow', p, 0.8)], layer=[V(pre + '.purr', p, 0.8)], delay=10),
        'tame_fail': E([V(pre + '.hiss', p * 1.1, 0.35)]),
        'eat': E([V(pre + '.eat', p, 0.8)], jitter=0.06),
        'heal': E([V(pre + '.purr', p * 1.05, 0.5)]),
        'hurt': E([V(pre + '.hurt', p)], jitter=0.07),
        'death': E([V(pre + '.death', p)]),
        'sit': E([V(pre + '.purreow', p * 0.95, 0.35)]),
        'stand': E([V(pre + '.ambient', p * 1.05, 0.45)]),
        'arrive': E([V(pre + '.purreow', p * 1.05, 0.75)]),
        'revive': E([V(pre + '.purreow', p * 1.05, 0.8)], layer=[V(pre + '.purr', p, 0.8)], delay=12),
        'ability': E([V(pre + '.purreow', p, 0.6)], jitter=0.05),
        'attack': E([V(pre + '.hiss', p, 0.8)], jitter=0.06),
    }


def critter(voice, p, *, hurt=None, death=None, eat=None, step=None, every=260, particle='minecraft:heart', subs=None):
    """A generic small creature built around one vanilla voice event (rabbit, fox, armadillo ...)."""
    hurt = hurt or voice.rsplit('.', 1)[0] + '.hurt'
    death = death or voice.rsplit('.', 1)[0] + '.death'
    out = {
        '_every': every, '_particle': particle, '_subs': subs or {},
        'ambient': E([V(voice, p, 0.7)], jitter=0.08),
        'ambient_happy': E([V(voice, p * 1.12, 0.65)], layer=[V(voice, p * 1.2, 0.45)], delay=6, jitter=0.06),
        'ambient_sleepy': E([V(voice, p * 0.85, 0.35)], jitter=0.05),
        'ambient_scared': E([V(hurt, p * 1.15, 0.45)], jitter=0.06),
        'sleep': E([V(voice, p * 0.8, 0.35)]),
        'wake': E([V(voice, p * 0.95, 0.45)]),
        'pet': E([V(voice, p * 1.1, 0.7)], jitter=0.06),
        'happy': E([V(voice, p * 1.2, 0.75)]),
        'bond_up': E([V(voice, p * 1.25, 0.4)], jitter=0.05),
        'bond_level': E([V(voice, p * 1.15, 0.8)], layer=[V(voice, p * 1.3, 0.8)], delay=6, jitter=0.0),
        'tame': E([V(voice, p * 1.15, 0.85)], layer=[V(voice, p * 1.25, 0.7)], delay=7),
        'tame_fail': E([V(voice, p * 0.8, 0.4)]),
        'eat': E([V(eat or 'entity.generic.eat', p * 1.05, 0.7)], jitter=0.06),
        'heal': E([V(voice, p * 1.2, 0.45)]),
        'hurt': E([V(hurt, p)], jitter=0.07),
        'death': E([V(death, p)]),
        'sit': E([V(voice, p * 0.95, 0.35)]),
        'stand': E([V(voice, p * 1.05, 0.4)]),
        'arrive': E([V(voice, p * 1.15, 0.8)]),
        'revive': E([V(voice, p * 1.2, 0.8)], layer=[V(voice, p * 1.3, 0.6)], delay=10),
        'ability': E([V(voice, p * 1.1, 0.6)], jitter=0.05),
    }
    if step:
        out['step'] = step
    return out


# ---------------------------------------------------------------------------------------------------------- pets

PROFILES = {}

# Dogs: wolf sound sets pitched per breed size
PROFILES['corgi'] = merge(dog(1.32, 'cute', 'small'), _subs={'ambient': '{n} yips'})
PROFILES['dachshund'] = merge(dog(1.28, 'puglin', 'small'), _subs={'ambient': '{n} yaps'},
                              ability_truffle=E([V('entity.fox.sniff', 1.2, 0.7)], layer=[V('entity.wolf_puglin.ambient', 1.35, 0.6)], delay=14))
PROFILES['beagle'] = merge(dog(1.12, 'classic', 'medium'), _subs={'ambient': '{n} bays'},
                           ability=E([V('entity.fox.sniff', 0.9, 0.7)], layer=[V('entity.wolf_sad.whine', 1.25, 0.8)], delay=12))
PROFILES['husky'] = merge(dog(1.0, 'classic', 'medium'), _subs={'ambient_happy': '{n} talks back', 'ambient': '{n} woos'},
                          # huskies "talk": a whine-bark mix instead of plain barks when happy
                          ambient_happy=E([V('entity.wolf.whine', 1.15, 0.7)], layer=[V('entity.wolf.ambient', 1.05, 0.6)], delay=10, jitter=0.07),
                          step=E([V('entity.wolf.step', 1.05, 0.9)], layer=[V('block.snow.step', 1.1, 0.25)], delay=0, jitter=0.08),
                          # the night howl (26.3 has no wolf howl): two long whines pitched down and rising, "aw-ooo";
                          # also synced to the sitting howl clip (sit_howl)
                          ability_howl=E([V('entity.wolf.whine', 0.66, 1.0)], layer=[V('entity.wolf.whine', 0.76, 0.9)], delay=12,
                                         jitter=0.04, dist=40, sub='{n} howls'),
                          ability_howlback=E([V('entity.wolf.whine', 0.74, 0.9)], layer=[V('entity.wolf.whine', 0.84, 0.8)], delay=10,
                                             jitter=0.05, dist=40, sub='{n} howls back'))
PROFILES['husky_copper'] = merge(dog(0.95, 'grumpy', 'medium'), _subs={'ambient': '{n} woofs'},
                                 ambient_happy=E([V('entity.wolf_grumpy.whine', 1.1, 0.7)], layer=[V('entity.wolf_grumpy.ambient', 1.0, 0.6)], delay=10, jitter=0.07),
                                 ability_howl=E([V('entity.wolf_grumpy.whine', 0.64, 1.0)], layer=[V('entity.wolf_grumpy.whine', 0.74, 0.9)], delay=12,
                                                jitter=0.04, dist=40, sub='{n} howls'),
                                 ability_howlback=E([V('entity.wolf_grumpy.whine', 0.72, 0.9)], layer=[V('entity.wolf_grumpy.whine', 0.82, 0.8)],
                                                    delay=10, jitter=0.05, dist=40, sub='{n} howls back'))
PROFILES['golden_retriever'] = merge(dog(0.95, 'classic', 'large'), _subs={'ambient': '{n} woofs'},
                                     ambient=E([V('entity.wolf.ambient', 0.95, 0.7, 2), V('entity.wolf.pant', 0.95, 0.6)], jitter=0.07),
                                     ability=E([V('entity.wolf.ambient', 1.05, 0.7)], layer=[V('entity.item.pickup', 0.8, 0.5)], delay=6))
PROFILES['saint_bernard'] = merge(dog(0.72, 'big', 'large'), _subs={'ambient': '{n} woofs deeply'},
                                  ability=E([V('entity.wolf_big.ambient', 0.75, 0.9)], layer=[V('block.bell.resonate', 1.6, 0.25)], delay=4),
                                  # a big dog's snore: slow deep panting with a wet snort (the plain 0.6 x pant clamped below 0.5)
                                  snore=E([V('entity.wolf_big.pant', 0.52, 0.32)], layer=[V('entity.sniffer.idle', 0.62, 0.14)], delay=10, jitter=0.04),
                                  step=E([V('entity.wolf.step', 0.72, 1.0)], jitter=0.06))

# Cats: cat sounds only, pitched per cat; the kitten uses the baby cat set
PROFILES['tabby_cat'] = merge(cat(1.0))
PROFILES['black_cat'] = merge(cat(0.92, 'cat_royal'), _subs={'ambient': '{n} mrrps mysteriously'},
                              ability=E([V('entity.cat_royal.purreow', 0.95, 0.7)], layer=[F('block/amethyst/shimmer', 1.2, 0.5)], delay=8))
PROFILES['calico_kitten'] = merge(cat(1.0, 'baby_cat'), _subs={'ambient': '{n} mews'},
                                  ability=E([V('entity.baby_cat.purr', 1.25, 0.9)], jitter=0.04))
PROFILES['maine_coon'] = merge(cat(0.8), _subs={'ambient': '{n} chirps', 'ambient_happy': '{n} chirrups'},
                               # Maine Coons are famous chirpers: the trill is their everyday voice
                               ambient=E([V('entity.cat.purreow', 0.82, 0.65, 2), V('entity.cat.ambient', 0.8, 0.6)], jitter=0.07),
                               ambient_sleepy=E([V('entity.cat.purreow', 0.75, 0.35)], jitter=0.05))
# Luna, Kevin, Kitty and Clover react to petting with their own skills (purrs, bonks, swats): no extra pet sound
PROFILES['luna'] = merge(cat(1.1), pet=None)
PROFILES['kevin'] = merge(cat(0.86), pet=None, _subs={'ambient': '{n} mrows'},
                          # a 40-pound chonk is audible: soft, heavy fur-padded paws (cats only: no other animal's steps)
                          step=E([V('block.wool.step', 0.62, 0.55)], jitter=0.08))
# Kitty: a big black Maine Coon. Chirps and trills like the Maine Coon, deeper; padded heavy paws like Kevin
PROFILES['kitty'] = merge(cat(0.82), pet=None, _subs={'ambient': '{n} chirps', 'ambient_happy': '{n} trills'},
                          ambient=E([V('entity.cat.purreow', 0.85, 0.6, 2), V('entity.cat.ambient', 0.82, 0.6)], jitter=0.07),
                          ambient_sleepy=E([V('entity.cat.purreow', 0.78, 0.35)], jitter=0.05),
                          step=E([V('block.wool.step', 0.6, 0.6)], jitter=0.08))
# Clover: a slim young tuxedo. Bright, chatty meows and quick trills, light paws
PROFILES['clover'] = merge(cat(1.06), pet=None, _subs={'ambient': '{n} meows', 'ambient_happy': '{n} trills'},
                           ambient_happy=E([V('entity.cat.purreow', 1.08, 0.7, 2), V('entity.cat.ambient', 1.12, 0.5)], jitter=0.07))
# Luna But The Second One: a round, unimpressed grey tabby. Low flat meows, a big rumbly purr she'd deny
PROFILES['luna_two'] = merge(cat(0.9), pet=None, _subs={'ambient': '{n} grumbles', 'ambient_happy': '{n} purrs'},
                             ambient=E([V('entity.cat.stray_ambient', 0.86, 0.45), V('entity.cat.ambient', 0.84, 0.55)], jitter=0.06),
                             ambient_happy=E([V('entity.cat.purr', 0.85, 0.8, 2), V('entity.cat.purreow', 0.82, 0.5)], jitter=0.05))
# Olive: a sweet, derpy young orange tom. Soft chirpy meows, a loud happy purr
PROFILES['olive'] = merge(cat(1.02), pet=None, _subs={'ambient': '{n} chirps', 'ambient_happy': '{n} purrs'},
                          ambient_happy=E([V('entity.cat.purr', 1.0, 0.85, 2), V('entity.cat.purreow', 1.05, 0.6)], jitter=0.06))
# Ozzy: a very fat black menace. Low, demanding yowls and grumbly growls
PROFILES['ozzy'] = merge(cat(0.8), pet=None, _subs={'ambient': '{n} yowls', 'ambient_happy': '{n} grumbles'},
                         ambient=E([V('entity.cat.stray_ambient', 0.78, 0.55), V('entity.cat.ambient', 0.76, 0.5)], jitter=0.06),
                         ambient_happy=E([V('entity.cat.purr', 0.75, 0.8, 2), V('entity.cat.stray_ambient', 0.8, 0.35)], jitter=0.05))

# Cairnling: a pebble golem. Clinks of amethyst, stone grinding, a deep tumbling-pebble "purr"
PROFILES['cairnling'] = {
    '_every': 260, '_snore_every': 100, '_particle': 'minecraft:wax_off',
    '_subs': {'ambient': '{n} clinks', 'ambient_happy': '{n} chimes', 'ambient_sleepy': '{n} settles its stones',
              'ambient_scared': '{n} rattles', 'snore': '{n} grinds softly', 'pet': '{n} rumbles like tumbling pebbles',
              'hurt': '{n} chips', 'death': '{n} crumbles', 'step': 'Stone footsteps', 'eat': '{n} crunches amethyst',
              'ability': '{n} grinds stone', 'attack': '{n} slams'},
    'ambient': E([V('block.amethyst_block.step', 0.9, 0.9, 2), V('block.calcite.step', 0.8, 0.9), V('block.amethyst_cluster.step', 1.1, 0.7)],
                 jitter=0.12),
    'ambient_happy': E([F('block/amethyst/shimmer', 1.1, 0.9)], layer=[V('block.amethyst_block.step', 1.3, 0.8)], delay=4, jitter=0.1),
    'ambient_sleepy': E([V('block.tuff.step', 0.6, 0.7)], layer=[V('block.gravel.step', 0.55, 0.4)], delay=6, jitter=0.06),
    'ambient_scared': E([V('block.amethyst_cluster.hit', 1.3, 0.7)], layer=[V('block.calcite.step', 1.4, 0.6)], delay=2, jitter=0.1),
    'ambient_angry': E([V('block.deepslate.step', 0.6, 0.9)], jitter=0.08),
    # snoring stones: a slow grindstone turn, very low
    'snore': E([V('block.grindstone.use', 0.5, 0.18)], jitter=0.05),
    'sleep': E([V('block.gravel.step', 0.55, 0.7)], layer=[V('block.tuff.place', 0.6, 0.6)], delay=10),
    'wake': E([V('block.tuff.step', 0.8, 0.7)], layer=[F('block/amethyst/shimmer', 0.9, 0.7)], delay=8),
    # the "purr": pebbles tumbling (gravel at half pitch), a stone grind and a faint amethyst hum under it
    'pet': E([V('block.gravel.step', 0.52, 1.0, 2), V('block.gravel.hit', 0.5, 0.9)],
             layer=[F('block/amethyst/resonate*', 0.6, 0.55), V('block.grindstone.use', 0.55, 0.12)], delay=5, jitter=0.05),
    'happy': E([F('block/amethyst/resonate*', 1.2, 0.9)], layer=[F('block/amethyst/shimmer', 1.4, 0.8)], delay=5),
    'bond_up': E([F('block/amethyst/shimmer', 1.25, 0.8)], jitter=0.06),
    'bond_level': E([F('block/amethyst/resonate*', 1.0, 1.0)],
                    layer=[F('block/amethyst/resonate*', 1.5, 0.9), V('block.amethyst_block.step', 1.5, 0.8)], delay=6, jitter=0.0),
    'tame': E([F('block/amethyst/resonate*', 0.9, 1.0)], layer=[V('block.gravel.step', 0.6, 0.8)], delay=6),
    'tame_fail': E([V('block.stone.hit', 0.8, 0.6)]),
    'eat': E([V('block.amethyst_cluster.break', 1.35, 0.8)], layer=[F('block/amethyst/shimmer', 1.3, 0.6)], delay=4, jitter=0.08),
    'heal': E([F('block/amethyst/resonate*', 1.3, 0.6)]),
    'hurt': E([V('block.stone.hit', 0.9, 1.0)], layer=[V('block.amethyst_cluster.hit', 1.1, 0.7)], delay=1, jitter=0.1),
    'death': E([V('block.stone.break', 0.8, 1.0)], layer=[V('block.amethyst_block.break', 0.8, 0.9), V('block.gravel.break', 0.7, 0.8)], delay=3),
    'step': E([V('block.tuff.step', 1.0, 0.9), V('block.calcite.step', 1.1, 0.8)], jitter=0.1),
    'sit': E([V('block.tuff.place', 0.8, 0.6)]),
    'stand': E([V('block.gravel.step', 0.8, 0.6)], layer=[V('block.amethyst_block.step', 1.2, 0.6)], delay=4),
    'arrive': E([V('block.amethyst_block.step', 1.2, 0.9)], layer=[F('block/amethyst/shimmer', 1.2, 0.8)], delay=3),
    'revive': E([V('block.stone.place', 0.8, 0.9)], layer=[F('block/amethyst/resonate*', 1.1, 1.0)], delay=8),
    'ability': E([V('block.grindstone.use', 1.2, 0.3)], layer=[V('block.stone.break', 1.4, 0.5)], delay=8),
    'ability_pebbleminer': E([V('block.grindstone.use', 1.3, 0.3)], layer=[V('block.stone.break', 1.5, 0.5), F('block/amethyst/shimmer', 1.4, 0.4)], delay=8),
    'attack': E([V('entity.iron_golem.attack', 1.5, 0.7)], jitter=0.08),
}

# Emberkit: a fire fox. Fox voice over campfire crackle, soft fire puffs
PROFILES['emberkit'] = merge(critter('entity.fox.ambient', 1.0, eat='entity.fox.eat', particle='minecraft:small_flame'), {
    '_subs': {'ambient': '{n} yips and crackles', 'pet': '{n} crackles warmly', 'snore': '{n} smolders in its sleep',
              'ambient_happy': '{n} puffs embers', 'ability': '{n} puffs fire'},
    'ambient': E([V('entity.fox.ambient', 1.0, 0.65)], layer=[V('block.campfire.crackle', 1.2, 0.8)], delay=2, jitter=0.08),
    'ambient_happy': E([V('item.firecharge.use', 1.9, 0.25)], layer=[V('entity.fox.ambient', 1.15, 0.6)], delay=4, jitter=0.07),
    'snore': E([V('entity.fox.sleep', 1.0, 0.5)], layer=[V('block.campfire.crackle', 0.9, 0.5)], delay=8, jitter=0.05),
    'pet': E([V('block.campfire.crackle', 1.3, 1.0)], layer=[V('entity.fox.sniff', 1.2, 0.5), V('block.fire.ambient', 1.6, 0.25)], delay=5, jitter=0.06),
    'happy': E([V('entity.blaze.shoot', 2.0, 0.18)], layer=[V('entity.fox.ambient', 1.25, 0.7)], delay=3),
    'bond_up': E([V('block.campfire.crackle', 1.5, 0.6)], jitter=0.06),
    'bond_level': E([V('item.firecharge.use', 1.7, 0.4)], layer=[V('entity.fox.screech', 1.4, 0.35), V('block.campfire.crackle', 1.2, 0.9)], delay=4, jitter=0.0),
    'eat': E([V('entity.fox.eat', 1.0, 0.8)], layer=[V('block.campfire.crackle', 1.4, 0.5)], delay=6),
    'hurt': E([V('entity.fox.hurt', 1.0)], layer=[V('block.fire.extinguish', 1.8, 0.15)], delay=1, jitter=0.07),
    'death': E([V('entity.fox.death', 1.0)], layer=[V('block.fire.extinguish', 1.2, 0.5)], delay=6),
    'ability': E([V('entity.blaze.shoot', 1.9, 0.3)], layer=[V('block.campfire.crackle', 1.0, 0.8)], delay=4),
    'attack': E([V('entity.fox.bite', 1.0, 0.9)], layer=[V('item.firecharge.use', 1.8, 0.25)], delay=1),
    'step': None,
})

# Glimmermoth: wing flutter and chimes, no voice at all
PROFILES['glimmermoth'] = {
    '_every': 240, '_particle': 'minecraft:end_rod',
    '_subs': {'ambient': '{n} flutters', 'ambient_happy': '{n} chimes', 'pet': '{n} chimes softly', 'hurt': '{n} flutters wildly',
              'death': '{n} fades', 'eat': '{n} sips', 'flap': 'Wings flutter', 'ability': '{n} scatters glimmer'},
    'ambient': E([V('entity.parrot.fly', 1.6, 0.4)], layer=[V('block.note_block.chime', 1.8, 0.18)], delay=6, jitter=0.1),
    'ambient_happy': E([V('block.note_block.chime', 1.6, 0.3)], layer=[V('block.note_block.chime', 2.0, 0.25)], delay=3, jitter=0.0),
    'ambient_sleepy': E([F('block/amethyst/shimmer', 1.5, 0.35)], jitter=0.08),
    'ambient_scared': E([V('entity.bat.takeoff', 1.5, 0.35)], jitter=0.08),
    'sleep': E([V('entity.parrot.fly', 1.2, 0.3)]),
    'wake': E([V('entity.parrot.fly', 1.7, 0.4)], layer=[V('block.note_block.chime', 1.5, 0.2)], delay=4),
    'pet': E([V('block.note_block.chime', 1.5, 0.3)], layer=[V('block.note_block.chime', 1.89, 0.28), V('entity.parrot.fly', 1.8, 0.3)], delay=3, jitter=0.0),
    'happy': E([V('block.note_block.chime', 1.34, 0.3)], layer=[V('block.note_block.chime', 1.68, 0.3)], delay=3, jitter=0.0),
    'bond_up': E([V('block.note_block.chime', 2.0, 0.22)], jitter=0.0),
    'bond_level': E([V('block.note_block.chime', 1.0, 0.35)], layer=[V('block.note_block.chime', 1.5, 0.35), F('block/amethyst/shimmer', 1.6, 0.8)], delay=4, jitter=0.0),
    'tame': E([V('block.note_block.chime', 1.26, 0.35)], layer=[V('block.note_block.chime', 1.89, 0.35)], delay=4),
    'tame_fail': E([V('entity.bat.takeoff', 1.6, 0.3)]),
    'eat': E([V('item.honey_bottle.drink', 1.8, 0.3)], jitter=0.06),
    'heal': E([F('block/amethyst/shimmer', 1.7, 0.5)]),
    'hurt': E([V('entity.bat.hurt', 1.5, 0.5)], layer=[V('entity.parrot.fly', 1.9, 0.4)], delay=0, jitter=0.08),
    'death': E([V('entity.bat.death', 1.5, 0.5)], layer=[V('block.note_block.chime', 0.7, 0.3)], delay=4),
    'sit': E([V('entity.parrot.fly', 1.3, 0.3)]),
    'stand': E([V('entity.parrot.fly', 1.7, 0.35)]),
    'arrive': E([V('entity.parrot.fly', 1.7, 0.45)], layer=[V('block.note_block.chime', 1.68, 0.3)], delay=3),
    'revive': E([F('block/amethyst/shimmer', 1.4, 0.9)], layer=[V('block.note_block.chime', 1.5, 0.35)], delay=6),
    'ability': E([V('entity.parrot.fly', 1.6, 0.4)], layer=[V('block.note_block.chime', 1.78, 0.3)], delay=4),
    'step': E([V('entity.parrot.step', 1.6, 0.3)]),
}

# Honeycub: a bear cub with a bee's hum about it
PROFILES['honeycub'] = merge(critter('entity.polar_bear.ambient_baby', 1.3, hurt='entity.polar_bear.hurt', death='entity.polar_bear.death',
                                     eat='entity.panda.eat', particle='minecraft:falling_honey'), {
    '_subs': {'ambient': '{n} grumbles and buzzes', 'pet': '{n} hums happily', 'snore': '{n} snuffles', 'eat': '{n} munches',
              'ability': '{n} shares honey'},
    'ambient': E([V('entity.polar_bear.ambient_baby', 1.3, 0.6)], layer=[V('entity.bee.loop', 1.2, 0.18)], delay=2, jitter=0.08),
    'ambient_happy': E([V('entity.panda.ambient', 1.5, 0.5)], layer=[V('entity.bee.pollinate', 1.2, 0.35)], delay=6, jitter=0.07),
    'snore': E([F('mob/panda/nosebreath*', 1.2, 0.5)], jitter=0.05),
    'pet': E([V('entity.panda.ambient', 1.45, 0.6)], layer=[V('entity.bee.loop', 1.35, 0.22)], delay=4, jitter=0.05),
    'eat': E([V('entity.panda.eat', 1.4, 0.7)], layer=[V('item.honey_bottle.drink', 1.3, 0.35)], delay=8),
    'hurt': E([V('entity.polar_bear.hurt', 1.7, 0.7)], jitter=0.07),
    'death': E([V('entity.polar_bear.death', 1.7, 0.8)], layer=[V('entity.bee.death', 1.0, 0.4)], delay=4),
    'ability': E([V('item.honey_bottle.drink', 1.2, 0.5)], layer=[V('entity.bee.pollinate', 1.3, 0.5)], delay=4),
    'attack': E([V('entity.panda.bite', 1.5, 0.6)], jitter=0.06),
    'step': E([V('entity.polar_bear.step', 1.6, 0.35)], jitter=0.08),
})

# Puddlepup: splashy. Axolotl voice, water splashes, wet squelchy paws
PROFILES['puddlepup'] = merge(critter('entity.axolotl.idle_air', 1.4, hurt='entity.axolotl.hurt', death='entity.axolotl.death',
                                      particle='minecraft:splash'), {
    '_subs': {'ambient': '{n} burbles', 'pet': '{n} splashes happily', 'step': 'Wet pawsteps', 'snore': '{n} blows bubbles',
              'ability': '{n} splashes', 'splash': '{n} splashes'},
    'ambient': E([V('entity.axolotl.idle_air', 1.4, 0.65)], layer=[V('entity.generic.splash', 1.9, 0.12)], delay=6, jitter=0.08),
    'ambient_happy': E([V('entity.axolotl.splash', 1.5, 0.45)], layer=[V('entity.axolotl.idle_air', 1.55, 0.55)], delay=4, jitter=0.07),
    'snore': E([V('block.bubble_column.bubble_pop', 1.1, 0.35)], jitter=0.06),
    'pet': E([V('entity.axolotl.splash', 1.6, 0.6)], layer=[V('entity.axolotl.idle_air', 1.5, 0.6), V('block.pointed_dripstone.drip_water', 1.3, 0.6)], delay=5, jitter=0.05),
    'happy': E([V('entity.player.splash', 1.9, 0.18)], layer=[V('entity.axolotl.idle_air', 1.6, 0.7)], delay=3),
    'bond_up': E([V('block.pointed_dripstone.drip_water', 1.5, 0.7)], jitter=0.06),
    'bond_level': E([V('entity.axolotl.splash', 1.4, 0.7)], layer=[V('entity.player.splash', 1.7, 0.25), V('entity.axolotl.idle_air', 1.7, 0.7)], delay=5, jitter=0.0),
    'step': E([V('block.mud.step', 1.6, 0.35), V('block.pointed_dripstone.drip_water', 1.4, 0.25)], jitter=0.1),
    'stand': E([V('entity.wolf.shake', 1.5, 0.4)]),
    'ability': E([V('entity.axolotl.splash', 1.3, 0.8)], layer=[V('block.fire.extinguish', 1.6, 0.25)], delay=3),
    'splash': E([V('entity.axolotl.splash', 1.3, 0.8)]),
})

# Wickling: a little candle creature. Wick flicker, wax steps, a puff when it blinks out
PROFILES['wickling'] = merge(critter('entity.frog.ambient', 1.5, eat='entity.frog.eat', particle='minecraft:small_flame'), {
    '_every': 220,
    '_subs': {'ambient': '{n} flickers', 'ambient_happy': '{n} flares up', 'pet': '{n} glows warmly', 'snore': '{n} burns low',
              'step': 'Waxy footsteps', 'death': '{n} gutters out', 'ability': '{n} brightens'},
    'ambient': E([V('block.candle.ambient', 1.2, 1.0, 3), V('entity.frog.ambient', 1.6, 0.3)], jitter=0.1),
    'ambient_happy': E([V('item.firecharge.use', 2.0, 0.18)], layer=[V('block.candle.ambient', 1.4, 1.0)], delay=3, jitter=0.06),
    'ambient_sleepy': E([V('block.candle.ambient', 0.8, 0.7)], jitter=0.05),
    'ambient_scared': E([V('block.candle.extinguish', 1.6, 0.25)], layer=[V('block.candle.ambient', 1.6, 0.9)], delay=4),
    'snore': E([V('block.candle.ambient', 0.7, 0.6)], jitter=0.04),
    'sleep': E([V('block.candle.extinguish', 1.3, 0.2)]),
    'wake': E([V('item.flintandsteel.use', 1.5, 0.3)], layer=[V('block.candle.ambient', 1.2, 0.9)], delay=3),
    'pet': E([V('block.candle.ambient', 1.3, 1.0)], layer=[V('block.fire.ambient', 1.7, 0.2), V('entity.frog.ambient', 1.7, 0.25)], delay=4, jitter=0.05),
    'happy': E([V('block.candle.ambient', 1.6, 1.0)], layer=[V('item.firecharge.use', 1.9, 0.15)], delay=2),
    'bond_up': E([V('block.candle.ambient', 1.7, 0.9)], jitter=0.06),
    'bond_level': E([V('item.flintandsteel.use', 1.3, 0.5)], layer=[V('block.candle.ambient', 1.5, 1.0), V('item.firecharge.use', 1.7, 0.3)], delay=4, jitter=0.0),
    'tame': E([V('item.flintandsteel.use', 1.4, 0.5)], layer=[V('block.candle.ambient', 1.3, 1.0)], delay=4),
    'eat': E([V('item.honeycomb.wax_on', 1.5, 0.6)], layer=[V('block.candle.ambient', 1.4, 0.8)], delay=6),
    'hurt': E([V('block.candle.hit', 1.2, 0.9)], layer=[V('entity.frog.hurt', 1.6, 0.5)], delay=0, jitter=0.08),
    'death': E([V('block.candle.extinguish', 1.0, 0.9)], layer=[V('block.candle.break', 0.9, 0.8)], delay=4),
    'step': E([V('block.candle.step', 1.2, 0.8)], jitter=0.1),
    'revive': E([V('item.flintandsteel.use', 1.2, 0.6)], layer=[V('block.candle.ambient', 1.3, 1.0)], delay=4),
    'ability': E([V('block.candle.ambient', 1.5, 1.0)], layer=[V('item.firecharge.use', 2.0, 0.15)], delay=2),
})

# Cloudlamb: soft sheep with wind in its wool
PROFILES['cloudlamb'] = merge(critter('entity.sheep.ambient', 1.55, particle='minecraft:cloud'), {
    '_subs': {'ambient': '{n} bleats on the breeze', 'pet': '{n} fluffs up', 'step': 'Soft woolly steps', 'snore': '{n} breathes softly',
              'ability': '{n} puffs a cloud', 'flap': 'Wind gusts'},
    'ambient': E([V('entity.sheep.ambient', 1.55, 0.55)], layer=[F('mob/breeze/idle_air*', 1.4, 0.15)], delay=4, jitter=0.08),
    'ambient_happy': E([V('entity.sheep.ambient', 1.75, 0.55)], layer=[V('block.wool.step', 1.4, 0.5)], delay=4, jitter=0.07),
    'snore': E([F('mob/breeze/idle_air*', 0.8, 0.12)], jitter=0.05),
    'pet': E([V('block.wool.step', 1.2, 0.8, 2), V('block.wool.hit', 1.1, 0.7)],
             layer=[V('entity.sheep.ambient', 1.7, 0.4), F('mob/breeze/idle_air*', 1.6, 0.12)], delay=8, jitter=0.05),
    'step': E([V('block.wool.step', 1.3, 0.6)], jitter=0.1),
    'ability': E([F('mob/breeze/inhale*', 1.6, 0.35)], layer=[V('block.wool.place', 1.3, 0.6)], delay=6),
    'flap': E([F('mob/breeze/idle_air*', 1.3, 0.25)], jitter=0.08),
})

# Barkling: a little pale-oak sentinel. Creaking wood, rustling leaves
PROFILES['barkling'] = merge(critter('entity.creaking.ambient', 1.6, hurt='entity.creaking.sway', death='entity.creaking.death',
                                     eat='block.composter.fill', particle='minecraft:pale_oak_leaves'), {
    '_subs': {'ambient': '{n} creaks', 'pet': '{n} rustles its leaves', 'step': 'Wooden footsteps', 'snore': '{n} creaks in its sleep',
              'ambient_angry': '{n} groans', 'ability': '{n} takes root'},
    'ambient': E([V('entity.creaking.ambient', 1.6, 0.35)], layer=[V('block.azalea_leaves.step', 1.0, 0.4)], delay=6, jitter=0.08),
    'ambient_happy': E([V('block.azalea_leaves.break', 1.3, 0.45)], layer=[V('entity.creaking.twitch', 1.6, 0.3)], delay=4),
    'ambient_angry': E([V('entity.creaking.activate', 1.5, 0.5)], jitter=0.06),
    'snore': E([V('block.creaking_heart.idle', 1.2, 0.35)], jitter=0.05),
    'pet': E([V('block.azalea_leaves.step', 1.1, 0.9, 2), V('block.azalea_leaves.break', 1.2, 0.5)],
             layer=[V('entity.creaking.twitch', 1.7, 0.3), V('block.wood.step', 1.2, 0.5)], delay=6, jitter=0.05),
    'step': E([V('entity.creaking.step', 1.6, 0.35), V('block.wood.step', 1.3, 0.5)], jitter=0.1),
    'ability': E([V('block.rooted_dirt.break', 0.9, 0.8)], layer=[V('entity.creaking.activate', 1.5, 0.5)], delay=6),
    'attack': E([V('entity.creaking.attack', 1.5, 0.7)], jitter=0.06),
})

PROFILES['burrowpip'] = merge(critter('entity.armadillo.ambient', 1.6, eat='entity.armadillo.eat'), {
    '_subs': {'ambient': '{n} squeaks', 'pet': '{n} snuffles happily', 'ability': '{n} digs', 'snore': '{n} snuffles'},
    'snore': E([V('entity.sniffer.idle', 1.6, 0.3)], jitter=0.05),
    'pet': E([V('entity.sniffer.happy', 1.8, 0.5)], layer=[V('entity.armadillo.ambient', 1.7, 0.5)], delay=6, jitter=0.05),
    'ability': E([V('block.rooted_dirt.break', 1.3, 0.7)], layer=[V('item.brush.brushing.gravel', 1.3, 0.5)], delay=4),
    'step': E([V('entity.armadillo.step', 1.4, 0.6)], jitter=0.08),
})
PROFILES['mossbun'] = merge(critter('entity.rabbit.ambient', 1.4), {
    '_particle': 'minecraft:happy_villager',
    '_subs': {'ambient': '{n} squeaks', 'pet': '{n} rustles softly', 'ability': '{n} tends the garden'},
    'pet': E([V('block.moss.step', 1.2, 0.9)], layer=[V('entity.rabbit.ambient', 1.6, 0.45), V('block.azalea_leaves.step', 1.4, 0.4)], delay=6, jitter=0.05),
    'snore': E([V('block.moss.step', 0.7, 0.3)], jitter=0.05),
    'step': E([V('block.moss.step', 1.3, 0.4)], jitter=0.1),
    'ability': E([V('item.bone_meal.use', 1.2, 0.7)], layer=[V('block.azalea_leaves.step', 1.3, 0.6)], delay=4),
})
PROFILES['canopip'] = merge(critter('entity.fox.ambient', 1.6, eat='entity.generic.eat'), {
    '_subs': {'ambient': '{n} chatters', 'ambient_happy': '{n} hoots and chatters', 'pet': '{n} chitters', 'ability': '{n} replants'},
    # a monkey's chatter: excited piglin cheering pitched up and quick fox yips
    'ambient_happy': E([V('entity.piglin.celebrate', 1.9, 0.4)], layer=[V('entity.fox.ambient', 1.9, 0.5)], delay=5, jitter=0.06),
    'pet': E([V('entity.fox.ambient', 1.85, 0.55)], layer=[V('entity.piglin.celebrate', 1.95, 0.3)], delay=6, jitter=0.05),
    'snore': E([V('entity.fox.sleep', 1.6, 0.45)], jitter=0.05),
    'ability': E([V('item.crop.plant', 1.2, 0.7)], layer=[V('entity.fox.ambient', 1.9, 0.5)], delay=6),
    'step': E([V('entity.wolf.step', 1.7, 0.35), V('block.rooted_dirt.step', 1.5, 0.2)], jitter=0.1),   # light monkey pads
})
PROFILES['magpip'] = merge(critter('entity.parrot.ambient', 1.5, eat='entity.parrot.eat'), {
    '_subs': {'ambient': '{n} chatters', 'pet': '{n} chirps', 'ability': '{n} fetches', 'flap': 'Wings flap'},
    'pet': E([V('entity.parrot.ambient', 1.7, 0.6)], layer=[V('entity.parrot.fly', 1.6, 0.4)], delay=4, jitter=0.05),
    'snore': E([V('entity.parrot.ambient', 1.0, 0.18)], jitter=0.04),
    'ability': E([V('entity.allay.item_taken', 1.4, 0.6)], layer=[V('entity.parrot.ambient', 1.8, 0.5)], delay=4),
    'step': E([V('entity.parrot.step', 1.3, 0.5)]),
})
PROFILES['sporeling'] = merge(critter('entity.slime.squish_small', 1.6, hurt='entity.slime.hurt_small', death='entity.slime.death_small',
                                      particle='minecraft:spore_blossom_air'), {
    '_subs': {'ambient': '{n} squishes', 'pet': '{n} puffs spores', 'ability': '{n} puffs spores', 'step': 'Squishy steps'},
    'ambient': E([V('entity.slime.squish_small', 1.6, 0.5)], layer=[V('entity.puffer_fish.blow_out', 1.9, 0.12)], delay=6, jitter=0.08),
    'pet': E([V('entity.puffer_fish.blow_out', 1.8, 0.3)], layer=[V('entity.slime.squish_small', 1.7, 0.6), V('block.fungus.break', 1.4, 0.4)], delay=4, jitter=0.05),
    'snore': E([V('entity.puffer_fish.blow_out', 1.3, 0.15)], jitter=0.05),
    'step': E([V('block.shroomlight.step', 1.4, 0.5)], jitter=0.1),
    'ability': E([V('entity.puffer_fish.blow_out', 1.7, 0.4)], layer=[V('block.fungus.break', 1.3, 0.5)], delay=3),
})
PROFILES['hushwing'] = merge(critter('entity.bat.ambient', 1.3, eat='entity.generic.eat', particle='minecraft:sculk_soul'), {
    '_every': 320, '_dist': 10,   # hushed: quiet and short-ranged
    '_subs': {'ambient': '{n} clicks', 'pet': '{n} hoots softly', 'ability': '{n} hushes the sculk', 'flap': 'Silent wingbeats'},
    'ambient': E([V('block.sculk_sensor.clicking', 1.4, 0.25)], layer=[V('entity.bat.ambient', 1.3, 0.12)], delay=6, jitter=0.08),
    'ambient_happy': E([V('block.note_block.flute', 0.62, 0.35)], jitter=0.04),
    'pet': E([V('block.note_block.flute', 0.6, 0.4)], layer=[V('block.note_block.flute', 0.53, 0.35), V('block.sculk.step', 1.2, 0.4)], delay=8, jitter=0.0),
    'snore': E([V('block.sculk_sensor.clicking_stop', 1.0, 0.2)], jitter=0.05),
    'ability': E([V('block.sculk_catalyst.bloom', 1.5, 0.35)], layer=[V('block.sculk_sensor.clicking_stop', 1.3, 0.4)], delay=6),
    'flap': E([V('entity.phantom.flap', 1.8, 0.12)], jitter=0.08),
})
PROFILES['snowpelt_owlet'] = merge(critter('entity.parrot.ambient', 0.62, eat='entity.parrot.eat', particle='minecraft:snowflake'), {
    '_subs': {'ambient': '{n} hoots', 'pet': '{n} fluffs its feathers', 'ability': '{n} hoots into the night', 'step': 'Fluffy footsteps'},
    # the owlet's hoo-hoo: a low flute note twice
    'ambient': E([V('block.note_block.flute', 0.6, 0.4)], layer=[V('block.note_block.flute', 0.5, 0.35)], delay=7, jitter=0.03),
    'ambient_happy': E([V('entity.parrot.ambient', 0.75, 0.55)], layer=[V('block.note_block.flute', 0.67, 0.3)], delay=5),
    'pet': E([V('block.powder_snow.step', 1.2, 0.7)], layer=[V('entity.parrot.fly', 0.9, 0.4), V('block.note_block.flute', 0.67, 0.3)], delay=5, jitter=0.05),
    'snore': E([V('block.note_block.flute', 0.5, 0.15)], jitter=0.02),
    'step': E([V('block.powder_snow.step', 1.3, 0.4)], jitter=0.1),
    # an owl never squawks: joy is a quick rising hoo-hoo, sitting / standing a soft feather rustle (no parrot calls)
    'happy': E([V('block.note_block.flute', 0.67, 0.4)], layer=[V('block.note_block.flute', 0.8, 0.35)], delay=4, jitter=0.03),
    'tame': E([V('block.note_block.flute', 0.6, 0.45)], layer=[V('block.note_block.flute', 0.75, 0.4), V('entity.parrot.fly', 0.9, 0.4)], delay=6),
    'bond_level': E([V('block.note_block.flute', 0.6, 0.45)], layer=[V('block.note_block.flute', 0.9, 0.45), V('block.powder_snow.step', 1.2, 0.6)],
                    delay=5, jitter=0.0),
    'arrive': E([V('entity.parrot.fly', 0.9, 0.5)], layer=[V('block.note_block.flute', 0.67, 0.35)], delay=5),
    'sit': E([V('entity.parrot.fly', 0.8, 0.3)], layer=[V('block.powder_snow.step', 1.2, 0.35)], delay=3),
    'stand': E([V('entity.parrot.fly', 0.95, 0.35)]),
    'sleep': E([V('block.note_block.flute', 0.5, 0.2)], layer=[V('entity.parrot.fly', 0.7, 0.25)], delay=8),
    'wake': E([V('entity.parrot.fly', 0.9, 0.35)], layer=[V('block.note_block.flute', 0.6, 0.25)], delay=6),
    'attack': E([V('block.powder_snow.step', 1.0, 0.9)], layer=[V('entity.parrot.hurt', 0.9, 0.4)], delay=0),
})
PROFILES['runeback_tortoise'] = merge(critter('entity.turtle.ambient_land', 0.7, eat='entity.armadillo.eat'), {
    '_every': 340, '_particle': 'minecraft:enchant',
    '_subs': {'ambient': '{n} rumbles', 'pet': '{n} hums its runes', 'ability': '{n} raises its rune shield', 'step': 'Slow heavy steps'},
    'pet': E([V('entity.turtle.shamble', 0.8, 0.8)], layer=[V('block.enchantment_table.use', 1.4, 0.35), V('block.beacon.ambient', 1.6, 0.2)], delay=6, jitter=0.04),
    'snore': E([V('entity.turtle.ambient_land', 0.55, 0.3)], jitter=0.03),
    'sit': E([V('item.armor.equip_turtle', 0.8, 0.6)]),
    'stand': E([V('item.armor.equip_turtle', 1.0, 0.5)]),
    'step': E([V('entity.turtle.shamble', 0.8, 0.6)], jitter=0.06),
    'ability': E([V('item.armor.equip_turtle', 0.8, 0.8)], layer=[V('block.enchantment_table.use', 1.2, 0.6)], delay=4),
})
PROFILES['skyfin_ray'] = merge(critter('entity.glow_squid.ambient', 1.3, eat='entity.generic.eat', particle='minecraft:cloud'), {
    '_subs': {'ambient': '{n} sings', 'pet': '{n} ripples', 'ability': '{n} lifts you on the wind', 'flap': 'Fins sweep'},
    # a sky manta: a soft whale call from the underwater ambience under a breath of wind
    'ambient': E([F('ambient/underwater/additions/animal*', 1.5, 0.35)], layer=[F('mob/breeze/idle_air*', 1.2, 0.12)], delay=4, jitter=0.06),
    'ambient_happy': E([F('ambient/underwater/additions/bass_whale*', 1.8, 0.3)], jitter=0.05),
    'pet': E([V('entity.glow_squid.ambient', 1.4, 0.5)], layer=[F('ambient/underwater/additions/animal*', 1.7, 0.3)], delay=6, jitter=0.05),
    'snore': E([F('ambient/underwater/additions/animal*', 1.0, 0.12)], jitter=0.04),
    'ability': E([V('entity.breeze.wind_burst', 1.6, 0.3)], layer=[V('entity.phantom.flap', 1.2, 0.5)], delay=3),
    'flap': E([V('entity.phantom.flap', 1.1, 0.35)], jitter=0.08),
})
PROFILES['shellpip'] = merge(critter('entity.turtle.ambient_land', 1.65, hurt='entity.turtle.hurt_baby', death='entity.turtle.death_baby',
                                     particle='minecraft:bubble_pop'), {
    '_subs': {'ambient': '{n} chirps', 'pet': '{n} clicks its shell', 'ability': '{n} digs up a treasure'},
    'pet': E([V('block.decorated_pot.insert', 1.6, 0.5)], layer=[V('entity.turtle.shamble_baby', 1.4, 0.7), V('entity.turtle.ambient_land', 2.0, 0.4)], delay=4, jitter=0.05),
    'step': E([V('entity.turtle.shamble_baby', 1.3, 0.6)], jitter=0.08),
    'ability': E([V('item.brush.brushing.sand', 1.3, 0.6)], layer=[V('item.brush.brushing.sand.complete', 1.3, 0.6)], delay=10),
    'splash': E([V('entity.turtle.swim', 1.5, 0.5)]),
})
PROFILES['ripplekit'] = merge(critter('entity.dolphin.ambient', 1.6, hurt='entity.fox.hurt', death='entity.fox.death', eat='entity.fox.eat',
                                      particle='minecraft:splash'), {
    '_subs': {'ambient': '{n} chirps', 'ambient_happy': '{n} squeaks playfully', 'pet': '{n} squeaks happily', 'ability': '{n} watches your line'},
    'ambient': E([V('entity.dolphin.ambient', 1.6, 0.4)], jitter=0.08),
    'ambient_happy': E([V('entity.dolphin.play', 1.5, 0.5)], jitter=0.07),
    'pet': E([V('entity.dolphin.play', 1.55, 0.55)], layer=[V('entity.axolotl.splash', 1.5, 0.3)], delay=6, jitter=0.05),
    'snore': E([V('entity.fox.sleep', 1.4, 0.4)], jitter=0.04),
    'ability': E([V('entity.fishing_bobber.splash', 1.3, 0.5)], layer=[V('entity.dolphin.ambient', 1.7, 0.4)], delay=5),
    'hurt': E([V('entity.fox.hurt', 1.3, 0.9)], jitter=0.07),
    'splash': E([V('entity.dolphin.splash', 1.4, 0.6)]),
    'step': E([V('block.mud.step', 1.5, 0.3), V('block.pointed_dripstone.drip_water', 1.5, 0.15)], jitter=0.1),   # wet otter paws
})
PROFILES['bubblefin'] = merge(critter('entity.tropical_fish.flop', 1.4, hurt='entity.tropical_fish.hurt', death='entity.tropical_fish.death',
                                      particle='minecraft:bubble'), {
    '_subs': {'ambient': '{n} bubbles', 'pet': '{n} blows bubbles', 'ability': '{n} swirls the water'},
    'ambient': E([V('block.bubble_column.bubble_pop', 1.4, 0.6)], jitter=0.1),
    'ambient_happy': E([V('block.bubble_column.upwards_ambient', 1.6, 0.3)], jitter=0.07),
    'pet': E([V('block.bubble_column.bubble_pop', 1.6, 0.8)], layer=[V('block.bubble_column.bubble_pop', 1.9, 0.7)], delay=3, jitter=0.05),
    'snore': E([V('block.bubble_column.bubble_pop', 1.0, 0.25)], jitter=0.05),
    'ability': E([V('block.conduit.ambient.short', 1.5, 0.5)], layer=[V('block.bubble_column.upwards_inside', 1.4, 0.4)], delay=4),
    'step': E([V('entity.slime.squish_small', 1.9, 0.25)], jitter=0.1),   # boing on its curled tail
})
PROFILES['driftglow'] = merge(critter('entity.glow_squid.ambient', 1.55, eat='entity.generic.eat', particle='minecraft:glow'), {
    '_subs': {'ambient': '{n} glows and hums', 'pet': '{n} pulses softly', 'ability': '{n} stings'},
    'ambient': E([V('entity.glow_squid.ambient', 1.7, 0.4)], layer=[F('block/amethyst/shimmer', 1.8, 0.3)], delay=6, jitter=0.08),
    'pet': E([F('block/amethyst/shimmer', 1.6, 0.6)], layer=[V('block.bubble_column.bubble_pop', 1.5, 0.6), V('entity.glow_squid.ambient', 1.9, 0.3)], delay=4, jitter=0.05),
    'snore': E([V('block.conduit.ambient.short', 1.8, 0.15)], jitter=0.04),
    'ability': E([V('entity.glow_squid.squirt', 1.6, 0.5)], layer=[V('block.conduit.attack.target', 1.8, 0.3)], delay=3),
    'step': E([V('entity.slime.squish_small', 1.4, 0.2)], jitter=0.1),   # a jelly on land
})

# ---------------------------------------------------------------------------------------------------------- mounts

PROFILES['dunebun'] = merge(critter('entity.rabbit.ambient', 0.6, eat='entity.generic.eat'), {
    '_subs': {'ambient': '{n} snuffles', 'pet': '{n} thumps happily', 'jump': '{n} springs', 'land': '{n} thumps down'},
    'pet': E([V('entity.rabbit.jump', 0.6, 0.6)], layer=[V('entity.rabbit.jump', 0.55, 0.6), V('entity.rabbit.ambient', 0.7, 0.5)], delay=5, jitter=0.04),
    'snore': E([V('entity.rabbit.ambient', 0.5, 0.2)], jitter=0.03),
    'step': E([V('block.sand.step', 0.9, 0.5), V('block.wool.step', 0.8, 0.45)], jitter=0.08),
    'saddle': E([V('entity.horse.saddle', 1.2, 0.6)], layer=[V('entity.rabbit.ambient', 0.65, 0.6)], delay=4),
    'jump': E([V('entity.rabbit.jump', 0.6, 0.8)], jitter=0.06),
    'land': E([V('block.wool.fall', 0.7, 0.7)], jitter=0.06),
    'gallop': E([V('entity.rabbit.jump', 0.7, 0.4)], jitter=0.08),
})
PROFILES['snowmuff'] = merge(critter('entity.polar_bear.ambient_baby', 0.8, hurt='entity.polar_bear.hurt', death='entity.polar_bear.death'), {
    '_every': 320, '_particle': 'minecraft:snowflake',
    '_subs': {'ambient': '{n} rumbles', 'ambient_happy': '{n} toots its trunk', 'pet': '{n} toots happily', 'snore': '{n} snores deeply'},
    # the trunk toot: a goat horn far too low and soft
    'ambient_happy': E([V('item.goat_horn.sound.1', 1.6, 0.18)], layer=[V('entity.polar_bear.ambient_baby', 0.85, 0.6)], delay=10),
    'pet': E([V('entity.polar_bear.ambient_baby', 0.9, 0.6)], layer=[V('item.goat_horn.sound.5', 1.7, 0.15), V('block.snow.step', 0.8, 0.5)], delay=8, jitter=0.04),
    'snore': E([V('entity.polar_bear.ambient', 0.55, 0.25)], jitter=0.03),
    'step': E([V('entity.polar_bear.step', 0.6, 0.6)], layer=[V('block.snow.step', 0.8, 0.4)], delay=0, jitter=0.06),
    'saddle': E([V('entity.horse.saddle', 0.9, 0.6)], layer=[V('entity.polar_bear.ambient_baby', 0.8, 0.7)], delay=4),
    'jump': E([V('entity.goat.long_jump', 0.6, 0.6)]),
    'land': E([V('entity.polar_bear.step', 0.5, 0.9)], layer=[V('block.snow.fall', 0.6, 0.9)], delay=0),
    'gallop': E([V('entity.polar_bear.step', 0.65, 0.7)], layer=[V('block.snow.step', 0.7, 0.5)], delay=0, jitter=0.06),
})
PROFILES['cinderwing_phoenix'] = merge(critter('entity.blaze.ambient', 0.65, eat='entity.generic.eat', particle='minecraft:flame'), {
    '_every': 280, '_dist': 24,
    '_subs': {'ambient': '{n} trills over a crackle', 'pet': '{n} warms with a soft trill', 'flap': 'Fiery wingbeats',
              'ability': '{n} kindles', 'saddle': '{n} ruffles its plumage'},
    'ambient': E([V('entity.blaze.ambient', 0.65, 0.5)], layer=[V('block.fire.ambient', 1.2, 0.5)], delay=2, jitter=0.07),
    'ambient_happy': E([V('entity.parrot.ambient', 0.8, 0.6)], layer=[V('block.campfire.crackle', 1.0, 0.8)], delay=2),
    'pet': E([V('block.campfire.crackle', 0.9, 1.0)], layer=[V('entity.parrot.ambient', 0.85, 0.5), V('entity.blaze.ambient', 0.9, 0.3)], delay=5, jitter=0.05),
    'snore': E([V('block.fire.ambient', 0.8, 0.4)], jitter=0.03),
    'hurt': E([V('entity.blaze.hurt', 0.7, 0.9)], layer=[V('entity.parrot.hurt', 0.7, 0.5)], delay=0, jitter=0.07),
    'death': E([V('entity.blaze.death', 0.5, 1.0)], layer=[V('block.fire.extinguish', 0.6, 0.8)], delay=6),
    'step': E([V('entity.parrot.step', 0.6, 0.7)], jitter=0.06),
    'saddle': E([V('entity.parrot.fly', 0.7, 0.8)], layer=[V('block.campfire.crackle', 1.1, 0.8)], delay=3),
    'jump': E([V('entity.ender_dragon.flap', 1.4, 0.5)]),
    'land': E([V('entity.phantom.flap', 0.7, 0.6)], layer=[V('block.fire.extinguish', 1.6, 0.15)], delay=2),
    'flap': E([V('entity.ender_dragon.flap', 1.35, 0.35)], layer=[V('block.fire.ambient', 1.6, 0.25)], delay=0, jitter=0.08),
    'ability': E([V('item.firecharge.use', 0.9, 0.6)], layer=[V('entity.blaze.shoot', 0.8, 0.4)], delay=2),
})
PROFILES['tempest_roc'] = merge(critter('entity.phantom.ambient', 0.6, eat='entity.generic.eat', particle='minecraft:electric_spark'), {
    '_every': 280, '_dist': 24,
    '_subs': {'ambient': '{n} cries on the wind', 'pet': '{n} rumbles like distant thunder', 'flap': 'Storm wingbeats',
              'ability': '{n} calls the gale'},
    'ambient': E([V('entity.phantom.ambient', 0.5, 0.55)], layer=[F('mob/breeze/idle_air*', 0.9, 0.25)], delay=4, jitter=0.06),
    'ambient_happy': E([V('entity.parrot.ambient', 0.55, 0.6)], jitter=0.06),
    'pet': E([V('entity.parrot.ambient', 0.6, 0.55)], layer=[V('entity.lightning_bolt.thunder', 1.8, 0.08), V('entity.parrot.fly', 0.7, 0.5)], delay=6, jitter=0.04),
    'snore': E([F('mob/breeze/idle_air*', 0.6, 0.18)], jitter=0.03),
    'hurt': E([V('entity.phantom.hurt', 0.6, 0.9)], jitter=0.07),
    'death': E([V('entity.phantom.death', 0.5, 1.0)], layer=[V('entity.lightning_bolt.thunder', 1.4, 0.2)], delay=6),
    'step': E([V('entity.parrot.step', 0.5, 0.8)], jitter=0.06),
    'saddle': E([V('entity.parrot.fly', 0.6, 0.8)], layer=[V('entity.phantom.ambient', 0.6, 0.4)], delay=4),
    'jump': E([V('entity.ender_dragon.flap', 1.1, 0.6)], layer=[V('entity.breeze.wind_burst', 1.2, 0.25)], delay=0),
    'land': E([V('entity.phantom.flap', 0.6, 0.7)], layer=[V('entity.breeze.land', 0.8, 0.4)], delay=1),
    'flap': E([V('entity.ender_dragon.flap', 1.05, 0.4)], layer=[F('mob/breeze/idle_air*', 1.0, 0.2)], delay=0, jitter=0.08),
    'ability': E([V('entity.breeze.wind_burst', 0.8, 0.6)], layer=[V('entity.lightning_bolt.thunder', 1.6, 0.15)], delay=4),
})
PROFILES['tidemane'] = merge(critter('entity.horse.ambient', 0.85, hurt='entity.horse.hurt', death='entity.horse.death', eat='entity.horse.eat',
                                     particle='minecraft:splash'), {
    '_every': 300,
    '_subs': {'ambient': '{n} whinnies', 'pet': '{n} nickers', 'gallop': 'Hooves pound', 'splash': '{n} plunges in'},
    'ambient': E([V('entity.horse.ambient', 0.85, 0.6)], layer=[V('entity.dolphin.ambient_water', 1.0, 0.2)], delay=6, jitter=0.06),
    'ambient_happy': E([V('entity.horse.breathe', 0.9, 0.7)], layer=[V('entity.dolphin.play', 0.9, 0.3)], delay=6),
    'pet': E([V('entity.horse.breathe', 0.85, 0.8)], layer=[V('entity.horse.ambient', 0.95, 0.4)], delay=10, jitter=0.04),
    'snore': E([V('entity.horse.breathe', 0.6, 0.35)], jitter=0.03),
    'step': E([V('entity.horse.step', 0.85, 0.7)], jitter=0.06),
    # the mount spec's motion sounds already buckle a horse saddle on: this is the Tidemane's own snort
    'saddle': E([V('entity.horse.breathe', 0.9, 0.7)], layer=[V('entity.horse.ambient', 0.95, 0.4)], delay=6),
    'jump': E([V('entity.horse.jump', 0.85, 0.7)]),
    'land': E([V('entity.horse.land', 0.85, 0.7)]),
    'gallop': E([V('entity.horse.gallop', 0.85, 0.6)], jitter=0.06),
    'splash': E([V('entity.player.splash', 0.8, 0.6)], layer=[V('entity.dolphin.splash', 0.9, 0.6)], delay=0),
})

# ---------------------------------------------------------------------------------------------------------- tameable hostiles (eerie wild)

def hostile_extra(angry):
    return {'ambient_angry': angry}


PROFILES['cinder_imp'] = merge(critter('entity.blaze.ambient', 1.6, eat='entity.generic.eat', particle='minecraft:flame'), {
    '_every': 220,
    '_subs': {'ambient': '{n} cackles and crackles', 'ambient_angry': '{n} hisses with fire', 'pet': '{n} crackles contentedly',
              'ability': '{n} snuffs the flames'},
    'ambient': E([V('entity.blaze.ambient', 1.8, 0.45)], layer=[V('block.fire.ambient', 1.6, 0.35)], delay=2, jitter=0.08),
    'ambient_angry': E([V('entity.blaze.burn', 1.6, 0.5)], layer=[V('entity.witch.ambient', 1.8, 0.35)], delay=4, jitter=0.07),
    'ambient_happy': E([V('entity.witch.celebrate', 1.9, 0.3)], layer=[V('block.campfire.crackle', 1.4, 0.7)], delay=3),
    'pet': E([V('block.campfire.crackle', 1.5, 1.0)], layer=[V('entity.blaze.ambient', 1.9, 0.3)], delay=5, jitter=0.05),
    'snore': E([V('block.fire.ambient', 1.0, 0.35)], jitter=0.04),
    'hurt': E([V('entity.blaze.hurt', 1.7, 0.9)], jitter=0.07),
    'death': E([V('entity.blaze.death', 1.8, 0.9)], layer=[V('block.fire.extinguish', 1.3, 0.6)], delay=4),
    'ability': E([V('block.fire.extinguish', 1.5, 0.5)], layer=[V('entity.blaze.ambient', 1.9, 0.35)], delay=4),
    'attack': E([V('item.firecharge.use', 1.4, 0.55)], jitter=0.06),
})
PROFILES['rime_wraith'] = merge(critter('entity.stray.ambient', 0.7, eat='block.glass.break', particle='minecraft:snowflake'), {
    '_every': 220, '_dist': 20,
    '_subs': {'ambient': '{n} whispers in the frost', 'ambient_angry': '{n} wails', 'pet': '{n} chimes like ice',
              'hurt': '{n} cracks', 'death': '{n} shatters', 'ability': '{n} breathes frost'},
    # eerie: soul-sand-valley whispers and a stray's rattle over a cold wind
    'ambient': E([F('ambient/nether/soulsand_valley/whisper*', 1.3, 0.35)], layer=[V('entity.stray.ambient', 0.7, 0.4), F('mob/breeze/idle_air*', 0.7, 0.15)], delay=6, jitter=0.06),
    'ambient_angry': E([F('ambient/nether/soulsand_valley/voices*', 1.4, 0.45)], layer=[V('entity.stray.ambient', 0.6, 0.6)], delay=4, jitter=0.06),
    'ambient_happy': E([V('block.amethyst_block.chime', 1.5, 1.0)], layer=[F('ambient/nether/soulsand_valley/whisper*', 1.6, 0.2)], delay=4),
    'pet': E([V('block.glass.step', 1.6, 0.5)], layer=[F('block/amethyst/shimmer', 1.7, 0.6), F('ambient/nether/soulsand_valley/whisper*', 1.7, 0.18)], delay=4, jitter=0.05),
    'snore': E([F('mob/breeze/idle_air*', 0.6, 0.12)], jitter=0.03),
    'hurt': E([V('entity.stray.hurt', 0.8, 0.8)], layer=[V('block.glass.hit', 1.4, 0.5)], delay=0, jitter=0.07),
    'death': E([V('entity.stray.death', 0.9, 0.9)], layer=[V('block.glass.break', 0.8, 0.9), F('ambient/nether/soulsand_valley/voices*', 1.0, 0.3)], delay=4),
    'step': E([V('block.powder_snow.step', 0.9, 0.3)], jitter=0.08),
    'ability': E([V('entity.player.hurt_freeze', 1.2, 0.5)], layer=[F('mob/breeze/idle_air*', 0.8, 0.3)], delay=2),
    'attack': E([V('entity.player.hurt_freeze', 1.0, 0.7)], layer=[V('entity.stray.ambient', 0.6, 0.4)], delay=0),
})
PROFILES['volt_wisp'] = merge(critter('block.beacon.ambient', 2.0, hurt='entity.allay.hurt', death='entity.allay.death', particle='minecraft:electric_spark'), {
    '_every': 200,
    '_subs': {'ambient': '{n} crackles with static', 'ambient_angry': '{n} buzzes angrily', 'pet': '{n} sparks happily',
              'hurt': '{n} fizzles', 'death': '{n} bursts', 'ability': '{n} zaps'},
    'ambient': E([V('block.beacon.ambient', 2.0, 0.35)], layer=[V('block.copper_bulb.turn_on', 1.8, 0.25), V('block.sculk_sensor.clicking', 1.9, 0.2)], delay=4, jitter=0.08),
    'ambient_angry': E([V('entity.bee.loop_aggressive', 1.9, 0.25)], layer=[V('block.copper_bulb.turn_off', 1.6, 0.4)], delay=6, jitter=0.07),
    'ambient_happy': E([V('block.copper_bulb.turn_on', 2.0, 0.4)], layer=[V('block.note_block.bit', 1.8, 0.2)], delay=3),
    'pet': E([V('block.copper_bulb.turn_on', 1.9, 0.45)], layer=[V('block.note_block.bit', 1.5, 0.2), V('block.note_block.bit', 2.0, 0.2)], delay=3, jitter=0.03),
    'snore': E([V('block.beacon.ambient', 1.5, 0.18)], jitter=0.04),
    'hurt': E([V('entity.allay.hurt', 0.6, 0.8)], layer=[V('block.copper_bulb.turn_off', 1.4, 0.5)], delay=0, jitter=0.07),
    'death': E([V('entity.lightning_bolt.impact', 1.6, 0.45)], layer=[V('block.beacon.deactivate', 1.8, 0.6)], delay=2),
    'ambient_scared': E([V('block.copper_bulb.turn_off', 1.8, 0.5)], layer=[V('entity.allay.hurt', 1.2, 0.3)], delay=2),
    'happy': E([V('block.copper_bulb.turn_on', 1.9, 0.5)], layer=[V('block.note_block.bit', 1.68, 0.25)], delay=3),
    'heal': E([V('block.beacon.power_select', 1.9, 0.3)]),
    'bond_up': E([V('block.note_block.bit', 2.0, 0.2)], jitter=0.0),
    'bond_level': E([V('block.beacon.activate', 1.8, 0.5)], layer=[V('block.note_block.bit', 1.5, 0.25), V('block.note_block.bit', 2.0, 0.25)], delay=4, jitter=0.0),
    'tame': E([V('block.beacon.activate', 1.6, 0.5)], layer=[V('block.copper_bulb.turn_on', 1.9, 0.5)], delay=4),
    'arrive': E([V('block.copper_bulb.turn_on', 1.8, 0.5)], layer=[V('block.beacon.ambient', 1.9, 0.25)], delay=2),
    'revive': E([V('block.beacon.activate', 1.7, 0.6)], layer=[V('block.copper_bulb.turn_on', 2.0, 0.5)], delay=6),
    'stand': E([V('block.copper_bulb.turn_on', 1.7, 0.35)]),
    'eat': E([V('block.beacon.power_select', 2.0, 0.35)]),
    'ability': E([V('block.copper_bulb.turn_on', 1.4, 0.7)], layer=[V('entity.lightning_bolt.impact', 2.0, 0.15)], delay=2),
    'attack': E([V('block.copper_bulb.turn_on', 1.2, 0.8)], layer=[V('entity.lightning_bolt.impact', 1.9, 0.2)], delay=1),
    'flap': E([V('block.beacon.ambient', 1.9, 0.12)]),
})
PROFILES['geode_crawler'] = merge(critter('entity.silverfish.ambient', 0.6, hurt='block.amethyst_cluster.hit', death='entity.silverfish.death',
                                          eat='block.amethyst_cluster.break', particle='minecraft:wax_off'), {
    '_every': 220,
    '_subs': {'ambient': '{n} skitters and chimes', 'ambient_angry': '{n} clacks its crystals', 'pet': '{n} hums in crystal tones',
              'step': 'Crystal legs skitter', 'ability': '{n} chimes at ore'},
    'ambient': E([V('entity.silverfish.ambient', 0.6, 0.5)], layer=[V('block.amethyst_cluster.step', 1.2, 0.7)], delay=3, jitter=0.08),
    'ambient_angry': E([V('block.amethyst_cluster.hit', 0.8, 0.8)], layer=[V('entity.silverfish.ambient', 0.5, 0.6)], delay=2, jitter=0.07),
    'pet': E([F('block/amethyst/resonate*', 1.3, 0.6)], layer=[V('block.amethyst_cluster.step', 1.5, 0.7)], delay=4, jitter=0.05),
    'snore': E([F('block/amethyst/shimmer', 0.8, 0.3)], jitter=0.04),
    'death': E([V('entity.silverfish.death', 0.6, 0.9)], layer=[V('block.amethyst_cluster.break', 0.9, 0.9)], delay=2),
    'step': E([V('entity.silverfish.step', 0.7, 0.5)], layer=[V('block.amethyst_cluster.step', 1.4, 0.3)], delay=0, jitter=0.1),
    'ability': E([V('block.amethyst_block.chime', 1.6, 1.0)], layer=[F('block/amethyst/resonate*', 1.5, 0.7)], delay=4),
    'attack': E([V('block.amethyst_cluster.break', 1.3, 0.6)], jitter=0.07),
})
PROFILES['lurefin'] = {
    '_every': 200, '_dist': 20,
    '_subs': {'ambient': '{n} lure hums in the deep', 'ambient_angry': '{n} gnashes', 'hurt': '{n} thrashes', 'death': '{n} sinks',
              'attack': '{n} snaps'},
    # an anglerfish: the lure hums (conduit), the jaws are a guardian's
    'ambient': E([V('block.conduit.ambient.short', 0.8, 0.4)], layer=[V('entity.guardian.ambient', 0.6, 0.35)], delay=8, jitter=0.06),
    'ambient_angry': E([V('entity.guardian.ambient', 0.5, 0.7)], jitter=0.06),
    'hurt': E([V('entity.guardian.hurt', 0.7)], jitter=0.07),
    'death': E([V('entity.guardian.death', 0.6)], layer=[V('block.conduit.deactivate', 0.8, 0.6)], delay=4),
    'attack': E([V('entity.fox.bite', 0.55, 0.9)], layer=[V('entity.guardian.attack', 1.4, 0.3)], delay=0),
}

# ---------------------------------------------------------------------------------------------------------- wildlife & livestock


def beast(voice, p, *, hurt, death, step, extra=None, every=320, alt=None, subs=None):
    out = {
        '_every': every, '_subs': subs or {},
        'ambient': E([V(voice, p, 0.8)] + ([V(alt, p, 0.5)] if alt else []), jitter=0.08),
        'ambient_scared': E([V(hurt, p * 1.1, 0.5)], jitter=0.06),
        'hurt': E([V(hurt, p)], jitter=0.07),
        'death': E([V(death, p)]),
        'step': E([V(step, p, 0.8)], jitter=0.08),
        'eat': E([V('entity.generic.eat', p, 0.6)], jitter=0.06),
    }
    if extra:
        out.update(extra)
    return out


for young, k in ((False, 1.0), (True, 1.45)):
    sfx = '_young' if young else ''
    PROFILES['bristleboar' + sfx] = beast('entity.pig.ambient', 0.75 * k, hurt='entity.pig.hurt', death='entity.pig.death', step='entity.pig.step',
                                          alt='entity.hoglin.ambient', subs={'ambient': '{n} grunts', 'snore': '{n} snores'},
                                          extra={'snore': E([V('entity.pig.ambient', 0.55 * k, 0.15), V('entity.horse.breathe', 0.6 * k, 0.2)], jitter=0.05),
                                                 '_snore_every': 120,
                                                 'attack': E([V('entity.hoglin.attack', 1.3 * min(k, 1.3), 0.8)]),
                                                 'ambient_angry': E([V('entity.hoglin.angry', 1.2 * min(k, 1.4), 0.6)])})
    PROFILES['lanternsnail' + sfx] = beast('block.honey_block.slide', 1.6 * min(k, 1.25), hurt='entity.slime.squish_small', death='entity.slime.death_small',
                                           step='block.honey_block.step', every=420, subs={'ambient': '{n} glides', 'step': 'Slime trail'},
                                           extra={'ambient': E([V('block.honey_block.slide', 1.6 * min(k, 1.25), 0.25)], layer=[F('block/amethyst/shimmer', 1.6, 0.25)], delay=10, jitter=0.06),
                                                  'step': E([V('block.honey_block.step', 1.6, 0.15)], jitter=0.06)})
    PROFILES['loomworm' + sfx] = beast('entity.silverfish.ambient', 1.6 * min(k, 1.2), hurt='entity.silverfish.hurt', death='entity.silverfish.death',
                                       step='entity.silverfish.step', every=420, subs={'ambient': '{n} chitters', 'step': 'Tiny feet patter'},
                                       extra={'ambient': E([V('entity.silverfish.ambient', 1.6 * min(k, 1.2), 0.25)], layer=[V('block.wool.step', 1.6, 0.3)], delay=6, jitter=0.06),
                                              'step': E([V('entity.silverfish.step', 1.4, 0.15)])})
    PROFILES['pondwaddle' + sfx] = beast('entity.chicken.ambient', 0.62 * k, hurt='entity.chicken.hurt', death='entity.chicken.death', step='entity.chicken.step',
                                         subs={'ambient': '{n} quacks', 'step': 'Webbed feet slap'},
                                         extra={'step': E([V('entity.chicken.step', 0.8 * k, 0.6)], layer=[V('block.mud.step', 1.6, 0.15)], delay=0, jitter=0.08),
                                                'splash': E([V('entity.generic.splash', 1.6, 0.3)])})
    PROFILES['tufthorn' + sfx] = beast('entity.cow_moody.ambient', 0.85 * k, hurt='entity.cow_moody.hurt', death='entity.cow_moody.death', step='entity.cow.step',
                                       subs={'ambient': '{n} lows', 'snore': '{n} breathes slowly'},
                                       extra={'snore': E([V('entity.horse.breathe', 0.55 * k, 0.25)], jitter=0.05), '_snore_every': 140})
    PROFILES['wartgrazer' + sfx] = beast('entity.sheep.ambient', 0.8 * k, hurt='entity.sheep.hurt', death='entity.sheep.death', step='entity.sheep.step',
                                         subs={'ambient': '{n} bleats', 'snore': '{n} breathes softly'},
                                         extra={'step': E([V('entity.sheep.step', 0.8 * k, 0.7)], layer=[V('block.nether_wart.break', 1.3, 0.12)], delay=0, jitter=0.08),
                                                'snore': E([V('entity.horse.breathe', 0.85 * k, 0.18)], jitter=0.05), '_snore_every': 140})

PROFILES['velvetstag'] = beast('entity.goat.ambient', 1.35, hurt='entity.goat.hurt', death='entity.goat.death', step='entity.goat.step', every=420,
                               subs={'ambient': '{n} bleats softly', 'ambient_scared': '{n} snorts and bolts'},
                               extra={'ambient': E([V('entity.goat.ambient', 1.35, 0.4)], jitter=0.06),
                                      'ambient_scared': E([V('entity.horse.breathe', 1.4, 0.5)], jitter=0.06)})
PROFILES['squallgull'] = beast('entity.parrot.ambient', 0.6, hurt='entity.parrot.hurt', death='entity.parrot.death', step='entity.parrot.step', every=260,
                               subs={'ambient': '{n} cries', 'flap': 'Gull wings flap'},
                               extra={'ambient': E([V('entity.parrot.ambient', 0.6, 0.7, 2), V('entity.parrot.imitate.phantom', 1.15, 0.3)],
                                                   layer=[V('entity.parrot.fly', 0.8, 0.25)], delay=10, jitter=0.07),
                                      'flap': E([V('entity.parrot.fly', 0.8, 0.35)], jitter=0.08)})
PROFILES['duskflies'] = {
    '_every': 160, '_dist': 10,
    '_subs': {'ambient': 'Fireflies hum'},
    'ambient': E([V('block.firefly_bush.idle', 1.0, 0.4)], jitter=0.1),
}

# ---------------------------------------------------------------------------------------------------------- undead (eerie)


def undead(voice, p, *, hurt, death, step, every=240, layer=None, subs=None, extra=None):
    out = {
        '_every': every, '_dist': 20, '_subs': dict({'ambient_angry': '{n} snarls'}, **(subs or {})),
        'ambient': E([V(voice, p, 0.8)], layer=layer, delay=6, jitter=0.06),
        'ambient_angry': E([V(voice, p * 0.92, 1.0)], jitter=0.06),
        'hurt': E([V(hurt, p)], jitter=0.07),
        'death': E([V(death, p)]),
        'step': E([V(step, 1.0, 0.8)], jitter=0.08),
    }
    if extra:
        out.update(extra)
    return out


PROFILES['bilgebones'] = undead('entity.skeleton.ambient', 0.85, hurt='entity.skeleton.hurt', death='entity.skeleton.death', step='entity.skeleton.step',
                                layer=[V('block.pointed_dripstone.drip_water', 0.9, 0.6), V('block.bamboo_wood.step', 0.7, 0.3)],
                                subs={'ambient': '{n} rattles and drips'},
                                extra={'death': E([V('entity.skeleton.death', 0.85)], layer=[V('entity.generic.splash', 0.8, 0.3)], delay=6),
                                       'attack': E([V('entity.skeleton.shoot', 0.9, 0.9)])})
PROFILES['dustdigger'] = undead('entity.husk.ambient', 1.0, hurt='entity.husk.hurt', death='entity.husk.death', step='entity.husk.step',
                                layer=[V('block.sand.step', 0.8, 0.5)], subs={'ambient': '{n} groans through the dust'},
                                extra={'attack': E([V('entity.zombie.attack_iron_door', 1.6, 0.25)], layer=[V('block.stone.hit', 1.3, 0.6)], delay=0),
                                       'death': E([V('entity.husk.death', 1.0)], layer=[V('block.lantern.break', 1.0, 0.6)], delay=6)})
PROFILES['hollow_pilgrim'] = undead('entity.skeleton.ambient', 0.7, hurt='entity.skeleton.hurt', death='entity.skeleton.death', step='block.soul_sand.step',
                                    every=320, layer=[F('ambient/nether/soulsand_valley/whisper*', 1.0, 0.35), V('block.chain.step', 0.9, 0.3)],
                                    subs={'ambient': '{n} murmurs a prayer'},
                                    extra={'ambient_angry': E([F('ambient/nether/soulsand_valley/voices*', 0.9, 0.5)], layer=[V('entity.skeleton.ambient', 0.65, 0.8)], delay=4),
                                           'death': E([V('entity.skeleton.death', 0.7)], layer=[V('block.lantern.break', 0.8, 0.7), V('particle.soul_escape', 1.0, 1.0)], delay=4),
                                           'step': E([V('block.soul_sand.step', 0.9, 0.8)], layer=[V('block.chain.step', 1.1, 0.25)], delay=0, jitter=0.08)})
PROFILES['mirewalker'] = undead('entity.zombie.ambient', 0.8, hurt='entity.zombie.hurt', death='entity.zombie.death', step='block.mud.step',
                                layer=[V('block.mud.step', 0.7, 0.8)], subs={'ambient': '{n} gurgles', 'step': 'Mud squelches'},
                                extra={'death': E([V('entity.zombie.death', 0.8)], layer=[V('block.mud.break', 0.7, 0.9)], delay=4),
                                       'attack': E([V('block.mud.hit', 0.8, 0.9)])})
PROFILES['rimeshambler'] = undead('entity.zombie.ambient', 0.7, hurt='entity.zombie.hurt', death='entity.zombie.death', step='block.snow.step',
                                  layer=[F('mob/breeze/idle_air*', 0.6, 0.15), V('block.glass.step', 1.4, 0.25)], subs={'ambient': '{n} groans through the frost'},
                                  extra={'hurt': E([V('entity.zombie.hurt', 0.75)], layer=[V('block.glass.hit', 1.4, 0.4)], delay=0, jitter=0.07),
                                         'death': E([V('entity.zombie.death', 0.75)], layer=[V('block.glass.break', 0.9, 0.7)], delay=4),
                                         'attack': E([V('entity.player.hurt_freeze', 0.9, 0.6)])})
PROFILES['vinebound'] = undead('entity.zombie.ambient', 1.1, hurt='entity.zombie.hurt', death='entity.zombie.death', step='block.azalea_leaves.step',
                               layer=[V('block.azalea_leaves.break', 0.9, 0.5)], subs={'ambient': '{n} rustles and moans', 'step': 'Leaves rustle'},
                               extra={'ability': E([V('block.azalea_leaves.break', 0.7, 1.0)], layer=[V('entity.zombie.ambient', 0.9, 1.0)], delay=6,
                                                   sub='{n} rises from the leaves')})

# ---------------------------------------------------------------------------------------------------------- bosses (big)


def boss(*, ambient, angry, hurt, death, step, ability, every=160, subs=None):
    return {
        '_every': every, '_dist': 48, '_jitter': 0.05, '_subs': subs or {},
        'ambient': ambient, 'ambient_angry': angry, 'hurt': hurt, 'death': death, 'step': step, 'ability': ability,
    }


PROFILES['cinderwing_phoenix_boss'] = boss(
    ambient=E([V('entity.blaze.ambient', 0.5, 1.0)], layer=[V('block.fire.ambient', 0.8, 1.0)], delay=2),
    angry=E([V('entity.ender_dragon.growl', 1.6, 0.6)], layer=[V('entity.blaze.burn', 0.6, 0.9)], delay=4),
    hurt=E([V('entity.blaze.hurt', 0.55, 1.0)], layer=[V('entity.parrot.hurt', 0.55, 0.7)], delay=0),
    death=E([V('entity.ender_dragon.growl', 1.3, 0.8)], layer=[V('entity.blaze.death', 0.5, 1.0), V('item.firecharge.use', 0.5, 1.0)], delay=10),
    step=E([V('entity.ender_dragon.flap', 1.0, 0.4)]),
    ability=E([V('entity.ender_dragon.growl', 1.8, 0.45)], layer=[V('block.fire.ambient', 0.7, 1.0)], delay=2),
    subs={'ambient': 'Phoenix crackles', 'ambient_angry': 'Phoenix screams', 'step': 'Fiery wingbeats', 'ability': 'Phoenix shrieks'})
PROFILES['tempest_roc_boss'] = boss(
    ambient=E([V('entity.phantom.ambient', 0.45, 1.0)], layer=[F('mob/breeze/idle_air*', 0.6, 0.5)], delay=4),
    angry=E([V('entity.ender_dragon.growl', 1.3, 0.6)], layer=[V('entity.lightning_bolt.thunder', 1.5, 0.3)], delay=6),
    hurt=E([V('entity.phantom.hurt', 0.5, 1.0)]),
    death=E([V('entity.phantom.death', 0.4, 1.0)], layer=[V('entity.lightning_bolt.thunder', 0.9, 0.6), V('entity.breeze.wind_burst', 0.6, 0.8)], delay=8),
    step=E([V('entity.ender_dragon.flap', 0.85, 0.45)]),
    ability=E([V('entity.ender_dragon.growl', 1.5, 0.45)], layer=[V('entity.breeze.wind_burst', 0.8, 0.6)], delay=3),
    subs={'ambient': 'Roc cries in the storm', 'ambient_angry': 'Roc screeches', 'step': 'Storm wingbeats', 'ability': 'Roc calls the storm'})
PROFILES['glacial_wyrm'] = boss(
    ambient=E([V('entity.ravager.ambient', 0.6, 1.0)], layer=[F('mob/breeze/idle_air*', 0.5, 0.5), V('block.glass.step', 0.8, 0.5)], delay=6),
    angry=E([V('entity.ravager.roar', 0.7, 0.9)], layer=[V('entity.ender_dragon.growl', 0.8, 0.5)], delay=6),
    hurt=E([V('entity.ravager.hurt', 0.65, 1.0)], layer=[V('block.glass.hit', 0.9, 0.7)], delay=0),
    death=E([V('entity.ender_dragon.growl', 0.6, 1.0)], layer=[V('block.glass.break', 0.6, 1.0), V('entity.ravager.death', 0.6, 1.0)], delay=6),
    step=E([V('entity.ravager.step', 0.6, 0.9)], layer=[V('block.glass.step', 0.7, 0.4)], delay=0),
    ability=E([V('entity.ravager.roar', 0.8, 0.6)], layer=[F('mob/breeze/inhale*', 0.6, 0.6)], delay=4),
    subs={'ambient': 'Wyrm rumbles beneath the ice', 'ambient_angry': 'Wyrm roars', 'step': 'Wyrm coils grind', 'ability': 'Wyrm draws breath'})
PROFILES['runebound_colossus'] = boss(
    ambient=E([V('block.beacon.ambient', 0.6, 0.8)], layer=[V('block.deepslate.step', 0.5, 1.0), V('block.grindstone.use', 0.5, 0.3)], delay=6),
    angry=E([V('entity.warden.agitated', 1.3, 0.7)], layer=[V('block.respawn_anchor.charge', 0.6, 0.7)], delay=4),
    hurt=E([V('entity.iron_golem.hurt', 0.6, 1.0)], layer=[V('block.deepslate.hit', 0.6, 1.0)], delay=0),
    death=E([V('entity.iron_golem.death', 0.5, 1.0)], layer=[V('block.beacon.deactivate', 0.5, 1.0), V('block.anvil.land', 0.5, 0.6)], delay=6),
    step=E([V('entity.iron_golem.step', 0.5, 1.0)], layer=[V('block.deepslate.step', 0.5, 0.8)], delay=0),
    ability=E([V('block.respawn_anchor.charge', 0.7, 0.8)], layer=[V('block.beacon.power_select', 0.6, 0.7)], delay=4),
    subs={'ambient': 'Colossus hums with runes', 'ambient_angry': 'Colossus grinds', 'step': 'Colossus stomps', 'ability': 'Runes flare'})
PROFILES['wukong'] = boss(
    ambient=E([V('entity.vindicator.ambient', 1.25, 0.8)], layer=[V('entity.witch.celebrate', 1.5, 0.35)], delay=8),
    angry=E([V('entity.vindicator.celebrate', 1.2, 0.9)], jitter=0.05),
    hurt=E([V('entity.vindicator.hurt', 1.25, 1.0)], layer=[V('block.bamboo.hit', 0.8, 0.5)], delay=0),
    death=E([V('entity.evoker.death', 1.1, 1.0)], layer=[V('block.bell.resonate', 0.8, 0.8), V('block.bell.use', 0.6, 0.6)], delay=8),
    step=E([V('block.bamboo_wood.step', 0.9, 0.6)]),
    ability=E([V('entity.witch.celebrate', 1.4, 0.45)], layer=[V('item.trident.throw', 0.8, 0.6)], delay=2),
    every=200,
    subs={'ambient': 'Wukong laughs', 'ambient_angry': 'Wukong taunts', 'step': 'Light footsteps', 'ability': 'Wukong cackles'})
PROFILES['wukong_clone'] = boss(
    ambient=E([V('entity.witch.celebrate', 1.7, 0.4)]),
    angry=E([V('entity.vindicator.celebrate', 1.5, 0.6)]),
    hurt=E([V('entity.vindicator.hurt', 1.5, 0.8)]),
    death=E([V('entity.evoker.prepare_summon', 1.8, 0.4)], layer=[F('mob/breeze/inhale*', 1.6, 0.4)], delay=2),
    step=E([V('block.bamboo_wood.step', 1.2, 0.4)]),
    ability=E([V('entity.witch.celebrate', 1.8, 0.35)]),
    every=260,
    subs={'ambient': 'Clone giggles', 'ambient_angry': 'Clone jeers', 'death': 'Clone poofs', 'step': 'Light footsteps', 'ability': 'Clone cackles'})
for k in ('wukong', 'wukong_clone'):
    PROFILES[k]['_dist'] = 40

# Boss clip sounds (p2-bosses): played by the attack / idle / phase clips' sound keyframes on their exact frames
# (animkit Clip.sound -> CreatureAnim), so every wind-up, swing and impact is heard when it is seen. Named ability_* so
# the generator accepts them; the skills' own Java sounds (explosions, fire, thunder) layer on top at the same ticks.
BOSS_SFX = {
    'runebound_colossus': {
        'ability_grind': E([V('block.grindstone.use', 0.5, 1.0)], layer=[V('block.deepslate.break', 0.55, 0.6)], delay=3, sub='Colossus grinds'),
        'ability_hum': E([V('block.beacon.ambient', 0.55, 1.0)], layer=[V('block.respawn_anchor.charge', 0.6, 0.5)], delay=4, sub='Runes hum'),
        'ability_heave': E([V('entity.iron_golem.repair', 0.5, 0.9)], layer=[V('block.deepslate.break', 0.6, 0.6)], delay=2, sub='Colossus heaves'),
        'ability_whoosh': E([V('entity.player.attack.sweep', 0.5, 1.0)], layer=[V('entity.breeze.wind_burst', 0.6, 0.5)], delay=1, sub='Stone fists swing'),
        'ability_slam': E([V('entity.iron_golem.attack', 0.5, 1.0), V('block.anvil.land', 0.5, 0.8)],
                          layer=[V('entity.generic.explode', 0.6, 0.7), V('block.deepslate.break', 0.5, 1.0)], delay=1, dist=48, sub='Colossus slams the ground'),
        'ability_stomp': E([V('entity.iron_golem.step', 0.5, 1.0)], layer=[V('block.deepslate.break', 0.6, 0.6)], delay=1, sub='Colossus stamps'),
        'ability_roar': E([V('entity.warden.roar', 0.6, 1.0)], layer=[V('block.respawn_anchor.charge', 0.6, 0.9), V('entity.iron_golem.repair', 0.5, 0.8)],
                          delay=3, dist=48, sub='Colossus bellows'),
        'ability_crash': E([V('block.deepslate.break', 0.5, 1.0), V('entity.generic.explode', 0.5, 0.7)],
                           layer=[V('block.anvil.land', 0.5, 0.7)], delay=3, dist=48, sub='Stone crashes down'),
        'ability_throw': E([V('entity.ravager.attack', 0.6, 1.0)], layer=[V('entity.breeze.wind_burst', 0.6, 0.7)], delay=2, sub='Boulder hurled'),
        'ability_ward': E([V('block.beacon.power_select', 0.6, 1.0)], layer=[V('block.enchantment_table.use', 0.6, 0.9)], delay=3, sub='Runic ward rises'),
    },
    'glacial_wyrm': {
        'ability_inhale': E([F('mob/breeze/inhale*', 0.55, 1.0)], layer=[V('entity.ravager.ambient', 0.5, 0.5)], delay=4, sub='Wyrm draws breath'),
        'ability_breath': E([V('entity.breeze.wind_burst', 0.5, 0.9), V('block.powder_snow.break', 0.6, 0.9)],
                            layer=[V('entity.player.hurt_freeze', 0.6, 0.7), V('block.glass.break', 1.4, 0.3)], delay=3, dist=48, sub='Freezing breath'),
        'ability_grind': E([V('block.glass.step', 0.5, 0.8), V('entity.ravager.step', 0.55, 0.6)], sub='Ice coils grind'),
        'ability_hiss': E([V('entity.ravager.stunned', 0.75, 0.8)], layer=[F('mob/breeze/inhale*', 0.8, 0.5)], delay=2, sub='Wyrm hisses'),
        'ability_roar': E([V('entity.ravager.roar', 0.55, 1.0), V('entity.ender_dragon.growl', 0.7, 0.7)],
                          layer=[V('block.glass.break', 0.6, 0.6)], delay=6, dist=56, sub='Wyrm roars'),
        'ability_shatter': E([V('block.glass.break', 0.55, 1.0), V('block.amethyst_block.break', 0.6, 0.8)],
                             layer=[V('block.glass.break', 0.8, 0.6)], delay=3, dist=48, sub='Ice shatters'),
        'ability_slam': E([V('entity.ravager.attack', 0.55, 1.0), V('entity.generic.explode', 0.7, 0.6)],
                          layer=[V('block.glass.break', 0.7, 0.7)], delay=1, dist=48, sub='Wyrm slams down'),
        'ability_whoosh': E([V('entity.player.attack.sweep', 0.5, 1.0)], layer=[V('entity.breeze.wind_burst', 0.7, 0.6)], delay=1, sub='Tail whips'),
        'ability_dig': E([V('entity.warden.dig', 0.7, 0.9)], layer=[V('block.snow.break', 0.6, 0.8)], delay=4, dist=48, sub='Wyrm burrows'),
        'ability_bite': E([V('entity.ravager.attack', 0.65, 1.0)], layer=[V('block.glass.hit', 0.6, 0.8)], delay=1, sub='Wyrm bites'),
    },
    'cinderwing_phoenix_boss': {
        'ability_screech': E([V('entity.ender_dragon.growl', 1.6, 0.7), V('entity.parrot.imitate.blaze', 0.55, 0.8)],
                             layer=[V('entity.blaze.ambient', 0.55, 0.8)], delay=3, dist=56, sub='Phoenix screeches'),
        'ability_whoosh': E([V('entity.ender_dragon.flap', 0.8, 1.0)], layer=[V('block.fire.ambient', 0.9, 0.8)], delay=1, sub='Fiery wingbeat'),
        'ability_flare': E([V('item.firecharge.use', 0.6, 0.8)], layer=[V('block.fire.ambient', 0.6, 1.0)], delay=2, sub='Flames flare'),
        'ability_fireball': E([V('entity.blaze.shoot', 0.6, 1.0)], layer=[V('item.firecharge.use', 0.7, 0.6)], delay=1, sub='Phoenix hurls fire'),
        'ability_slam': E([V('entity.generic.explode', 0.7, 0.7), V('item.firecharge.use', 0.5, 0.8)],
                          layer=[V('block.fire.extinguish', 0.6, 0.6)], delay=2, dist=48, sub='Phoenix crashes down'),
        'ability_inhale': E([F('mob/breeze/inhale*', 0.6, 0.8)], layer=[V('block.respawn_anchor.charge', 0.7, 0.8)], delay=3, sub='Embers gather'),
        'ability_rake': E([V('entity.phantom.bite', 0.6, 1.0)], layer=[V('entity.player.attack.sweep', 0.8, 0.6)], delay=1, sub='Talons rake'),
    },
    'tempest_roc_boss': {
        'ability_screech': E([V('entity.phantom.ambient', 0.45, 1.0), V('entity.ender_dragon.growl', 1.4, 0.5)],
                             layer=[F('mob/breeze/idle_air*', 0.6, 0.6)], delay=3, dist=56, sub='Roc screeches'),
        'ability_whoosh': E([V('entity.ender_dragon.flap', 0.7, 1.0)], layer=[V('entity.breeze.wind_burst', 0.8, 0.5)], delay=1, sub='Storm wingbeat'),
        'ability_flare': E([V('block.copper_bulb.turn_on', 0.6, 0.8)], layer=[F('mob/breeze/idle_air*', 0.6, 0.6)], delay=2, sub='Static crackles'),
        'ability_rake': E([V('entity.phantom.bite', 0.5, 1.0)], layer=[V('entity.player.attack.sweep', 0.7, 0.6)], delay=1, sub='Talons strike'),
        'ability_crackle': E([V('block.copper_bulb.turn_on', 0.5, 1.0), V('block.respawn_anchor.charge', 1.3, 0.5)],
                             layer=[V('entity.lightning_bolt.impact', 1.5, 0.4)], delay=4, sub='Storm crackles'),
        'ability_thunder': E([V('entity.lightning_bolt.thunder', 1.0, 0.8)], layer=[V('entity.breeze.wind_burst', 0.5, 1.0)], delay=2, dist=64, sub='Thunder breaks'),
        'ability_gust': E([V('entity.breeze.wind_burst', 0.6, 1.0)], layer=[V('entity.breeze.whirl', 0.6, 0.6)], delay=2, dist=48, sub='A gale bursts'),
    },
    'wukong': {
        'ability_whoosh': E([V('entity.player.attack.sweep', 0.8, 0.8)], layer=[V('entity.breeze.wind_burst', 1.2, 0.3)], delay=1, sub='Staff whistles'),
        'ability_kiai': E([V('entity.vindicator.celebrate', 1.3, 0.9)], sub='Wukong shouts'),
        'ability_grunt': E([V('entity.vindicator.hurt', 1.1, 1.0)], sub='Wukong grunts'),
        'ability_thud': E([V('entity.iron_golem.step', 0.8, 0.8)], layer=[V('block.stone.place', 0.7, 0.7)], delay=1, sub='Wukong drops to a knee'),
        'ability_stone': E([V('block.stone.break', 0.6, 1.0)], layer=[V('block.bell.resonate', 0.8, 0.7)], delay=4, dist=48, sub='Stone creeps over the Sage'),
        # 2026-11-20 redesign (WUKONG.md)
        'ability_laugh': E([V('entity.witch.celebrate', 1.35, 1.0), V('entity.vindicator.celebrate', 1.5, 0.9)],
                           layer=[V('entity.witch.celebrate', 1.6, 0.7)], delay=5, dist=48, sub='Wukong cackles'),
        'ability_chew': E([V('entity.generic.eat', 0.8, 0.9)], sub='Wukong munches a peach'),
        'ability_gong': E([V('block.bell.use', 0.5, 1.0)], layer=[V('block.bell.resonate', 0.6, 0.8)], delay=2, dist=64, sub='A heavenly gong'),
        'ability_poof': E([V('entity.evoker.prepare_summon', 1.6, 0.6)], layer=[F('mob/breeze/inhale*', 1.4, 0.6)], delay=1, sub='Wukong vanishes'),
        'ability_grow': E([V('entity.warden.sonic_charge', 0.6, 0.8)], layer=[V('entity.ravager.roar', 0.5, 0.9)], delay=8, dist=64, sub='Wukong grows to the sky'),
        'ability_cloud': E([V('entity.breeze.jump', 0.7, 0.9)], layer=[V('entity.breeze.whirl', 0.8, 0.6)], delay=2, sub='Cloud billows'),
        'ability_roar': E([V('entity.ravager.roar', 0.9, 1.0)], layer=[V('entity.vindicator.celebrate', 0.9, 1.0)], delay=3, dist=64, sub='Wukong roars'),
        'ability_slam': E([V('entity.iron_golem.attack', 0.5, 1.0), V('block.anvil.land', 0.5, 0.8)],
                          layer=[V('entity.generic.explode', 0.55, 0.8)], delay=1, dist=56, sub='The staff crashes down'),
        'ability_strain': E([V('entity.vindicator.hurt', 0.8, 1.0)], layer=[V('block.stone.hit', 0.6, 0.8)], delay=3, sub='Wukong strains'),
    },
    'wukong_clone': {
        'ability_whoosh': E([V('entity.player.attack.sweep', 1.0, 0.5)], sub='Staff whistles'),
        'ability_kiai': E([V('entity.vindicator.celebrate', 1.6, 0.5)], sub='Clone shouts'),
        'ability_grunt': E([V('entity.vindicator.hurt', 1.5, 0.6)], sub='Clone grunts'),
        'ability_thud': E([V('block.bamboo_wood.step', 0.9, 0.6)], sub='Clone drops'),
        'ability_stone': E([F('mob/breeze/inhale*', 1.6, 0.4)], sub='Clone fades'),
    },
    'police': {
        'ability_radio': E([V('block.copper_bulb.turn_off', 1.6, 0.5), V('block.note_block.bit', 1.9, 0.25)],
                           layer=[V('block.copper_bulb.turn_on', 1.4, 0.4)], delay=2, sub='Police radio crackles'),
    },
}
PROFILES['police'] = {
    '_every': 400, '_dist': 20,
    '_subs': {'ambient': 'Officer grumbles', 'ambient_angry': 'Officer shouts', 'hurt': 'Officer hurts', 'death': 'Officer falls'},
    'ambient': E([V('entity.villager.ambient', 0.8, 0.6)], jitter=0.05),
    'ambient_angry': E([V('entity.vindicator.ambient', 1.1, 0.7)], jitter=0.05),
    'hurt': E([V('entity.player.hurt', 1.0)], jitter=0.07),
    'death': E([V('entity.player.death', 1.0)]),
}

# the boss clip sounds (BOSS_SFX above) join their profiles once every profile exists
for k, extra in BOSS_SFX.items():
    PROFILES[k].update(extra)

# ---------------------------------------------------------------------------------------------------------- the End (Creatures 2)
# Chorusling: a walking chorus sprout. Its voice is the chorus flower (the creak of it growing), its body chorus wood;
# the seed on its crown rings like an end rod; its job sound is the chorus-fruit teleport.
PROFILES['chorusling'] = merge(critter('block.chorus_flower.grow', 1.5, hurt='block.chorus_flower.death', death='block.chorus_flower.death',
                                       eat='entity.generic.eat', particle='minecraft:reverse_portal'), {
    '_every': 300, '_snore_every': 110,
    '_subs': {'ambient': '{n} creaks softly', 'ambient_happy': '{n} chimes', 'pet': '{n} rustles and chimes', 'hurt': '{n} snaps',
              'death': '{n} withers', 'snore': '{n} creaks in its sleep', 'step': 'Rooty footsteps', 'ability': '{n} pulls you back',
              'eat': '{n} munches chorus', 'tame_fail': '{n} turns away'},
    'ambient': E([V('block.chorus_flower.grow', 1.5, 0.45)], layer=[F('block/amethyst/shimmer', 1.7, 0.2)], delay=6, jitter=0.08),
    'ambient_happy': E([V('block.chorus_flower.grow', 1.7, 0.5)], layer=[F('block/amethyst/shimmer', 1.8, 0.45)], delay=4, jitter=0.06),
    'ambient_sleepy': E([V('block.chorus_flower.grow', 1.1, 0.25)], jitter=0.05),
    'ambient_scared': E([V('block.chorus_flower.death', 1.6, 0.4)], jitter=0.06),
    'snore': E([V('block.chorus_flower.grow', 0.7, 0.12)], jitter=0.04),
    'pet': E([V('block.chorus_flower.grow', 1.35, 0.6)], layer=[F('block/amethyst/shimmer', 1.5, 0.6), V('block.azalea_leaves.step', 1.4, 0.4)],
             delay=5, jitter=0.05),
    'happy': E([F('block/amethyst/shimmer', 1.6, 0.7)], layer=[V('block.chorus_flower.grow', 1.8, 0.5)], delay=4),
    'eat': E([V('entity.generic.eat', 1.4, 0.6)], layer=[V('item.chorus_fruit.teleport', 1.9, 0.15)], delay=8, jitter=0.06),
    'hurt': E([V('block.chorus_flower.death', 1.5, 0.8)], layer=[V('block.wood.hit', 1.4, 0.5)], delay=1, jitter=0.07),
    'death': E([V('block.chorus_flower.death', 1.0, 1.0)], layer=[V('item.chorus_fruit.teleport', 1.2, 0.5)], delay=6),
    'step': E([V('block.wood.step', 1.5, 0.5), V('block.azalea_leaves.step', 1.3, 0.4)], jitter=0.1),
    'sit': E([V('block.wood.place', 1.4, 0.35)]),
    'stand': E([V('block.chorus_flower.grow', 1.3, 0.35)]),
    'ability': E([V('item.chorus_fruit.teleport', 1.0, 0.9)], layer=[F('block/amethyst/resonate*', 1.4, 0.7)], delay=4),
    'arrive': E([V('item.chorus_fruit.teleport', 1.4, 0.5)], layer=[V('block.chorus_flower.grow', 1.6, 0.5)], delay=5),
})
# Shulkling: a young shulker. Shulker voice pitched up for its size; the shell opens and closes (sit / stand, sleep /
# wake); its hops land on purpur; the frost crystal on its lid rings.
PROFILES['shulkling'] = merge(critter('entity.shulker.ambient', 1.55, hurt='entity.shulker.hurt', death='entity.shulker.death',
                                      eat='entity.generic.eat', particle='minecraft:end_rod'), {
    '_every': 280, '_snore_every': 120,
    '_subs': {'ambient': '{n} murmurs', 'ambient_happy': '{n} peeks out and chirps', 'pet': '{n} clicks its shell happily',
              'hurt': '{n} snaps shut', 'death': '{n} closes for good', 'snore': '{n} hums in its shell', 'step': 'Shell hops',
              'sit': '{n} closes its shell', 'stand': '{n} opens its shell', 'ability': '{n} hums in shulker', 'tame_fail': '{n} hides'},
    'ambient': E([V('entity.shulker.ambient', 1.55, 0.5)], jitter=0.08),
    'ambient_happy': E([V('entity.shulker.open', 1.7, 0.4)], layer=[V('entity.shulker.ambient', 1.75, 0.45)], delay=4, jitter=0.06),
    'ambient_scared': E([V('entity.shulker.close', 1.6, 0.6)], jitter=0.06),
    'snore': E([V('entity.shulker.ambient', 0.9, 0.12)], jitter=0.04),
    'sleep': E([V('entity.shulker.close', 1.4, 0.5)]),
    'wake': E([V('entity.shulker.open', 1.5, 0.5)], layer=[V('entity.shulker.ambient', 1.6, 0.35)], delay=6),
    'pet': E([V('entity.shulker.open', 1.7, 0.45)], layer=[V('entity.shulker.ambient', 1.8, 0.5), F('block/amethyst/shimmer', 1.6, 0.35)],
             delay=4, jitter=0.05),
    'happy': E([V('entity.shulker.ambient', 1.8, 0.6)], layer=[F('block/amethyst/shimmer', 1.7, 0.5)], delay=4),
    'hurt': E([V('entity.shulker.hurt_closed', 1.5, 0.9)], jitter=0.07),
    'death': E([V('entity.shulker.death', 1.4, 1.0)], layer=[V('entity.shulker.close', 1.2, 0.6)], delay=4),
    'step': E([V('block.stone.step', 1.6, 0.25), V('entity.shulker.close', 1.9, 0.08)], jitter=0.1),
    'sit': E([V('entity.shulker.close', 1.6, 0.45)]),
    'stand': E([V('entity.shulker.open', 1.6, 0.45)]),
    'ability': E([V('entity.shulker.teleport', 1.6, 0.4)], layer=[F('block/amethyst/resonate*', 1.5, 0.6)], delay=3),
    'ability_endercalm': E([V('entity.enderman.ambient', 1.6, 0.25)], layer=[F('block/amethyst/resonate*', 1.3, 0.6)], delay=4,
                           sub='Shulkling hums to the endermen'),
    'arrive': E([V('entity.shulker.teleport', 1.5, 0.5)], layer=[V('entity.shulker.ambient', 1.7, 0.5)], delay=5),
})
# Voidpuff: a balloon puffer of the outer End. A soft pufferfish breath over a glow-squid hum; puffing up is the
# pufferfish blow-up.
PROFILES['voidpuff'] = {
    '_every': 300, '_dist': 14,
    '_subs': {'ambient': '{n} burbles', 'ambient_scared': '{n} puffs up', 'hurt': '{n} puffs up', 'death': '{n} deflates'},
    'ambient': E([V('entity.puffer_fish.blow_out', 1.2, 0.35)], layer=[V('entity.glow_squid.ambient', 1.6, 0.25)], delay=8, jitter=0.07),
    'ambient_scared': E([V('entity.puffer_fish.blow_up', 1.1, 0.5)], jitter=0.06),
    'hurt': E([V('entity.puffer_fish.blow_up', 0.9, 0.9)], layer=[V('entity.puffer_fish.hurt', 0.9, 0.6)], delay=2, jitter=0.07),
    'death': E([V('entity.puffer_fish.death', 0.8, 1.0)], layer=[V('entity.puffer_fish.blow_out', 0.7, 0.7)], delay=4),
    'eat': E([V('entity.generic.eat', 1.2, 0.5)], jitter=0.06),
}
# ---------------------------------------------------------------------------------------------------------- Creatures 2: Nether
# Soulmander: a newt of soul fire. Its voice is a small frog chirp under soul-fire whispers (the soul sand valley's
# escaping souls), its body cooled basalt; its flames hiss on hurt and gutter out on death.
PROFILES['soulmander'] = merge(critter('entity.frog.ambient', 1.45, hurt='entity.frog.hurt', death='entity.frog.death',
                                       eat='entity.frog.eat', particle='minecraft:soul_fire_flame'), {
    '_every': 240,
    '_subs': {'ambient': '{n} whispers and flickers', 'ambient_happy': '{n} flares blue', 'pet': '{n} glows softly',
              'snore': '{n} smolders low', 'step': 'Basalt toes patter', 'death': '{n} gutters out', 'ability': '{n} fans its soul fire'},
    'ambient': E([V('particle.soul_escape', 1.0, 0.9, 2), V('entity.frog.ambient', 1.45, 0.35)], layer=[V('block.fire.ambient', 1.5, 0.25)], delay=3, jitter=0.08),
    'ambient_happy': E([V('item.firecharge.use', 1.8, 0.2)], layer=[V('particle.soul_escape', 1.2, 1.0)], delay=3, jitter=0.06),
    'ambient_sleepy': E([V('block.fire.ambient', 0.7, 0.3)], jitter=0.05),
    'snore': E([V('block.fire.ambient', 0.6, 0.35)], layer=[V('particle.soul_escape', 0.7, 0.5)], delay=10, jitter=0.04),
    '_snore_every': 120,
    'sleep': E([V('block.fire.extinguish', 1.7, 0.12)]),
    'wake': E([V('item.flintandsteel.use', 1.3, 0.3)], layer=[V('particle.soul_escape', 1.1, 0.9)], delay=3),
    'pet': E([V('particle.soul_escape', 1.2, 1.0)], layer=[V('block.fire.ambient', 1.7, 0.25), V('entity.frog.ambient', 1.6, 0.25)], delay=4, jitter=0.05),
    'happy': E([V('entity.blaze.shoot', 2.0, 0.15)], layer=[V('entity.frog.ambient', 1.6, 0.6)], delay=3),
    'bond_up': E([V('particle.soul_escape', 1.4, 0.9)], jitter=0.06),
    'bond_level': E([V('item.firecharge.use', 1.6, 0.35)], layer=[V('particle.soul_escape', 1.3, 1.0), V('block.respawn_anchor.charge', 1.8, 0.2)], delay=4, jitter=0.0),
    'tame': E([V('item.flintandsteel.use', 1.2, 0.5)], layer=[V('particle.soul_escape', 1.1, 1.0)], delay=4),
    'eat': E([V('entity.frog.eat', 1.3, 0.7)], layer=[V('block.bone_block.break', 1.6, 0.35)], delay=4),
    'hurt': E([V('entity.frog.hurt', 1.4, 0.8)], layer=[V('block.fire.extinguish', 1.9, 0.15)], delay=1, jitter=0.07),
    'death': E([V('block.fire.extinguish', 0.9, 0.8)], layer=[V('particle.soul_escape', 0.8, 1.0)], delay=5),
    'step': E([V('block.soul_soil.step', 1.5, 0.35)], jitter=0.1),
    'ability': E([V('entity.blaze.shoot', 1.6, 0.25)], layer=[V('particle.soul_escape', 1.0, 1.0)], delay=3),
    'attack': E([V('entity.frog.eat', 1.4, 0.8)], layer=[V('item.firecharge.use', 1.8, 0.25)], delay=1),
})

# Fumetail: a skunk. Fox chitters and sniffs (small, high), a hiss when it sprays; its musk "whoosh" is a soft breeze
# puff over a fungus squish.
PROFILES['fumetail'] = merge(critter('entity.fox.sniff', 1.35, hurt='entity.fox.hurt', death='entity.fox.death',
                                     eat='entity.fox.eat', particle='minecraft:warped_spore'), {
    '_every': 260,
    '_subs': {'ambient': '{n} snuffles', 'ambient_happy': '{n} chitters', 'ambient_angry': '{n} hisses',
              'pet': '{n} chirrs', 'snore': '{n} snores softly', 'ability': '{n} fans its musk', 'step': 'Soft paws pad'},
    'ambient': E([V('entity.fox.sniff', 1.35, 0.6), V('entity.fox.ambient', 1.5, 0.35)], jitter=0.08),
    'ambient_happy': E([V('entity.fox.ambient', 1.6, 0.6)], layer=[V('entity.fox.sniff', 1.5, 0.4)], delay=5, jitter=0.07),
    'ambient_angry': E([V('entity.cat.hiss', 1.4, 0.55)], jitter=0.06),
    'snore': E([V('entity.fox.sleep', 1.3, 0.45)], jitter=0.05),
    '_snore_every': 110,
    'pet': E([V('entity.fox.ambient', 1.55, 0.5)], layer=[V('block.fungus.step', 1.4, 0.35)], delay=4, jitter=0.06),
    'happy': E([V('entity.fox.ambient', 1.7, 0.7)], layer=[V('entity.fox.sniff', 1.6, 0.5)], delay=3),
    'tame': E([V('entity.fox.ambient', 1.5, 0.8)], layer=[V('block.fungus.place', 1.2, 0.6)], delay=6),
    'eat': E([V('entity.fox.eat', 1.3, 0.8)], layer=[V('block.roots.break', 1.3, 0.4)], delay=4),
    'step': E([V('block.wool.step', 1.6, 0.25)], jitter=0.1),
    'ability': E([V('entity.cat.hiss', 1.3, 0.4)], layer=[V('entity.breeze.idle_ground', 1.8, 0.25), V('block.fungus.break', 0.9, 0.5)], delay=2),
    'ability_spray': E([V('entity.cat.hiss', 1.5, 0.6)], layer=[V('block.honey_block.slide', 1.8, 0.4)], delay=1, sub='{n} sprays'),
    'attack': E([V('entity.fox.bite', 1.3, 0.8)], jitter=0.06),
})

# Shroomwallow (+ pups): a capybara of the crimson forest. Low contented hoglin grunts pitched up (no hoglin anger),
# chewing, nylium footsteps; brushing rings the shroomlight.
for young, k in ((False, 1.0), (True, 1.45)):
    sfx = '_young' if young else ''
    PROFILES['shroomwallow' + sfx] = beast('entity.hoglin.ambient', 1.4 * k, hurt='entity.hoglin.hurt', death='entity.hoglin.death',
                                           step='block.nylium.step', every=380,
                                           subs={'ambient': '{n} grunts contentedly', 'snore': '{n} snores', 'step': 'Heavy paws on nylium'},
                                           extra={'ambient': E([V('entity.hoglin.ambient', 1.4 * k, 0.35)], layer=[V('entity.generic.eat', 0.8 * k, 0.2)], delay=8, jitter=0.07),
                                                  'step': E([V('block.nylium.step', 0.9 * k, 0.5)], jitter=0.08),
                                                  'snore': E([V('entity.horse.breathe', 0.6 * k, 0.22)], jitter=0.05), '_snore_every': 130})
# ---------------------------------------------------------------------------------------------------------- Creatures 2: ocean
# Floepup (seal pup): a bleating "maa" (goat, pitched up, wet dolphin breath under it) for its voice and a snorty sea-lion
# bark (puglin wolf) when happy; flipper slaps on land. Kelpback (sea cow): a low, slow moo with whale-ish water calls.
# Inkcurl (octopus): nearly silent; squid gurgles, a squirt when it inks, slime squelch steps.
PROFILES['floepup'] = merge(critter('entity.goat.ambient', 1.55, hurt='entity.wolf.hurt', death='entity.wolf.death', eat='entity.fox.eat',
                                    particle='minecraft:splash'), {
    '_subs': {'ambient': '{n} bleats', 'ambient_happy': '{n} barks happily', 'pet': '{n} grunts happily',
              'ability': '{n} rushes in with air', 'snore': '{n} snuffles'},
    'ambient': E([V('entity.goat.ambient', 1.55, 0.45)], layer=[V('entity.dolphin.ambient_water', 1.3, 0.2)], delay=4, jitter=0.08),
    'ambient_happy': E([V('entity.wolf_puglin.ambient', 1.05, 0.55)], layer=[V('entity.wolf_puglin.ambient', 1.15, 0.45)], delay=5, jitter=0.06),
    'pet': E([V('entity.wolf_puglin.pant', 1.2, 0.5)], layer=[V('entity.goat.ambient', 1.75, 0.3)], delay=8, jitter=0.05),
    'happy': E([V('entity.wolf_puglin.ambient', 1.1, 0.7)], layer=[V('entity.wolf_puglin.ambient', 1.2, 0.6)], delay=5),
    'snore': E([V('entity.fox.sleep', 0.9, 0.35)], jitter=0.04),
    'ability': E([V('entity.player.breath', 1.3, 0.6)], layer=[V('block.bubble_column.upwards_inside', 1.3, 0.5), V('entity.wolf_puglin.ambient', 1.1, 0.5)], delay=5),
    'splash': E([V('entity.dolphin.splash', 1.3, 0.6)]),
    'step': E([V('block.mud.step', 1.2, 0.35), V('entity.slime.squish_small', 1.2, 0.15)], jitter=0.1),   # flipper slaps
})
PROFILES['kelpback'] = beast('entity.cow.ambient', 0.5, hurt='entity.cow.hurt', death='entity.cow.death', step='entity.dolphin.swim', every=360,
                             alt='entity.dolphin.ambient_water', subs={'ambient': '{n} lows under water', 'snore': '{n} breathes slowly'},
                             extra={'ambient': E([V('entity.cow.ambient', 0.5, 0.5)], layer=[V('entity.dolphin.ambient_water', 0.55, 0.35)], delay=8, jitter=0.06),
                                    'step': E([V('entity.dolphin.swim', 0.6, 0.12)], jitter=0.08),
                                    'eat': E([V('block.wet_grass.break', 0.8, 0.4)], jitter=0.08),
                                    'snore': E([V('block.bubble_column.bubble_pop', 0.6, 0.25), V('entity.horse.breathe', 0.5, 0.2)], jitter=0.05),
                                    '_snore_every': 160})
PROFILES['inkcurl'] = beast('entity.squid.ambient', 1.35, hurt='entity.squid.hurt', death='entity.squid.death', step='entity.slime.squish_small', every=360,
                            subs={'ambient': '{n} gurgles', 'step': 'Suckers squelch', 'ability': '{n} squirts ink'},
                            extra={'ambient': E([V('entity.squid.ambient', 1.35, 0.35)], layer=[V('block.bubble_column.bubble_pop', 1.6, 0.25)], delay=5, jitter=0.07),
                                   'step': E([V('entity.slime.squish_small', 1.6, 0.12)], jitter=0.08),
                                   'ability': E([V('entity.squid.squirt', 1.3, 0.8)], layer=[V('block.bubble_column.upwards_inside', 1.6, 0.4)], delay=2)})
# ---------------------------------------------------------------------------------------------- Creatures 2: undead (c2-undead)
# Gloomweaver (dark-forest spider): the spider's own voice pitched down with a creak of old wood and a silk rustle;
# Sandshroud (desert husk): husk voice, dry and breathy, sand pouring off it; Reedwader (river drowned): the drowned's
# wet voice with river drips and a bobber's plink.
PROFILES['gloomweaver'] = undead('entity.spider.ambient', 0.85, hurt='entity.spider.hurt', death='entity.spider.death', step='entity.spider.step',
                                 every=220, layer=[V('block.creaking_heart.idle', 1.4, 0.25), V('block.wool.step', 1.3, 0.3)],
                                 subs={'ambient': '{n} hisses and creaks', 'step': 'Spider legs skitter'},
                                 extra={'death': E([V('entity.spider.death', 0.85)], layer=[V('block.wool.break', 0.9, 0.6)], delay=4),
                                        'attack': E([V('entity.spider.hurt', 1.3, 0.5)], layer=[V('block.wool.hit', 0.8, 0.4)], delay=1),
                                        'ability': E([V('entity.sheep.shear', 1.2, 0.8)], layer=[V('entity.spider.ambient', 1.1, 0.6)], delay=4,
                                                     sub='Silk snips')})
PROFILES['sandshroud'] = undead('entity.husk.ambient', 0.9, hurt='entity.husk.hurt', death='entity.husk.death', step='entity.husk.step',
                                layer=[V('block.sand.fall', 0.9, 0.5), F('mob/breeze/idle_air*', 0.5, 0.15)],
                                subs={'ambient': '{n} rasps through its veil', 'step': 'Sand shifts'},
                                extra={'death': E([V('entity.husk.death', 0.9)], layer=[V('block.sand.break', 0.8, 0.8), V('block.decorated_pot.shatter', 1.2, 0.4)], delay=4),
                                       'attack': E([V('entity.husk.ambient', 1.2, 0.5)], layer=[V('block.sand.hit', 1.0, 0.5)], delay=0)})
PROFILES['reedwader'] = undead('entity.drowned.ambient', 0.9, hurt='entity.drowned.hurt', death='entity.drowned.death', step='entity.drowned.step',
                               layer=[V('block.pointed_dripstone.drip_water', 1.1, 0.6), V('entity.fishing_bobber.splash', 1.6, 0.15)],
                               subs={'ambient': '{n} gurgles and drips', 'step': 'Wet steps'},
                               extra={'death': E([V('entity.drowned.death', 0.9)], layer=[V('entity.generic.splash', 0.9, 0.4)], delay=4),
                                      'attack': E([V('entity.drowned.ambient_water', 1.1, 0.6)], layer=[V('block.bamboo_wood.hit', 0.9, 0.4)], delay=0),
                                      'splash': E([V('entity.drowned.swim', 1.0, 0.6)], sub='{n} splashes')})
# ---------------------------------------------------------------------------------------------------------- Pockie
# Pockie (pocket shark, in its fishbowl on land): a tiny wet voice (the axolotl's water chirps pitched up high), bubble
# pops and a fish flop for its little body; on land every touch sound is its glass bowl: a soft high clink as it rolls,
# a brighter one when petted through the glass. Splash when it hops into / out of the bowl; the glow squirt is a glow
# squid's squirt pitched up.
PROFILES['pockie'] = merge(critter('entity.axolotl.idle_water', 1.65, hurt='entity.axolotl.hurt', death='entity.axolotl.death',
                                   eat='entity.generic.eat', particle='minecraft:glow'), {
    '_every': 220,
    '_subs': {'ambient': '{n} chirps', 'ambient_happy': '{n} chirps happily', 'pet': '{n} wiggles',
              'ability': '{n} squirts glowing fluid', 'step': 'A fishbowl rolls', 'splash': '{n} splashes',
              'snore': '{n} blows tiny bubbles'},
    'ambient': E([V('entity.axolotl.idle_water', 1.7, 0.4)], layer=[V('block.bubble_column.bubble_pop', 1.9, 0.25)], delay=5, jitter=0.08),
    'ambient_happy': E([V('entity.axolotl.idle_water', 1.9, 0.45)], layer=[V('entity.axolotl.idle_water', 2.0, 0.35)], delay=4, jitter=0.06),
    'pet': E([V('entity.axolotl.idle_water', 1.85, 0.5)], layer=[V('block.glass.hit', 1.9, 0.18), V('block.bubble_column.bubble_pop', 1.8, 0.4)], delay=4, jitter=0.05),
    'happy': E([V('entity.axolotl.idle_water', 2.0, 0.6)], layer=[V('entity.tropical_fish.flop', 1.8, 0.3)], delay=5),
    'snore': E([V('block.bubble_column.bubble_pop', 1.3, 0.2)], jitter=0.06),
    'ability': E([V('entity.glow_squid.squirt', 1.7, 0.55)], layer=[V('block.amethyst_block.chime', 1.9, 0.35)], delay=4),
    'eat': E([V('entity.generic.eat', 1.9, 0.5)], layer=[V('block.bubble_column.bubble_pop', 1.7, 0.3)], delay=3, jitter=0.06),
    'splash': E([V('entity.axolotl.splash', 1.6, 0.55)], layer=[V('entity.fish.swim', 1.6, 0.35)], delay=2),
    'step': E([V('block.glass.step', 1.9, 0.16)], jitter=0.12),   # the bowl rolling: a soft glass clink
    'sit': E([V('block.glass.hit', 1.6, 0.18)]),
    'stand': E([V('entity.axolotl.idle_water', 1.8, 0.35)]),
})
# Frog Guy: a tiny chunky frog scout in an orange sash. A small high frog croak, a wet tongue slurp when he eats or
# licks his eye, squishy little slime steps on his big flat feet, a happy ribbit when he hops.
PROFILES['frog_guy'] = merge(critter('entity.frog.ambient', 1.35, hurt='entity.frog.hurt', death='entity.frog.death',
                                    eat='entity.frog.eat', particle='minecraft:heart'), {
    '_every': 200,
    '_subs': {'ambient': '{n} croaks', 'ambient_happy': '{n} ribbits happily', 'pet': '{n} croaks softly',
              'ability': '{n} does a happy hop', 'step': 'Little frog feet squish', 'snore': '{n} snores'},
    'ambient': E([V('entity.frog.ambient', 1.35, 0.6)], jitter=0.08),
    'ambient_happy': E([V('entity.frog.ambient', 1.55, 0.65)], layer=[V('entity.frog.ambient', 1.7, 0.4)], delay=4, jitter=0.06),
    'pet': E([V('entity.frog.ambient', 1.25, 0.45)], jitter=0.05),
    'happy': E([V('entity.frog.long_jump', 1.4, 0.5)], layer=[V('entity.frog.ambient', 1.6, 0.5)], delay=6),
    'ability': E([V('entity.frog.long_jump', 1.3, 0.55)], layer=[V('entity.frog.ambient', 1.5, 0.55)], delay=8),
    'eat': E([V('entity.frog.eat', 1.3, 0.6)], jitter=0.06),
    'snore': E([V('entity.frog.ambient', 0.8, 0.15)], jitter=0.05),
    'step': E([V('entity.frog.step', 1.4, 0.35)], jitter=0.1),
    'splash': E([V('entity.player.splash', 1.6, 0.3)]),
})
# ---------------------------------------------------------------------------------------------------------- Penguins
# Emperor (tall, calm): a low two-pulse trumpet call (goat bleat pitched down, a second pulse behind it), a dolphin's
# gulp when it swallows a fish, soft wet foot slaps on snow, a splash going in. Rockhopper (small, bold): a raspy
# braying squawk (donkey hee-haw pitched up high: the real rockhopper's bray), parrot squawks when hurt, stone taps when
# it hops on rock. The chicks whistle and peep: the emperor chick's thin flute whistle, the rockhopper chick's squeaky
# chirp. Sleeping penguins only breathe.
def _penguin(voice, p, v, *, second, hurt, death, step, subs, every=260):
    return merge(critter(voice, p, hurt=hurt, death=death, eat='entity.dolphin.eat', particle='minecraft:snowflake'), {
        '_every': every,
        '_subs': subs,
        'ambient': E([V(voice, p, v)], layer=[V(voice, p * second, v * 0.8)], delay=5, jitter=0.07),
        'ambient_happy': E([V(voice, p * 1.1, v)], layer=[V(voice, p * 1.18, v * 0.9), V(voice, p * 1.25, v * 0.7)], delay=4, jitter=0.06),
        'ambient_sleepy': E([V(voice, p * 0.85, v * 0.4)], jitter=0.05),
        'snore': E([V('entity.player.breath', 0.75 * p / max(p, 0.8), 0.12)], jitter=0.05),
        'sleep': E([V(voice, p * 0.8, v * 0.35)]),
        'eat': E([V('entity.dolphin.eat', 1.1 * min(1.4, p * 1.2), 0.7)], layer=[V(voice, p * 1.15, v * 0.5)], delay=10, jitter=0.06),
        'happy': E([V(voice, p * 1.15, v * 1.1)], layer=[V(voice, p * 1.25, v)], delay=4),
        'pet': E([V(voice, p * 1.08, v * 0.8)], layer=[V('block.wool.step', 1.5, 0.25)], delay=3, jitter=0.06),
        'ability': E([V('entity.dolphin.splash', 1.2, 0.6)], layer=[V(voice, p * 1.15, v * 0.8)], delay=6),
        'splash': E([V('entity.dolphin.splash', 1.1, 0.6)], layer=[V('entity.generic.swim', 1.3, 0.3)], delay=2),
        'step': step,
    })


PROFILES['emperor_penguin'] = _penguin('entity.goat.ambient', 0.62, 0.55, second=0.94, hurt='entity.goat.hurt', death='entity.goat.death',
                                       step=E([V('block.snow.step', 1.15, 0.35), V('entity.slime.squish_small', 0.9, 0.12)], jitter=0.08),
                                       subs={'ambient': '{n} trumpets', 'ambient_happy': '{n} calls out', 'pet': '{n} leans in',
                                             'ability': '{n} dives for fish', 'step': 'Penguin feet slap', 'splash': '{n} dives in',
                                             'snore': '{n} breathes slowly'})
PROFILES['rockhopper_penguin'] = _penguin('entity.donkey.ambient', 1.75, 0.3, second=1.08, hurt='entity.parrot.hurt', death='entity.parrot.death',
                                          step=E([V('block.stone.step', 1.5, 0.22), V('entity.slime.squish_small', 1.3, 0.1)], jitter=0.1),
                                          subs={'ambient': '{n} brays', 'ambient_happy': '{n} squawks happily', 'pet': '{n} shakes its crest',
                                                'ability': '{n} dives for fish', 'step': 'Penguin hops', 'splash': '{n} dives in',
                                                'snore': '{n} breathes softly'}, every=220)
PROFILES['emperor_penguin_chick'] = merge(_penguin('entity.chicken.ambient', 1.55, 0.45, second=1.1, hurt='entity.chicken.hurt', death='entity.chicken.death',
                                                   step=E([V('block.snow.step', 1.6, 0.2)], jitter=0.1),
                                                   subs={'ambient': '{n} whistles', 'ambient_happy': '{n} peeps happily', 'pet': '{n} fluffs up',
                                                         'step': 'Tiny feet patter', 'snore': '{n} breathes softly'}, every=200), {
    'ambient': E([F('note/flute', 1.9, 0.22)], layer=[V('entity.chicken.ambient', 1.6, 0.35)], delay=3, jitter=0.06),
})
PROFILES['rockhopper_penguin_chick'] = _penguin('entity.parrot.ambient', 1.9, 0.4, second=1.06, hurt='entity.chicken.hurt', death='entity.chicken.death',
                                                step=E([V('block.stone.step', 1.8, 0.15)], jitter=0.1),
                                                subs={'ambient': '{n} chirps', 'ambient_happy': '{n} chirps happily', 'pet': '{n} fluffs up',
                                                      'step': 'Tiny feet patter', 'snore': '{n} breathes softly'}, every=200)
