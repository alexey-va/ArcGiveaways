# ArcGiveaways

Player-funded giveaways for the RusCrafting Paper network. A host escrows the
held item, the invitation is broadcast over Redis, and players click it to join.
Only players physically present in the host world and within the configured
radius enter the final draw snapshot.

Active giveaways show a warm network-wide bossbar. Registered participants get
an actionbar countdown and a title for the final five seconds; each successful
join is announced across every backend. Backend presence uses expiring Redis
leases, so a stopped host backend cannot leave a player permanently blocked by
a stale giveaway claim. When the host changes backends, the giveaway and its
countdown pause during transit, migrate to the new backend, and give every
registered participant an urgent one-click route back to the host.

The host is surrounded by a rotating three-axis particle atom and a geometric
crown with recurring fireworks. The final countdown becomes a pulsing rainbow
star with stacked rings, winner selection renders a triple helix with orbiting
nodes, and victory ends in a rainbow sphere, radial burst, crown, and a dense
multi-effect firework sequence. The host and current participants glow for the
active lifecycle, with their original state restored afterwards. Every interval,
particle budget, radius, palette, winner firework parameter, and glow role is
bounded and configurable under `effects`; `effects.intensity` scales the whole
show down from `1.0`, while `effects.particles` and `effects.fireworks` remain
master switches.

## Commands

- `/giveaway start [amount]` — start a giveaway with the held item; requires
  `arcgiveaways.start`, granted from the `burgher` progression rank upward.
- `/giveaway join <id>` — move to the host backend when needed, teleport beside
  the host, and join the giveaway.
- `/giveaway follow <id>` — preserve an existing entry while moving to the
  host's new backend and location.
- `/giveaway status` — show active giveaways.
- `/giveaway cancel [id]` — cancel your giveaway and queue a safe refund.
- `/giveaway claim` — retry a pending prize or refund after freeing inventory.
- `/giveaway reload` — reload gameplay and locale configuration (admin).

## Build

```bash
./gradlew clean test shadowJar
```

The Redis/Testcontainers integration suite runs in CI; local development uses
the unit and MockBukkit suite above.

The deployable artifact is `build/libs/ArcGiveaways-0.1.9.jar`.
