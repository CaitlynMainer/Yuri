package net.dv8tion.discord.commands;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

import net.dv8tion.discord.Permissions;
import net.dv8tion.discord.util.Database;
import net.dv8tion.jda.core.events.message.MessageReceivedEvent;

public class IgnoreUserCommand extends Command {
    private TreeMap<String, Command> commands;

    public IgnoreUserCommand()
    {
        commands = new TreeMap<>();
    }

    public Command registerCommand(Command command)
    {
        commands.put(command.getAliases().get(0), command);
        return command;
    }
	@Override
	public void onCommand(MessageReceivedEvent e, String[] args) {
        if (!Permissions.getPermissions().isOp(e.getAuthor()))
        {
            sendMessage(e, Permissions.OP_REQUIRED_MESSAGE);
            return;
        } else {
            if (args[0].equalsIgnoreCase(".ignore")) {
                try {
                    PreparedStatement addIgnore = Database.getInstance().getStatement("addIgnore");
                    addIgnore.setString(1,  args[1]);
                    addIgnore.executeUpdate();
                    sendMessage(e, "Now ignoring: " + args[1]);
                } catch (SQLException e1) {
                    e1.printStackTrace();
                }
            } else {
                try {
                    PreparedStatement delIgnore = Database.getInstance().getStatement("delIgnore");
                    delIgnore.setString(1,  args[1]);
                    delIgnore.executeUpdate();
                    sendMessage(e, "No longer ignoring: " + args[1]);
                } catch (SQLException e1) {
                    e1.printStackTrace();
                }
            }
        }
    }

	@Override
	public List<String> getAliases() {
		return Arrays.asList(".ignore", ".unignore");
	}

	@Override
	public String getDescription() {
		return "Ignores an IRC user so their messages never get sent to discord";
	}

	@Override
	public String getName() {
		return "Ignore User";
	}

	@Override
	public List<String> getUsageInstructions() {
		return Arrays.asList(".ignore IRCNick");
	}

}
