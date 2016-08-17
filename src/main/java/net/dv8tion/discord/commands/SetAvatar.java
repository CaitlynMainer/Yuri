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
import net.dv8tion.discord.Yui;
import net.dv8tion.jda.MessageBuilder;
import net.dv8tion.jda.events.message.GenericMessageEvent;
import net.dv8tion.jda.events.message.MessageReceivedEvent;
import net.dv8tion.jda.events.message.guild.GenericGuildMessageEvent;
import net.dv8tion.jda.events.message.priv.GenericPrivateMessageEvent;
import net.dv8tion.jda.managers.AccountManager;
import net.dv8tion.jda.utils.AvatarUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SetAvatar extends Command
{

    @Override
    public void onCommand(MessageReceivedEvent e, String[] args)
    {
        if (!Permissions.getPermissions().isOp(e.getAuthor()))
        {
            sendMessage(e, Permissions.OP_REQUIRED_MESSAGE);
            return;
        }
        AccountManager accountManager = Yui.getAPI().getAccountManager();
        sendMessage(e, new MessageBuilder()
                .appendString("New avatar set!")
                .build());
        try {

            URLConnection connection = new URL(args[1]).openConnection();
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.95 Safari/537.11");
            connection.connect();

            //BufferedReader r  = new BufferedReader(new InputStreamReader(connection.getInputStream(), Charset.forName("UTF-8")));

            accountManager.setAvatar(AvatarUtil.getAvatar(connection.getInputStream()));
            accountManager.update();
        } catch (IOException e1) {
            e1.printStackTrace();
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
        return Arrays.asList("!setavatar", "!newavatar");
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
