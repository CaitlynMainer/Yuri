const fs = require('fs');
const irc = require('irc-framework');
const {
    Client,
    GatewayIntentBits,
    WebhookClient,
    MessageType
} = require('discord.js');
const express = require('express');
const app = express();
const server = require('http').createServer(app);
const path = require('path');
const crypto = require('crypto');
const crc32 = require('crc32');
const im = require('imagemagick');
const axios = require('axios');
const url = require('url');
const mime = require('mime-types');
const config = JSON.parse(fs.readFileSync('config.json'));
const {
    exec
} = require('child_process');
const glob = require('glob');
const webhookCache = {}; // Create a cache object to store webhooks
const ircConfig = config.irc;
const discordToken = config.discord.token;
let channelMappings = config.channelMappings;
const ircUserChannelMapping = {}; // Initialize an empty mapping for users and their channels

app.use((req, res, next) => {
    res.setHeader('Pragma', 'no-cache');
    res.setHeader('Cache-Control', 'no-cache, no-store, must-revalidate');
    res.setHeader('Expires', '0');
    next();
});
// Web server to serve saved Discord embeds locally
const savedEmbedsPath = path.join(__dirname, 'saved_embeds');
app.use('/saved_embeds', express.static(savedEmbedsPath));
server.listen(config.webPort, () => {
    console.log(`Web server running on port ${config.webPort}`);
});
const ircClient = new irc.Client();
ircClient.connect({
    host: ircConfig.server,
    port: ircConfig.port,
    nick: ircConfig.nick,
    username: ircConfig.identNick,
    password: ircConfig.identPass,

    sasl_disconnect_on_fail: false,
    account: {
        account: ircConfig.identNick,
        password: ircConfig.identPass,
    },

    channels: ircConfig.channels
});
const discordClient = new Client({
    intents: [
        GatewayIntentBits.Guilds, // GUILD_CREATE, GUILD_DELETE
        GatewayIntentBits.GuildMembers,
        GatewayIntentBits.GuildMessages, // MESSAGE_CREATE, MESSAGE_UPDATE, MESSAGE_DELETE
        GatewayIntentBits.MessageContent, // GUILD_MESSAGE_TYPING
        // Add other required intents as needed
    ],
});
discordClient.login(discordToken);
discordClient.on('ready', async () => {
    console.log(`Logged in as ${discordClient.user.tag}`);
    // Access guilds directly from the cache property
    const guilds = discordClient.guilds.cache;
    // Iterate through guilds and fetch members to cache them
    guilds.forEach(async (guild) => {
        try {
            // Fetch members for the current guild and populate the cache
            await guild.members.fetch();
            // Access members from the cache
            const members = (await guild.members.fetch()).map(member => member.id);
        } catch (error) {
            console.error(`Error fetching members for guild ${guild.name}:`, error);
        }
    });
});
ircClient.on('registered', () => {
    // Identify with NickServ after connecting to the IRC server
    ircClient.say('NickServ', `IDENTIFY ${ircConfig.identNick} ${ircConfig.identPass}`);
    // Auto-join IRC channels based on keys in channelMappings
    const tempChannelMappings = {};

    Object.keys(channelMappings).forEach(ircChannel => {
        const lowercaseKey = ircChannel.toLowerCase();
        tempChannelMappings[lowercaseKey] = channelMappings[ircChannel];
        console.log(`Joining: ${lowercaseKey}`);
        ircClient.join(ircChannel);
    });
    
    // Copy modified data back to the original channelMappings object
    Object.assign(channelMappings, tempChannelMappings);
    console.log(channelMappings);
    console.log('Connected to IRC server, identified with NickServ, and joined specified channels.');
});


ircClient.on('message', async (event) => {    
    let ircMessage = removeColorCodes(event.message);
    ircMessage = ircToDiscordMarkdown(ircMessage);
    const sender = event.nick;
    //const channelMappings = config.channelMappings; // Ensure channelMappings is accessible here
    //console.log('Received IRC Event:', event); // Log the entire event object
    if(ircMessage.startsWith('!adduser')) {
        // Command to add registered IRC user
        const [, nickname] = ircMessage.split(' ');
        addRegisteredIRCUser(nickname);
        ircClient.say(event.target, `User ${nickname} has been added to the registered users list.`);
        saveConfig(); // Save config after modifying registered users list
        return;
    }
    if(ircMessage.startsWith('!deluser')) {
        // Command to remove registered IRC user
        const [, nickname] = ircMessage.split(' ');
        if(config.irc.registeredUsers.includes(nickname)) {
            config.irc.registeredUsers = config.irc.registeredUsers.filter(user => user !== nickname);
            saveConfig(); // Save config after modifying registered users list
            ircClient.say(event.target, `User ${nickname} has been removed from the registered users list.`);
        } else {
            ircClient.say(event.target, `User ${nickname} is not in the registered users list.`);
        }
        return;
    }
    if(ircMessage.startsWith('!setmyavatar')) {
        const [, avatarUrl] = ircMessage.split(' ');
        const nick = sender;
        const avatarFileName = `${nick}.*`;
        const avatarPath = path.join(__dirname, 'avatars');
        const matchingFiles = glob.sync(path.join(avatarPath, avatarFileName));
    
        // Delete existing avatar files matching the nickname
        matchingFiles.forEach(file => {
            fs.unlinkSync(file);
            console.log(`Deleted existing avatar file: ${file}`);
        });
        try {
            // Validate if the remote URL is an image
            // console.log('Fetching avatar metadata...');
            const response = await axios.head(avatarUrl);
            // console.log('Avatar metadata received:', response.headers);
            const contentType = response.headers['content-type'];
            if(!contentType.startsWith('image')) {
                ircClient.say(event.target, 'Invalid image URL');
                //console.log('Invalid image URL:', avatarUrl);
                return;
            }
            // Fetch the image data
            //console.log('Downloading avatar image...');
            const imageResponse = await axios.get(avatarUrl, {
                responseType: 'arraybuffer'
            });
            //console.log('Avatar image downloaded');
            const fileExtension = mime.extension(contentType);
            const filePath = path.join(__dirname, 'avatars', `${nick}.${fileExtension}`);
            // Save the image data to a file
            //console.log('Saving avatar image...');
            fs.writeFileSync(filePath, imageResponse.data);
            //console.log('Avatar image saved:', filePath);
            ircClient.say(event.target, 'Avatar downloaded and set successfully');
        } catch (error) {
            //console.error('Error:', error);
            ircClient.say(event.target, 'Error downloading or saving the avatar');
        }
    }
    // IRC command handler
    if(ircMessage.startsWith('!link')) {
        if(config.irc.registeredUsers.includes(sender)) {
            const [, ircChannel, discordChannelID, showMoreInfo = 'false'] = ircMessage.split(' ');
            // Update channel mapping in config
            channelMappings[ircChannel.toLowerCase()] = {
                "discordChannelID": discordChannelID,
                "showMoreInfo": showMoreInfo.toLowerCase() === 'true'
            };
            ircClient.join(ircChannel)
            // Save updated mappings to config.json
            saveConfig();
            ircClient.say(event.target, `Linked Discord channel ${discordChannelID} to IRC channel ${ircChannel} with showMoreInfo set to ${showMoreInfo}`);
        } else {
            ircClient.say(event.target, `Permission denied`);
        }
        return;
    }
    if (ircMessage.startsWith('!unlink')) {
        if (config.irc.registeredUsers.includes(sender)) {
            const [, ircChannel] = ircMessage.split(' ');
    
            // Check if the channel exists in the config
            if (channelMappings.hasOwnProperty(ircChannel.toLowerCase())) {
                // Remove channel mapping from config
                delete channelMappings[ircChannel.toLowerCase()];
                ircClient.part(ircChannel); // Leave the IRC channel
                // Save updated mappings to config.json
                saveConfig();
                ircClient.say(event.target, `Unlinked IRC channel ${ircChannel}`);
            } else {
                ircClient.say(event.target, `No mapping found for IRC channel ${ircChannel}`);
            }
        } else {
            ircClient.say(event.target, `Permission denied`);
        }
        return;
    }
    
    //!update
    if(ircMessage.startsWith('!update')) {
        if(config.irc.registeredUsers.includes(sender)) {
            // Run git pull command
            exec('git pull', (error, stdout, stderr) => {
                if(error) {
                    console.error(`Error during git pull: ${error.message}`);
                    return;
                }
                // Check if there were any changes pulled
                if(stdout.includes('Already up to date.')) {
                    exec('git rev-parse HEAD', (error, stdout, stderr) => {
                        if(!error) {
                            const commitHash = stdout.trim();
                            ircClient.say(event.target, `Bot is already up to date (Commit: ${commitHash}).`);
                        } else {
                            console.error(`Error getting commit hash: ${error.message}`);
                        }
                    });
                } else {
                    ircClient.say(event.target, 'Bot has been updated. Relaunching...');
                    // Relaunch the bot
                    process.exit(0);
                }
            });
        } else {
            ircClient.say(event.target, `Permission denied`);
        }
    }
    // Discord command handler
    if(ircMessage.startsWith('!showmoreinfo')) {
        if(config.irc.registeredUsers.includes(sender)) {
            const [, showMoreInfoArg] = ircMessage.split(' ');
            if(showMoreInfoArg !== undefined && (showMoreInfoArg.toLowerCase() === 'true' || showMoreInfoArg.toLowerCase() === 'false')) {
                const showMoreInfo = showMoreInfoArg.toLowerCase() === 'true';
                const discordChannelID = message.channel.id;
                // Find the IRC channel ID based on the current Discord channel
                const ircChannel = Object.keys(channelMappings).find(ircChannel => {
                    return config.channelMappings[ircChannel.toLowerCase()].discordChannelID === discordChannelID;
                });
                if(ircChannel) {
                    // Update showMoreInfo property for the IRC channel
                    channelMappings[ircChannel.toLowerCase()].showMoreInfo = showMoreInfo;
                    // Save updated mappings to config.json
                    saveConfig();
                    ircClient.say(event.target, `Set showMoreInfo to ${showMoreInfo}`);
                } else {
                    ircClient.say(event.target, "Error: Unable to find the corresponding IRC channel for the current Discord channel.");
                }
            } else {
                ircClient.say(event.target, "Invalid argument. Please use `true` or `false` after the command.");
            }
        } else {
            ircClient.say(event.target, `Permission denied`);
        }
        return;
    }
    const mappedChannel = channelMappings[event.target.toLowerCase()];
    // Regular expression to match IRC mentions
    const ircMentionRegex = /@(\w+)/g;
    
    // Replace IRC mentions with corresponding Discord mentions in the message
    ircMessage = ircMessage.replace(ircMentionRegex, (match, username) => {
        // Get the channel from the channel ID
        const channel = discordClient.channels.cache.get(mappedChannel.discordChannelID);
    
        // Check if the channel is a guild (server) channel
        if (channel.guild) {
            // Get the guild (server) ID
            const guildId = channel.guild.id;
    
            const discordUser = discordClient.users.cache.find(user => {
                const normalizedUsername = username.toLowerCase();
                return (
                    // Match by account name
                    user.username.toLowerCase() === normalizedUsername ||
                    // Match by server nickname
                    (user.nickname && user.nickname.toLowerCase() === normalizedUsername) ||
                    // Match by account tag
                    user.tag.toLowerCase() === `${normalizedUsername}#${user.discriminator}`
                ) && (
                    // Check if the user is a member of the same guild
                    discordClient.guilds.cache.get(guildId).members.cache.has(user.id)
                );
            });
    
            if (discordUser) {
                // Convert IRC mention to Discord mention format
                return `<@${discordUser.id}>`; // Replace IRC mention with Discord mention in the message
            }
        }
    
        return match; // If no corresponding Discord user found or if the channel is not a guild channel, keep the original mention in the message
    });
    //console.log(event.target.toLowerCase());

    if(mappedChannel) {
        const mappedDiscordChannelID = mappedChannel.discordChannelID;
        const showMoreInfo = mappedChannel.showMoreInfo;
        const discordChannel = discordClient.channels.cache.get(mappedDiscordChannelID);
        // Regular user message, send it to Discord
        const yuriWebhook = await getWebhook(discordChannel);
        sendMessageToDiscord(yuriWebhook, sender, ircMessage)
    } else {
        //console.error(`No mapped Discord channel found for IRC channel: ${event.target.toLowerCase()}`);
    }
});
ircClient.on('wholist', (event) => {
    if(!ircUserChannelMapping[event.target]) {
        ircUserChannelMapping[event.target] = [];
    }
    event.users.forEach((user) => {
        ircUserChannelMapping[event.target].push(user.nick);
    });
});
// Join event handler
ircClient.on('join', async (event) => {
    //console.log(event.nick, ircConfig.nick);
    if(event.nick == ircConfig.nick) {
        ircClient.who(event.channel);
        return;
    }
    // Update the ircUserChannelMapping for the user's join event
    const ircChannel = event.channel;
    const ircUser = event.nick;
    if(!ircUserChannelMapping[ircChannel.toLowerCase()]) {
        ircUserChannelMapping[ircChannel.toLowerCase()].push(ircUser);
    }
    ircUserChannelMapping[ircChannel].push(ircUser);
    console.log(event);
    const mappedChannel = channelMappings[event.channel.toLowerCase()];
    if(mappedChannel && mappedChannel.showMoreInfo) {
        const mappedDiscordChannelID = mappedChannel.discordChannelID;
        const showMoreInfo = mappedChannel.showMoreInfo;
        const discordChannel = discordClient.channels.cache.get(mappedDiscordChannelID);
        console.log(event.channel, mappedDiscordChannelID, showMoreInfo);
        // Regular user message, send it to Discord
        const yuriWebhook = await getWebhook(discordChannel);
        sendMessageToDiscord(yuriWebhook, ircConfig.nick, `${event.nick}@${event.hostname} Joined ${event.channel} On IRC`)
    }
});
// Part event handler
ircClient.on('part', async (event) => {
    if(event.nick === ircConfig.nick) {
        return;
    }
    const ircChannel = event.channel;
    const ircUser = event.nick;
    // Check if the user is in the channel
    if(ircUserChannelMapping[ircChannel] && ircUserChannelMapping[ircChannel].includes(ircUser)) {
        // Remove the user from the channel in ircUserChannelMapping
        ircUserChannelMapping[ircChannel] = ircUserChannelMapping[ircChannel].filter(user => user !== ircUser);
    }
    // Handle Discord relay logic here if needed
    const mappedChannel = channelMappings[ircChannel.toLowerCase()];
    if(mappedChannel && mappedChannel.showMoreInfo) {
        const mappedDiscordChannelID = mappedChannel.discordChannelID;
        const discordChannel = discordClient.channels.cache.get(mappedDiscordChannelID);
        // Regular user message, send it to Discord
        const yuriWebhook = await getWebhook(discordChannel);
        sendMessageToDiscord(yuriWebhook, ircConfig.nick, `${ircUser}@${event.hostname} has left ${ircChannel} (${event.message || 'No reason provided'})`);
    }
});
// Kick event handler
ircClient.on('kick', async (event) => {
    if(event.nick === ircConfig.nick) {
        return;
    }
    const ircChannel = event.channel;
    const ircUser = event.nick;
    const kickedUser = event.kicked;
    // Check if the user is in the channel
    if(ircUserChannelMapping[ircChannel] && ircUserChannelMapping[ircChannel].includes(kickedUser)) {
        // Remove the user from the channel in ircUserChannelMapping
        ircUserChannelMapping[ircChannel] = ircUserChannelMapping[ircChannel].filter(user => user !== kickedUser);
    }
    // Handle Discord relay logic here if needed
    const mappedChannel = channelMappings[ircChannel.toLowerCase()];
    if(mappedChannel && mappedChannel.showMoreInfo) {
        const mappedDiscordChannelID = mappedChannel.discordChannelID;
        const discordChannel = discordClient.channels.cache.get(mappedDiscordChannelID);
        // Regular user message, send it to Discord
        const yuriWebhook = await getWebhook(discordChannel);
        sendMessageToDiscord(yuriWebhook, ircConfig.nick, `${kickedUser} was kicked from ${ircChannel} by ${ircUser}: (${event.message || 'No reason provided'})`);
    }
});
// Quit event handler
ircClient.on('quit', async (event) => {
    const ircUser = event.nick;
    const quitMessage = event.message || 'No reason provided';
    // Iterate through all mapped channels
    Object.keys(ircUserChannelMapping).forEach(async channel => {
        // Check if the quitting user is in the current channel
        if(ircUserChannelMapping[channel].includes(ircUser)) {
            // Remove the user from the channel
            ircUserChannelMapping[channel] = ircUserChannelMapping[channel].filter(user => user !== ircUser);
            // Handle Discord relay logic here if needed
            const mappedChannel = channelMappings[channel.toLowerCase()];
            if(mappedChannel && mappedChannel.showMoreInfo) {
                const mappedDiscordChannelID = mappedChannel.discordChannelID;
                const discordChannel = discordClient.channels.cache.get(mappedDiscordChannelID);
                const yuriWebhook = await getWebhook(discordChannel);
                sendMessageToDiscord(yuriWebhook, ircConfig.nick, `${ircUser}@${event.hostname} has quit IRC (${quitMessage}) in ${channel}`);
            }
        }
    });
});
// Nick change event handler
ircClient.on('nick', async (event) => {
    const oldNick = event.nick;
    const newNick = event.new_nick;
    const channel = event.channel;
    // Iterate through all channels the old nickname is in
    Object.keys(ircUserChannelMapping).forEach(async channel => {
        if(ircUserChannelMapping[channel].includes(oldNick)) {
            // Update the old nickname to the new nickname in the channel
            ircUserChannelMapping[channel] = ircUserChannelMapping[channel].map(user =>
                user === oldNick ? newNick : user
            );
            // Process the channel as required (send message to Discord, etc.)
            const mappedChannel = channelMappings[channel.toLowerCase()];
            if(mappedChannel && mappedChannel.showMoreInfo) {
                const mappedDiscordChannelID = mappedChannel.discordChannelID;
                const discordChannel = discordClient.channels.cache.get(mappedDiscordChannelID);
                // Regular user message, send it to Discord
                const yuriWebhook = await getWebhook(discordChannel);
                sendMessageToDiscord(yuriWebhook, ircConfig.nick, `${oldNick} is now known as ${newNick}`);
            }
        }
    });
});

// discordClient.on('raw', packet => {
//     // 'packet' contains the raw data from the WebSocket connection
//     // You can parse and handle different events here
//     if (!packet.t) return; // Ignore heartbeats and other non-event packets
//     const eventName = packet.t;
//     const eventData = packet.d;
  
//     // Log the event name and data
//     console.log(`Received event: ${eventName}`);
//     console.log('Event data:', eventData);
//   });

//We got a discord edit.. fml
discordClient.on('messageUpdate', async (oldMessage, newMessage) => {
    const oldMessageContent = oldMessage.content;
    const newMessageContent = newMessage.content;

    console.log("oldMessageContent" ,oldMessage.content)
    console.log("newMessageContent" ,newMessage.content)

    const mappedIRCChannel = Object.keys(channelMappings).find(
        (key) => channelMappings[key.toLowerCase()]?.discordChannelID === oldMessage.channel.id
    );
    if(mappedIRCChannel) {

        if(oldMessage.webhookId) {
            // Fetch webhooks from the channel
            const yuriWebhook = await getWebhook(oldMessage.channel);
            if(yuriWebhook && oldMessage.webhookId === yuriWebhook.id) {
                // If it's from the yuri webhook, do not relay back to IRC
                //console.log('Message from yuri webhook, not relaying to IRC.');
                return;
            }
        }
        // Get the sender's nickname on the server
        let senderNickname = oldMessage.member ? oldMessage.member.nickname : null;
        // If the sender doesn't have a server nickname, use their account nickname
        if(!senderNickname) {
            senderNickname = oldMessage.member.user.globalName;
        }
        // If the sender doesn't have an account nickname, use their account name
        if(!senderNickname) {
            senderNickname = oldMessage.author.tag;
        }
        // Ignore messages sent by the bot itself
        if(oldMessage.author.id === discordClient.user.id) {
            return;
        }

        var theDiff = lineDiff(oldMessageContent, newMessageContent);

        if (theDiff !== null) {
            //ircClient.say('#channel', theDiff); // Replace '#channel' with the appropriate IRC channel
            
            ircClient.say(mappedIRCChannel, `<${antiPing(senderNickname)}> ${theDiff}`);
        }
    }
 })

//We got a discord messagem, so we need to make it look pretty in IRC.
discordClient.on('messageCreate', async (message) => {
    const mappedIRCChannel = Object.keys(channelMappings).find(
        (key) => channelMappings[key.toLowerCase()]?.discordChannelID === message.channel.id
    );
    if(mappedIRCChannel) {

        let discordMessage = discordMarkdownToIRC(message.cleanContent);
        const sender = message.author.username;
        // Check if the message is from a webhook
        if(message.webhookId) {
            // Fetch webhooks from the channel
            const yuriWebhook = await getWebhook(message.channel);
            if(yuriWebhook && message.webhookId === yuriWebhook.id) {
                // If it's from the yuri webhook, do not relay back to IRC
                //console.log('Message from yuri webhook, not relaying to IRC.');
                return;
            }
        }
        // Get the sender's nickname on the server
        let senderNickname = message.member ? message.member.nickname : null;
        // If the sender doesn't have a server nickname, use their account nickname
        if (!senderNickname) {
            // Check if the message is sent by a webhook
            if (message.webhookId) {
                // Use the webhook's name
                senderNickname = message.author.username;
            } else {
                // If the sender doesn't have a server nickname, use their account nickname
                senderNickname = message.member ? message.member.user.globalName : null;
            }
        }
        // If the sender doesn't have an account nickname, use their account name
        if(!senderNickname) {
            senderNickname = message.author.tag;
        }
        // Ignore messages sent by the bot itself
        if(message.author.id === discordClient.user.id) {
            return;
        }
        if (discordMessage.startsWith('!adduser')) {
            if (config.discord.allowedUsers.includes(message.author.id)) {
                const [, userId] = discordMessage.split(' ');
        
                // Validate that a userId is provided and is numeric
                if (!userId || !/^\d+$/.test(userId)) {
                    message.channel.send(
                        `Usage: !adduser UserID\n` +
                        `- UserID should be a numeric Discord user ID.`
                    );
                    return;
                }
        
                addAllowedDiscordUser(userId);
                message.channel.send(`User ${userId} has been added to the allowed users list.`);
            } else {
                message.channel.send(`Permission denied`);
            }
            return;
        }
        
        if (discordMessage.startsWith('!deluser')) {
            if (config.discord.allowedUsers.includes(message.author.id)) {
                const [, userId] = discordMessage.split(' ');
        
                // Validate that a userId is provided and is numeric
                if (!userId || !/^\d+$/.test(userId)) {
                    message.channel.send(
                        `Usage: !deluser UserID\n` +
                        `- UserID should be a numeric Discord user ID.`
                    );
                    return;
                }
        
                if (config.discord.allowedUsers.includes(userId)) {
                    config.discord.allowedUsers = config.discord.allowedUsers.filter(user => user !== userId);
                    saveConfig();
                    message.channel.send(`User ${userId} has been removed from the allowed users list.`);
                } else {
                    message.channel.send(`User ${userId} is not in the allowed users list.`);
                }
            } else {
                message.channel.send(`Permission denied`);
            }
            return;
        }
        
        // Discord command handler
        if (discordMessage.startsWith('!link')) {
            if (config.discord.allowedUsers.includes(message.author.id)) {
                const [, discordChannelID, ircChannel, showMoreInfo = 'false'] = discordMessage.split(' ');
        
                // Validate arguments
                if (!/^\d+$/.test(discordChannelID) || !ircChannel.startsWith('#')) {
                    message.channel.send(
                        `Usage: !link DiscordChannelID #IRCChannel [showMoreInfo]\n` +
                        `- DiscordChannelID should be numeric only.\n` +
                        `- #IRCChannel should include the hash (#) symbol.\n` +
                        `- showMoreInfo is optional and should be 'true' or 'false'.`
                    );
                    return;
                }
        
                // Update channel mapping in config
                channelMappings[ircChannel.toLowerCase()] = {
                    "discordChannelID": discordChannelID,
                    "showMoreInfo": showMoreInfo.toLowerCase() === 'true'
                };
        
                ircClient.join(ircChannel);
                // Save updated mappings to config.json
                saveConfig();
        
                message.channel.send(`Linked Discord channel ${discordChannelID} to IRC channel ${ircChannel} with showMoreInfo set to ${showMoreInfo}`);
            } else {
                message.channel.send(`Permission denied`);
            }
            return;
        }
        //!update
        if(message.content.startsWith('!update')) {
            if(config.discord.allowedUsers.includes(message.author.id)) {
                // Run git pull command
                exec('git pull', (error, stdout, stderr) => {
                    if(error) {
                        console.error(`Error during git pull: ${error.message}`);
                        return;
                    }
                    // Check if there were any changes pulled
                    if(stdout.includes('Already up to date.')) {
                        exec('git rev-parse HEAD', (error, stdout, stderr) => {
                            if(!error) {
                                const commitHash = stdout.trim();
                                message.channel.send(`Bot is already up to date (Commit: ${commitHash}).`);
                            } else {
                                console.error(`Error getting commit hash: ${error.message}`);
                            }
                        });
                    } else {
                        message.channel.send('Bot has been updated. Relaunching...');
                        // Relaunch the bot
                        process.exit(0);
                    }
                });
            } else {
                message.channel.send(`Permission denied`);
            }
        }
        // Discord command handler
        if(discordMessage.startsWith('!showmoreinfo')) {
            if(config.discord.allowedUsers.includes(message.author.id)) {
                const [, showMoreInfoArg] = discordMessage.split(' ');
                if(showMoreInfoArg !== undefined && (showMoreInfoArg.toLowerCase() === 'true' || showMoreInfoArg.toLowerCase() === 'false')) {
                    const showMoreInfo = showMoreInfoArg.toLowerCase() === 'true';
                    const discordChannelID = message.channel.id;
                    // Find the IRC channel ID based on the current Discord channel
                    const ircChannel = Object.keys(channelMappings).find(ircChannel => {
                        return config.channelMappings[ircChannel.toLowerCase()].discordChannelID === discordChannelID;
                    });
                    if(ircChannel) {
                        // Update showMoreInfo property for the IRC channel
                        channelMappings[ircChannel.toLowerCase()].showMoreInfo = showMoreInfo;
                        // Save updated mappings to config.json
                        saveConfig();
                        message.channel.send(`Set showMoreInfo to ${showMoreInfo}`);
                    } else {
                        message.channel.send("Error: Unable to find the corresponding IRC channel for the current Discord channel.");
                    }
                } else {
                    message.channel.send("Invalid argument. Please use `true` or `false` after the command.");
                }
            } else {
                message.channel.send(`Permission denied`);
            }
            return;
        }
        const isCodeBlock = /^```[\s\S]*```$/.test(discordMessage);
        const hasMoreThan3NewLines = discordMessage.split('\n').length > 3;
        if(isCodeBlock || hasMoreThan3NewLines) {
            const hastebinLink = await uploadToHastebin(discordMessage);
            if(hastebinLink) {
                // Replace the message with the Hastebin link
                discordMessage = hastebinLink;
            } else {
                // Handle error uploading to Hastebin
                discordMessage = 'Error uploading to Hastebin. Please try again later.';
            }
        }
        if(message.embeds.length > 0) {
            // Iterate through the embeds and extract URLs
            message.embeds.forEach(embed => {
                const embedURL = embed.url;
                console.log('Embed URL:', embedURL);
                // Process the embed URL as needed
            });
        }
        if(message.attachments.size > 0) {
            // Iterate through the attachments and extract URLs
            for(const attachment of message.attachments.values()) {
                const attachmentURL = attachment.url;
                //console.log('Attachment URL:', attachmentURL);
                // Remove query parameters from the attachment URL
                const parsedUrl = url.parse(attachmentURL);
                const newAttachmentURL = `${parsedUrl.protocol}//${parsedUrl.host}${parsedUrl.pathname}`;
                //console.log('Modified Attachment URL:', newAttachmentURL);
                try {
                    const newFilePath = await downloadAndSaveFile(attachmentURL, 'saved_embeds');
                    if(newFilePath) {
                        //console.log('File downloaded and saved:', newFilePath);
                        // Construct the new URL based on your server configuration
                        const newUrl = `${config.embedSite}${newFilePath}`;
                        //console.log('New URL:', newUrl);
                        //discordMessage.replace(attachmentURL, newUrl);
                        discordMessage += ` ${newUrl} `;
                    } else {
                        //console.log('Failed to download and save the file.');
                    }
                } catch (error) {
                    console.error('Error:', error);
                }
            }
        }
        //console.log(`Message Type: ${message.type}`)
        if (message.type === MessageType.Reply) {
            const msg1 = await message.fetchReference();
            var originalMessage = truncateString(discordMarkdownToIRC(msg1.cleanContent), 60);
            //console.log(msg1.author.displayName, msg1.content);
            ircClient.say(mappedIRCChannel, `> <${antiPing(msg1.author.displayName)}> ${originalMessage}`);
        }
        const lines = discordMessage.split('\n');
        lines.forEach(line => {
            ircClient.say(mappedIRCChannel, `<${antiPing(senderNickname)}> ${line}`);
        });
    }
});
//Function defs below.
// Function to get or fetch a webhook for a channel
async function getWebhook(discordChannel) {
    const channelId = discordChannel.id;
    if(webhookCache[channelId]) {
        // If webhook is already in cache for the channel, return it
        return webhookCache[channelId];
    } else {
        // If not in cache, fetch and store in cache
        const webhooks = await discordChannel.fetchWebhooks();
        const matchingWebhook = webhooks.find(webhook => webhook.name === config.webHookName);
        if(matchingWebhook) {
            webhookCache[channelId] = matchingWebhook;
            return matchingWebhook;
        }
    }
}

function antiPing(senderNickname) {
    // Find the middle index of the senderNickname
    const middleIndex = Math.floor(senderNickname.length / 2);
    // Insert Zero-Width Space character at the middle index
    return senderNickname.slice(0, middleIndex) + '\u200B' + senderNickname.slice(middleIndex);
}
// Function to add allowed Discord users dynamically
function addAllowedDiscordUser(userId) {
    config.discord.allowedUsers.push(userId);
    saveConfig();
}
// Function to add registered IRC users dynamically
function addRegisteredIRCUser(nickname) {
    config.irc.registeredUsers.push(nickname);
    saveConfig();
}

function saveConfig() {
    fs.writeFileSync('config.json', JSON.stringify(config, null, 2));
}

function sendMessageToDiscord(webook, sender, ircMessage) {
    if(webook) {
        const regexPattern = /@(everyone|here)/g;
        ircMessage = ircMessage.replace(regexPattern, '<Redacted Mention>');
        //message.channel.send(sanitizedText);

        // If "yuri" webhook exists, create a WebhookClient instance
        const webhookClient = new WebhookClient({
            id: webook.id,
            token: webook.token,
        });
        const min = 100000; // Minimum value (inclusive)
        const max = 999999; // Maximum value (inclusive)

        // Send the message using the webhook client with custom username and avatar
        webhookClient.send({
                username: sender,
                avatarURL: config.webHookAvatar.replace("%IRCUSERNAME%", sender) + "&" + (Math.floor(Math.random() * (max - min + 1)) + min),
                content: ircMessage
            })
            .then(() => {
                //console.log(`Message sent successfully via webhook to Discord channel ${mappedDiscordChannelID}`);
                webhookClient.destroy(); // Destroy the client after sending the message
            })
            .catch(error => {
                //console.error(`Error sending message via webhook to Discord channel ${mappedDiscordChannelID}:`, error);
                webhookClient.destroy(); // Destroy the client in case of an error
            });
    } else {
        // If "yuri" webhook doesn't exist, send as a regular Discord message
        discordChannel.send(`${sender}: ${ircMessage}`)
            .then(() => {
                //console.log(`Message sent successfully to Discord channel ${mappedDiscordChannelID}`);
            })
            .catch(error => {
                //console.error(`Error sending message to Discord channel ${mappedDiscordChannelID}:`, error);
            });
    }
}

function discordMarkdownToIRC(message) {
    const discordToIRC = {
        '**': '\x02', // Bold
        '__': '\x1F', // Underline
        '~~': '\x1D', // Strike-through
        '*': '\x1D', // Italic (single asterisks)
        '_': '\x1D', // Italic (single underscores)
    };
    // Regular expression to match Discord markdown formatting surrounded by double underscores
    const discordMarkdownRegex = /(__)\*\*|(__)\_\_|(__)\~\~|(__)\*|(__)\_/g;
    // Regular expression to match URLs
    const urlRegex = /https?:\/\/\S+|www\.\S+/gi;
    // Replace Discord markdown with IRC formatting codes except within URLs
    return message.replace(discordMarkdownRegex, (match, p1, p2, p3, p4, p5, offset, string) => {
        if(message.match(urlRegex) && message.match(urlRegex).some(url => url.includes(match))) {
            // If the match is inside a URL, return it unchanged
            return match;
        } else {
            // If not inside a URL, replace with IRC formatting code
            return discordToIRC[match];
        }
    });
}
app.get('/avatar', (req, res) => {
    const nick = req.query.nick;
    const image_path = path.join(__dirname, `avatars/${nick}.png`); // Assuming PNG format
    const matchingFiles = glob.sync(image_path); // Use glob to find matching files

    console.log('Requested Nick:', nick);
    console.log('Image Path:', image_path);

    if (matchingFiles.length > 0) {
        // Serve the first matching image to the browser
        console.log('Serving Existing Image:', matchingFiles[0]);
        res.sendFile(matchingFiles[0]);
    } else if (nick && /^[a-zA-Z0-9]+$/.test(nick)) {
        // Generate a new avatar
        console.log('Generating New Avatar for:', nick);

        const color = stringToColorCode(nick);
        const textColor = readableColor(color) === 'FFFFFF' ? '#FFFFFF' : '#000000';
        im.convert(
            [
                '-size', '256x256',
                'xc:' + color,
                '-pointsize', '120',
                '-font', './arial.ttf',
                '-gravity', 'center',
                '-fill', textColor,
                '-draw', `text 0,0 "${nick[0]}"`,
                image_path
            ],
            function(err) {
                if (err) {
                    console.error('Image Generation Error:', err);
                    res.status(500).send('Internal Server Error');
                } else {
                    // Serve the newly generated image to the browser
                    console.log('New Avatar Generated Successfully:', image_path);
                    res.sendFile(image_path);
                }
            }
        );
    } else {
        console.log('Invalid Input:', nick);
        res.status(400).send('Invalid input');
    }
});


function readableColor(bg) {
    const r = parseInt(bg.substr(1, 2), 16);
    const g = parseInt(bg.substr(3, 2), 16);
    const b = parseInt(bg.substr(5, 2), 16);
    const squaredContrast = r * r * 0.299 + g * g * 0.587 + b * b * 0.114;
    return squaredContrast > Math.pow(110, 2) ? '000000' : 'FFFFFF';
}

function stringToColorCode(str) {
    const code = crc32(str).toString(16);
    return padToSixDigits(code);
}

function padToSixDigits(code) {
    while(code.length < 6) {
        code = '0' + code;
    }
    return '#' + code;
}

async function downloadAndSaveFile(urlIn, saveDirectory) {
    try {
        // Parse the URL to separate the path and query string
        const parsedUrl = url.parse(urlIn, true);
        const cleanUrl = `${parsedUrl.protocol}//${parsedUrl.host}${parsedUrl.pathname}`;

        // Fetch the file content using axios with the original URL
        const response = await axios({
            method: 'get',
            url: urlIn,
            responseType: 'stream'
        });

        // Generate a unique filename (e.g., using timestamp)
        const timestamp = Date.now();
        const fileExtension = cleanUrl.split('.').pop();
        const savedFilePath = `${saveDirectory}/${timestamp}.${fileExtension}`;

        // Save the file to the specified directory
        const writer = fs.createWriteStream(savedFilePath);
        response.data.pipe(writer);

        return `${timestamp}.${fileExtension}`; // Return the path to the saved file without the query string
    } catch (error) {
        console.error('Error downloading and saving file:', error);
        return null; // Return null if there's an error
    }
}

function removeColorCodes(message) {
    // Regular expression to match IRC color codes
    const colorCodeRegex = /\x03(?:\d{1,2}(?:,\d{1,2})?)?|\x02|\x0F|\x16|\x1D|\x1F|\x03(?:\d{1,2}(?:,\d{1,2})?)?|\x04(?:\d{1,2}(?:,\d{1,2})?)?/g;
    // Replace color codes with an empty string
    return message.replace(colorCodeRegex, '');
}
async function uploadToHastebin(content) {
    try {
        const response = await axios.post(`${config.pasteURL}/documents`, content);
        return `${config.pasteURL}/${response.data.key}`;
    } catch (error) {
        console.error('Error uploading to Hastebin:', error);
        return null;
    }
}

function ircToDiscordMarkdown(message) {
    const ircToDiscord = {
        '\x02': '**', // Bold
        '\x1F': '__', // Underline
        '\x1D': '*', // Italic
        '~~': '~~' // Strike-through
    };
    const ircFormattingRegex = /\x02|\x1F|\x1D|\x03(?:\d{1,2}(?:,\d{1,2})?)?/g;
    return message.replace(ircFormattingRegex, match => ircToDiscord[match]);
}

function truncateString(str, maxLength) {
    if (str.length <= maxLength) {
        return str;
    } else {
        // Truncate the string and append Unicode character U+2026 (…)
        return str.slice(0, maxLength - 1) + '…';
    }
}

function lineDiff(oldMessage, newMessage) {
    const a = oldMessage.split(' ');
    const b = newMessage.split(' ');

    let pre = null;
    let post = null;
    const mlen = Math.min(a.length, b.length);

    for (let i = 0; i < mlen; i++) {
        if (a[i] !== b[i]) {
            break;
        }

        pre = i + 1;
    }

    for (let i = 0; i < mlen; i++) {
        if (a[a.length - 1 - i] !== b[b.length - 1 - i]) {
            post = -i - 1;
            break;
        }
    }

    const rem = a.slice(pre);
    const add = b.slice(pre);

    if (add.length === 0 && rem.length > 0) {
        return "-" + rem.join(' ');
    }

    if (rem.length === 0 && add.length > 0) {
        return "+" + add.join(' ');
    }

    if (add.length > 0) {
        return "* " + add.join(' ');
    }

    return null;
}




