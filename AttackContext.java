# SentinelAC

A server-authoritative anti-cheat for Paper/Folia (Minecraft 1.21.11) that targets
modern *closet* cheats — the kind tuned to look human (reach 3.1–3.5 instead of 6,
smoothed/jittered aimbots, tick-perfect combos, modules that auto-disable when no
target is near). It uses [PacketEvents](https://github.com/retrooper/packetevents)
to read the raw client packet stream and re-derives everything (reach, speed,
timing, aim) from what is *physically legal*, never trusting a value the client
reports about itself.

> **Status: reference implementation, not yet battle-tested.** The detection logic
> is the valuable part and is complete. It has **not** been compiled against live
> Paper/PacketEvents jars in this environment (only the dependency-free core was
> syntax-checked), so before deploying you must build it against your actual server
> + PacketEvents version and fix any small API drift — see *Building* below.

---

## Threat model & philosophy

* **The server is authoritative.** Hit registration, movement legality, item
  attributes and cooldowns are all recomputed server-side. The client's claims are
  evidence to be checked, not facts.
* **Lag compensation is mandatory.** Every combat check rewinds the victim to where
  the attacker *would have seen them* (now − ping), and evaluates against the most
  attacker-favorable position in a small window, so high-ping players are not
  false-flagged.
* **Violation Levels (VL) with decay, never an instant ban.** Each check accrues
  weighted VL toward configurable punishment tiers; clean play bleeds VL back down.
  A single anomalous tick can't ban anyone.
* **Everything is configurable and lenient by default.** Watch `sentinel.log` for a
  week, then tighten. Every threshold in `config.yml` has an inline comment telling
  you which way to move it and what it trades off.

---

## Architecture

```
client packets ──▶ PacketListener (Netty thread)
                      │  updates thread-safe PlayerData (position ring buffer,
                      │  rotation samples, verified item flags, ping, timings)
                      ▼
                   CheckManager
                      │  builds ONE AttackContext per attack (resolve victim by
                      │  entity id, rewind by ping, compute eye + look ray)
                      ├──▶ combat checks: Reach, SilentAim, Killaura, MaceStunSlam, Lunge
                      └──▶ movement: Speed     rotation: Killaura behavioral
                      ▼
                   ViolationManager ──▶ weighted VL ──▶ punishment tiers
                      │                              (ALERT / CANCEL / KICK / BAN)
                      └──▶ throttled staff alerts + plugins/SentinelAC/sentinel.log
```

Key design choices:

* **Own pure math** (`MathUtil`: `Vec3`/`AABB`, ray-vs-box, angle-to-box). Check math
  is allocation-light and runs off the main thread without touching live world objects.
* **Folia-aware scheduling** (`SchedulerUtil`). The same jar runs on Paper and Folia;
  anything touching the live world (set-back teleports, the through-wall block lookup,
  inventory reads) is routed to the correct region/entity thread.
* **A single monotonic tick counter** drives all timing, so check logic is independent
  of wall-clock scheduling.
* **`PlayerData` is the lifecycle owner** of all per-player state; it's dropped on quit,
  so there are no leaks and no cross-player state bleed.

---

## The seven checks (and where to tune them)

All keys below live under `checks.<name>` in `config.yml`. Common keys for every
check: `enabled`, `max-vl` (VL ceiling), `decay` (VL removed each
`violation-tick-interval`), `punishment` (which tier-set to use).

1. **Reach** (`reach`) — measures the *closest* distance from the attacker's eye to
   the rewound victim hitbox; a player can't be nearer than the box's nearest point.
   Tune `max-distance` (3.0) + `tolerance` (0.03). Position-spoof reach is intentionally
   left to the Speed check (it shows up as illegal movement).

2. **Speed** (`speed`) — per-tick horizontal displacement vs the max legal for the
   player's state. Ships with `use-simulation: false` (threshold model). The threshold
   model is deliberately generous (`threshold-multiplier` 1.10, an airborne sprint-jump
   allowance) because it does **not** know the block under the player. Tune
   `threshold-multiplier` down once trusted; raise `min-consecutive-ticks` /
   `velocity-grace-ticks` if lag causes flags. *The "real" answer is movement simulation
   — see `SimulationEngine.java`, a documented stub you can grow into.*

3. **Killaura** (`killaura`) — several independent sub-checks under `sub-checks`:
   * `angle` — could the actual look have been aimed at the target? (`max-angle-degrees`)
   * `raytrace` — does the look ray actually intersect the rewound hitbox?
   * `multi-target` — too many distinct entities hit in `window-ms` (`max-targets`).
   * `through-wall` — attacking through solids. **OFF by default** (needs world access,
     runs async, can't cancel the packet retroactively — only raises VL). Load-test
     before enabling.
   * `rotation` — behavioral aim analysis over `sample-size` samples: flags perfectly
     constant angular velocity (`constant-velocity-ratio`) and absent micro-jitter
     (`min-jitter-stddev`). The strongest signal against humanized aimbots. Raise the
     thresholds if legit players flag.

4. **Silent aim** (`silent-aim`) — the reported camera ray must come within
   `max-miss-degrees` (12°, tighter than killaura's angle) of the victim; a hit that
   lands while the camera points elsewhere is silent aim. Routes to the high-confidence
   `hard` tier.

5. **Silent swap** (`silent-swap`) — detects the *timing* signature of swap→action→revert
   within `window-ticks`, ramping after `min-repeats`. (On an authoritative server the
   held slot always matches the applied item, so the only observable is timing; the
   attribute-mismatch case that matters is handled by the mace check.)

6. **Mace 1-tick stun slam** (`mace-stun-slam`) — catches attribute swapping
   ([MC-28289](https://bugs.mojang.com/browse/MC-28289)): a same-tick slot change on an
   attack, an axe(shield-disable)→mace(smash) chain on the same target within
   `max-combo-ticks`, or a full-power mace hit faster than `full-power-cooldown-ticks`.
   Set `legal-swap-window-ticks: 0` to treat *all* same-tick swaps as illegal, or to
   your swap plugin's legal window. **Set `full-power-cooldown-ticks` to your weapon
   balance.**

7. **Lunge bypass** (`lunge-bypass`) — method-agnostic: treats the lunge cooldown as
   server-authoritative and flags any lunge-spear activation arriving sooner than
   `lunge-cooldown-ticks` (minus `cooldown-tolerance-ticks`), whether achieved by
   swap-tricking or a cooldown-suppressing client. **Set `lunge-cooldown-ticks` to your
   server's actual lunge cooldown.**

Punishment escalation lives in `punishment-tiers` (`combat-default`, `movement-default`,
`hard`). Edit the `vl:` thresholds and `actions:` there; `{player}`, `{check}`, `{vl}`,
`{reason}` expand in command/kick strings.

---

## Building

Requirements: **JDK 21**, Maven, and network access to the PaperMC and CodeMC repos
(both are in `pom.xml`).

```bash
mvn -U clean package
```

The jar lands in `target/SentinelAC-<version>.jar`.

Before you build:

* **Rename the package.** `com.example.ac` / groupId `com.example` are placeholders
  (flagged in `pom.xml` and `plugin.yml`). Pick your own before shipping.
* **Verify the versions** in `pom.xml`: `paper.version` (match your server) and
  `packetevents.version` (latest from the PacketEvents releases page). PacketEvents
  wrapper/enum names drift between releases — if compilation fails it's almost always
  in `PacketListener.java` (renamed getters / wrapper classes). The detection logic in
  the checks is unaffected by such changes.

PacketEvents is declared `provided` and depended on via `plugin.yml` — i.e. you install
the PacketEvents plugin alongside this one. If you'd rather ship one self-contained jar,
switch the PacketEvents dependency to `compile` scope and add the Shade plugin **with
relocation** of `com.github.retrooper.packetevents` and `io.github.retrooper.packetevents`
to avoid clashing with other plugins' copies, then remove the `depend: [packetevents]`
line.

---

## Installing

1. Drop the [PacketEvents](https://github.com/retrooper/packetevents/releases) plugin
   jar in `/plugins` (unless you shaded it in).
2. Drop `SentinelAC-<version>.jar` in `/plugins`.
3. Start the server once to generate `plugins/SentinelAC/config.yml`, tune it, then
   `/sentinel reload`.

### Permissions

* `anticheat.bypass` — skips **all** checks (staff/test accounts only). `default: false`
* `anticheat.alerts` — receives in-game flag alerts. `default: op`
* `anticheat.command` — use `/sentinel`. `default: op`

### Command

`/sentinel` (aliases `/sac`, `/ac`):

* `reload` — reload `config.yml` and all check settings.
* `vl <player>` — show a player's current per-check VL.
* `alerts` — toggle your own alert visibility.

---

## Honest scope & limitations

* **Untested against live jars here.** Only the dependency-free core
  (`MathUtil`, `PlayerData`, etc.) was compiled in the build environment. Treat the
  PacketEvents wrapper layer as the most likely place to need a small fix for your
  exact version.
* **Movement simulation is a stub.** A faithful 1:1 movement engine (the
  effectively-unbypassable approach) plus a per-player world replica are large,
  multi-thousand-line subsystems and are out of scope. You get a solid threshold-based
  Speed check today and clearly-marked interfaces (`SimulationEngine`, and a
  `WorldReplica` you'd add) to grow into. Through-wall is off by default for the same
  performance reason and, running async, can only raise VL rather than cancel a packet.
* **Lunge dash vs jab** is distinguished by interval, not by movement physics. If your
  jab cadence is legitimately faster than the lunge cooldown, wire a dash-velocity
  confirmation in before tightening that check.
* **Non-player victims** (mobs) skip the hitbox-based combat checks to avoid false
  positives, so PvE reach/aim isn't covered (PvP is). Multi-target still counts mob ids.
* **Attribute swapping (MC-28289)** was patched in a 26.2 pre-release and then reverted,
  so it remains exploitable on current releases — which is why the mace check ships
  enabled. If a future server build re-applies the fix, the exploit yields only 0-charge
  damage and the check simply stops finding the signature.

### A note on Grim

This is a clean-room reimplementation. The *patterns* (lag-comp rewind windows, VL
buffering, behavioral aim analysis) are standard anti-cheat technique and are studied
across the ecosystem, but no code was copied from [Grim](https://github.com/GrimAnticheat/Grim)
or any GPL-3.0 project — none of this is derived from their source. Keep it that way if
you extend it.
