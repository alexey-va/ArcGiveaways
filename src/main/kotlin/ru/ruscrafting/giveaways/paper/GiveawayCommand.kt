package ru.ruscrafting.giveaways.paper

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import ru.ruscrafting.giveaways.config.GiveawayLocale
import ru.ruscrafting.giveaways.config.MessageKey

class GiveawayCommand(
    private val service: GiveawayService,
    private val locale: GiveawayLocale,
    private val reload: () -> Result<Unit>,
    private val openMenu: (Player) -> Unit = {},
    private val qaReport: (String?) -> List<String> = service::qaReport,
) : CommandExecutor, TabCompleter {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            (sender as? Player)?.let(openMenu) ?: sender.sendMessage(locale.render(MessageKey.HELP, sender))
            return true
        }
        val subcommand = args[0].lowercase()
        if (subcommand == "help") {
            sender.sendMessage(locale.render(MessageKey.HELP, sender))
            return true
        }
        if (subcommand == "menu") {
            (sender as? Player)?.let(openMenu) ?: sender.sendMessage(locale.render(MessageKey.PLAYER_ONLY, sender))
            return true
        }
        if (subcommand == "reload" || subcommand == "qa") {
            if (!sender.hasPermission("arcgiveaways.admin")) {
                sender.sendMessage(locale.render(MessageKey.NO_PERMISSION, sender))
                return true
            }
            if (subcommand == "qa") {
                qaReport(args.getOrNull(1)).forEach(sender::sendMessage)
            } else {
                reload().fold(
                    onSuccess = { sender.sendMessage(locale.render(MessageKey.RELOAD_OK, sender)) },
                    onFailure = { sender.sendMessage(locale.render(MessageKey.RELOAD_FAILED, sender, mapOf("reason" to locale.text(it.message ?: "unknown")))) },
                )
            }
            return true
        }
        val player = sender as? Player
        if (player == null) {
            sender.sendMessage(locale.render(MessageKey.PLAYER_ONLY, sender))
            return true
        }
        if (!player.hasPermission("arcgiveaways.use")) {
            player.sendMessage(locale.render(MessageKey.NO_PERMISSION, player))
            return true
        }
        when (subcommand) {
            "start" -> {
                if (!player.hasPermission("arcgiveaways.start")) player.sendMessage(locale.render(MessageKey.NO_PERMISSION, player))
                else {
                    val rawAmount = args.getOrNull(1)
                    val amount = rawAmount?.toIntOrNull()
                    if (rawAmount != null && amount == null) player.sendMessage(locale.render(MessageKey.HELP, player))
                    else service.startGiveaway(player, amount)
                }
            }
            "join" -> args.getOrNull(1)?.let { service.join(player, it) }
                ?: player.sendMessage(locale.render(MessageKey.HELP, player))
            "follow", "tp" -> args.getOrNull(1)?.let { service.follow(player, it) }
                ?: player.sendMessage(locale.render(MessageKey.HELP, player))
            "status", "list" -> service.sendStatus(player)
            "cancel" -> service.cancel(player, args.getOrNull(1))
            "claim" -> service.claim(player)
            else -> player.sendMessage(locale.render(MessageKey.HELP, player))
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            val available = buildList {
                addAll(listOf("menu", "help", "start", "join", "follow", "status", "cancel", "claim"))
                if (sender.hasPermission("arcgiveaways.admin")) addAll(listOf("reload", "qa"))
            }
            return available.filter { it.startsWith(args[0], ignoreCase = true) }
        }
        if (args.size == 2 && args[0].equals("join", true)) {
            return service.activeRecords().map { it.displayId() }.filter { it.startsWith(args[1], true) }
        }
        if (args.size == 2 && (args[0].equals("follow", true) || args[0].equals("tp", true))) {
            return service.activeRecords().map { it.displayId() }.filter { it.startsWith(args[1], true) }
        }
        if (args.size == 2 && args[0].equals("qa", true) && sender.hasPermission("arcgiveaways.admin")) {
            return service.activeRecords().map { it.displayId() }.filter { it.startsWith(args[1], true) }
        }
        return emptyList()
    }
}
