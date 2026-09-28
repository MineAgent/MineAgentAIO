// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.mineagent.aio.ctl;

import com.mineagent.aio.http.Http;
import com.mineagent.aio.http.Httpd;
import com.mineagent.aio.http.PathHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The input side of MineAgentAIO (from mcctl), mounted under {@code /ctl}.
 *
 * <ul>
 *   <li>{@code GET /ctl/} returns the manual.</li>
 *   <li>{@code POST /ctl/} parses the body and queues the commands.</li>
 *   <li>{@code GET /ctl/prtsc} returns the current frame as PNG.</li>
 *   <li>{@code GET /ctl/mouse} returns the cursor position.</li>
 * </ul>
 *
 * <p>The HTTP server itself (127.0.0.1:3420) belongs to {@link Httpd}; this class only serves the
 * paths it is handed below the prefix, so {@code /prtsc} here means {@code /ctl/prtsc}.</p>
 */
public final class ControlEndpoint implements PathHandler {
	public static final String PREFIX = "/ctl";
	public static final String NAME = "MineAgentAIO /ctl — 客户端远程控制 (按键/鼠标/视角/截图/Baritone)";

	/** What the server's {@code GET /} index lists for this part. */
	public static final List<Httpd.Endpoint> ENDPOINTS = List.of(
			new Httpd.Endpoint("GET", "/ctl/", "使用说明"),
			new Httpd.Endpoint("POST", "/ctl/", "执行命令 (text/plain, UTF-8)"),
			new Httpd.Endpoint("GET", "/ctl/prtsc", "当前帧 PNG (别名 /ctl/screenshot)"),
			new Httpd.Endpoint("GET", "/ctl/mouse", "当前光标位置 (窗口像素 + GUI 缩放)"));

	private static final Logger LOG = Logger.getLogger("mineagentaio");
	private static final int MAX_BODY_BYTES = 64 * 1024;

	private final CommandRunner runner;
	private final InputExecutor executor;

	public ControlEndpoint(CommandRunner runner, InputExecutor executor) {
		this.runner = runner;
		this.executor = executor;
	}

	@Override
	public void handle(HttpExchange exchange, String path) throws IOException {
		try {
			String method = exchange.getRequestMethod();

			if (isScreenshotPath(path)) {
				if (!"GET".equals(method) && !"HEAD".equals(method) && !"POST".equals(method)) {
					exchange.getResponseHeaders().set("Allow", "GET, HEAD, POST, OPTIONS");
					Http.respondText(exchange, 405, "method not allowed: " + method + "\n");
					return;
				}
				handleScreenshot(exchange);
				return;
			}

			if (isMousePath(path)) {
				if (!"GET".equals(method) && !"HEAD".equals(method) && !"POST".equals(method)) {
					exchange.getResponseHeaders().set("Allow", "GET, HEAD, POST, OPTIONS");
					Http.respondText(exchange, 405, "method not allowed: " + method + "\n");
					return;
				}
				handleMouse(exchange);
				return;
			}

			switch (method) {
				case "GET", "HEAD" -> Http.respondText(exchange, 200, Help.text());
				case "POST" -> handlePost(exchange);
				case "OPTIONS" -> {
					exchange.getResponseHeaders().set("Allow", "GET, HEAD, POST, OPTIONS");
					Http.respondText(exchange, 204, "");
				}
				default -> {
					exchange.getResponseHeaders().set("Allow", "GET, HEAD, POST, OPTIONS");
					Http.respondText(exchange, 405,
							"method not allowed: " + method + " (use GET for help, POST for commands)\n");
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

	private static boolean isScreenshotPath(String path) {
		return "/prtsc".equals(path) || "/prtsc.png".equals(path)
				|| "/screenshot".equals(path) || "/screenshot.png".equals(path);
	}

	private static boolean isMousePath(String path) {
		return "/mouse".equals(path) || "/mouse.txt".equals(path) || "/cursor".equals(path);
	}

	/** {@code GET /ctl/mouse}: where the cursor is right now (window pixels + GUI-scaled units). */
	private void handleMouse(HttpExchange exchange) throws IOException {
		if (!executor.isReady()) {
			Http.respondText(exchange, 409, "game not ready: " + executor.unavailableReason() + "\n");
			return;
		}

		String mouse;
		try {
			mouse = executor.mousePosition();
		} catch (RuntimeException e) {
			LOG.log(Level.WARNING, "mouse position failed", e);
			Http.respondText(exchange, 500, "mouse position failed: " + e + "\n");
			return;
		}

		Http.respondText(exchange, 200, mouse == null ? "" : mouse);
	}

	/** {@code GET /ctl/prtsc}: capture the current frame and hand it back as a PNG. */
	private void handleScreenshot(HttpExchange exchange) throws IOException {
		if (!executor.isReady()) {
			Http.respondText(exchange, 409, "game not ready: " + executor.unavailableReason() + "\n");
			return;
		}

		byte[] png;
		try {
			png = executor.captureScreenshot();
		} catch (Exception e) {
			LOG.log(Level.WARNING, "screenshot failed", e);
			Http.respondText(exchange, 500, "screenshot failed: " + e + "\n");
			return;
		}

		if (png == null || png.length == 0) {
			Http.respondText(exchange, 500, "screenshot failed: empty image\n");
			return;
		}

		exchange.getResponseHeaders().set("Content-Disposition",
				"inline; filename=\"mineagentaio-screenshot.png\"");
		Http.respond(exchange, 200, "image/png", png);
	}

	private void handlePost(HttpExchange exchange) throws IOException {
		String body = new String(Http.readBody(exchange, MAX_BODY_BYTES), StandardCharsets.UTF_8);

		List<Action> plan;
		try {
			plan = CommandParser.parse(body);
		} catch (CommandException e) {
			Http.respondText(exchange, 400, "bad command: " + e.getMessage() + "\n\n" + Help.text());
			return;
		}

		if (!executor.isReady()) {
			Http.respondText(exchange, 409, "game not ready: " + executor.unavailableReason() + "\n");
			return;
		}

		if (needsBaritone(plan)) {
			// bt / # commands must never fall back to a chat message: without Baritone the whole
			// request fails up front and nothing is queued.
			String reason = executor.baritoneUnavailableReason();
			if (reason != null) {
				Http.respondText(exchange, 400, "bt failed: " + reason + "\n");
				return;
			}
		}

		if (needsTextBox(plan)) {
			// Typing reports an outcome (no text box -> 400), so that request runs synchronously on
			// the usual single worker: the actions keep their order and we wait for the result.
			String failure = runner.submitAndWait(plan, waitBudgetMs(plan));

			if (failure != null) {
				Http.respondText(exchange, 400, "type failed: " + failure + "\n");
				return;
			}
		} else {
			runner.submit(plan);
		}

		StringBuilder json = new StringBuilder();
		json.append("{\"ok\":true,\"queued\":").append(runner.pending())
				.append(",\"inWorld\":").append(executor.inWorld())
				.append(",\"actions\":[");
		for (int i = 0; i < plan.size(); i++) {
			if (i > 0) {
				json.append(',');
			}
			json.append('"').append(Http.escapeJson(plan.get(i).describe())).append('"');
		}
		json.append("]}\n");

		Http.respond(exchange, 200, "application/json; charset=utf-8", json.toString());
	}

	/** True when the plan runs a Baritone command, i.e. it needs Baritone to be installed. */
	private static boolean needsBaritone(List<Action> plan) {
		for (Action action : plan) {
			if (action.kind() == Action.Kind.BARITONE) {
				return true;
			}
		}
		return false;
	}

	/** True when the plan types into a text box, i.e. it has an outcome worth reporting. */
	private static boolean needsTextBox(List<Action> plan) {
		for (Action action : plan) {
			if (action.kind() == Action.Kind.TYPE_TEXT || action.kind() == Action.Kind.TYPE_ENTER) {
				return true;
			}
		}
		return false;
	}

	/** How long the HTTP thread is willing to wait for a typing request (its own holds + 5 s). */
	private static long waitBudgetMs(List<Action> plan) {
		long budget = 5_000L;
		for (Action action : plan) {
			budget += action.holdMs() + action.delayMs();
		}
		return Math.min(budget, 120_000L);
	}
}
