// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.mineagent.aio;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The mod's own message table for the container side ({@code /op}, merged from cmdCraft).
 *
 * <p>Why this exists: those messages are translation based, but a mod's {@code assets/} only reach
 * the vanilla resource manager when the Fabric API is installed — and MineAgentAIO deliberately
 * needs nothing but Fabric Loader. So instead of asking vanilla to look the key up, this class reads
 * the very same {@code assets/mineagentaio/lang/*.json} files straight out of the mod jar and hands
 * the template to {@link Component#translatableWithFallback}: vanilla still does the argument
 * interpolation (so a {@code %s} fed a {@code Component} behaves exactly like before), and the
 * messages are correct with or without the Fabric API. With the Fabric API installed vanilla simply
 * finds the same key in its own table and uses that.</p>
 *
 * <p>The table follows the in-game language, falls back to {@code en_us} and finally to the key
 * itself, so a missing string is never fatal.</p>
 */
public final class Messages {
	/** Where the language files live inside the mod jar. */
	public static final String RESOURCE_ROOT = "/assets/mineagentaio/lang/";

	private static final String FALLBACK_LANGUAGE = "en_us";
	private static final Logger LOG = Logger.getLogger("mineagentaio");

	private static Map<String, String> table = Map.of();
	private static String tableLanguage;

	private Messages() {
	}

	/**
	 * Resolves {@code key} with the mod's own table.
	 *
	 * @param key  translation key, e.g. {@code mineagentaio.msg.done}
	 * @param args arguments interpolated into the template by vanilla ({@code %s})
	 * @return the message component; the plain key when the table has no such entry either
	 */
	public static Component translatable(String key, Object... args) {
		String template = table().get(key);

		if (template == null) {
			return Component.translatable(key, args);
		}

		return Component.translatableWithFallback(key, template, args);
	}

	/** @return the table for the language selected right now, reloaded when that changes */
	private static Map<String, String> table() {
		String language = currentLanguage();

		if (language.equals(tableLanguage)) {
			return table;
		}

		synchronized (Messages.class) {
			if (!language.equals(tableLanguage)) {
				Map<String, String> loaded = new HashMap<>();
				read(FALLBACK_LANGUAGE, loaded);
				read(language, loaded);
				table = Map.copyOf(loaded);
				tableLanguage = language;
			}
			return table;
		}
	}

	/** @return the in-game language code (e.g. {@code zh_cn}), or the fallback before the client is up */
	private static String currentLanguage() {
		Minecraft minecraft = Minecraft.getInstance();

		if (minecraft == null || minecraft.getLanguageManager() == null) {
			return FALLBACK_LANGUAGE;
		}

		String selected = minecraft.getLanguageManager().getSelected();
		return selected == null || selected.isBlank()
				? FALLBACK_LANGUAGE
				: selected.toLowerCase(Locale.ROOT);
	}

	/** Merges one language file into {@code into}; a missing file simply leaves it untouched. */
	private static void read(String language, Map<String, String> into) {
		String path = RESOURCE_ROOT + language + ".json";

		try (InputStream in = Messages.class.getResourceAsStream(path)) {
			if (in == null) {
				return;
			}

			try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
				JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();

				for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
					into.put(entry.getKey(), entry.getValue().getAsString());
				}
			}
		} catch (Exception e) {
			LOG.log(Level.WARNING, "could not read the message table " + path, e);
		}
	}
}
