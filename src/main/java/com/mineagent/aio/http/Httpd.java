// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.mineagent.aio.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The one HTTP server of MineAgentAIO, bound to {@code 127.0.0.1:3420}.
 *
 * <p>Each part of the mod mounts a path prefix ({@code /ctl} for the input side, {@code /aif} for
 * the read-only info side, {@code /op} for the container side) and serves everything below it;
 * {@code GET /} lists what is mounted. All three live in this mod, so mounting happens once from
 * {@link com.mineagent.aio.MineAgentAIOMod} and {@link #start()} is called right after — there is
 * no cross-mod startup ordering to get right and no separate library jar.</p>
 *
 * <p>This replaces the standalone MGHttpdProvider mod: same 3420 endpoint, same prefix routing,
 * same {@code GET /} index, but plain classes inside this mod instead of a compileOnly API jar.</p>
 */
public final class Httpd {
	public static final String HOST = "127.0.0.1";
	public static final int PORT = 3420;

	private static final Logger LOG = Logger.getLogger("mineagentaio");

	private static final Map<String, Mount> MOUNTS = new TreeMap<>();

	private static HttpServer server;
	private static ExecutorService pool;

	private Httpd() {
	}

	/** One line of the {@code GET /} index. */
	public record Endpoint(String method, String path, String description) {
	}

	private record Mount(String prefix, String name, List<Endpoint> endpoints, PathHandler handler) {
	}

	/**
	 * Registers everything below {@code prefix} (for example {@code /ctl}).
	 *
	 * @param prefix    path prefix, with or without a trailing slash
	 * @param name      human readable owner shown in the {@code GET /} index
	 * @param endpoints the owner's endpoints, for the index
	 * @param handler   serves every request below {@code prefix}
	 */
	public static synchronized void mount(String prefix, String name, List<Endpoint> endpoints,
			PathHandler handler) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(handler, "handler");
		String normalized = normalize(prefix);

		Mount mount = new Mount(normalized, name, List.copyOf(endpoints), handler);
		MOUNTS.put(normalized, mount);

		if (server != null) {
			server.createContext(normalized, exchange -> dispatch(mount, exchange));
		}
		LOG.info("mounted " + normalized + " (" + name + ")");
	}

	/**
	 * Binds the port and starts serving. Called once, after everything is mounted.
	 *
	 * @return true when the server is listening; false when the port could not be bound (logged)
	 */
	public static synchronized boolean start() {
		if (server != null) {
			return true;
		}
		try {
			HttpServer created = HttpServer.create(new InetSocketAddress(HOST, PORT), 16);
			created.createContext("/", Httpd::handleIndex);
			for (Mount mount : MOUNTS.values()) {
				created.createContext(mount.prefix(), exchange -> dispatch(mount, exchange));
			}
			pool = Executors.newFixedThreadPool(2, runnable -> {
				Thread thread = new Thread(runnable, "mineagentaio-http");
				thread.setDaemon(true);
				return thread;
			});
			created.setExecutor(pool);
			created.start();
			server = created;
			LOG.info("MineAgentAIO listening on http://" + HOST + ":" + PORT);
			return true;
		} catch (IOException e) {
			pool = null;
			LOG.log(Level.SEVERE, "MineAgentAIO could not bind to " + HOST + ":" + PORT
					+ " - is another instance already running?", e);
			return false;
		}
	}

	/** Stops the server and its worker pool (idempotent). */
	public static synchronized void stop() {
		if (server != null) {
			server.stop(0);
			server = null;
		}
		if (pool != null) {
			pool.shutdownNow();
			pool = null;
		}
	}

	private static String normalize(String prefix) {
		String normalized = prefix == null ? "" : prefix.trim();
		if (normalized.isEmpty()) {
			throw new IllegalArgumentException("prefix must not be empty");
		}
		if (!normalized.startsWith("/")) {
			normalized = "/" + normalized;
		}
		while (normalized.length() > 1 && normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		if ("/".equals(normalized)) {
			throw new IllegalArgumentException("prefix must not be /");
		}
		return normalized;
	}

	/** Strips the prefix and hands the request to the part of the mod that mounted it. */
	private static void dispatch(Mount mount, HttpExchange exchange) {
		String requestPath = exchange.getRequestURI().getPath();
		String path = requestPath.length() <= mount.prefix().length()
				? "/"
				: requestPath.substring(mount.prefix().length());
		if (!path.startsWith("/")) {
			path = "/" + path;
		}

		try {
			mount.handler().handle(exchange, path);
		} catch (Exception e) {
			LOG.log(Level.WARNING, "endpoint " + mount.prefix() + " failed", e);
			try {
				Http.respondText(exchange, 500, "internal error: " + e + "\n");
			} catch (IOException ignored) {
				// the handler already started the response
			}
		} finally {
			exchange.close();
		}
	}

	/** {@code GET /}: the index of everything that is mounted right now. */
	private static void handleIndex(HttpExchange exchange) throws IOException {
		String method = exchange.getRequestMethod();
		if (!"GET".equals(method) && !"HEAD".equals(method)) {
			exchange.getResponseHeaders().set("Allow", "GET, HEAD");
			Http.respondText(exchange, 405, "method not allowed: " + method + "\n");
			return;
		}

		String path = exchange.getRequestURI().getPath();
		if (!path.isEmpty() && !"/".equals(path)) {
			Http.respondText(exchange, 404, "no endpoint at " + path + "\n\n" + index());
			return;
		}

		Http.respondText(exchange, 200, index());
	}

	private static synchronized String index() {
		StringBuilder out = new StringBuilder();
		out.append("MineAgentAIO — Minecraft 客户端 HTTP 服务 (Fabric, Minecraft 26.2)\n")
				.append("监听地址: http://").append(HOST).append(':').append(PORT).append("\n\n")
				.append("  GET  /                         ").append("本说明 (当前可用的 endpoint 列表)\n");

		if (MOUNTS.isEmpty()) {
			out.append("\n还没有挂载任何 endpoint。\n");
			return out.toString();
		}

		out.append("\n当前可用的 endpoint\n");
		for (Mount mount : MOUNTS.values()) {
			out.append("\n  ").append(mount.prefix()).append(" — ")
					.append(mount.name()).append('\n');
			for (Endpoint endpoint : mount.endpoints()) {
				out.append("    ").append(pad(endpoint.method(), 9)).append(' ')
						.append(pad(endpoint.path(), 24))
						.append(endpoint.description()).append('\n');
			}
		}

		out.append("\n各部分的使用说明在它的前缀根路径下:");
		for (String prefix : MOUNTS.keySet()) {
			out.append(" GET ").append(prefix).append('/');
		}
		out.append('\n');
		return out.toString();
	}

	private static String pad(String value, int width) {
		String text = value == null ? "" : value;
		if (text.length() >= width) {
			return text;
		}
		StringBuilder padded = new StringBuilder(width).append(text);
		while (padded.length() < width) {
			padded.append(' ');
		}
		return padded.toString();
	}
}
