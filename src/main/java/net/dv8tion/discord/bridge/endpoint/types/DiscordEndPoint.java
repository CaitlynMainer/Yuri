/**
 *     Copyright 2015-2016 Austin Keener
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.dv8tion.discord.bridge.endpoint.types;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.regex.Pattern;

import club.minnced.discord.webhook.WebhookClient;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.*;
import org.apache.commons.lang3.StringUtils;

import net.dv8tion.discord.Settings;
import net.dv8tion.discord.SettingsManager;
import net.dv8tion.discord.Yuri;
import net.dv8tion.discord.bridge.endpoint.EndPoint;
import net.dv8tion.discord.bridge.endpoint.EndPointInfo;
import net.dv8tion.discord.bridge.endpoint.EndPointMessage;
import net.dv8tion.discord.bridge.endpoint.EndPointType;
import net.dv8tion.jda.api.Permission;

import club.minnced.discord.webhook.send.WebhookMessageBuilder;

public class DiscordEndPoint extends EndPoint
{
	public static final int MAX_MESSAGE_LENGTH = 2000;

	private String guildId;
	private String channelId;
	Settings settings = SettingsManager.getInstance().getSettings();
	public DiscordEndPoint(EndPointInfo info)
	{
		super(EndPointType.DISCORD);
		this.guildId = info.getConnectorId();
		this.channelId = info.getChannelId();
	}

	public String getGuildId()
	{
		return guildId;
	}

	public Guild getGuild()
	{
		return Yuri.getAPI().getGuildById(guildId);
	}

	public String getChannelId()
	{
		return channelId;
	}

	public TextChannel getChannel()
	{
		return Yuri.getAPI().getTextChannelById(channelId);
	}

	@Override
	public EndPointInfo toEndPointInfo()
	{
		return new EndPointInfo( this.connectionType, this.guildId, this.channelId);
	}

	@Override
	public int getMaxMessageLength()
	{
		return MAX_MESSAGE_LENGTH;
	}

	@Override
	public void sendMessage(String message)
	{
		if (!connected)
			throw new IllegalStateException("Cannot send message to disconnected EndPoint! EndPoint: " + this.toEndPointInfo().toString());
		try
		{
			if (getChannel().getGuild() != null && getChannel().getGuild().getSelfMember().hasPermission(Permission.MANAGE_WEBHOOKS)) {
				List<Webhook> webhook = getChannel().retrieveWebhooks().complete();
				if (webhook.size() == 0) {
					throw new RuntimeException();
				}
				for (Webhook hook : webhook) {
					if (hook.getName().equalsIgnoreCase(settings.getWebHookName())) {
						String nick = StringUtils.substringBetween(message, "<", ">");

						String formattedMessage = message;

						// Format the message to include @ mentions to the users from the channel
						List<Member> members = getChannel().getMembers();
						for (Member m : members) {
							if (formattedMessage.toLowerCase().contains("@" + m.getEffectiveName().toLowerCase())) {
								formattedMessage = formattedMessage.replace("@" + m.getEffectiveName(), m.getAsMention());
							}
							if (formattedMessage.toLowerCase().contains("@" + m.getUser().getName().toLowerCase())) {
								formattedMessage = formattedMessage.replace("@" + m.getUser().getName(), m.getAsMention());
							}
						}

						// Format the message to disable @ everyone and @ here (by adding a zero-width
						// character in the middle of them)
						formattedMessage = formattedMessage.replace("@everyone", "@" + "\u00a0" + "everyone");
						formattedMessage = formattedMessage.replace("@here", "@" + "\u00a0" + "here");
						WebhookClient client = null;
						// Prepare to send the message over the webhook
						try {
							client = WebhookClient.withUrl(hook.getUrl().replace("discord.com", "discordapp.com").replace("v6/", ""));
						} catch (Exception e) {
							e.printStackTrace();
						}
						Optional<String> discordUsername = Optional.empty();
						WebhookMessageBuilder builder = new WebhookMessageBuilder();

						String avatar = "";
						if (hook.getDefaultUser().getAvatarUrl() == null) {
							avatar = settings.getWebHookAvatar().replace("%IRCUSERNAME%", nick) + "&random="+Math.random();
						} else {
							avatar = hook.getDefaultUser().getAvatarUrl();
						}
						builder.setAvatarUrl(avatar);

						builder.setContent(message.replaceFirst(Pattern.quote("<"+nick+">"), ""));
						//MessageEmbed firstEmbed = new EmbedBuilder().setColor(Color.RED).setDescription("This is one embed").build();
						//MessageEmbed secondEmbed = new EmbedBuilder().setColor(Color.GREEN).setDescription("This is another embed").build();

						// Set the username for the WebHook message, sung the given formatter
						builder.setUsername(nick);
						// Set the contents for the WebHook message

						// Send the message to the WebHook
						//WebhookMessage webhookMessage = messageBuilder.build();
						client.send(builder.build());















						//WebhookClientBuilder builder = hook.newClient(); //Get the first webhook.. I can't think of a better way to do this ATM.
						//WebhookClient client = builder.build();
						//WebhookMessageBuilder builder1 = new WebhookMessageBuilder();
						//builder1.setContent(message.replaceFirst(Pattern.quote("<"+nick+">"), ""));
						//MessageEmbed firstEmbed = new EmbedBuilder().setColor(Color.RED).setDescription("This is one embed").build();
						//MessageEmbed secondEmbed = new EmbedBuilder().setColor(Color.GREEN).setDescription("This is another embed").build();
						//builder1.setUsername(nick);
						//String avatar = "";
						//if (hook.getDefaultUser().getAvatarUrl() == null) {
						//	avatar = settings.getWebHookAvatar().replace("%IRCUSERNAME%", nick) + "&random="+Math.random();
						//} else {
						//	avatar = hook.getDefaultUser().getAvatarUrl();
						//}
						//builder1.setAvatarUrl(avatar);
						//WebhookMessage message1 = builder1.build();
						//client.send(message1);
						//client.close();
						return;
					}
				}
				getChannel().sendMessage(message).queue();
			} else {
				System.out.println("ERROR! Do not have \"Manage WebHooks\" Permission!");
				getChannel().sendMessage(message).queue();
			}
		} catch (Exception e1) {
			getChannel().sendMessage(message).queue();
		}
	}

	@Override
	public void sendMessage(EndPointMessage message)
	{
		if (!connected)
			throw new IllegalStateException("Cannot send message to disconnected EndPoint! EndPoint: " + this.toEndPointInfo().toString());
		switch (message.getType())
		{
		case DISCORD:
			getChannel().sendMessage(message.getDiscordMessage()).queue();
			break;
		default:
			for (String segment : this.divideMessageForSending(message.getMessage()))
				sendMessage(String.format("<%s> %s", message.getSenderName(), segment));
		}
	}

	@Override
	public void sendAction(String message) {
		// TODO Auto-generated method stub

	}

	@Override
	public void sendAction(EndPointMessage message) {
		// TODO Auto-generated method stub

	}
}
