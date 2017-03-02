package net.dv8tion.discord.commands;

import net.dv8tion.discord.Permissions;
import net.dv8tion.discord.Yuri;
import net.dv8tion.discord.util.Database;
import net.dv8tion.jda.events.message.GenericMessageEvent;
import net.dv8tion.jda.events.message.MessageReceivedEvent;
import net.dv8tion.jda.events.message.guild.GenericGuildMessageEvent;
import net.dv8tion.jda.events.message.priv.GenericPrivateMessageEvent;
import net.dv8tion.jda.managers.AccountManager;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Created by Administrator on 3/2/2017.
 */
public class RelayMoreInfo extends Command {
    @Override
    public void onCommand(MessageReceivedEvent e, String[] args)
    {
        if (!Permissions.getPermissions().isOp(e.getAuthor()))
        {
            sendMessage(e, Permissions.OP_REQUIRED_MESSAGE);
            return;
        } else {
            if (args[1].equalsIgnoreCase("true")) {
                try {
                    PreparedStatement addChan = Database.getInstance().getStatement("addChan");
                    addChan.setString(1,  e.getChannel().getId());
                    addChan.executeUpdate();
                    sendMessage(e, "Enabled");
                } catch (SQLException e1) {
                    e1.printStackTrace();
                }
            } else {
                try {
                    PreparedStatement delChan = Database.getInstance().getStatement("delChan");
                    delChan.setString(1,  e.getChannel().getId());
                    delChan.executeUpdate();
                    sendMessage(e, "Disabled");
                } catch (SQLException e1) {
                    e1.printStackTrace();
                }
            }
        }
    }

    @Override
    public void onGenericMessage(GenericMessageEvent e)
    {
        //Don't care about Delete and Embed events. (both have null messages).
        if (e.getMessage() == null)
            return;

        if (e instanceof GenericGuildMessageEvent)
        {
            GenericGuildMessageEvent event = (GenericGuildMessageEvent) e;
            if (event.getGuild().getId().equals("107563502712954880"))  //Gaming Bunch Guild Id
                System.out.println((event.getMessage().isEdited() ? "# " : "") + "[#" + event.getChannel().getName() + "] <" + event.getAuthor().getUsername() + "> " + event.getMessage().getContent());
        }

        if (e instanceof GenericPrivateMessageEvent)
            System.out.println((e.getMessage().isEdited() ? "# " : "") + "[Private Message] <" + e.getAuthor().getUsername() + "> " + e.getMessage().getContent());
    }

    @Override
    public List<String> getAliases()
    {
        return Arrays.asList("!ircrelayevents");
    }

    @Override
    public String getDescription()
    {
        return "irc relay events";
    }

    @Override
    public String getName()
    {
        return "ircrelayevents";
    }

    @Override
    public List<String> getUsageInstructions()
    {
        return Collections.singletonList(
                "!ircrelayevents\n"
                        + "true/false enables / disables relaying of more IRC events like join/part/nick change");
    }
}
