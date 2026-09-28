// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.mineagent.aio.http;

import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The response/request helpers shared by the endpoints.
 *
 * <p>The three parts of the mod used to be separate mods and each carried its own copy of these;
 * now that they share one jar they share one implementation too. Conventions (see
 * {@code GET :3420/}): {@code text/plain; charset=utf-8} unless stated otherwise, {@code no-store},
 * and a {@code HEAD} gets the status and headers but no body.</p>
 */
public final class Http {
	/** Thrown by {@link #readBody} when the request body exceeds the caller's limit. */
	public static final class BodyTooLargeException extends IOException {
		private static final long serialVersionUID = 1L;
	}

	private Http() {
	}

	/** Sends {@code body} as {@code text/plain; charset=utf-8}. */
	public static void respondText(HttpExchange exchange, int status, String body) throws IOException {
		respond(exchange, status, "text/plain; charset=utf-8", body);
	}

	/** Sends {@code body} with an explicit content type. */
	public static void respond(HttpExchange exchange, int status, String contentType, String body)
			throws IOException {
		respond(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
	}

	/** Sends a binary {@code body} (screenshots) with an explicit content type. */
	public static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
			throws IOException {
		exchange.getResponseHeaders().set("Content-Type", contentType);
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		if ("HEAD".equals(exchange.getRequestMethod()) || body.length == 0) {
			exchange.sendResponseHeaders(status, -1);
			return;
		}
		exchange.sendResponseHeaders(status, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

	/**
	 * Reads a request body, refusing to grow past {@code maxBytes}.
	 *
	 * @throws BodyTooLargeException when the body is longer than {@code maxBytes}
	 */
	public static byte[] readBody(HttpExchange exchange, int maxBytes) throws IOException {
		try (InputStream in = exchange.getRequestBody();
				ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[4096];
			int total = 0;
			int read;

			while ((read = in.read(buffer)) > 0) {
				total += read;

				if (total > maxBytes) {
					throw new BodyTooLargeException();
				}

				out.write(buffer, 0, read);
			}

			return out.toByteArray();
		}
	}

	/** Escapes one string for a JSON reply (used by the {@code POST /ctl/} receipt). */
	public static String escapeJson(String value) {
		StringBuilder sb = new StringBuilder(value.length() + 8);
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
				}
			}
		}
		return sb.toString();
	}
}
