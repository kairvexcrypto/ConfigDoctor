package ru.warndev.configdoctor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import org.bukkit.plugin.java.JavaPlugin;

public final class ConfigDoctorPlugin extends JavaPlugin {
    private DoctorService service;
    private DoctorCommand commands;

    @Override
    public void onEnable() {
        try {
            if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
                throw new IOException("Data directory unavailable");
            }
            saveIfAbsent("rules.yml");
            saveIfAbsent("example.yml");
            Path data = getDataFolder().toPath().toAbsolutePath().normalize();
            TargetFiles files = new TargetFiles(data.getParent());
            service = new DoctorService(files, data.getFileName() + "/rules.yml", data.resolve("reports"));
            commands = new DoctorCommand(this, service);
            var command = Objects.requireNonNull(getCommand("configdoctor"));
            command.setExecutor(commands);
            command.setTabCompleter(commands);
            getServer().getPluginManager().registerEvents(commands, this);
            getLogger().info("ConfigDoctor enabled; profiles: " + service.profiles().size());
        } catch (DoctorException error) {
            getLogger().severe(error.code() + ": " + error.getMessage() + " (" + error.line() + ":" + error.column() + ")");
            getServer().getPluginManager().disablePlugin(this);
        } catch (IOException | RuntimeException error) {
            getLogger().severe("ConfigDoctor initialization failed; check plugin files and permissions");
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void saveIfAbsent(String name) {
        if (!new java.io.File(getDataFolder(), name).exists()) {
            saveResource(name, false);
        }
    }

    @Override
    public void onDisable() {
        if (commands != null) {
            commands.stop();
        }
        if (service != null) {
            service.close();
        }
    }
}
