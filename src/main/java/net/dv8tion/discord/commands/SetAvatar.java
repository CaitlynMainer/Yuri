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

//Author Caitlyn Mainer (Michiyo/Mimiru)
package net.dv8tion.discord.commands;

import net.dv8tion.discord.Permissions;
import net.dv8tion.jda.api.events.message.*;
import net.dv8tion.discord.Yuri;
import net.dv8tion.jda.api.MessageBuilder;
import net.dv8tion.jda.api.entities.Icon;
import net.dv8tion.jda.api.entities.SelfUser;
import net.dv8tion.jda.api.managers.AccountManager;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

public class SetAvatar extends Command
{
	private TreeMap<String, Command> commands;

    public SetAvatar()
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
        }
        AccountManager accountManager = Yuri.getAPI().getSelfUser().getManager();

        try {

            URLConnection connection = new URL(args[1]).openConnection();
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.95 Safari/537.11");
            connection.connect();

            //BufferedReader r  = new BufferedReader(new InputStreamReader(connection.getInputStream(), Charset.forName("UTF-8")));

            Icon avatar = Icon.from(connection.getInputStream());
			accountManager.setAvatar(avatar).queue();
            sendMessage(e, new MessageBuilder()
                .append("New avatar set!")
                .build());
        } catch (Exception e1) {
            sendMessage(e, new MessageBuilder()
                .append("Error: ")
                    .append(e1.getCause().toString())
                .build());
            e1.printStackTrace();
        }
    }

    @Override
    public List<String> getAliases()
    {
        return Arrays.asList("!setavatar", "!newavatar", "@setavatar");
    }

    @Override
    public String getDescription()
    {
        return "Sets the bot's Avatar.";
    }

    @Override
    public String getName()
    {
        return "Set Avatar";
    }

    @Override
    public List<String> getUsageInstructions()
    {
        return Collections.singletonList(
                "!setavatar\n"
                + "Changes the bot's avatar to the image provided in the URL.");
    }
}
