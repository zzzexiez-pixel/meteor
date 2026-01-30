package com.example.meteor;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class RadiationCommand implements CommandExecutor {
    private final MeteorController meteorController;

    public RadiationCommand(MeteorController meteorController) {
        this.meteorController = meteorController;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("check")) {
            sender.sendMessage("Использование: /radiation check");
            return true;
        }
        boolean sealed = meteorController.checkDome(sender);
        if (!sealed) {
            sender.sendMessage("Купол еще не завершен или радиации нет.");
        }
        return true;
    }
}
