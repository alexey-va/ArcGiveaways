import assert from 'node:assert/strict';
import { test, expect, waitUntil } from '@drownek/plugwright';

async function startGiveaway(player, signal) {
  await player.makeOp();
  player.chat(`/lp user ${player.username} permission set arcgiveaways.start true`);
  await expect(player).toHaveReceivedMessage('Set arcgiveaways.start to true');
  await player.deOp();
  await player.giveItem('diamond', 1);
  await waitUntil(() => player.bot.inventory.items().some((item) => item.name === 'diamond'), {
    signal,
    message: 'Synthetic giveaway item was not delivered',
  });
  await player.bot.equip(player.bot.inventory.items().find((item) => item.name === 'diamond'), 'hand');
  player.chat('/giveaway start 1');
  await expect(player).toHaveReceivedMessage(/The giveaway is live|Раздача началась/i);
  await waitUntil(() => !player.bot.inventory.items().some((item) => item.name === 'diamond'), {
    signal,
    message: 'Escrowed diamond remained in host inventory',
  });
}

function diamonds(player) {
  return player.bot.inventory.items().filter(item => item.name === 'diamond').reduce((sum, item) => sum + item.count, 0);
}

async function activeId(player) {
  // Resolve the same ID offered to a player typing the join command.
  const matches = await player.bot.tabComplete('/giveaway join ');
  assert.equal(matches.length, 1, 'Expected exactly one active giveaway');
  const id = matches[0].match;
  assert.match(id, /^[0-9a-f]{8}$/);
  return id;
}

test('giveaway start, status and cancel refund the host on real Paper', async ({ player, signal }) => {
  await startGiveaway(player, signal);
  player.clearMessages();
  player.chat('/giveaway status');
  await expect(player).toHaveReceivedMessage(/diamond|алмаз/i);
  player.clearMessages();
  player.chat('/giveaway cancel');
  await expect(player).toHaveReceivedMessage(/The giveaway was cancelled|Раздача отменена/i);
  await waitUntil(() => player.bot.inventory.items().filter((item) => item.name === 'diamond').reduce((sum, item) => sum + item.count, 0) === 1, {
    signal,
    message: 'Cancelled giveaway did not refund diamond',
  });
  assert.equal(player.bot.inventory.items().filter((item) => item.name === 'diamond').reduce((sum, item) => sum + item.count, 0), 1);
});

test('another player cannot cancel escrow; owner cancellation and repeat claim return exactly one item', async ({ player, createPlayer, signal }) => {
  const intruder = await createPlayer({ username: 'GiveawayVisitor' });
  await startGiveaway(player, signal);
  const id = await activeId(intruder);
  intruder.chat(`/giveaway cancel ${id}`);
  await expect(intruder).toHaveReceivedMessage('You can only cancel your own giveaway.');
  assert.equal(await activeId(intruder), id);
  assert.equal(diamonds(player), 0);
  assert.equal(diamonds(intruder), 0);

  player.chat(`/giveaway cancel ${id}`);
  await expect(player).toHaveReceivedMessage('The giveaway was cancelled.');
  await waitUntil(() => diamonds(player) === 1, { signal });
  player.chat(`/giveaway cancel ${id}`);
  await expect(player).toHaveReceivedMessage('Registration for this giveaway is already closed.');
  player.chat('/giveaway claim');
  await expect(player).toHaveReceivedMessage('No prize or refund is pending.');
  assert.equal(diamonds(player), 1);
  assert.equal(diamonds(intruder), 0);
});

test('the only eligible participant wins and cannot claim the delivered prize twice', async ({ player, createPlayer, signal }) => {
  const participant = await createPlayer({ username: 'GiveawayWinner' });
  await startGiveaway(player, signal);
  const id = await activeId(participant);
  participant.chat(`/giveaway join ${id}`);
  await expect(participant).toHaveReceivedMessage('You joined.');
  participant.chat(`/giveaway join ${id}`);
  await expect(participant).toHaveReceivedMessage('You are beside the host again. Your entry is preserved.');
  const since = participant.messageBuffer.length;
  participant.chat('/giveaway status');
  await expect(participant).toHaveReceivedMessage('Participants: 1', { since });
  assert.equal(diamonds(player), 0);
  assert.equal(diamonds(participant), 0);

  // Keep the production registration/drawing durations; await the real lifecycle.
  await expect(participant).toHaveReceivedMessage('The item was delivered to your inventory.', { timeout: 70000 });
  await expect(participant).toHaveReceivedMessage(`Winner — ${participant.username}`);
  await waitUntil(() => diamonds(participant) === 1, { signal });
  participant.chat('/giveaway claim');
  await expect(participant).toHaveReceivedMessage('No prize or refund is pending.');
  player.chat('/giveaway claim');
  await expect(player).toHaveReceivedMessage('No prize or refund is pending.');
  assert.equal(diamonds(participant), 1);
  assert.equal(diamonds(player), 0);
});
