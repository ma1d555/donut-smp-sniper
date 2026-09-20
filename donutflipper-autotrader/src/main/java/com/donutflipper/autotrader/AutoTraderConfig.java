package com.donutflipper.autotrader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for AutoTrader, controlled via chat commands and persisted to
 * config/donutflipper-autotrader.json so settings survive restarts.
 */
public class AutoTraderConfig {
   private static final Logger LOGGER = LoggerFactory.getLogger("donutflipper-autotrader");
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("donutflipper-autotrader.json");
   private static AutoTraderConfig INSTANCE;

   public boolean enabled = false;
   public double minRoiPercent = 5.0;
   public long minProfitCents = 10_000;
   /** Percent of the item's true detected value (ask + profit) to list it for, e.g. 90 = sell at 90% of true value. */
   public double sellMarkupPercent = 90.0;
   public long rateLimitMs = 5000;
   /** Highest price (in cents) willing to pay for a single item, default $1,000,000. */
   public long maxSpendCents = 100_000_000L;
   private final Set<String> blacklist = Collections.synchronizedSet(new LinkedHashSet<>());

   public static AutoTraderConfig instance() {
      if (INSTANCE == null) {
         INSTANCE = new AutoTraderConfig();
         INSTANCE.load();
         LOGGER.info("AutoTrader config loaded from {}", CONFIG_PATH);
      }
      return INSTANCE;
   }

   private static final class ConfigData {
      boolean enabled;
      double minRoiPercent;
      long minProfitCents;
      double sellMarkupPercent;
      long rateLimitMs;
      long maxSpendCents;
      List<String> blacklist;
   }

   private void load() {
      if (!Files.exists(CONFIG_PATH)) {
         return;
      }
      try {
         String json = Files.readString(CONFIG_PATH);
         ConfigData data = GSON.fromJson(json, ConfigData.class);
         if (data == null) {
            return;
         }
         enabled = data.enabled;
         minRoiPercent = data.minRoiPercent;
         minProfitCents = data.minProfitCents;
         sellMarkupPercent = data.sellMarkupPercent;
         rateLimitMs = data.rateLimitMs;
         maxSpendCents = data.maxSpendCents;
         blacklist.clear();
         if (data.blacklist != null) {
            blacklist.addAll(data.blacklist);
         }
      } catch (IOException | com.google.gson.JsonSyntaxException e) {
         LOGGER.error("Failed to load AutoTrader config, using defaults", e);
      }
   }

   private void save() {
      try {
         ConfigData data = new ConfigData();
         data.enabled = enabled;
         data.minRoiPercent = minRoiPercent;
         data.minProfitCents = minProfitCents;
         data.sellMarkupPercent = sellMarkupPercent;
         data.rateLimitMs = rateLimitMs;
         data.maxSpendCents = maxSpendCents;
         data.blacklist = new ArrayList<>(blacklist);

         Files.createDirectories(CONFIG_PATH.getParent());
         Files.writeString(CONFIG_PATH, GSON.toJson(data));
      } catch (IOException e) {
         LOGGER.error("Failed to save AutoTrader config", e);
      }
   }

   public boolean isEnabled() {
      return enabled;
   }

   public double getMinRoiPercent() {
      return minRoiPercent;
   }

   public long getMinProfitCents() {
      return minProfitCents;
   }

   public double getSellMarkupPercent() {
      return sellMarkupPercent;
   }

   public long getRateLimitMs() {
      return rateLimitMs;
   }

   public long getMaxSpendCents() {
      return maxSpendCents;
   }

   public void setEnabled(boolean value) {
      enabled = value;
      LOGGER.info("AutoTrader " + (value ? "enabled" : "disabled"));
      save();
   }

   public void setMinRoi(double percent) {
      minRoiPercent = Math.max(0, percent);
      LOGGER.info("Min ROI set to {}%", minRoiPercent);
      save();
   }

   public void setMinProfit(long cents) {
      minProfitCents = Math.max(0, cents);
      LOGGER.info("Min profit set to ${}", minProfitCents / 100);
      save();
   }

   public void setSellMarkup(double percent) {
      sellMarkupPercent = Math.max(1.0, Math.min(100.0, percent));
      LOGGER.info("Sell markup set to {}% of true value", sellMarkupPercent);
      save();
   }

   public void setRateLimit(long ms) {
      rateLimitMs = Math.max(1000, ms);
      LOGGER.info("Rate limit set to {}ms", rateLimitMs);
      save();
   }

   public void setMaxSpend(long cents) {
      maxSpendCents = Math.max(0, cents);
      LOGGER.info("Max spend per item set to ${}", maxSpendCents / 100);
      save();
   }

   /** Returns true if it is now blacklisted (was added), false if it was removed. */
   public boolean toggleBlacklist(String itemName) {
      String key = itemName.trim().toLowerCase();
      boolean nowBlacklisted;
      if (blacklist.remove(key)) {
         nowBlacklisted = false;
      } else {
         blacklist.add(key);
         nowBlacklisted = true;
      }
      save();
      return nowBlacklisted;
   }

   public boolean isBlacklisted(String itemName) {
      if (itemName == null) return false;
      return blacklist.contains(itemName.trim().toLowerCase());
   }

   public Set<String> getBlacklist() {
      return Collections.unmodifiableSet(blacklist);
   }
}
