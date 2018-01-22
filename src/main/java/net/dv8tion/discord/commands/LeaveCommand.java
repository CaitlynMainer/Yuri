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
package net.dv8tion.discord.commands;

import net.dv8tion.discord.Permissions;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.entities.ChannelType;
import net.dv8tion.jda.core.entities.Guild;
import net.dv8tion.jda.core.events.message.MessageReceivedEvent;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class LeaveCommand extends Command
{
    @Override
    public void onCommand(MessageReceivedEvent e, String[] args)
    {
        if (!Permissions.getPermissions().isOp(e.getAuthor()))
        {
            sendMessage(e, "Sorry, this command is OP only!");
            return;
        }
        if (args.length >= 2) {
        	JDA jda = e.getJDA();
        	jda.getGuildById(args[1]).leave().queue();
        } else {
        	sendMessage(e, "Currently joined guilds:");
        		for (Iterator<Guild> it = e.getJDA().getGuilds().iterator(); it.hasNext(); ) {
        			if (it.hasNext()) {
        		        Guild guild = it.next();
        		        sendMessage(e, "Name: " + guild.getName() + " ID: " + guild.getId());
        		    }
        		}
        }
        
    }

    @Override
    public List<String> getAliases()
    {
        return Collections.singletonList(".leave");
    }

    @Override
    public String getDescription()
    {
        return "leaves the guild via the guild id";
    }

    @Override
    public String getName()
    {
        return "Leave";
    }

    @Override
    public List<String> getUsageInstructions()
    {
        return Collections.singletonList(
                "!leave guildid\n"
                + "leaves the guild");
    }
}
