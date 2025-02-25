package wtf.choco.veinminer;

import com.google.common.base.Preconditions;
import com.google.gson.Gson;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.AdvancedPie;
import org.bstats.charts.DrilldownPie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import wtf.choco.veinminer.anticheat.AntiCheatHook;
import wtf.choco.veinminer.anticheat.AntiCheatHookAAC;
import wtf.choco.veinminer.anticheat.AntiCheatHookAntiAura;
import wtf.choco.veinminer.anticheat.AntiCheatHookMatrix;
import wtf.choco.veinminer.anticheat.AntiCheatHookNCP;
import wtf.choco.veinminer.anticheat.AntiCheatHookSpartan;
import wtf.choco.veinminer.api.ClientActivation;
import wtf.choco.veinminer.api.VeinMinerManager;
import wtf.choco.veinminer.commands.VeinMinerCommand;
import wtf.choco.veinminer.data.PlayerPreferences;
import wtf.choco.veinminer.data.block.VeinBlock;
import wtf.choco.veinminer.economy.EconomyModifier;
import wtf.choco.veinminer.economy.EmptyEconomyModifier;
import wtf.choco.veinminer.economy.VaultBasedEconomyModifier;
import wtf.choco.veinminer.integration.McMMOIntegration;
import wtf.choco.veinminer.integration.WorldGuardIntegration;
import wtf.choco.veinminer.listener.BreakBlockListener;
import wtf.choco.veinminer.listener.ItemCollectionListener;
import wtf.choco.veinminer.listener.PlayerDataListener;
import wtf.choco.veinminer.metrics.AntiCheatInformation;
import wtf.choco.veinminer.metrics.StatTracker;
import wtf.choco.veinminer.network.PluginMessageProtocol;
import wtf.choco.veinminer.network.message.PluginMessageInHandshake;
import wtf.choco.veinminer.network.message.PluginMessageInToggleVeinMiner;
import wtf.choco.veinminer.pattern.PatternExpansive;
import wtf.choco.veinminer.pattern.PatternRegistry;
import wtf.choco.veinminer.pattern.PatternThorough;
import wtf.choco.veinminer.pattern.VeinMiningPattern;
import wtf.choco.veinminer.tool.ToolCategory;
import wtf.choco.veinminer.utils.ConfigWrapper;
import wtf.choco.veinminer.utils.NamespacedKeyUtil;
import wtf.choco.veinminer.utils.ReflectionUtil;
import wtf.choco.veinminer.utils.UpdateChecker;
import wtf.choco.veinminer.utils.UpdateChecker.UpdateReason;
import wtf.choco.veinminer.utils.VMConstants;

/**
 * The VeinMiner {@link JavaPlugin} class.
 */
public final class VeinMiner extends JavaPlugin {

    public static final Gson GSON = new Gson();

    public static final Pattern BLOCK_DATA_PATTERN = Pattern.compile("(?:[\\w:]+)(?:\\[(.+=.+)+\\])*");

    private static final int VEINMINER_PROTOCOL_VERSION = 1;

    private static VeinMiner instance;

    private final List<@NotNull AntiCheatHook> anticheatHooks = new ArrayList<>();

    private VeinMinerManager manager;
    private PatternRegistry patternRegistry;
    private EconomyModifier economyModifier;

    private VeinMiningPattern veinMiningPattern;

    private ConfigWrapper categoriesConfig;
    private File playerDataDirectory;

    private final PluginMessageProtocol<@NotNull VeinMiner> pluginMessageProtocol = new PluginMessageProtocol<>(this, "veinminer:activation", VEINMINER_PROTOCOL_VERSION,
        serverRegistry -> serverRegistry
            .registerMessage(PluginMessageInHandshake.class, PluginMessageInHandshake::new) // 0x00
            .registerMessage(PluginMessageInToggleVeinMiner.class, PluginMessageInToggleVeinMiner::new), // 0x01

        clientRegistry -> { } // No client-bound messages... yet?
    );

    @Override
    public void onLoad() {
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") != null) {
            this.getLogger().info("Found WorldGuard. Registering custom region flag.");
            WorldGuardIntegration.init(this);
        }
    }

    @Override
    public void onEnable() {
        VeinMiner.instance = this;
        this.saveDefaultConfig();

        this.manager = new VeinMinerManager(this);

        // Configuration handling
        this.categoriesConfig = new ConfigWrapper(this, "categories.yml");
        this.playerDataDirectory = new File(getDataFolder(), "playerdata");
        this.playerDataDirectory.mkdirs();

        // Pattern registration
        this.patternRegistry = new PatternRegistry();
        this.patternRegistry.registerPattern(PatternThorough.get());
        this.patternRegistry.registerPattern(PatternExpansive.get());

        ReflectionUtil.init(Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3]);

        // Enable anticheat hooks if required
        PluginManager manager = Bukkit.getPluginManager();
        this.registerAntiCheatHookIfEnabled(manager, "AAC5", AntiCheatHookAAC::new);
        this.registerAntiCheatHookIfEnabled(manager, "AntiAura", () -> new AntiCheatHookAntiAura(this));
        this.registerAntiCheatHookIfEnabled(manager, "Matrix", AntiCheatHookMatrix::new);
        this.registerAntiCheatHookIfEnabled(manager, "NoCheatPlus", () -> new AntiCheatHookNCP(this));
        this.registerAntiCheatHookIfEnabled(manager, "Spartan", () -> new AntiCheatHookSpartan(this));

        // Register events
        this.getLogger().info("Registering events");
        manager.registerEvents(new BreakBlockListener(this), this);
        manager.registerEvents(new ItemCollectionListener(this), this);
        manager.registerEvents(new PlayerDataListener(this), this);

        Plugin mcMMOPlugin = manager.getPlugin("mcMMO");
        if (mcMMOPlugin != null && manager.isPluginEnabled("mcMMO")) {
            // Integrate with McMMO, but don't integrate with mcMMO-Classic, version 1.x
            if (!mcMMOPlugin.getDescription().getVersion().startsWith("1")) {
                manager.registerEvents(new McMMOIntegration(this), this);
            }
            else if (getConfig().getBoolean(VMConstants.CONFIG_NERF_MCMMO, false)) {
                this.getLogger().warning(VMConstants.CONFIG_NERF_MCMMO + " is enabled but McMMO-Classic is installed.");
                this.getLogger().warning("This version of McMMO is not supported and therefore this configuration option will not work!");
                this.getLogger().warning("Consider updating your version of McMMO.");
            }
        }

        // Register commands
        this.getLogger().info("Registering commands");
        this.registerCommandSafely("veinminer", new VeinMinerCommand(this));

        if (Bukkit.getPluginManager().isPluginEnabled("Vault")) {
            this.getLogger().info("Vault found. Attempting to enable economy support...");
            this.economyModifier = new VaultBasedEconomyModifier();
            this.getLogger().info(((VaultBasedEconomyModifier) economyModifier).hasEconomyPlugin()
                    ? "Economy found! Hooked successfully."
                    : "Cancelled. No economy plugin found.");
        } else {
            this.getLogger().info("Vault not found. Economy support suspended");
            this.economyModifier = EmptyEconomyModifier.get();
        }

        // Metrics
        if (getConfig().getBoolean(VMConstants.CONFIG_METRICS_ENABLED, true)) {
            this.getLogger().info("Enabling Plugin Metrics");

            Metrics metrics = new Metrics(this, 1938); // https://bstats.org/what-is-my-plugin-id
            metrics.addCustomChart(new AdvancedPie("blocks_veinmined", StatTracker::getVeinMinedCountAsData));
            metrics.addCustomChart(new SingleLineChart("using_client_mod", ClientActivation::getPlayersUsingClientMod));
            metrics.addCustomChart(new DrilldownPie("installed_anticheats", StatTracker::getInstalledAntiCheatsAsData));

            this.getLogger().info("Thanks for enabling Metrics! The anonymous stats are appreciated");
        }

        // Load blocks to the veinable list
        this.getLogger().info("Loading configuration options to local memory");
        this.manager.loadToolCategories();
        this.manager.loadVeinableBlocks();
        this.manager.loadMaterialAliases();

        // Special case for reloads and crashes
        Bukkit.getOnlinePlayers().forEach(player -> PlayerPreferences.get(player).readFromFile(playerDataDirectory));

        // Update check (https://www.spigotmc.org/resources/veinminer.12038/)
        UpdateChecker updateChecker = UpdateChecker.init(this, 12038);
        if (getConfig().getBoolean(VMConstants.CONFIG_PERFORM_UPDATE_CHECKS, true)) {
            this.getLogger().info("Performing an update check!");
            updateChecker.requestUpdateCheck().whenComplete((result, exception) -> {
                if (result.requiresUpdate()) {
                    this.getLogger().info(String.format("An update is available! VeinMiner %s may be downloaded on SpigotMC", result.getNewestVersion()));
                    return;
                }

                UpdateReason reason = result.getReason();
                if (reason == UpdateReason.UP_TO_DATE) {
                    this.getLogger().info(String.format("Your version of VeinMiner (%s) is up to date!", result.getNewestVersion()));
                } else if (reason == UpdateReason.UNRELEASED_VERSION) {
                    this.getLogger().info(String.format("Your version of VeinMiner (%s) is more recent than the one publicly available. Are you on a development build?", result.getNewestVersion()));
                } else {
                    this.getLogger().warning("Could not check for a new version of VeinMiner. Reason: " + reason);
                }
            });
        }
    }

    @Override
    public void onDisable() {
        this.getLogger().info("Clearing localized data");
        this.manager.clearLocalisedData();
        this.patternRegistry.clearPatterns();
        this.anticheatHooks.clear();

        // Special case for reloads and crashes
        Bukkit.getOnlinePlayers().forEach(player -> {
            PlayerPreferences playerData = PlayerPreferences.get(player);
            if (!playerData.isDirty()) {
                return;
            }

            playerData.writeToFile(playerDataDirectory);
        });

        PlayerPreferences.clearCache();
        VeinBlock.clearCache();
        ToolCategory.clearCategories();
    }

    /**
     * Get an instance of the main VeinMiner class (for VeinMiner API usages).
     *
     * @return an instance of the VeinMiner class
     */
    @NotNull
    public static VeinMiner getPlugin() {
        return instance;
    }

    /**
     * Get the VeinMiner Manager used to keep track of Veinminable blocks, and other utilities.
     *
     * @return an instance of the VeinMiner manager
     */
    @NotNull
    public VeinMinerManager getVeinMinerManager() {
        return manager;
    }

    /**
     * Get the pattern registry used to register custom vein mining patterns.
     *
     * @return an instance of the pattern registry
     */
    @NotNull
    public PatternRegistry getPatternRegistry() {
        return patternRegistry;
    }

    /**
     * Get the plugin message protocol for VeinMiner.
     *
     * @return the plugin message protocol
     */
    @NotNull
    public PluginMessageProtocol<@NotNull VeinMiner> getPluginMessageProtocol() {
        return pluginMessageProtocol;
    }

    /**
     * Get an instance of the categories configuration file.
     *
     * @return the categories config
     */
    @NotNull
    public ConfigWrapper getCategoriesConfig() {
        return categoriesConfig;
    }

    /**
     * Get VeinMiner's playerdata directory.
     *
     * @return the playerdata directory
     */
    @NotNull
    public File getPlayerDataDirectory() {
        return playerDataDirectory;
    }

    /**
     * Get the economy abstraction layer for a Vault economy.
     *
     * @return economy abstraction
     */
    @NotNull
    public EconomyModifier getEconomyModifier() {
        return economyModifier;
    }

    /**
     * Set the vein mining pattern to use.
     *
     * @param pattern the pattern to set
     */
    public void setVeinMiningPattern(@NotNull VeinMiningPattern pattern) {
        Preconditions.checkArgument(pattern != null, "null patterns are not supported");
        this.veinMiningPattern = pattern;
    }

    /**
     * Get the vein mining pattern to use.
     *
     * @return the pattern
     */
    @NotNull
    public VeinMiningPattern getVeinMiningPattern() {
        if (veinMiningPattern == null) {
            String patternKeyString = getConfig().getString(VMConstants.CONFIG_VEIN_MINING_PATTERN);
            NamespacedKey patternKey = patternKeyString != null ? NamespacedKeyUtil.fromString(patternKeyString, this) : PatternExpansive.get().getKey();

            if (patternKey == null) {
                this.getLogger().warning("Malformatted pattern key, " + patternKeyString + ". Expected \"foo:bar\" format.");
                patternKey = PatternExpansive.get().getKey();
            }

            VeinMiningPattern pattern = patternRegistry.getPattern(patternKey);
            if (pattern == null) {
                this.getLogger().warning("Unrecognized pattern. Could not find pattern with id " + patternKey + ". Was it spelt correctly?");
                pattern = PatternExpansive.get();
            }

            this.veinMiningPattern = pattern;
        }

        return veinMiningPattern;
    }

    /**
     * Register an anticheat hook to VeinMiner. Hooks should be registered for all anticheat plugins
     * as to support VeinMining and not false-flag players with fast-break.
     *
     * @param hook the hook to register
     *
     * @return true if successful, false if a hook for a plugin with a similar name is already registered
     * or the hook is unsupported according to {@link AntiCheatHook#isSupported()}.
     */
    public boolean registerAntiCheatHook(@NotNull AntiCheatHook hook) {
        Preconditions.checkNotNull(hook, "Cannot register a null anticheat hook implementation");

        if (!hook.isSupported()) {
            return false;
        }

        for (AntiCheatHook anticheatHook : anticheatHooks) {
            if (anticheatHook.getPluginName().equals(hook.getPluginName())) {
                return false;
            }
        }

        return anticheatHooks.add(hook);
    }

    /**
     * Get an immutable list of all anti cheat hooks.
     *
     * @return all anticheat hooks
     */
    @NotNull
    public List<@NotNull AntiCheatHook> getAnticheatHooks() {
        return Collections.unmodifiableList(anticheatHooks);
    }

    private void registerAntiCheatHookIfEnabled(@NotNull PluginManager manager, @NotNull String pluginName, @NotNull Supplier<@NotNull ? extends AntiCheatHook> hookSupplier) {
        if (!manager.isPluginEnabled(pluginName)) {
            return;
        }

        this.getLogger().info("Anti cheat detected. Enabling anti cheat support for \"" + pluginName + "\"");

        AntiCheatHook hook = hookSupplier.get();
        if (!registerAntiCheatHook(hook)) {
            this.getLogger().info("Tried to register hook for plugin " + pluginName + " but one was already registered. Not overriding...");
            return;
        }

        if (hook instanceof Listener) {
            manager.registerEvents((Listener) hook, this);
        }

        Plugin antiCheatPlugin = hook.getPlugin();
        if (antiCheatPlugin != null) {
            StatTracker.recognizeInstalledAntiCheat(new AntiCheatInformation(antiCheatPlugin.getName(), antiCheatPlugin.getDescription().getVersion()));
        }
    }

    private void registerCommandSafely(@NotNull String commandString, @NotNull CommandExecutor executor) {
        PluginCommand command = getCommand(commandString);
        if (command == null) {
            return;
        }

        command.setExecutor(executor);

        if (executor instanceof TabCompleter) {
            command.setTabCompleter((TabCompleter) executor);
        }
    }

}
