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

    enum Mode { BROWSE, ADD, REMOVE, SETPRICE, REMOVE_TAB, ADD_TAB }

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
        Inventory inv;
        ShopHolder(Mode mode, int tab) { this.mode = mode; this.tab = tab; }
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
            case "remove" -> {
                if (args.length >= 2 && args[1].equalsIgnoreCase("aba")) {
                    openMain(p, Mode.REMOVE_TAB);
                } else {
                    openMain(p, Mode.REMOVE);
                }
            }
            case "setprice" -> {
                if (args.length >= 2) {
                    Double price = parsePrice(args[1]);
                    if (price == null) { msg(p, "§cPreço inválido."); return true; }
                    pendingSetPriceArg.put(p.getUniqueId(), price);
                }
                openMain(p, Mode.SETPRICE);
            }
            default -> msg(p, "§cUse: /shop, /shop add <preço>, /shop add aba, /shop remove [aba], /shop setprice [preço]");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.hasPermission(ADMIN)) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("add", "remove", "setprice"))
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
        } else if (args.length == 2) {
            String a = args[0].toLowerCase();
            if ((a.equals("add") || a.equals("remove")) && "aba".startsWith(args[1].toLowerCase())) out.add("aba");
        }
        return out;
    }

    // ------------------------------------------------------------------ menus

    private void openMain(Player p, Mode mode) {
        String title = switch (mode) {
            case ADD -> "§8Shop » Escolha a aba";
            case REMOVE -> "§8Shop » Remover item";
            case SETPRICE -> "§8Shop » Mudar preço";
            case REMOVE_TAB -> "§8Shop » Remover aba";
            case ADD_TAB -> "§8Shop » Escolha o slot da aba";
            default -> "§8Shop";
        };
        ShopHolder holder = new ShopHolder(mode, -1);
        Inventory inv = Bukkit.createInventory(holder, SIZE, title);
        holder.inv = inv;

        for (Map.Entry<Integer, Tab> e : tabs.entrySet()) {
            ItemStack it = e.getValue().icon.clone();
            ItemMeta meta = it.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§e§l" + e.getValue().name);
                meta.setLore(List.of(mode == Mode.REMOVE_TAB
                        ? "§cClique para REMOVER esta aba"
                        : mode == Mode.ADD_TAB
                        ? "§cSlot ocupado"
                        : "§7Clique para abrir"));
                it.setItemMeta(meta);
            }
            inv.setItem(e.getKey(), it);
        }
        p.openInventory(inv);
    }

    private void openTab(Player p, int tabSlot, Mode mode) {
        Tab tab = tabs.get(tabSlot);
        if (tab == null) return;
        String title = switch (mode) {
            case ADD -> "§8Escolha o slot";
            case REMOVE -> "§8Remover item";
            case SETPRICE -> "§8Mudar preço";
            default -> "§8Shop » " + tab.name;
        };
        ShopHolder holder = new ShopHolder(mode, tabSlot);
        Inventory inv = Bukkit.createInventory(holder, SIZE, title);
        holder.inv = inv;

        for (Map.Entry<Integer, Entry> e : tab.items.entrySet()) {
            ItemStack it = e.getValue().item.clone();
            ItemMeta meta = it.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                lore.add("");
                lore.add("§7Preço: §a" + fmt(e.getValue().price));
                switch (mode) {
                    case BROWSE -> { lore.add("§eEsquerdo: comprar"); lore.add("§eDireito: voltar"); }
                    case REMOVE -> lore.add("§cClique para remover");
                    case SETPRICE -> lore.add("§eClique para mudar o preço");
                    default -> lore.add("§cSlot ocupado");
                }
                meta.setLore(lore);
                it.setItemMeta(meta);
            }
            inv.setItem(e.getKey(), it);
        }
        p.openInventory(inv);
    }

    // ------------------------------------------------------------------ eventos

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof ShopHolder) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof ShopHolder h)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;

        int slot = e.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;

        if (h.tab == -1) clickMain(p, h, slot);
        else clickTab(p, h, slot, e.isRightClick());
    }

    private void clickMain(Player p, ShopHolder h, int slot) {
        Tab tab = tabs.get(slot);

        if (h.mode == Mode.ADD_TAB) {
            if (!p.hasPermission(ADMIN)) return;
            if (tab != null) { msg(p, "§cEsse slot já está ocupado."); return; }
            ItemStack icon = pendingAddItem.remove(p.getUniqueId());
            if (icon == null) { p.closeInventory(); return; }
            String name = niceName(icon);
            tabs.put(slot, new Tab(name, icon));
            save();
            p.closeInventory();
            msg(p, "§aAba §f" + name + " §acriada no slot " + slot + ".");
            return;
        }

        if (tab == null) return;

        if (h.mode == Mode.REMOVE_TAB) {
            if (!p.hasPermission(ADMIN)) return;
            tabs.remove(slot);
            save();
            msg(p, "§aAba §f" + tab.name + " §aremovida.");
            openMain(p, Mode.REMOVE_TAB);
            return;
        }
        openTab(p, slot, h.mode);
    }

    private void clickTab(Player p, ShopHolder h, int slot, boolean right) {
        Tab tab = tabs.get(h.tab);
        if (tab == null) { p.closeInventory(); return; }
        Entry entry = tab.items.get(slot);

        switch (h.mode) {
            case BROWSE -> {
                if (right) { openMain(p, Mode.BROWSE); return; }
                if (entry != null) buy(p, entry);
            }
            case ADD -> {
                if (!p.hasPermission(ADMIN)) return;
                if (entry != null) { msg(p, "§cEsse slot já está ocupado."); return; }
                ItemStack item = pendingAddItem.remove(p.getUniqueId());
                Double price = pendingAddPrice.remove(p.getUniqueId());
                if (item == null || price == null) { p.closeInventory(); return; }
                tab.items.put(slot, new Entry(item, price));
                save();
                p.closeInventory();
                msg(p, "§aItem adicionado na aba §f" + tab.name + " §apor §f" + fmt(price) + "§a.");
            }
            case REMOVE -> {
                if (!p.hasPermission(ADMIN) || entry == null) return;
                tab.items.remove(slot);
                save();
                msg(p, "§aItem removido.");
                openTab(p, h.tab, Mode.REMOVE);
            }
            case SETPRICE -> {
                if (!p.hasPermission(ADMIN) || entry == null) return;
                Double arg = pendingSetPriceArg.remove(p.getUniqueId());
                if (arg != null) {
                    entry.price = arg;
                    save();
                    msg(p, "§aPreço alterado para §f" + fmt(arg) + "§a.");
                    openTab(p, h.tab, Mode.SETPRICE);
                } else {
                    awaitingChat.put(p.getUniqueId(), new int[]{h.tab, slot});
                    p.closeInventory();
                    msg(p, "§eDigite o novo preço no chat (ou §fcancelar§e).");
                }
            }
            default -> { }
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        int[] target = awaitingChat.get(p.getUniqueId());
        if (target == null) return;
        e.setCancelled(true);
        String text = e.getMessage().trim();

        Bukkit.getScheduler().runTask(this, () -> {
            if (text.equalsIgnoreCase("cancelar")) {
                awaitingChat.remove(p.getUniqueId());
                msg(p, "§cCancelado.");
                return;
            }
            Double price = parsePrice(text);
            if (price == null) { msg(p, "§cPreço inválido. Digite um número ou §fcancelar§c."); return; }
            awaitingChat.remove(p.getUniqueId());
            Tab tab = tabs.get(target[0]);
            Entry en = tab == null ? null : tab.items.get(target[1]);
            if (en == null) { msg(p, "§cItem não existe mais."); return; }
            en.price = price;
            save();
            msg(p, "§aPreço alterado para §f" + fmt(price) + "§a.");
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { clearPending(e.getPlayer()); }

    // ------------------------------------------------------------------ utilidades

    private void buy(Player p, Entry en) {
        if (eco == null) { msg(p, "§cEconomia indisponível."); return; }
        if (!hasSpace(p, en.item)) { msg(p, "§cInventário cheio."); return; }
        if (eco.getBalance(p) < en.price) { msg(p, "§cSaldo insuficiente. Custa " + fmt(en.price) + "."); return; }
        eco.withdrawPlayer(p, en.price);
        p.getInventory().addItem(en.item.clone());
        msg(p, "§aCompra feita por §f" + fmt(en.price) + "§a.");
    }

    private boolean hasSpace(Player p, ItemStack item) {
        int needed = item.getAmount();
        for (ItemStack s : p.getInventory().getStorageContents()) {
            if (s == null || s.getType().isAir()) return true;
            if (s.isSimilar(item)) {
                needed -= s.getMaxStackSize() - s.getAmount();
                if (needed <= 0) return true;
            }
        }
        return false;
    }

    private int firstFreeTabSlot() {
        for (int i = 0; i < SIZE; i++) if (!tabs.containsKey(i)) return i;
        return -1;
    }

    private void clearPending(Player p) {
        UUID id = p.getUniqueId();
        pendingAddItem.remove(id);
        pendingAddPrice.remove(id);
        pendingSetPriceArg.remove(id);
        awaitingChat.remove(id);
    }

    private Double parsePrice(String s) {
        try {
            double v = Double.parseDouble(s.replace(",", "."));
            return (v < 0 || Double.isNaN(v) || Double.isInfinite(v)) ? null : v;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String fmt(double v) {
        return eco != null ? eco.format(v) : String.format("$%.2f", v);
    }

    private String niceName(ItemStack item) {
        ItemMeta m = item.getItemMeta();
        if (m != null && m.hasDisplayName()) return ChatColor.stripColor(m.getDisplayName());
        String[] parts = item.getType().name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String s : parts) sb.append(Character.toUpperCase(s.charAt(0))).append(s.substring(1)).append(' ');
        return sb.toString().trim();
    }

    private void msg(Player p, String m) { p.sendMessage("§6[Shop] " + m); }
}
