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

import net.dv8tion.discord.Yuri;
import net.dv8tion.discord.bridge.endpoint.EndPoint;
import net.dv8tion.discord.bridge.endpoint.EndPointInfo;
import net.dv8tion.discord.bridge.endpoint.EndPointManager;
import net.dv8tion.discord.bridge.endpoint.EndPointMessage;
import net.dv8tion.discord.util.Database;
import net.dv8tion.discord.util.PasteUtils;
import net.dv8tion.discord.util.makeTiny;
import net.dv8tion.jda.core.entities.Guild;
import net.dv8tion.jda.core.entities.Member;
import net.dv8tion.jda.core.entities.Message;
import net.dv8tion.jda.core.entities.TextChannel;
import net.dv8tion.jda.core.events.Event;
import net.dv8tion.jda.core.events.ReadyEvent;
import net.dv8tion.jda.core.events.guild.GuildJoinEvent;
import net.dv8tion.jda.core.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.core.events.guild.member.GuildMemberLeaveEvent;
import net.dv8tion.jda.core.events.guild.member.GuildMemberNickChangeEvent;
import net.dv8tion.jda.core.events.message.MessageUpdateEvent;
import net.dv8tion.jda.core.events.message.guild.GenericGuildMessageEvent;
import net.dv8tion.jda.core.events.message.guild.GuildMessageDeleteEvent;
import net.dv8tion.jda.core.events.message.guild.GuildMessageUpdateEvent;
import net.dv8tion.jda.core.events.message.priv.PrivateMessageReceivedEvent;
import net.dv8tion.jda.core.hooks.EventListener;
import net.dv8tion.jda.core.managers.ChannelManager;
import org.pircbotx.Channel;
import org.pircbotx.Colors;
import org.pircbotx.Configuration.Builder;
import org.pircbotx.PircBotX;
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

import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class IrcConnection extends ListenerAdapter<PircBotX> implements EventListener
{
    public static final int MESSAGE_DELAY_AMOUNT = 250;

    private final IrcConnectInfo info;
    private String identifier;
    private Thread botThread;
    private PircBotX bot;
    private HashMap<String, Member> userToNick = new HashMap<>();
    private HashMap<Member, Guild>	memberToGuild = new HashMap<>();
    private HashMap<Guild, String> 	pinnedMessages = new HashMap<>();
    
    public IrcConnection(IrcConnectInfo info)
    {
        this.info = info;
        identifier = info.getIdentifier();
        Builder<PircBotX> builder = info.getIrcConfigBuilder();
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
    public void onConnect(ConnectEvent<PircBotX> event)
    {
        //If, after connection, we don't have the defined nick AND we have auth info, attempt to ghost
        // account using our desired nick and switch to our desired nick.
        if (!event.getBot().getUserBot().getNick().equals(info.getNick())
                && !Strings.isNullOrEmpty(info.getIdentPass()))
        {
            event.getBot().sendRaw().rawLine("NICKSERV GHOST " + info.getNick() + " " + info.getIdentPass());
            event.getBot().sendIRC().changeNick(info.getNick());
        }
    }

    @Override
    public void onTopic(TopicEvent<PircBotX> event) {
        //If this returns null, then this EndPoint isn't part of a bridge.
        EndPoint endPoint = BridgeManager.getInstance().getOtherEndPoint(EndPointInfo.createFromIrcChannel(identifier, event.getChannel()));
        if (endPoint != null) {
            PreparedStatement getChans = Database.getInstance().getStatement("getChan");
            try {
                getChans.setString(1, endPoint.toEndPointInfo().getChannelId());
                ResultSet results = getChans.executeQuery();
                if (results.next()) {
                    ChannelManager chanMan = new ChannelManager(Yuri.getAPI().getTextChannelById(endPoint.toEndPointInfo().getChannelId()));
                    chanMan.setTopic(event.getTopic()).queue();
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onPrivateMessage(PrivateMessageEvent<PircBotX> event) {
    	String pmTo = event.getMessage().split(" ")[0].replace(":", "");
    	String pmMessage = event.getMessage().replace(pmTo + ": ", "<" + event.getUser().getNick() + "> ");
    	if (userToNick.containsKey(pmTo)) {
    		Member pmToUser = userToNick.get(pmTo);
    		pmToUser.getUser().getPrivateChannel().sendMessage(pmMessage).queue();
    	}
    }
    
    public void parseMessage(EndPoint endPoint, GenericMessageEvent event, Boolean checkStatus) {
    	if (endPoint != null) {
    		String chanName;
    		if (event instanceof MessageEvent) {
    			chanName = ((MessageEvent) event).getChannel().getName();
    		} else {
    			chanName = ((ActionEvent) event).getChannel().getName();
    		}
    		EndPointMessage message = EndPointMessage.createFromIrcEvent(event);
    		Pattern pattern = Pattern.compile("@[^\\s\"']+\\b+|@\"([^\"]*)\"|@'([^']*)'");
    		Matcher matcher = pattern.matcher(message.getMessage().replace("@status", ""));
    		while (matcher.find()) {
    			Member checkUser = userToNick.get(matcher.group(0).replace("@", "").replace("\"", ""));
    			System.out.println(matcher.group(0).replace("@", "") + " | " + matcher.group(0).replace("@", "").replace("\"", ""));
    			if (userToNick.containsKey(matcher.group(0).replace("@", "").replace("\"", ""))) {
    				if (checkStatus) {
    					event.getBot().sendIRC().message(chanName, "<Discord> " + checkUser.getEffectiveName() + " is currently " + checkUser.getOnlineStatus());
    				}
    				message.setMessage(Colors.removeColors(message.getMessage().replace(matcher.group(0).replace("@", ""), checkUser.getAsMention()).replace("@<", "<")));
    			}
    		}
    		if(event instanceof ActionEvent) {
    			message.setMessage("_" + message.getMessage() + "_");
    		}
    		endPoint.sendMessage(message);
    	}
    }
    
    @Override
    public void onMessage(MessageEvent<PircBotX> event)
    {
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
                users += "User: " + userToNick.get(currentKey).getUser().getName().replace("\n", "").replace("\r", "") + " | Guild: " + memberToGuild.get(userToNick.get(currentKey)).getName() + " | Status: " + userToNick.get(currentKey).getOnlineStatus() + "\r\n";
            }
            event.getBot().sendIRC().message(event.getChannel().getName(), "<Discord> " + PasteUtils.paste("Current Discord users:\r\n" + users));
        }
        
        //If this returns null, then this EndPoint isn't part of a bridge.
        parseMessage(endPoint, event, checkStatus);
    }
    
    @Override
    public void onAction(ActionEvent<PircBotX> event)
    {
        //Specific to the the Imaginescape IRC/Discord channel. Dumb minecraft server spits out an empty message that is really annoying.
        if (event.getUser().getNick().equals("IServer") && event.getMessage().equals("[Server]"))
            return;

        //If this returns null, then this EndPoint isn't part of a bridge.
        EndPoint endPoint = BridgeManager.getInstance().getOtherEndPoint(EndPointInfo.createFromIrcChannel(identifier, event.getChannel()));
        parseMessage(endPoint, event, false);
    }

    @Override
    public void onQuit(QuitEvent<PircBotX> event) {
        String nick = event.getUser().getNick();
        for (String channelName : Yuri.channelNicks.keySet()) {
            if (Yuri.channelNicks.get(channelName).contains(nick)) {
                PreparedStatement getChans = Database.getInstance().getStatement("getChan");
                try {
                    EndPoint endPoint = BridgeManager.getInstance().getOtherEndPoint(EndPointInfo.createFromIrcChannel(identifier, getChannel(channelName)));
                    getChans.setString(1, endPoint.toEndPointInfo().getChannelId());
                    ResultSet results = getChans.executeQuery();
                    if (results.next()) {
                        endPoint.sendMessage(event.getUser().getNick() + " has quit IRC (" + event.getReason() + ")");
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
    public void onPart(PartEvent<PircBotX> event) {
        //if (messages.containsValue(event.getUser().getNick())) {
        PreparedStatement getChans = Database.getInstance().getStatement("getChan");
        try {
            EndPoint endPoint = BridgeManager.getInstance().getOtherEndPoint(EndPointInfo.createFromIrcChannel(identifier, event.getChannel()));
            getChans.setString(1, endPoint.toEndPointInfo().getChannelId());
            ResultSet results = getChans.executeQuery();
            if (results.next()) {
                endPoint.sendMessage(event.getUser().getNick() + " has left " + event.getChannel().getName() + " on IRC (" + event.getReason() + ")");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        //}
        updateNickList(event.getChannel());
    }

    @Override
    public void onNickChange(NickChangeEvent<PircBotX> event) {
        String nick = event.getOldNick();
        for (String channelName : Yuri.channelNicks.keySet()) {
            if (Yuri.channelNicks.get(channelName).contains(nick)) {
                EndPoint endPoint = BridgeManager.getInstance().getOtherEndPoint(EndPointInfo.createFromIrcChannel(identifier, getChannel(channelName)));
                PreparedStatement getChans = Database.getInstance().getStatement("getChan");
                try {
                    getChans.setString(1, endPoint.toEndPointInfo().getChannelId());
                    ResultSet results = getChans.executeQuery();
                    if (results.next()) {
                        endPoint.sendMessage(nick + " is now known as " + event.getNewNick());
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
    public void onKick(KickEvent<PircBotX> event) {
        PreparedStatement getChans = Database.getInstance().getStatement("getChan");
        try {
            EndPoint endPoint = BridgeManager.getInstance().getOtherEndPoint(EndPointInfo.createFromIrcChannel(identifier, event.getChannel()));
            getChans.setString(1, endPoint.toEndPointInfo().getChannelId());
            ResultSet results = getChans.executeQuery();
            if (results.next()) {
                endPoint.sendMessage(event.getRecipient().getNick() + " has been kicked from " + event.getChannel().getName() + " on IRC by " + event.getUser().getNick() + " (" + event.getReason() + ")");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        updateNickList(event.getChannel());
    }

    @Override
    public void onJoin(JoinEvent<PircBotX> event) {
        if (event.getBot().getUserBot().equals(event.getUser())) {
        	EndPointManager.getInstance().createEndPoint(EndPointInfo.createFromIrcChannel(identifier, event.getChannel()));
        	EndPoint endPoint = BridgeManager.getInstance().getOtherEndPoint(EndPointInfo.createFromIrcChannel(identifier, event.getChannel()));
        	endPoint.sendMessage("Bridge Bot available");
       } else {
            EndPoint endPoint = BridgeManager.getInstance().getOtherEndPoint(EndPointInfo.createFromIrcChannel(identifier, event.getChannel()));
            if (endPoint != null) {
                PreparedStatement getChans = Database.getInstance().getStatement("getChan");
                try {
                    getChans.setString(1, endPoint.toEndPointInfo().getChannelId());
                    ResultSet results = getChans.executeQuery();
                    if (results.next()) {
                        endPoint.sendMessage(event.getUser().getNick() + " has joined " + event.getChannel().getName() + " on IRC");
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
    
    @Override
    public void onEvent(Event event) {
    	Boolean abort = false;
    	if (event instanceof MessageUpdateEvent) {
            MessageUpdateEvent e = (MessageUpdateEvent) event;
            if (pinnedMessages.containsValue(e.getMessage().getId())) {
            	return;
            }
            ((MessageUpdateEvent) event).getChannel().getPinnedMessages().queue(new Consumer<List<Message>>() {
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
            ((GuildMessageUpdateEvent) event).getChannel().getPinnedMessages().queue(new Consumer<List<Message>>() {
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
        }

        if (event instanceof PrivateMessageReceivedEvent) {
            PrivateMessageReceivedEvent e = (PrivateMessageReceivedEvent) event;
            if (event.getJDA().getSelfUser().getId().equals(e.getAuthor().getId()))
                return;
            String pmTo = e.getMessage().getContent().split(" ")[0].replace(":", "");
            String pmMessage = e.getMessage().getContent().replace(pmTo + ": ", "<" + e.getAuthor().getName() + ">" + " ");

            bot.sendIRC().message(pmTo, pmMessage);
        }

        if (event instanceof ReadyEvent) {
            for (Guild currGuild : event.getJDA().getGuilds()) {
                for (Member currMember : currGuild.getMembers()) {
                    String userNick;
                    userNick = currMember.getEffectiveName();
                    userToNick.put(userNick, currMember);
                    memberToGuild.put(currMember, currGuild);
                }
                for (TextChannel currChan : currGuild.getTextChannels()) {
                	currChan.getPinnedMessages().queue(new Consumer<List<Message>>() {

                    	@Override
                    	public void accept(List<Message> t) {
                    		ListIterator<Message> it = t.listIterator();
                    		while(it.hasNext()) {
                    			Message msg = it.next();
                    			pinnedMessages.put(currGuild, msg.getId());
                    		}
                    	} 
                    }
                    );
                }
            }
        }

        if (event instanceof GuildMemberNickChangeEvent) {
            GuildMemberNickChangeEvent e = (GuildMemberNickChangeEvent) event;
            Member currMember = e.getMember();
            String userNick;
            userNick = currMember.getEffectiveName();
            userToNick.put(userNick, currMember);
            memberToGuild.put(currMember, currMember.getGuild());
        }
        
        if (event instanceof GuildMemberJoinEvent) {
        	GuildMemberJoinEvent e = (GuildMemberJoinEvent) event;
        	memberToGuild.put(e.getMember(), e.getGuild());
        }

        if (event instanceof GuildMemberLeaveEvent) {
        	GuildMemberLeaveEvent e = (GuildMemberLeaveEvent) event;
        	memberToGuild.remove(e.getMember(), e.getGuild());
        }

        //We only deal with TextChannel Message events
        if (!(event instanceof GenericGuildMessageEvent))
            return;

        //Don't care about deleted messages or embeds.
        if (event instanceof GuildMessageDeleteEvent /*|| event instanceof GuildMessageEmbedEvent*/)
        	return;

        GenericGuildMessageEvent e = (GenericGuildMessageEvent) event;

        //Basically: If we are the ones that sent the message, don't send it to IRC.
        if (e.getAuthor().getId() == null){
            return;
        }
        if (event.getJDA().getSelfUser().getId().equals(e.getAuthor().getId()))
            return;

        //If this returns null, then this EndPoint isn't part of a bridge.
        EndPoint endPoint = BridgeManager.getInstance().getOtherEndPoint(EndPointInfo.createFromDiscordChannel(e.getChannel()));
        if (endPoint != null && !abort) {
            String userNick;
            userNick = e.getMember().getEffectiveName();
            userToNick.put(userNick, e.getMember());
            EndPointMessage message = EndPointMessage.createFromDiscordEvent(e);
            String parsedMessage = "";
            String nick;
            String tinyURL = "";
            if (!e.getMessage().getAttachments().isEmpty()) {
                for (Message.Attachment attach : e.getMessage().getAttachments()) {
                    //nick = AntiPing.antiPing(userNick);
                    tinyURL = makeTiny.getTinyURL(attach.getUrl());
                    parsedMessage += "<" + userNick + "> " + addSpace(removeUrl(message.getMessage())) + tinyURL;
                }
                parsedMessage.replace(tinyURL, "");
                endPoint.sendMessage(parsedMessage.toString());
            } else {
            	String messageString = message.getMessage();
            	final String regex = ".*```.*?\\n((?:.|\\n)*?)\\n```";
        		Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        		Matcher matcher = pattern.matcher(messageString);
        		while (matcher.find()) {
        		    for (int i = 1; i <= matcher.groupCount(); i++) {
        		    	messageString = messageString.replace(matcher.group(i), PasteUtils.paste(matcher.group(i), PasteUtils.Formats.NONE)).replace("```", "");
        		    }
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
}
