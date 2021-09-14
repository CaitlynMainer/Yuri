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
package net.dv8tion.discord.bridge;

import com.google.common.base.Strings;
import com.google.common.collect.UnmodifiableIterator;

import net.dv8tion.discord.Settings;
import net.dv8tion.discord.SettingsManager;
import net.dv8tion.discord.Yuri;
import net.dv8tion.discord.bridge.endpoint.EndPoint;
import net.dv8tion.discord.bridge.endpoint.EndPointInfo;
import net.dv8tion.discord.bridge.endpoint.EndPointManager;
import net.dv8tion.discord.bridge.endpoint.EndPointMessage;
import net.dv8tion.discord.util.AntiPing;
import net.dv8tion.discord.util.Database;
import net.dv8tion.discord.util.PasteUtils;
import net.dv8tion.discord.util.makeTiny;
import net.dv8tion.jda.api.MessageBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.PrivateChannel;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.entities.Webhook;
import net.dv8tion.jda.api.events.Event;
import net.dv8tion.jda.api.events.GenericEvent;
import net.dv8tion.jda.api.events.ReadyEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberLeaveEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberUpdateEvent;
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateNicknameEvent;
import net.dv8tion.jda.api.events.message.MessageUpdateEvent;
import net.dv8tion.jda.api.events.message.guild.GenericGuildMessageEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageDeleteEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.events.message.guild.GuildMessageUpdateEvent;
import net.dv8tion.jda.api.events.message.priv.PrivateMessageReceivedEvent;
import net.dv8tion.jda.api.events.user.update.UserUpdateNameEvent;
import net.dv8tion.jda.api.hooks.EventListener;
import net.dv8tion.jda.api.managers.ChannelManager;

import org.jetbrains.annotations.NotNull;
import org.pircbotx.Channel;
import org.pircbotx.Colors;
import org.pircbotx.Configuration.Builder;
import org.pircbotx.PircBotX;
import org.pircbotx.User;
import org.pircbotx.exception.IrcException;
import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.ActionEvent;
import org.pircbotx.hooks.events.ConnectEvent;
import org.pircbotx.hooks.events.JoinEvent;
import org.pircbotx.hooks.events.KickEvent;
import org.pircbotx.hooks.events.MessageEvent;
import org.pircbotx.hooks.events.NickChangeEvent;
import org.pircbotx.hooks.events.PartEvent;
import org.pircbotx.hooks.events.PrivateMessageEvent;
import org.pircbotx.hooks.events.QuitEvent;
import org.pircbotx.hooks.events.TopicEvent;
import org.pircbotx.hooks.types.GenericMessageEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class IrcConnection extends ListenerAdapter implements EventListener
{

	private void sendGet(String nick, String inURL) throws Exception {
		String USER_AGENT = "Mozilla/5.0";
		String url = settings.getWebHookAvatarUpload().replace("%IRCUSERNAME%",nick).replace("%REMOTE%",inURL);

		URL obj = new URL(url);
		HttpURLConnection con = (HttpURLConnection) obj.openConnection();

		// optional default is GET
		con.setRequestMethod("GET");

		//add request header
		con.setRequestProperty("User-Agent", USER_AGENT);

		int responseCode = con.getResponseCode();
		System.out.println("\nSending 'GET' request to URL : " + url);
		System.out.println("Response Code : " + responseCode);

		BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
		StringBuffer response = new StringBuffer();
		in.close();

		//print result
		System.out.println(response.toString());

	}

	public static final int MESSAGE_DELAY_AMOUNT = 250;

	Settings settings = SettingsManager.getInstance().getSettings();
	private final IrcConnectInfo info;
	private String identifier;
	private Thread botThread;
	private PircBotX bot;
	public static HashMap<String, Member> userToNick = new HashMap<>();
	private HashMap<Member, Guild>	memberToGuild = new HashMap<>();
	private HashMap<Channel, Guild> channelToGuild = new HashMap<>();
	private HashMap<Guild, String> 	pinnedMessages = new HashMap<>();
	private HashMap<Message, Long> messagesToDelete = new HashMap<>();
	private HashMap<Guild, String> joinedGuilds = new HashMap<>();
	private ScheduledFuture<?> executor;
	public IrcConnection(IrcConnectInfo info)
	{
		this.info = info;
		identifier = info.getIdentifier();
		Builder builder = info.getIrcConfigBuilder();
		builder.addListener(this);
		builder.setMessageDelay(MESSAGE_DELAY_AMOUNT);
		builder.setAutoReconnect(true);
		builder.setAutoNickChange(true);
		bot = new PircBotX(builder.buildConfiguration());
		this.open();
	}

	public void open()
	{
		if (botThread != null)
			throw new IllegalStateException("We tried to create another bot thread before killing the current one!");

		botThread = new Thread()
		{
			public void run()
			{
				try
				{
					bot.startBot();
				}
				catch (IOException | IrcException e)
				{
					System.err.println("Yeah.. idk. Sorry");
					e.printStackTrace();
				}
			}
		};
		botThread.start();
		ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor();
		executor = ses.scheduleAtFixedRate(new Runnable() {
			@SuppressWarnings("rawtypes")
			@Override
			public void run() {
				try {
					//TODO STUFF HERE!
					if (!messagesToDelete.isEmpty()) {
						Iterator it = messagesToDelete.entrySet().iterator();
						while (it.hasNext()) {
							Map.Entry pair = (Map.Entry)it.next();
							if ((Long) pair.getValue() <= System.currentTimeMillis()) {
								((Message) pair.getKey()).delete().queue();
								it.remove(); // avoids a ConcurrentModificationException
							}
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}, 0, 1, TimeUnit.SECONDS);

	}

	public void close(String reason)
	{
		//TODO: Cleanup the EndPoints of this connection in EndPointManager.
		bot.stopBotReconnect();
		bot.sendIRC().quitServer(reason);
	}

	public String getIdentifier()
	{
		return identifier;
	}

	public PircBotX getIrcBot()
	{
		return bot;
	}

	// -----  Events -----

	// -- IRC --

	@Override
	public void onConnect(ConnectEvent event)
	{
		ConnectEvent e = event;
		//If, after connection, we don't have the defined nick AND we have auth info, attempt to ghost
		// account using our desired nick and switch to our desired nick.
		if (!event.getBot().getUserBot().getNick().equals(info.getNick()) && !Strings.isNullOrEmpty(info.getIdentPass())) {
			event.getBot().sendRaw().rawLine("NICKSERV GHOST " + info.getNick() + " " + info.getIdentPass());
		}
		ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor();
		executor = ses.scheduleAtFixedRate(new Runnable() {
			@Override
			public void run() {
				if (!e.getBot().getUserBot().getNick().equals(info.getNick())) {
					e.getBot().sendIRC().changeNick(info.getNick());
				}
			}
		}, 0, 1, TimeUnit.SECONDS);
	}

	@Override
	public void onTopic(TopicEvent event) {
		//If this returns null, then this EndPoint isn't part of a bridge.
		EndPoint endPoint = BridgeManager.getInstance().getOtherEndPoint(EndPointInfo.createFromIrcChannel(identifier, event.getChannel()));
		if (endPoint != null) {
			PreparedStatement getChans = Database.getInstance().getStatement("getChan");
			try {
				getChans.setString(1, endPoint.toEndPointInfo().getChannelId());
				ResultSet results = getChans.executeQuery();
				if (results.next()) {
					Yuri.getAPI().getTextChannelById(endPoint.toEndPointInfo().getChannelId()).getManager().setTopic(event.getTopic()).queue();
				}
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}
	}

	@Override
	public void onPrivateMessage(PrivateMessageEvent event) {
		if (event.getMessage().split(" ")[0].equals("!mapid")) {
			EndPointMessage message = EndPointMessage.createFromIrcEvent(event);
			Pattern pattern = Pattern.compile("@[^\\s\"']+\\b+|@\"([^\"]*)\"|@'([^']*)'");
			Matcher matcher = pattern.matcher(message.getMessage().toLowerCase().replace("@status", ""));
			message.setMessage(Colors.removeFormattingAndColors(message.getMessage()));
			while (matcher.find()) {
				Member checkUser = userToNick.get(matcher.group(0).toLowerCase().replace("@", "").replace("\"", "").replaceAll("\u200B", ""));
				event.getBot().sendIRC().message(event.getUser().getNick(), checkUser.getUser().getId());
			}
		}
		String pmTo = event.getMessage().split(" ")[0].replace(":", "");
		String pmMessage = Colors.removeFormattingAndColors(event.getMessage().replace(pmTo + ": ", "<" + event.getUser().getNick() + "> "));
		if (userToNick.containsKey(pmTo)) {
			Member pmToUser = userToNick.get(pmTo.toLowerCase());
			//pmToUser.getUser().openPrivateChannel().queue();
			//pmToUser.getUser().sendMessage(pmMessage).queue();
			sendPrivate(pmToUser.getUser().openPrivateChannel().complete(), pmMessage);
		}
	}

	private void sendPrivate(PrivateChannel channel, String args)
	{

		channel.sendMessage(new MessageBuilder()
				.append(args)
				.build()).queue();
	}

	/** Bold text */
	public static final String BOLD = Colors.BOLD;

	/** Underline text */
	public static final String UNDERLINE = Colors.UNDERLINE;

	/**
	 * Italic text 
	 */
	public static final String ITALIC = Colors.ITALICS;

	/**
	 * Removes all previously applied color and formatting attributes.
	 */
	public static final String NORMAL = Colors.NORMAL;

	private static String ircToDiscordFormatting(String message) {
		//Replaces IRC Codes to MarkDown
		// BOLD
		Pattern boldPattern = Pattern.compile("(\\x02([^\\x02]*)\\x02?)");
		Matcher boldMatcher = boldPattern.matcher(message);
		while (boldMatcher.find()) {
			message = message.replace(boldMatcher.group(1), "**" + boldMatcher.group(2) + "**");
		}

		// UNDERLINE
		Pattern underlinePattern = Pattern.compile("(\\x1F([^\\x1F]*)\\x1F?)");
		Matcher underlineMatcher = underlinePattern.matcher(message);
		while (underlineMatcher.find()) {
			message = message.replace(underlineMatcher.group(1), "__" + underlineMatcher.group(2) + "__");
		}

		// ITALIC
		Pattern italicPattern = Pattern.compile("(\\x1D([^\\x1D]*)\\x1D?)");
		Matcher italicMatcher = italicPattern.matcher(message);
		while (italicMatcher.find()) {
			message = message.replace(italicMatcher.group(1), "*" + italicMatcher.group(2) + "*");
		}

		// STRIKETHROUGH
		Pattern strikePattern = Pattern.compile("(\\x1E([^\\x1E]*)\\x1E?)");
		Matcher strikeMatcher = strikePattern.matcher(message);
		while (strikeMatcher.find()) {
			message = message.replace(strikeMatcher.group(1), "~~" + strikeMatcher.group(2) + "~~");
		}

		// SPOLIER?
		Pattern spoilerPattern = Pattern.compile("(\\x0301,01([^\\x0301,01]*)\\x03?)");
		Matcher spoilerMatcher = spoilerPattern.matcher(message);
		while (spoilerMatcher.find()) {
			message = message.replace(spoilerMatcher.group(1), "||" + spoilerMatcher.group(2) + "||");
		}

		return message;
	}

	public static String bg(String foreground, String background) {
		return foreground + "," + background.replace("\u0003", "");
	}

	private static String discordToIRCFormatting(String message) {
		//Replaces markdown to IRC formatting codes.
		// BOLD: replace all occurrences of "**text**" with BOLD+"text"+RESET
		message = message.replaceAll("\\*\\*([^\\*]*)\\*\\*", Colors.BOLD + "$1" + Colors.BOLD);
		// UNDERLINE:
		message = message.replaceAll("\\_\\_([^\\_\\_]*)\\_\\_", Colors.UNDERLINE + "$1" + Colors.UNDERLINE);

		// ITALIC: replace all occurrences of "*text*" with ITALIC+"text"+RESET
		message = message.replaceAll("\\*([^\\*]*)\\*", Colors.ITALICS + "$1" + Colors.ITALICS);

		message = message.replaceAll("\\_([^\\_]*)\\_", Colors.ITALICS + "$1" + Colors.ITALICS);

		//message = message.replaceAll("\\~\\~([^\\~\\~]*)\\~\\~", "\\x1E$1\\x1E");

		message = message.replaceAll("\\|\\|([^\\|\\|]*)\\|\\|", "SPOILER: " + bg(Colors.BLACK,Colors.BLACK) + "$1" + Colors.NORMAL);

		return message;
	}


	@SuppressWarnings("rawtypes")
	public void parseMessage(EndPoint endPoint, GenericMessageEvent event, Boolean checkStatus) {
		if (endPoint != null) {
			String chanName;
			if (event instanceof MessageEvent) {
				chanName = ((MessageEvent) event).getChannel().getName();
			} else {
				chanName = ((ActionEvent) event).getChannel().getName();
			}
			EndPointMessage message = EndPointMessage.createFromIrcEvent(event);

			message.setMessage(ircToDiscordFormatting(message.getMessage()));
			Pattern pattern = Pattern.compile("@[^\\s\"']+|@\"([^\"]*)\"|@'([^']*)'");
			Matcher matcher = pattern.matcher(message.getMessage().toLowerCase().replace("@status", ""));
			message.setMessage(Colors.removeFormattingAndColors(message.getMessage()));
			while (matcher.find()) {
				Member checkUser = userToNick.get(matcher.group(0).toLowerCase().replace("@", "").replace("\"", "").replaceAll("\u200B", ""));
				if (userToNick.containsKey(matcher.group(0).toLowerCase().replace("@", "").replace("\"", "").replaceAll("[\\p{Cf}]", ""))) {
					if (checkStatus) {
						String playing = "";
						//if (checkUser.getOnlineStatus().name().equals("ONLINE") && (checkUser.getActivities() != null)) {
						//	playing = " Playing: " + checkUser.getActivities().;
						//}
						event.getBot().sendIRC().message(chanName, "<Discord> " + checkUser.getEffectiveName() + " is currently " + checkUser.getOnlineStatus() + playing);
						return;
					}
					message.setMessage(message.getMessage().replaceAll("(?i)"+matcher.group(0), checkUser.getAsMention()).replace("@<", "<"));
				} else {
					if (checkStatus) {
						event.getBot().sendIRC().message(chanName, "<Discord> " + matcher.group(0).toLowerCase().replace("@", "").replace("\"", "").replaceAll("\u200B", "") + " is not a member of this server.");
						return;
					}
				}
			}
			message.setMessage(message.getMessage().replaceAll("@everyone"," I just tried to ping everyone. ").replaceAll("@here"," I just tried to ping everyone. "));
			if(event instanceof ActionEvent) {
				String inMessage = message.getMessage().replaceAll("_","\\\\_");
				message.setMessage("_" + inMessage + "_");
			}
			endPoint.sendMessage(message);
		}
	}

	@Override
	public void onMessage(MessageEvent event)
	{
		if (Yuri.ignoredUsers.containsKey(event.getUser().getNick())) {
			return;
		}
		EndPoint endPoint = BridgeManager.getInstance().getOtherEndPoint(EndPointInfo.createFromIrcChannel(identifier, event.getChannel()));
		Boolean checkStatus = false;
		//Specific to the the Imaginescape IRC/Discord channel. Dumb minecraft server spits out an empty message that is really annoying.
		if (event.getUser().getNick().equals("IServer") && event.getMessage().equals("[Server]"))
			return;

		if (event.getMessage().toLowerCase().startsWith("@status")) {
			checkStatus = true;
		}

		if (event.getMessage().startsWith("!users")) {
			String users = "";
			for(String currentKey : userToNick.keySet()) {
				if (memberToGuild.get(userToNick.get(currentKey)).getId().equals(endPoint.toEndPointInfo().getConnectorId())) {
					users += userToNick.get(currentKey).getUser().getName().replace("\n", "").replace("\r", "") + " | Status: " + userToNick.get(currentKey).getOnlineStatus() + "\r\n";
				}
			}
			event.getBot().sendIRC().message(event.getChannel().getName(), "<Discord> Current Discord users: " + PasteUtils.paste(users));
			return;
		}

		if (event.getMessage().startsWith("!setmyavatar")) {
			if (event.getUser().isVerified()) {
				try {
					this.sendGet(event.getUser().getNick(), URLEncoder.encode(event.getMessage().substring("!setmyavatar".length() + 1), "UTF-8"));
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
					event.getBot().sendIRC().message(event.getChannel().getName(), "There was an error processing this request poke Mimiru!");
				}
			}
			return;
		}

		//If this returns null, then this EndPoint isn't part of a bridge.
		parseMessage(endPoint, event, checkStatus);
	}

	@Override
	public void onAction(ActionEvent event)
	{
		if (Yuri.ignoredUsers.containsKey(event.getUser().getNick())) {
			return;
		}
		//Specific to the the Imaginescape IRC/Discord channel. Dumb minecraft server spits out an empty message that is really annoying.
		if (event.getUser().getNick().equals("IServer") && event.getMessage().equals("[Server]"))
			return;

		//If this returns null, then this EndPoint isn't part of a bridge.
		EndPoint endPoint = BridgeManager.getInstance().getOtherEndPoint(EndPointInfo.createFromIrcChannel(identifier, event.getChannel()));
		parseMessage(endPoint, event, false);
	}

	@Override
	public void onQuit(QuitEvent event) {
		String nick = event.getUser().getNick();
		for (String channelName : Yuri.channelNicks.keySet()) {
			if (Yuri.channelNicks.get(channelName).contains(nick)) {
				PreparedStatement getChans = Database.getInstance().getStatement("getChan");
				try {
					EndPoint endPoint = BridgeManager.getInstance().getOtherEndPoint(EndPointInfo.createFromIrcChannel(identifier, getChannel(channelName)));
					getChans.setString(1, endPoint.toEndPointInfo().getChannelId());
					ResultSet results = getChans.executeQuery();
					if (results.next()) {
						endPoint.sendMessage(Colors.removeFormattingAndColors(event.getUser().getNick() + " ha"+ "\u200B" +"s quit IRC (" + event.getReason() + ")"));
					}
				} catch (SQLException e) {
					e.printStackTrace();
				}
			}
			Yuri.channelNicks.get(channelName).remove(nick);
		}
		updateNickList();
	}

	@Override
	public void onPart(PartEvent event) {
		//if (messages.containsValue(event.getUser().getNick())) {
		PreparedStatement getChans = Database.getInstance().getStatement("getChan");
		try {
			EndPoint endPoint = BridgeManager.getInstance().getOtherEndPoint(EndPointInfo.createFromIrcChannel(identifier, event.getChannel()));
			getChans.setString(1, endPoint.toEndPointInfo().getChannelId());
			ResultSet results = getChans.executeQuery();
			if (results.next()) {
				endPoint.sendMessage(Colors.removeFormattingAndColors(event.getUser().getNick() + " ha"+ "\u200B" +"s left the channel on IRC (" + event.getReason() + ")"));
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		//}
		updateNickList(event.getChannel());
	}

	@Override
	public void onNickChange(NickChangeEvent event) {
		String nick = event.getOldNick();
		for (String channelName : Yuri.channelNicks.keySet()) {
			if (Yuri.channelNicks.get(channelName).contains(nick)) {
				EndPoint endPoint = BridgeManager.getInstance().getOtherEndPoint(EndPointInfo.createFromIrcChannel(identifier, getChannel(channelName)));
				PreparedStatement getChans = Database.getInstance().getStatement("getChan");
				try {
					getChans.setString(1, endPoint.toEndPointInfo().getChannelId());
					ResultSet results = getChans.executeQuery();
					if (results.next()) {
						endPoint.sendMessage(nick + " is no"+ "\u200B" +"w known as " + event.getNewNick());
					}
				} catch (SQLException e) {
					e.printStackTrace();
				}
			}
			Yuri.channelNicks.get(channelName).remove(nick);
		}
		updateNickList();
	}

	@Override
	public void onKick(KickEvent event) {
		PreparedStatement getChans = Database.getInstance().getStatement("getChan");
		try {
			EndPoint endPoint = BridgeManager.getInstance().getOtherEndPoint(EndPointInfo.createFromIrcChannel(identifier, event.getChannel()));
			getChans.setString(1, endPoint.toEndPointInfo().getChannelId());
			ResultSet results = getChans.executeQuery();
			if (results.next()) {
				endPoint.sendMessage(Colors.removeFormattingAndColors(event.getRecipient().getNick() + " ha"+ "\u200B" +"s been kicked from " + event.getChannel().getName() + " on IRC by " + event.getUser().getNick() + " (" + event.getReason() + ")"));
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
		updateNickList(event.getChannel());
	}

	@Override
	public void onJoin(JoinEvent event) {
		if (event.getBot().getUserBot().equals(event.getUser())) {
			EndPointManager.getInstance().createEndPoint(EndPointInfo.createFromIrcChannel(identifier, event.getChannel()));
			EndPoint endPoint = BridgeManager.getInstance().getOtherEndPoint(EndPointInfo.createFromIrcChannel(identifier, event.getChannel()));
			channelToGuild.put(event.getChannel(), Yuri.getAPI().getGuildById(endPoint.toEndPointInfo().getConnectorId()));
		} else {
			EndPoint endPoint = BridgeManager.getInstance().getOtherEndPoint(EndPointInfo.createFromIrcChannel(identifier, event.getChannel()));
			if (endPoint != null) {
				channelToGuild.put(event.getChannel(), Yuri.getAPI().getGuildById(endPoint.toEndPointInfo().getConnectorId()));
				PreparedStatement getChans = Database.getInstance().getStatement("getChan");
				try {
					getChans.setString(1, endPoint.toEndPointInfo().getChannelId());
					ResultSet results = getChans.executeQuery();
					if (results.next()) {
						endPoint.sendMessage(event.getUser().getNick() + " ha"+ "\u200B" +"s joined " + event.getChannel().getName() + " on IRC");
					}
				} catch (SQLException e) {
					e.printStackTrace();
				}
			}
		}
		updateNickList(event.getChannel());
	}

	// -- Discord --

	private String removeUrl(String commentstr) {
		String urlPattern = "((https?|ftp|gopher|telnet|file|Unsure|http):((//)|(\\\\))+[\\w\\d:#@%/;$()~_?\\+-=\\\\\\.&]*)";
		Pattern p = Pattern.compile(urlPattern, Pattern.CASE_INSENSITIVE);
		Matcher m = p.matcher(commentstr);
		int i = 0;
		while (m.find()) {
			commentstr = commentstr.replaceAll(m.group(i), "").trim();
			i++;
		}
		return commentstr;
	}

	public String addSpace(String in) {
		if (in.length() > 0) {
			return in + " ";
		} else {
			return in;
		}
	}

	public void updateNickList() {
		if (!this.getIrcBot().isConnected()) {
			return;
		}
		for (Channel channel : this.getIrcBot().getUserChannelDao().getAllChannels()) {
			this.updateNickList(channel);
		}
	}

	public void updateNickList(Channel channel) {
		if (!this.getIrcBot().isConnected()) {
			return;
		}
		// Build current list of names in channel
		ArrayList<String> users = new ArrayList<>();
		for (org.pircbotx.User user : channel.getUsers()) {
			//plugin.logDebug("N: " + user.getNick());
			users.add(user.getNick());
		}
		try {
			Yuri.wl.tryLock(10, TimeUnit.MILLISECONDS);
		} catch (InterruptedException ex) {
			return;
		}
		try {
			String channelName = channel.getName();
			Yuri.channelNicks.put(channelName, users);
		} finally {
			Yuri.wl.unlock();
		}
	}

	public Channel getChannel(String channelName) {
		Channel channel = null;
		for (Channel c : this.getIrcBot().getUserChannelDao().getAllChannels()) {
			if (c.getName().equalsIgnoreCase(channelName)) {
				return c;
			}
		}
		return channel;
	}

	public static String getIDFromUser(String target) {
		String input = target.toLowerCase().replace("/", "").replace("@", "").replace("\"", "").replaceAll("\u200B", "");
		try {
			input = URLDecoder.decode(input, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		if (userToNick.containsKey(input)) {
			Member checkUser = userToNick.get(input);
			return checkUser.getUser().getId();
		} else {
			return input;
		}

	}

	// -- Discord --

	@Override
	public void onEvent(GenericEvent event) {
		Boolean abort = false;
		if (event instanceof MessageUpdateEvent) {
			MessageUpdateEvent e = (MessageUpdateEvent) event;
			if (pinnedMessages.containsValue(e.getMessage().getId())) {
				return;
			}
			((MessageUpdateEvent) event).getChannel().retrievePinnedMessages().queue(new Consumer<List<Message>>() {
																						 @Override
																						 public void accept(List<Message> t) {
																							 ListIterator<Message> it = t.listIterator();
																							 while(it.hasNext()) {
																								 Message msg = it.next();
																								 pinnedMessages.put(((MessageUpdateEvent) event).getGuild(), msg.getId());
																							 }
																						 }
																					 }
			);
			if (!e.getMessage().isEdited()) {
				return;
			}
		}

		if (event instanceof GuildMessageUpdateEvent) {
			GuildMessageUpdateEvent e = (GuildMessageUpdateEvent) event;
			if (pinnedMessages.containsValue(e.getMessage().getId())) {
				return;
			}
			((GuildMessageUpdateEvent) event).getChannel().retrievePinnedMessages().queue(new Consumer<List<Message>>() {
																							  @Override
																							  public void accept(List<Message> t) {
																								  ListIterator<Message> it = t.listIterator();
																								  while(it.hasNext()) {
																									  Message msg = it.next();
																									  pinnedMessages.put(((GuildMessageUpdateEvent) event).getGuild(), msg.getId());
																								  }
																							  }
																						  }
			);
			if (!e.getMessage().isEdited()) {
				return;
			}
			EndPoint endPoint = BridgeManager.getInstance().getOtherEndPoint(EndPointInfo.createFromDiscordChannel(e.getChannel()));
			String userNick;
			userNick = e.getMember().getEffectiveName();
			userToNick.put(userNick, e.getMember());
			EndPointMessage message = EndPointMessage.createFromDiscordEvent(e);
			String parsedMessage = "";
			String nick;
			String tinyURL = "";
			if (!e.getMessage().getAttachments().isEmpty()) {
				for (Message.Attachment attach : e.getMessage().getAttachments()) {
					tinyURL = makeTiny.getTinyURL(attach.getUrl());
					parsedMessage += "<" + AntiPing.antiPing(userNick) + "> " + addSpace(removeUrl(message.getMessage())) + tinyURL;
				}
				parsedMessage.replace(tinyURL, "");
				endPoint.sendMessage(parsedMessage.toString() + " [Edited]");
			} else {
				String messageString = message.getMessage();
				final String regex = "``?`?.*?\\n?((?:.|\\n)*?)\\n?``?`?";
				Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
				Matcher matcher = pattern.matcher(messageString);
				while (matcher.find()) {
					if (matcher.group(1).contains("\n"))
						messageString = messageString.replace(matcher.group(0), "Code Block pastebined "+PasteUtils.paste(matcher.group(1), PasteUtils.Formats.NONE));
				}
				if (message.getMessage().startsWith("_") && message.getMessage().endsWith("_")) {
					message = EndPointMessage.createFromDiscordEvent(e);
					message.setMessage(messageString.replaceAll("(?m)^[ \t]*\r?\n", "") + " [Edited]");
					endPoint.sendAction(message);
				} else {
					message = EndPointMessage.createFromDiscordEvent(e);
					message.setMessage(messageString.replaceAll("(?m)^[ \t]*\r?\n", "") + " [Edited]");
					endPoint.sendMessage(message);
				}
			}
		}

		if (event instanceof PrivateMessageReceivedEvent) {
			PrivateMessageReceivedEvent e = (PrivateMessageReceivedEvent) event;
			if (event.getJDA().getSelfUser().getId().equals(e.getAuthor().getId()))
				return;
			String pmTo = e.getMessage().getContentDisplay().split(" ")[0].replace(":", "");
			String pmMessage = e.getMessage().getContentDisplay().replace(pmTo + ": ", "<" + e.getAuthor().getName() + ">" + " ");

			bot.sendIRC().message(pmTo, pmMessage);
		}

		if (event instanceof ReadyEvent) {
			for (Guild currGuild : event.getJDA().getGuilds()) {
				joinedGuilds.put(currGuild, currGuild.getName());
				for (Member currMember : currGuild.getMembers()) {
					String userNick;
					userNick = currMember.getEffectiveName().toLowerCase();
					userToNick.put(userNick, currMember);
					memberToGuild.put(currMember, currGuild);
					System.out.println("Adding user: " + userNick + " | " + currMember.getUser().getName() + " | " + currGuild.getName());
				}
				for (TextChannel currChan : currGuild.getTextChannels()) {
					System.out.println(currGuild.getName());
					System.out.println(currChan.getName());
					if (currChan.canTalk()) {
						currChan.retrievePinnedMessages().queue(new Consumer<List<Message>>() {

							@Override
							public void accept(List<Message> t) {
								ListIterator<Message> it = t.listIterator();
								while(it.hasNext()) {
									Message msg = it.next();
									pinnedMessages.put(currGuild, msg.getId());
								}
							}
						} );
					}
				}
			}
		}

		if (event instanceof UserUpdateNameEvent) {
			UserUpdateNameEvent e = (UserUpdateNameEvent) event;
			userToNick.remove(e.getOldName());
			for (Guild currGuild : event.getJDA().getGuilds()) {
				Member currMember = currGuild.getMemberById(e.getUser().getId());
				String userNick = currMember.getEffectiveName().toLowerCase();
				userToNick.put(userNick, currMember);
				memberToGuild.put(currMember, currGuild);
			}
		}

		if (event instanceof GuildMemberUpdateNicknameEvent) {
			GuildMemberUpdateNicknameEvent e = (GuildMemberUpdateNicknameEvent) event;
			Member currMember = e.getMember();
			String userNick;
			userNick = currMember.getEffectiveName().toLowerCase();
			userToNick.put(userNick, currMember);
			memberToGuild.put(currMember, currMember.getGuild());
		}

		if (event instanceof GuildMemberJoinEvent) {
			GuildMemberJoinEvent e = (GuildMemberJoinEvent) event;
			userToNick.put(e.getMember().getEffectiveName().toLowerCase(), e.getMember());
			memberToGuild.put(e.getMember(), e.getGuild());
		}

		if (event instanceof GuildMemberLeaveEvent) {
			GuildMemberLeaveEvent e = (GuildMemberLeaveEvent) event;
			userToNick.remove(e.getMember().getEffectiveName().toLowerCase(), e.getMember());
			memberToGuild.remove(e.getMember(), e.getGuild());
		}

		//We only deal with TextChannel Message events
		if (!(event instanceof GenericGuildMessageEvent))
			return;

		//Don't care about deleted messages or embeds.
		if (event instanceof GuildMessageDeleteEvent /*|| event instanceof GuildMessageEmbedEvent*/)
			return;

		GuildMessageReceivedEvent e = (GuildMessageReceivedEvent) event;
		//Null = Webhook?
		if (e.getAuthor().getId() == null){
			return;
		}

		String msgContents = e.getMessage().getContentDisplay();
		if (msgContents.contains("discord.amazingsexdating.com")) {
			messagesToDelete.put(e.getMessage(), System.currentTimeMillis() + 10);
			return;
		}
		/*
		if (msgContents.contains(" ha"+ "\u200B" +"s quit IRC ") || msgContents.contains(" ha"+ "\u200B" +"s joined ") ||
				msgContents.contains(" ha"+ "\u200B" +"s left the channel on IRC ") || msgContents.contains(" is n"+ "\u200B" +"ow known as ") ||
				msgContents.contains(" ha"+ "\u200B" +"s been kicked from ") ||
				msgContents.contains("Bridge Bot available")) {
			messagesToDelete.put(e.getMessage(), System.currentTimeMillis() + 30000);
		}*/

		//Basically: If we are the ones that sent the message, don't send it to IRC.
		if (event.getJDA().getSelfUser().getId().equals(e.getAuthor().getId()))
			return;

		//If this returns null, then this EndPoint isn't part of a bridge.
		EndPoint endPoint = BridgeManager.getInstance().getOtherEndPoint(EndPointInfo.createFromDiscordChannel(e.getChannel()));
		if (endPoint != null && !abort) {
			if (e.getMember() != null) {


				String userNick;
				userNick = e.getMember().getEffectiveName();
				userToNick.put(userNick, e.getMember());
				EndPointMessage message = EndPointMessage.createFromDiscordEvent(e);
				String parsedMessage = "";
				String nick;
				String tinyURL = "";

				if (!e.getMessage().getAttachments().isEmpty()) {
					for (Message.Attachment attach : e.getMessage().getAttachments()) {
						tinyURL = makeTiny.getTinyURL(attach.getUrl());
						parsedMessage += "<" + AntiPing.antiPing(userNick) + "> " + addSpace(removeUrl(message.getMessage())) + tinyURL;
					}
					parsedMessage.replace(tinyURL, "");
					endPoint.sendMessage(parsedMessage.toString());
				} else {

					String messageString = message.getMessage();
					if (messageString.startsWith("!users")) {
						String users = "";
						System.out.println(channelToGuild.size());
						for(Entry<Channel, Guild> currentKey : channelToGuild.entrySet()) {
							if (currentKey.getValue().getId().equals(e.getGuild().getId())) {
								UnmodifiableIterator<User> iterator = bot.getUserChannelDao().getChannel(currentKey.getKey().getName()).getUsers().iterator();
								while (iterator.hasNext()) {
									users += iterator.next().getNick() + "\r\n";
								}
							}
						}
						try {
							event.getJDA().getTextChannelById(e.getChannel().getId()).sendMessage("<IRC> Current IRC users: " + PasteUtils.paste(users)).queue();
						} catch (Exception e1) {
							e1.printStackTrace();
						}
						return;
					}


					// Pattern for recognizing a URL, based off RFC 3986
					Pattern urlPattern = Pattern.compile(
							"(?:^|[\\W])((ht|f)tp(s?):\\/\\/|www\\.)"
									+ "(([\\w\\-]+\\.){1,}?([\\w\\-.~]+\\/?)*"
									+ "[\\p{Alnum}.,%_=?&#\\-+()\\[\\]\\*$~@!:/{};']*)",
							Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);
					Matcher urlmatcher = urlPattern.matcher(message.getMessage());
					if (!urlmatcher.find()) {
						messageString = discordToIRCFormatting(message.getMessage());
					}


					if (e.getMessage().getReferencedMessage() != null) {
						String theReply = e.getMessage().getReferencedMessage().getContentRaw();
						String theReplyAuthor = e.getMessage().getReferencedMessage().getAuthor().getName();
						String preview = "";

						if (theReply.length() > 30 + (userNick.length() + 2))
						{
							preview = theReply.substring(0, 30 + (userNick.length() + 2)) + "…";
						}
						else
						{
							preview = theReply;
						}
						message = EndPointMessage.createFromDiscordEvent(e);
						message.setMessage(">" + theReplyAuthor + ": " + preview.replaceAll("(?m)^[ \t]*\r?\n", ""));
						endPoint.sendMessage(message);
					}

					final String regex = "``?`?.*?\\n?((?:.|\\n)*?)\\n?``?`?";
					Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
					Matcher matcher = pattern.matcher(messageString);
					while (matcher.find()) {
						if (matcher.group(1).contains("\n"))
							messageString = messageString.replace(matcher.group(0), "Code Block pastebined "+PasteUtils.paste(matcher.group(1), PasteUtils.Formats.NONE));
					}
					if (message.getMessage().startsWith("_") && message.getMessage().endsWith("_")) {
						message = EndPointMessage.createFromDiscordEvent(e);
						message.setMessage(messageString.replaceAll("(?m)^[ \t]*\r?\n", ""));
						endPoint.sendAction(message);
					} else {
						message = EndPointMessage.createFromDiscordEvent(e);
						message.setMessage(messageString.replaceAll("(?m)^[ \t]*\r?\n", ""));
						endPoint.sendMessage(message);
					}
				}
			} else if (e.getMessage().isWebhookMessage()) {
				EndPointMessage message = EndPointMessage.createFromDiscordEvent(e);
				String messageString = message.getMessage();
				if (e.getGuild() != null && e.getGuild().getSelfMember().hasPermission(Permission.MANAGE_WEBHOOKS)) {
					List<Webhook> webhook = e.getChannel().retrieveWebhooks().complete(); // some webhook instance
					if (webhook.size() == 0) {
						throw new RuntimeException();
					}
					for (Webhook hook : webhook) {
						if (hook.getName().equalsIgnoreCase(settings.getWebHookName())) {
							if (hook.getId().equals(e.getAuthor().getId())) {
								return;
							}
						}
					}
				}

				message = EndPointMessage.createFromDiscordEvent(e);
				message.setMessage(messageString.replaceAll("(?m)^[ \t]*\r?\n", ""));
				endPoint.sendMessage(message);
			}
		}
	}
}
