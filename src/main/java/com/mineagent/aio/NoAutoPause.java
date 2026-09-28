// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.mineagent.aio;

import net.minecraft.client.Minecraft;

import java.util.logging.Logger;

/**
 * Turns off Minecraft's automatic pausing, so the client keeps ticking while its window is in the
 * background — an agent controlled over HTTP needs the game to run when nobody is looking at it.
 *
 * <p>Merged from <a href="https://github.com/Armandukx/noautopause">noautopause</a> 1.0.2 by
 * Armandukx (MIT / CC0-1.0, see {@code NOTICE.md}). Upstream registers a Fabric API client tick
 * event that writes {@code Minecraft.options.pauseOnLostFocus = false} once; this mod does not use
 * the Fabric API, so the same work runs from {@code MinecraftMixin#tick} instead. The behaviour is
 * unchanged: only the "pause when the window loses focus" flag is touched, so opening a menu in a
 * single-player world still pauses the game the vanilla way.</p>
 */
public final class NoAutoPause {
	private static final Logger LOG = Logger.getLogger("mineagentaio");

	/** Set once, like upstream's {@code AppliedSetting} flag. */
	private static boolean applied;

	private NoAutoPause() {
	}

	/** Called from the client tick (see {@code MinecraftMixin}); does nothing after the first call. */
	public static void tick(Minecraft minecraft) {
		if (applied || minecraft == null || minecraft.options == null) {
			return;
		}

		minecraft.options.pauseOnLostFocus = false;
		applied = true;
		LOG.info("noautopause: pauseOnLostFocus=false (the client keeps running while unfocused)");
	}
}
