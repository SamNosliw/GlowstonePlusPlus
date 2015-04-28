package net.glowstone;

import com.avaje.ebean.config.DataSourceConfig;
import com.avaje.ebean.config.dbplatform.SQLitePlatform;
import com.avaje.ebeaninternal.server.lib.sql.TransactionIsolation;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import net.glowstone.block.BuiltinMaterialValueManager;
import net.glowstone.block.MaterialValueManager;
import net.glowstone.command.ColorCommand;
import net.glowstone.command.TellrawCommand;
import net.glowstone.constants.GlowEnchantment;
import net.glowstone.constants.GlowPotionEffect;
import net.glowstone.entity.EntityIdManager;
import net.glowstone.entity.GlowPlayer;
import net.glowstone.generator.CakeTownGenerator;
import net.glowstone.generator.NetherGenerator;
import net.glowstone.generator.OverworldGenerator;
import net.glowstone.inventory.CraftingManager;
import net.glowstone.inventory.GlowInventory;
import net.glowstone.inventory.GlowItemFactory;
import net.glowstone.io.PlayerDataService;
import net.glowstone.map.GlowMapView;
import net.glowstone.net.GlowNetworkServer;
import net.glowstone.net.SessionRegistry;
import net.glowstone.net.query.QueryServer;
import net.glowstone.net.rcon.RconServer;
import net.glowstone.scheduler.GlowScheduler;
import net.glowstone.scheduler.WorldScheduler;
import net.glowstone.util.*;
import net.glowstone.util.bans.GlowBanList;
import net.glowstone.util.bans.UuidListFile;
import org.apache.commons.lang.Validate;
import org.bukkit.*;
import org.bukkit.World.Environment;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.help.HelpMap;
import org.bukkit.inventory.*;
import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.*;
import org.bukkit.plugin.java.JavaPluginLoader;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.StandardMessenger;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.util.CachedServerIcon;
import org.bukkit.util.permissions.DefaultPermissions;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.KeyPair;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The core class of the Glowstone server.
 * @author Graham Edgecombe
 */
public final class GlowServer {

    /**
     * The logger for this class.
     */
    public static final Logger logger = Logger.getLogger("Minecraft");

    /**
     * The game version supported by the server.
     */
    public static final String GAME_VERSION = "1.8.3";

    /**
     * The protocol version supported by the server.
     */
    public static final int PROTOCOL_VERSION = 47;

    /**
     * Creates a new server on TCP port 25565 and starts listening for
     * connections.
     * @param args The command-line arguments.
     */
    public static void main(String[] args) {
        try {
            // parse arguments and read config
            final ServerConfig config = parseArguments(args);
            if (config == null) {
                return;
            }

            ConfigurationSerialization.registerClass(GlowOfflinePlayer.class);
            GlowPotionEffect.register();
            GlowEnchantment.register();

            // start server
            final GlowServer server = new GlowServer(config);
            server.start();
            server.bind();
            server.bindQuery();
            server.bindRcon();
            logger.info("Ready for connections.");
        } catch (BindException ex) {
            // descriptive bind error messages
            logger.severe("The server could not bind to the requested address.");
            if (ex.getMessage().startsWith("Cannot assign requested address")) {
                logger.severe("The 'server.ip' in your configuration may not be valid.");
                logger.severe("Unless you are sure you need it, try removing it.");
                logger.severe(ex.toString());
            } else if (ex.getMessage().startsWith("Address already in use")) {
                logger.severe("The address was already in use. Check that no server is");
                logger.severe("already running on that port. If needed, try killing all");
                logger.severe("Java processes using Task Manager or similar.");
                logger.severe(ex.toString());
            } else {
                logger.log(Level.SEVERE, "An unknown bind error has occurred.", ex);
            }
            System.exit(1);
        } catch (Throwable t) {
            // general server startup crash
            logger.log(Level.SEVERE, "Error during server startup.", t);
            System.exit(1);
        }
    }

    private static ServerConfig parseArguments(String[] args) {
        final Map<ServerConfig.Key, Object> parameters = new EnumMap<>(ServerConfig.Key.class);
        String configDirName = "config";
        String configFileName = "glowstone.yml";

        // Calculate acceptable parameters
        for (int i = 0; i < args.length; i++) {
            final String opt = args[i];

            if (!opt.startsWith("-")) {
                System.err.println("Ignored invalid option: " + opt);
                continue;
            }

            // Help and version
            if ("--help".equals(opt) || "-h".equals(opt) || "-?".equals(opt)) {
                System.out.println("Available command-line options:");
                System.out.println("  --help, -h, -?                 Shows this help message and exits.");
                System.out.println("  --version, -v                  Shows version information and exits.");
                System.out.println("  --configdir <directory>        Sets the configuration directory.");
                System.out.println("  --configfile <file>            Sets the configuration file.");
                System.out.println("  --port, -p <port>              Sets the server listening port.");
                System.out.println("  --host, -H <ip | hostname>     Sets the server listening address.");
                System.out.println("  --onlinemode, -o <onlinemode>  Sets the server's online-mode.");
                System.out.println("  --jline <true/false>           Enables or disables JLine console.");
                System.out.println("  --plugins-dir, -P <directory>  Sets the plugin directory to use.");
                System.out.println("  --worlds-dir, -W <directory>   Sets the world directory to use.");
                System.out.println("  --update-dir, -U <directory>   Sets the plugin update folder to use.");
                System.out.println("  --max-players, -M <director>   Sets the maximum amount of players.");
                System.out.println("  --world-name, -N <name>        Sets the main world name.");
                System.out.println("  --log-pattern, -L <pattern>    Sets the log file pattern (%D for date).");
                return null;
            } else if ("--version".equals(opt) || "-v".equals(opt)) {
                System.out.println("Glowstone version: " + GlowServer.class.getPackage().getImplementationVersion());
                System.out.println("Bukkit version:    " + GlowServer.class.getPackage().getSpecificationVersion());
                System.out.println("Minecraft version: " + GAME_VERSION + " protocol " + PROTOCOL_VERSION);
                return null;
            }

            // Below this point, options require parameters
            if (i == args.length - 1) {
                System.err.println("Ignored option specified without value: " + opt);
                continue;
            }

            switch (opt) {
                case "--configdir":
                    configDirName = args[++i];
                    break;
                case "--configfile":
                    configFileName = args[++i];
                    break;
                case "--port":
                case "-p":
                    parameters.put(ServerConfig.Key.SERVER_PORT, Integer.valueOf(args[++i]));
                    break;
                case "--host":
                case "-H":
                    parameters.put(ServerConfig.Key.SERVER_IP, args[++i]);
                    break;
                case "--onlinemode":
                case "-o":
                    parameters.put(ServerConfig.Key.ONLINE_MODE, Boolean.valueOf(args[++i]));
                    break;
                case "--jline":
                    parameters.put(ServerConfig.Key.USE_JLINE, Boolean.valueOf(args[++i]));
                    break;
                case "--plugins-dir":
                case "-P":
                    parameters.put(ServerConfig.Key.PLUGIN_FOLDER, args[++i]);
                    break;
                case "--worlds-dir":
                case "-W":
                    parameters.put(ServerConfig.Key.WORLD_FOLDER, args[++i]);
                    break;
                case "--update-dir":
                case "-U":
                    parameters.put(ServerConfig.Key.UPDATE_FOLDER, args[++i]);
                    break;
                case "--max-players":
                case "-M":
                    parameters.put(ServerConfig.Key.MAX_PLAYERS, Integer.valueOf(args[++i]));
                    break;
                case "--world-name":
                case "-N":
                    parameters.put(ServerConfig.Key.LEVEL_NAME, args[++i]);
                    break;
                case "--log-pattern":
                case "-L":
                    parameters.put(ServerConfig.Key.LOG_FILE, args[++i]);
                    break;
                default:
                    System.err.println("Ignored invalid option: " + opt);
            }
        }

        final File configDir = new File(configDirName);
        final File configFile = new File(configDir, configFileName);

        return new ServerConfig(configDir, configFile, parameters);
    }

    /**
     * A list of all the active {@link net.glowstone.net.GlowSession}s.
     */
    private final SessionRegistry sessions = new SessionRegistry();

    /**
     * The console manager of this server.
     */
    private final ConsoleManager consoleManager = new ConsoleManager(this);

    /**
     * The services manager of this server.
     */
    private final SimpleServicesManager servicesManager = new SimpleServicesManager();

    /**
     * The command map of this server.
     */
    private final SimpleCommandMap commandMap = new SimpleCommandMap(this);

    /**
     * The plugin type detector of thi server.
     */
    private GlowPluginTypeDetector pluginTypeDetector;

    /**
     * The plugin channel messenger for the server.
     */
    private final Messenger messenger = new StandardMessenger();

    /**
     * The help map for the server.
     */
    private final GlowHelpMap helpMap = new GlowHelpMap(this);

    /**
     * The scoreboard manager for the server.
     */
    private final ScoreboardManager scoreboardManager = null;

    /**
     * The crafting manager for this server.
     */
    private final CraftingManager craftingManager = new CraftingManager();

    /**
     * The configuration for the server.
     */
    private final ServerConfig config;

    /**
     * The list of OPs on the server.
     */
    private final UuidListFile opsList;

    /**
     * The list of players whitelisted on the server.
     */
    private final UuidListFile whitelist;

    /**
     * The BanList for player names.
     */
    private final GlowBanList nameBans;

    /**
     * The BanList for IP addresses.
     */
    private final GlowBanList ipBans;

    /**
     * The EntityIdManager for this server.
     */
    private final EntityIdManager entityIdManager = new EntityIdManager();

    /**
     * The world this server is managing.
     */
    private final WorldScheduler worlds = new WorldScheduler();

    /**
     * The task scheduler used by this server.
     */
    private final GlowScheduler scheduler = new GlowScheduler(this, worlds);

    /**
     * The Bukkit UnsafeValues implementation.
     */
    private final UnsafeValues unsafeAccess = new GlowUnsafeValues();

    /**
     * An empty player array used for deprecated getOnlinePlayers.
     */
    private final Player[] emptyPlayerArray = new Player[0];

    /**
     * The server's default game mode
     */
    private GameMode defaultGameMode = GameMode.CREATIVE;

    /**
     * The setting for verbose deprecation warnings.
     */
    private Warning.WarningState warnState = Warning.WarningState.DEFAULT;

    /**
     * Whether the server is shutting down
     */
    private boolean isShuttingDown = false;

    /**
     * Whether the whitelist is in effect.
     */
    private boolean whitelistEnabled;

    /**
     * The size of the area to keep protected around the spawn point.
     */
    private int spawnRadius;

    /**
     * The ticks until a player who has not played the game has been kicked, or 0.
     */
    private int idleTimeout;

    /**
     * A RSA key pair used for encryption and authentication
     */
    private final KeyPair keyPair = SecurityUtils.generateKeyPair();

    /**
     * The network server used for network communication
     */
    private final GlowNetworkServer networkServer = new GlowNetworkServer(this);

    /**
     * The query server for this server, or null if disabled.
     */
    private QueryServer queryServer;

    /**
     * The Rcon server for this server, or null if disabled.
     */
    private RconServer rconServer;

    /**
     * The default icon, usually blank, used for the server list.
     */
    private GlowServerIcon defaultIcon;

    /**
     * The server port.
     */
    private int port;

    /**
     * A set of all online players.
     */
    private final Set<GlowPlayer> onlinePlayers = new HashSet<>();

    /**
     * A view of all online players.
     */
    private final Set<GlowPlayer> onlineView = Collections.unmodifiableSet(onlinePlayers);

    /**
     * The {@link net.glowstone.block.MaterialValueManager} of this server.
     */
    private MaterialValueManager materialValueManager;

    /**
     * Creates a new server.
     */
    public GlowServer(ServerConfig config) {
        this.materialValueManager = new BuiltinMaterialValueManager();

        this.config = config;
        // stuff based on selected config directory
        opsList = new UuidListFile(config.getFile("ops.json"));
        whitelist = new UuidListFile(config.getFile("whitelist.json"));
        nameBans = new GlowBanList(this, BanList.Type.NAME);
        ipBans = new GlowBanList(this, BanList.Type.IP);

        GlowServer.setServer(this);
        loadConfig();
    }

    private static GlowServer theServer;

    private static void setServer(GlowServer server) {
        theServer = server;
    }

    public static GlowServer getServer() {
        return theServer;
    }

    /**
     * Starts this server.
     */
    public void start() {
        // Determine console mode and start reading input
        consoleManager.startConsole(config.getBoolean(ServerConfig.Key.USE_JLINE));
        consoleManager.startFile(config.getString(ServerConfig.Key.LOG_FILE));

        if (getProxySupport()) {
            if (getOnlineMode()) {
                logger.warning("Proxy support is enabled, but online mode is enabled.");
            } else {
                logger.info("Proxy support is enabled.");
            }
        } else if (!getOnlineMode()) {
            logger.warning("The server is running in offline mode! Only do this if you know what you're doing.");
        }

        // Load player lists
        opsList.load();
        whitelist.load();
        nameBans.load();
        ipBans.load();

        // Start loading plugins
        new LibraryManager(this).run();
        loadPlugins();

        // Create worlds
        String name = config.getString(ServerConfig.Key.LEVEL_NAME);
        String seedString = config.getString(ServerConfig.Key.LEVEL_SEED);
        boolean structs = getGenerateStructures();
        WorldType type = WorldType.getByName(getWorldType());
        if (type == null) {
            type = WorldType.NORMAL;
        }

        long seed = new Random().nextLong();
        if (!seedString.isEmpty()) {
            try {
                long parsed = Long.parseLong(seedString);
                if (parsed != 0) {
                    seed = parsed;
                }
            } catch (NumberFormatException ex) {
                seed = seedString.hashCode();
            }
        }

        createWorld(WorldCreator.name(name).environment(Environment.NORMAL).seed(seed).type(type).generateStructures(structs));
        if (getAllowNether()) {
            checkTransfer(name, "_nether", Environment.NETHER);
            createWorld(WorldCreator.name(name + "_nether").environment(Environment.NETHER).seed(seed).type(type).generateStructures(structs));
        }
        if (getAllowEnd()) {
            checkTransfer(name, "_the_end", Environment.THE_END);
            createWorld(WorldCreator.name(name + "_the_end").environment(Environment.THE_END).seed(seed).type(type).generateStructures(structs));
        }

        // Finish loading plugins
        commandMap.registerServerAliases();
        scheduler.start();
    }

    private void checkTransfer(String name, String suffix, Environment environment) {
        // todo: import things like per-dimension villages.dat when those are implemented
        final Path srcPath = new File(new File(getWorldContainer(), name), "DIM" + environment.getId()).toPath();
        final Path destPath = new File(getWorldContainer(), name + suffix).toPath();
        if (Files.exists(srcPath) && !Files.exists(destPath)) {
            logger.info("Importing " + destPath + " from " + srcPath);
            try {
                Files.walkFileTree(srcPath, new FileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        Path target = destPath.resolve(srcPath.relativize(dir));
                        if (!Files.exists(target)) {
                            Files.createDirectory(target);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.copy(file, destPath.resolve(srcPath.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                        logger.warning("Importing file " + srcPath.relativize(file) + " + failed: " + exc);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        return FileVisitResult.CONTINUE;
                    }
                });
                Files.copy(srcPath.resolve("../level.dat"), destPath.resolve("level.dat"));
            } catch (IOException e) {
                logger.log(Level.WARNING, "Import of " + srcPath + " failed", e);
            }
        }
    }

    /**
     * Binds this server to the address specified in the configuration.
     */
    private void bind() throws BindException {
        SocketAddress address = getBindAddress(ServerConfig.Key.SERVER_PORT);

        logger.info("Binding to address: " + address + "...");
        ChannelFuture future = networkServer.bind(address);
        Channel channel = future.awaitUninterruptibly().channel();
        if (!channel.isActive()) {
            Throwable cause = future.cause();
            if (cause instanceof BindException) {
                throw (BindException) cause;
            }
            throw new RuntimeException("Failed to bind to address", cause);
        }

        logger.info("Successfully bound to: " + channel.localAddress());
        port = ((InetSocketAddress) channel.localAddress()).getPort();
    }

    /**
     * Binds the query server to the address specified in the configuration.
     */
    private void bindQuery() {
        if (!config.getBoolean(ServerConfig.Key.QUERY_ENABLED)) {
            return;
        }

        SocketAddress address = getBindAddress(ServerConfig.Key.QUERY_PORT);
        queryServer = new QueryServer(this, config.getBoolean(ServerConfig.Key.QUERY_PLUGINS));

        logger.info("Binding query to address: " + address + "...");
        ChannelFuture future = queryServer.bind(address);
        Channel channel = future.awaitUninterruptibly().channel();
        if (!channel.isActive()) {
            logger.warning("Failed to bind query. Address already in use?");
        }
    }

    /**
     * Binds the rcon server to the address specified in the configuration.
     */
    private void bindRcon() {
        if (!config.getBoolean(ServerConfig.Key.RCON_ENABLED)) {
            return;
        }

        SocketAddress address = getBindAddress(ServerConfig.Key.RCON_PORT);
        rconServer = new RconServer(this, config.getString(ServerConfig.Key.RCON_PASSWORD));

        logger.info("Binding rcon to address: " + address + "...");
        ChannelFuture future = rconServer.bind(address);
        Channel channel = future.awaitUninterruptibly().channel();
        if (!channel.isActive()) {
            logger.warning("Failed to bind rcon. Address already in use?");
        }
    }

    /**
     * Get the SocketAddress to bind to for a specified service.
     * @param portKey The configuration key for the port to use.
     * @return The SocketAddress
     */
    private SocketAddress getBindAddress(ServerConfig.Key portKey) {
        String ip = getIp();
        int port = config.getInt(portKey);
        if (ip.length() == 0) {
            return new InetSocketAddress(port);
        } else {
            return new InetSocketAddress(ip, port);
        }
    }

    /**
     * Stops this server.
     */
    public void shutdown() {
        // Just in case this gets called twice
        if (isShuttingDown) {
            return;
        }
        isShuttingDown = true;
        logger.info("The server is shutting down...");

        // Kick all players (this saves their data too)
        for (Player player : getOnlinePlayers()) {
            player.kickPlayer(getShutdownMessage());
        }

        // Stop the network servers - starts the shutdown process
        // It may take a second or two for Netty to totally clean up
        networkServer.shutdown();
        if (queryServer != null) {
            queryServer.shutdown();
        }
        if (rconServer != null) {
            rconServer.shutdown();
        }

        // Save worlds
        for (World world : getWorlds()) {
            logger.info("Saving world: " + world.getName());
            unloadWorld(world, true);
        }

        // Stop scheduler and console
        scheduler.stop();
        consoleManager.stop();

        // Wait for a while and terminate any rogue threads
        new ShutdownMonitorThread().start();
    }

    /**
     * Load the server configuration.
     */
    private void loadConfig() {
        config.load();

        // modifiable values
        spawnRadius = config.getInt(ServerConfig.Key.SPAWN_RADIUS);
        whitelistEnabled = config.getBoolean(ServerConfig.Key.WHITELIST);
        idleTimeout = config.getInt(ServerConfig.Key.PLAYER_IDLE_TIMEOUT);
        craftingManager.initialize();

        // special handling
        warnState = Warning.WarningState.value(config.getString(ServerConfig.Key.WARNING_STATE));
        try {
            defaultGameMode = GameMode.valueOf(config.getString(ServerConfig.Key.GAMEMODE));
        } catch (IllegalArgumentException | NullPointerException e) {
            defaultGameMode = GameMode.SURVIVAL;
        }

        // server icon
        defaultIcon = new GlowServerIcon();
        try {
            File file = config.getFile("server-icon.png");
            if (file.isFile()) {
                defaultIcon = new GlowServerIcon(file);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to load server-icon.png", e);
        }
    }

    /**
     * Loads all plugins, calling onLoad, &c.
     */
    private void loadPlugins() {
        // clear the map
        commandMap.clearCommands();
        commandMap.register("glowstone", new ColorCommand());
        commandMap.register("glowstone", new TellrawCommand());

        File folder = new File(config.getString(ServerConfig.Key.PLUGIN_FOLDER));
        if (!folder.isDirectory() && !folder.mkdirs()) {
            logger.log(Level.SEVERE, "Could not create plugins directory: " + folder);
        }

        // detect plugin types
        pluginTypeDetector = new GlowPluginTypeDetector(folder, logger);
        pluginTypeDetector.scan();

        if (pluginTypeDetector.spongePlugins.size() != 0) {
            boolean hasSponge = false;
            /*
            for (Plugin plugin : plugins) {
                if (plugin.getName().equals("Bukkit2Sponge")) {
                    hasSponge = true; // TODO: better detection method, plugin description file annotation APIs?
                    break;
                }
            }
            */

            if (!hasSponge) {
                logger.log(Level.WARNING, "SpongeAPI plugins found, but no Sponge bridge present! They will be ignored.");
                for (File file : pluginTypeDetector.spongePlugins) {
                    logger.log(Level.WARNING, "Ignored SpongeAPI plugin: " + file.getPath());
                }
                logger.log(Level.WARNING, "Suggestion: install https://github.com/deathcap/Bukkit2Sponge to load these plugins");
            }
        }

        if (pluginTypeDetector.canaryPlugins.size() != 0 ||
                pluginTypeDetector.forgefPlugins.size() != 0 ||
                pluginTypeDetector.forgenPlugins.size() != 0 ||
                pluginTypeDetector.unrecognizedPlugins.size() != 0) {
            logger.log(Level.WARNING, "Unsupported plugin types found, will be ignored:");

            for (File file : pluginTypeDetector.canaryPlugins)
                logger.log(Level.WARNING, "Canary plugin not supported: " + file.getPath());

            for (File file : pluginTypeDetector.forgefPlugins)
                logger.log(Level.WARNING, "Forge plugin not supported: " + file.getPath());

            for (File file : pluginTypeDetector.forgenPlugins)
                logger.log(Level.WARNING, "Forge plugin not supported: " + file.getPath());

            for (File file : pluginTypeDetector.unrecognizedPlugins)
                logger.log(Level.WARNING, "Unrecognized plugin not supported: " + file.getPath());
       }

    }

    // API for Bukkit2Sponge
    public List<File> getSpongePlugins() {
        return pluginTypeDetector.spongePlugins;
    }


    /**
     * Reloads the server, refreshing settings and plugin information
     */
    public void reload() {
        try {
            // Reload relevant configuration
            loadConfig();
            opsList.load();
            whitelist.load();
            nameBans.load();
            ipBans.load();

            // Reset crafting
            craftingManager.resetRecipes();

            // Load plugins
            loadPlugins();
        } catch (Exception ex) {
            logger.log(Level.SEVERE, "Uncaught error while reloading", ex);
        }
    }

    @Override
    public String toString() {
        return "GlowServer{name=" + getName() + ",version=" + getVersion() + ",minecraftVersion=" + GAME_VERSION + "}";
    }

    ////////////////////////////////////////////////////////////////////////////
    // Access to internals

    /**
     * Gets the command map.
     * @return The {@link SimpleCommandMap}.
     */
    public SimpleCommandMap getCommandMap() {
        return commandMap;
    }

    /**
     * Gets the session registry.
     * @return The {@link SessionRegistry}.
     */
    public SessionRegistry getSessionRegistry() {
        return sessions;
    }

    /**
     * Gets the entity id manager.
     * @return The {@link EntityIdManager}.
     */
    public EntityIdManager getEntityIdManager() {
        return entityIdManager;
    }

    /**
     * Returns the list of OPs on this server.
     */
    public UuidListFile getOpsList() {
        return opsList;
    }

    /**
     * Returns the list of whitelisted players on this server.
     */
    public UuidListFile getWhitelist() {
        return whitelist;
    }

    /**
     * Returns the folder where configuration files are stored
     */
    public File getConfigDir() {
        return config.getDirectory();
    }

    /**
     * Return the crafting manager.
     * @return The server's crafting manager.
     */
    public CraftingManager getCraftingManager() {
        return craftingManager;
    }

    /**
     * The key pair generated at server start up
     * @return The key pair generated at server start up
     */
    public KeyPair getKeyPair() {
        return keyPair;
    }

    /**
     * Returns the player data service attached to the first world.
     * @return The server's player data service.
     */
    public PlayerDataService getPlayerDataService() {
        return worlds.getWorlds().get(0).getStorage().getPlayerDataService();
    }

    /**
     * Get the threshold to use for network compression defined in the config.
     * @return The compression threshold, or -1 for no compression.
     */
    public int getCompressionThreshold() {
        return config.getInt(ServerConfig.Key.COMPRESSION_THRESHOLD);
    }

    /**
     * Get the default game difficulty defined in the config.
     * @return The default difficulty.
     */
    public Difficulty getDifficulty() {
        try {
            return Difficulty.valueOf(config.getString(ServerConfig.Key.DIFFICULTY));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Difficulty.NORMAL;
        }
    }

    /**
     * Get whether worlds should keep their spawns loaded by default.
     * @return Whether to keep spawns loaded by default.
     */
    public boolean keepSpawnLoaded() {
        return config.getBoolean(ServerConfig.Key.PERSIST_SPAWN);
    }

    /**
     * Get whether parsing of data provided by a proxy is enabled.
     * @return True if a proxy is providing data to use.
     */
    public boolean getProxySupport() {
        return config.getBoolean(ServerConfig.Key.PROXY_SUPPORT);
    }

    /**
     * Get whether to use color codes in Rcon responses.
     * @return True if color codes will be present in Rcon responses
     */
    public boolean useRconColors() {
        return config.getBoolean(ServerConfig.Key.RCON_COLORS);
    }

    public MaterialValueManager getMaterialValueManager() {
        return materialValueManager;
    }

    /**
     * Get the resource pack url for this server, or {@code null} if not set.
     * @return The url of the resource pack to use, or {@code null}
     */
    public String getResourcePackURL() {
        return config.getString(ServerConfig.Key.RESOURCE_PACK);
    }

    /**
     * Get the resource pack hash for this server, or the empty string if not set.
     * @return The hash of the resource pack, or the empty string
     */
    public String getResourcePackHash() {
        return config.getString(ServerConfig.Key.RESOURCE_PACK_HASH);
    }

    /**
     * Get whether achievements should be announced.
     * @return True if achievements should be announced in chat.
     */
    public boolean getAnnounceAchievements() {
        return config.getBoolean(ServerConfig.Key.ANNOUNCE_ACHIEVEMENTS);
    }

    /**
     * Sets a player as being online internally.
     * @param player player to set online/offline
     * @param online whether the player is online or offline
     */
    public void setPlayerOnline(GlowPlayer player, boolean online) {
        Validate.notNull(player);
        if (online) {
            onlinePlayers.add(player);
        } else {
            onlinePlayers.remove(player);
        }
    }

    ////////////////////////////////////////////////////////////////////////////
    // Static server properties

    public String getName() {
        return "Glowstone++";
    }

    public String getVersion() {
        return getClass().getPackage().getImplementationVersion();
    }

    public String getBukkitVersion() {
        return getClass().getPackage().getSpecificationVersion();
    }

    public Logger getLogger() {
        return logger;
    }

    public boolean isPrimaryThread() {
        return scheduler.isPrimaryThread();
    }

    ////////////////////////////////////////////////////////////////////////////
    // Access to Bukkit API

    public GlowScheduler getScheduler() {
        return scheduler;
    }

    public ServicesManager getServicesManager() {
        return servicesManager;
    }

    public Messenger getMessenger() {
        return messenger;
    }

    public HelpMap getHelpMap() {
        return helpMap;
    }

    public ItemFactory getItemFactory() {
        return GlowItemFactory.instance();
    }

    public ScoreboardManager getScoreboardManager() {
        return scoreboardManager;
    }

    @Deprecated
    public UnsafeValues getUnsafe() {
        return unsafeAccess;
    }

    public BanList getBanList(BanList.Type type) {
        switch (type) {
            case NAME:
                return nameBans;
            case IP:
                return ipBans;
            default:
                throw new IllegalArgumentException("Unknown BanList type " + type);
        }
    }

    ////////////////////////////////////////////////////////////////////////////
    // Commands and console

    public ConsoleCommandSender getConsoleSender() {
        return consoleManager.getSender();
    }

    public PluginCommand getPluginCommand(String name) {
        Command command = commandMap.getCommand(name);
        if (command instanceof PluginCommand) {
            return (PluginCommand) command;
        } else {
            return null;
        }
    }

    public Map<String, String[]> getCommandAliases() {
        Map<String, String[]> aliases = new HashMap<>();
        ConfigurationSection section = config.getConfigFile(ServerConfig.Key.COMMANDS_FILE).getConfigurationSection("aliases");
        if (section == null) {
            return aliases;
        }
        for (String key : section.getKeys(false)) {
            List<String> list = section.getStringList(key);
            aliases.put(key, list.toArray(new String[list.size()]));
        }
        return aliases;
    }

    public boolean dispatchCommand(CommandSender sender, String commandLine) throws CommandException {
        if (commandMap.dispatch(sender, commandLine)) {
            return true;
        }

        String firstword = commandLine;
        if (firstword.indexOf(' ') >= 0) {
            firstword = firstword.substring(0, firstword.indexOf(' '));
        }

        sender.sendMessage(ChatColor.GRAY + "Unknown command \"" + firstword + "\", try \"help\"");
        return false;
    }

    ////////////////////////////////////////////////////////////////////////////
    // Player management

    public Set<OfflinePlayer> getOperators() {
        Set<OfflinePlayer> offlinePlayers = new HashSet<>();
        for (UUID uuid : opsList.getUUIDs()) {
            offlinePlayers.add(getOfflinePlayer(uuid));
        }
        return offlinePlayers;
    }

    @Deprecated
    public Player[] _INVALID_getOnlinePlayers() {
        return getOnlinePlayers().toArray(emptyPlayerArray);
    }

    public Collection<GlowPlayer> getOnlinePlayers() {
        return onlineView;
    }

    public OfflinePlayer[] getOfflinePlayers() {
        Set<OfflinePlayer> result = new HashSet<>();
        Set<UUID> uuids = new HashSet<>();

        // add the currently online players
        for (World world : getWorlds()) {
            for (Player player : world.getPlayers()) {
                result.add(player);
                uuids.add(player.getUniqueId());
            }
        }

        // add all offline players that aren't already online
        for (OfflinePlayer offline : getPlayerDataService().getOfflinePlayers()) {
            if (!uuids.contains(offline.getUniqueId())) {
                result.add(offline);
                uuids.add(offline.getUniqueId());
            }
        }

        return result.toArray(new OfflinePlayer[result.size()]);
    }

    public Player getPlayer(String name) {
        name = name.toLowerCase();
        Player bestPlayer = null;
        int bestDelta = -1;
        for (Player player : getOnlinePlayers()) {
            if (player.getName().toLowerCase().startsWith(name)) {
                int delta = player.getName().length() - name.length();
                if (bestPlayer == null || delta < bestDelta) {
                    bestPlayer = player;
                }
            }
        }
        return bestPlayer;
    }

    public Player getPlayer(UUID uuid) {
        for (Player player : getOnlinePlayers()) {
            if (player.getUniqueId().equals(uuid)) {
                return player;
            }
        }
        return null;
    }

    public Player getPlayerExact(String name) {
        for (Player player : getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(name)) {
                return player;
            }
        }
        return null;
    }

    public List<Player> matchPlayer(String name) {
        name = name.toLowerCase();

        ArrayList<Player> result = new ArrayList<>();
        for (Player player : getOnlinePlayers()) {
            String lower = player.getName().toLowerCase();
            if (lower.equals(name)) {
                result.clear();
                result.add(player);
                break;
            } else if (lower.contains(name)) {
                result.add(player);
            }
        }
        return result;
    }

    @Deprecated
    public OfflinePlayer getOfflinePlayer(String name) {
        Player onlinePlayer = getPlayerExact(name);
        if (onlinePlayer != null) {
            return onlinePlayer;
        }
        return new GlowOfflinePlayer(this, name);
    }

    public OfflinePlayer getOfflinePlayer(UUID uuid) {
        Player onlinePlayer = getPlayer(uuid);
        if (onlinePlayer != null) {
            return onlinePlayer;
        }
        return new GlowOfflinePlayer(this, uuid);
    }

    public void savePlayers() {
        for (Player player : getOnlinePlayers()) {
            player.saveData();
        }
    }

    public int broadcastMessage(String message) {
        return broadcast(message, Server.BROADCAST_CHANNEL_USERS);
    }

    public int broadcast(String message, String permission) {
        int count = 0;
        /* TODO: permission subscriptions?
        for (Permissible permissible : getPluginManager().getPermissionSubscriptions(permission)) {
            if (permissible instanceof CommandSender && permissible.hasPermission(permission)) {
                ((CommandSender) permissible).sendMessage(message);
                ++count;
            }
        }
        */
        return count;
    }

    public Set<OfflinePlayer> getWhitelistedPlayers() {
        Set<OfflinePlayer> players = new HashSet<>();
        for (UUID uuid : whitelist.getUUIDs()) {
            players.add(getOfflinePlayer(uuid));
        }
        return players;
    }

    public void reloadWhitelist() {
        whitelist.load();
    }

    public Set<String> getIPBans() {
        Set<String> result = new HashSet<>();
        for (BanEntry entry : ipBans.getBanEntries()) {
            result.add(entry.getTarget());
        }
        return result;
    }

    public void banIP(String address) {
        ipBans.addBan(address, null, null, null);
    }

    public void unbanIP(String address) {
        ipBans.pardon(address);
    }

    public Set<OfflinePlayer> getBannedPlayers() {
        Set<OfflinePlayer> bannedPlayers = new HashSet<>();
        for (BanEntry entry : nameBans.getBanEntries()) {
            bannedPlayers.add(getOfflinePlayer(entry.getTarget()));
        }
        return bannedPlayers;
    }

    ////////////////////////////////////////////////////////////////////////////
    // World management

    public GlowWorld getWorld(String name) {
        return worlds.getWorld(name);
    }

    public GlowWorld getWorld(UUID uid) {
        for (GlowWorld world : worlds.getWorlds()) {
            if (uid.equals(world.getUID())) {
                return world;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public List<World> getWorlds() {
        // Shenanigans needed to cast List<GlowWorld> to List<World>
        return (List) worlds.getWorlds();
    }

    /**
     * Gets the default ChunkGenerator for the given environment and type.
     * @return The ChunkGenerator.
     */
    private ChunkGenerator getGenerator(String name, Environment environment, WorldType type) {
        // find generator based on configuration
        ConfigurationSection worlds = config.getWorlds();
        if (worlds != null) {
            String genName = worlds.getString(name + ".generator", null);
            ChunkGenerator generator = WorldCreator.getGeneratorForName(name, genName, getConsoleSender());
            if (generator != null) {
                return generator;
            }
        }

        // find generator based on environment and world type
        if (environment == Environment.NETHER) {
            return new NetherGenerator();
        } else if (environment == Environment.THE_END) {
            return new CakeTownGenerator();
        } else {
            return new OverworldGenerator();
        }
    }

    public GlowWorld createWorld(WorldCreator creator) {
        GlowWorld world = getWorld(creator.name());
        if (world != null) {
            return world;
        }

        if (creator.generator() == null) {
            creator.generator(getGenerator(creator.name(), creator.environment(), creator.type()));
        }

        // GlowWorld's constructor calls addWorld below.
        return new GlowWorld(this, creator);
    }

    /**
     * Add a world to the internal world collection.
     * @param world The world to add.
     */
    void addWorld(GlowWorld world) {
        worlds.addWorld(world);
    }

    public boolean unloadWorld(String name, boolean save) {
        GlowWorld world = getWorld(name);
        return world != null && unloadWorld(world, save);
    }

    public boolean unloadWorld(World bWorld, boolean save) {
        if (!(bWorld instanceof GlowWorld)) {
            return false;
        }
        GlowWorld world = (GlowWorld) bWorld;
        if (save) {
            world.setAutoSave(false);
            world.save(false);
        }
        if (worlds.removeWorld(world)) {
            world.unload();
            return true;
        }
        return false;
    }

    public GlowMapView getMap(short id) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public GlowMapView createMap(World world) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    ////////////////////////////////////////////////////////////////////////////
    // Inventory and crafting

    public List<Recipe> getRecipesFor(ItemStack result) {
        return craftingManager.getRecipesFor(result);
    }

    public Iterator<Recipe> recipeIterator() {
        return craftingManager.iterator();
    }

    public boolean addRecipe(Recipe recipe) {
        return craftingManager.addRecipe(recipe);
    }

    public void clearRecipes() {
        craftingManager.clearRecipes();
    }

    public void resetRecipes() {
        craftingManager.resetRecipes();
    }

    public Inventory createInventory(InventoryHolder owner, InventoryType type) {
        return new GlowInventory(owner, type);
    }

    public Inventory createInventory(InventoryHolder owner, int size) {
        return new GlowInventory(owner, InventoryType.CHEST, size);
    }

    public Inventory createInventory(InventoryHolder owner, int size, String title) {
        return new GlowInventory(owner, InventoryType.CHEST, size, title);
    }

    public Inventory createInventory(InventoryHolder owner, InventoryType type, String title) {
        return new GlowInventory(owner, type, type.getDefaultSize(), title);
    }

    ////////////////////////////////////////////////////////////////////////////
    // Server icons

    public GlowServerIcon getServerIcon() {
        return defaultIcon;
    }

    public CachedServerIcon loadServerIcon(File file) throws Exception {
        return new GlowServerIcon(file);
    }

    public CachedServerIcon loadServerIcon(BufferedImage image) throws Exception {
        return new GlowServerIcon(image);
    }

    ////////////////////////////////////////////////////////////////////////////
    // Plugin messages

    public void sendPluginMessage(Plugin source, String channel, byte[] message) {
        StandardMessenger.validatePluginMessage(getMessenger(), source, channel, message);
        for (Player player : getOnlinePlayers()) {
            player.sendPluginMessage(source, channel, message);
        }
    }

    public Set<String> getListeningPluginChannels() {
        HashSet<String> result = new HashSet<>();
        for (Player player : getOnlinePlayers()) {
            result.addAll(player.getListeningPluginChannels());
        }
        return result;
    }

    ////////////////////////////////////////////////////////////////////////////
    // Configuration with special handling

    public GameMode getDefaultGameMode() {
        return defaultGameMode;
    }

    public void setDefaultGameMode(GameMode mode) {
        defaultGameMode = mode;
    }

    public int getSpawnRadius() {
        return spawnRadius;
    }

    public void setSpawnRadius(int value) {
        spawnRadius = value;
    }

    public boolean hasWhitelist() {
        return whitelistEnabled;
    }

    public void setWhitelist(boolean enabled) {
        whitelistEnabled = enabled;
        config.set(ServerConfig.Key.WHITELIST, whitelistEnabled);
        config.save();
    }

    public Warning.WarningState getWarningState() {
        return warnState;
    }

    public void setIdleTimeout(int timeout) {
        idleTimeout = timeout;
    }

    public int getIdleTimeout() {
        return idleTimeout;
    }

    public void configureDbConfig(com.avaje.ebean.config.ServerConfig dbConfig) {
        DataSourceConfig ds = new DataSourceConfig();
        ds.setDriver(config.getString(ServerConfig.Key.DB_DRIVER));
        ds.setUrl(config.getString(ServerConfig.Key.DB_URL));
        ds.setUsername(config.getString(ServerConfig.Key.DB_USERNAME));
        ds.setPassword(config.getString(ServerConfig.Key.DB_PASSWORD));
        ds.setIsolationLevel(TransactionIsolation.getLevel(config.getString(ServerConfig.Key.DB_ISOLATION)));

        if (ds.getDriver().contains("sqlite")) {
            dbConfig.setDatabasePlatform(new SQLitePlatform());
            dbConfig.getDatabasePlatform().getDbDdlSyntax().setIdentity("");
        }

        dbConfig.setDataSourceConfig(ds);
    }

    ////////////////////////////////////////////////////////////////////////////
    // Configuration

    public String getIp() {
        return config.getString(ServerConfig.Key.SERVER_IP);
    }

    public int getPort() {
        return port;
    }

    public String getServerName() {
        return config.getString(ServerConfig.Key.SERVER_NAME);
    }

    public String getServerId() {
        return Integer.toHexString(getServerName().hashCode());
    }

    public int getMaxPlayers() {
        return config.getInt(ServerConfig.Key.MAX_PLAYERS);
    }

    public String getUpdateFolder() {
        return config.getString(ServerConfig.Key.UPDATE_FOLDER);
    }

    public File getUpdateFolderFile() {
        return new File(getUpdateFolder());
    }

    public boolean getOnlineMode() {
        return config.getBoolean(ServerConfig.Key.ONLINE_MODE);
    }

    public boolean getAllowNether() {
        return config.getBoolean(ServerConfig.Key.ALLOW_NETHER);
    }

    public boolean getAllowEnd() {
        return config.getBoolean(ServerConfig.Key.ALLOW_END);
    }

    public int getViewDistance() {
        return config.getInt(ServerConfig.Key.VIEW_DISTANCE);
    }

    public String getMotd() {
        return config.getString(ServerConfig.Key.MOTD);
    }

    public File getWorldContainer() {
        return new File(config.getString(ServerConfig.Key.WORLD_FOLDER));
    }

    public String getWorldType() {
        return config.getString(ServerConfig.Key.LEVEL_TYPE);
    }

    public boolean getGenerateStructures() {
        return config.getBoolean(ServerConfig.Key.GENERATE_STRUCTURES);
    }

    public long getConnectionThrottle() {
        return config.getInt(ServerConfig.Key.CONNECTION_THROTTLE);
    }

    public int getTicksPerAnimalSpawns() {
        return config.getInt(ServerConfig.Key.ANIMAL_TICKS);
    }

    public int getTicksPerMonsterSpawns() {
        return config.getInt(ServerConfig.Key.MONSTER_TICKS);
    }

    public boolean isHardcore() {
        return config.getBoolean(ServerConfig.Key.HARDCORE);
    }

    public boolean useExactLoginLocation() {
        return config.getBoolean(ServerConfig.Key.EXACT_LOGIN_LOCATION);
    }

    public int getMonsterSpawnLimit() {
        return config.getInt(ServerConfig.Key.MONSTER_LIMIT);
    }

    public int getAnimalSpawnLimit() {
        return config.getInt(ServerConfig.Key.ANIMAL_LIMIT);
    }

    public int getWaterAnimalSpawnLimit() {
        return config.getInt(ServerConfig.Key.WATER_ANIMAL_LIMIT);
    }

    public int getAmbientSpawnLimit() {
        return config.getInt(ServerConfig.Key.AMBIENT_LIMIT);
    }

    public String getShutdownMessage() {
        return config.getString(ServerConfig.Key.SHUTDOWN_MESSAGE);
    }

    public boolean getAllowFlight() {
        return config.getBoolean(ServerConfig.Key.ALLOW_FLIGHT);
    }

    public int getMaxBuildHeight() {
        return Math.max(64, Math.min(256, config.getInt(ServerConfig.Key
                .MAX_BUILD_HEIGHT)));
    }
}
