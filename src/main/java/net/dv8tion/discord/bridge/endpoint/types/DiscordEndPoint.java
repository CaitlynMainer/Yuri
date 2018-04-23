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
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import net.dv8tion.discord.Settings;
import net.dv8tion.discord.SettingsManager;
import net.dv8tion.discord.Yuri;
import net.dv8tion.discord.bridge.endpoint.EndPoint;
import net.dv8tion.discord.bridge.endpoint.EndPointInfo;
import net.dv8tion.discord.bridge.endpoint.EndPointMessage;
import net.dv8tion.discord.bridge.endpoint.EndPointType;
import net.dv8tion.jda.core.entities.Guild;
import net.dv8tion.jda.core.entities.TextChannel;
import net.dv8tion.jda.core.entities.Webhook;
import net.dv8tion.jda.webhook.WebhookClient;
import net.dv8tion.jda.webhook.WebhookClientBuilder;
import net.dv8tion.jda.webhook.WebhookMessage;
import net.dv8tion.jda.webhook.WebhookMessageBuilder;

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
			List<Webhook> webhook = getChannel().getWebhooks().complete(); // some webhook instance
			if (webhook.size() == 0) {
				throw new RuntimeException();
			}
			for (Webhook hook : webhook) {
				if (hook.getName().equalsIgnoreCase(settings.getWebHookName())) {
					String nick = StringUtils.substringBetween(message, "<", ">");
					WebhookClientBuilder builder = hook.newClient(); //Get the first webhook.. I can't think of a better way to do this ATM.
					WebhookClient client = builder.build();
					WebhookMessageBuilder builder1 = new WebhookMessageBuilder();
					builder1.setContent(message.replaceFirst(Pattern.quote("<"+nick+">"), ""));
					//MessageEmbed firstEmbed = new EmbedBuilder().setColor(Color.RED).setDescription("This is one embed").build();
					//MessageEmbed secondEmbed = new EmbedBuilder().setColor(Color.GREEN).setDescription("This is another embed").build();
					builder1.setUsername(nick);
					String avatar = "";
					if (hook.getDefaultUser().getAvatarUrl() == null) {
						avatar = settings.getWebHookAvatar().replace("%IRCUSERNAME%", nick);
					} else {
						avatar = hook.getDefaultUser().getAvatarUrl();
					}
					builder1.setAvatarUrl(avatar);
					WebhookMessage message1 = builder1.build();
					client.send(message1);
					client.close();
					return;
				}
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
