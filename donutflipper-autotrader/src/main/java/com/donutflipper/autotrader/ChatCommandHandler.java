package com.donutflipper.autotrader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChatCommandHandler {
   private static final Logger LOGGER = LoggerFactory.getLogger("donutflipper-autotrader");

   /** message has no leading slash (Fabric's command-send event strips it). */
   public static boolean handleCommand(String message) {
      if (!message.equals("autotrader") && !message.startsWith("autotrader ")) {
         return false;
      }

      String[] parts = message.split(" ");
      if (parts.length < 2) {
         printHelp();
         return true;
      }

      String cmd = parts[1].toLowerCase();
      AutoTraderConfig config = AutoTraderConfig.instance();

      try {
         switch (cmd) {
            case "enable":
               config.setEnabled(true);
               chat("§a[AutoTrader] Enabled");
               break;
            case "disable":
               config.setEnabled(false);
               chat("§c[AutoTrader] Disabled");
               break;
            case "status":
               printStatus();
               break;
            case "roi":
               if (parts.length < 3) {
                  chat("Usage: /autotrader roi <percent>");
                  break;
               }
               double roi = Double.parseDouble(parts[2]);
               config.setMinRoi(roi);
               chat("§b[AutoTrader] Min ROI set to " + config.getMinRoiPercent() + "%");
               break;
            case "profit":
               if (parts.length < 3) {
                  chat("Usage: /autotrader profit <dollars>");
                  break;
               }
               long profit = Long.parseLong(parts[2]) * 100; // Convert to cents
               config.setMinProfit(profit);
               chat("§b[AutoTrader] Min profit set to $" + (config.getMinProfitCents() / 100));
               break;
            case "markup":
               if (parts.length < 3) {
                  chat("Usage: /autotrader markup <percent>");
                  break;
               }
               double markup = Double.parseDouble(parts[2]);
               config.setSellMarkup(markup);
               chat("§b[AutoTrader] Sell price set to " + config.getSellMarkupPercent() + "% of true value");
               break;
            case "ratelimit":
               if (parts.length < 3) {
                  chat("Usage: /autotrader ratelimit <milliseconds>");
                  break;
               }
               long ms = Long.parseLong(parts[2]);
               config.setRateLimit(ms);
               chat("§b[AutoTrader] Rate limit set to " + config.getRateLimitMs() + "ms");
               break;
            case "maxspend":
               if (parts.length < 3) {
                  chat("Usage: /autotrader maxspend <dollars>");
                  break;
               }
               long maxSpend = Long.parseLong(parts[2]) * 100; // Convert to cents
               config.setMaxSpend(maxSpend);
               chat("§b[AutoTrader] Max spend per item set to $" + (config.getMaxSpendCents() / 100));
               break;
            case "blacklist":
               if (parts.length < 3) {
                  printBlacklist();
                  break;
               }
               String itemName = message.substring("autotrader blacklist ".length()).trim();
               boolean added = config.toggleBlacklist(itemName);
               chat(added
                  ? "§c[AutoTrader] Blacklisted: " + itemName
                  : "§a[AutoTrader] Removed from blacklist: " + itemName);
               break;
            default:
               printHelp();
         }
      } catch (NumberFormatException e) {
         chat("§c[AutoTrader] Invalid number: " + e.getMessage());
      }

      return true;
   }

   private static void printStatus() {
      AutoTraderConfig config = AutoTraderConfig.instance();
      chat("§6=== AutoTrader Status ===");
      chat("Enabled: " + config.isEnabled());
      chat("Min ROI: " + config.getMinRoiPercent() + "%");
      chat("Min Profit: $" + (config.getMinProfitCents() / 100));
      chat("Sell Price: " + config.getSellMarkupPercent() + "% of true value");
      chat("Rate Limit: " + config.getRateLimitMs() + "ms");
      chat("Max Spend: $" + (config.getMaxSpendCents() / 100));
      chat("Blacklist: " + (config.getBlacklist().isEmpty() ? "(empty)" : String.join(", ", config.getBlacklist())));
   }

   private static void printBlacklist() {
      AutoTraderConfig config = AutoTraderConfig.instance();
      if (config.getBlacklist().isEmpty()) {
         chat("§6[AutoTrader] Blacklist is empty. Usage: /autotrader blacklist <item name>");
      } else {
         chat("§6[AutoTrader] Blacklist: " + String.join(", ", config.getBlacklist()));
      }
   }

   private static void printHelp() {
      chat("§6=== AutoTrader Commands ===");
      chat("/autotrader enable - Enable AutoTrader");
      chat("/autotrader disable - Disable AutoTrader");
      chat("/autotrader status - Show current settings");
      chat("/autotrader roi <percent> - Set minimum ROI (default 5%)");
      chat("/autotrader profit <dollars> - Set minimum profit (default $100)");
      chat("/autotrader markup <percent> - Sell price as % of true value, ask+profit (default 90%)");
      chat("/autotrader ratelimit <ms> - Set rate limit between trades (default 5000ms)");
      chat("/autotrader maxspend <dollars> - Max price to pay for a single item (default $1,000,000)");
      chat("/autotrader blacklist <item name> - Toggle an item on/off the do-not-buy list");
      chat("/autotrader blacklist - Show the current blacklist");
   }

   private static void chat(String message) {
      LOGGER.info(message);
      LocalPlayer player = Minecraft.getInstance().player;
      if (player != null) {
         player.displayClientMessage(Component.literal(message), false);
      }
   }
}
