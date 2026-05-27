'use strict';

const fs = require('fs');
const path = require('path');

const targetPath = path.join(__dirname, 'yuri.js');

if (!fs.existsSync(targetPath)) {
    console.error('Could not find yuri.js in this directory.');
    process.exit(1);
}

let src = fs.readFileSync(targetPath, 'utf8');

const backupPath = path.join(
    __dirname,
    `yuri.js.backup-${new Date().toISOString().replace(/[:.]/g, '-')}`
);

fs.writeFileSync(backupPath, src, 'utf8');
console.log(`Backup written: ${backupPath}`);

function replaceBetween(source, startMarker, endMarker, replacement, label) {
    const start = source.indexOf(startMarker);
    if (start === -1) {
        throw new Error(`Could not find start marker for ${label}: ${startMarker}`);
    }

    const end = source.indexOf(endMarker, start);
    if (end === -1) {
        throw new Error(`Could not find end marker for ${label}: ${endMarker}`);
    }

    return source.slice(0, start) + replacement + source.slice(end);
}

const messageAndActionHandlers = String.raw`
ircClient.on('message', async (event) => {
    const plainIrcMessage = stripIrcFormatting(event.message);
    let ircMessage = ircToDiscordBridgeMessage(event.message);
    const sender = event.nick;

    // Check if the message type is 'action' to avoid handling it twice
    if (event.type === 'action') {
        return;
    }

    if (plainIrcMessage.startsWith('!adduser')) {
        const [, nickname] = plainIrcMessage.split(' ');

        if (!nickname) {
            ircClient.say(event.target, 'Usage: !adduser nickname');
            return;
        }

        addRegisteredIRCUser(nickname);
        ircClient.say(event.target, \`User \${nickname} has been added to the registered users list.\`);
        saveConfig();
        return;
    }

    if (plainIrcMessage.startsWith('!deluser')) {
        const [, nickname] = plainIrcMessage.split(' ');

        if (!nickname) {
            ircClient.say(event.target, 'Usage: !deluser nickname');
            return;
        }

        if (config.irc.registeredUsers.includes(nickname)) {
            config.irc.registeredUsers = config.irc.registeredUsers.filter(user => user !== nickname);
            saveConfig();
            ircClient.say(event.target, \`User \${nickname} has been removed from the registered users list.\`);
        } else {
            ircClient.say(event.target, \`User \${nickname} is not in the registered users list.\`);
        }

        return;
    }

    if (plainIrcMessage.startsWith('!setmyavatar')) {
        const [, avatarUrl] = plainIrcMessage.split(' ');
        const nick = sender;

        if (!avatarUrl) {
            ircClient.say(event.target, 'Usage: !setmyavatar image_url');
            return;
        }

        const avatarFileName = \`\${nick}.*\`;
        const avatarPath = path.join(__dirname, 'avatars');
        const matchingFiles = glob.sync(path.join(avatarPath, avatarFileName));

        matchingFiles.forEach(file => {
            fs.unlinkSync(file);
            console.log(\`Deleted existing avatar file: \${file}\`);
        });

        try {
            const response = await axios.head(avatarUrl);
            const contentType = response.headers['content-type'];

            if (!contentType || !contentType.startsWith('image')) {
                ircClient.say(event.target, 'Invalid image URL');
                return;
            }

            const imageResponse = await axios.get(avatarUrl, {
                responseType: 'arraybuffer'
            });

            const fileExtension = mime.extension(contentType);
            const filePath = path.join(__dirname, 'avatars', \`\${nick}.\${fileExtension}\`);

            fs.writeFileSync(filePath, imageResponse.data);
            ircClient.say(event.target, 'Avatar downloaded and set successfully');
        } catch (error) {
            ircClient.say(event.target, 'Error downloading or saving the avatar');
        }

        return;
    }

    if (plainIrcMessage.startsWith('!link')) {
        if (config.irc.registeredUsers.includes(sender)) {
            const [, ircChannel, discordChannelID, showMoreInfo = 'false'] = plainIrcMessage.split(' ');

            if (!ircChannel || !ircChannel.startsWith('#') || !discordChannelID || !/^\\d+$/.test(discordChannelID)) {
                ircClient.say(event.target, 'Usage: !link #IRCChannel DiscordChannelID [showMoreInfo]');
                return;
            }

            channelMappings[ircChannel.toLowerCase()] = {
                discordChannelID,
                showMoreInfo: showMoreInfo.toLowerCase() === 'true'
            };

            ircClient.join(ircChannel);
            saveConfig();

            ircClient.say(
                event.target,
                \`Linked Discord channel \${discordChannelID} to IRC channel \${ircChannel} with showMoreInfo set to \${showMoreInfo}\`
            );
        } else {
            ircClient.say(event.target, 'Permission denied');
        }

        return;
    }

    if (plainIrcMessage.startsWith('!unlink')) {
        if (config.irc.registeredUsers.includes(sender)) {
            const [, ircChannel] = plainIrcMessage.split(' ');

            if (!ircChannel || !ircChannel.startsWith('#')) {
                ircClient.say(event.target, 'Usage: !unlink #IRCChannel');
                return;
            }

            if (channelMappings.hasOwnProperty(ircChannel.toLowerCase())) {
                delete channelMappings[ircChannel.toLowerCase()];
                ircClient.part(ircChannel);
                saveConfig();
                ircClient.say(event.target, \`Unlinked IRC channel \${ircChannel}\`);
            } else {
                ircClient.say(event.target, \`No mapping found for IRC channel \${ircChannel}\`);
            }
        } else {
            ircClient.say(event.target, 'Permission denied');
        }

        return;
    }

    if (plainIrcMessage.startsWith('!update')) {
        if (config.irc.registeredUsers.includes(sender)) {
            exec('git pull', (error, stdout, stderr) => {
                if (error) {
                    console.error(\`Error during git pull: \${error.message}\`);
                    ircClient.say(event.target, \`Error during git pull: \${error.message}\`);
                    return;
                }

                if (stdout.includes('Already up to date.')) {
                    exec('git rev-parse HEAD', (error2, stdout2, stderr2) => {
                        if (!error2) {
                            const commitHash = stdout2.trim();
                            ircClient.say(event.target, \`Bot is already up to date (Commit: \${commitHash}).\`);
                        } else {
                            console.error(\`Error getting commit hash: \${error2.message}\`);
                            ircClient.say(event.target, \`Error getting commit hash: \${error2.message}\`);
                        }
                    });
                } else {
                    ircClient.say(event.target, 'Bot has been updated. Relaunching...');
                    process.exit(0);
                }
            });
        } else {
            ircClient.say(event.target, 'Permission denied');
        }

        return;
    }

    if (plainIrcMessage.startsWith('!showmoreinfo')) {
        if (config.irc.registeredUsers.includes(sender)) {
            const [, showMoreInfoArg] = plainIrcMessage.split(' ');

            if (
                showMoreInfoArg !== undefined &&
                (showMoreInfoArg.toLowerCase() === 'true' || showMoreInfoArg.toLowerCase() === 'false')
            ) {
                const showMoreInfo = showMoreInfoArg.toLowerCase() === 'true';

                const mappedCurrentChannel = channelMappings[event.target.toLowerCase()];
                if (mappedCurrentChannel) {
                    mappedCurrentChannel.showMoreInfo = showMoreInfo;
                    saveConfig();
                    ircClient.say(event.target, \`Set showMoreInfo to \${showMoreInfo}\`);
                } else {
                    ircClient.say(event.target, 'Error: Unable to find the corresponding Discord channel for this IRC channel.');
                }
            } else {
                ircClient.say(event.target, 'Invalid argument. Please use \`true\` or \`false\` after the command.');
            }
        } else {
            ircClient.say(event.target, 'Permission denied');
        }

        return;
    }

    const mappedChannel = channelMappings[event.target.toLowerCase()];

    if (!mappedChannel) {
        return;
    }

    if (!isDiscordAnsiCodeblock(ircMessage)) {
        ircMessage = replaceIrcMentionsForDiscord(ircMessage, mappedChannel);
    }

    const mappedDiscordChannelID = mappedChannel.discordChannelID;
    const discordChannel = discordClient.channels.cache.get(mappedDiscordChannelID);

    if (!discordChannel) {
        console.error(\`No Discord channel found for ID: \${mappedDiscordChannelID}\`);
        return;
    }

    const yuriWebhook = await getWebhook(discordChannel);
    sendMessageToDiscord(yuriWebhook, sender, ircMessage);
});


ircClient.on('action', async (event) => {
    let ircMessage = ircToDiscordBridgeMessage(event.message);
    const sender = event.nick;

    const mappedChannel = channelMappings[event.target.toLowerCase()];

    if (!mappedChannel) {
        return;
    }

    if (!isDiscordAnsiCodeblock(ircMessage)) {
        ircMessage = replaceIrcMentionsForDiscord(ircMessage, mappedChannel);
        ircMessage = \`_\${ircMessage}_\`;
    }

    const mappedDiscordChannelID = mappedChannel.discordChannelID;
    const discordChannel = discordClient.channels.cache.get(mappedDiscordChannelID);

    if (!discordChannel) {
        console.error(\`No Discord channel found for ID: \${mappedDiscordChannelID}\`);
        return;
    }

    const yuriWebhook = await getWebhook(discordChannel);
    sendMessageToDiscord(yuriWebhook, sender, ircMessage);
});


`;

src = replaceBetween(
    src,
    "ircClient.on('message', async (event) => {",
    "ircClient.on('wholist', (event) => {",
    messageAndActionHandlers,
    'IRC message/action handlers'
);

const formattingHelpers = String.raw`
function isDiscordAnsiCodeblock(message) {
    return String(message || '').startsWith('```ansi\n');
}

function replaceIrcMentionsForDiscord(message, mappedChannel) {
    const ircMentionRegex = /@(\\w+)/g;

    return String(message || '').replace(ircMentionRegex, (match, username) => {
        if (!mappedChannel || !mappedChannel.discordChannelID) {
            return match;
        }

        const channel = discordClient.channels.cache.get(mappedChannel.discordChannelID);

        if (!channel || !channel.guild) {
            return match;
        }

        const guildId = channel.guild.id;
        const guild = discordClient.guilds.cache.get(guildId);

        if (!guild) {
            return match;
        }

        const normalizedUsername = username.toLowerCase();

        const member = guild.members.cache.find(guildMember => {
            const accountUsername = guildMember.user?.username || '';
            const accountTag = guildMember.user?.tag || '';
            const displayName = guildMember.displayName || '';
            const nickname = guildMember.nickname || '';

            return (
                accountUsername.toLowerCase() === normalizedUsername ||
                displayName.toLowerCase() === normalizedUsername ||
                nickname.toLowerCase() === normalizedUsername ||
                accountTag.toLowerCase() === normalizedUsername
            );
        });

        if (member) {
            return \`<@\${member.id}>\`;
        }

        return match;
    });
}

function hasIrcColorFormatting(message) {
    return /(?:\\x03(?:\\d{1,2}(?:,\\d{1,2})?|,\\d{1,2})|\\x04[0-9A-Fa-f]{6})/.test(String(message || ''));
}

function hasIrcFormatting(message) {
    return /[\\x02\\x03\\x04\\x0F\\x16\\x1D\\x1F\\x1E]/.test(String(message || ''));
}

function stripIrcFormatting(message) {
    return String(message || '')
        .replace(/\\x03(?:\\d{1,2}(?:,\\d{1,2})?)?/g, '')
        .replace(/\\x04(?:[0-9A-Fa-f]{6}(?:,[0-9A-Fa-f]{6})?)?/g, '')
        .replace(/[\\x02\\x0F\\x16\\x1D\\x1F\\x1E]/g, '');
}

function removeColorCodes(message) {
    return stripIrcFormatting(message);
}

function escapeDiscordAnsiCodeblock(text) {
    return String(text || '')
        .replace(/\`\`\`/g, '\`\\u200b\`\`')
        .replace(/\\x1b/g, '');
}

const IRC_TO_ANSI_FG = {
    0: 37,
    1: 30,
    2: 34,
    3: 32,
    4: 31,
    5: 33,
    6: 35,
    7: 33,
    8: 33,
    9: 32,
    10: 36,
    11: 36,
    12: 34,
    13: 35,
    14: 37,
    15: 37
};

const IRC_TO_ANSI_BG = {
    0: 47,
    1: 40,
    2: 44,
    3: 42,
    4: 41,
    5: 41,
    6: 45,
    7: 43,
    8: 43,
    9: 42,
    10: 46,
    11: 46,
    12: 44,
    13: 45,
    14: 40,
    15: 47
};

function readIrcColorNumber(message, index) {
    const remaining = message.slice(index);
    const match = remaining.match(/^\\d{1,2}/);

    if (!match) {
        return { value: null, length: 0 };
    }

    if (match[0].length >= 2) {
        const twoDigit = parseInt(match[0].slice(0, 2), 10);
        if (twoDigit >= 0 && twoDigit <= 15) {
            return { value: twoDigit, length: 2 };
        }
    }

    const oneDigit = parseInt(match[0][0], 10);

    if (oneDigit >= 0 && oneDigit <= 15) {
        return { value: oneDigit, length: 1 };
    }

    return { value: null, length: 0 };
}

function ansiSequence(state) {
    const codes = [];

    if (state.bold) {
        codes.push(1);
    }

    if (state.underline) {
        codes.push(4);
    }

    if (state.fg !== null && state.fg !== undefined) {
        codes.push(state.fg);
    }

    if (state.bg !== null && state.bg !== undefined) {
        codes.push(state.bg);
    }

    if (!codes.length) {
        return '\\x1b[0m';
    }

    return \`\\x1b[\${codes.join(';')}m\`;
}

function ircToDiscordAnsiCodeblock(message) {
    message = escapeDiscordAnsiCodeblock(message);

    let out = '';
    const state = {
        bold: false,
        underline: false,
        fg: null,
        bg: null
    };

    function emitState() {
        out += ansiSequence(state);
    }

    for (let i = 0; i < message.length; i++) {
        const ch = message[i];

        if (ch === '\\x02') {
            state.bold = !state.bold;
            emitState();
            continue;
        }

        if (ch === '\\x1F') {
            state.underline = !state.underline;
            emitState();
            continue;
        }

        if (ch === '\\x1D') {
            continue;
        }

        if (ch === '\\x16') {
            const oldFg = state.fg;
            state.fg = state.bg;
            state.bg = oldFg;
            emitState();
            continue;
        }

        if (ch === '\\x0F') {
            state.bold = false;
            state.underline = false;
            state.fg = null;
            state.bg = null;
            out += '\\x1b[0m';
            continue;
        }

        if (ch === '\\x03') {
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

        if (ch === '\\x04') {
            const hexMatch = message.slice(i + 1).match(/^[0-9A-Fa-f]{6}(?:,[0-9A-Fa-f]{6})?/);
            if (hexMatch) {
                i += hexMatch[0].length;
            }
            continue;
        }

        out += ch;
    }

    out += '\\x1b[0m';

    return \`\`\`ansi
\${out}
\`\`\`\`;
}

function ircToDiscordBridgeMessage(message) {
    if (hasIrcColorFormatting(message)) {
        return ircToDiscordAnsiCodeblock(message);
    }

    return ircToDiscordMarkdown(message);
}

function ircToDiscordMarkdown(message) {
    const ircToDiscord = {
        '\\x02': '**',
        '\\x1F': '__',
        '\\x1D': '*'
    };

    return String(message || '')
        .replace(/\\x02|\\x1F|\\x1D/g, match => ircToDiscord[match] || '')
        .replace(/\\x03(?:\\d{1,2}(?:,\\d{1,2})?)?/g, '')
        .replace(/\\x04(?:[0-9A-Fa-f]{6}(?:,[0-9A-Fa-f]{6})?)?/g, '')
        .replace(/[\\x0F\\x16\\x1E]/g, '');
}
`;

const helperStartCandidates = [
    'function hasIrcColorFormatting(message) {',
    'function removeColorCodes(message) {'
];

let helperStart = -1;
let helperMarker = '';

for (const marker of helperStartCandidates) {
    const idx = src.indexOf(marker);
    if (idx !== -1 && (helperStart === -1 || idx < helperStart)) {
        helperStart = idx;
        helperMarker = marker;
    }
}

if (helperStart === -1) {
    throw new Error('Could not find IRC formatting helper section.');
}

src = src.slice(0, helperStart) + formattingHelpers.trimEnd() + '\n';

fs.writeFileSync(targetPath, src, 'utf8');

console.log('Patched yuri.js successfully.');
console.log('Run this to sanity check syntax:');
console.log('  node --check yuri.js');
