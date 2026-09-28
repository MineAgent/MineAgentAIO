// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.mineagent.aio.baritone;

import java.lang.reflect.InvocationTargetException;

/**
 * The one place that talks to Baritone.
 *
 * <p>Baritone is <em>not</em> part of this mod: it is an optional mod the player installs next to
 * it, so the {@code bt} command must work when Baritone is there and fail with a readable reason
 * when it is not. The API is therefore reached reflectively — the same call chain the chat box uses
 * for {@code #goal ~ ~ ~20}, minus the chat round trip:</p>
 *
 * <pre>
 * BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute(command)
 * </pre>
 *
 * <p>Everything is guarded, so a missing, older or otherwise incompatible Baritone is reported
 * instead of breaking the mod: a failed lookup only affects {@code bt}.</p>
 */
public final class BaritoneBridge {
	private BaritoneBridge() {
	}

	/**
	 * Resolves Baritone's {@code ICommandManager}.
	 *
	 * @return the command manager, or {@code null} when Baritone is installed but has no primary
	 *         baritone instance yet (the client has not joined a world)
	 * @throws ClassNotFoundException when Baritone is not installed
	 * @throws ReflectiveOperationException when the installed Baritone has a different API
	 */
	private static Object commandManager() throws ReflectiveOperationException {
		Object provider = Class.forName("baritone.api.BaritoneAPI")
				.getMethod("getProvider").invoke(null);
		if (provider == null) {
			return null;
		}
		Object baritone = Class.forName("baritone.api.IBaritoneProvider")
				.getMethod("getPrimaryBaritone").invoke(provider);
		if (baritone == null) {
			return null;
		}
		return Class.forName("baritone.api.IBaritone")
				.getMethod("getCommandManager").invoke(baritone);
	}

	/**
	 * @return {@code null} when {@code bt} can run right now, otherwise the reason it cannot
	 */
	public static String unavailableReason() {
		try {
			if (commandManager() != null) {
				return null;
			}
			return "Baritone has no primary baritone instance yet (join a world first)";
		} catch (ClassNotFoundException | NoClassDefFoundError e) {
			return "Baritone is not installed (mod id 'baritone')";
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			return "the Baritone API call failed: " + e;
		}
	}

	/**
	 * Runs one Baritone command (without the leading {@code #}).
	 *
	 * @throws ReflectiveOperationException when Baritone is missing or its API differs
	 * @throws IllegalStateException when there is no primary baritone instance yet
	 */
	public static void execute(String command) throws ReflectiveOperationException {
		Object manager = commandManager();

		if (manager == null) {
			throw new IllegalStateException("Baritone has no primary baritone instance yet"
					+ " (join a world first)");
		}

		try {
			Class.forName("baritone.api.command.manager.ICommandManager")
					.getMethod("execute", String.class).invoke(manager, command);
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause();
			throw new IllegalStateException(cause == null ? e.toString() : cause.toString(), cause);
		}
	}
}
