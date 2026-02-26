package com.rlxo.huemc;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import com.google.gson.JsonObject;

public class HueMCController extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private static final String DEVICE_TYPE = "huemc#minecraft";

    private HueBridgeClient bridgeClient;
    private String bridgeIp;
    private String apiKey;

    private final Map<UUID, PendingBinding> pendingBindings = new ConcurrentHashMap<>();
    private final Map<String, LeverBinding> leverBindings = new HashMap<>();
    private Map<String, HueBridgeClient.HueLight> cachedLights = Map.of();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();
        bridgeClient = new HueBridgeClient(getLogger());

        registerCommand("bridge");
        registerCommand("lights");
        registerCommand("list");
        registerCommand("set");

        loadBindingsFromConfig();
        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info("Hue MC Controller loaded");
    }

    private void registerCommand(String name) {
        PluginCommand cmd = getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        } else {
            getLogger().warning("Command not found in plugin.yml: " + name);
        }
    }

    private void loadConfig() {
        bridgeIp = getConfig().getString("bridge.ip", "").trim();
        apiKey = getConfig().getString("bridge.apiKey", "").trim();
    }

    private void saveBridgeConfig() {
        getConfig().set("bridge.ip", bridgeIp == null ? "" : bridgeIp);
        getConfig().set("bridge.apiKey", apiKey == null ? "" : apiKey);
        saveConfig();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        switch (name) {
            case "bridge":
                return handleBridge(sender, args);
            case "lights":
                return handleLights(sender, args);
            case "list":
                return handleList(sender, args);
            case "set":
                return handleSet(sender, args);
            default:
                return false;
        }
    }

    private boolean handleBridge(CommandSender sender, String[] args) {
        if (args.length != 2 || !"set".equalsIgnoreCase(args[0])) {
            sender.sendMessage(ChatColor.RED + "Usage: /bridge set <ip>");
            return true;
        }

        String ip = args[1].trim();
        if (!ip.matches("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")) {
            sender.sendMessage(ChatColor.RED + "That doesn't look like an IPv4 address.");
            return true;
        }

        bridgeIp = ip;
        apiKey = ""; // clear old key when IP changes
        saveBridgeConfig();

        sender.sendMessage(ChatColor.GREEN + "Saved Hue bridge IP: " + ChatColor.AQUA + bridgeIp);
        sender.sendMessage(ChatColor.YELLOW + "Press the Hue bridge link button now. Pairing will be attempted for 30 seconds...");

        startPairingFlow(sender);
        return true;
    }

    private void startPairingFlow(CommandSender sender) {
        if (bridgeIp == null || bridgeIp.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No bridge IP configured.");
            return;
        }

        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<BukkitTask> taskRef = new AtomicReference<>();

        BukkitTask task = Bukkit.getScheduler().runTaskTimerAsynchronously(
                this,
                () -> {
                    int attempt = attempts.incrementAndGet();
                    HueBridgeClient.RegistrationResult result = bridgeClient.registerUser(bridgeIp, DEVICE_TYPE);

                    if (result.success()) {
                        apiKey = result.username();
                        saveBridgeConfig();
                        sendMainThread(sender, ChatColor.GREEN + "Paired with Hue bridge. API key saved.");
                        cancelTask(taskRef.get());
                        return;
                    }

                    if (attempt >= 10) { // roughly 30 seconds at 3s interval
                        sendMainThread(sender, ChatColor.RED + "Pairing timed out. Press the link button and try again.");
                        cancelTask(taskRef.get());
                        return;
                    }

                    if (result.linkButtonNotPressed()) {
                        if (attempt == 1) {
                            sendMainThread(sender, ChatColor.YELLOW + "Waiting for link button press...");
                        }
                    } else if (!result.message().isEmpty()) {
                        sendMainThread(sender, ChatColor.RED + "Hue bridge error: " + result.message());
                    }
                },
                20L,
                60L);

        taskRef.set(task);
    }

    private void cancelTask(BukkitTask task) {
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
    }

    private void sendMainThread(CommandSender sender, String message) {
        Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(message));
    }

    private boolean ensureBridgeReady(CommandSender sender) {
        if (bridgeIp == null || bridgeIp.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No bridge IP set. Run /bridge set <ip> first.");
            return false;
        }
        if (apiKey == null || apiKey.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Not paired yet. Press the link button and run /bridge set <ip>.");
            return false;
        }
        return true;
    }

    private boolean handleLights(CommandSender sender, String[] args) {
        if (args.length != 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /lights <on|off>");
            return true;
        }

        if (!ensureBridgeReady(sender)) {
            return true;
        }

        boolean turnOn;
        String option = args[0].toLowerCase(Locale.ROOT);
        if (option.equals("on")) {
            turnOn = true;
        } else if (option.equals("off")) {
            turnOn = false;
        } else {
            sender.sendMessage(ChatColor.RED + "Usage: /lights <on|off>");
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Sending command to Hue bridge...");

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            HueBridgeClient.ActionResult result = bridgeClient.setGroupPower(bridgeIp, apiKey, turnOn);
            if (result.success()) {
                sendMainThread(sender, ChatColor.GREEN + "Lights turned " + (turnOn ? "on" : "off") + ".");
            } else {
                sendMainThread(sender, ChatColor.RED + "Failed to update lights: " + result.message());
            }
        });

        return true;
    }

    private boolean handleList(CommandSender sender, String[] args) {
        if (args.length != 1 || !args[0].equalsIgnoreCase("lights")) {
            sender.sendMessage(ChatColor.RED + "Usage: /list lights");
            return true;
        }

        if (!ensureBridgeReady(sender)) {
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Fetching Hue lights...");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            Map<String, HueBridgeClient.HueLight> lights = bridgeClient.listLights(bridgeIp, apiKey);
            cachedLights = lights;
            if (lights.isEmpty()) {
                sendMainThread(sender, ChatColor.RED + "No lights found.");
                return;
            }
            StringBuilder sb = new StringBuilder(ChatColor.GREEN + "Lights: ");
            boolean first = true;
            for (HueBridgeClient.HueLight light : lights.values()) {
                if (!first) {
                    sb.append(ChatColor.GRAY).append(", ");
                }
                sb.append(ChatColor.AQUA).append(light.name());
                first = false;
            }
            sendMainThread(sender, sb.toString());
        });
        return true;
    }

    private boolean handleSet(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Only players can bind levers.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + "Usage: /set <light <name>|scene>");
            return true;
        }

        if (!ensureBridgeReady(sender)) {
            return true;
        }

        String mode = args[0].toLowerCase(Locale.ROOT);
        if (mode.equals("light")) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Usage: /set light <name>");
                return true;
            }
            String requestedName = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
            sender.sendMessage(ChatColor.YELLOW + "Looking up light '" + requestedName + "'...");
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                Map<String, HueBridgeClient.HueLight> lights = bridgeClient.listLights(bridgeIp, apiKey);
                cachedLights = lights;
                String lightId = findLightIdByName(lights, requestedName);
                if (lightId == null) {
                    sendMainThread(sender, ChatColor.RED + "Light not found: " + requestedName);
                    return;
                }

                pendingBindings.put(player.getUniqueId(), new PendingBinding(BindingType.LIGHT, lightId, Map.of()));
                sendMainThread(sender, ChatColor.GREEN + "Light found. Right-click a lever to bind it within 60 seconds.");
                schedulePendingTimeout(player.getUniqueId());
            });
            return true;
        }

        if (mode.equals("scene")) {
            sender.sendMessage(ChatColor.YELLOW + "Capturing current scene...");
            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                Map<String, HueBridgeClient.HueLight> lights = bridgeClient.listLights(bridgeIp, apiKey);
                cachedLights = lights;
                if (lights.isEmpty()) {
                    sendMainThread(sender, ChatColor.RED + "No lights found.");
                    return;
                }
                Map<String, JsonObject> states = new HashMap<>();
                for (Map.Entry<String, HueBridgeClient.HueLight> entry : lights.entrySet()) {
                    states.put(entry.getKey(), entry.getValue().state().deepCopy());
                }
                pendingBindings.put(player.getUniqueId(), new PendingBinding(BindingType.SCENE, null, states));
                sendMainThread(sender, ChatColor.GREEN + "Scene captured. Right-click a lever to bind it within 60 seconds.");
                schedulePendingTimeout(player.getUniqueId());
            });
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Usage: /set <light <name>|scene>");
        return true;
    }

    private void schedulePendingTimeout(UUID playerId) {
        Bukkit.getScheduler().runTaskLater(this, () -> pendingBindings.remove(playerId), 20L * 60);
    }

    private String findLightIdByName(Map<String, HueBridgeClient.HueLight> lights, String requestedName) {
        String lower = requestedName.toLowerCase(Locale.ROOT);
        for (HueBridgeClient.HueLight light : lights.values()) {
            if (light.name().equalsIgnoreCase(requestedName)) {
                return light.id();
            }
            if (light.name().toLowerCase(Locale.ROOT).contains(lower)) {
                return light.id();
            }
        }
        return null;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null || event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getClickedBlock().getType() != Material.LEVER) {
            return;
        }

        UUID playerId = event.getPlayer().getUniqueId();
        PendingBinding pending = pendingBindings.remove(playerId);
        if (pending == null) {
            return;
        }

        String key = locationKey(event.getClickedBlock().getLocation());
        LeverBinding binding = new LeverBinding(pending.type(), pending.lightId(), copyStates(pending.sceneStates()));
        leverBindings.put(key, binding);
        saveBindingsToConfig();

        event.getPlayer().sendMessage(ChatColor.GREEN + "Lever bound to " + describeBinding(binding) + ".");
    }

    @EventHandler
    public void onLeverPower(BlockRedstoneEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.LEVER) {
            return;
        }

        boolean isOn = event.getNewCurrent() > 0;
        String key = locationKey(block.getLocation());
        LeverBinding binding = leverBindings.get(key);
        if (binding == null) {
            return;
        }

        if (!ensureBridgeReady(getServer().getConsoleSender())) {
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            if (binding.type() == BindingType.LIGHT) {
                HueBridgeClient.ActionResult result = bridgeClient.setLightPower(bridgeIp, apiKey, binding.lightId(), isOn);
                if (!result.success()) {
                    getLogger().warning("Failed to toggle light: " + result.message());
                }
                return;
            }

            if (binding.type() == BindingType.SCENE) {
                if (binding.sceneStates().isEmpty()) {
                    getLogger().warning("Scene binding has no states stored.");
                    return;
                }
                for (Map.Entry<String, JsonObject> entry : binding.sceneStates().entrySet()) {
                    JsonObject body = isOn ? entry.getValue().deepCopy() : offState();
                    HueBridgeClient.ActionResult result = bridgeClient.setLightState(bridgeIp, apiKey, entry.getKey(), body);
                    if (!result.success()) {
                        getLogger().warning("Failed to apply scene to light " + entry.getKey() + ": " + result.message());
                    }
                }
            }
        });
    }

    private JsonObject offState() {
        JsonObject obj = new JsonObject();
        obj.addProperty("on", false);
        return obj;
    }

    private String describeBinding(LeverBinding binding) {
        return binding.type() == BindingType.LIGHT ? ("light " + binding.lightId()) : "captured scene";
    }

    private Map<String, JsonObject> copyStates(Map<String, JsonObject> source) {
        Map<String, JsonObject> out = new HashMap<>();
        for (Map.Entry<String, JsonObject> e : source.entrySet()) {
            out.put(e.getKey(), e.getValue().deepCopy());
        }
        return out;
    }

    private String locationKey(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    private void loadBindingsFromConfig() {
        leverBindings.clear();
        ConfigurationSection section = getConfig().getConfigurationSection("bindings");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection b = section.getConfigurationSection(key);
            if (b == null) {
                continue;
            }
            String typeStr = b.getString("type", "");
            BindingType type;
            try {
                type = BindingType.valueOf(typeStr);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            String lightId = b.getString("lightId", null);
            Map<String, JsonObject> states = new HashMap<>();
            ConfigurationSection stateSection = b.getConfigurationSection("states");
            if (stateSection != null) {
                for (String lightKey : stateSection.getKeys(false)) {
                    String json = stateSection.getString(lightKey, "{}");
                    states.put(lightKey, com.google.gson.JsonParser.parseString(json).getAsJsonObject());
                }
            }
            leverBindings.put(key, new LeverBinding(type, lightId, states));
        }
    }

    private void saveBindingsToConfig() {
        getConfig().set("bindings", null);
        for (Map.Entry<String, LeverBinding> entry : leverBindings.entrySet()) {
            String key = entry.getKey();
            LeverBinding binding = entry.getValue();
            String path = "bindings." + key;
            getConfig().set(path + ".type", binding.type().name());
            getConfig().set(path + ".lightId", binding.lightId());
            for (Map.Entry<String, JsonObject> state : binding.sceneStates().entrySet()) {
                getConfig().set(path + ".states." + state.getKey(), state.getValue().toString());
            }
        }
        saveConfig();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("bridge")) {
            if (args.length == 1) {
                return Collections.singletonList("set");
            }
        } else if (name.equals("lights")) {
            if (args.length == 1) {
                return Arrays.asList("on", "off");
            }
        } else if (name.equals("list")) {
            if (args.length == 1) {
                return Collections.singletonList("lights");
            }
        } else if (name.equals("set")) {
            if (args.length == 1) {
                return Arrays.asList("light", "scene");
            }
            if (args.length >= 2 && args[0].equalsIgnoreCase("light")) {
                return cachedLights.values().stream().map(HueBridgeClient.HueLight::name).toList();
            }
        }
        return Collections.emptyList();
    }

    private enum BindingType {
        LIGHT,
        SCENE
    }

    private record PendingBinding(BindingType type, String lightId, Map<String, JsonObject> sceneStates) { }

    private record LeverBinding(BindingType type, String lightId, Map<String, JsonObject> sceneStates) { }
}
