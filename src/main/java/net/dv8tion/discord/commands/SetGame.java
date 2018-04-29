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
import net.dv8tion.jda.core.events.message.*;
import net.dv8tion.discord.Yuri;
import net.dv8tion.jda.core.MessageBuilder;
import net.dv8tion.jda.core.entities.Game;
import net.dv8tion.jda.core.entities.Icon;
import net.dv8tion.jda.core.entities.SelfUser;
import net.dv8tion.jda.core.managers.AccountManager;
import net.dv8tion.jda.core.managers.AccountManagerUpdatable;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

public class SetGame extends Command
{

    private TreeMap<String, Command> commands;

    public SetGame()
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
        String game = "";
       if (!Permissions.getPermissions().isOp(e.getAuthor()))
        {
            sendMessage(e, Permissions.OP_REQUIRED_MESSAGE);
            return;
        }
        for (int i=1;i < args.length;i++)
        {
            game += " " + args[i];
        }
        game = game.trim();
        Yuri.getAPI().getPresence().setGame(Game.playing(game));
    }

    @Override
    public List<String> getAliases()
    {
        return Arrays.asList("!setgame", "@setgame");
    }

    @Override
    public String getDescription()
    {
        return "Sets the bot's game.";
    }

    @Override
    public String getName()
    {
        return "Set Game";
    }

    @Override
    public List<String> getUsageInstructions()
    {
        return Collections.singletonList(
                "!setgame\n"
                + "Changes the bot's game to the provided string");
    }
}
