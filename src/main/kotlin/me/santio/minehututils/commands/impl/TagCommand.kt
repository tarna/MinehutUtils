package me.santio.minehututils.commands.impl

import com.google.auto.service.AutoService
import dev.minn.jda.ktx.events.listener
import dev.minn.jda.ktx.interactions.commands.Command
import dev.minn.jda.ktx.interactions.commands.Option
import dev.minn.jda.ktx.interactions.commands.Subcommand
import dev.minn.jda.ktx.interactions.components.Modal
import me.santio.minehututils.bot
import me.santio.minehututils.commands.SlashCommand
import me.santio.minehututils.database.models.Tag
import me.santio.minehututils.factories.EmbedFactory
import me.santio.minehututils.logger.GuildLogger
import me.santio.minehututils.tags.SearchAlgorithm
import me.santio.minehututils.tags.TagManager
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.Command.Choice
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import java.util.*
import kotlin.time.Duration.Companion.minutes

@AutoService(SlashCommand::class)
class TagCommand : SlashCommand {

    override fun getData(): CommandData {
        return Command("tag", "See and manage tags") {
            isGuildOnly = true

            addSubcommands(
                Subcommand("create", "Create a new tag") {
                    defaultPermissions = DefaultMemberPermissions.enabledFor(Permission.MESSAGE_MANAGE)

                    addOptions(
                        Option<String>("type", "How should the tag be detected", true, true)
                    )
                },
                Subcommand("delete", "Delete a tag") {
                    defaultPermissions = DefaultMemberPermissions.enabledFor(Permission.MESSAGE_MANAGE)

                    addOptions(
                        Option<String>("id", "The id of the tag to delete", true, true)
                    )
                },
                Subcommand("edit", "Edit a tag") {
                    defaultPermissions = DefaultMemberPermissions.enabledFor(Permission.MESSAGE_MANAGE)

                    addOptions(
                        Option<String>("id", "The id of the tag to edit", true, true)
                    )
                },
                Subcommand("list", "List all tags"),
                Subcommand("info", "Get information about a tag") {
                    defaultPermissions = DefaultMemberPermissions.enabledFor(Permission.MESSAGE_MANAGE)

                    addOptions(
                        Option<String>("id", "The id of the tag to get information about", true, true)
                    )
                }
            )
        }
    }

    override suspend fun autoComplete(event: CommandAutoCompleteInteractionEvent): List<Choice> {
        return when (event.focusedOption.name) {
            "id" -> {
                return TagManager.fetchAll().map { Choice("[${it.id}] ${it.name}", "${it.id}") }
            }
            "type" -> {
                return SearchAlgorithm.entries.map { it.name.lowercase() }.map { Choice(it, it) }
            }
            else -> emptyList()
        }
    }

    private suspend fun createTag(event: SlashCommandInteractionEvent) {
        val type = event.getOption("type")?.asString ?: error("Type not provided")
        val searchAlg = SearchAlgorithm.from(type) ?: error("Invalid search algorithm provided")

        val id = UUID.randomUUID().toString()
        val modal = Modal("minehut:tag:create:$id", "Create a new tag") {
            short("minehut:tag:search", searchAlg.placeholder)
            paragraph("minehut:tag:body", "Enter the body of the tag (supports markdown)")
        }

        event.replyModal(modal).queue()

        bot.listener<ModalInteractionEvent>(timeout = 15.minutes) {
            if (it.modalId != "minehut:tag:create:$id") return@listener
            cancel()

            val searchValue = it.values.firstOrNull { it.id == "minehut:tag:search" }?.asString
                ?: error("No search value provided")
            val body = it.values.firstOrNull { it.id == "minehut:tag:body" }?.asString
                ?: error("No body provided")

            val tag = Tag(
                searchAlg = searchAlg.id,
                searchValue = searchValue.trim(),
                body = body,
                uses = 0,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                createdBy = event.user.id
            )

            TagManager.add(tag)
            GuildLogger.of(event.guild!!).log(
                "Created a new tag `${searchAlg.name.lowercase()}: $searchValue`",
                ":identification_card: User: ${event.member?.asMention} *(${event.user.name} - ${event.user.id})*",
                ":gear: Tag type set to `${searchAlg.name.lowercase()}`",
                ":label: Tag can be used using `${searchValue.trim()}`",
            ).withContext(event).titled("Tag Created").post()

            it.replyEmbeds(
                EmbedFactory.success("Successfully created the tag `${tag.name}`!", event.guild!!).build()
            ).setEphemeral(true).queue()
        }
    }

    private suspend fun deleteTag(event: SlashCommandInteractionEvent) {
        val id = event.getOption("id")?.asString ?: error("Tag id not provided")
        val tag = TagManager.get(id.toInt()) ?: error("Tag not found")

        TagManager.remove(tag)

        GuildLogger.of(event.guild!!).log(
            "Deleted the tag `${tag.name}`",
            ":identification_card: User: ${event.member?.asMention} *(${event.user.name} - ${event.user.id})*",
            ":gear: The tag was used `${tag.uses} times`",
            ":label: Tag ID: `${tag.id}`"
        ).withContext(event).titled("Tag Deleted").post()

        event.replyEmbeds(
            EmbedFactory.success("Successfully deleted the tag `${tag.name}`!", event.guild!!).build()
        ).setEphemeral(true).queue()
    }

    private fun listTags(event: SlashCommandInteractionEvent) {
        val tags = TagManager.all()

        event.replyEmbeds(
            EmbedFactory.default(
                """
                | :label: Tags
                | 
                | ${tags.joinToString("\n") {
                    "[${it.id}] ${it.name}"
                }}
                """.trimMargin()
            ).build()
        ).setEphemeral(true).queue()
    }

    private suspend fun editTag(event: SlashCommandInteractionEvent) {
        val tagId = event.getOption("id")?.asString ?: error("Tag id not provided")
        val tag = TagManager.get(tagId.toInt()) ?: error("Tag not found")

        val id = UUID.randomUUID().toString()
        val modal = Modal("minehut:tag:edit:$id", "Edit a tag") {
            short("minehut:tag:edit", tag.searchAlg().placeholder, value = tag.searchValue)
            paragraph("minehut:tag:body", "Enter the body of the tag (supports markdown)", value = tag.body)
        }

        event.replyModal(modal).queue()

        bot.listener<ModalInteractionEvent>(timeout = 15.minutes) {
            if (it.modalId != "minehut:tag:edit:$id") return@listener
            cancel()

            val searchValue = it.values.firstOrNull { it.id == "minehut:tag:edit" }?.asString
                ?: error("No search value provided")
            val body = it.values.firstOrNull { it.id == "minehut:tag:body" }?.asString
                ?: error("No body provided")

            tag.searchValue = searchValue
            tag.body = body

            TagManager.save(tag)

            GuildLogger.of(event.guild!!).log(
                "Edited the tag `${tag.name}`",
                ":identification_card: User: ${event.member?.asMention} *(${event.user.name} - ${event.user.id})*",
                ":label: Tag ID: `${tag.id}`"
            ).withContext(event).titled("Tag Edited").post()

            it.replyEmbeds(
                EmbedFactory.success("Successfully edited the tag `${tag.name}`!", event.guild!!).build()
            ).setEphemeral(true).queue()
        }
    }

    private suspend fun getTagInfo(event: SlashCommandInteractionEvent) {
        val tagId = event.getOption("id")?.asString ?: error("Tag id not provided")
        val tag = TagManager.get(tagId.toInt()) ?: error("Tag not found")

        event.replyEmbeds(
            EmbedFactory.default(
                """
                | ## :label: Tag Info
                | 
                | :gear: Tag id: `${tag.id}`
                | :label: Search Algorithm: `${tag.searchAlg().name.lowercase()}`
                | :mag: Search Value: `${tag.searchValue}`
                | 
                | :bust_in_silhouette: Author: <@${tag.createdBy}> (${tag.createdBy})
                | :calendar: Created: <t:${tag.createdAt / 1000}:R>
                | :stopwatch: Last updated: <t:${tag.updatedAt / 1000}:R>
                |
                | ```${tag.body}```
                """.trimMargin()
            ).build()
        ).setEphemeral(true).queue()
    }

    override suspend fun execute(event: SlashCommandInteractionEvent) {
        when (event.subcommandName) {
            "create" -> createTag(event)
            "delete" -> deleteTag(event)
            "list" -> listTags(event)
            "info" -> getTagInfo(event)
            "edit" -> editTag(event)
            else -> error("Subcommand not found", true)
        }
    }

}