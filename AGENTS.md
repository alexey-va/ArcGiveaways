# AGENTS.md — ArcGiveaways

Standalone Kotlin/Paper plugin for player-funded, proximity-gated network
giveaways.

- Target Purpur/Paper 1.21.11 and Java 25.
- Use `arc-core`, `arc-core-paper`, and `arc-core-redis` through the sibling
  composite build. Consult `../arc-core/docs/shared-primitives.md` before adding
  infrastructure; ArcGiveaways owns only its giveaway domain and Redis schema.
- Paper tests use `ru.arc:arc-core-paper-testing` and
  `MockBukkitTestRuntime`; never pin or manage MockBukkit directly here.
- Keep the giveaway state machine and Redis protocol independent of Bukkit.
- Redis is mandatory: fail closed when the connection or atomic state update is
  unavailable.
- A participant is eligible only while online on the host backend, in the host
  world, and inside the configured radius at the draw snapshot.
- Persist inventory removal/delivery journals before mutation and converge them
  after restart. Never retry an ambiguous inventory mutation blindly.
- All player text belongs in `lang/ru.yml` and `lang/en.yml`; locale keys remain
  identical and untrusted names use non-parsing placeholders.
- Build and test with `../arc-core/gradlew -p . clean check shadowJar`.
