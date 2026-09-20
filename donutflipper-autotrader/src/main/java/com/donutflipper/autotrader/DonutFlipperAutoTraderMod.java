package com.donutflipper.autotrader;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DonutFlipperAutoTraderMod implements ClientModInitializer {
   private static final Logger LOGGER = LoggerFactory.getLogger("donutflipper-autotrader");

   private static final KeyMapping TOGGLE_KEY = KeyBindingHelper.registerKeyBinding(new KeyMapping(
      "key.donutflipper-autotrader.toggle",
      InputConstants.Type.KEYSYM,
      InputConstants.KEY_V,
      KeyMapping.Category.MISC
   ));

   @Override
   public void onInitializeClient() {
      LOGGER.info("DonutFlipper AutoTrader initialized");
      LOGGER.info("Press the AutoTrader toggle key (default V, rebindable in Controls) or use /autotrader");
      AutoTraderConfig.instance();

      // Intercept "/autotrader ..." before it reaches the server (it isn't a real
      // server command) and handle it locally instead.
      ClientSendMessageEvents.ALLOW_COMMAND.register(message -> !ChatCommandHandler.handleCommand(message));

      // Feed server chat/system messages to AutoTrader so it can detect sell failures (e.g. full auction slots).
      ClientReceiveMessageEvents.GAME.register((message, overlay) -> AutoTrader.instance().onGameMessage(message.getString()));

      ClientTickEvents.END_CLIENT_TICK.register(DonutFlipperAutoTraderMod::checkKeybind);

      // Start trading loop
      Thread.ofVirtual().start(() -> {
         while (true) {
            try {
               Minecraft client = Minecraft.getInstance();
               if (client != null) {
                  AutoTrader.instance().tick(client);
               }
               Thread.sleep(100);
            } catch (Exception e) {
               LOGGER.error("AutoTrader loop error: {}", e.getMessage());
               try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
            }
         }
      });
   }

   private static void checkKeybind(Minecraft client) {
      while (TOGGLE_KEY.consumeClick()) {
         AutoTraderConfig config = AutoTraderConfig.instance();
         boolean nowEnabled = !config.isEnabled();
         config.setEnabled(nowEnabled);
         LOGGER.info("AutoTrader toggle key pressed - {}", nowEnabled ? "enabled" : "disabled");

         LocalPlayer player = client.player;
         if (player != null) {
            player.displayClientMessage(
               Component.literal(nowEnabled ? "§a[AutoTrader] Enabled" : "§c[AutoTrader] Disabled"),
               false
            );
         }
      }
   }
}
