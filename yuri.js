'use strict';

const fs = require('fs');
const path = require('path');
const http = require('http');
const crypto = require('crypto');
const { exec } = require('child_process');

const axios = require('axios');
const crc32 = require('crc32');
const express = require('express');
const glob = require('glob');
const im = require('imagemagick');
const irc = require('irc-framework');
const mime = require('mime-types');
const { URL } = require('url');
const {
    Client,
    GatewayIntentBits,
    MessageType,
    Partials,
    WebhookClient
} = require('discord.js');

const CONFIG_PATH = path.join(__dirname, 'config.json');
const config = JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));
const ircConfig = config.irc;

config.discord.allowedUsers ??= [];
config.irc.registeredUsers ??= [];
config.channelMappings ??= {};

let channelMappings = normalizeChannelMappings(config.channelMappings);
config.channelMappings = channelMappings;

const app = express();
const server = http.createServer(app);
const savedEmbedsPath = path.join(__dirname, 'saved_embeds');
const avatarsPath = path.join(__dirname, 'avatars');

const discordClient = new Client({
    intents: [
        GatewayIntentBits.Guilds,
        GatewayIntentBits.GuildMembers,
        GatewayIntentBits.GuildMessages,
        GatewayIntentBits.GuildMessageReactions,
        GatewayIntentBits.MessageContent
    ],
    partials: [Partials.Message, Partials.Channel, Partials.Reaction]
});

const ircClient = new irc.Client();
const webhookCache = new Map();
const ircUserChannelMapping = new Map();
const BRIDGE_BUILD = '2026-07-09-reaction-quote-fix-2';
const recentRelayIndex = new Map();

const RELAY_TTL_MS = 5 * 60 * 1000;
const IRC_RECONNECT_MIN_MS = 5_000;
const IRC_RECONNECT_MAX_MS = 5 * 60_000;
const MAX_REMOTE_FILE_BYTES = Number(config.maxRemoteFileBytes || 25 * 1024 * 1024);
const REMOTE_REQUEST_TIMEOUT_MS = Number(config.remoteRequestTimeoutMs || 30_000);

let ircReconnectTimer = null;
let ircReconnectAttempts = 0;
let ircConnected = false;
let ircConnecting = false;
let shuttingDown = false;

app.disable('x-powered-by');
app.use(express.json({ limit: '64kb' }));
app.use((req, res, next) => {
    res.setHeader('Pragma', 'no-cache');
    res.setHeader('Cache-Control', 'no-cache, no-store, must-revalidate');
    res.setHeader('Expires', '0');
    next();
});
app.use('/saved_embeds', express.static(savedEmbedsPath));

app.post('/resolve-discord-id', (req, res) => {
    try {
        const requiredSecret = String(config.discordBridgeAuth?.secret || '').trim();
        const providedSecret = String(req.get('x-bridge-secret') || '').trim();

        if (requiredSecret && !safeEqual(providedSecret, requiredSecret)) {
            return res.status(403).send('');
        }

        pruneRecentRelayIndex();

        const channel = normalizeBridgeValue(req.body?.channel);
        const bridgeUser = normalizeBridgeValue(req.body?.bridgeUser);
        const message = normalizeBridgeValue(req.body?.message);

        if (!channel || !bridgeUser || !message) {
            return res.status(400).send('');
        }

        const matches = recentRelayIndex.get(makeRelayKey({ channel, bridgeUser, message })) || [];
        const winner = matches.at(-1);

        if (!winner) {
            return res.status(404).send('');
        }

        return res.type('text/plain').send(winner.discordUserId);
    } catch (error) {
        console.error('[http] resolve-discord-id failed:', error);
        return res.status(500).send('');
    }
});

app.get('/avatar', async (req, res) => {
    const nick = String(req.query.nick || '').trim();

    if (!/^[a-zA-Z0-9_-]+$/.test(nick)) {
        return res.status(400).send('Invalid input');
    }

    await fs.promises.mkdir(avatarsPath, { recursive: true });

    const matchingFiles = glob.sync(path.join(avatarsPath, `${nick}.*`));
    if (matchingFiles.length > 0) {
        return res.sendFile(path.resolve(matchingFiles[0]));
    }

    const imagePath = path.join(avatarsPath, `${nick}.png`);
    const color = stringToColorCode(nick);
    const textColor = readableColor(color) === 'FFFFFF' ? '#FFFFFF' : '#000000';

    im.convert([
        '-size', '256x256',
        `xc:${color}`,
        '-pointsize', '120',
        '-font', path.join(__dirname, 'arial.ttf'),
        '-gravity', 'center',
        '-fill', textColor,
        '-draw', `text 0,0 "${nick[0]}"`,
        imagePath
    ], (error) => {
        if (error) {
            console.error('[avatar] generation failed:', error);
            return res.status(500).send('Internal Server Error');
        }

        return res.sendFile(path.resolve(imagePath));
    });
});

console.log(`[bridge] Build ${BRIDGE_BUILD}`);

server.listen(config.webPort, () => {
    console.log(`[http] Web server running on port ${config.webPort}`);
});

function connectIRC() {
    if (shuttingDown || ircConnected || ircConnecting) {
        return;
    }

    ircConnecting = true;
    console.log(`[irc] Connecting to ${ircConfig.server}:${ircConfig.port}...`);

    try {
        ircClient.connect({
            host: ircConfig.server,
            port: ircConfig.port,
            nick: ircConfig.nick,
            username: ircConfig.identNick,
            password: ircConfig.identPass,
            sasl_disconnect_on_fail: false,
            account: {
                account: ircConfig.identNick,
                password: ircConfig.identPass
            },
            channels: []
        });
    } catch (error) {
        ircConnecting = false;
        console.error('[irc] connect failed:', error);
        scheduleIRCReconnect(error.message);
    }
}

function scheduleIRCReconnect(reason = 'connection lost') {
    ircConnected = false;
    ircConnecting = false;

    if (shuttingDown || ircReconnectTimer) {
        return;
    }

    const baseDelay = Math.min(
        IRC_RECONNECT_MIN_MS * (2 ** ircReconnectAttempts),
        IRC_RECONNECT_MAX_MS
    );
    const jitter = Math.floor(Math.random() * 2_000);
    const delay = baseDelay + jitter;

    ircReconnectAttempts += 1;
    console.warn(`[irc] ${reason}; reconnecting in ${Math.ceil(delay / 1000)} seconds.`);

    ircReconnectTimer = setTimeout(() => {
        ircReconnectTimer = null;
        connectIRC();
    }, delay);
    ircReconnectTimer.unref?.();
}

function joinMappedIRCChannels() {
    for (const ircChannel of Object.keys(channelMappings)) {
        console.log(`[irc] Joining ${ircChannel}`);
        ircClient.join(ircChannel);
    }
}

ircClient.on('registered', () => {
    ircConnected = true;
    ircConnecting = false;
    ircReconnectAttempts = 0;

    if (ircReconnectTimer) {
        clearTimeout(ircReconnectTimer);
        ircReconnectTimer = null;
    }

    console.log('[irc] Registered with IRC server.');

    if (ircConfig.identNick && ircConfig.identPass) {
        ircClient.say('NickServ', `IDENTIFY ${ircConfig.identNick} ${ircConfig.identPass}`);
    }

    joinMappedIRCChannels();
});

for (const eventName of ['close', 'socket close']) {
    ircClient.on(eventName, () => scheduleIRCReconnect(eventName));
}

ircClient.on('socket error', (error) => {
    console.error('[irc] Socket error:', error);
    scheduleIRCReconnect(error?.message || 'socket error');
});

ircClient.on('error', (error) => {
    console.error('[irc] Error:', error);
});

discordClient.once('ready', async () => {
    console.log(`[discord] Logged in as ${discordClient.user.tag}`);

    for (const guild of discordClient.guilds.cache.values()) {
        try {
            await guild.members.fetch();
        } catch (error) {
            console.error(`[discord] Error fetching members for ${guild.name}:`, error);
        }
    }
});

discordClient.on('messageCreate', handleDiscordMessageCreate);
discordClient.on('messageUpdate', handleDiscordMessageUpdate);
discordClient.on('messageDelete', handleDiscordMessageDelete);
discordClient.on('messageDeleteBulk', handleDiscordMessageDeleteBulk);
discordClient.on('messageReactionAdd', handleDiscordReactionAdd);

ircClient.on('message', handleIRCMessage);
ircClient.on('action', handleIRCAction);
ircClient.on('wholist', handleIRCWhoList);
ircClient.on('join', handleIRCJoin);
ircClient.on('part', handleIRCPart);
ircClient.on('kick', handleIRCKick);
ircClient.on('quit', handleIRCQuit);
ircClient.on('nick', handleIRCNick);

async function handleIRCMessage(event) {
    if (event.type === 'action') {
        return;
    }

    const plainMessage = stripIrcFormatting(event.message);
    let bridgeMessage = ircToDiscordBridgeMessage(event.message);
    const sender = event.nick;
    const target = normalizeIRCChannel(event.target);

    if (await handleIRCCommand({ event, plainMessage, bridgeMessage, sender, target })) {
        return;
    }

    const mappedChannel = channelMappings[target];
    if (!mappedChannel) {
        return;
    }

    bridgeMessage = replaceIRCMentionsForDiscord(bridgeMessage, mappedChannel);
    await relayIRCToDiscord(mappedChannel, sender, bridgeMessage);
}

async function handleIRCAction(event) {
    const target = normalizeIRCChannel(event.target);
    const mappedChannel = channelMappings[target];

    if (!mappedChannel) {
        return;
    }

    let bridgeMessage = ircToDiscordBridgeMessage(event.message);
    bridgeMessage = replaceIRCMentionsForDiscord(bridgeMessage, mappedChannel);

    if (!isDiscordAnsiCodeblock(bridgeMessage)) {
        bridgeMessage = `_${bridgeMessage}_`;
    }

    await relayIRCToDiscord(mappedChannel, event.nick, bridgeMessage);
}

async function handleIRCCommand({ event, plainMessage, bridgeMessage, sender, target }) {
    const [commandRaw, ...args] = plainMessage.trim().split(/\s+/);
    const command = commandRaw.toLowerCase();
    const isAllowed = config.irc.registeredUsers.some(
        (nick) => String(nick).toLowerCase() === String(sender).toLowerCase()
    );

    if (!['!adduser', '!deluser', '!setmyavatar', '!link', '!unlink', '!update', '!showmoreinfo'].includes(command)) {
        return false;
    }

    if (!isAllowed && command !== '!setmyavatar') {
        ircClient.say(event.target, 'Permission denied');
        return true;
    }

    switch (command) {
        case '!adduser': {
            const nickname = args[0];
            if (!nickname) {
                ircClient.say(event.target, 'Usage: !adduser nickname');
                return true;
            }
            addRegisteredIRCUser(nickname);
            ircClient.say(event.target, `User ${nickname} has been added to the registered users list.`);
            return true;
        }

        case '!deluser': {
            const nickname = args[0];
            const existing = config.irc.registeredUsers.find(
                (nick) => String(nick).toLowerCase() === String(nickname || '').toLowerCase()
            );
            if (!existing) {
                ircClient.say(event.target, `User ${nickname || ''} is not in the registered users list.`);
                return true;
            }
            config.irc.registeredUsers = config.irc.registeredUsers.filter((nick) => nick !== existing);
            saveConfig();
            ircClient.say(event.target, `User ${existing} has been removed from the registered users list.`);
            return true;
        }

        case '!setmyavatar': {
            const avatarUrl = args[0];
            if (!avatarUrl) {
                ircClient.say(event.target, 'Usage: !setmyavatar https://example/image.png');
                return true;
            }
            await setIRCUserAvatar(sender, avatarUrl, event.target);
            return true;
        }

        case '!link': {
            const [ircChannelRaw, discordChannelID, showMoreInfoRaw = 'false'] = args;
            const ircChannel = normalizeIRCChannel(ircChannelRaw);

            if (!ircChannel.startsWith('#') || !/^\d+$/.test(discordChannelID || '')) {
                ircClient.say(event.target, 'Usage: !link #IRCChannel DiscordChannelID [true|false]');
                return true;
            }

            channelMappings[ircChannel] = {
                discordChannelID,
                showMoreInfo: String(showMoreInfoRaw).toLowerCase() === 'true'
            };
            saveConfig();
            ircClient.join(ircChannel);
            ircClient.say(event.target, `Linked Discord channel ${discordChannelID} to IRC channel ${ircChannel}.`);
            return true;
        }

        case '!unlink': {
            const ircChannel = normalizeIRCChannel(args[0]);
            if (!channelMappings[ircChannel]) {
                ircClient.say(event.target, `No mapping found for IRC channel ${ircChannel}.`);
                return true;
            }
            delete channelMappings[ircChannel];
            saveConfig();
            ircClient.part(ircChannel);
            ircClient.say(event.target, `Unlinked IRC channel ${ircChannel}.`);
            return true;
        }

        case '!update': {
            runGitUpdate((message) => ircClient.say(event.target, message));
            return true;
        }

        case '!showmoreinfo': {
            const value = String(args[0] || '').toLowerCase();
            if (!['true', 'false'].includes(value)) {
                ircClient.say(event.target, 'Usage: !showmoreinfo true|false');
                return true;
            }

            const mapped = channelMappings[target];
            if (!mapped) {
                ircClient.say(event.target, 'No mapping exists for this IRC channel.');
                return true;
            }

            mapped.showMoreInfo = value === 'true';
            saveConfig();
            ircClient.say(event.target, `Set showMoreInfo to ${mapped.showMoreInfo}.`);
            return true;
        }

        default:
            return false;
    }
}

function handleIRCWhoList(event) {
    const channel = normalizeIRCChannel(event.target);
    const users = new Set((event.users || []).map((user) => user.nick).filter(Boolean));
    ircUserChannelMapping.set(channel, users);
}

async function handleIRCJoin(event) {
    const channel = normalizeIRCChannel(event.channel);

    if (event.nick === ircConfig.nick) {
        ircUserChannelMapping.set(channel, new Set());
        ircClient.who(event.channel);
        return;
    }

    getIRCUserSet(channel).add(event.nick);
    await relayIRCStatus(channel, `${event.nick}@${event.hostname} joined ${event.channel} on IRC`);
}

async function handleIRCPart(event) {
    if (event.nick === ircConfig.nick) {
        return;
    }

    const channel = normalizeIRCChannel(event.channel);
    getIRCUserSet(channel).delete(event.nick);
    await relayIRCStatus(
        channel,
        `${event.nick}@${event.hostname} left ${event.channel} (${event.message || 'No reason provided'})`
    );
}

async function handleIRCKick(event) {
    const channel = normalizeIRCChannel(event.channel);
    getIRCUserSet(channel).delete(event.kicked);
    await relayIRCStatus(
        channel,
        `${event.kicked} was kicked from ${event.channel} by ${event.nick} (${event.message || 'No reason provided'})`
    );
}

async function handleIRCQuit(event) {
    const jobs = [];

    for (const [channel, users] of ircUserChannelMapping.entries()) {
        if (!users.delete(event.nick)) {
            continue;
        }

        jobs.push(relayIRCStatus(
            channel,
            `${event.nick}@${event.hostname} quit IRC (${event.message || 'No reason provided'}) in ${channel}`
        ));
    }

    await Promise.allSettled(jobs);
}

async function handleIRCNick(event) {
    const jobs = [];

    for (const [channel, users] of ircUserChannelMapping.entries()) {
        if (!users.delete(event.nick)) {
            continue;
        }

        users.add(event.new_nick);
        jobs.push(relayIRCStatus(channel, `${event.nick} is now known as ${event.new_nick}`));
    }

    await Promise.allSettled(jobs);
}

async function relayIRCStatus(ircChannel, text) {
    const mappedChannel = channelMappings[normalizeIRCChannel(ircChannel)];
    if (!mappedChannel?.showMoreInfo) {
        return;
    }

    await relayIRCToDiscord(mappedChannel, ircConfig.nick, text);
}

async function relayIRCToDiscord(mappedChannel, sender, content) {
    const discordChannel = discordClient.channels.cache.get(mappedChannel.discordChannelID);
    if (!discordChannel) {
        console.warn(`[bridge] Discord channel ${mappedChannel.discordChannelID} is unavailable.`);
        return;
    }

    const webhook = await getWebhook(discordChannel);
    await sendMessageToDiscord(webhook, discordChannel, sender, content);
}

function replaceIRCMentionsForDiscord(message, mappedChannel) {
    const discordChannel = discordClient.channels.cache.get(mappedChannel.discordChannelID);
    const guild = discordChannel?.guild;

    if (!guild) {
        return message;
    }

    return String(message).replace(/@(\w+)/g, (match, username) => {
        const normalized = username.toLowerCase();
        const member = guild.members.cache.find((candidate) => {
            return candidate.user.username.toLowerCase() === normalized ||
                candidate.displayName.toLowerCase() === normalized ||
                candidate.user.tag.toLowerCase() === normalized;
        });

        return member ? `<@${member.id}>` : match;
    });
}

async function handleDiscordMessageCreate(message) {
    try {
        const mappedIRCChannel = findIRCChannelByDiscordId(message.channel.id);
        if (!mappedIRCChannel || message.author?.id === discordClient.user?.id) {
            return;
        }

        if (await isOwnWebhookMessage(message)) {
            return;
        }

        let discordMessage = discordMarkdownToIRC(message.cleanContent || '');

        // Webhook messages (especially GitHub) are often embed-only. Fold useful
        // embed content into the IRC relay so those messages are not blank.
        const embedText = formatDiscordEmbedsForIRC(message.embeds);
        if (embedText) {
            discordMessage = [discordMessage.trim(), embedText]
                .filter(Boolean)
                .join('\n');
        }

        const senderNickname = getDiscordMessageAuthorName(message);

        if (await handleDiscordCommand(message, discordMessage)) {
            return;
        }

        const bridgedText = String(message.cleanContent || message.content || '').trim();
        if (bridgedText) {
            rememberDiscordRelay({
                channel: mappedIRCChannel,
                bridgeUser: senderNickname,
                message: bridgedText,
                discordUserId: message.author.id,
                discordTag: message.author.tag,
                discordMessageId: message.id
            });
        }

        const isCodeBlock = /^```[\s\S]*```$/.test(discordMessage);
        const hasMoreThan3Lines = discordMessage.split('\n').length > 3;

        if (isCodeBlock || hasMoreThan3Lines) {
            discordMessage = await uploadToHastebin(discordMessage) || 'Error uploading to Hastebin.';
        }

        if (message.attachments.size > 0) {
            for (const attachment of message.attachments.values()) {
                const publicPath = await downloadAndSaveFile(attachment.url, message.id, savedEmbedsPath);
                if (publicPath) {
                    discordMessage += ` ${String(config.embedSite || '').replace(/\/$/, '')}/${publicPath}`;
                }
            }
        }

        if (message.type === MessageType.Reply) {
            try {
                const referenced = await message.fetchReference();
                const author = getDiscordMessageAuthorName(referenced);
                const original = truncateString(discordMarkdownToIRC(referenced.cleanContent || ''), 60);
                ircClient.say(mappedIRCChannel, `> <${antiPing(author)}> ${original}`);
            } catch (error) {
                console.error('[discord] Failed to fetch reply reference:', error);
            }
        }

        for (const line of String(discordMessage || '')
            .split('\n')
            .map((line) => line.trimEnd())
            .filter((line) => line.trim().length > 0)) {
            ircClient.say(mappedIRCChannel, `<${antiPing(senderNickname)}> ${line}`);
        }
    } catch (error) {
        console.error('[discord] messageCreate failed:', error);
    }
}

async function handleDiscordCommand(message, discordMessage) {
    const [commandRaw, ...args] = String(discordMessage || '').trim().split(/\s+/);
    const command = commandRaw.toLowerCase();

    if (!['!adduser', '!deluser', '!link', '!update', '!showmoreinfo'].includes(command)) {
        return false;
    }

    if (!config.discord.allowedUsers.includes(message.author.id)) {
        await message.channel.send('Permission denied');
        return true;
    }

    switch (command) {
        case '!adduser': {
            const userId = args[0];
            if (!/^\d+$/.test(userId || '')) {
                await message.channel.send('Usage: !adduser UserID');
                return true;
            }
            addAllowedDiscordUser(userId);
            await message.channel.send(`User ${userId} has been added to the allowed users list.`);
            return true;
        }

        case '!deluser': {
            const userId = args[0];
            if (!/^\d+$/.test(userId || '')) {
                await message.channel.send('Usage: !deluser UserID');
                return true;
            }
            if (!config.discord.allowedUsers.includes(userId)) {
                await message.channel.send(`User ${userId} is not in the allowed users list.`);
                return true;
            }
            config.discord.allowedUsers = config.discord.allowedUsers.filter((id) => id !== userId);
            saveConfig();
            await message.channel.send(`User ${userId} has been removed from the allowed users list.`);
            return true;
        }

        case '!link': {
            const [discordChannelID, ircChannelRaw, showMoreInfoRaw = 'false'] = args;
            const ircChannel = normalizeIRCChannel(ircChannelRaw);
            if (!/^\d+$/.test(discordChannelID || '') || !ircChannel.startsWith('#')) {
                await message.channel.send('Usage: !link DiscordChannelID #IRCChannel [true|false]');
                return true;
            }
            channelMappings[ircChannel] = {
                discordChannelID,
                showMoreInfo: String(showMoreInfoRaw).toLowerCase() === 'true'
            };
            saveConfig();
            ircClient.join(ircChannel);
            await message.channel.send(`Linked Discord channel ${discordChannelID} to IRC channel ${ircChannel}.`);
            return true;
        }

        case '!update':
            runGitUpdate((text) => message.channel.send(text));
            return true;

        case '!showmoreinfo': {
            const value = String(args[0] || '').toLowerCase();
            if (!['true', 'false'].includes(value)) {
                await message.channel.send('Usage: !showmoreinfo true|false');
                return true;
            }
            const ircChannel = findIRCChannelByDiscordId(message.channel.id);
            if (!ircChannel) {
                await message.channel.send('No IRC mapping exists for this Discord channel.');
                return true;
            }
            channelMappings[ircChannel].showMoreInfo = value === 'true';
            saveConfig();
            await message.channel.send(`Set showMoreInfo to ${value}.`);
            return true;
        }

        default:
            return false;
    }
}

async function handleDiscordMessageUpdate(oldMessage, newMessage) {
    try {
        if (oldMessage.partial) await oldMessage.fetch();
        if (newMessage.partial) await newMessage.fetch();

        const mappedIRCChannel = findIRCChannelByDiscordId(newMessage.channel.id);
        if (!mappedIRCChannel || await isOwnWebhookMessage(newMessage)) {
            return;
        }

        if (newMessage.author?.id === discordClient.user?.id) {
            return;
        }

        const diff = lineDiff(oldMessage.content || '', newMessage.content || '');
        if (!diff) {
            return;
        }

        ircClient.say(
            mappedIRCChannel,
            `<${antiPing(getDiscordMessageAuthorName(newMessage))}> ${diff}`
        );
    } catch (error) {
        console.error('[discord] messageUpdate failed:', error);
    }
}

async function handleDiscordMessageDelete(message) {
    await deleteSavedEmbedDirectory(message?.id);
}

async function handleDiscordMessageDeleteBulk(messages) {
    await Promise.allSettled(
        [...messages.values()].map((message) => deleteSavedEmbedDirectory(message?.id))
    );
}

async function deleteSavedEmbedDirectory(messageId) {
    if (!messageId) {
        return;
    }

    const safeMessageId = sanitizePathSegment(messageId);
    const dirToDelete = path.join(savedEmbedsPath, safeMessageId);

    try {
        await fs.promises.rm(dirToDelete, { recursive: true, force: true });
        console.log(`[embeds] Deleted ${dirToDelete}`);
    } catch (error) {
        console.error('[embeds] Cleanup failed:', error);
    }
}

async function handleDiscordReactionAdd(reaction, user) {
    try {
        if (!user || user.bot) {
            return;
        }

        if (reaction.partial) await reaction.fetch();
        if (reaction.message.partial) await reaction.message.fetch();

        const message = reaction.message;
        const mappedIRCChannel = findIRCChannelByDiscordId(message.channel.id);
        if (!mappedIRCChannel) {
            return;
        }

        const member = message.guild?.members.cache.get(user.id);
        const reactorName = member?.displayName || user.globalName || user.username || user.tag || 'Unknown';

        const reactionLine =
            `> <${antiPing(reactorName)}> reacted ${formatReactionForIRC(reaction)} ` +
            `to <${antiPing(getDiscordMessageAuthorName(message))}> ${summarizeDiscordMessageForIRC(message, 80)}`;

        console.log('[discord->irc] reaction:', JSON.stringify(reactionLine));
        ircClient.say(mappedIRCChannel, reactionLine);
    } catch (error) {
        console.error('[discord] reaction handler failed:', error);
    }
}

async function isOwnWebhookMessage(message) {
    if (!message.webhookId) {
        return false;
    }

    const webhook = await getWebhook(message.channel);
    return Boolean(webhook && message.webhookId === webhook.id);
}

async function getWebhook(discordChannel) {
    if (!discordChannel?.id || typeof discordChannel.fetchWebhooks !== 'function') {
        return null;
    }

    if (webhookCache.has(discordChannel.id)) {
        return webhookCache.get(discordChannel.id);
    }

    try {
        const webhooks = await discordChannel.fetchWebhooks();
        let webhook = webhooks.find((candidate) => candidate.name === config.webHookName);

        if (!webhook && typeof discordChannel.createWebhook === 'function') {
            webhook = await discordChannel.createWebhook({
                name: config.webHookName || 'IRC Bridge'
            });
        }

        if (!webhook?.token) {
            return null;
        }

        const client = new WebhookClient({ id: webhook.id, token: webhook.token });
        const cached = { id: webhook.id, client };
        webhookCache.set(discordChannel.id, cached);
        return cached;
    } catch (error) {
        console.error(`[discord] Failed to get webhook for ${discordChannel.id}:`, error);
        return null;
    }
}

async function sendMessageToDiscord(webhook, discordChannel, sender, ircMessage) {
    const safeSender = String(sender || 'IRC').slice(0, 80);
    const safeContent = String(ircMessage || '')
        .replace(/@(everyone|here)/gi, '<Redacted Mention>')
        .slice(0, 2000);

    try {
        if (webhook?.client) {
            const cacheBust = crypto.randomInt(100000, 1000000);
            const avatarURL = String(config.webHookAvatar || '')
                .replace('%IRCUSERNAME%', encodeURIComponent(safeSender));

            await webhook.client.send({
                username: safeSender,
                avatarURL: avatarURL ? `${avatarURL}&${cacheBust}` : undefined,
                content: safeContent,
                allowedMentions: { parse: [] }
            });
            return;
        }

        await discordChannel.send({
            content: `${safeSender}: ${safeContent}`,
            allowedMentions: { parse: [] }
        });
    } catch (error) {
        console.error('[discord] Failed to relay IRC message:', error);
    }
}

async function setIRCUserAvatar(nick, avatarUrl, replyTarget) {
    try {
        const parsed = new URL(avatarUrl);
        if (!['http:', 'https:'].includes(parsed.protocol)) {
            throw new Error('Only HTTP and HTTPS URLs are supported.');
        }

        await fs.promises.mkdir(avatarsPath, { recursive: true });
        for (const file of glob.sync(path.join(avatarsPath, `${nick}.*`))) {
            await fs.promises.rm(file, { force: true });
        }

        const response = await axios.get(avatarUrl, {
            responseType: 'arraybuffer',
            timeout: REMOTE_REQUEST_TIMEOUT_MS,
            maxContentLength: MAX_REMOTE_FILE_BYTES,
            maxBodyLength: MAX_REMOTE_FILE_BYTES,
            validateStatus: (status) => status >= 200 && status < 300
        });

        const contentType = String(response.headers['content-type'] || '').split(';')[0];
        if (!contentType.startsWith('image/')) {
            throw new Error('URL did not return an image.');
        }

        const extension = mime.extension(contentType) || 'img';
        await fs.promises.writeFile(path.join(avatarsPath, `${nick}.${extension}`), response.data);
        ircClient.say(replyTarget, 'Avatar downloaded and set successfully.');
    } catch (error) {
        console.error('[avatar] download failed:', error);
        ircClient.say(replyTarget, 'Error downloading or saving the avatar.');
    }
}

async function downloadAndSaveFile(urlIn, messageId, baseDirectory) {
    const safeMessageId = sanitizePathSegment(messageId);
    const targetDir = path.join(baseDirectory, safeMessageId);

    try {
        await fs.promises.mkdir(targetDir, { recursive: true });

        const parsedUrl = new URL(urlIn);
        const extension = path.extname(parsedUrl.pathname) || '.bin';
        const filename = `${Date.now()}-${crypto.randomBytes(6).toString('hex')}${extension}`;
        const savedFilePath = path.join(targetDir, filename);

        const response = await axios.get(urlIn, {
            responseType: 'stream',
            timeout: REMOTE_REQUEST_TIMEOUT_MS,
            maxContentLength: MAX_REMOTE_FILE_BYTES,
            maxBodyLength: MAX_REMOTE_FILE_BYTES,
            validateStatus: (status) => status >= 200 && status < 300
        });

        const contentLength = Number(response.headers['content-length'] || 0);
        if (contentLength > MAX_REMOTE_FILE_BYTES) {
            response.data.destroy();
            throw new Error(`Attachment exceeds ${MAX_REMOTE_FILE_BYTES} bytes.`);
        }

        let received = 0;
        response.data.on('data', (chunk) => {
            received += chunk.length;
            if (received > MAX_REMOTE_FILE_BYTES) {
                response.data.destroy(new Error('Attachment exceeded size limit while downloading.'));
            }
        });

        await new Promise((resolve, reject) => {
            const writer = fs.createWriteStream(savedFilePath, { flags: 'wx' });
            response.data.pipe(writer);
            writer.once('finish', resolve);
            writer.once('error', reject);
            response.data.once('error', reject);
        });

        return path.posix.join(safeMessageId, filename);
    } catch (error) {
        console.error('[embeds] Download failed:', error);
        await fs.promises.rm(targetDir, { recursive: true, force: true }).catch(() => {});
        return null;
    }
}

async function uploadToHastebin(content) {
    try {
        const response = await axios.post(`${String(config.pasteURL).replace(/\/$/, '')}/documents`, content, {
            timeout: REMOTE_REQUEST_TIMEOUT_MS,
            headers: { 'content-type': 'text/plain; charset=utf-8' }
        });
        return `${String(config.pasteURL).replace(/\/$/, '')}/${response.data.key}`;
    } catch (error) {
        console.error('[paste] Upload failed:', error);
        return null;
    }
}

function runGitUpdate(sendResult) {
    exec('git pull', { cwd: __dirname }, (error, stdout) => {
        if (error) {
            console.error('[update] git pull failed:', error);
            void sendResult(`Error during git pull: ${error.message}`);
            return;
        }

        if (stdout.includes('Already up to date.')) {
            exec('git rev-parse HEAD', { cwd: __dirname }, (hashError, hashStdout) => {
                if (hashError) {
                    void sendResult(`Error getting commit hash: ${hashError.message}`);
                    return;
                }
                void sendResult(`Bot is already up to date (Commit: ${hashStdout.trim()}).`);
            });
            return;
        }

        Promise.resolve(sendResult('Bot has been updated. Relaunching...'))
            .finally(() => setTimeout(() => process.exit(0), 500));
    });
}

function normalizeChannelMappings(mappings) {
    const normalized = {};
    for (const [channel, value] of Object.entries(mappings || {})) {
        normalized[normalizeIRCChannel(channel)] = {
            discordChannelID: String(value.discordChannelID),
            showMoreInfo: Boolean(value.showMoreInfo)
        };
    }
    return normalized;
}

function normalizeIRCChannel(channel) {
    return String(channel || '').trim().toLowerCase();
}

function findIRCChannelByDiscordId(discordChannelId) {
    return Object.keys(channelMappings).find(
        (ircChannel) => channelMappings[ircChannel]?.discordChannelID === String(discordChannelId)
    );
}

function getIRCUserSet(channel) {
    const normalized = normalizeIRCChannel(channel);
    if (!ircUserChannelMapping.has(normalized)) {
        ircUserChannelMapping.set(normalized, new Set());
    }
    return ircUserChannelMapping.get(normalized);
}

function saveConfig() {
    config.channelMappings = channelMappings;
    const tempPath = `${CONFIG_PATH}.tmp`;
    fs.writeFileSync(tempPath, `${JSON.stringify(config, null, 2)}\n`, 'utf8');
    fs.renameSync(tempPath, CONFIG_PATH);
}

function addAllowedDiscordUser(userId) {
    if (!config.discord.allowedUsers.includes(userId)) {
        config.discord.allowedUsers.push(userId);
        saveConfig();
    }
}

function addRegisteredIRCUser(nickname) {
    const exists = config.irc.registeredUsers.some(
        (nick) => String(nick).toLowerCase() === String(nickname).toLowerCase()
    );
    if (!exists) {
        config.irc.registeredUsers.push(nickname);
        saveConfig();
    }
}

function normalizeBridgeValue(value) {
    return String(value || '').trim();
}

function makeRelayKey({ channel, bridgeUser, message }) {
    return [
        normalizeBridgeValue(channel).toLowerCase(),
        normalizeBridgeValue(bridgeUser).toLowerCase(),
        normalizeBridgeValue(message)
    ].join('||');
}

function rememberDiscordRelay(entry) {
    if (!entry.channel || !entry.bridgeUser || !entry.message || !entry.discordUserId) {
        return;
    }

    pruneRecentRelayIndex();
    const key = makeRelayKey(entry);
    const existing = recentRelayIndex.get(key) || [];

    existing.push({
        ts: Date.now(),
        discordUserId: String(entry.discordUserId),
        discordTag: String(entry.discordTag || ''),
        discordMessageId: String(entry.discordMessageId || '')
    });

    recentRelayIndex.set(key, existing.slice(-10));
}

function pruneRecentRelayIndex() {
    const now = Date.now();
    for (const [key, entries] of recentRelayIndex.entries()) {
        const kept = entries.filter((entry) => now - entry.ts <= RELAY_TTL_MS);
        if (kept.length) recentRelayIndex.set(key, kept);
        else recentRelayIndex.delete(key);
    }
}

function safeEqual(left, right) {
    const a = Buffer.from(String(left));
    const b = Buffer.from(String(right));
    return a.length === b.length && crypto.timingSafeEqual(a, b);
}

function sanitizePathSegment(value) {
    return String(value || '').replace(/[^a-zA-Z0-9_-]/g, '_');
}

function antiPing(value) {
    const text = String(value || 'Unknown');
    const middle = Math.floor(text.length / 2);
    return `${text.slice(0, middle)}\u200B${text.slice(middle)}`;
}

function readableColor(bg) {
    const r = parseInt(bg.slice(1, 3), 16);
    const g = parseInt(bg.slice(3, 5), 16);
    const b = parseInt(bg.slice(5, 7), 16);
    const contrast = r * r * 0.299 + g * g * 0.587 + b * b * 0.114;
    return contrast > 110 ** 2 ? '000000' : 'FFFFFF';
}

function stringToColorCode(str) {
    const code = crc32(str).toString(16).replace(/^-/, '').slice(-6).padStart(6, '0');
    return `#${code}`;
}

function truncateString(str, maxLength) {
    const text = String(str || '');
    return text.length <= maxLength ? text : `${text.slice(0, maxLength - 1)}…`;
}

function getDiscordMessageAuthorName(message) {
    return message?.member?.displayName ||
        message?.author?.globalName ||
        message?.author?.username ||
        message?.author?.tag ||
        'Unknown';
}


function normalizeEmbedTextForIRC(value) {
    return discordMarkdownToIRC(String(value || ''))
        .replace(/\r/g, '')
        .replace(/[ \t]+/g, ' ')
        .replace(/\n{3,}/g, '\n\n')
        .trim();
}

function truncateEmbedPart(value, maxLength) {
    const text = normalizeEmbedTextForIRC(value);
    return text ? truncateString(text, maxLength) : '';
}

function formatDiscordEmbedForIRC(embed) {
    if (!embed) {
        return '';
    }

    const parts = [];

    const authorName = truncateEmbedPart(embed.author?.name, 80);
    const title = truncateEmbedPart(embed.title, 180);
    const description = truncateEmbedPart(embed.description, 500);
    const embedUrl = String(embed.url || '').trim();

    // GitHub webhook embeds usually put the repository/event summary in the
    // author/title and the issue/PR body excerpt in the description.
    if (authorName && authorName !== title) {
        parts.push(authorName);
    }

    if (title) {
        parts.push(title);
    }

    if (description) {
        parts.push(description);
    }

    // Keep useful metadata, but cap field count/length so IRC is not flooded
    // by CI/status embeds with huge field lists.
    if (Array.isArray(embed.fields)) {
        for (const field of embed.fields.slice(0, 6)) {
            const fieldName = truncateEmbedPart(field?.name, 80);
            const fieldValue = truncateEmbedPart(field?.value, 240);

            if (!fieldName && !fieldValue) {
                continue;
            }

            if (fieldName && fieldValue) {
                parts.push(`${fieldName}: ${fieldValue}`);
            } else {
                parts.push(fieldName || fieldValue);
            }
        }
    }

    if (embedUrl && !parts.some((part) => part.includes(embedUrl))) {
        parts.push(embedUrl);
    }

    return parts
        .map((part) => String(part).trim())
        .filter(Boolean)
        .join('\n');
}

function formatDiscordEmbedsForIRC(embeds) {
    if (!embeds || embeds.length === 0) {
        return '';
    }

    return embeds
        .slice(0, 4)
        .map(formatDiscordEmbedForIRC)
        .filter(Boolean)
        .join('\n');
}

function formatReactionForIRC(reaction) {
    const emoji = reaction?.emoji;
    if (!emoji) return ':unknown:';
    if (emoji.id && emoji.name) return `:${emoji.name}:`;
    return emoji.name || emoji.toString() || ':unknown:';
}

function summarizeDiscordMessageForIRC(message, maxLength = 80) {
    let text = String(message?.cleanContent || message?.content || '');
    if (isDiscordAnsiCodeblock(text)) text = stripDiscordAnsiForIRC(text);
    if (text) text = discordMarkdownToIRC(text);
    text = text.replace(/\s+/g, ' ').trim();

    if (!text) {
        if (message?.attachments?.size > 0) text = '[attachment]';
        else if (message?.embeds?.length > 0) text = '[embed]';
        else text = '[no text]';
    }

    return truncateString(text, maxLength);
}

function lineDiff(oldMessage, newMessage) {
    const oldWords = String(oldMessage || '').split(' ');
    const newWords = String(newMessage || '').split(' ');

    let prefix = 0;
    while (prefix < oldWords.length && prefix < newWords.length && oldWords[prefix] === newWords[prefix]) {
        prefix += 1;
    }

    let suffix = 0;
    while (
        suffix < oldWords.length - prefix &&
        suffix < newWords.length - prefix &&
        oldWords[oldWords.length - 1 - suffix] === newWords[newWords.length - 1 - suffix]
    ) {
        suffix += 1;
    }

    const removed = oldWords.slice(prefix, suffix ? -suffix : undefined);
    const added = newWords.slice(prefix, suffix ? -suffix : undefined);

    if (!added.length && removed.length) return `-${removed.join(' ')}`;
    if (!removed.length && added.length) return `+${added.join(' ')}`;
    if (added.length) return `* ${added.join(' ')}`;
    return null;
}

function discordMarkdownToIRC(message) {
    return String(message || '')
        .replace(/\*\*(.+?)\*\*/gs, '\x02$1\x02')
        .replace(/__(.+?)__/gs, '\x1F$1\x1F')
        .replace(/~~(.+?)~~/gs, '\x1D$1\x1D')
        .replace(/(?<!\*)\*([^*]+?)\*(?!\*)/gs, '\x1D$1\x1D')
        .replace(/(?<!_)_([^_]+?)_(?!_)/gs, '\x1D$1\x1D');
}

function hasIrcColorFormatting(message) {
    return /[\x03\x04]/.test(String(message || ''));
}

function stripIrcFormatting(message) {
    return String(message || '')
        .replace(/\x03(?:\d{1,2}(?:,\d{1,2})?)?/g, '')
        .replace(/\x04(?:[0-9A-Fa-f]{6}(?:,[0-9A-Fa-f]{6})?)?/g, '')
        .replace(/[\x02\x0F\x16\x1D\x1F\x1E]/g, '');
}

function removeColorCodes(message) {
    return stripIrcFormatting(message);
}

function ircToDiscordBridgeMessage(message) {
    return hasIrcColorFormatting(message)
        ? ircToDiscordAnsiCodeblock(message)
        : ircToDiscordMarkdown(message);
}

function ircToDiscordMarkdown(message) {
    const conversions = { '\x02': '**', '\x1F': '__', '\x1D': '*' };
    return String(message || '')
        .replace(/\x02|\x1F|\x1D/g, (match) => conversions[match] || '')
        .replace(/\x03(?:\d{1,2}(?:,\d{1,2})?)?/g, '')
        .replace(/\x04(?:[0-9A-Fa-f]{6}(?:,[0-9A-Fa-f]{6})?)?/g, '')
        .replace(/[\x0F\x16\x1E]/g, '');
}

const DISCORD_CODE_FENCE = '`'.repeat(3);
const DISCORD_ANSI_PREFIX = `${DISCORD_CODE_FENCE}ansi\n`;

function isDiscordAnsiCodeblock(message) {
    return String(message || '').startsWith(DISCORD_ANSI_PREFIX);
}

function stripDiscordAnsiForIRC(content) {
    let text = String(content || '');
    if (text.startsWith(DISCORD_ANSI_PREFIX)) text = text.slice(DISCORD_ANSI_PREFIX.length);
    if (text.endsWith(`\n${DISCORD_CODE_FENCE}`)) text = text.slice(0, -4);
    else if (text.endsWith(DISCORD_CODE_FENCE)) text = text.slice(0, -3);
    return text.replace(/\x1b\[[0-9;]*m/g, '');
}

function escapeDiscordAnsiCodeblock(text) {
    return String(text || '').replace(/```/g, '`\u200b``').replace(/\x1b/g, '');
}

const IRC_TO_ANSI_FG = {
    0: 37, 1: 30, 2: 34, 3: 32, 4: 31, 5: 31, 6: 35, 7: 33,
    8: 33, 9: 32, 10: 36, 11: 36, 12: 34, 13: 35, 14: 30, 15: 37
};
const IRC_TO_ANSI_BG = {
    0: 47, 1: 40, 2: 44, 3: 42, 4: 41, 5: 41, 6: 45, 7: 43,
    8: 43, 9: 42, 10: 46, 11: 46, 12: 44, 13: 45, 14: 40, 15: 47
};

function readIrcColorNumber(message, index) {
    const match = message.slice(index).match(/^\d{1,2}/);
    if (!match) return { value: null, length: 0 };

    if (match[0].length >= 2) {
        const value = parseInt(match[0].slice(0, 2), 10);
        if (value >= 0 && value <= 15) return { value, length: 2 };
    }

    return { value: parseInt(match[0][0], 10), length: 1 };
}

function ansiSequence(state) {
    const codes = [];
    if (state.bold) codes.push(1);
    if (state.underline) codes.push(4);
    if (state.fg != null) codes.push(state.fg);
    if (state.bg != null) codes.push(state.bg);
    return codes.length ? `\x1b[${codes.join(';')}m` : '\x1b[0m';
}

function ircToDiscordAnsiCodeblock(input) {
    const message = escapeDiscordAnsiCodeblock(input);
    const state = { bold: false, underline: false, fg: null, bg: null };
    let out = '';

    const emitState = () => { out += ansiSequence(state); };

    for (let i = 0; i < message.length; i += 1) {
        const ch = message[i];

        if (ch === '\x02') { state.bold = !state.bold; emitState(); continue; }
        if (ch === '\x1F') { state.underline = !state.underline; emitState(); continue; }
        if (ch === '\x1D') continue;

        if (ch === '\x16') {
            [state.fg, state.bg] = [state.bg, state.fg];
            emitState();
            continue;
        }

        if (ch === '\x0F') {
            Object.assign(state, { bold: false, underline: false, fg: null, bg: null });
            out += '\x1b[0m';
            continue;
        }

        if (ch === '\x03') {
            const fg = readIrcColorNumber(message, i + 1);
            if (fg.value === null) {
                state.fg = null;
                state.bg = null;
                emitState();
                continue;
            }

            i += fg.length;
            state.fg = IRC_TO_ANSI_FG[fg.value] ?? null;

            if (message[i + 1] === ',') {
                const bg = readIrcColorNumber(message, i + 2);
                if (bg.value !== null) {
                    i += 1 + bg.length;
                    state.bg = IRC_TO_ANSI_BG[bg.value] ?? null;
                }
            }

            emitState();
            continue;
        }

        if (ch === '\x04') {
            const match = message.slice(i + 1).match(/^[0-9A-Fa-f]{6}(?:,[0-9A-Fa-f]{6})?/);
            if (match) i += match[0].length;
            continue;
        }

        out += ch;
    }

    return `${DISCORD_ANSI_PREFIX}${out}\x1b[0m\n${DISCORD_CODE_FENCE}`;
}

async function shutdown(signal) {
    if (shuttingDown) return;
    shuttingDown = true;
    console.log(`[shutdown] Received ${signal}.`);

    if (ircReconnectTimer) clearTimeout(ircReconnectTimer);

    try { ircClient.quit('Bridge shutting down'); } catch {}
    try { discordClient.destroy(); } catch {}

    for (const cached of webhookCache.values()) {
        try { cached.client.destroy(); } catch {}
    }

    server.close(() => process.exit(0));
    setTimeout(() => process.exit(1), 10_000).unref();
}

process.on('SIGINT', () => shutdown('SIGINT'));
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('unhandledRejection', (error) => console.error('[process] Unhandled rejection:', error));
process.on('uncaughtException', (error) => {
    console.error('[process] Uncaught exception:', error);
    void shutdown('uncaughtException');
});

fs.mkdirSync(savedEmbedsPath, { recursive: true });
fs.mkdirSync(avatarsPath, { recursive: true });

discordClient.login(config.discord.token).catch((error) => {
    console.error('[discord] Login failed:', error);
    process.exitCode = 1;
});

connectIRC();
