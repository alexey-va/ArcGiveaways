# AGENTS.md — ArcGiveaways

Standalone Kotlin/Paper plugin for player-funded, proximity-gated network
giveaways.

- Target Purpur/Paper 1.21.11 and Java 25.
- Use the pinned public `arc-core` release by default; opt into a local
  composite only with `-ParcCoreDir=/absolute/path/to/arc-core`. Consult
  `../arc-core/docs/shared-primitives.md` before adding
  infrastructure; ArcGiveaways owns only its giveaway domain and Redis schema.
- Paper tests use the pinned public release through
  `ru.ruscrafting.arc:arc-core-paper-testing` and
  `MockBukkitTestRuntime`; never pin or manage MockBukkit directly here.
- Keep the giveaway state machine and Redis protocol independent of Bukkit.
- Keep Paper effects behind the narrow `GiveawayPresentationPort`,
  `GiveawayTravelPort`, and `GiveawayPlayerDataPersistence` seams. Their native
  adapters may compose released ArcCore platform ports; do not replace them
  with constructor callback bags or a generic platform context.
- Redis is mandatory: fail closed when the connection or atomic state update is
  unavailable.
- A participant is eligible only while online on the host backend, in the host
  world, and inside the configured radius at the draw snapshot.
- Persist inventory removal/delivery journals before mutation and converge them
  after restart. Never retry an ambiguous inventory mutation blindly.
- All player text belongs in `lang/ru.yml` and `lang/en.yml`; locale keys remain
  identical and untrusted names use non-parsing placeholders.
- Build and test locally with `./gradlew clean test shadowJar`; the disposable
  Redis `integrationTest` runs in CI, not on the owner's workstation. Set
  `RUSCRAFTING_OPS_ROOT=/absolute/path/to/ruscrafting-ops` to include tests
  that verify tracked runtime profiles.

## Git publication authorization

- The owner grants standing authorization for ordinary non-force pushes of
  verified trunk commits from this repository to its already-configured GitHub
  `origin`; do not ask for confirmation again.
- This does not authorize changing remotes, force-pushing, deleting branches or
  tags, publishing releases, or bypassing the project's credential and secret
  handling rules.
