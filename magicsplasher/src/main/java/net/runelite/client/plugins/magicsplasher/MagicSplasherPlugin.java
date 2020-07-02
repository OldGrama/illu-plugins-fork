/*
 * Copyright (c) 2018, SomeoneWithAnInternetConnection
 * Copyright (c) 2018, oplosthee <https://github.com/oplosthee>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.magicsplasher;

import com.google.inject.Provides;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.queries.NPCQuery;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.PluginType;
import net.runelite.client.plugins.botutils.BotUtils;
import net.runelite.client.plugins.magicsplasher.MagicSplasherConfig;
import net.runelite.client.ui.overlay.OverlayManager;
import org.pf4j.Extension;
import static net.runelite.client.plugins.magicsplasher.MagicSplasherState.*;


@Extension
@PluginDependency(BotUtils.class)
@PluginDescriptor(
	name = "Magic Splasher",
	enabledByDefault = false,
	description = "Illumine automated magic splasher",
	tags = {"Magic", "Splashing"},
	type = PluginType.SKILLING
)
@Slf4j
public class MagicSplasherPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private BotUtils utils;

	@Inject
	private MagicSplasherConfig config;

	@Inject
	PluginManager pluginManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	MagicSplasherOverlay overlay;

	SplashSpells selectedSpell;
	Widget spell;
	MagicSplasherState state;
	Instant botTimer;
	MenuEntry targetMenu;
	LocalPoint beforeLoc = new LocalPoint(0, 0); //initiate to mitigate npe
	Player player;
	NPC splashNPC;

	int npcID = -1;
	int timeout = 0;
	int failureCount = 0;
	long sleepLength = 0;
	boolean startSplasher;
	private static final String OUT_OF_RUNES_MSG = "You do not have enough";
	private static final String UNREACHABLE_MSG = "I can't reach that";
	private final int MAX_FAILURE = 10;
	private BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(1);
	private ThreadPoolExecutor executorService = new ThreadPoolExecutor(1, 1, 25, TimeUnit.SECONDS, queue,
		new ThreadPoolExecutor.DiscardPolicy());

	@Override
	protected void startUp()
	{
		botTimer = Instant.now();
		overlayManager.add(overlay);
		selectedSpell = config.getSpells();
		npcID = config.npcID();
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		startSplasher = false;
		botTimer = null;
		failureCount = 0;
		npcID = -1;
	}

	@Provides
	MagicSplasherConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(MagicSplasherConfig.class);
	}

	@Subscribe
	private void onConfigChanged(ConfigChanged event)
	{
		if (event.getGroup() != "MagicSplasher")
		{
			return;
		}
		switch(event.getKey())
		{
			case "npcID":
				npcID = config.npcID();
				log.debug("NPC ID set to {}", npcID);
				break;
			case "spell":
				selectedSpell = config.getSpells();
				log.debug("Splashing spell set to {}", selectedSpell.getName());
				break;
		}
	}

	private void sleepDelay()
	{
		sleepLength = utils.randomDelay(config.sleepWeightedDistribution(), config.sleepMin(), config.sleepMax(), config.sleepDeviation(), config.sleepTarget());
		log.debug("Sleeping for {}ms", sleepLength);
		utils.sleep(sleepLength);
	}

	private int tickDelay()
	{
		int tickLength = (int) utils.randomDelay(config.tickDelayWeightedDistribution(), config.tickDelayMin(), config.tickDelayMax(), config.tickDelayDeviation(), config.tickDelayTarget());
		log.debug("tick delay for {} ticks", tickLength);
		return tickLength;
	}

	private boolean canCastSpell()
	{
		//spell = client.getWidget(selectedSpell.getInfo());
		//return spell != null && spell.getSpriteId() == selectedSpell.getSpellSpriteID();
		return client.getVar(VarPlayer.ATTACK_STYLE) == 3 || client.getVar(VarPlayer.ATTACK_STYLE) == 4;
	}

	private NPC findNPC()
	{
		log.debug("looking for NPC");
		return new NPCQuery().idEquals(npcID).filter(n -> n.getInteracting() == null || n.getInteracting() == client.getLocalPlayer()).result(client).nearestTo(player);
	}

	public MagicSplasherState getState()
	{
		if (timeout > 0)
		{
			return IDLING;
		}
		if (utils.isMoving(beforeLoc)) //could also test with just isMoving
		{
			return MOVING;
		}
		if(player.getAnimation() != -1)
		{
			return ANIMATING;
		}
		if(!canCastSpell())
		{
			return SPELL_UNAVAILABLE;
		}
		splashNPC = findNPC();
		return (splashNPC != null) ? FIND_NPC : NPC_NOT_FOUND;
	}

	@Subscribe
	private void onGameTick(GameTick tick)
	{
		player = client.getLocalPlayer();
		if (client != null && player != null && client.getGameState() == GameState.LOGGED_IN && startSplasher)
		{
			utils.handleRun(40, 20);
			state = getState();
			beforeLoc = player.getLocalLocation();
			switch (state)
			{
				case IDLING:
					timeout--;
					return;
				case MOVING:
					timeout = tickDelay();
					break;
				case SPELL_UNAVAILABLE:
					log.debug("Auto-cast is not setup or out of runes");
					utils.sendGameMessage("Auto-cast is not setup or out of runes");
					startSplasher = false; //TODO: add logout support
					break;
				case NPC_NOT_FOUND:
					log.debug("NPC not found");
					utils.sendGameMessage("NPC not found");
					timeout = tickDelay();
					break;
				case FIND_NPC:
					targetMenu = new MenuEntry("", "", splashNPC.getIndex(), MenuOpcode.NPC_SECOND_OPTION.getId(), 0, 0, false);
					sleepDelay();
					utils.clickRandomPointCenter(-100, 100);
					break;
			}
		}
		else
		{
			log.debug("client/player is null or bot isn't started");
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (!startSplasher || targetMenu == null)
		{
			return;
		}
		log.debug("MenuEntry string event: " + targetMenu.toString());
		event.setMenuEntry(targetMenu);
		timeout = 10 + tickDelay();
		targetMenu = null; //this allow the player to interact with the client without their clicks being overridden
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		if (!startSplasher || event.getActor() != player ||
			event.getActor().getAnimation() != AnimationID.LOW_LEVEL_MAGIC_ATTACK)
		{
			return;
		}
		log.debug("Animation ID changed to {}, resetting timeout", event.getActor().getAnimation());
		timeout = 10 + tickDelay();
		failureCount = 0;
	}

	@Subscribe
	private void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE ||
		event.getType() != ChatMessageType.ENGINE)
		{
			return;
		}

		if(event.getMessage().contains(OUT_OF_RUNES_MSG))
		{
			log.debug("Out of runes!");
			utils.sendGameMessage("Out of runes!");
			startSplasher = false;
			return;
		}
		if(event.getMessage().contains(UNREACHABLE_MSG))
		{
			log.debug("unreachable message, fail count: " + failureCount);
			if (failureCount >= MAX_FAILURE)
			{
				utils.sendGameMessage("failed to reach NPC too many times, stopping");
				startSplasher = false;
				return;
			}
			failureCount++;
			timeout = tickDelay();
		}
	}
}