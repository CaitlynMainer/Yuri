package net.dv8tion.discord.bridge.endpoint.messages;

import org.pircbotx.PircBotX;
import org.pircbotx.hooks.events.ActionEvent;

import net.dv8tion.discord.bridge.endpoint.EndPointMessage;

public class IrcActionEndPointMessage extends EndPointMessage
{
    private ActionEvent<? extends PircBotX> ircEvent;
    private org.pircbotx.User ircUser;

    public IrcActionEndPointMessage(ActionEvent<? extends PircBotX> event)
    {
        super(event.getUser().getNick(), event.getMessage());
        this.ircEvent = event;
        this.ircUser = event.getUser();
    }

    public ActionEvent<? extends PircBotX> getIrcEvent()
    {
        return ircEvent;
    }

    public org.pircbotx.User getIrcUser()
    {
        return ircUser;
    }
}
