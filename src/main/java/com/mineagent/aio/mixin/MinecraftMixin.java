// SPDX-License-Identifier: LGPL-3.0-only
// Copyright (C) 2026 MineAgent

package com.mineagent.aio.mixin;

import com.mineagent.aio.NoAutoPause;
import com.mineagent.aio.op.CraftJob;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The per-tick work of the mod, at the end of {@code Minecraft#tick}.
 *
 * <p>Two things need a client tick and the mod deliberately does not use the Fabric API (neither
 * does Baritone), so instead of a lifecycle event the tick is injected directly:</p>
 *
 * <ul>
 *   <li>{@link CraftJob} — a craft job clicks real container slots, so it has to advance on the
 *       render thread and at most once per tick (the server needs a tick to compute the crafting
 *       result and to answer the click).</li>
 *   <li>{@link NoAutoPause} — merged from noautopause, it clears
 *       {@code Options#pauseOnLostFocus} once so the client keeps ticking while unfocused.</li>
 * </ul>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "tick()V", at = @At("TAIL"))
	private void mineagentaio$tick(CallbackInfo ci) {
		Minecraft minecraft = (Minecraft) (Object) this;
		CraftJob.tick(minecraft);
		NoAutoPause.tick(minecraft);
	}
}
