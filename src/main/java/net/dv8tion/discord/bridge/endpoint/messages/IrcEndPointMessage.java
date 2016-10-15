package net.dv8tion.discord.bridge.endpoint.messages;

import org.pircbotx.Colors;
import org.pircbotx.PircBotX;
import org.pircbotx.hooks.events.MessageEvent;

import net.dv8tion.discord.bridge.endpoint.EndPointMessage;

public class IrcEndPointMessage extends EndPointMessage
{
    private MessageEvent<? extends PircBotX> ircEvent;
    private org.pircbotx.User ircUser;

    public IrcEndPointMessage(MessageEvent<? extends PircBotX> event)
    {
        super(event.getUser().getNick(), "", Colors.removeColors(event.getMessage()));
        this.ircEvent = event;
        this.ircUser = event.getUser();
    }

    public MessageEvent<? extends PircBotX> getIrcEvent()
    {
        return ircEvent;
    }

    public org.pircbotx.User getIrcUser()
    {
        return ircUser;
    }
}
