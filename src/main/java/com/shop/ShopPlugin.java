package com.shop;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class ShopPlugin extends JavaPlugin implements Listener {

    private static final int SIZE = 27;
    private static final String ADMIN = "shop.admin";

    private Economy eco;
    private File dataFile;

    /** slot do menu principal -> aba */
    private final Map<Integer, Tab> tabs = new LinkedHashMap<>();

    /** item e preço guardados entre /shop add e o clique no slot */
    private final Map<UUID, ItemStack> pendingAddItem = new HashMap<>();
    private final Map<UUID, Double> pendingAddPrice = new HashMap<>();
    /** preço digitado no comando /shop setprice <valor> */
    private final Map<UUID, Double> pendingSetPriceArg = new HashMap<>();
    /** jogadores esperando digitar o novo preço no chat */
    private final Map<UUID, int[]> awaitingChat = new HashMap<>();

    enum Mode { BROWSE, ADD, REMOVE, SETPRICE, REMOVE_TAB, ADD_TAB, QUANTITY }

    static class Entry {
        ItemStack item;
        double price;
        Entry(ItemStack item, double price) { this.item = item; this.price = price; }
    }

    static class Tab {
        String name;
        ItemStack icon;
        final Map<Integer, Entry> items = new LinkedHashMap<>();
        Tab(String name, ItemStack icon) { this.name = name; this.icon = icon; }
    }

    static class ShopHolder implements InventoryHolder {
        final Mode mode;
        final int tab; // -1 = menu principal
        final int slot; // slot do item (tela de quantidade)
        Inventory inv;
        ShopHolder(Mode mode, int tab) { this(mode, tab, -1); }
        ShopHolder(Mode mode, int tab, int slot) { this.mode = mode; this.tab = tab; this.slot = slot; }
        @Override public Inventory getInventory() { return inv; }
    }

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    public void onEnable() {
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) eco = rsp.getProvider();
        else getLogger().warning("Vault/economia nao encontrada! Compras desativadas.");

        dataFile = new File(getDataFolder(), "data.yml");
        load();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("shop").setExecutor(this);
    }

    @Override
    public void onDisable() { save(); }

    // ------------------------------------------------------------------ dados

    private void load() {
        tabs.clear();
        if (!dataFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection ts = cfg.getConfigurationSection("tabs");
        if (ts == null) return;
        for (String key : ts.getKeys(false)) {
            ItemStack icon = ts.getItemStack(key + ".icon");
            if (icon == null) continue;
            Tab tab = new Tab(ts.getString(key + ".name", "Aba"), icon);
            ConfigurationSection is = ts.getConfigurationSection(key + ".items");
            if (is != null) {
                for (String s : is.getKeys(false)) {
                    ItemStack it = is.getItemStack(s + ".item");
                    if (it == null) continue;
                    tab.items.put(Integer.parseInt(s), new Entry(it, is.getDouble(s + ".price")));
                }
            }
            tabs.put(Integer.parseInt(key), tab);
        }
    }

    private void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<Integer, Tab> t : tabs.entrySet()) {
            String p = "tabs." + t.getKey();
            cfg.set(p + ".name", t.getValue().name);
            cfg.set(p + ".icon", t.getValue().icon);
            for (Map.Entry<Integer, Entry> e : t.getValue().items.entrySet()) {
                cfg.set(p + ".items." + e.getKey() + ".item", e.getValue().item);
                cfg.set(p + ".items." + e.getKey() + ".price", e.getValue().price);
            }
        }
        try {
            getDataFolder().mkdirs();
            cfg.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe("Erro ao salvar data.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ comandos

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Apenas jogadores.");
            return true;
        }

        if (args.length == 0) {
            openMain(p, Mode.BROWSE);
            return true;
        }

        if (!p.hasPermission(ADMIN)) {
            msg(p, "§cVocê não tem permissão.");
            return true;
        }

        String sub = args[0].toLowerCase();
        clearPending(p);

        switch (sub) {
            case "add" -> {
                if (args.length < 2) { msg(p, "§cUse: /shop add <preço> ou /shop add aba"); return true; }
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand.getType().isAir()) { msg(p, "§cSegure um item na mão."); return true; }

                if (args[1].equalsIgnoreCase("aba")) {
                    if (firstFreeTabSlot() == -1) { msg(p, "§cO shop já tem 27 abas."); return true; }
                    ItemStack icon = hand.clone();
                    icon.setAmount(1);
                    pendingAddItem.put(p.getUniqueId(), icon);
                    msg(p, "§eClique no slot onde a aba vai ficar.");
                    openMain(p, Mode.ADD_TAB);
                    return true;
                }

                Double price = parsePrice(args[1]);
                if (price == null) { msg(p, "§cPreço inválido."); return true; }
                if (tabs.isEmpty()) { msg(p, "§cCrie uma aba primeiro: /shop add aba"); return true; }
                pendingAddItem.put(p.getUniqueId(), hand.clone());
                pendingAddPrice.put(p.getUniqueId(), price);
                msg(p, "§eEscolha a aba e depois o slot onde o item vai ficar.");
                openMain(p, Mode.ADD);
            }
            case
