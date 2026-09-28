// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

import com.mineagent.aio.ctl.Action;
import com.mineagent.aio.ctl.CommandRunner;
import com.mineagent.aio.ctl.InputExecutor;
import com.mineagent.aio.http.Http;
import com.mineagent.aio.http.Httpd;
import com.mineagent.aio.http.PathHandler;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Headless check of the shutdown story — no Minecraft, no window, no client.
 *
 * <p>Why the client needs an explicit exit at all: after the render thread returns from
 * {@code Minecraft#run()}, vanilla's {@code Main} starts a post-main watchdog; if the JVM has not
 * finished 15 seconds later it writes a bogus {@code Client shutdown from post-main} crash report
 * and calls {@code System.exit(-8)}. The JVM only finishes when every <em>non-daemon</em> thread is
 * done, and {@code com.sun.net.httpserver} owns exactly such a thread ("HTTP-Dispatcher"), as do
 * other mods (Baritone keeps a worker pool). {@code ClientExitWatcher} therefore joins the render
 * thread, tears everything down and calls {@code System.exit(0)} itself.</p>
 *
 * <p>This harness checks the parts of that story that do not need the game:</p>
 *
 * <ol>
 *   <li>the JDK really does leave a non-daemon HTTP-Dispatcher thread behind (the hazard is real),</li>
 *   <li>every thread the mod creates itself (HTTP pool, command runner) is a daemon, so it can never
 *       be the reason the JVM stays alive,</li>
 *   <li>{@code Httpd.stop()} removes the non-daemon dispatcher thread and is idempotent, so the
 *       teardown really does undo the server's threads,</li>
 *   <li>after the teardown the only remaining non-daemon threads are the JVM's own.</li>
 * </ol>
 *
 * <pre>
 * javac --release 25 -encoding UTF-8 -d build/verify \
 *   src/main/java/com/mineagent/aio/http/*.java \
 *   src/main/java/com/mineagent/aio/ctl/{Action,CommandException,CommandRunner,InputExecutor}.java \
 *   tools/VerifyExit.java
 * java -cp build/verify VerifyExit
 * </pre>
 */
public final class VerifyExit {
	private static int failures;

	public static void main(String[] args) throws Exception {
		Map<String, Boolean> before = daemonByName();

		// 1. start the HTTP server exactly like the mod does, with a trivial handler
		Httpd.mount("/probe", "VerifyExit probe", List.of(new Httpd.Endpoint("GET", "/probe/", "probe")),
				probe());
		check("Httpd.start() succeeded", Httpd.start(), "port 3420 busy?");

		Thread dispatcher = awaitThread("HTTP-Dispatcher", 2000);
		check("the JDK leaves a non-daemon 'HTTP-Dispatcher' thread behind (the hazard is real)",
				dispatcher != null && !dispatcher.isDaemon(),
				"found=" + (dispatcher != null) + (dispatcher == null ? "" : " daemon=" + dispatcher.isDaemon()));

		// 2. everything the mod creates is a daemon; the pool thread only exists once a request ran
		check("serving works while probing", get("/probe/") == 200, "the probe endpoint answered");
		Thread worker = awaitThread("mineagentaio-http", 2000);
		check("the HTTP worker pool is a daemon thread", worker != null && worker.isDaemon(),
				"threads=" + modThreads());

		CommandRunner runner = new CommandRunner(stubExecutor());
		runner.submit(List.of(Action.releaseAll(0)));
		check("the command runner is a daemon thread", isDaemon("mineagentaio-runner"),
				"threads=" + modThreads());
		runner.shutdown();

		// 3. the teardown removes the non-daemon thread and is idempotent
		Httpd.stop();
		Httpd.stop();
		check("Httpd.stop() removed the non-daemon dispatcher thread",
				awaitThread("HTTP-Dispatcher", 2000) == null,
				"still there: " + threadNames());
		check("Httpd.stop() removed the HTTP worker pool", awaitThread("mineagentaio-http", 2000) == null,
				"threads=" + modThreads());

		// 4. nothing of the server survives, so only vanilla's own non-daemon threads remain
		Set<String> nonDaemonNow = nonDaemonNames();
		nonDaemonNow.removeAll(before.keySet());
		check("no non-daemon thread is left behind by this mod", nonDaemonNow.isEmpty(),
				"left over: " + nonDaemonNow);
		check("the JVM would now exit on its own (no thread of ours keeps it alive)",
				nonDaemonNames().stream().allMatch(name -> !name.startsWith("mineagentaio")
						&& !name.equals("HTTP-Dispatcher")),
				"non-daemon: " + nonDaemonNames());

		System.out.println(failures == 0
				? "EXIT TESTS: all passed — the only reason the client needs System.exit(0) is the"
						+ " JDK dispatcher (gone after the teardown) plus other mods' threads"
						+ " (Baritone), and no thread of this mod is non-daemon"
				: "EXIT TESTS: " + failures + " FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}

	/** A handler that always answers 200, so the server has something to serve while probing. */
	private static PathHandler probe() {
		return (HttpExchange exchange, String path) -> Http.respondText(exchange, 200, "probe\n");
	}

	/** An {@link InputExecutor} that does nothing: enough to make the runner start its worker. */
	private static InputExecutor stubExecutor() {
		return (InputExecutor) Proxy.newProxyInstance(VerifyExit.class.getClassLoader(),
				new Class<?>[] {InputExecutor.class}, (proxy, method, args) -> {
					Class<?> type = method.getReturnType();
					if (type == boolean.class) {
						return false;
					}
					if (type == byte[].class) {
						return new byte[0];
					}
					return null;
				});
	}

	/** @return every live thread whose name contains {@code needle} */
	private static Thread find(String needle) {
		return Thread.getAllStackTraces().keySet().stream()
				.filter(thread -> thread.getName().contains(needle))
				.findFirst().orElse(null);
	}

	/** @return the thread once it exists, waiting up to {@code timeoutMs} for it */
	private static Thread awaitThread(String needle, long timeoutMs) throws InterruptedException {
		Thread found = find(needle);

		for (long waited = 0; found == null && waited < timeoutMs; waited += 50) {
			Thread.sleep(50);
			found = find(needle);
		}

		return found;
	}

	private static boolean isDaemon(String needle) {
		Thread thread = find(needle);
		return thread != null && thread.isDaemon();
	}

	/** One GET against the probing server; returns the status code. */
	private static int get(String path) throws IOException {
		java.net.HttpURLConnection connection = (java.net.HttpURLConnection)
				new java.net.URL("http://127.0.0.1:3420" + path).openConnection();
		connection.setRequestMethod("GET");
		int status = connection.getResponseCode();
		try (java.io.InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
			if (in != null) {
				in.readAllBytes();
			}
		}
		return status;
	}

	private static Set<String> modThreads() {
		return threadNames().stream().filter(name -> name.startsWith("mineagentaio")
				|| name.equals("HTTP-Dispatcher") || name.equals("mcctl-runner"))
				.collect(Collectors.toCollection(TreeSet::new));
	}

	private static Set<String> threadNames() {
		return Thread.getAllStackTraces().keySet().stream()
				.map(Thread::getName).collect(Collectors.toCollection(TreeSet::new));
	}

	private static Map<String, Boolean> daemonByName() {
		Map<String, Boolean> map = new TreeMap<>();
		Thread.getAllStackTraces().keySet().forEach(thread -> map.put(thread.getName(), thread.isDaemon()));
		return map;
	}

	private static Set<String> nonDaemonNames() {
		return Thread.getAllStackTraces().keySet().stream()
				.filter(thread -> !thread.isDaemon())
				.map(Thread::getName).collect(Collectors.toCollection(TreeSet::new));
	}

	private static void check(String what, boolean ok, String detail) {
		if (ok) {
			System.out.println("ok   " + what);
		} else {
			failures++;
			System.out.println("FAIL " + what + " -> " + detail);
		}
	}

	private VerifyExit() {
	}
}
