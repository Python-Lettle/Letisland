package cn.lettle.letisland.ship;

import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 船桨加速监听器
 * 玩家骑船移动时，根据船桨等级渐进加速至最大速度上限
 */
public class ShipListener implements Listener {

    private final ShipManager shipManager;

    public ShipListener(@NotNull ShipManager shipManager) {
        this.shipManager = shipManager;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onVehicleMove(@NotNull VehicleMoveEvent event) {
        if (!(event.getVehicle() instanceof Boat boat)) return;
        List<Entity> passengers = boat.getPassengers();
        if (passengers.isEmpty() || !(passengers.get(0) instanceof Player player)) return;

        int oar = shipManager.getOarLevel(player.getUniqueId());
        if (oar <= 0) return;

        Vector vel = boat.getVelocity();
        double speed = vel.length();
        if (speed <= 0.05) return; // 船未在划动

        double max = shipManager.getOarMaxSpeedBase() + oar * shipManager.getOarMaxSpeedBonusPerLevel();
        if (speed >= max) return; // 已达速度上限

        // 在当前移动方向上施加增量，不覆盖玩家输入方向
        Vector dir = vel.clone().normalize();
        double boost = speed * oar * shipManager.getOarBoostPerLevel();
        double newSpeed = Math.min(speed + boost, max);
        boat.setVelocity(dir.multiply(newSpeed));
    }
}
