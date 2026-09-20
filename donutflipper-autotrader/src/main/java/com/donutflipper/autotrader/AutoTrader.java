package com.donutflipper.autotrader;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Processes auction finds strictly one at a time: search item, click-buy it,
 * verify it actually arrived, select it in the hotbar, list it for sale,
 * wait out the rate limit, then move to the next find.
 */
public class AutoTrader {
   private static final Logger LOGGER = LoggerFactory.getLogger("donutflipper-autotrader");
   private static final AutoTrader INSTANCE = new AutoTrader();
   private static final long SCREEN_WAIT_TIMEOUT_MS = 3000;
   private static final long PURCHASE_SETTLE_MS = 500;
   private static final long POST_HOTBAR_SETTLE_MS = 300;
   private static final int SELL_HOTBAR_SLOT = 8;

   private static final String[] SELL_FAILURE_KEYWORDS = {
      "limit", "maximum", "too many", "cannot list", "can't list", "not enough space", "full"
   };

   private final Map<String, Long> lastTradeTime = new ConcurrentHashMap<>();
   private final ReflectionHelper reflectionHelper = new ReflectionHelper();
   private final BlockingQueue<Object> pendingFinds = new LinkedBlockingQueue<>();
   private final BlockingQueue<String> recentMessages = new LinkedBlockingQueue<>(50);
   private volatile boolean workerStarted = false;

   /** Called by the mod's chat listener for every game/system message received. */
   public void onGameMessage(String text) {
      if (!recentMessages.offer(text)) {
         recentMessages.poll();
         recentMessages.offer(text);
      }
   }

   private void drainRecentMessages() {
      recentMessages.clear();
   }

   /** Polls recent chat for a short window after a sell attempt. Returns the failing message, or null if it looked fine. */
   private String waitForSellFailure() throws InterruptedException {
      long deadline = System.currentTimeMillis() + 1500;
      while (System.currentTimeMillis() < deadline) {
         String message = recentMessages.poll(150, TimeUnit.MILLISECONDS);
         if (message == null) {
            continue;
         }
         String lower = message.toLowerCase();
         if (lower.contains("you listed")) {
            return null;
         }
         for (String keyword : SELL_FAILURE_KEYWORDS) {
            if (lower.contains(keyword)) {
               return message;
            }
         }
      }
      return null;
   }

   private AutoTrader() {}

   public static AutoTrader instance() {
      return INSTANCE;
   }

   public void tick(Object clientObj) {
      Minecraft client = (Minecraft) clientObj;
      ensureWorker(client);

      if (!AutoTraderConfig.instance().isEnabled() || client == null) {
         return;
      }
      try {
         enqueueNewFinds();
      } catch (Exception e) {
         LOGGER.error("Error polling auction finds", e);
      }
   }

   private synchronized void ensureWorker(Minecraft client) {
      if (workerStarted || client == null) {
         return;
      }
      workerStarted = true;
      Thread.ofVirtual().start(() -> processQueueLoop(client));
   }

   private void enqueueNewFinds() throws Exception {
      Object radar = reflectionHelper.invokeStatic("com.donutflipper.mod.client.DonutFlipperClient", "auctionRadar");
      if (radar == null) {
         return;
      }

      Collection<?> finds = (Collection<?>) reflectionHelper.invoke(radar, "takeNew");
      if (finds == null || finds.isEmpty()) {
         return;
      }

      for (Object find : finds) {
         if (shouldTrade(find)) {
            pendingFinds.offer(find);
         }
      }
   }

   private boolean shouldTrade(Object find) {
      try {
         AutoTraderConfig config = AutoTraderConfig.instance();
         Object profitObj = reflectionHelper.invoke(find, "profit");
         String roiPercent = (String) reflectionHelper.invoke(find, "roiPercent");
         String itemId = (String) reflectionHelper.invoke(find, "itemId");
         String itemName = (String) reflectionHelper.invoke(find, "name");

         if (profitObj == null || roiPercent == null || itemId == null) {
            return false;
         }

         if (config.isBlacklisted(itemName) || config.isBlacklisted(itemId)) {
            return false;
         }

         Object askObj = reflectionHelper.invoke(find, "ask");
         long askCents = (Long) reflectionHelper.invoke(askObj, "cents");
         if (askCents > config.getMaxSpendCents()) {
            return false;
         }

         long now = System.currentTimeMillis();
         Long lastTrade = lastTradeTime.get(itemId);
         if (lastTrade != null && (now - lastTrade) < config.getRateLimitMs()) {
            return false;
         }

         if (config.getMinProfitCents() > 0) {
            long profitCents = (Long) reflectionHelper.invoke(profitObj, "cents");
            if (profitCents < config.getMinProfitCents()) {
               return false;
            }
         }

         double roi = parseRoi(roiPercent);
         return roi >= config.getMinRoiPercent();
      } catch (Exception e) {
         LOGGER.debug("Error evaluating trade", e);
         return false;
      }
   }

   private double parseRoi(String roiStr) {
      if (roiStr == null) return 0.0;
      try {
         return Double.parseDouble(roiStr.replace("%", "").trim());
      } catch (NumberFormatException e) {
         return 0.0;
      }
   }

   /** Runs forever on its own thread, handling exactly one find at a time. */
   private void processQueueLoop(Minecraft client) {
      while (true) {
         try {
            Object find = pendingFinds.take();
            if (AutoTraderConfig.instance().isEnabled()) {
               processOneFind(client, find);
            }
         } catch (InterruptedException ie) {
            break;
         } catch (Exception e) {
            LOGGER.error("Error processing find", e);
         }
      }
   }

   private void processOneFind(Minecraft client, Object find) {
      try {
         String itemName = (String) reflectionHelper.invoke(find, "name");
         String searchName = (String) reflectionHelper.invoke(find, "searchName");
         String itemId = (String) reflectionHelper.invoke(find, "itemId");
         String roiPercent = (String) reflectionHelper.invoke(find, "roiPercent");
         Object askObj = reflectionHelper.invoke(find, "ask");
         String askDisplay = (String) reflectionHelper.invoke(askObj, "display");
         long askCents = (Long) reflectionHelper.invoke(askObj, "cents");
         Object profitObj = reflectionHelper.invoke(find, "profit");
         long profitCents = (Long) reflectionHelper.invoke(profitObj, "cents");

         if (itemName == null || itemId == null) {
            return;
         }

         String searchTerm = searchName != null ? searchName : itemName;
         lastTradeTime.put(itemId, System.currentTimeMillis());

         int[] before = runOnMain(client, () -> snapshotMatchingSlots(client, itemName));

         LOGGER.info("AutoTrader: Searching {} at {} (+{} ROI)", itemName, askDisplay, roiPercent);
         sendCommand(client, "/ah " + searchTerm);

         Long actualPaidCents = waitForScreenAndBuy(client, find, askCents);
         if (actualPaidCents == null) {
            LOGGER.warn("AutoTrader: Could not find a lot priced like the flagged deal for {} - skipping", itemName);
            closeScreen(client);
            return;
         }

         Thread.sleep(PURCHASE_SETTLE_MS);

         Integer hotbarSlot = runOnMain(client, () -> confirmPurchaseAndPrepareHotbar(client, itemName, before));
         closeScreen(client);

         if (hotbarSlot == null) {
            LOGGER.warn("AutoTrader: {} was not actually received after buying - skipping sell", itemName);
            return;
         }

         LOGGER.info("AutoTrader: Bought {} at {} (+{} ROI)", itemName, askDisplay, roiPercent);
         showNotification("AutoTrader", "Bought: " + itemName + " at " + askDisplay + " (+" + roiPercent + " ROI)");

         Thread.sleep(POST_HOTBAR_SETTLE_MS);

         // Base the sell price on what was actually paid, not the (possibly stale) originally flagged ask.
         long trueValueCents = actualPaidCents + profitCents;
         long sellPrice = (long) (trueValueCents * AutoTraderConfig.instance().getSellMarkupPercent() / 100.0);
         // Hard floor: never list for less than what was paid, no matter what the calculation above produced.
         long minimumSafeSellPrice = (long) (actualPaidCents * 1.02);
         sellPrice = Math.max(sellPrice, minimumSafeSellPrice);

         drainRecentMessages();
         sendCommand(client, String.format("/ah sell %d", sellPrice / 100));
         showNotification("AutoTrader", "Selling: " + itemName + " at $" + (sellPrice / 100));

         String sellFailure = waitForSellFailure();
         if (sellFailure != null) {
            LOGGER.warn("AutoTrader: sell appears to have failed ({}) - pausing AutoTrader", sellFailure);
            AutoTraderConfig.instance().setEnabled(false);
            LocalPlayer player = client.player;
            if (player != null) {
               player.displayClientMessage(Component.literal(
                  "§c[AutoTrader] Paused - sell failed, auction slots may be full: " + sellFailure), false);
            }
            return;
         }

         Thread.sleep(Math.max(250, AutoTraderConfig.instance().getRateLimitMs()));
      } catch (Exception e) {
         LOGGER.error("Error processing find", e);
      }
   }

   /** Must run on the main thread. Counts, per inventory slot (0-35), how many of itemName are sitting there. */
   private int[] snapshotMatchingSlots(Minecraft client, String itemName) {
      int[] counts = new int[36];
      LocalPlayer player = client.player;
      if (player == null) {
         return counts;
      }
      for (int i = 0; i < 36; i++) {
         ItemStack stack = player.getInventory().getItem(i);
         if (!stack.isEmpty() && stack.getHoverName().getString().equalsIgnoreCase(itemName)) {
            counts[i] = stack.getCount();
         }
      }
      return counts;
   }

   /**
    * Must run on the main thread, while the auction screen is still open.
    * Confirms a new stack of itemName appeared, moves it into the sell hotbar slot if needed,
    * and selects that hotbar slot. Returns the hotbar slot used, or null if nothing was actually received.
    */
   private Integer confirmPurchaseAndPrepareHotbar(Minecraft client, String itemName, int[] before) {
      LocalPlayer player = client.player;
      if (player == null) {
         return null;
      }

      int[] after = snapshotMatchingSlots(client, itemName);
      int boughtInventorySlot = -1;
      for (int i = 0; i < 36; i++) {
         if (after[i] > before[i]) {
            boughtInventorySlot = i;
            break;
         }
      }
      if (boughtInventorySlot == -1) {
         return null;
      }

      if (boughtInventorySlot <= 8) {
         selectHotbarSlot(player, boughtInventorySlot);
         return boughtInventorySlot;
      }

      // Item landed in the main inventory - swap it into the sell hotbar slot using the still-open screen's menu.
      Screen screen = client.screen;
      if (screen instanceof AbstractContainerScreen<?> containerScreen) {
         AbstractContainerMenu menu = containerScreen.getMenu();
         for (int menuIndex = 0; menuIndex < menu.slots.size(); menuIndex++) {
            Slot slot = menu.slots.get(menuIndex);
            if (slot.container == player.getInventory() && slot.getContainerSlot() == boughtInventorySlot) {
               client.gameMode.handleInventoryMouseClick(menu.containerId, menuIndex, SELL_HOTBAR_SLOT, ClickType.SWAP, player);
               break;
            }
         }
      }
      selectHotbarSlot(player, SELL_HOTBAR_SLOT);
      return SELL_HOTBAR_SLOT;
   }

   private void selectHotbarSlot(LocalPlayer player, int hotbarSlot) {
      player.getInventory().setSelectedSlot(hotbarSlot);
      player.connection.send(new ServerboundSetCarriedItemPacket(hotbarSlot));
   }

   /**
    * Polls (from this worker thread) until the auction screen opens, then clicks the matching lot on the main thread.
    * Returns the clicked lot's actual worst-case price in cents (never null on success), or null if no safe match was found.
    */
   private Long waitForScreenAndBuy(Minecraft client, Object find, long expectedAskCents) throws InterruptedException {
      long deadline = System.currentTimeMillis() + SCREEN_WAIT_TIMEOUT_MS;
      while (System.currentTimeMillis() < deadline) {
         Long result = runOnMain(client, () -> {
            try {
               return tryClickMatch(client, find, expectedAskCents);
            } catch (Exception e) {
               LOGGER.debug("Click attempt failed", e);
               return null;
            }
         });
         if (result != null) {
            return result;
         }
         Thread.sleep(150);
      }
      return null;
   }

   /**
    * Must run on the main/render thread: reads the open auction screen and clicks the matching lot,
    * but only if its displayed price is actually consistent with the flagged deal - auctions can have
    * multiple sellers listing the same item at very different prices, and we must never buy the wrong one.
    * Leaves the screen open. Returns the clicked lot's worst-case price in cents, or null if nothing safe to click.
    */
   private Long tryClickMatch(Minecraft client, Object find, long expectedAskCents) throws Exception {
      Screen screen = client.screen;
      if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
         return null;
      }

      String title = screen.getTitle().getString();
      Optional<?> pageOf = (Optional<?>) reflectionHelper.invokeStatic(
         "com.donutflipper.mod.auction.AuctionPage", "pageOf",
         new Class[]{String.class}, new Object[]{title});
      if (pageOf == null || pageOf.isEmpty()) {
         return null;
      }

      AbstractContainerMenu menu = containerScreen.getMenu();
      List<?> slots = (List<?>) reflectionHelper.invokeStaticDeclared(
         "com.donutflipper.mod.client.auction.AuctionScreenWatcher", "read",
         new Class[]{AbstractContainerMenu.class}, new Object[]{menu});

      Optional<?> pageOpt = (Optional<?>) reflectionHelper.invokeStatic(
         "com.donutflipper.mod.auction.AuctionPage", "read",
         new Class[]{String.class, List.class}, new Object[]{title, slots});
      if (pageOpt == null || pageOpt.isEmpty()) {
         return null;
      }
      Object page = pageOpt.get();

      List<?> lots = (List<?>) reflectionHelper.invokeStatic(
         "com.donutflipper.mod.auction.FindMatch", "lotsFor",
         new Class[]{page.getClass(), find.getClass()}, new Object[]{page, find});
      if (lots == null || lots.isEmpty()) {
         return null;
      }

      // FindMatch.lotsFor() can match on item+count alone; multiple sellers may list the same item
      // at very different prices, so only accept a lot whose price is actually consistent with the
      // flagged deal, and prefer the cheapest such lot.
      Object bestLot = null;
      long bestPriceCents = Long.MAX_VALUE;
      for (Object lot : lots) {
         Object priceObj = reflectionHelper.invoke(lot, "price");
         boolean couldBeExpected = (Boolean) reflectionHelper.invoke(priceObj, "couldBe", new Class[]{long.class}, new Object[]{expectedAskCents});
         if (!couldBeExpected) {
            continue;
         }
         long worstCase = (Long) reflectionHelper.invoke(priceObj, "mostItCouldCost");
         if (worstCase < bestPriceCents) {
            bestPriceCents = worstCase;
            bestLot = lot;
         }
      }
      if (bestLot == null) {
         LOGGER.warn("AutoTrader: found lots but none matched the expected price ({}) - refusing to buy", expectedAskCents);
         return null;
      }

      int slotIndex = (Integer) reflectionHelper.invoke(bestLot, "slot");
      client.gameMode.handleInventoryMouseClick(menu.containerId, slotIndex, 0, ClickType.PICKUP, client.player);
      return bestPriceCents;
   }

   private void closeScreen(Minecraft client) {
      client.execute(() -> client.setScreen(null));
   }

   private void sendCommand(Minecraft client, String command) throws Exception {
      reflectionHelper.invokeStatic("com.donutflipper.mod.client.notify.FindCommand",
         "send", new Class[]{Minecraft.class, String.class},
         new Object[]{client, command});
   }

   private void showNotification(String title, String message) {
      try {
         reflectionHelper.invokeStatic("com.donutflipper.mod.client.notify.Notifications",
            "info", new Class[]{String.class, String.class},
            new Object[]{title, message});
      } catch (Exception e) {
         LOGGER.debug("Could not show notification", e);
      }
   }

   /** Runs a task on the main thread and blocks this worker thread until it completes (or times out). */
   private <T> T runOnMain(Minecraft client, Supplier<T> task) throws InterruptedException {
      AtomicReference<T> result = new AtomicReference<>();
      CountDownLatch latch = new CountDownLatch(1);
      client.execute(() -> {
         try {
            result.set(task.get());
         } catch (Exception e) {
            LOGGER.error("Main-thread task failed", e);
         } finally {
            latch.countDown();
         }
      });
      latch.await(1000, TimeUnit.MILLISECONDS);
      return result.get();
   }
}
