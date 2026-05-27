package com.bahealerorder;

import com.google.inject.Provides;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
		name = "BA Healer Order",
		description = "Displays Barbarian Assault Penance Healer spawn order above each healer.",
		tags = {"barbarian assault", "ba", "healer", "penance", "overlay"}
)
public class BaHealerOrderPlugin extends Plugin
{
	private static final String PENANCE_HEALER_NAME = "Penance Healer";
	private static final String WRONG_FOOD_MESSAGE = "that's the wrong type of poisoned food to use! penalty!";

	@Inject
	private Client client;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private BaHealerOrderOverlay overlay;

	@Inject
	private BaHealerOrderFoodOverlay foodOverlay;

	@Getter
	private final Map<Integer, Integer> healerOrderByNpcIndex = new HashMap<>();

	@Getter
	private final Map<NPC, Integer> visibleHealers = new HashMap<>();

	@Getter
	private final Map<Integer, Integer> foodFedByHealerOrder = new HashMap<>();

	private int nextHealerNumber = 1;
	private Integer selectedPoisonedFoodItemId;
	private PendingFeedAttempt pendingFeedAttempt;

	@Override
	protected void startUp()
	{
		reset();
		overlayManager.add(overlay);
		overlayManager.add(foodOverlay);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(foodOverlay);
		overlayManager.remove(overlay);
		reset();
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		NPC npc = event.getNpc();

		if (!isPenanceHealer(npc))
		{
			return;
		}

		int npcIndex = npc.getIndex();
		Integer order = healerOrderByNpcIndex.get(npcIndex);

		if (order == null)
		{
			order = nextHealerNumber++;
			healerOrderByNpcIndex.put(npcIndex, order);

			log.debug("Assigned Penance Healer index {} to spawn #{}", npcIndex, order);
		}
		else
		{
			log.debug("Re-associated Penance Healer index {} with spawn #{}", npcIndex, order);
		}

		visibleHealers.put(npc, order);
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		NPC npc = event.getNpc();

		if (visibleHealers.remove(npc) != null)
		{
			log.debug("Removed visible Penance Healer index {}", npc.getIndex());
		}

		/*
		 * Do not remove from healerOrderByNpcIndex here.
		 *
		 * A despawn event can happen when the NPC leaves render distance.
		 * Keeping the NPC index mapping lets us restore the same number if that
		 * healer re-enters render distance later in the same wave.
		 */
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		log.debug(
				"BA_HEALER_ORDER_MENU_CLICK: option='{}', target='{}', action='{}', id={}, itemId={}, itemOp={}, param0={}, param1={}",
				event.getMenuOption(),
				event.getMenuTarget(),
				event.getMenuAction(),
				event.getId(),
				event.getItemId(),
				event.getItemOp(),
				event.getParam0(),
				event.getParam1()
		);

		String option = Text.removeTags(event.getMenuOption()).toLowerCase(Locale.ROOT);
		String target = Text.removeTags(event.getMenuTarget()).toLowerCase(Locale.ROOT);

		handlePoisonedFoodSelection(event, option, target);
		handlePoisonedFoodUseOnHealer(event, option, target);
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.INVENTORY.getId())
		{
			return;
		}

		if (pendingFeedAttempt == null)
		{
			return;
		}

		int currentFoodCount = getItemCount(event.getItemContainer().getItems(), pendingFeedAttempt.foodItemId);

		if (currentFoodCount >= pendingFeedAttempt.foodCountBeforeUse)
		{
			return;
		}

		int healerOrder = pendingFeedAttempt.healerOrder;

		foodFedByHealerOrder.merge(healerOrder, 1, Integer::sum);

		log.debug(
				"Counted consumed poisoned food for healer #{}. Item {} went from {} to {}. Total now {}",
				healerOrder,
				pendingFeedAttempt.foodItemId,
				pendingFeedAttempt.foodCountBeforeUse,
				currentFoodCount,
				foodFedByHealerOrder.get(healerOrder)
		);

		pendingFeedAttempt = null;
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		String message = Text.removeTags(event.getMessage()).toLowerCase(Locale.ROOT);

		if (message.contains(WRONG_FOOD_MESSAGE))
		{
			log.debug("Wrong poisoned food detected. Cancelling pending feed attempt.");

			pendingFeedAttempt = null;
			return;
		}

		if (isWaveEndMessage(event.getType(), message))
		{
			log.debug("Detected BA wave end message: {}", message);
			reset();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState gameState = event.getGameState();

		if (gameState == GameState.LOGIN_SCREEN
				|| gameState == GameState.HOPPING
				|| gameState == GameState.LOADING)
		{
			reset();
		}
	}

	public Map<NPC, Integer> getTrackedHealers()
	{
		return Collections.unmodifiableMap(visibleHealers);
	}

	private void handlePoisonedFoodSelection(MenuOptionClicked event, String option, String target)
	{
		if (event.getMenuAction() != MenuAction.WIDGET_TARGET)
		{
			return;
		}

		if (!"use".equals(option))
		{
			return;
		}

		if (!target.contains("poisoned"))
		{
			return;
		}

		selectedPoisonedFoodItemId = event.getItemId();

		log.debug("Selected poisoned food item id {}", selectedPoisonedFoodItemId);
	}

	private void handlePoisonedFoodUseOnHealer(MenuOptionClicked event, String option, String target)
	{
		if (event.getMenuAction() != MenuAction.WIDGET_TARGET_ON_NPC)
		{
			return;
		}

		if (!"use".equals(option))
		{
			return;
		}

		if (!target.contains("poisoned") || !target.contains("penance healer"))
		{
			return;
		}

		if (selectedPoisonedFoodItemId == null || selectedPoisonedFoodItemId <= 0)
		{
			log.debug("Food used on healer, but no selected poisoned food item id was stored");
			return;
		}

		int npcIndex = event.getId();
		Integer healerOrder = healerOrderByNpcIndex.get(npcIndex);

		if (healerOrder == null)
		{
			log.debug("Food used on Penance Healer index {}, but no healer order was found", npcIndex);
			return;
		}

		int foodCountBeforeUse = getInventoryItemCount(selectedPoisonedFoodItemId);

		pendingFeedAttempt = new PendingFeedAttempt(
				healerOrder,
				selectedPoisonedFoodItemId,
				foodCountBeforeUse
		);

		log.debug(
				"Pending food feed for healer #{} from NPC index {} using item id {}. Count before use: {}",
				healerOrder,
				npcIndex,
				selectedPoisonedFoodItemId,
				foodCountBeforeUse
		);
	}

	private int getInventoryItemCount(int itemId)
	{
		ItemContainer inventory = client.getItemContainer(InventoryID.INVENTORY);

		if (inventory == null)
		{
			return 0;
		}

		return getItemCount(inventory.getItems(), itemId);
	}

	private int getItemCount(Item[] items, int itemId)
	{
		int count = 0;

		if (items == null)
		{
			return count;
		}

		for (Item item : items)
		{
			if (item != null && item.getId() == itemId)
			{
				count += item.getQuantity();
			}
		}

		return count;
	}

	private boolean isPenanceHealer(NPC npc)
	{
		if (npc == null || npc.getName() == null)
		{
			return false;
		}

		String name = Text.removeTags(npc.getName());
		return PENANCE_HEALER_NAME.equals(name);
	}

	private boolean isWaveEndMessage(ChatMessageType type, String message)
	{
		if (type != ChatMessageType.GAMEMESSAGE
				&& type != ChatMessageType.SPAM
				&& type != ChatMessageType.WELCOME
				&& type != ChatMessageType.CONSOLE)
		{
			return false;
		}

		return message.contains("wave")
				&& (
				message.contains("complete")
						|| message.contains("completed")
						|| message.contains("duration")
						|| message.contains("congratulations")
						|| message.contains("queen")
		);
	}

	private void reset()
	{
		visibleHealers.clear();
		healerOrderByNpcIndex.clear();
		foodFedByHealerOrder.clear();
		pendingFeedAttempt = null;
		selectedPoisonedFoodItemId = null;
		nextHealerNumber = 1;
	}

	@Provides
	BaHealerOrderConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BaHealerOrderConfig.class);
	}

	private static class PendingFeedAttempt
	{
		private final int healerOrder;
		private final int foodItemId;
		private final int foodCountBeforeUse;

		private PendingFeedAttempt(int healerOrder, int foodItemId, int foodCountBeforeUse)
		{
			this.healerOrder = healerOrder;
			this.foodItemId = foodItemId;
			this.foodCountBeforeUse = foodCountBeforeUse;
		}
	}
}