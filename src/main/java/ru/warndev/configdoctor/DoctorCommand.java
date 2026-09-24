package ru.warndev.configdoctor;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class DoctorCommand implements TabExecutor, Listener {
    private static final UUID CONSOLE = new UUID(0, 0);
    private final JavaPlugin plugin;
    private final DoctorService service;
    private final ReportVault vault = new ReportVault(Clock.systemUTC());
    private final Map<UUID, UUID> requests = new HashMap<>();
    private boolean stopped;

    public DoctorCommand(JavaPlugin plugin, DoctorService service) {
        this.plugin = plugin;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (stopped || !sender.hasPermission("configdoctor.use")) {
            say(sender, "Нет доступа", true);
            return true;
        }
        if (!(sender instanceof Player) && !(sender instanceof ConsoleCommandSender)) {
            say(sender, "Команда доступна игроку или консоли", true);
            return true;
        }
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        String permission = permission(action);
        if (permission != null && !sender.hasPermission(permission)) {
            say(sender, "Нет доступа: " + permission, true);
            return true;
        }
        UUID owner = owner(sender);
        switch (action) {
            case "help" -> say(sender, "/cdoc profiles | check <профиль> | checkall | report [страница] | reload | export", false);
            case "profiles" -> say(sender, "Профили: " + String.join(", ", service.profiles()), false);
            case "check", "checkall" -> {
                if (action.equals("check") ? args.length != 2 : args.length != 1) {
                    say(sender, "/cdoc check <профиль> или /cdoc checkall", true);
                    return true;
                }
                if (!available(sender, owner)) {
                    return true;
                }
                run(sender, owner, permission, service.check(action.equals("check") ? args[1] : null), reports -> {
                    vault.put(owner, reports);
                    long errors = reports.stream().mapToLong(CheckReport::errors).sum();
                    long warnings = reports.stream().mapToLong(CheckReport::warnings).sum();
                    say(sender, "Проверено файлов: " + reports.size() + "; ошибок: " + errors
                            + "; предупреждений: " + warnings + ". /cdoc report", errors > 0);
                });
            }
            case "report" -> {
                if (args.length > 2) {
                    say(sender, "/cdoc report [страница]", true);
                    return true;
                }
                try {
                    int page = args.length == 2 ? Integer.parseInt(args[1]) : 1;
                    show(sender, vault.get(owner), page);
                } catch (NumberFormatException error) {
                    say(sender, "Номер страницы должен быть целым числом", true);
                }
            }
            case "reload" -> {
                if (available(sender, owner)) {
                    run(sender, owner, permission, service.reload(), count -> {
                        vault.clear();
                        say(sender, "Правила загружены. Профилей: " + count, false);
                    });
                }
            }
            case "export" -> {
                List<CheckReport> reports = vault.get(owner);
                if (reports.isEmpty()) {
                    say(sender, "Сначала выполните проверку. Результаты хранятся 10 минут", true);
                } else if (available(sender, owner)) {
                    run(sender, owner, permission, service.export(reports), path ->
                            say(sender, "Отчёт: plugins/ConfigDoctor/reports/" + path.getFileName(), false));
                }
            }
            default -> say(sender, "Неизвестная команда. /cdoc help", true);
        }
        return true;
    }

    private boolean available(CommandSender sender, UUID owner) {
        if (requests.containsKey(owner)) {
            say(sender, "Предыдущая операция ещё выполняется", true);
            return false;
        }
        if (requests.size() >= 32) {
            say(sender, "Слишком много запросов; повторите позже", true);
            return false;
        }
        return true;
    }

    private <T> void run(CommandSender sender, UUID owner, String permission,
                         CompletableFuture<T> future, Consumer<T> success) {
        UUID request = UUID.randomUUID();
        requests.put(owner, request);
        say(sender, "Операция поставлена в очередь", false);
        future.whenComplete((value, error) -> {
            try {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (stopped || !request.equals(requests.get(owner))) {
                        return;
                    }
                    requests.remove(owner);
                    if (sender instanceof Player player && !player.isOnline()) {
                        return;
                    }
                    if (!sender.hasPermission("configdoctor.use") || !sender.hasPermission(permission)) {
                        return;
                    }
                    if (error != null) {
                        Throwable cause = error instanceof CompletionException ? error.getCause() : error;
                        if (cause instanceof DoctorException known) {
                            say(sender, known.code() + ": " + known.getMessage(), true);
                        } else {
                            say(sender, "Операция отменена или завершилась с ошибкой", true);
                        }
                    } else {
                        success.accept(value);
                    }
                });
            } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
            }
        });
    }

    private void show(CommandSender sender, List<CheckReport> reports, int page) {
        if (reports.isEmpty()) {
            say(sender, "Нет свежего отчёта. Выполните /cdoc check <профиль>", true);
            return;
        }
        List<String> lines = new ArrayList<>();
        for (CheckReport report : reports) {
            lines.add(report.profile() + " — " + report.file() + " — " + report.created()
                    + " — ошибок " + report.errors() + ", предупреждений " + report.warnings());
            for (var finding : report.findings()) {
                lines.add(finding.severity() + " " + printable(finding.path()) + " [" + finding.code()
                        + "] " + finding.message());
            }
            if (report.truncated()) {
                lines.add("Предел 256 замечаний; отчёт неполный");
            }
        }
        int pages = Math.max(1, (lines.size() + 7) / 8);
        if (page < 1 || page > pages) {
            say(sender, "Допустимые страницы: 1.." + pages, true);
            return;
        }
        say(sender, "Отчёт " + page + "/" + pages, false);
        int start = (page - 1) * 8;
        for (String line : lines.subList(start, Math.min(start + 8, lines.size()))) {
            say(sender, line, false);
        }
    }

    private static String printable(String value) {
        String clean = value.replaceAll("[\\p{Cntrl}\\p{Cf}§]", " ");
        return clean.length() > 160 ? clean.substring(0, 160) + "…" : clean;
    }

    private static String permission(String action) {
        return switch (action) {
            case "check", "checkall", "report" -> "configdoctor.check";
            case "reload" -> "configdoctor.reload";
            case "export" -> "configdoctor.export";
            default -> null;
        };
    }

    private static UUID owner(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : CONSOLE;
    }

    private static void say(CommandSender sender, String message, boolean error) {
        sender.sendMessage(Component.text("[ConfigDoctor] ", NamedTextColor.AQUA)
                .append(Component.text(message, error ? NamedTextColor.RED : NamedTextColor.GRAY)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("configdoctor.use")) {
            return List.of();
        }
        if (args.length == 1) {
            return List.of("help", "profiles", "check", "checkall", "report", "reload", "export").stream()
                    .filter(action -> permission(action) == null || sender.hasPermission(permission(action)))
                    .filter(action -> action.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("check") && sender.hasPermission("configdoctor.check")) {
            return service.profiles().stream().filter(id -> id.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID owner = event.getPlayer().getUniqueId();
        vault.remove(owner);
        requests.remove(owner);
    }

    public void stop() {
        stopped = true;
        requests.clear();
        vault.clear();
    }
}
