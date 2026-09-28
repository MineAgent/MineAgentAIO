// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.mineagent.aio;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Identity of the mod: the id, the logger every part of it shares, and the version that is reported
 * by {@code GET :3420/} (taken from the mod metadata, so it cannot drift from the build).
 */
public final class MineAgentAIO {
	public static final String MOD_ID = "mineagentaio";
	public static final String NAME = "MineAgentAIO";

	/** Shared logger; the whole mod logs under one name so {@code grep mineagentaio} finds it all. */
	public static final Logger LOGGER = LoggerFactory.getLogger(NAME);

	private MineAgentAIO() {
	}

	/** @return the mod version from {@code fabric.mod.json}, or {@code "unknown"} outside the game */
	public static String version() {
		return FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");
	}
}
