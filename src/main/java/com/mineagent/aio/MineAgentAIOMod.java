// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.mineagent.aio;

import com.mineagent.aio.aif.InfoEndpoint;
import com.mineagent.aio.aif.PlayerInfoProvider;
import com.mineagent.aio.ctl.CommandRunner;
import com.mineagent.aio.ctl.ControlEndpoint;
import com.mineagent.aio.ctl.McInputExecutor;
import com.mineagent.aio.http.Httpd;
import com.mineagent.aio.op.OpEndpoint;
import net.fabricmc.api.ClientModInitializer;

/**
 * The single client entrypoint of MineAgentAIO.
 *
 * <p>It mounts the three endpoint groups on the mod's own HTTP server and starts it:</p>
 *
 * <ul>
 *   <li>{@code /ctl} — input: keyboard, mouse, camera, screenshots, chat, {@code bt} (mcctl).</li>
 *   <li>{@code /aif} — read-only state: position, inventory, world, chat and sounds
 *       (AdvancedInfoFetcher).</li>
 *   <li>{@code /op} — containers: craft, hotbar, furnace, chest, look (cmdCraft).</li>
 * </ul>
 *
 * <p>Before the merge each of those registered itself on the MGHttpdProvider mod and the ordering
 * between entrypoints had to be handled; now a single entrypoint does all of it in order, and the
 * server is a plain part of this mod rather than a separate jar.</p>
 *
 * <p>No Minecraft class is touched here: the executors resolve {@code Minecraft.getInstance()}
 * lazily on first use, which keeps startup safe.</p>
 */
public final class MineAgentAIOMod implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// /ctl — the input side (mcctl)
		McInputExecutor executor = new McInputExecutor();
		CommandRunner runner = new CommandRunner(executor);
		Httpd.mount(ControlEndpoint.PREFIX, ControlEndpoint.NAME, ControlEndpoint.ENDPOINTS,
				new ControlEndpoint(runner, executor));

		// /aif — the read-only side (AdvancedInfoFetcher)
		Httpd.mount(InfoEndpoint.PREFIX, InfoEndpoint.NAME, InfoEndpoint.ENDPOINTS,
				new InfoEndpoint(new PlayerInfoProvider()));

		// /op — the container side (cmdCraft)
		Httpd.mount(OpEndpoint.PREFIX, OpEndpoint.NAME, OpEndpoint.ENDPOINTS, new OpEndpoint());

		MineAgentAIO.LOGGER.info("MineAgentAIO {} starting", MineAgentAIO.version());

		// The mixins own the per-tick work (craft jobs, noautopause); this is the teardown: release
		// anything the input layer may still be holding, stop the HTTP server, and end the JVM once
		// the render thread is gone so the post-main watchdog cannot fire.
		//
		// It is installed *before* the server starts, and no matter whether it starts at all: the
		// watchdog fires whenever any non-daemon thread is left behind, and this mod is not the only
		// source of those. The realistic case where binding fails is a second game instance, and
		// that instance still needs a clean exit (Baritone keeps worker threads of its own).
		Runnable teardown = () -> {
			executor.releaseAll();
			runner.shutdown();
			Httpd.stop();
		};
		ClientExitWatcher.onClientExit(teardown);
		// Still tear down on a real JVM shutdown (crash, SIGTERM, System.exit, ...).
		Runtime.getRuntime().addShutdownHook(new Thread(teardown, "mineagentaio-shutdown"));

		if (!Httpd.start()) {
			MineAgentAIO.LOGGER.error("MineAgentAIO is not listening on http://{}:{} - the game runs,"
					+ " but no endpoint is reachable (is another instance already running?)",
					Httpd.HOST, Httpd.PORT);
		}
	}
}
