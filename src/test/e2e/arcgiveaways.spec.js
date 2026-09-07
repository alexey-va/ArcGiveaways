import assert from 'node:assert/strict';
import { test, expect, waitUntil } from '@drownek/plugwright';

test('giveaway start, status and cancel refund the host on real Paper', async ({ player, signal }) => {
  await player.makeOp();
  player.chat(`/lp user ${player.username} permission set arcgiveaways.start true`);
  await expect(player).toHaveReceivedMessage('Set arcgiveaways.start to true');
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
