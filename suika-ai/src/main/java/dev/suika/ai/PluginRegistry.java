package dev.suika.ai;

import java.util.*;

/**
 * Discovers AgentPlugin and TrainerPlugin implementations via {@link ServiceLoader} (ROADMAP §XII).
 *
 * <p>Third-party plugins are registered by placing a
 * {@code META-INF/services/dev.suika.ai.AgentPlugin} file on the classpath
 * listing fully-qualified implementation class names, one per line.
 */
public final class PluginRegistry {

    private static final PluginRegistry INSTANCE = new PluginRegistry();

    private final Map<String, AgentPlugin>   agents   = new LinkedHashMap<>();
    private final Map<String, TrainerPlugin> trainers = new LinkedHashMap<>();

    private PluginRegistry() {
        for (AgentPlugin p : ServiceLoader.load(AgentPlugin.class)) {
            agents.put(p.id(), p);
        }
        for (TrainerPlugin t : ServiceLoader.load(TrainerPlugin.class)) {
            trainers.put(t.id(), t);
        }
    }

    public static PluginRegistry get() { return INSTANCE; }

    /** All registered agent plugins, in registration order. */
    public Collection<AgentPlugin> agents() { return Collections.unmodifiableCollection(agents.values()); }

    /** All registered trainer plugins, in registration order. */
    public Collection<TrainerPlugin> trainers() { return Collections.unmodifiableCollection(trainers.values()); }

    /** Look up an agent by its {@link AgentPlugin#id()}, or empty if not found. */
    public Optional<AgentPlugin> findAgent(String id) { return Optional.ofNullable(agents.get(id)); }

    /** Look up a trainer by its {@link TrainerPlugin#id()}, or empty if not found. */
    public Optional<TrainerPlugin> findTrainer(String id) { return Optional.ofNullable(trainers.get(id)); }

    /** Register a plugin at runtime (e.g. loaded from a JAR at startup). */
    public void registerAgent(AgentPlugin plugin) { agents.put(plugin.id(), plugin); }

    /** Register a trainer at runtime. */
    public void registerTrainer(TrainerPlugin trainer) { trainers.put(trainer.id(), trainer); }
}
