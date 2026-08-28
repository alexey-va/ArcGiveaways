package ru.ruscrafting.giveaways.paper

import net.kyori.adventure.text.Component

internal object GiveawayPresentation {
    fun chatAnnouncement(body: Component, joinButton: Component): Component =
        Component.newline()
            .append(body)
            .append(Component.newline())
            .append(joinButton)
            .append(Component.newline())
}
