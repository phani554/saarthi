// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.channel

/**
 * Product scope switch (2026-11-14): Saathi works only on this phone (WhatsApp, other installed
 * apps, quick-commerce apps). Remote-control channels (Telegram, Discord, WeChat bots) are switched
 * off: they never start and their settings are hidden. The code is kept so it still compiles and
 * can be turned back on by flipping this one flag.
 *
 * The LOCAL channel (in-app chat) is not affected: it never had a handler, so
 * ChannelManager.sendMessage(Channel.LOCAL, ...) stays a harmless no-op as before.
 */
object ChannelFeatures {
    const val REMOTE_CHANNELS_ENABLED = false
}
