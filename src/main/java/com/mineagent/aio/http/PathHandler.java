// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.mineagent.aio.http;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

/**
 * Serves every request below one mounted path prefix.
 *
 * <p>Called on the HTTP worker threads, never on the game thread: implementations that need the
 * game state hop onto {@code Minecraft.execute} themselves.</p>
 */
@FunctionalInterface
public interface PathHandler {
	/**
	 * @param exchange the HTTP exchange; the server closes it after this method returns
	 * @param path     the request path with the mounted prefix removed, always starting with
	 *                 {@code '/'} — a request to the prefix itself arrives as {@code "/"}
	 */
	void handle(HttpExchange exchange, String path) throws IOException;
}
