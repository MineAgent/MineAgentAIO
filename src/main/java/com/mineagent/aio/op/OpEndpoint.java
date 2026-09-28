/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Copyright (C) 2026 MineAgent
 */

package com.mineagent.aio.op;

import com.mineagent.aio.http.Http;
import com.mineagent.aio.http.Httpd;
import com.mineagent.aio.http.PathHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecraft.client.Minecraft;

/**
 * The container side of MineAgentAIO (from cmdCraft), mounted under {@code /op}.
 *
 * <ul>
 *   <li>{@code GET /op/} returns the manual.</li>
 *   <li>{@code POST /op/} runs one command (the request body) and returns its outcome.</li>
 * </ul>
 *
 * <p>The HTTP server itself (127.0.0.1:3420) belongs to {@link Httpd}; this class only serves the
 * paths below the prefix, so {@code "/"} here means {@code /op}.</p>
 *
 * <p>Requests arrive on HTTP worker threads. Every command is hopped onto the client thread through
 * {@link Minecraft#execute} and the worker waits for the outcome, so a response always describes
 * what really happened: {@code 200} for success, {@code 400} for a rejected command, {@code 409}
 * when no world is loaded. Crafting keeps clicking over several client ticks; that request stays
 * open until the craft job reports back.</p>
 */
public final class OpEndpoint implements PathHandler {
	public static final String PREFIX = "/op";
	public static final String NAME = "MineAgentAIO /op — 客户端容器操作 (合成/背包/熔炉/箱子/转向)";

	/** What the server's {@code GET /} index lists for this part. */
	public static final List<Httpd.Endpoint> ENDPOINTS = List.of(
			new Httpd.Endpoint("GET", "/op/", "使用说明"),
			new Httpd.Endpoint("POST", "/op/", "执行命令 (text/plain, UTF-8)"));

	private static final Logger LOG = Logger.getLogger("mineagentaio");
	private static final int MAX_BODY_BYTES = 16 * 1024;
	/** How long to wait for the command to even reach the client thread. */
	private static final long DISPATCH_WAIT_MS = 15_000L;
	/** Additional time a running craft job gets before the request is answered with 504. */
	private static final long CRAFT_WAIT_MS = 120_000L;

	@Override
	public void handle(HttpExchange exchange, String path) throws IOException {
		try {
			if (!"/".equals(path)) {
				Http.respondText(exchange, 404, "no endpoint at /op" + path + "\n\n" + Help.text());
				return;
			}

			switch (exchange.getRequestMethod()) {
				case "GET", "HEAD" -> Http.respondText(exchange, 200, Help.text());
				case "POST" -> handlePost(exchange);
				case "OPTIONS" -> {
					exchange.getResponseHeaders().set("Allow", "GET, HEAD, POST, OPTIONS");
					Http.respondText(exchange, 204, "");
				}
				default -> {
					exchange.getResponseHeaders().set("Allow", "GET, HEAD, POST, OPTIONS");
					Http.respondText(exchange, 405, "method not allowed: "
							+ exchange.getRequestMethod()
							+ " (use GET for the manual, POST to run a command)\n");
				}
			}
		} catch (Http.BodyTooLargeException e) {
			Http.respondText(exchange, 413,
					"request body too large (max " + MAX_BODY_BYTES + " bytes)\n");
		} catch (Exception e) {
			LOG.log(Level.WARNING, "request failed", e);
			Http.respondText(exchange, 500, "internal error: " + e + "\n");
		}
		// the server closes the exchange
	}

	/** {@code POST /op/}: run the body as one command and answer with its outcome. */
	private void handlePost(HttpExchange exchange) throws IOException {
		String body = new String(Http.readBody(exchange, MAX_BODY_BYTES), StandardCharsets.UTF_8).trim();
		String line = normalize(body);

		if (line.isEmpty()) {
			Http.respondText(exchange, 400, "empty request body\n\n" + Help.text());
			return;
		}

		Minecraft client = Minecraft.getInstance();

		if (client == null) {
			Http.respondText(exchange, 409, "game not ready: the Minecraft client is not running\n");
			return;
		}

		OpCommandSource source = new OpCommandSource();
		Runnable task = () -> OpCommands.dispatch(line, source, client);

		try {
			if (client.isSameThread()) {
				task.run();
			} else {
				client.execute(task);
			}
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "could not queue the command", e);
			Http.respondText(exchange, 503, "could not queue the command: " + e + "\n");
			return;
		}

		try {
			boolean finished = source.await(DISPATCH_WAIT_MS);

			if (!finished && source.isAsync()) {
				// A craft job is clicking its way through the grid; wait for it to report back.
				finished = source.await(CRAFT_WAIT_MS);
			}

			if (!finished) {
				Http.respondText(exchange, 504,
						"timed out waiting for the client (the command may still be running)\n" + source.text());
				return;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			Http.respondText(exchange, 503, "interrupted\n");
			return;
		}

		String text = source.text();

		if (source.notReady()) {
			Http.respondText(exchange, 409, text);
			return;
		}

		if (source.failed()) {
			Http.respondText(exchange, 400, text);
			return;
		}

		Http.respondText(exchange, 200, text.isEmpty() ? "ok\n" : text);
	}

	/**
	 * Accepts the body with a little slack: surrounding whitespace, a leading {@code /} and a legacy
	 * {@code cmdop} prefix are dropped, so {@code craft stick 4}, {@code /craft stick 4} and
	 * {@code /cmdop craft stick 4} all mean the same thing.
	 */
	private static String normalize(String body) {
		String line = body.trim();

		while (line.startsWith("/")) {
			line = line.substring(1).trim();
		}

		if (startsWithWord(line, "cmdop")) {
			line = line.substring("cmdop".length()).trim();
		}

		return line;
	}

	private static boolean startsWithWord(String text, String word) {
		return text.regionMatches(true, 0, word, 0, word.length())
				&& (text.length() == word.length() || Character.isWhitespace(text.charAt(word.length())));
	}
}
