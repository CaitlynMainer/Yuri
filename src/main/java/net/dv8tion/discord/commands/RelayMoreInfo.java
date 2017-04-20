package net.dv8tion.discord.commands;

import net.dv8tion.discord.Permissions;
import net.dv8tion.jda.core.events.message.*;
import net.dv8tion.discord.util.Database;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

/**
 * Created by Administrator on 3/2/2017.
 */
public class RelayMoreInfo extends Command {
    private TreeMap<String, Command> commands;

    public RelayMoreInfo()
    {
        commands = new TreeMap<>();
    }

    public Command registerCommand(Command command)
    {
        commands.put(command.getAliases().get(0), command);
        return command;
    }
    
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
    public List<String> getAliases()
    {
        return Arrays.asList("!ircrelayevents", "@ircrelayevents");
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
