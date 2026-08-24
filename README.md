# ArcGiveaways

Player-funded giveaways for the RusCrafting Paper network. A host escrows the
held item, the invitation is broadcast over Redis, and players click it to join.
Only players physically present in the host world and within the configured
radius enter the final draw snapshot.

## Commands

- `/giveaway start [amount]` — start a giveaway with the held item.
- `/giveaway join <id>` — move to the host backend when needed, teleport beside
  the host, and join the giveaway.
- `/giveaway status` — show active giveaways.
- `/giveaway cancel [id]` — cancel your giveaway and queue a safe refund.
- `/giveaway claim` — retry a pending prize or refund after freeing inventory.
- `/giveaway reload` — reload gameplay and locale configuration (admin).

## Build

```bash
../arc-core/gradlew -p . clean check shadowJar
```

The deployable artifact is `build/libs/ArcGiveaways-0.1.4.jar`.
