# Real Paper giveaway lifecycle tests

The GitHub E2E job creates a disposable Redis 7.4 service on 127.0.0.1:6380
with no password and runs `TEST_TIMEOUT=90000 ./gradlew plugwrightTest`.
The synthetic configuration
never imports ARC credentials. Paper 1.21.11, Plugwright 2.0.4, Node 22.14.0 and
LuckPerms 5.5.17 are pinned. Paper binds to 127.0.0.1:25565 and its world/plugin
data are recreated under `build/plugwright` on each run.

The scenario grants the test player the start permission through real LuckPerms,
equips one synthetic diamond, starts a giveaway, proves escrow removed it,
checks status and cancels. The inventory must receive exactly one diamond back.
Two-player scenarios additionally reject another player's cancellation, repeat
cancel/claim without a second refund, and run the complete draw with exactly one
eligible participant. Rejoining preserves a single registration; the winner receives
exactly one diamond and a repeated claim creates no item or host refund. Hosts
lose OP after permission setup; IDs come from real command completion. The draw
uses the production 45-second registration and 6-second drawing durations.

The fixture fixes the locale to English and disables only scene particles:
the pinned bot protocol raises `PartialReadError` while decoding Paper dust
particles. Particle rendering is not covered by this suite.

Proximity rejection, cross-server travel and crash recovery retain their
separate JVM/Redis integration coverage. Logs are uploaded on every CI
outcome. Run storage-dependent integration checks in CI as required by AGENTS.md.
